package com.ragagent.knowledge.service.management;

import com.ragagent.contract.knowledge.KnowledgeIngest;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.domain.KnowledgeTenants;
import com.ragagent.knowledge.repository.KnowledgeRepository;
import com.ragagent.knowledge.service.KnowledgeAccessHelper;
import com.ragagent.knowledge.service.access.ChunkAccessGuard;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 批量面：批量删除 / 批量重解析 / 重建索引 / 清空 KB 内容。
 * <p>复位/全列写复用 {@link KnowledgeFileService}/{@link KnowledgeParseService}（同包开放），
 * moving 状态防线复用 {@link ChunkAccessGuard#rejectMovingKnowledge}；
 * 门面 helper（requireKb/getKnowledge/tenantId）下沉在 {@link KnowledgeAccessHelper}，直接依赖不回注门面。</p>
 */
@Service
public class KnowledgeBatchOpsService {

    private final KnowledgeRepository knowledgeRepository;
    private final KnowledgeIngest knowledgeIngest;
    private final KnowledgeFileService knowledgeFileService;
    private final KnowledgeAccessHelper access;
    private final KnowledgeRecycleService recycleService;

    public KnowledgeBatchOpsService(KnowledgeRepository knowledgeRepository,
                                    KnowledgeIngest knowledgeIngest,
                                    KnowledgeFileService knowledgeFileService,
                                    KnowledgeAccessHelper access,
                                    KnowledgeRecycleService recycleService) {
        this.knowledgeRepository = knowledgeRepository;
        this.knowledgeIngest = knowledgeIngest;
        this.knowledgeFileService = knowledgeFileService;
        this.access = access;
        this.recycleService = recycleService;
    }

    // ── 批量删除 / 批量重解析 / 清空（任务队列 → 同步尽力而为，响应契约一致） ──

    /**
     * 批量删除：与单条共用 {@link KnowledgeRecycleService} 的资源清单（库内软删 +
     * 提交后清文件/图片/索引）。注意调用方已做过 RejectMoving/kb 归属校验。
     */
    public String batchDeleteKnowledge(String kbId, List<String> ids) {
        return recycleService.recycleBatch(kbId, ids);
    }

    /**
     * 逐条 reset 到 pending + 入队。
     * 调用方已完成 requireKnowledgeInKB / RejectMoving 校验。
     */
    public String batchReparseKnowledge(String kbId, List<String> ids) {
        KnowledgeBase kb = access.requireKb(kbId);
        for (String id : ids) {
            Knowledge k = access.getKnowledge(id);
            KnowledgeParseService.resetKnowledgeForReparse(k, kb);
            knowledgeFileService.updateKnowledgeRow(k, k.getMetadata());
            knowledgeIngest.enqueueProcessing(k.getId());
        }
        return UUID.randomUUID().toString();
    }

    /**
     * 重建知识库索引：索引策略
     * （vector/keyword/wiki/graph）变更后对 KB 内全部知识重跑处理管线，使 chunk/
     * 向量/图谱与新策略一致。复用 reparse 的复位与入队路径（docreader 重解析在
     * @return 提交重建的知识条数（document_count）
     */
    public int rebuildKnowledgeBaseIndex(String kbId) {
        KnowledgeBase kb = access.requireKb(kbId);
        List<Knowledge> rows = knowledgeRepository.listByTenantAndKb(KnowledgeTenants.tenantId(), kbId);
        for (Knowledge k : rows) {
            KnowledgeParseService.resetKnowledgeForReparse(k, kb);
            knowledgeFileService.updateKnowledgeRow(k, k.getMetadata());
            knowledgeIngest.enqueueProcessing(k.getId());
        }
        return rows.size();
    }

    /**
     * 清空知识库：库内软删与物理回收全部复用 {@link KnowledgeRecycleService}
     * （资源清单与单条删除一致），不再只置 {@code deleting} 而没有后续动作。
     *
     * @return 本次回收的条数
     */
    public int clearKnowledgeBaseContents(String kbId) {
        return recycleService.clearKnowledgeBase(kbId);
    }

    /** 内部：worker 使用的按 id 加载（无租户条件，任务可能跨请求线程） */
    public Knowledge loadById(String id) {
        return knowledgeRepository.findByIdAny(id);
    }

    public void updateStatus(String id, String parseStatus, String errorMessage, Boolean enable) {
        knowledgeRepository.updateStatusColumns(id, parseStatus, errorMessage, enable,
                OffsetDateTime.now(ZoneOffset.UTC));
    }
}
