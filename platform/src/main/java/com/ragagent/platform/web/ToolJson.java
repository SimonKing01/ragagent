package com.ragagent.platform.web;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 工具参数/输出的 JSON 编码（标准 Jackson 实现）。
 *
 * <p>历史上的手写逐字节对齐 writer 不再使用：现在就是标准 Jackson 序列化；
 * 仅保留<b>递归键排序</b>一条——LLM 载荷的字节稳定性前提（同一参数两次编码需同字节）。</p>
 */
public final class ToolJson {

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    private static final ObjectWriter PRETTY = MAPPER.writerWithDefaultPrettyPrinter();

    private ToolJson() {
    }

    /** 标准 Jackson 紧凑 JSON（Java 原生做法，替代手写 writer）。 */
    public static String compactJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 序列化失败", e);
        }
    }

    /** 标准 Jackson 缩进 JSON。 */
    public static String prettyJson(Object value) {
        try {
            return PRETTY.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 序列化失败", e);
        }
    }

    /** JSON 字符串字面量（连引号）：标准 Jackson 转义。 */
    public static String quoted(String s) {
        try {
            return MAPPER.writeValueAsString(s);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("String 序列化不应失败", e);
        }
    }

    /** JSON 类型名（错误文案用）：null / boolean / number / string / array / object。 */
    public static String nodeTypeLabel(JsonNode node) {
        return node == null ? "null" : node.getNodeType().name().toLowerCase(java.util.Locale.ROOT);
    }

    /** 非对象请求体的 400 文案：{@code expected JSON object, got <类型名>}。 */
    public static String expectedObjectMessage(JsonNode node) {
        return "expected JSON object, got " + nodeTypeLabel(node);
    }

    /** 工具参数编码：递归键排序（确定性）+ 标准 Jackson 紧凑输出。 */
    public static String write(JsonNode node) {
        return compactJson(sorted(node));
    }

    /** 递归按键字母序重建（数组保序、标量原样）；入参不被修改。 */
    private static JsonNode sorted(JsonNode node) {
        if (node instanceof ObjectNode obj) {
            List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
            entries.addAll(obj.properties());
            entries.sort(Map.Entry.comparingByKey());
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            for (Map.Entry<String, JsonNode> e : entries) {
                out.set(e.getKey(), sorted(e.getValue()));
            }
            return out;
        }
        if (node instanceof ArrayNode arr) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            for (JsonNode item : arr) {
                out.add(sorted(item));
            }
            return out;
        }
        return node;
    }
}
