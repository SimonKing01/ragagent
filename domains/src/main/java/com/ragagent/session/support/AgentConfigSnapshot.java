package com.ragagent.session.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.platform.web.JsonMappers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

/**
 * 智能体配置快照：把「追问建议需要的部分」从 agent 配置里摘出来，随 assistant 消息落库。
 *
 * <p>追问建议是异步派生流程，主流程（SSE）结束后才发起，那时拿不到原始请求上下文，
 * 只能依赖 {@code messages.execution_context} 里的快照。缺了它，{@code MessageSuggestionService}
 * 会把每一次生成都判成 {@code disabled} —— 表现为「智能体开了回答后推荐但一直没效果」。</p>
 *
 * <p>{@code configHash} 只用于判断「配置是否变过、推荐集要不要重建」，取配置序列化的
 * SHA-256 前 16 位十六进制即可；无配置（纯知识库对话）固定为 {@code no-agent-config}。</p>
 */
public final class AgentConfigSnapshot {

    /** 没有智能体配置时使用的固定哈希。 */
    public static final String NO_CONFIG_HASH = "no-agent-config";

    private static final int HASH_PREFIX_LENGTH = 16;

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    private AgentConfigSnapshot() {
    }

    /**
     * 快照结果：配置哈希 + 追问建议配置（原样透传，内层键保持 agent 域的写法）。
     */
    public record Snapshot(String configHash, Map<String, Object> questionSuggestions) {
    }

    /**
     * 从 agent 配置节点取快照；{@code null} / 空对象都视为「无配置」。
     */
    public static Snapshot of(JsonNode agentConfig) {
        if (agentConfig == null || agentConfig.isNull() || agentConfig.isEmpty()) {
            return new Snapshot(NO_CONFIG_HASH, null);
        }
        return new Snapshot(hashOf(agentConfig), questionSuggestionsOf(agentConfig));
    }

    /** 配置哈希：序列化后 SHA-256 的前 16 位十六进制。 */
    public static String hashOf(JsonNode agentConfig) {
        if (agentConfig == null || agentConfig.isNull() || agentConfig.isEmpty()) {
            return NO_CONFIG_HASH;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] raw = digest.digest(
                    MAPPER.writeValueAsString(agentConfig).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(HASH_PREFIX_LENGTH);
            for (byte b : raw) {
                if (sb.length() >= HASH_PREFIX_LENGTH) {
                    break;
                }
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.substring(0, Math.min(HASH_PREFIX_LENGTH, sb.length()));
        } catch (NoSuchAlgorithmException | JsonProcessingException e) {
            throw new IllegalStateException("agent config hash failed", e);
        }
    }

    private static Map<String, Object> questionSuggestionsOf(JsonNode agentConfig) {
        JsonNode suggestions = agentConfig.get("questionSuggestions");
        if (suggestions == null || suggestions.isNull() || !suggestions.isObject()) {
            return null;
        }
        return MAPPER.convertValue(suggestions, new TypeReference<>() {
        });
    }
}
