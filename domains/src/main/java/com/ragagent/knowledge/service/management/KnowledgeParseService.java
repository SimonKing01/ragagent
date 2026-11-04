package com.ragagent.knowledge.service.management;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.contract.error.BizException;
import com.ragagent.contract.knowledge.KnowledgeIngest;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.domain.KnowledgeTenants;
import com.ragagent.knowledge.domain.span.SpanTracker;
import com.ragagent.knowledge.repository.DocumentParser;
import com.ragagent.knowledge.service.KnowledgeAccessHelper;
import com.ragagent.knowledge.support.KnowledgeMetadataOverrides;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;

/**
 * 解析生命周期操作：重新解析（复位状态后入队）与取消解析（状态机校验 + span 收口）。
 */
@Service
public class KnowledgeParseService {


    private final KnowledgeAccessHelper access;
    private final KnowledgeFolderService folderService;
    private final KnowledgeFileService fileService;
    private final DocumentParser documentParser;
    private final SpanTracker spanTracker;
    private final KnowledgeIngest knowledgeIngest;

    public KnowledgeParseService(
                            KnowledgeAccessHelper access,
                            KnowledgeFolderService folderService,
                            KnowledgeFileService fileService,
                            DocumentParser documentParser,
                            SpanTracker spanTracker,
                            KnowledgeIngest knowledgeIngest) {
        this.access = access;
        this.folderService = folderService;
        this.fileService = fileService;
        this.documentParser = documentParser;
        this.spanTracker = spanTracker;
        this.knowledgeIngest = knowledgeIngest;
    }

    /**
 * 重解析（reset + 入队）。
 *
 * <p> 说明：**接管语义保留**（契约：连点两次重解析第二次返回 200 ——
 * {@code kg-reparse-null-config} 金片；对用户而言"再点一次就再跑一遍"也合理）。
 * 危险不在接管本身，而在"接管后旧尝试仍能写终态 / 清场到新尝试的数据"——那两处
 * 已按尝试划界（见 {@code KnowledgeProcessWorker.failOrComplete} 的 CAS 守卫与
 * 失败清场范围说明）；若要改成"处理中拒绝重解析"，需先有代次列（parse_attempt）才能
 * 把清场与终态写入完全按代隔离（登记在 HANDOFF）。</p>
 */
    public Knowledge reparseKnowledge(String id) {
        return reparseKnowledge(id, null);
    }

    /**
     * 重解析并**替换**逐文档处理覆盖。
     *
     * <p>重解析弹窗里的选择（如「生成推荐问题」）必须覆盖上一次导入时落的
     * {@code metadata.processOverrides}，否则旧的开关会一直生效——典型现象：
     * 首次导入关掉推荐问题、重解析时打开，结果仍不生成。</p>
     *
     * @param processConfig 本次重解析的覆盖；null/空对象表示沿用既有 metadata
     */
    public Knowledge reparseKnowledge(String id, JsonNode processConfig) {
        Knowledge existing = folderService.loadKnowledgeWrite(id);
        KnowledgeBase kb = access.requireKb(existing.getKnowledgeBaseId());
        resetKnowledgeForReparse(existing, kb);
        existing.setMetadata(KnowledgeMetadataOverrides.merge(existing.getMetadata(), processConfig));
        fileService.updateKnowledgeRow(existing, existing.getMetadata());
        knowledgeIngest.enqueueProcessing(existing.getId());
        return existing;
    }
    public static void resetKnowledgeForReparse(Knowledge k, KnowledgeBase kb) {
        k.setParseStatus(Knowledge.PARSE_PENDING);
        // 代次 +1：旧 attempt 之后的状态 CAS 与清场都会因代次不匹配而失效
        k.setParseAttempt(k.getParseAttempt() + 1);
        k.setEnableStatus("disabled");
        k.setDescription("");
        k.setProcessedAt(null);
        k.setErrorMessage("");
        k.setEmbeddingModelId(kb.getEmbeddingModelId());
        k.setPendingSubtasksCount(0);
    }

    /**
     * cancelled 幂等、
     * completed/failed → 400 "解析已结束，无法取消"、deleting → 400 "知识正在删除中，
     * 无法取消解析"、其余状态（含 unknown）放行改 cancelled。
     */
    public Knowledge cancelKnowledgeParse(String id) {
        Knowledge existing = documentParser.findById(KnowledgeTenants.tenantId(), id);
        if (existing == null) {
            throw BizException.notFound("knowledge not found");
        }
        switch (existing.getParseStatus() == null ? "" : existing.getParseStatus()) {
            case Knowledge.PARSE_CANCELLED -> {
                return existing; // 幂等
            }
            case Knowledge.PARSE_COMPLETED, Knowledge.PARSE_FAILED ->
                throw BizException.badRequest("解析已结束，无法取消");
            case Knowledge.PARSE_DELETING ->
                throw BizException.badRequest("知识正在删除中，无法取消解析");
            default -> {
            }
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        documentParser.markCancelled(existing.getId(), now, "用户已取消解析");
        existing.setParseStatus(Knowledge.PARSE_CANCELLED);
        existing.setErrorMessage("用户已取消解析");
        existing.setPendingSubtasksCount(0);
        existing.setUpdatedAt(now);
        // 取消时收口进度 span：LatestAttempt → AbortAttempt（平扫非终态子 span +
        // 收口 root 为 cancelled；best-effort，attempt 为 null/缺失时 no-op）
        int spanAttempt = spanTracker.latestAttempt(existing.getId());
        if (spanAttempt > 0) {
            spanTracker.abortAttempt(existing.getId(), spanAttempt,
                    "USER_CANCELLED", "用户已取消解析", "用户已取消解析");
        }
        return existing;
    }

    /**
     * manual 流 metadata.content；
     * document 走本地文件。路径解析：resource:// 与
     * "invalid file path: path traversal denied: ..." 原文（契约样例锁定）。
     *         非 Seeker → Accept-Ranges: none + 显式 CL），document = 存储层打开
     *         （本地 *os.File 可 seek → bytes + Range；云按 provider 能力，W5γ5.4 ①b）
     */
}
