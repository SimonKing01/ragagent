package com.ragagent.im.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.im.domain.ImChannelEntity;
import com.ragagent.platform.web.JsonMappers;
import java.time.OffsetDateTime;

/** IM 渠道响应行：键序与旧控制器逐字一致。 */
public final class ImChannelRows {

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    private ImChannelRows() {
    }

    /** 渠道资源行（create/update/toggle）：键名即字段名，{@code deletedAt} 恒输出 null。 */
    public record ChannelRow(
            String id,
            Long tenantId,
            String agentId,
            String platform,
            String name,
            boolean enabled,
            String mode,
            String outputMode,
            String knowledgeBaseId,
            String botIdentity,
            String sessionMode,
            JsonNode credentials,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            Object deletedAt) {
    }

    /** 渠道行（create/update/toggle 的资源行），键名即字段名。 */
    public static ChannelRow channelRow(ImChannelEntity ch) {
        return new ChannelRow(
                ch.getId(),
                ch.getTenantId(),
                ch.getAgentId(),
                ch.getPlatform(),
                ch.getName(),
                ch.isEnabled(),
                ch.getMode(),
                ch.getOutputMode(),
                ch.getKnowledgeBaseId(),
                ch.getBotIdentity(),
                ch.getSessionMode(),
                rawJson(ch.getCredentials()),
                ch.getCreatedAt(),
                ch.getUpdatedAt(),
                null);
    }

    /** per-agent 列表行：凭据不出行，只出 {@code credentialsConfigured}。 */
    public record ChannelSummary(
            String id,
            Long tenantId,
            String agentId,
            String platform,
            String name,
            boolean enabled,
            String mode,
            String outputMode,
            String knowledgeBaseId,
            String botIdentity,
            String sessionMode,
            boolean credentialsConfigured,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
    }

    /** per-agent 列表行（IMChannelSummary：凭据不出行，只出 credentialsConfigured）。 */
    public static ChannelSummary summaryRow(ImChannelEntity ch) {
        return new ChannelSummary(
                ch.getId(),
                ch.getTenantId(),
                ch.getAgentId(),
                ch.getPlatform(),
                ch.getName(),
                ch.isEnabled(),
                ch.getMode(),
                ch.getOutputMode(),
                ch.getKnowledgeBaseId(),
                ch.getBotIdentity(),
                ch.getSessionMode(),
                credentialsConfigured(ch.getCredentials()),
                ch.getCreatedAt(),
                ch.getUpdatedAt());
    }

    /** trim 后非 "" 且非 "{}"。 */
    private static boolean credentialsConfigured(String credentials) {
        String s = credentials == null ? "" : credentials.trim();
        return !s.isEmpty() && !"{}".equals(s);
    }

    /** credentials 是任意 jsonb：坏 JSON 在绑定阶段已被拒，这里容错回 "{}"。 */
    private static JsonNode rawJson(String raw) {
        if (raw == null || raw.isEmpty()) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    // ── 工具 ──
}
