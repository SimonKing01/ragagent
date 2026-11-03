package com.ragagent.knowledge.service.faq;

import com.ragagent.contract.audit.AuditAction;
import com.ragagent.contract.audit.AuditWritePort;
import com.ragagent.contract.error.AppError;
import com.ragagent.contract.error.BizException;
import com.ragagent.contract.knowledge.FaqChunkMetadata;
import com.ragagent.contract.model.ModelCatalogPort.ModelRowView;
import com.ragagent.contract.retrieval.VectorIndexStore;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.domain.KnowledgeTag;
import com.ragagent.knowledge.domain.access.FaqGuard;
import com.ragagent.knowledge.dto.faq.FaqEntry;
import com.ragagent.knowledge.dto.faq.FaqEntryFieldsBatchUpdate;
import com.ragagent.knowledge.dto.faq.FaqEntryFieldsUpdate;
import com.ragagent.knowledge.dto.faq.FaqEntryPayload;
import com.ragagent.knowledge.index.KnowledgeVectorWrites;
import com.ragagent.knowledge.repository.ChunkRepository;
import com.ragagent.knowledge.repository.FaqChunkRepository;
import com.ragagent.knowledge.repository.KnowledgeRepository;
import com.ragagent.knowledge.repository.KnowledgeTagRepository;
import com.ragagent.knowledge.support.FaqImportTaskStore;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.platform.audit.ActivityAuditRecorder;
import com.ragagent.platform.security.Hashes;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * FAQ 条目命令面：创建、更新、相似问追加、批量字段/标签更新与删除，
 * 附 KB 活动审计与重复问校验。写路径的域校验经 {@link FaqGuard}，
 * chunk 落库与向量索引进 {@link FaqIndexWriter}。
 */
@Service
public class FaqEntryCommandService {

    private static final Logger log = LoggerFactory.getLogger(FaqEntryCommandService.class);


    private final ChunkRepository chunkRepository;
    private final FaqChunkRepository faqChunkRepository;
    private final KnowledgeRepository knowledgeRepository;
    private final KnowledgeTagRepository tagRepository;
    private final FaqImportTaskStore taskStore;
    private final ActivityAuditRecorder activityAudit;
    private final FaqGuard faqGuard;
    private final FaqChunkCodec faqChunkCodec;
    private final FaqIndexWriter faqIndexWriter;
    private final FaqEntryProjector projector;
    private final FaqDuplicateChecker duplicateChecker;
    private final FaqBatchFieldUpdater batchFieldUpdater;

    public FaqEntryCommandService(ChunkRepository chunkRepository,
                                  KnowledgeRepository knowledgeRepository,
                                  KnowledgeTagRepository tagRepository,
                                  FaqImportTaskStore taskStore,
                                  VectorIndexStore vectorStore,
                                  KnowledgeVectorWrites vectorWrites,
                                  AuditWritePort auditWriter,
                                  FaqGuard faqGuard,
                                  FaqChunkCodec faqChunkCodec,
                                  FaqIndexWriter faqIndexWriter,
                                  FaqChunkRepository faqChunkRepository) {
        this.chunkRepository = chunkRepository;
        this.faqChunkRepository = faqChunkRepository;
        this.knowledgeRepository = knowledgeRepository;
        this.tagRepository = tagRepository;
        this.taskStore = taskStore;
        this.activityAudit = new ActivityAuditRecorder(auditWriter);
        this.faqGuard = faqGuard;
        this.faqChunkCodec = faqChunkCodec;
        this.faqIndexWriter = faqIndexWriter;
        this.projector = new FaqEntryProjector(tagRepository, faqChunkCodec);
        this.duplicateChecker = new FaqDuplicateChecker(faqChunkRepository, faqChunkCodec);
        this.batchFieldUpdater = new FaqBatchFieldUpdater(
                faqGuard, faqChunkRepository, vectorWrites, vectorStore, activityAudit);
    }

    private static long tenantId() {
        Long tid = TenantContext.currentTenantId();
        return tid == null ? 0 : tid;
    }

