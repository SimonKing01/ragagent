package com.ragagent.knowledge.service.management;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.contract.error.AppError;
import com.ragagent.contract.error.BizException;
import com.ragagent.contract.knowledge.KnowledgeIngest;
import com.ragagent.contract.storage.fileserve.OpenedFile;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.domain.KnowledgeTenants;
import com.ragagent.knowledge.index.ChunkVectorIndexer;
import com.ragagent.knowledge.repository.ChunkRepository;
import com.ragagent.knowledge.repository.KnowledgeRepository;
import com.ragagent.knowledge.service.KnowledgeAccessHelper;
import com.ragagent.knowledge.storage.LocalStorageService;
import com.ragagent.knowledge.storage.TenantFileStorage;
import com.ragagent.knowledge.support.KnowledgePaths;
import com.ragagent.platform.security.InputSanitizer;
import com.ragagent.platform.text.CleanInvalidUtf8;
import com.ragagent.platform.web.JsonMappers;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 知识文件/行写面：manual 知识更新（全列写语义）、文件下载流、图片信息更新与
 * 图片子块维护。
 */
@Service
public class KnowledgeFileService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeFileService.class);
    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    private final ChunkVectorIndexer chunkVectorIndexer;
    private final KnowledgeAccessHelper access;
    private final KnowledgeFolderService folderService;
    private final TenantFileStorage fileStorage;
    private final KnowledgeRepository knowledgeRepository;
    private final ChunkRepository chunkRepository;
    private final KnowledgeIngest knowledgeIngest;

    public KnowledgeFileService(
                            KnowledgeRepository knowledgeRepository,
                            ChunkRepository chunkRepository,
                            ChunkVectorIndexer chunkVectorIndexer,
                            KnowledgeAccessHelper access,
                            KnowledgeFolderService folderService,
                            TenantFileStorage fileStorage,
                            KnowledgeIngest knowledgeIngest) {
        this.chunkRepository = chunkRepository;
        this.chunkVectorIndexer = chunkVectorIndexer;
        this.access = access;
        this.folderService = folderService;
        this.fileStorage = fileStorage;
        this.knowledgeRepository = knowledgeRepository;
        this.knowledgeIngest = knowledgeIngest;
    }

    public Knowledge updateManualKnowledge(String id, String title, String content,
                                           String status, String channel) {
        if (content == null && title == null && status == null && channel == null) {
            throw BizException.badRequest("请求内容不能为空");
        }
        String cleanContent = InputSanitizer.cleanMarkdown(content == null ? "" : content);
        if (cleanContent.trim().isEmpty()) {
            throw new BizException(AppError.validation("内容不能为空"));
        }
        if (cleanContent.length() > 200000) {
            throw new BizException(AppError.validation("内容长度超出限制（最多200000个字符）"));
        }
        String safeTitle = InputSanitizer.validateInput(title == null ? "" : title);
        if (safeTitle == null) {
            throw new BizException(AppError.validation("标题包含非法字符或超出长度限制"));
        }
        String normalizedStatus = status == null ? "" : status.trim().toLowerCase();
        if (normalizedStatus.isEmpty()) {
            normalizedStatus = "draft";
        }
        if (!"draft".equals(normalizedStatus) && !"publish".equals(normalizedStatus)) {
            throw new BizException(AppError.validation("状态仅支持 draft 或 publish"));
        }

        Knowledge existing = folderService.loadKnowledgeWrite(id);
        if (!"manual".equals(existing.getType())) {
            throw BizException.badRequest("仅支持手工知识的在线编辑");
        }
        KnowledgeBase kb = access.requireKb(existing.getKnowledgeBaseId());

        int version = 1;
        JsonNode oldMeta = existing.getMetadata();
        if (oldMeta != null && oldMeta.hasNonNull("version")) {
            version = oldMeta.path("version").asInt(0) + 1;
            if (version <= 1) {
                version = 1;
            }
        }
        ObjectNode meta = MAPPER.createObjectNode();
        meta.put("content", cleanContent);
        meta.put("format", "markdown");
        meta.put("status", normalizedStatus);
        meta.put("version", version);
        meta.put("updated_at", OffsetDateTime.now(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.SECONDS)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")));

        if (!safeTitle.isEmpty()) {
            existing.setTitle(safeTitle);
        } else if (existing.getTitle() == null || existing.getTitle().isEmpty()) {
            existing.setTitle("手工知识-" + DateTimeFormatter
                    .ofPattern("yyyyMMdd-HHmmss").format(OffsetDateTime.now()));
        }
        existing.setFileName(KnowledgePaths.ensureManualFileName(existing.getTitle()));
        existing.setFileType("manual");
        existing.setType("manual");
        existing.setSource("manual");
        existing.setEnableStatus("disabled");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        existing.setUpdatedAt(now);
        existing.setEmbeddingModelId(kb.getEmbeddingModelId());

        if ("draft".equals(normalizedStatus)) {
            existing.setParseStatus("draft");
            existing.setDescription("");
            existing.setProcessedAt(null);
          updateKnowledgeRow(existing, meta);
            existing.setMetadata(meta);
            return existing;
        }

        // Publish：pending + 异步清理重建
        existing.setParseStatus("pending");
        existing.setDescription("");
        existing.setProcessedAt(null);
        updateKnowledgeRow(existing, meta);
        existing.setMetadata(meta);
        knowledgeIngest.enqueueProcessing(existing.getId());
        return existing;
    }


    /**
     * 全列写（DeletedAt/PendingSubtasksCount 除外）：
     * description=""/processed_at=NULL 这类零值也必须落库，不能走 MP 默认的跳空列。
     */
    /** 语句体已归位 {@code repository.KnowledgeRepository#updateRow}，此处保留薄委托（调用方不变）。 */
    public void updateKnowledgeRow(Knowledge k, JsonNode metadata) {
        knowledgeRepository.updateRow(k, metadata);
    }

    /** 下载文件名清洗：换行/制表删除、斜杠转连字符、引号转单引号、
     *  空白回落 untitled、补 .md 后缀。 */
    public static String sanitizeManualDownloadFilename(String title) {
        String safeName = title == null ? "" : title
                .replace("\n", "").replace("\r", "").replace("\t", "")
                .replace("/", "-").replace("\\", "-").replace("\"", "'");
        if (safeName.trim().isEmpty()) {
            safeName = "untitled";
        }
        if (!safeName.toLowerCase().endsWith(".md")) {
            safeName = safeName + ".md";
        }
        return safeName;
    }

    /** 文件流的打开句柄（filename 是清洗后的下载名；manual = 内存流）。 */
    public record KnowledgeFileStream(String filename,
            OpenedFile opened, boolean manual) {
    }

    /**
     * 打开知识文件流。manual 知识直接读 metadata.content，文档走存储层
     * （本地可 seek → 支持 Range；云按 provider 能力）。不整份读入内存。
     */
    public KnowledgeFileStream openKnowledgeFile(String id) {
        Knowledge knowledge = knowledgeRepository.findById(KnowledgeTenants.tenantId(), id);
        if (knowledge == null) {
            throw BizException.notFound("record not found");
        }
        if ("manual".equals(knowledge.getType())) {
            String content = knowledge.getMetadata() != null
                    && knowledge.getMetadata().hasNonNull("content")
                    ? knowledge.getMetadata().get("content").asText() : "";
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            return new KnowledgeFileStream(sanitizeManualDownloadFilename(knowledge.getTitle()),
                    OpenedFile.ofStream(
                            new ByteArrayInputStream(bytes), bytes.length),
                    true);
        }
        String filePath = knowledge.getFilePath() == null ? "" : knowledge.getFilePath();
        return new KnowledgeFileStream(knowledge.getFileName(),
                fileStorage.open(KnowledgeTenants.tenantId(), filePath), false);
    }

    /**
     * chunk 归属校验（403）、子块 caption/OCR 同步、缺块补建、
     * {@code updateChunkVector(updateChunks + addChunks)}（模型 ID 空 → 1007
     * "model ID cannot be empty"，契约样例锁定）、
     * knowledge.file_hash = md5(knowledgeID+fileHash+imageInfo)。
     */
    @Transactional
    public void updateImageInfo(String knowledgeId, String chunkId, String rawImageInfo) {
        Knowledge knowledge = folderService.loadKnowledgeWrite(knowledgeId);
        String imageInfo = CleanInvalidUtf8.clean(rawImageInfo == null ? "" : rawImageInfo);
        final JsonNode images;
        try {
            images = MAPPER.readTree(imageInfo);
        } catch (Exception e) {
            // 只保留解析器首行信息（Jackson 的完整 message 附带源码片段与位置，噪声大）
            String detail = e.getMessage() == null ? "parse failed" : e.getMessage().split("\n", 2)[0];
            throw new BizException(AppError.internal("invalid image info payload: " + detail));
        }
        if (!images.isArray() || images.size() != 1) {
            log.warn("Expected exactly one image info, got {}",
                    images.isArray() ? images.size() : -1);
            return; // 不满足结构 → 静默跳过（响应仍 200）
        }
        JsonNode image = images.get(0);

        Chunk chunk = chunkRepository.findByIdOrNull(KnowledgeTenants.tenantId(), chunkId);
        if (chunk == null) {
            // 父块缺失 "chunk not found"（handler 包 500）
            throw new BizException(AppError.internal("chunk not found"));
        }
        if (!chunk.getId().equals(chunkId) || !chunk.getKnowledgeId().equals(knowledge.getId())
                || !chunk.getTenantId().equals(knowledge.getTenantId())
                || !chunk.getKnowledgeBaseId().equals(knowledge.getKnowledgeBaseId())) {
            throw BizException.forbidden("chunk does not belong to its knowledge document");
        }
        chunk.setImageInfo(imageInfo);
        long tenantId = KnowledgeTenants.tenantId();
        List<Chunk> chunkChildren = chunkRepository.listChunkByParentId(tenantId, chunkId);

        List<Chunk> updateChunks = new ArrayList<>();
        updateChunks.add(chunk);
        List<Chunk> addChunks = new ArrayList<>();
        boolean hasOcr = false;
        boolean hasCaption = false;
        String originalUrl = image.path("originalUrl").asText("");
        String caption = image.path("caption").asText("");
        String ocrText = image.path("ocrText").asText("");
        for (Chunk child : chunkChildren) {
            JsonNode childImages;
            try {
                childImages = MAPPER.readTree(child.getImageInfo() == null ? "" : child.getImageInfo());
            } catch (Exception e) {
                continue; // 单块失败仅告警
            }
            if (!childImages.isArray() || childImages.isEmpty()) {
                continue;
            }
            if (!originalUrl.equals(childImages.get(0).path("originalUrl").asText(""))) {
                continue;
            }
            switch (child.getChunkType() == null ? "" : child.getChunkType()) {
                case "image_caption" -> {
                    hasCaption = true;
                    if (!caption.equals(childImages.get(0).path("caption").asText(""))) {
                        child.setContent(caption);
                        child.setImageInfo(imageInfo);
                        updateChunks.add(child);
                    }
                }
                case "image_ocr" -> {
                    hasOcr = true;
                    if (!ocrText.equals(childImages.get(0).path("ocrText").asText(""))) {
                        child.setContent(ocrText);
                        child.setImageInfo(imageInfo);
                        updateChunks.add(child);
                    }
                }
                default -> {
                }
            }
        }
        if (!hasCaption && !caption.isEmpty()) {
            addChunks.add(newImageChunk(knowledge, chunk, "image_caption", caption, imageInfo));
        }
        if (!hasOcr && !ocrText.isEmpty()) {
            addChunks.add(newImageChunk(knowledge, chunk, "image_ocr", ocrText, imageInfo));
        }
        //：外部索引副作用移出事务——写库前先过"可索引"校验（1007/404 仍在事务内抛，且
        // 一行都不写），真正的 delete+batchIndex 排到提交后执行，回滚则一次都不执行。
        chunkVectorIndexer.assertIndexable(chunk.getKnowledgeBaseId());
        for (Chunk c : addChunks) {
            chunkRepository.insert(c);
        }
        for (Chunk c : updateChunks) {
            chunkRepository.updateById(c);
        }
        // 向量同步（更新既有 + 新增图片子块两批）：
        // 内部过 NeedsEmbedding（策略判定在 ChunkVectorIndexer）→ GetEmbeddingModel；
        // 模型 ID 空 → 1007 "model ID cannot be empty"（契约样例 kg-image-update/again 钉住）
        List<Chunk> vectorChunks = new ArrayList<>(updateChunks.size() + addChunks.size());
        vectorChunks.addAll(updateChunks);
        vectorChunks.addAll(addChunks);
        //：提交后执行（本地事务回滚不了外部索引的 delete/batchIndex）。
        afterCommit(() -> chunkVectorIndexer.updateChunkVector(chunk.getKnowledgeBaseId(), vectorChunks));
        // 对照：knowledge.file_hash = calculateStr(knowledgeID, fileHash, imageInfo)
        Knowledge fresh = knowledgeRepository.findById(tenantId, knowledgeId);
        if (fresh != null) {
            String fileHash = LocalStorageService.md5Hex((knowledgeId + (fresh.getFileHash() == null
                    ? "" : fresh.getFileHash()) + imageInfo)
                    .getBytes(StandardCharsets.UTF_8));
            knowledgeRepository.updateFileHash(fresh.getId(), fileHash, OffsetDateTime.now(ZoneOffset.UTC));
        }
    }

    private static Chunk newImageChunk(Knowledge k, Chunk parent, String type,
                                       String content, String imageInfo) {
        Chunk c = new Chunk();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        c.setId(UUID.randomUUID().toString());
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        c.setTenantId(k.getTenantId());
        c.setKnowledgeId(parent.getKnowledgeId());
        c.setKnowledgeBaseId(parent.getKnowledgeBaseId());
        c.setContent(content);
        c.setChunkType(type);
        c.setParentChunkId(parent.getId());
        c.setImageInfo(imageInfo);
        c.setIsEnabled(true);
        return c;
    }

    /**
 * 把外部副作用排到**事务提交后**执行——回滚时一次都不执行。无事务上下文（直调 /
 * 单测）内联执行，行为不变。提交后失败只记日志：DB 已提交，不能把成功请求变成 500；
 * 幂等重试 / 索引意图 outbox 化见 后续（方案）。
 */
    static void afterCommit(Runnable externalEffect) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            runQuietly(externalEffect);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                runQuietly(externalEffect);
            }
        });
    }

    private static void runQuietly(Runnable effect) {
        try {
            effect.run();
        } catch (RuntimeException e) {
            log.error("[K7] post-commit external index failed", e);
        }
    }
}
