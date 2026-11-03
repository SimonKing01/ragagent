package com.ragagent.knowledge.service.faq;

import com.ragagent.contract.audit.AuditAction;
import com.ragagent.contract.retrieval.IndexEngine;
import com.ragagent.contract.retrieval.VectorIndexStore;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.domain.KnowledgeTag;
import com.ragagent.knowledge.domain.access.FaqGuard;
import com.ragagent.knowledge.dto.faq.FaqEntryFieldsBatchUpdate;
import com.ragagent.knowledge.dto.faq.FaqEntryFieldsUpdate;
import com.ragagent.knowledge.index.KnowledgeVectorWrites;
import com.ragagent.knowledge.repository.FaqChunkRepository;
import com.ragagent.platform.audit.ActivityAuditRecorder;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FAQ 条目批量字段更新：byTag 一条 UPDATE、byId 逐条比对，最后同步检索引擎。
 *
 * <p>两条分支的 flag 位运算与引擎三态（绑定引擎 / 向量库 / 抛错）在此收口；
 * 命令服务只保留入口与事务/审计编排。</p>
 */
final class FaqBatchFieldUpdater {

    private static final Logger log = LoggerFactory.getLogger(FaqBatchFieldUpdater.class);

    private final FaqGuard faqGuard;
    private final FaqChunkRepository faqChunkRepository;
    private final KnowledgeVectorWrites vectorWrites;
    private final VectorIndexStore vectorStore;
    private final ActivityAuditRecorder activityAudit;

    FaqBatchFieldUpdater(FaqGuard faqGuard, FaqChunkRepository faqChunkRepository,
                         KnowledgeVectorWrites vectorWrites, VectorIndexStore vectorStore,
                         ActivityAuditRecorder activityAudit) {
        this.faqGuard = faqGuard;
        this.faqChunkRepository = faqChunkRepository;
        this.vectorWrites = vectorWrites;
        this.vectorStore = vectorStore;
        this.activityAudit = activityAudit;
    }

