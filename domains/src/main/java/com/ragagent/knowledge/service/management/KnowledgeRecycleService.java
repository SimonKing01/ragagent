package com.ragagent.knowledge.service.management;

import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.domain.KnowledgeTenants;
import com.ragagent.knowledge.index.KnowledgeIndexCleaner;
import com.ragagent.knowledge.repository.ChunkRepository;
import com.ragagent.knowledge.repository.KnowledgeRepository;
import com.ragagent.knowledge.service.KnowledgeAccessHelper;
import com.ragagent.knowledge.service.access.ChunkAccessGuard;
import com.ragagent.knowledge.service.image.ExtractedImageStore;
import com.ragagent.knowledge.storage.TenantFileStorage;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * 知识删除的统一回收用例：单条与批量共用同一份资源清单和失败语义。
 *
 * <p><b>两段式</b>：库内状态在一次事务里推进（knowledge + chunk 软删），物理资源
 * （文件、抽取图片、向量/关键词索引、图谱）在<b>提交之后</b>清理。顺序不能反——
 * 外部资源无法靠数据库回滚，先删文件再写库，一旦写库失败就留下不可逆的缺文件状态。</p>
 *
 * <p><b>失败语义</b>：物理清理失败只告警，不回滚已提交的软删（检索侧已不可见）；
 * 需要可靠保证时由后续的清理任务重试，本类只负责把"要清理什么"确定下来。</p>
 */
