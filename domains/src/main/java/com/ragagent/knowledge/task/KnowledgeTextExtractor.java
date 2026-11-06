package com.ragagent.knowledge.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.contract.knowledge.rule.ParserEngineRules;
import com.ragagent.knowledge.client.DocReaderClient;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.domain.KnowledgeBaseChunkingConfig;
import com.ragagent.knowledge.domain.KnowledgeProcessingSpan;
import com.ragagent.knowledge.domain.span.SpanTracker;
import com.ragagent.knowledge.repository.KnowledgeBaseRepository;
import com.ragagent.knowledge.service.image.ExtractedImageStore;
import com.ragagent.knowledge.storage.TenantFileStorage;
import com.ragagent.knowledge.support.HtmlMainContent;
import com.ragagent.knowledge.support.ProcessOverridesResolver;
import com.ragagent.platform.knowledge.ImageRefRewriter;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 入库阶段一：取文本。
 *
 * <p>manual 直接读 {@code metadata.content}；文件经 docreader 解析，图片引用落盘并重写 URL，
 * 随后交给图片 OCR 在引用下方补文字。阶段 span 记录文件类型/名称与字符量；失败只收口 span，
 * 异常向上抛给 worker。</p>
 */
final class KnowledgeTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeTextExtractor.class);

    private final TenantFileStorage fileStorage;
    private final ExtractedImageStore extractedImageStore;
    private final DocReaderClient docReader;
    private final KnowledgeBaseRepository kbRepository;
    private final KnowledgeImageOcr imageOcr;
    private final SpanTracker spanTracker;

    KnowledgeTextExtractor(TenantFileStorage fileStorage,
            ExtractedImageStore extractedImageStore,
            DocReaderClient docReader,
            KnowledgeBaseRepository kbRepository,
            KnowledgeImageOcr imageOcr,
            SpanTracker spanTracker) {
        this.fileStorage = fileStorage;
        this.extractedImageStore = extractedImageStore;
        this.docReader = docReader;
        this.kbRepository = kbRepository;
        this.imageOcr = imageOcr;
        this.spanTracker = spanTracker;
    }

    String extract(Knowledge k, int attempt, String knowledgeId) throws Exception {
        if ("manual".equals(k.getType())) {
            String content = k.getMetadata() != null && k.getMetadata().hasNonNull("content")
                    ? k.getMetadata().get("content").asText() : "";
            return imageOcr.enrich(k, content, attempt, knowledgeId, resolveOcrEnabled(k));
        }
        SpanTracker.SpanHandle docSpan = beginStageSpan(attempt, knowledgeId,
                KnowledgeProcessingSpan.STAGE_DOC_READER,
                Map.of(
                        "file_type", k.getFileType() == null ? "" : k.getFileType(),
                        "file_name", k.getFileName() == null ? "" : k.getFileName()));
        String markdown;
        try {
            byte[] content = fileStorage.read(
                    k.getTenantId() == null ? 0L : k.getTenantId(), k.getFilePath());
            // 网页只送主体给解析器：导航/页头/页脚不进正文（抽取失败时退回原文）
            if ("html".equals(k.getFileType())) {
                content = HtmlMainContent.extract(content);
            }
            DocReaderClient.ParseResult parsed = docReader.read(
                    content, k.getFileName(), k.getFileType(), k.getTitle(),
                    resolveParserEngine(k));
            List<ImageRefRewriter.RefRow> rows = parsed.imageRefs() == null ? List.of()
                    : parsed.imageRefs().stream().map(r -> new ImageRefRewriter.RefRow(
                            r.originalRef(), r.filename(), r.mimeType(), r.imageData())).toList();
            markdown = extractedImageStore.storeAndRewrite(k, rows, parsed.markdown());
        } catch (RuntimeException e) {
            failStageSpan(docSpan, "DOCREADER_FAILED",
                    e.getMessage() == null ? e.toString() : e.getMessage(), e);
            throw e;
        }
        endStageSpan(docSpan, Map.of("chars", markdown.length()));
        return imageOcr.enrich(k, markdown, attempt, knowledgeId, resolveOcrEnabled(k));
    }

    /**
     * KB 级「图片 OCR」开关：{@code imageProcessingConfig.ocrEnabled}，缺省开启。
     *
     * <p>只有显式 false 才关闭；KB 读不到（含知识库已删）时按开启处理，与产品默认一致。</p>
     */
    boolean resolveOcrEnabled(Knowledge k) {
        // 逐文档覆盖优先（上传/重解析时带过来的 imageProcessingConfig.ocrEnabled）
        Boolean override = ocrOverride(k);
        if (override != null) {
            return override;
        }
        String kbId = k.getKnowledgeBaseId();
        if (kbId == null || kbId.isBlank()) {
            return true;
        }
        try {
            KnowledgeBase kb = kbRepository.findByIdOnly(kbId);
            if (kb == null || kb.getImageProcessingConfig() == null) {
                return true;
            }
            return kb.getImageProcessingConfig().isOcrEnabled();
        } catch (RuntimeException e) {
            log.warn("resolve ocr switch failed for knowledge {}", k.getId(), e);
            return true;
        }
    }

    /** {@code metadata.processOverrides.imageProcessingConfig.ocrEnabled}，没有则 null。 */
    private Boolean ocrOverride(Knowledge k) {
        JsonNode metadata = k.getMetadata();
        if (metadata == null || !metadata.isObject()) {
            return null;
        }
        JsonNode value = metadata.path("processOverrides")
                .path("imageProcessingConfig")
                .path("ocrEnabled");
        return value.isBoolean() ? value.booleanValue() : null;
    }

    /**
     * KB 级 {@code parserEngineRules} → 引擎名；未配置/不命中返回空（docreader 默认路由）。
     *
     * <p>这是该配置的唯一消费点。</p>
     */
    String resolveParserEngine(Knowledge k) {
        // 逐文档规则优先（上传/重解析弹窗里按文件类型指定的引擎）
        JsonNode docRules = ProcessOverridesResolver.parserEngineRules(k);
        if (docRules != null) {
            String fileType = ParserEngineRules.normalize(k.getFileType());
            for (JsonNode rule : docRules) {
                JsonNode types = rule.path("fileTypes");
                if (!types.isArray() || !rule.path("engine").isTextual()) {
                    continue;
                }
                for (JsonNode candidate : types) {
                    if (ParserEngineRules.normalize(candidate.asText("")).equals(fileType)) {
                        return rule.path("engine").asText().trim();
                    }
                }
            }
            // 覆盖里没命中：回落到 KB 规则（上传弹窗默认会带一份 KB 快照，
            // 未命中即视为"这份覆盖没管这个类型"，而不是把引擎清空）
        }
        String kbId = k.getKnowledgeBaseId();
        if (kbId == null || kbId.isBlank()) {
            return "";
        }
        try {
            KnowledgeBase kb = kbRepository.findByIdOnly(kbId);
            if (kb == null) {
                return "";
            }
            List<KnowledgeBaseChunkingConfig.ParserEngineRule> rules =
                    kb.getChunkingConfig().getParserEngineRules();
            if (rules == null || rules.isEmpty()) {
                return "";
            }
            String fileType = ParserEngineRules.normalize(k.getFileType());
            for (KnowledgeBaseChunkingConfig.ParserEngineRule rule : rules) {
                if (rule.getFileTypes() == null || rule.getEngine() == null) {
                    continue;
                }
                for (String candidate : rule.getFileTypes()) {
                    if (ParserEngineRules.normalize(candidate).equals(fileType)) {
                        return rule.getEngine().trim();
                    }
                }
            }
            return "";
        } catch (RuntimeException e) {
            log.warn("resolve parser engine failed for knowledge {}", k.getId(), e);
            return "";
        }
    }

    private SpanTracker.SpanHandle beginStageSpan(int attempt, String knowledgeId,
            String stage, Map<String, Object> input) {
        if (attempt <= 0) {
            return null;
        }
        return spanTracker.beginStage(knowledgeId, attempt, stage, input);
    }

    private void endStageSpan(SpanTracker.SpanHandle span, Map<String, Object> output) {
        if (span != null) {
            spanTracker.endSpan(span, output);
        }
    }

    private void failStageSpan(SpanTracker.SpanHandle span, String code, String message,
            Throwable error) {
        if (span != null) {
            spanTracker.failSpan(span, code, message, error);
        }
    }
}
