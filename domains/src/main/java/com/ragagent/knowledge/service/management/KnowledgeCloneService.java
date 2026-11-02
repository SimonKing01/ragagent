package com.ragagent.knowledge.service.management;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.contract.error.AppError;
import com.ragagent.contract.error.BizException;
import com.ragagent.contract.model.Embedder;
import com.ragagent.contract.model.ModelRuntime;
import com.ragagent.contract.retrieval.IndexEngine;
import com.ragagent.contract.retrieval.IndexEngineResolver;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.domain.KnowledgeBaseIndexingStrategy;
import com.ragagent.knowledge.dto.kb.KBCloneProgress;
import com.ragagent.knowledge.index.KnowledgeVectorWrites;
import com.ragagent.knowledge.repository.ChunkRepository;
import com.ragagent.knowledge.repository.KnowledgeBaseRepository;
import com.ragagent.knowledge.repository.KnowledgeRepository;
import com.ragagent.knowledge.service.image.ExtractedImageStore;
import com.ragagent.knowledge.support.KnowledgeTaskProgressStore;
import com.ragagent.knowledge.support.KnowledgeTaskRunner;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.platform.web.JsonMappers;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * KB clone（copy 路由 worker 面）+ Duplicate（同步 settings-only）+ 跨库兼容性校验。
 * clone 在虚拟线程内驱动状态机；
 * duplicate 是同步 settings 级复制（不复制知识内容）。
 */
@Service
public class KnowledgeCloneService {


    /** duplicate 的配置克隆用：知识实体带 OffsetDateTime，往返 mapper 必须挂 JSR310（本仓约定 步 3 教训）。 */
    private static final ObjectMapper CLONE_MAPPER =
            JsonMappers.lenient();
    private final KnowledgeRepository knowledgeRepository;
    private final KnowledgeBaseRepository kbRepository;
    private final ChunkRepository chunkRepo;
    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeTaskProgressStore progressStore;
    private final KnowledgeVectorWrites vectorWrites;
    private final IndexEngineResolver indexEngineResolver;
    /** 抽取图片搬运：克隆时把图片复制到新文档并改写正文引用。 */
    private final ExtractedImageStore extractedImageStore;
    private final ModelRuntime modelRuntimeAssembler;
    /** 后台任务执行器（统一命名与关停）。 */
    private final KnowledgeTaskRunner taskExecutor;


    public KnowledgeCloneService(KnowledgeTaskRunner taskExecutor, KnowledgeRepository knowledgeRepository,
            KnowledgeBaseRepository kbRepository,
            ChunkRepository chunkRepo,
            KnowledgeBaseService knowledgeBaseService,
            KnowledgeTaskProgressStore progressStore,
            KnowledgeVectorWrites vectorWrites,
            IndexEngineResolver indexEngineResolver,
            ExtractedImageStore extractedImageStore,
            ModelRuntime modelRuntimeAssembler) {

        this.taskExecutor = taskExecutor;
        this.knowledgeRepository = knowledgeRepository;
        this.kbRepository = kbRepository;
        this.chunkRepo = chunkRepo;
        this.knowledgeBaseService = knowledgeBaseService;
        this.progressStore = progressStore;
        this.vectorWrites = vectorWrites;
        this.indexEngineResolver = indexEngineResolver;
        this.extractedImageStore = extractedImageStore;
        this.modelRuntimeAssembler = modelRuntimeAssembler;
    }


    /**
     * 入队 KB clone 任务。
     * EnsureDefaults 补成 vector+keyword）；已有目标做 preflight（add=源里 target 没有的、
     * remove=target 里的多余行；file_hash+completed 二次匹配），total=add+remove，
     * 逐步 "Processed X/N clone operations"，终态 completed/100 +
     * "Knowledge base clone completed successfully"（created_at=0 为既有行为）。
     * <p><b>实现边界</b>：克隆只到"行级"（KB 行 + knowledge 行 + chunk 行），向量索引/
     * 文件对象/wiki/FAQ tag 映射不复制；transfer-state 续跑/重试语义未实现。</p>
     */
    public void startKBClone(long tenantId, String taskId, String sourceId, String targetId,
                             boolean createTarget, String creatorId) {
        progressStore.saveCloneInitial(new KBCloneProgress(
                taskId, sourceId, targetId, "pending", 0, 0, 0,
                "Task queued, waiting to start...", "", epochNow(), epochNow()));
        taskExecutor.submit("kb-clone", () -> {
            TenantContext.set(tenantId, null, null, false, null, false);
            try {
                runKBClone(tenantId, taskId, sourceId, targetId, createTarget, creatorId);
            } finally {
                TenantContext.clear();
            }
        });
    }

