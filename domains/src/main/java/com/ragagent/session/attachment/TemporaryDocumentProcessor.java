package com.ragagent.session.attachment;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.contract.doc.DocReaderParsePort;
import com.ragagent.contract.knowledge.DocumentChunkingPort;
import com.ragagent.contract.knowledge.rule.ParserEngineRules;
import com.ragagent.contract.model.AsrTranscriber;
import com.ragagent.contract.model.ModelCatalogPort.ModelRowView;
import com.ragagent.contract.model.ModelRuntime;
import com.ragagent.contract.tenant.TenantConfigLookup;
import com.ragagent.contract.text.Tokens;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.platform.knowledge.ImageRefRewriter;
import com.ragagent.platform.web.JsonMappers;
import com.ragagent.session.attachment.TemporaryDocumentService.CreateOptions;
import com.ragagent.session.domain.document.TemporaryDocument;
import com.ragagent.session.repository.TemporaryDocumentRepository;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code TemporaryDocumentService} 的**解析 / 落盘管线子模块**（步骤 2）：异步投递与重试、
 * docreader → chunker（auto/1600/160）→ ApproxTokenCount → MarkReady、音频转写、
 * 文本与图片落盘（chunks jsonb / image_refs jsonb / markdown 内联图替换）。
 *
 * <p>为什么单独一类：这条管线自成一条单向流（投递 → 取行 → 解析 → 落库 → 标记 ready），
 * 与门面的 HTTP 面（create/get/list/delete/openFile）、生命周期（cleanup ticker）与
 * 提示词渲染子模块互不依赖。门面保留 {@code processNow} 薄委托（流程契约测试的驱动口）。</p>
 *
 * <p>共享项处置（口径）：{@code MAPPER}/{@code readJsonArray}/{@code extOf}/
 * {@code isImageFormat} 门面也在用（删除/入 KB/附件提示词路径）→ 留门面，本类按类名引用；
 * {@code CreateOptions} 是门面的公开 record → 本类 import 其嵌套类型。</p>
 */
final class TemporaryDocumentProcessor {

    private static final Logger log = LoggerFactory.getLogger(TemporaryDocumentProcessor.class);