    // ── 创建 ──

    /**
     * 判定顺序 契约样例依赖：
     * sanitize → tag 解析 → create guard → 重复检查 → 容器 → index mode →
     * <b>GetEmbeddingModel（plain 500 分支）</b> → 建 chunk → 索引（失败回滚 chunk）。
     */
    public FaqEntry createEntry(String kbId, FaqEntryPayload payload) {
        KnowledgeBase kb = faqGuard.writableFAQKnowledgeBase(kbId);
        faqGuard.ensureDefaults(kb);
        long tid = tenantId();

        FaqChunkMetadata meta = faqGuard.sanitizeFAQEntryPayload(payload);
        String tagId = faqGuard.resolveTagId(kb.getId(), payload);

        String guardKey = "faq:create:" + tid + ":" + kb.getId() + ":" + sha256Hex(meta.standardQuestion);
        if (!taskStore.acquireCreateGuard(guardKey)) {
            throw new BizException(AppError.conflict("相同标准问的 FAQ 条目正在创建中，请勿重复提交"));
        }
        try {
            duplicateChecker.check(tid, kb.getId(), "", meta);

            Knowledge faqKnowledge = faqIndexWriter.ensureFAQKnowledge(tid, kb);
            if (faqKnowledge == null) {
                throw new IllegalStateException("failed to ensure FAQ knowledge: knowledge not found");
            }

            String indexMode = faqChunkCodec.faqIndexMode(kb);

            // GetEmbeddingModel：模型行缺失/ID 空 → plain 500（handler c.Error 的非 AppError 分支）
            ModelRowView embeddingModel = faqIndexWriter.requireEmbeddingModel(kb);

            boolean isEnabled = payload.enabled() == null || payload.enabled();
            int flags = payload.recommended() != null && !payload.recommended() ? 0 : 1;

            Chunk chunk = new Chunk();
            chunk.setId(UUID.randomUUID().toString());
            chunk.setTenantId(tid);
            chunk.setKnowledgeId(faqKnowledge.getId());
            chunk.setKnowledgeBaseId(kb.getId());
            chunk.setContent(faqChunkCodec.buildFAQChunkContent(meta, indexMode));
            chunk.setIsEnabled(isEnabled);
            chunk.setFlags(flags);
            chunk.setChunkType("faq");
            chunk.setTagId(tagId);
            chunk.setStatus(1); // stored
            if (payload.id() != null && payload.id() > 0) {
                chunk.setSeqId(payload.id());
            }
            faqChunkCodec.setFaqMetadata(chunk, meta);
            if (chunk.getCreatedAt() == null) {
                chunk.setCreatedAt(OffsetDateTime.now());
                chunk.setUpdatedAt(chunk.getCreatedAt());
            }
            faqIndexWriter.createChunks(List.of(chunk));

            // 索引步：
            try {
                faqIndexWriter.indexFAQChunks(kb, faqKnowledge, List.of(chunk), embeddingModel, true);
            } catch (RuntimeException indexErr) {
                chunkRepository.deleteChunk(tid, chunk.getId());
                throw new IllegalStateException("failed to index chunk: " + indexErr.getMessage(), indexErr);
            }

            chunk.setStatus(2); // indexed
            chunkRepository.updateChunk(chunk);

            FaqEntry entry = projector.toEntry(tid, chunk, kb);
            log.info("FAQ entry created: kb={}, entry={}", kb.getId(), chunk.getSeqId());
            recordKbActivity(tid, kb.getId(), AuditAction.KNOWLEDGE_CREATED,
                    "faq_entry", chunk.getId(),
                    Map.of("entryId", chunk.getSeqId() == null ? 0L : chunk.getSeqId(),
                            "sourceType", "faq"));
            return entry;
        } finally {
            taskStore.releaseCreateGuard(guardKey);
        }
    }

    // ── 更新 / 相似问 ──