    private void runKBClone(long tenantId, String taskId, String sourceId, String targetId,
                            boolean createTarget, String creatorId) {
        KnowledgeBase source = kbRepository.findById(tenantId, sourceId);
        if (source == null) {
            progressStore.saveClone(new KBCloneProgress(
                    taskId, sourceId, targetId, "failed", 0, 0, 0, "Clone preflight failed",
                    "knowledge base not found", 0, epochNow()));
            return;
        }
        KBCloneProgress progress = new KBCloneProgress(
                taskId, sourceId, targetId, "processing", 0, 0, 0,
                "Starting knowledge base clone...", "", 0, epochNow());
        progressStore.saveClone(progress);
        try {
            KnowledgeBase dst = targetRowForClone(tenantId, targetId, createTarget, creatorId, source);
            // preflight：add = 源里 target 没有的（file_hash+completed 二次匹配）；
            // remove = target 里的多余行；源里未完成的行报错
            List<Knowledge> srcRows = knowledgeRepository.listByTenantAndKb(tenantId, sourceId);
            List<Knowledge> dstRows = createTarget ? new ArrayList<>()
                    : knowledgeRepository.listByTenantAndKb(tenantId, targetId);
            List<Knowledge> toAdd = new ArrayList<>();
            Set<String> matched = new HashSet<>();
            for (Knowledge k : srcRows) {
                if (!Knowledge.PARSE_COMPLETED.equals(k.getParseStatus())) {
                    throw new IllegalStateException("source knowledge " + k.getId() + " is not completed");
                }
                boolean hit = false;
                if (k.getFileHash() != null && !k.getFileHash().isEmpty()) {
                    for (Knowledge o : dstRows) {
                        if (!matched.contains(o.getId()) && k.getFileHash().equals(o.getFileHash())
                                && Knowledge.PARSE_COMPLETED.equals(o.getParseStatus())) {
                            matched.add(o.getId());
                            hit = true;
                            break;
                        }
                    }
                }
                if (!hit) {
                    toAdd.add(k);
                }
            }
            List<String> toRemove = new ArrayList<>();
            for (Knowledge o : dstRows) {
                if (matched.contains(o.getId())) {
                    continue;
                }
                if (Knowledge.PARSE_PROCESSING.equals(o.getParseStatus())
                        || Knowledge.PARSE_PENDING.equals(o.getParseStatus())
                        || Knowledge.PARSE_DELETING.equals(o.getParseStatus())) {
                    throw new IllegalStateException("target knowledge " + o.getId() + " is busy");
                }
                toRemove.add(o.getId());
            }
            int total = toAdd.size() + toRemove.size();
            progress = new KBCloneProgress(taskId, sourceId, targetId, "processing", 0, total, 0,
                    progress.message(), "", 0, epochNow());
            progressStore.saveClone(progress);
            int done = 0;
            for (String id : toRemove) {
                removeKnowledgeRow(id);
                done++;
                progress = progress.withDone(done);
                progressStore.saveClone(progress);
            }
            for (Knowledge k : toAdd) {
                cloneKnowledgeRow(k, dst);
                done++;
                progress = progress.withDone(done);
                progressStore.saveClone(progress);
            }
            progressStore.saveClone(new KBCloneProgress(
                    taskId, sourceId, targetId, "completed", 100, total,
                    total, "Knowledge base clone completed successfully", "", 0, epochNow()));
        } catch (RuntimeException e) {
            progressStore.saveClone(new KBCloneProgress(
                    taskId, sourceId, targetId, "failed", progress.progress(), progress.total(),
                    progress.processed(), "Failed to clone knowledge", String.valueOf(e.getMessage()),
                    0, epochNow()));
        }
    }

