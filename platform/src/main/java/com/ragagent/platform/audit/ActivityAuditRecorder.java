package com.ragagent.platform.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.contract.audit.AuditOutcome;
import com.ragagent.contract.audit.AuditWritePort;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.platform.web.JsonMappers;
import java.util.Map;
import java.util.TreeMap;

/**
 * 业务活动审计的统一写入器：租户兜底、操作者提取、details 字母序与 best-effort 语义
 * 收敛在一处，调用方只描述"谁在什么作用域里做了什么"。
 *
 * <p>作用域类型由调用方传入（例如知识库活动的 {@code knowledge_base}），本类不持有
 * 任何业务域的词汇；任务上下文（taskId / trigger / processingStatus）仅在
 * {@code taskContext=true} 时参与组装，供同步类活动使用。</p>
 *
 * <p>写失败只记日志（{@link AuditWritePort#logBestEffort}），不影响业务响应。</p>
 */
public final class ActivityAuditRecorder {

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    private final AuditWritePort writer;

    public ActivityAuditRecorder(AuditWritePort writer) {
        this.writer = writer;
    }

    /** 成功结果、无任务上下文的一条活动。 */
    public void record(long tenantId, String scopeType, String scopeId, String action,
                       String targetType, String targetId, Map<String, Object> details) {
        record(tenantId, scopeType, scopeId, action, targetType, targetId,
                AuditOutcome.SUCCESS, details, "", "", false, false);
    }

    /**
     * 完整入口。
     *
     * @param outcome     审计结果常量；空串按成功处理
     * @param taskContext 是否把 taskId/trigger/processingStatus 写进 details
     * @param suppressed  为 true 时整条丢弃（同步期间的逐条变更由调用方压掉）
     */
    public void record(long tenantId, String scopeType, String scopeId, String action,
                       String targetType, String targetId, String outcome,
                       Map<String, Object> details, String taskId, String trigger,
                       boolean taskContext, boolean suppressed) {
        if (suppressed || scopeId == null || scopeId.isEmpty()
                || action == null || action.isEmpty()) {
            return;
        }
        long tid = tenantId;
        if (tid == 0) {
            Long ctx = TenantContext.currentTenantId();
            tid = ctx == null ? 0L : ctx;
        }
        if (tid == 0) {
            return;
        }
        String effectiveOutcome = outcome == null || outcome.isEmpty() ? AuditOutcome.SUCCESS : outcome;

        Map<String, Object> merged = new TreeMap<>();
        if (details != null) {
            merged.putAll(details);
        }
        if (taskContext) {
            putIfPresent(merged, "taskId", taskId);
            putIfPresent(merged, "trigger", trigger);
            if (!merged.containsKey("processingStatus")) {
                switch (effectiveOutcome) {
                    case AuditOutcome.ACCEPTED -> merged.put("processingStatus", "pending");
                    case AuditOutcome.SUCCESS -> merged.put("processingStatus", "completed");
                    case AuditOutcome.PARTIAL -> merged.put("processingStatus", "partial");
                    case AuditOutcome.FAILED, AuditOutcome.DENIED ->
                            merged.put("processingStatus", "failed");
                    case AuditOutcome.CANCELED -> merged.put("processingStatus", "canceled");
                    default -> {
                        // 未知结果不猜 processingStatus
                    }
                }
            }
        }

        JsonNode detailsNode = merged.isEmpty() ? null : MAPPER.valueToTree(merged);
        String actorId = nullToEmpty(TenantContext.currentUserId());
        String actorRole = actorId.isEmpty() ? "" : nullToEmpty(TenantContext.currentRole());

        writer.logBestEffort(new AuditWritePort.AuditRecord(
                tid,
                actorId,
                actorRole,
                action,
                targetType,
                targetId,
                "",
                scopeType == null ? "" : scopeType,
                scopeId,
                effectiveOutcome,
                detailsNode == null ? null : detailsNode.toString()));
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isEmpty() && !target.containsKey(key)) {
            target.put(key, value);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
