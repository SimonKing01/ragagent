package com.ragagent.platform.web;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * provider（模型 / 搜索厂商）请求与响应的共享 JSON 编解码。
 *
 * <p>由 embedding / rerank / websearch / retrieval 四份包内副本
 * 收敛而来，语义约定为「标准 Jackson + 字段序由 ObjectNode 插入序保证 + 解析容忍未知字段」。</p>
 */
public final class ProviderJson {

    private static final ObjectMapper MARSHAL = JsonMappers.lenient();

    private static final ObjectMapper UNMARSHAL = JsonMappers.lenient();

    private ProviderJson() {
    }

    /** 序列化（字段序 = ObjectNode 插入序）。 */
    public static byte[] marshal(ObjectNode node) {
        try {
            return MARSHAL.writeValueAsBytes(node);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static ObjectNode object() {
        return MARSHAL.createObjectNode();
    }

    public static ArrayNode array() {
        return MARSHAL.createArrayNode();
    }

    /** 解析（容忍未知字段；失败返回 null，调用方按 err 分支）。 */
    public static JsonNode parse(String body) {
        try {
            return UNMARSHAL.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    public static JsonNode parse(byte[] body) {
        try {
            return UNMARSHAL.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    /** 便捷：文本数组字段（{@code []string}）。 */
    public static ArrayNode arrayOfStrings(java.util.List<String> texts) {
        ArrayNode arr = MARSHAL.createArrayNode();
        for (String t : texts) {
            arr.add(t == null ? "" : t);
        }
        return arr;
    }

    /** 解析 provider 响应的 embedding 数组：{@code data[i].embedding} 的 float 数组。 */
    public static float[] floatArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return new float[0];
        }
        float[] out = new float[node.size()];
        for (int i = 0; i < node.size(); i++) {
            out[i] = (float) node.get(i).asDouble();
        }
        return out;
    }
}