    private KnowledgeBase targetRowForClone(long tenantId, String targetId, boolean create,
                                            String creatorId, KnowledgeBase source) {
        if (!create) {
            return kbRepository.findById(tenantId, targetId);
        }
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(targetId);
        kb.setTenantId(tenantId);
        kb.setCreatorId(creatorId);
        kb.setName(source.getName());
        kb.setType(source.getType());
        kb.setDescription(source.getDescription());
        kb.setChunkingConfig(source.getChunkingConfig());
        kb.setImageProcessingConfig(source.getImageProcessingConfig());
        kb.setEmbeddingModelId(source.getEmbeddingModelId());
        kb.setSummaryModelId(source.getSummaryModelId());
        kb.setVlmConfig(source.getVlmConfig());
        kb.setStorageProviderConfig(source.getStorageProviderConfig());
        kb.setStorageBackendId(source.getStorageBackendId());
        kb.setStorageConfig(source.getStorageConfig());
        kb.setFaqConfig(source.getFaqConfig());
        kb.setVectorStoreId(source.getVectorStoreId());
        kb.setIndexingStrategy(KnowledgeBaseIndexingStrategy.defaultStrategy());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        kb.setCreatedAt(now);
        kb.setUpdatedAt(now);
        kb.normalizeVectorStoreId();
        kbRepository.insert(kb);
        return kb;
    }

    /** knowledge 软删 + chunk 软删。 */
    private void removeKnowledgeRow(String knowledgeId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        knowledgeRepository.softDeleteIfActive(knowledgeId, now);
        chunkRepo.softDeleteByKnowledgeId(knowledgeId, now);
    }

