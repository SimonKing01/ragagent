package com.ragagent.agent.compaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.contract.model.ChatMessage;
import com.ragagent.contract.model.ToolCall;
import com.ragagent.platform.web.JsonMappers;
import com.ragagent.platform.web.ToolJson;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 摘要用对话序列化。
 *
 * <p>把消息渲染成<b>文字记录</b>而不是作为对话传入：拿到真消息的模型会续写它们；
 * 拿到文字记录才会摘要。</p>
 */
public final class ConversationSerializer {

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    /**
     * 摘要请求里单个工具结果的字符上限。工具输出是上下文
     * 体积的最大贡献者，摘要要的是要点不是字节。
     */
    private static final int TOOL_RESULT_MAX_CHARS = 2000;

    /** user/assistant 正文的字符上限。很少是问题，但也不该无界。 */
    private static final int TEXT_MAX_CHARS = 4000;

    /**
     * 工具调用参数的渲染上限。write_sandbox_file 的参数里
     * 装着整个文件体；摘要需要路径和"写过"这个事实，绝不需要内容。
     */
    private static final int TOOL_ARGS_MAX_CHARS = 400;

    private ConversationSerializer() {
    }

    /** 把消息渲染成文字记录。 */
    public static String serializeConversation(List<ChatMessage> messages) {
        List<String> parts = new ArrayList<>();
        if (messages != null) {
            for (ChatMessage msg : messages) {
                // ChatMessage 的 name/toolCallId/reasoningContent 可为 null
                //（llm.domain 可空字段约定），一律 null 安全读取
                String reasoning = nvl(msg.getReasoningContent());
                String content = nvl(msg.getContent());
                String name = nvl(msg.getName());
                switch (msg.getRole()) {
                    case "system" -> {
                        continue;
                    }
                    case "user" -> {
                        String c = truncate(content, TEXT_MAX_CHARS);
                        if (!c.isEmpty()) {
                            parts.add("[User]: " + c);
                        }
                    }
                    case "assistant" -> {
                        if (!reasoning.isEmpty()) {
                            parts.add("[Assistant thinking]: "
                                    + truncate(reasoning, TEXT_MAX_CHARS));
                        }
                        if (!content.isEmpty()) {
                            parts.add("[Assistant]: " + truncate(content, TEXT_MAX_CHARS));
                        }
                        String calls = serializeToolCalls(msg.getToolCalls());
                        if (!calls.isEmpty()) {
                            parts.add("[Assistant tool calls]: " + calls);
                        }
                    }
                    case "tool" -> {
                        String c = truncate(content, TOOL_RESULT_MAX_CHARS);
                        if (!c.isEmpty()) {
                            parts.add("[Tool result %s]: %s".formatted(name, c));
                        }
                    }
                    default -> {
                    }
                }
            }
        }
        return String.join("\n\n", parts);
    }

    /** 渲染工具调用列表。 */
    public static String serializeToolCalls(List<ToolCall> calls) {
        if (calls == null || calls.isEmpty()) {
            return "";
        }
        List<String> rendered = new ArrayList<>(calls.size());
        for (ToolCall tc : calls) {
            rendered.add("%s(%s)".formatted(nvl(tc.getFunction().getName()),
                    renderToolArgs(nvl(tc.getFunction().getArguments()))));
        }
        return String.join("; ", rendered);
    }

    /**
     * 把参数 JSON 渲染成 {@code key=value} 对，丢弃超长值。
     * 键排序保证同一调用渲染结果恒定——跨压缩比较文字记录时用得上；
     * 值走标准 Jackson 紧凑输出（{@link ToolJson#write}，递归键排序保确定性）。
     */
    public static String renderToolArgs(String arguments) {
        JsonNode parsed;
        try {
            parsed = MAPPER.readTree(arguments == null ? "" : arguments);
        } catch (Exception e) {
            return truncate(arguments, TOOL_ARGS_MAX_CHARS);
        }
        if (parsed == null || !parsed.isObject() || hasNonFiniteNumber(parsed)) {
            // 非对象、或含非有限数（无标准 JSON 形态）→ 回退到截断原文
            return truncate(arguments, TOOL_ARGS_MAX_CHARS);
        }
        List<String> pairs = new ArrayList<>();
        for (String k : sortedKeys(parsed)) {
            pairs.add("%s=%s".formatted(k,
                    truncate(ToolJson.write(parsed.get(k)), TOOL_ARGS_MAX_CHARS)));
        }
        return String.join(", ", pairs);
    }

