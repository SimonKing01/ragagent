package com.ragagent.platform.prompt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.contract.model.ChatMessage;
import com.ragagent.contract.model.ChatOptions;
import com.ragagent.platform.security.Hashes;
import com.ragagent.platform.web.JsonMappers;
import java.util.ArrayList;
import java.util.List;

/**
 * 提示词前缀指纹与缓存 key（纯函数族）。
 *
 * <p><b>拆分</b>：原先这三个纯函数住在 {@code model-runtime} 的
 * {@code adapter.provider.chat.PromptCache} 里，与 provider 相关的账目/策略方法同处一类——
 * 而它们只依赖契约形状与 Jackson（无 provider 词汇），业务域（wiki 摄取）需要它却只能穿透到
 * 模型运行时实现面。现按"纯函数归技术面"拆到这里；{@code PromptCache} 同名方法改为委托，
 * 内部调用方零改动。</p>
 *
 * <p><b>序列化契约对字节敏感</b>：指纹对序列化字节敏感（字段序由各 DTO 的注解钉住），
 * 故本类的键序与消息体序列化方式与拆分前逐字一致。</p>
 */
public final class PromptFingerprints {

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    private PromptFingerprints() {
    }

    /**
     * 短、不可逆的标识，用于日志与缓存路由。
     * 逐段写入 UTF-8 字节，每段后跟一个 NUL 分隔符（防 "ab"+"c" 与 "a"+"bc" 撞车）。
     */
    public static String fingerprintPromptPrefix(String... parts) {
        StringBuilder joined = new StringBuilder();
        if (parts != null) {
            for (String part : parts) {
                joined.append(part == null ? "" : part).append('\0');
            }
        }
        return Hashes.sha256Hex(joined.toString()).substring(0, 16);
    }

    /**
     * 哈希普通 chat 与 agent 请求共有的稳定前缀——
     * 开头的 system 消息 + 确定性 tool schema。动态的对话/用户消息不参与。
     *
     * <p>序列化契约：顶层键序固定 system, tools，空则省略；消息体经
     * {@code valueToTree} 序列化，字段序由各 DTO 的 {@code @JsonPropertyOrder} 注解钉住
     * （指纹对序列化字节敏感）。</p>
     */
    public static String promptPrefixFingerprint(List<ChatMessage> messages, ChatOptions opts) {
        List<ChatMessage> system = new ArrayList<>();
        if (messages != null) {
            for (ChatMessage message : messages) {
                if (!"system".equals(message.getRole())) {
                    break;
                }
                system.add(message);
            }
        }
        ObjectNode prefix = MAPPER.createObjectNode();
        if (!system.isEmpty()) {
            ArrayNode systemNode = prefix.putArray("system");
            for (ChatMessage m : system) {
                systemNode.add(MAPPER.valueToTree(m));
            }
        }
        if (opts != null && opts.getTools() != null && !opts.getTools().isEmpty()) {
            prefix.set("tools", MAPPER.valueToTree(opts.getTools()));
        }
        return fingerprintPromptPrefix(stringify(prefix));
    }

    /**
     * 派生的、不透明的进程内协调 key。
     * 租户与模型标识只参与哈希，不以明文驻留。
     */
    public static String buildPromptCacheKey(long tenantId, String modelId, String purpose,
                                             String prefixFingerprint) {
        // tenantID 用无符号十进制表示，避免高位租户 ID 输出成负数
        return "wk-" + fingerprintPromptPrefix(
                Long.toUnsignedString(tenantId), modelId, purpose, prefixFingerprint);
    }

    private static String stringify(ObjectNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            // ObjectNode 序列化不会失败；真失败时返回空串会让指纹退化，故直接抛出
            throw new IllegalStateException("failed to serialize prompt prefix", e);
        }
    }
}