    /**
     * <b>先落库后失败</b>：chunk 更新先于 embedding 模型解析——无模型 KB 上
     * 返回 plain 500 但变更已持久化（契约样例 faq-get-after-update 锁定该行为）。
     */
    public FaqEntry updateEntry(String kbId, long entrySeqId, FaqEntryPayload payload) {
        KnowledgeBase kb = faqGuard.writableFAQKnowledgeBase(kbId);
        faqGuard.ensureDefaults(kb);
        long tid = tenantId();

        Chunk chunk = faqChunkRepository.getChunkBySeqId(tid, entrySeqId);
        if (chunk == null) {
            throw new BizException(AppError.notFound("FAQ条目不存在"));
        }
        if (!kb.getId().equals(chunk.getKnowledgeBaseId())) {
            throw new BizException(AppError.forbidden("无权操作该 FAQ 条目"));
        }
        if (!"faq".equals(chunk.getChunkType())) {
            throw new BizException(AppError.badRequest("仅支持更新 FAQ 条目"));
        }
        FaqChunkMetadata meta = faqGuard.sanitizeFAQEntryPayload(payload);

        duplicateChecker.check(tid, kb.getId(), chunk.getId(), meta);

        FaqChunkMetadata existing = faqChunkCodec.currentFaqMetadata(chunk);
        if (existing != null) {
            meta.version = existing.version + 1;
        }
        faqChunkCodec.setFaqMetadata(chunk, meta);

        String indexMode = faqChunkCodec.faqIndexMode(kb);
        chunk.setContent(faqChunkCodec.buildFAQChunkContent(meta, indexMode));

        if (payload.tagId() > 0) {
            KnowledgeTag tag = tagRepository.getBySeqId(tid, payload.tagId());
            if (tag == null) {
                throw new BizException(AppError.notFound("标签不存在"));
            }
            chunk.setTagId(tag.getId());
        } else {
            chunk.setTagId("");
        }
        if (payload.enabled() != null) {
            chunk.setIsEnabled(payload.enabled());
        }
        if (payload.recommended() != null) {
            if (payload.recommended()) {
                chunk.setFlags(chunk.getFlags() | 1);
            } else {
                chunk.setFlags(chunk.getFlags() & ~1);
            }
        }
        chunk.setUpdatedAt(OffsetDateTime.now());
        chunkRepository.updateChunk(chunk);

        Knowledge faqKnowledge = knowledgeRepository.findById(tid, chunk.getKnowledgeId());
        if (faqKnowledge == null) {
            throw new IllegalStateException("failed to get knowledge: record not found");
        }

        // GetEmbeddingModel；无模型的 KB 在这里 plain 500（变更已持久化）
        ModelRowView embeddingModel = faqIndexWriter.requireEmbeddingModel(kb);
        // separate 模式相似问减少时先删多余 sourceID——Java 的
        // indexFAQChunks 全删该 chunk 行后重插（净效果等价）；索引失败原样返回
        faqIndexWriter.indexFAQChunks(kb, faqKnowledge, List.of(chunk), embeddingModel, false);

        FaqEntry entry = projector.toEntry(tid, chunk, kb);
        log.info("FAQ entry updated: kb={}, entry={}", kb.getId(), chunk.getSeqId());
        recordKbActivity(tid, kb.getId(), AuditAction.KNOWLEDGE_UPDATED,
                "faq_entry", chunk.getId(),
                Map.of("entryId", chunk.getSeqId() == null ? 0L : chunk.getSeqId(),
                        "sourceType", "faq"));
        return entry;
    }