    void updateFields(long tid, String kbId, FaqEntryFieldsBatchUpdate req) {
        if (req == null || ((req.byId() == null || req.byId().isEmpty())
                && (req.byTag() == null || req.byTag().isEmpty()))) {
            return;
        }
        KnowledgeBase kb = faqGuard.writableFAQKnowledgeBase(kbId);

        Map<String, Boolean> enabledUpdates = new LinkedHashMap<>();
        Map<String, String> tagUpdates = new LinkedHashMap<>();

        FaqGuard.FaqFieldPlan plan = faqGuard.planFAQFields(kb, req);
        List<String> excludeUuids = plan.excludeIds;

        if (req.byTag() != null && !req.byTag().isEmpty()) {
            for (Long tagSeqId : FaqGuard.sortedIds(req.byTag().keySet())) {
                FaqEntryFieldsUpdate update = req.byTag().get(tagSeqId);
                KnowledgeTag tag = plan.tags.get(tagSeqId);

                int setFlags = 0;
                int clearFlags = 0;
                if (update.recommended() != null) {
                    if (update.recommended()) {
                        setFlags = 1;
                    } else {
                        clearFlags = 1;
                    }
                }
                String newTagUuid = null;
                if (update.tagId() != null) {
                    newTagUuid = update.tagId() > 0
                            ? plan.tags.get(update.tagId()).getId()
                            : "";
                }
                List<String> affectedIds = faqChunkRepository.updateChunkFieldsByTagId(
                        tid, kb.getId(), tag.getId(), update.enabled(),
                        setFlags, clearFlags, newTagUuid, excludeUuids);

                for (String id : affectedIds) {
                    Chunk chunk = plan.chunksById.get(id);
                    if (chunk != null) {
                        if (update.enabled() != null) {
                            chunk.setIsEnabled(update.enabled());
                        }
                        chunk.setFlags((chunk.getFlags() | setFlags) & ~clearFlags);
                        if (newTagUuid != null) {
                            chunk.setTagId(newTagUuid);
                        }
                    }
                }
                if (!affectedIds.isEmpty()) {
                    if (update.enabled() != null) {
                        for (String id : affectedIds) {
                            enabledUpdates.put(id, update.enabled());
                        }
                    }
                    if (newTagUuid != null) {
                        for (String id : affectedIds) {
                            tagUpdates.put(id, newTagUuid);
                        }
                    }
                }
            }
        }

        if (req.byId() != null && !req.byId().isEmpty()) {
            Map<Long, Chunk> chunkBySeqId = plan.chunks;

            Map<String, Integer> setFlags = new LinkedHashMap<>();
            Map<String, Integer> clearFlags = new LinkedHashMap<>();
            List<Chunk> chunksToUpdate = new ArrayList<>();

            for (Long entrySeqId : FaqGuard.sortedIds(req.byId().keySet())) {
                FaqEntryFieldsUpdate update = req.byId().get(entrySeqId);
                Chunk chunk = chunkBySeqId.get(entrySeqId);

                boolean needUpdate = false;
                if (update.enabled() != null && chunk.isIsEnabled() != update.enabled()) {
                    chunk.setIsEnabled(update.enabled());
                    enabledUpdates.put(chunk.getId(), update.enabled());
                    needUpdate = true;
                }
                if (update.recommended() != null) {
                    boolean currentRecommended = (chunk.getFlags() & 1) != 0;
                    if (currentRecommended != update.recommended()) {
                        if (update.recommended()) {
                            setFlags.put(chunk.getId(), 1);
                        } else {
                            clearFlags.put(chunk.getId(), 1);
                        }
                    }
                }
                if (update.tagId() != null) {
                    String newTagId = "";
                    if (update.tagId() > 0) {
                        newTagId = plan.tags.get(update.tagId()).getId();
                    }
                    if (!chunk.getTagId().equals(newTagId)) {
                        chunk.setTagId(newTagId);
                        tagUpdates.put(chunk.getId(), newTagId);
                        needUpdate = true;
                    }
                }
                if (needUpdate) {
                    chunk.setUpdatedAt(OffsetDateTime.now());
                    chunksToUpdate.add(chunk);
                }
            }
            if (!chunksToUpdate.isEmpty()) {
                faqChunkRepository.updateChunks(chunksToUpdate);
            }
            if (!setFlags.isEmpty() || !clearFlags.isEmpty()) {
                faqChunkRepository.updateChunkFlagsBatch(tid, kb.getId(), setFlags, clearFlags);
            }
        }

        // 检索引擎同步：失败 → 原样上抛（阻断）；绑定 store 的 KB 走引擎口。
        IndexEngine boundEngine =
                (!enabledUpdates.isEmpty() || !tagUpdates.isEmpty())
                        ? vectorWrites.boundEngine(kb, tid) : null;
        if (boundEngine != null) {
            try {
                if (!enabledUpdates.isEmpty()) {
                    boundEngine.batchUpdateChunkEnabledStatus(enabledUpdates);
                }
                if (!tagUpdates.isEmpty()) {
                    boundEngine.batchUpdateChunkTagId(tagUpdates);
                }
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(
                        e.getMessage() == null ? String.valueOf(e) : e.getMessage(), e);
            }
        } else {
            if (!enabledUpdates.isEmpty()) {
                vectorStore.batchUpdateChunkEnabledStatus(enabledUpdates);
            }
            if (!tagUpdates.isEmpty()) {
                vectorStore.batchUpdateChunkTagId(tagUpdates);
            }
        }
        log.info("FAQ fields batch updated: kb={}, by_id={}, by_tag={}",
                kb.getId(), req.byId() == null ? 0 : req.byId().size(),
                req.byTag() == null ? 0 : req.byTag().size());
        activityAudit.record(tid, "knowledge_base", kb.getId(), AuditAction.KNOWLEDGE_UPDATED,
                "faq_entry", "",
                Map.of("count", req.byId() == null ? 0 : req.byId().size(),
                        "tag_groups", req.byTag() == null ? 0 : req.byTag().size(),
                        "batch", true));
    }
}
