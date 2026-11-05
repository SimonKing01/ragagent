package com.ragagent.knowledge.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.knowledge.config.KnowledgeOcrProperties;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeProcessingSpan;
import com.ragagent.knowledge.domain.span.SpanTracker;
import com.ragagent.knowledge.storage.TenantFileStorage;
import com.ragagent.knowledge.support.ImageOcrImage;
import com.ragagent.platform.knowledge.ChunkSearchUtil;
import com.ragagent.platform.text.ContentTypeByFilename;
import com.ragagent.platform.web.JsonMappers;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 入库图片 OCR：把正文中已落盘的图片送外部 OCR 服务，识别文字插回图片引用下方。
 *
 * <p>只处理 {@code exports} 区的引用（docreader 抽出的页面图与内嵌图），用户手写的外链
 * 不搬运、不识别。识别结果作为普通段落进正文，随后由正常切分进入检索——不写子分块，
 * 因此没有额外的向量化/收尾步骤。</p>
 *
 * <p>失败按单图降级：读图/请求/解析任一失败只记警告并跳过，整篇解析仍继续。</p>
 */
final class KnowledgeImageOcr {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeImageOcr.class);

    private static final String EXPORTS_SEGMENT = "/exports/";
    private static final String OCR_PATH = "/ocr";
    /** 单张图识别文本上限，防止异常输出把正文撑爆。 */
    private static final int MAX_TEXT_CHARS = 20_000;
    private static final String EMPTY_MARKER = "no text content";

    private final TenantFileStorage fileStorage;
    private final KnowledgeOcrProperties properties;
    private final SpanTracker spanTracker;
    private final ObjectMapper mapper = JsonMappers.lenient();
    private final HttpClient http;
    /** 全局并发门：跨文档共享，避免批量上传时 N 篇文档同时打爆 OCR 服务。 */
    private final Semaphore ocrGate;

    KnowledgeImageOcr(TenantFileStorage fileStorage, KnowledgeOcrProperties properties,
            SpanTracker spanTracker) {
        this.fileStorage = fileStorage;
        this.properties = properties;
        this.spanTracker = spanTracker;
        this.ocrGate = new Semaphore(
                Math.max(1, properties.concurrencyOrDefault()), true);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * 识别正文中的图片并把文字插回引用下方；返回改写后的正文。
     *
     * <p>未启用、无图片、全部识别失败时原样返回。</p>
     */
    String enrich(Knowledge k, String markdown, int attempt, String knowledgeId) {
        return enrich(k, markdown, attempt, knowledgeId, true);
    }

    /**
     * @param kbOcrEnabled 知识库级开关（缺省开启）；false 时整篇跳过，不再看全局 endpoint。
     */
    String enrich(Knowledge k, String markdown, int attempt, String knowledgeId,
                  boolean kbOcrEnabled) {
        if (!kbOcrEnabled) {
            skipSpan(attempt, knowledgeId, "kb-disabled");
            return markdown;
        }
        if (!properties.enabled()) {
            skipSpan(attempt, knowledgeId, "disabled");
            return markdown;
        }
        if (markdown == null || markdown.isEmpty()) {
            skipSpan(attempt, knowledgeId, "empty-content");
            return markdown;
        }
        List<String> refs = imageRefs(markdown);
        if (refs.isEmpty()) {
            skipSpan(attempt, knowledgeId, "no-images");
            return markdown;
        }
        int limit = Math.min(refs.size(), properties.maxPagesOrDefault());
        List<String> selected = refs.subList(0, limit);
        if (refs.size() > limit) {
            log.info("[ImageOcr] knowledge={} images={} exceeds maxPages={}, rest skipped",
                    knowledgeId, refs.size(), limit);
        }

        SpanTracker.SpanHandle span = beginSpan(attempt, knowledgeId,
                Map.of("images", selected.size(), "endpoint", properties.baseUrl()));
        long startedAt = System.nanoTime();
        List<String> texts;
        try {
            texts = recognizeAll(k, selected, knowledgeId);
        } finally {
            endSpan(span, startedAt);
        }

        String out = markdown;
        int recognized = 0;
        int addedChars = 0;
        for (int i = 0; i < selected.size(); i++) {
            String text = texts.get(i);
            if (text == null || text.isEmpty()) {
                continue;
            }
            out = insertAfterImage(out, selected.get(i), text);
            recognized++;
            addedChars += text.length();
        }
        log.info("[ImageOcr] knowledge={} images={} recognized={} chars_added={} elapsedMs={}",
                knowledgeId, selected.size(), recognized, addedChars,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
        return out;
    }

    /** 正文中 exports 区的图片引用（保持出现顺序、去重）。 */
    private static List<String> imageRefs(String markdown) {
        Set<String> refs = new LinkedHashSet<>();
        for (String ref : ChunkSearchUtil.imageURLsInContent(markdown)) {
            if (ref != null && ref.contains(EXPORTS_SEGMENT)) {
                refs.add(ref);
            }
        }
        return new ArrayList<>(refs);
    }

    /** 并发识别；返回与 refs 同序的文本列表，失败位为 null。 */
    private List<String> recognizeAll(Knowledge k, List<String> refs, String knowledgeId) {
        long tenantId = k.getTenantId() == null ? 0L : k.getTenantId();
        List<String> texts = new ArrayList<>(refs.size());
        int concurrency = Math.min(properties.concurrencyOrDefault(), refs.size());
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, concurrency));
        try {
            List<Future<String>> futures = new ArrayList<>(refs.size());
            for (String ref : refs) {
                futures.add(pool.submit(() -> recognizeOne(tenantId, ref, knowledgeId)));
            }
            for (Future<String> future : futures) {
                try {
                    texts.add(future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    texts.add(null);
                    break;
                } catch (Exception e) {
                    log.warn("[ImageOcr] recognize failed", e);
                    texts.add(null);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return texts;
    }

    /** 单张图：读字节 → POST /ocr → 取 text；异常一律折成 null。 */
    private String recognizeOne(long tenantId, String ref, String knowledgeId) {
        boolean acquired = false;
        try {
            // 全局排队：并发上限由 knowledge.ocr.concurrency 控制（跨文档）
            acquired = ocrGate.tryAcquire(properties.timeoutSecondsOrDefault(), TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("[ImageOcr] knowledge={} image={} queue timeout", knowledgeId, ref);
                return null;
            }
            byte[] bytes = fileStorage.read(tenantId, ref);
            if (bytes == null || bytes.length == 0) {
                return null;
            }
            // 大图先等比缩小：控制 OCR 服务的单次推理内存与耗时
            bytes = ImageOcrImage.limitMaxSide(bytes, properties.maxImageSideOrDefault());
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(properties.baseUrl() + OCR_PATH))
                    .timeout(Duration.ofSeconds(properties.timeoutSecondsOrDefault()))
                    .header("Content-Type", ContentTypeByFilename.getByExt(extOf(ref)))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                    .build();
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                log.warn("[ImageOcr] knowledge={} image={} HTTP {}", knowledgeId, ref,
                        response.statusCode());
                return null;
            }
            JsonNode body = mapper.readTree(response.body());
            return sanitize(body.path("text").asText(""));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("[ImageOcr] knowledge={} image={} failed", knowledgeId, ref, e);
            return null;
        } finally {
            if (acquired) {
                ocrGate.release();
            }
        }
    }

    /** 去空标记、压缩多余空行、截断超长输出。 */
    private static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.strip();
        if (text.isEmpty() || EMPTY_MARKER.equalsIgnoreCase(text)) {
            return "";
        }
        text = text.replaceAll("\\n{3,}", "\n\n");
        if (text.length() > MAX_TEXT_CHARS) {
            text = text.substring(0, MAX_TEXT_CHARS);
        }
        return text;
    }

    /** 在图片引用的第一处出现位置之后插入识别文字。 */
    private static String insertAfterImage(String markdown, String ref, String text) {
        String needle = "](" + ref + ")";
        int index = markdown.indexOf(needle);
        if (index < 0) {
            return markdown;
        }
        String insertion = needle + "\n\n（图内文字）\n" + text + "\n";
        return markdown.substring(0, index) + insertion
                + markdown.substring(index + needle.length());
    }

    private static String extOf(String ref) {
        String path = ref == null ? "" : ref;
        int slash = path.lastIndexOf('/');
        String name = slash < 0 ? path : path.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot);
    }

    private SpanTracker.SpanHandle beginSpan(int attempt, String knowledgeId,
            Map<String, Object> input) {
        if (attempt <= 0) {
            return null;
        }
        return spanTracker.beginStage(knowledgeId, attempt,
                KnowledgeProcessingSpan.STAGE_MULTIMODAL, input);
    }

    private void endSpan(SpanTracker.SpanHandle span, long startedAt) {
        if (span != null) {
            spanTracker.endSpan(span, Map.of("durationMs",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)));
        }
    }

    /** 无 begin 记录时先合成一行再 skip（保 schema 不变量）。 */
    private void skipSpan(int attempt, String knowledgeId, String reason) {
        if (attempt <= 0) {
            return;
        }
        SpanTracker.SpanHandle span = spanTracker.lookupStage(knowledgeId, attempt,
                KnowledgeProcessingSpan.STAGE_MULTIMODAL);
        if (span == null) {
            span = spanTracker.beginStage(knowledgeId, attempt,
                    KnowledgeProcessingSpan.STAGE_MULTIMODAL, null);
        }
        if (span != null) {
            spanTracker.skipSpan(span, reason);
        }
    }
}