    /** ；同款先落库后 500。 */
    public FaqEntry addSimilarQuestions(String kbId, long entrySeqId, List<String> questions) {
        if (questions == null || questions.isEmpty()) {
            throw new BizException(AppError.badRequest("相似问列表不能为空"));
        }
        KnowledgeBase kb = faqGuard.writableFAQKnowledgeBase(kbId);
        faqGuard.ensureDefaults(kb);
        long tid = tenantId();

        Chunk chunk = faqChunkRepository.getChunkBySeqId(tid, entrySeqId);
        if (chunk == null) {
            throw new BizException(AppError.notFound("FAQ条目不存在"));
        }
        if (!kb.getId().equals(chunk.getKnowledgeBaseId())) {
            throw new BizException(AppError.forbidden("无权操作该 FAQ 条目"));
        }
        if (!"faq".equals(chunk.getChunkType())) {
            throw new BizException(AppError.badRequest("仅支持更新 FAQ 条目"));
        }
        FaqChunkMetadata meta = faqChunkCodec.currentFaqMetadata(chunk);
        if (meta == null) {
            throw new BizException(AppError.badRequest("获取 FAQ 元数据失败"));
        }

        Set<String> existingSet = new LinkedHashSet<>();
        if (meta.similarQuestions != null) {
            existingSet.addAll(meta.similarQuestions);
        }
        existingSet.add(meta.standardQuestion);

        List<String> newQuestions = new ArrayList<>();
        for (String q : questions) {
            q = q == null ? "" : FaqChunkMetadata.trimSpace(q);
            if (q.isEmpty() || existingSet.contains(q)) {
                continue;
            }
            existingSet.add(q);
            newQuestions.add(q);
        }

        if (newQuestions.isEmpty()) {
            return projector.toEntry(tid, chunk, kb);
        }

        FaqChunkMetadata tempMeta = new FaqChunkMetadata();
        tempMeta.standardQuestion = meta.standardQuestion;
        tempMeta.similarQuestions = new ArrayList<>();
        if (meta.similarQuestions != null) {
            tempMeta.similarQuestions.addAll(meta.similarQuestions);
        }
        tempMeta.similarQuestions.addAll(newQuestions);
        duplicateChecker.check(tid, kb.getId(), chunk.getId(), tempMeta);

        if (meta.similarQuestions == null) {
            meta.similarQuestions = new ArrayList<>();
        }
        meta.similarQuestions.addAll(newQuestions);
        meta.version++;

        faqChunkCodec.setFaqMetadata(chunk, meta);

        String indexMode = faqChunkCodec.faqIndexMode(kb);
        chunk.setContent(faqChunkCodec.buildFAQChunkContent(meta, indexMode));
        chunk.setUpdatedAt(OffsetDateTime.now());
        chunkRepository.updateChunk(chunk);

        Knowledge faqKnowledge = knowledgeRepository.findById(tid, chunk.getKnowledgeId());
        if (faqKnowledge == null) {
            throw new IllegalStateException("failed to get knowledge: record not found");
        }
        ModelRowView embeddingModel = faqIndexWriter.requireEmbeddingModel(kb);
        // 相似问追加后全量重索引；失败原样返回
        faqIndexWriter.indexFAQChunks(kb, faqKnowledge, List.of(chunk), embeddingModel, false);

        FaqEntry entry = projector.toEntry(tid, chunk, kb);
        recordKbActivity(tid, kb.getId(), AuditAction.KNOWLEDGE_UPDATED,
                "faq_entry", chunk.getId(),
                Map.of("entryId", chunk.getSeqId() == null ? 0L : chunk.getSeqId(),
                        "sourceType", "faq"));
        return entry;
    }

    // ── 批量字段 / 标签 ──

    /** null tag = 0 = 移除标签。 */
    public void updateEntryTagBatch(String kbId, Map<Long, Long> updates) {
        Map<Long, FaqEntryFieldsUpdate> byId = new LinkedHashMap<>();
        if (updates != null) {
            updates.forEach((id, tag) -> {
                long value = tag == null ? 0 : tag;
                byId.put(id, new FaqEntryFieldsUpdate(null, null, value));
            });
        }
        updateEntryFieldsBatch(kbId, new FaqEntryFieldsBatchUpdate(byId, null, null));
    }
    /** 批量字段更新；实现见 {@link FaqBatchFieldUpdater}。 */
    public void updateEntryFieldsBatch(String kbId, FaqEntryFieldsBatchUpdate req) {
        batchFieldUpdater.updateFields(tenantId(), kbId, req);
    }

    // ── 删除 ──