    /** 按 UTF-8 字节序排键，保证同一参数渲染结果恒定（String.compareTo 是 UTF-16 序，不等于字节序）。 */
    private static List<String> sortedKeys(JsonNode obj) {
        List<String> keys = new ArrayList<>();
        obj.fieldNames().forEachRemaining(keys::add);
        keys.sort(ConversationSerializer::compareUtf8);
        return keys;
    }

    static int compareUtf8(String a, String b) {
        byte[] ba = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        int n = Math.min(ba.length, bb.length);
        for (int i = 0; i < n; i++) {
            int x = ba[i] & 0xFF;
            int y = bb[i] & 0xFF;
            if (x != y) {
                return x - y;
            }
        }
        return ba.length - bb.length;
    }

    /** 含非有限数（NaN/Infinity 无标准 JSON 形态）时为真。 */
    private static boolean hasNonFiniteNumber(JsonNode node) {
        if (node.isNumber()) {
            double d = node.asDouble();
            return Double.isInfinite(d) || Double.isNaN(d);
        }
        for (JsonNode child : node) {
            if (hasNonFiniteNumber(child)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按字符（code point）截断并加省略标记；先做含 NBSP/NEL 的全空格裁剪
     * （Java 的 strip() 不含这些，见 {@link #trimUnicodeWhitespace}）。
     */
    public static String truncate(String s, int maxChars) {
        String t = trimUnicodeWhitespace(s);
        int[] runes = t.codePoints().toArray();
        if (runes.length <= maxChars) {
            return t;
        }
        String head = new String(runes, 0, maxChars);
        return "%s\n\n[... %d more characters truncated]".formatted(head, runes.length - maxChars);
    }

    /** null 字符串按空串处理（llm.domain 可空字段约定）。 */
    public static String nvl(String s) {
        return s == null ? "" : s;
    }

    /** unicode 空白语义的全空格裁剪（含 NBSP/NEL，Java 的 strip() 不覆盖）。 */
    public static String trimUnicodeWhitespace(String s) {
        if (s == null) {
            return "";
        }
        int start = 0;
        int end = s.length();
        while (start < end && isUnicodeWhitespace(s.codePointAt(start))) {
            start += Character.charCount(s.codePointAt(start));
        }
        while (end > start) {
            int cp = s.codePointBefore(end);
            if (!isUnicodeWhitespace(cp)) {
                break;
            }
            end -= Character.charCount(cp);
        }
        return s.substring(start, end);
    }

    public static boolean isUnicodeWhitespace(int cp) {
        return cp == '\t' || cp == '\n' || cp == 0x0B || cp == '\f' || cp == '\r'
                || cp == ' ' || cp == 0x85 || cp == 0xA0
                || Character.isSpaceChar(cp);
    }

    /**
     * 摘要器不可用时的兜底档案。有损且无结构，但保住了下一轮
     * 不重做已完成工作所需的工具名和路径。
     */
    public static String rawArchive(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append("Raw conversation archive (LLM summarization unavailable):\n\n");
        if (messages != null) {
            for (ChatMessage msg : messages) {
                String content = nvl(msg.getContent());
                String name = nvl(msg.getName());
                switch (msg.getRole()) {
                    case "user" -> sb.append("- User: %s\n".formatted(truncate(content, 500)));
                    case "assistant" -> {
                        String calls = serializeToolCalls(msg.getToolCalls());
                        if (!calls.isEmpty()) {
                            sb.append("- Assistant [%s]: %s\n"
                                    .formatted(calls, truncate(content, 500)));
                            continue;
                        }
                        sb.append("- Assistant: %s\n".formatted(truncate(content, 500)));
                    }
                    case "tool" -> sb.append("- Tool[%s]: %s\n"
                            .formatted(name, truncate(content, 500)));
                    default -> {
                    }
                }
            }
        }
        return sb.toString();
    }

}