    /** 行级克隆：新 knowledge id + 新 chunk id（向量/文件对象不复制，见 startKBClone 差异）。 */
    private void cloneKnowledgeRow(Knowledge src, KnowledgeBase dst) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String newId = UUID.randomUUID().toString();
        Knowledge copy = new Knowledge();
        copy.setId(newId);
        copy.setTenantId(dst.getTenantId());
        copy.setKnowledgeBaseId(dst.getId());
        copy.setType(src.getType());
        copy.setTitle(src.getTitle());
        copy.setDescription(src.getDescription());
        copy.setSource(src.getSource());
        copy.setParseStatus(src.getParseStatus());
        copy.setSummaryStatus(src.getSummaryStatus());
        copy.setEnableStatus(src.getEnableStatus());
        copy.setEmbeddingModelId(src.getEmbeddingModelId());
        copy.setFileName(src.getFileName());
        copy.setFolderPath(src.getFolderPath());
        copy.setFileType(src.getFileType());
        copy.setFileSize(src.getFileSize());
        copy.setFileHash(src.getFileHash());
        copy.setFilePath(src.getFilePath());
        copy.setMetadata(src.getMetadata());
        copy.setCustomMetadata(src.getCustomMetadata());
        copy.setCreatedAt(now);
        copy.setUpdatedAt(now);
        copy.setErrorMessage(src.getErrorMessage());
        knowledgeRepository.insert(copy);
        List<Chunk> chunks = chunkRepo.listActiveByKnowledgeId(src.getTenantId(), src.getId());
        Map<String, String> srcToDstChunkIds = new LinkedHashMap<>();
        for (Chunk c : chunks) {
            Chunk nc = new Chunk();
            nc.setId(UUID.randomUUID().toString());
            nc.setTenantId(dst.getTenantId());
            nc.setKnowledgeId(newId);
            nc.setKnowledgeBaseId(dst.getId());
            // 抽取图片随行复制到新文档并改写引用（原引用绑定的是源文档，跨库取图会 403）
            nc.setContent(extractedImageStore.copyImagesTo(src.getTenantId(), c.getContent(),
                    dst.getTenantId(), newId));
            nc.setChunkIndex(c.getChunkIndex());
            nc.setIsEnabled(c.isIsEnabled());
            nc.setChunkType(c.getChunkType());
            nc.setContentHash(c.getContentHash());
            nc.setStartAt(c.getStartAt());
            nc.setEndAt(c.getEndAt());
            nc.setCreatedAt(now);
            nc.setUpdatedAt(now);
            chunkRepo.insert(nc);
            srcToDstChunkIds.put(c.getId(), nc.getId());
        }
        copyKnowledgeVectors(src, dst, newId, srcToDstChunkIds);
    }

    /**
     * "callers that allow source/target KBs to bind to different stores must
     * perform their own cross-store migration"）。绑定店走引擎口，未绑定走
     * postgres 语义（pg 适配器的分页 + 三态 SourceID 改写 + ON CONFLICT DO NOTHING）。
     * 本仓 clone worker 无对应回滚面——失败时进度任务标 failed。
     */
    private void copyKnowledgeVectors(Knowledge src, KnowledgeBase dst, String dstKnowledgeId,
                                      Map<String, String> srcToDstChunkIds) {
        if (srcToDstChunkIds.isEmpty() || dst.getEmbeddingModelId() == null
                || dst.getEmbeddingModelId().isEmpty()) {
            return;
        }
        KnowledgeBase srcKb = kbRepository.findByIdOnly(src.getKnowledgeBaseId());
        if (srcKb == null) {
            return;
        }
        try {
            IndexEngine engine =
                    vectorWrites.boundEngine(srcKb, TenantContext.currentTenantId());
            if (engine != null) {
                Embedder emb =
                        modelRuntimeAssembler.getEmbeddingModel(dst.getEmbeddingModelId());
                engine.copyIndices(src.getKnowledgeBaseId(),
                        Map.of(src.getId(), dstKnowledgeId), srcToDstChunkIds, dst.getId(),
                        emb.getDimensions(), dst.getType());
                return;
            }
            indexEngineResolver.postgresDirect().copyIndices(src.getKnowledgeBaseId(),
                    Map.of(src.getId(), dstKnowledgeId), srcToDstChunkIds, dst.getId(), 0,
                    dst.getType());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    e.getMessage() == null ? String.valueOf(e) : e.getMessage(), e);
        }
    }

    public void saveKBCloneProgress(KBCloneProgress p) {
        progressStore.saveCloneInitial(p);
    }

    public KBCloneProgress getKBCloneProgress(String taskId) {
        return progressStore.getClone(taskId);
    }


    /**
     * 名字带 " 副本"（zh 缺省；重名 " 2"、" 3"...），creator=调用者（非合成用户），
     * 计数/置顶/临时全清零，EnsureDefaults + Normalize 后落库。**只复制设置**——
     */
    public KnowledgeBase duplicateKnowledgeBase(String sourceId) {
        KnowledgeBase source = kbRepository.findById(TenantContext.currentTenantId(), sourceId);
        if (source == null) {
            throw new BizException(AppError.notFound("knowledge base not found"));
        }
        KnowledgeBaseService.ensureDefaults(source);
        KnowledgeBase target;
        try {
            // JSON 往返深拷贝。
            // 注意 MAPPER 是裸 ObjectMapper（无 JSR310）——KnowledgeBase 带 OffsetDateTime，
            // 必须用带 JavaTimeModule 的独立 mapper（与 AbstractJsonListTypeHandler 的教训同族）。
            target = CLONE_MAPPER.convertValue(source, KnowledgeBase.class);
        } catch (IllegalArgumentException e) {
            throw new BizException(AppError.internal("failed to clone knowledge base configuration")
                    .withDetails(String.valueOf(e.getMessage())));
        }
        target.setId(UUID.randomUUID().toString());
        target.setTenantId(TenantContext.currentTenantId());
        target.setName(buildDuplicateKnowledgeBaseName(TenantContext.currentTenantId(), source.getName()));
        String uid = TenantContext.currentUserId();
        target.setCreatorId(uid != null && !uid.startsWith("system-") ? uid : "");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        target.setCreatedAt(now);
        target.setUpdatedAt(now);
        target.setDeletedAt(null);
        target.setIsTemporary(false);
        target.setIsPinned(false);
        target.setPinnedAt(null);
        target.setKnowledgeCount(0);
        target.setChunkCount(0);
        target.setIsProcessing(false);
        target.setProcessingCount(0);
        target.setShareCount(0);
        target.setCreatorName("");
        KnowledgeBaseService.ensureDefaults(target);
        target.normalizeVectorStoreId();
        // 复制出的 KB 若带 vector_store_id，同样过绑定校验
        if (target.hasVectorStore()) {
            knowledgeBaseService.validateVectorStoreBinding(TenantContext.currentTenantId(), target.getVectorStoreId());
        }
        kbRepository.insert(target);
        return target;
    }

    /** zh 缺省后缀 " 副本"，重名追加 " 2"/" 3"...。 */
    private String buildDuplicateKnowledgeBaseName(long tid, String sourceName) {
        String baseName = sourceName == null ? "" : sourceName.trim();
        if (baseName.isEmpty()) {
            baseName = "知识库";
        }
        String suffix = " 副本";
        Set<String> existing = new HashSet<>();
        for (KnowledgeBase kb : kbRepository.listByTenant(tid)) {
            existing.add(kb.getName());
        }
        String candidate = baseName + suffix;
        if (!existing.contains(candidate)) {
            return candidate;
        }
        for (int i = 2; ; i++) {
            candidate = baseName + suffix + " " + i;
            if (!existing.contains(candidate)) {
                return candidate;
            }
        }
    }


    public static void validateKBTransferCompatibility(KnowledgeBase source, KnowledgeBase target,
                                                       String mode) {
        if (!source.getType().equals(target.getType())) {
            throw new IllegalArgumentException("source and target knowledge bases must have the same type");
        }
        String se = source.getEmbeddingModelId() == null ? "" : source.getEmbeddingModelId();
        String te = target.getEmbeddingModelId() == null ? "" : target.getEmbeddingModelId();
        if (!se.equals(te)) {
            throw new IllegalArgumentException("source and target knowledge bases use different embedding models");
        }
        if (!"reuse_vectors".equals(mode) && !"reparse".equals(mode)) {
            throw new IllegalArgumentException("unknown move mode: " + mode);
        }
        if ("reuse_vectors".equals(mode) && !sharesStoreWith(source, target)) {
            throw new IllegalArgumentException(
                    "source and target knowledge bases use different vector stores; use reparse mode for moves");
        }
    }

    /** clone（无 mode）的兼容性判定：mode 传 null 跳过 move 专属检查。 */
    public static void validateCloneCompatibility(KnowledgeBase source, KnowledgeBase target) {
        if (!source.getType().equals(target.getType())) {
            throw new IllegalArgumentException("source and target knowledge bases must have the same type");
        }
        String se = source.getEmbeddingModelId() == null ? "" : source.getEmbeddingModelId();
        String te = target.getEmbeddingModelId() == null ? "" : target.getEmbeddingModelId();
        if (!se.equals(te)) {
            throw new IllegalArgumentException("source and target knowledge bases use different embedding models");
        }
        if (!sharesStoreWith(source, target)) {
            throw new IllegalArgumentException(
                    "source and target knowledge bases use different vector stores; use reparse mode for moves");
        }
    }

    /** 两边都没绑定（null/空串）视为共享。 */
    static boolean sharesStoreWith(KnowledgeBase a, KnowledgeBase b) {
        String sa = normalizeStore(a);
        String sb = normalizeStore(b);
        if (sa.isEmpty() && sb.isEmpty()) {
            return true;
        }
        return !sa.isEmpty() && sa.equals(sb);
    }

    private static String normalizeStore(KnowledgeBase kb) {
        String v = kb == null || kb.getVectorStoreId() == null ? "" : kb.getVectorStoreId();
        return v.trim();
    }

    private static long epochNow() {
        return Instant.now().getEpochSecond();
    }

}
