package com.ragagent.audit.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.audit.domain.AuditLog;
import com.ragagent.audit.service.AuditLogService;
import com.ragagent.contract.audit.AuditWritePort;
import com.ragagent.platform.web.JsonMappers;
import org.springframework.stereotype.Component;

/**
 * {@link AuditWritePort} 的 audit 侧实现：记录 → {@code AuditLog} 实体 →
 * {@code AuditLogService#logBestEffort}（尽力而为）。
 *
 * <p>详情以 JSON 字符串过端口（调用方本就以 Jackson 构造 details），此处解析回 {@code JsonNode}；
 * 解析失败按"无详情"处理——审计是尽力而为路径，不应因详情形态问题影响业务响应。</p>
 */
@Component
public class AuditWriteAdapter implements AuditWritePort {

    private final AuditLogService auditLogService;

    public AuditWriteAdapter(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @Override
    public void logBestEffort(AuditRecord record) {
        AuditLog entry = new AuditLog();
        entry.setTenantId(record.tenantId());
        entry.setActorUserId(nullSafe(record.actorUserId()));
        entry.setActorRole(nullSafe(record.actorRole()));
        entry.setAction(nullSafe(record.action()));
        entry.setTargetType(nullSafe(record.targetType()));
        entry.setTargetId(nullSafe(record.targetId()));
        entry.setTargetUserId(nullSafe(record.targetUserId()));
        entry.setScopeType(nullSafe(record.scopeType()));
        entry.setScopeId(nullSafe(record.scopeId()));
        entry.setOutcome(nullSafe(record.outcome()));
        entry.setDetails(parseDetails(record.detailsJson()));
        auditLogService.logBestEffort(entry);
    }

    private static String nullSafe(String v) {
        return v == null ? "" : v;
    }

    private static JsonNode parseDetails(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return JsonMappers.lenient().readTree(json);
        } catch (Exception e) {
            return null; // 尽力而为：详情不可解析时不带详情，不抛
        }
    }
}