    /**
     * 授权先行、逐条软删，
     * 然后 deleteFAQChunkVectors 的 GetEmbeddingModel 失败 → plain 500（行已删）。
     */
    public void deleteEntries(String kbId, List<Long> entrySeqIds) {
        if (entrySeqIds == null || entrySeqIds.isEmpty()) {
            throw new BizException(AppError.badRequest("请选择需要删除的 FAQ 条目"));
        }
        KnowledgeBase kb = faqGuard.writableFAQKnowledgeBase(kbId);
        long tid = tenantId();

        Map<Long, Chunk> selected = faqGuard.loadFAQWriteChunks(kb, entrySeqIds);
        List<Chunk> chunksToRemove = new ArrayList<>();
        Map<String, Knowledge> knowledges = new LinkedHashMap<>();
        Map<String, List<Chunk>> groups = new LinkedHashMap<>();
        for (Long id : FaqGuard.sortedIds(selected.keySet())) {
            Chunk chunk = selected.get(id);
            if (!knowledges.containsKey(chunk.getKnowledgeId())) {
                Knowledge knowledge = knowledgeRepository.findById(tid, chunk.getKnowledgeId());
                if (knowledge == null || knowledge.getTenantId() == null
                        || knowledge.getTenantId() != tid
                        || !kb.getId().equals(knowledge.getKnowledgeBaseId())
                        || !"faq".equals(knowledge.getType())) {
                    throw new BizException(AppError.forbidden("FAQ 文档不属于当前知识库"));
                }
                knowledges.put(chunk.getKnowledgeId(), knowledge);
            }
            groups.computeIfAbsent(chunk.getKnowledgeId(), k -> new ArrayList<>()).add(chunk);
            chunksToRemove.add(chunk);
        }
        for (Chunk chunk : chunksToRemove) {
            chunkRepository.deleteChunk(tid, chunk.getId());
        }
        for (Map.Entry<String, List<Chunk>> e : groups.entrySet()) {
            faqIndexWriter.deleteFAQChunkVectors(kb, knowledges.get(e.getKey()), e.getValue());
        }
        log.info("FAQ entries deleted: kb={}, count={}", kb.getId(), chunksToRemove.size());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("count", chunksToRemove.size());
        details.put("sourceType", "faq");
        List<String> titles = new ArrayList<>(chunksToRemove.size());
        for (Chunk chunk : chunksToRemove) {
            titles.add(faqChunkQuestion(chunk));
        }
        appendSampleTitles(details, titles);
        recordKbActivity(tid, kb.getId(), AuditAction.KNOWLEDGE_BATCH_DELETED,
                "faq_entry", "", details);
    }

    /** 标准问（trim）。 */
    private String faqChunkQuestion(Chunk chunk) {
        FaqChunkMetadata meta = faqChunkCodec.sanitizedFaqMetadata(chunk);
        if (meta == null) {
            return "";
        }
        String question = meta.standardQuestion == null ? "" : meta.standardQuestion.trim();
        return question;
    }

    /** 去空去重、上限 5、
     *  单条落 title、多条落 titles。 */
    private static void appendSampleTitles(Map<String, Object> details, List<String> titles) {
        List<String> samples = new ArrayList<>(5);
        Set<String> seen = new LinkedHashSet<>(5);
        for (String title : titles) {
            String t = title == null ? "" : title.trim();
            if (t.isEmpty() || samples.size() >= 5 || !seen.add(t)) {
                continue;
            }
            samples.add(t);
        }
        if (samples.isEmpty()) {
            return;
        }
        details.put("title", samples.get(0));
        if (samples.size() > 1) {
            details.put("titles", samples);
        }
    }

    /** 尽力而为的 KB 活动审计；写入规则见 {@link ActivityAuditRecorder}。 */
    private void recordKbActivity(long tenantId, String kbId, String action,
                                  String targetType, String targetId, Map<String, Object> details) {
        activityAudit.record(tenantId, "knowledge_base", kbId, action, targetType, targetId, details);
    }


    private static String sha256Hex(String input) {
        return Hashes.sha256HexPrefix(input, 32); // 契约：内容哈希取前 16 字节
    }




}
