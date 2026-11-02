package com.ragagent.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.platform.web.ProviderJson;
import java.util.Arrays;
import java.util.List;

/**
 * embedding 响应的唯一校验口径。
 *
 * <p><b>为什么必须校验</b>：pg 写库与索引写入都是 {@code rows[i] ↔ vectors[i]} 按位置配行——
 * 顺序错、少条、混维、{@code index} 重复都会**静默写错向量**，比直接报错危险得多。
 * OpenAI 族响应带 {@code data[].index}，必须按 index 归位；其余 provider 至少校验条数与维度。</p>
 *
 * <p>错误文案与历史上的最小 OpenAI 兼容客户端逐字一致，便于调用方与测试沿用。</p>
 */
public final class EmbeddingVectors {

    private static final int ABBREVIATE = 200;

    private EmbeddingVectors() {
    }

    /**
     * OpenAI 族：{@code data[].embedding} + 可选 {@code index}（缺省按出现位置），按 index 归位。
     *
     * @param data     响应里的 {@code data} 节点
     * @param bodyText 原文（只用于错误文案截断）
     * @param expected 请求的文本条数
     */
    public static List<float[]> fromData(JsonNode data, String bodyText, int expected) {
        if (data == null || !data.isArray()) {
            throw new IllegalStateException("embedding response missing data: " + abbreviate(bodyText));
        }
        float[][] ordered = new float[expected][];
        int seen = 0;
        int dimension = -1;
        for (JsonNode item : data) {
            float[] vector = ProviderJson.floatArray(item.path("embedding"));
            dimension = mergeDimension(dimension, vector);
            int index = indexOf(item, seen, expected);
            if (ordered[index] != null) {
                throw new IllegalStateException("embedding index duplicated: " + index);
            }
            ordered[index] = vector;
            seen++;
        }
        if (seen != expected) {
            throw new IllegalStateException("embedding response size mismatch: expected "
                    + expected + " vector(s) but got " + seen);
        }
        return Arrays.asList(ordered);
    }

    /** 非 OpenAI 族：只校验条数、空向量与批内维度。 */
    public static List<float[]> check(List<float[]> vectors, int expected) {
        if (vectors == null || vectors.size() != expected) {
            throw new IllegalStateException("embedding response size mismatch: expected "
                    + expected + " vector(s) but got " + (vectors == null ? 0 : vectors.size()));
        }
        int dimension = -1;
        for (float[] vector : vectors) {
            dimension = mergeDimension(dimension, vector);
        }
        return vectors;
    }

    /** 单条向量：空结果一律视为失败。 */
    public static float[] checkOne(float[] vector) {
        mergeDimension(-1, vector);
        return vector;
    }

    private static int mergeDimension(int dimension, float[] vector) {
        if (vector == null || vector.length == 0) {
            throw new IllegalStateException("embedding item missing vector");
        }
        if (dimension < 0) {
            return vector.length;
        }
        if (dimension != vector.length) {
            throw new IllegalStateException("embedding dimension mismatch within batch: expected "
                    + dimension + " but got " + vector.length);
        }
        return dimension;
    }

    private static int indexOf(JsonNode item, int seen, int expected) {
        JsonNode node = item.get("index");
        int index = node != null && node.isInt() ? node.asInt() : seen;
        if (index < 0 || index >= expected) {
            throw new IllegalStateException("embedding index out of range: " + index
                    + " (batch size " + expected + ")");
        }
        return index;
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > ABBREVIATE ? value.substring(0, ABBREVIATE) : value;
    }
}