@Service
public class KnowledgeRecycleService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRecycleService.class);

    private final KnowledgeRepository knowledgeRepository;
    private final ChunkRepository chunkRepository;
    private final TenantFileStorage fileStorage;
    private final ExtractedImageStore extractedImageStore;
    private final KnowledgeIndexCleaner indexCleaner;
    private final KnowledgeAccessHelper access;

    public KnowledgeRecycleService(KnowledgeRepository knowledgeRepository,
                                   ChunkRepository chunkRepository,
                                   TenantFileStorage fileStorage,
                                   ExtractedImageStore extractedImageStore,
                                   KnowledgeIndexCleaner indexCleaner,
                                   KnowledgeAccessHelper access) {
        this.knowledgeRepository = knowledgeRepository;
        this.chunkRepository = chunkRepository;
        this.fileStorage = fileStorage;
        this.extractedImageStore = extractedImageStore;
        this.indexCleaner = indexCleaner;
        this.access = access;
    }

    /**
     * 单条回收：事务内推进库内状态，返回 task id（对外契约不变）。
     */
    @Transactional
    public String recycleOne(String knowledgeId) {
        Knowledge k = access.getKnowledge(knowledgeId);
        KnowledgeBase kb = access.findKb(k.getKnowledgeBaseId());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        knowledgeRepository.softDelete(k.getId(), now);
        chunkRepository.softDeleteByKnowledgeId(k.getId(), now);
        scheduleCleanup(List.of(targetOf(k, kb)));
        return UUID.randomUUID().toString();
    }

    /**
     * 批量回收：逐条软删（已删行 no-op、不刷新时间戳），物理清理统一在提交后。
     *
     * @param kbId 归属校验由调用方完成，这里只用来取索引回收所需的 KB 配置
     */
    @Transactional
    public String recycleBatch(String kbId, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return UUID.randomUUID().toString();
        }
        KnowledgeBase kb = access.findKb(kbId);
        long tenantId = KnowledgeTenants.tenantId();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<CleanupTarget> targets = new ArrayList<>();
        for (String id : ids) {
            Knowledge k = knowledgeRepository.findById(tenantId, id);
            if (k == null) {
                continue;
            }
            boolean deleted = knowledgeRepository.softDeleteIfActive(id, now) > 0;
            chunkRepository.softDeleteByKnowledgeId(id, now);
            if (deleted) {
                targets.add(targetOf(k, kb));
            }
        }
        scheduleCleanup(targets);
        return UUID.randomUUID().toString();
    }

    /**
     * 显式租户的单条回收：供同步 worker 等**没有租户上下文**的调用方使用
     * （datasource 同步在线程池里跑，{@code TenantContext} 为空）。
     *
     * <p>幂等：行不存在或已软删都只做一次软删 + 清理，重复调用不报错。</p>
     */
    @Transactional
    public void recycleForTenant(long tenantId, String knowledgeId, OffsetDateTime now) {
        if (knowledgeId == null || knowledgeId.isEmpty()) {
            return;
        }
        Knowledge k = knowledgeRepository.findById(tenantId, knowledgeId);
        if (k == null) {
            return;
        }
        KnowledgeBase kb = access.findKb(k.getKnowledgeBaseId());
        knowledgeRepository.softDelete(k.getId(), now);
        chunkRepository.softDeleteByKnowledgeId(k.getId(), now);
        scheduleCleanup(List.of(targetOf(k, kb)));
    }

    /**
     * 清空知识库：资源清单与单条删除一致，范围是 KB 内全部存活行。
     *
     * <p>不单独开事务——调用方（清空入口 / 删库）通常已在事务里；无事务时清理立即执行。
     * 行会被直接软删，`deleting` 只作为过渡标记，不再留下无终态的中间态。</p>
     *
     * @return 本次回收的条数
     */
    public int recycleKnowledgeBaseContents(String kbId) {
        KnowledgeBase kb = access.findKb(kbId);
        long tenantId = KnowledgeTenants.tenantId();
        List<Knowledge> rows = knowledgeRepository.listByTenantAndKb(tenantId, kbId);
        for (Knowledge row : rows) {
            ChunkAccessGuard.rejectMovingKnowledge(row);
        }
        if (rows.isEmpty()) {
            return 0;
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<String> ids = rows.stream().map(Knowledge::getId).toList();
        knowledgeRepository.setParseStatusDeleting(ids, now);
        List<CleanupTarget> targets = new ArrayList<>();
        for (Knowledge row : rows) {
            knowledgeRepository.softDelete(row.getId(), now);
            chunkRepository.softDeleteByKnowledgeId(row.getId(), now);
            targets.add(targetOf(row, kb));
        }
        scheduleCleanup(targets);
        return rows.size();
    }

    /** 清空知识库（HTTP 入口）：一个事务内推进库内状态，提交后清理物理资源。 */
    @Transactional
    public int clearKnowledgeBase(String kbId) {
        return recycleKnowledgeBaseContents(kbId);
    }

    private static CleanupTarget targetOf(Knowledge k, KnowledgeBase kb) {
        long tenantId = k.getTenantId() == null ? KnowledgeTenants.tenantId() : k.getTenantId();
        return new CleanupTarget(tenantId, k.getId(), k.getFilePath(), k, kb);
    }

    /**
     * 有事务同步 → 注册 afterCommit（此时恢复点已落定）；无事务（单测直调）→ 立即执行。
     */
    private void scheduleCleanup(List<CleanupTarget> targets) {
        if (targets.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cleanup(targets);
                }
            });
            return;
        }
        cleanup(targets);
    }

    /** 逐项独立兜底：一项失败不能挡住同批其余文档的回收；有失败的行标记为待重试。 */
    private void cleanup(List<CleanupTarget> targets) {
        for (CleanupTarget t : targets) {
            List<String> failed = cleanupOne(t);
            if (!failed.isEmpty()) {
                markCleanupFailed(t, failed);
            }
        }
    }

    /** 执行单条的全部物理清理，返回失败资源名（空 = 全部成功）。 */
    private List<String> cleanupOne(CleanupTarget t) {
        List<String> failed = new ArrayList<>();
        try {
            fileStorage.delete(t.tenantId(), t.knowledgeId(), t.filePath());
        } catch (RuntimeException e) {
            log.warn("File cleanup failed, tenant={} knowledge={}", t.tenantId(), t.knowledgeId(), e);
            failed.add("file");
        }
        try {
            extractedImageStore.releaseFor(t.tenantId(), t.knowledgeId());
        } catch (RuntimeException e) {
            log.warn("Extracted image cleanup failed, tenant={} knowledge={}",
                    t.tenantId(), t.knowledgeId(), e);
            failed.add("images");
        }
        try {
            int failedItems = indexCleaner.cleanup(t.knowledge(), t.kb());
            if (failedItems > 0) {
                log.warn("Index cleanup incomplete, tenant={} knowledge={} failedItems={}",
                        t.tenantId(), t.knowledgeId(), failedItems);
                failed.add("index");
            }
        } catch (RuntimeException e) {
            log.warn("Index cleanup failed, tenant={} knowledge={}", t.tenantId(), t.knowledgeId(), e);
            failed.add("index");
        }
        return failed;
    }

    /**
     * 清理失败的行打上 {@code delete_failed} 终态：行已软删（检索不可见），
     * 但物理资源需要重启后重试——否则失败只留在日志里，无任何补救入口。
     */
    private void markCleanupFailed(CleanupTarget t, List<String> failed) {
        try {
            knowledgeRepository.updateStatusColumns(t.knowledgeId(), Knowledge.PARSE_DELETE_FAILED,
                    "cleanup failed: " + String.join(",", failed),
                    null, OffsetDateTime.now(ZoneOffset.UTC));
        } catch (RuntimeException e) {
            log.warn("Failed to mark cleanup failure, knowledge={}", t.knowledgeId(), e);
        }
    }

    /**
     * 启动重试：扫描 {@code delete_failed} 行并逐条重跑物理清理（幂等）。
     *
     * @return 本次完成的行数
     */
    public int retryFailedCleanups(int limit) {
        List<String> ids = knowledgeRepository.listCleanupFailedIds(limit);
        int done = 0;
        for (String id : ids) {
            if (retryCleanup(id)) {
                done++;
            }
        }
        if (!ids.isEmpty()) {
            log.info("Retried failed knowledge cleanups: attempted={} completed={}", ids.size(), done);
        }
        return done;
    }

    /**
     * 重试单条已软删文档的物理清理；全部成功则落 {@code deleted} 终态。
     *
     * @return 是否清理完成
     */
    public boolean retryCleanup(String knowledgeId) {
        Knowledge k = knowledgeRepository.findByIdAny(knowledgeId);
        if (k == null) {
            return false;
        }
        CleanupTarget target = targetOf(k, access.findKb(k.getKnowledgeBaseId()));
        List<String> failed = cleanupOne(target);
        if (!failed.isEmpty()) {
            markCleanupFailed(target, failed);
            return false;
        }
        knowledgeRepository.updateStatusColumns(knowledgeId, Knowledge.PARSE_DELETED, "",
                null, OffsetDateTime.now(ZoneOffset.UTC));
        return true;
    }

    /** 物理清理所需的快照：实体行在提交后仍可读，避免二次查库拿到已删行。 */
    private record CleanupTarget(long tenantId, String knowledgeId, String filePath,
                                 Knowledge knowledge, KnowledgeBase kb) {
    }
}
