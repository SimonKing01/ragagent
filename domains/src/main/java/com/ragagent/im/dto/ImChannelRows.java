package com.ragagent.im.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.im.domain.ImChannelEntity;
import com.ragagent.platform.web.JsonMappers;
import java.util.LinkedHashMap;
import java.util.Map;

/** IM 渠道响应行：键序与旧控制器逐字一致。 */
public final class ImChannelRows {

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    private ImChannelRows() {
    }

    /** 渠道行（create/update/toggle 的资源行），键名即字段名。 */
    public static Map<String, Object> channelRow(ImChannelEntity ch) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", ch.getId());
        m.put("tenantId", ch.getTenantId());
        m.put("agentId", ch.getAgentId());
        m.put("platform", ch.getPlatform());
        m.put("name", ch.getName());
        m.put("enabled", ch.isEnabled());
        m.put("mode", ch.getMode());
        m.put("outputMode", ch.getOutputMode());
        m.put("knowledgeBaseId", ch.getKnowledgeBaseId());
        m.put("botIdentity", ch.getBotIdentity());
        m.put("sessionMode", ch.getSessionMode());
        m.put("credentials", rawJson(ch.getCredentials()));
        m.put("createdAt", ch.getCreatedAt());
        m.put("updatedAt", ch.getUpdatedAt());
        m.put("deletedAt", null);
        return m;
    }

    /** per-agent 列表行（IMChannelSummary：凭据不出行，只出 credentialsConfigured）。 */
    public static Map<String, Object> summaryRow(ImChannelEntity ch) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", ch.getId());
        m.put("tenantId", ch.getTenantId());
        m.put("agentId", ch.getAgentId());
        m.put("platform", ch.getPlatform());
        m.put("name", ch.getName());
        m.put("enabled", ch.isEnabled());
        m.put("mode", ch.getMode());
        m.put("outputMode", ch.getOutputMode());
        m.put("knowledgeBaseId", ch.getKnowledgeBaseId());
        m.put("botIdentity", ch.getBotIdentity());
        m.put("sessionMode", ch.getSessionMode());
        m.put("credentialsConfigured", credentialsConfigured(ch.getCredentials()));
        m.put("createdAt", ch.getCreatedAt());
        m.put("updatedAt", ch.getUpdatedAt());
        return m;
    }

    /** trim 后非 "" 且非 "{}"。 */
    private static boolean credentialsConfigured(String credentials) {
        String s = credentials == null ? "" : credentials.trim();
        return !s.isEmpty() && !"{}".equals(s);
    }

    /** credentials 是任意 jsonb：坏 JSON 在绑定阶段已被拒，这里容错回 "{}"。 */
    private static Object rawJson(String raw) {
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
