package com.ragagent.agent.fixtures;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.platform.web.ContractJsonModule;
import com.ragagent.platform.web.ToolJson;
import java.util.ArrayList;
import java.util.Collections;

/** 4.5a 录制测试的共享小工具。 */
public final class RecordingSupport {

    public static final ObjectMapper PLAIN = new ObjectMapper()
            .registerModule(new ContractJsonModule());

    /**
     * 数字文本归一——把文本里的数字 token 统一成 Java {@code Double.toString} 形态
     * （录制语料里的 {@code 1 / 1e+21 / 1e-7} ↔ Java 侧 {@code 1.0 / 1.0E21 / 1.0E-7}；
     * 大整数同转）。两侧同归一后比较；非数字文本不受影响。
     */
    public static String normalizeNumberText(String text) {
        if (text == null) {
            return null;
        }
        java.util.regex.Matcher m = NUMBER_TOKEN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String token = m.group();
            try {
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(
                        Double.toString(Double.parseDouble(token))));
            } catch (NumberFormatException e) {
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(token));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 数字 token：前后不得是单词字符/点/反斜杠（避免误伤 `\\u003c` 的转义序列与标识符）。 */
    private static final java.util.regex.Pattern NUMBER_TOKEN =
            java.util.regex.Pattern.compile("(?<![\\w.\\\\])-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?(?![\\w.])");

    /**
     * 录制文本里的 HTML 转义还原：{@code \u003c/\u003e/\u0026} →
     * {@code < > &}。转义形态不再构成断言目标；键序/结构/数字形态仍被钉住。
     */
    public static String normalizeEscapes(String s) {
        return s == null ? null
                : s.replace("\\u003c", "<").replace("\\u003e", ">").replace("\\u0026", "&");
    }

    /** JSON mapper（map 键序由类型上的 serializer 负责；2026-10-03 起标准转义）。 */
    public static final ObjectMapper JSON_MAPPER = new ObjectMapper()
            .registerModule(new ContractJsonModule());

    private RecordingSupport() {
    }

    public static JsonNode rec(String constant) {
        return ToolContractGoldenA.rec(constant);
    }

    public static String text(JsonNode rec, String field) {
        JsonNode n = rec.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }

    public static JsonNode readTree(String json) {
        try {
            return PLAIN.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 用 ToolJson 把 data map 编成录制时的 JSON 字节形态（对照录制时的 mustJSON）。 */
    public static String jsonOfData(java.util.Map<String, Object> data) {
        return ToolJson.write(PLAIN.valueToTree(data));
    }

    /**
     * 键序无关的规范化 JSON：递归排序对象键后序列化。用于 data map 比较——
     * 录制侧 map 键序不定、{@code steps} 这类数组按声明序，
     * Java 经 valueToTree 的键序取决于序列化层；两边都规范化后比较值本身，
     * 键序契约由 steps_json 字符串与 output 文本的字节断言承担。
     */
    public static String canonicalJson(JsonNode node) {
        return writeCanonical(node);
    }

    private static String writeCanonical(JsonNode node) {
        if (node == null || node.isNull()) {
            return "null";
        }
        if (node.isObject()) {
            java.util.List<String> keys = new ArrayList<>();
            node.fieldNames().forEachRemaining(keys::add);
            Collections.sort(keys);
            StringBuilder sb = new StringBuilder("{");
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                String k = keys.get(i);
                sb.append('"').append(k).append("\":");
                sb.append(writeCanonical(node.get(k)));
            }
            return sb.append('}').toString();
        }
        if (node.isArray()) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(writeCanonical(node.get(i)));
            }
            return sb.append(']').toString();
        }
        // 标量借道 ToolJson 的编码（数字/字符串/布尔与录制侧一致）
        return ToolJson.write(node);
    }

    /** 重建 trunc 语料的输入串（pieces/repeats 拼接）。 */
    public static String buildTruncInput(JsonNode rec) {
        JsonNode pieces = rec.get("pieces");
        JsonNode repeats = rec.get("repeats");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pieces.size(); i++) {
            sb.append(pieces.get(i).asText().repeat(repeats.get(i).asInt()));
        }
        return sb.toString();
    }
}