    /** 文本扩展名白名单（带点）。 */
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            ".md", ".markdown", ".txt", ".csv", ".json", ".xml", ".yaml", ".yml", ".log");

    private final TemporaryDocumentRepository repo;
    private final AttachmentFileStore fileStore;
    private final DocReaderParsePort docReader;
    private final ModelRuntime modelRuntimeAssembler;
    private final AsrTranscriber asrTranscriber;
    private final TenantConfigLookup tenantService;
    private final DocumentChunkingPort documentChunking;

    TemporaryDocumentProcessor(TemporaryDocumentRepository repo,
                               AttachmentFileStore fileStore,
                               DocReaderParsePort docReader,
                               ModelRuntime modelRuntimeAssembler,
                               AsrTranscriber asrTranscriber,
                               TenantConfigLookup tenantService,
                                       DocumentChunkingPort documentChunking) {
        this.repo = repo;
        this.fileStore = fileStore;
        this.docReader = docReader;
        this.modelRuntimeAssembler = modelRuntimeAssembler;
        this.asrTranscriber = asrTranscriber;
        this.tenantService = tenantService;
        this.documentChunking = documentChunking;
    }

    private static final int CHUNK_SIZE = 1600;
    private static final int CHUNK_OVERLAP = 160;
    /** 解析任务的单线程 executor（任务串行消费）。 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "temporary-document-worker");
        t.setDaemon(true);
        return t;
    });
    /** 音频扩展名（无点，不含 aac）。 */
    private static final Set<String> AUDIO_FORMAT_EXTENSIONS =
            Set.of("mp3", "wav", "m4a", "flac", "ogg");
    /** 任务入队：进程内 executor 投递，失败直接 {@code markFailed}。 */
    void enqueueProcess(long tenantId, String documentId) {
        try {
            executor.submit(() -> processWithRetry(tenantId, documentId));
        } catch (RuntimeException e) {
            log.error("schedule attachment parsing failed: document={}", documentId, e);
            repo.markFailed(tenantId, documentId, "failed to schedule document parsing");
        }
    }
    /** 解析异常最多重试 2 次，仍失败落终态 failed。 */
    private void processWithRetry(long tenantId, String documentId) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                process(tenantId, documentId);
                return;
            } catch (RuntimeException e) {
                log.warn("temporary document parse attempt {} failed: document={}", attempt + 1, documentId, e);
            }
        }
    }
    /** 供测试直调（绕过 executor 的时序）。 */
    void processNow(long tenantId, String documentId) {
        process(tenantId, documentId);
    }
    private void process(long tenantId, String documentId) {
        TemporaryDocument document = repo.getById(tenantId, documentId);
        if (document == null || TemporaryDocument.STATUS_READY.equals(document.getStatus())) {
            return;
        }
        repo.markProcessing(tenantId, documentId, OffsetDateTime.now(ZoneId.systemDefault()));

        String content;
        String imageRefs;
        Map<String, String> metadata;
        try {
            CreateOptions options = optionsOf(document);
            // 资源租户：共享 agent 的解析依赖范围；文档行仍属上传方
            long resourceTenantId = options.resourceTenantId() != 0 ? options.resourceTenantId()
                    : tenantId;
            byte[] data = fileStore.getFile(document.getResourceRef());
            String ext = document.getFileType();
            String extNoDot = ext.startsWith(".") ? ext.substring(1) : ext;
            String engine = options.parserEngine();
            if (engine.isEmpty() || "auto".equals(engine)) {
                // 租户级规则兜底（未显式指定时用租户配置解析）
                engine = tenantParserEngine(resourceTenantId, ext);
            }
            if (TEXT_EXTENSIONS.contains(ext) && (engine.isEmpty() || engine.equals("auto"))) {
                content = new String(data, StandardCharsets.UTF_8);
                metadata = Map.of("parser", "plain_text");
                // 空列表写入 jsonb 的是 4 字节 "null" 字面量（不是 SQL NULL，
                // golden 实测读回渲染 null）——H2 列 NOT NULL，必须写字符串 "null"
                imageRefs = "null";
            } else if (AUDIO_FORMAT_EXTENSIONS.contains(extNoDot)) {
                // 音频：ASR 转写
                content = transcribeAudio(resourceTenantId, options.asrModelId(), data,
                        document.getFileName());
                metadata = Map.of("parser", "asr");
                imageRefs = "null";
            } else {
                // 传给 docreader 的 fileType 去掉点
                DocReaderParsePort.ParsedDocumentView parsed = docReader.parse(data, document.getFileName(),
                        extNoDot, document.getFileName(), "auto".equals(engine) ? "" : engine);
                // docreader
                // 直出的 inline ImageRef 落盘并把 markdown 引用改写成可服务 URL；列表写进
                // image_refs jsonb，供 ResolveForPrompt 提炼给 vision 模型。
                StoredImages stored = storeDocumentImages(tenantId, document, parsed.imageRefs(),
                        parsed.markdown());
                content = stored.markdown();
                metadata = new LinkedHashMap<>();
                metadata.put("parser", engine.isEmpty() ? "document_reader" : engine);
                imageRefs = stored.imageRefsJson();
            }
        } catch (Exception parseErr) {
            String message = String.valueOf(parseErr.getMessage());
            if (message.length() > 2000) {
                message = message.substring(0, 2000);
            }
            repo.markFailed(tenantId, documentId, message);
            log.error("temporary document parse failed: document={} {}", documentId, message);
            return;
        }

        content = cleanInvalidUtf8(content);
        String lang = Tokens.detectLanguage(content);
        List<DocumentChunkingPort.ChunkedPart> parts = documentChunking.split(content,
                new DocumentChunkingPort.SplitterConfigView(CHUNK_SIZE, CHUNK_OVERLAP,
                        DocumentChunkingPort.STRATEGY_AUTO));
        repo.markReady(tenantId, documentId, content, chunksJson(parts, lang), imageRefs,
                quoteMap(metadata), Tokens.approxTokenCount(content, lang),
                parts.size(), OffsetDateTime.now(ZoneId.systemDefault()));
    }
    /** 读回 processing_options（键名＝Java 字段名，与 {@code CreateOptions.toJson} 同源）。 */
    static CreateOptions optionsOf(TemporaryDocument document) {
        try {
            com.fasterxml.jackson.databind.JsonNode node = TemporaryDocumentService.MAPPER.readTree(
                    document.getProcessingOptions() == null ? "{}" : document.getProcessingOptions());
            return new CreateOptions(node.path("parserEngine").asText(""),
                    node.path("resourceTenantId").asLong(0),
                    node.path("asrModelId").asText(""),
                    node.path("vlmModelId").asText(""),
                    node.path("imageUnderstanding").asBoolean(false),
                    node.path("ocrMaxPages").asInt(0));
        } catch (Exception e) {
            return CreateOptions.empty();
        }
    }
    /** 未显式指定 engine 时用资源租户的 chat 解析规则兜底。 */
    private String tenantParserEngine(long resourceTenantId, String ext) {
        try {
            JsonNode cfg = JsonMappers.toJsonNode(tenantService.parserEngineConfig(resourceTenantId));
            if (cfg == null) {
                return "";
            }
            return ParserEngineRules.resolve(
                    JsonMappers.toJavaValue(cfg.get("chatParserEngineRules")), ext);
        } catch (RuntimeException e) {
            log.warn("failed to resolve tenant parser engine", e);
            return "";
        }
    }
    /** 音频分支：取 ASR 模型 → 转写；错误文案带固定前缀。 */
    private String transcribeAudio(long resourceTenantId, String asrModelId, byte[] data,
            String fileName) {
        if (asrModelId.isEmpty()) {
            throw new RuntimeException("audio transcription model is not configured");
        }
        return withResourceTenant(resourceTenantId, () -> {
            ModelRowView model;
            try {
                model = modelRuntimeAssembler.getAsrModel(asrModelId);
            } catch (RuntimeException e) {
                throw new RuntimeException("load ASR model: " + e.getMessage(), e);
            }
            var p = model.parameters();
            // language 不从模型来（恒空），customHeaders 透传
            var config = new AsrTranscriber.AsrConfig(p == null ? "" : p.getBaseUrl(),
                    model.name(), p == null ? "" : p.getApiKey(), model.id(), "",
                    p == null ? null : p.getCustomHeaders());
            try {
                return asrTranscriber.transcribe(config, data, fileName).text();
            } catch (RuntimeException e) {
                throw new RuntimeException("transcribe audio: " + e.getMessage(), e);
            }
        });
    }
    /**
     * 临时把线程租户切到资源租户：ASR 模型的
     * 可见性按共享来源空间解析，调用后恢复原值。
     */
    private <T> T withResourceTenant(long resourceTenantId, java.util.function.Supplier<T> body) {
        Long previous = TenantContext.currentTenantId();
        if (previous != null && previous == resourceTenantId) {
            return body.get();
        }
        TenantContext.set(resourceTenantId,
                TenantContext.currentPrincipal(),
                TenantContext.currentRole(),
                TenantContext.isSystemAdmin(),
                TenantContext.currentUserId(),
                TenantContext.canAccessAllTenants());
        try {
            return body.get();
        } finally {
            TenantContext.set(previous,
                    TenantContext.currentPrincipal(),
                    TenantContext.currentRole(),
                    TenantContext.isSystemAdmin(),
                    TenantContext.currentUserId(),
                    TenantContext.canAccessAllTenants());
        }
    }
    /** {@link #storeDocumentImages} 的结果：重写引用后的 markdown + image_refs jsonb。 */
    record StoredImages(String markdown, String imageRefsJson) {
    }
    /**
     * 处理 docreader 直出 {@code ImageRefs}：落盘 + 改写 markdown 引用。
     *
     * <p>只处理带内联字节的引用（聊天附件的唯一来源：docreader 的 ImageParser 产
     * {@code ![](images/x.png)} 并恒填 image_data）；data URI / HTML data URI /
     * bare base64 / 相对路径 HTML 四路不在范围内。</p>
     */
    StoredImages storeDocumentImages(long tenantId, TemporaryDocument document,
            List<DocReaderParsePort.ImageRefView> refs, String markdown) {
        if (refs == null || refs.isEmpty()) {
            return new StoredImages(markdown, "[]");
        }
        List<ImageRefRewriter.RefRow> rows = new ArrayList<>(refs.size());
        for (DocReaderParsePort.ImageRefView ref : refs) {
            rows.add(new ImageRefRewriter.RefRow(ref.originalRef(), ref.filename(),
                    ref.mimeType(), ref.imageData()));
        }
        // 来源文件本身是图片格式时保留原图（不做小图标过滤）
        ImageRefRewriter.Result result = ImageRefRewriter.rewrite(markdown, rows,
                !TemporaryDocumentService.isImageFormat(document.getFileType()),
                (data, fileName) -> fileStore.saveBytes(data, tenantId, fileName));
        List<String> jsonItems = new ArrayList<>(result.stored().size());
        for (ImageRefRewriter.StoredRef stored : result.stored()) {
            jsonItems.add("{\"originalRef\":" + quote(stored.originalRef())
                    + ",\"url\":" + quote(stored.servingRef())
                    + ",\"mimeType\":" + quote(stored.mimeType() == null ? "" : stored.mimeType())
                    + "}");
        }
        return new StoredImages(result.markdown(), "[" + String.join(",", jsonItems) + "]");
    }
    /** 替换非法 UTF-8 序列（REPLACE 语义）。 */
    static String cleanInvalidUtf8(String value) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .decode(java.nio.ByteBuffer.wrap(value.getBytes(StandardCharsets.UTF_8)))
                .toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return value;
        }
    }
    /** chunks jsonb（元素形态＝`DocumentChunk` 的字段名，contextHeader 空时不写）。 */
    private static String chunksJson(List<DocumentChunkingPort.ChunkedPart> parts, String lang) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (DocumentChunkingPort.ChunkedPart part : parts) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"seq\":").append(part.seq())
                    .append(",\"content\":").append(quote(part.content()));
            if (!part.contextHeader().isEmpty()) {
                sb.append(",\"contextHeader\":").append(quote(part.contextHeader()));
            }
            sb.append(",\"start\":").append(part.start())
                    .append(",\"end\":").append(part.end())
                    .append(",\"tokenCount\":")
                    .append(Tokens.approxTokenCount(part.embeddingContent(), lang))
                    .append('}');
        }
        return sb.append(']').toString();
    }
    private static String quote(String value) {
        try {
            return TemporaryDocumentService.MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return "\"\"";
        }
    }
    private static String quoteMap(Map<String, String> map) {
        try {
            return TemporaryDocumentService.MAPPER.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }
}
