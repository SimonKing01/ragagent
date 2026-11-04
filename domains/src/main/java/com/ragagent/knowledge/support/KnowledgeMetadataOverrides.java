package com.ragagent.knowledge.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.platform.web.JsonMappers;

/**
 * 知识库文档 {@code metadata} 的写入辅助。
 *
 * <p>上传/重解析可带逐文档处理覆盖（processConfig）：目前只有图片 OCR 开关会被解析管线消费，
 * 但按「先落库、再消费」的口径统一写入 {@code metadata.processOverrides}，处理时间线与
 * 文档详情才能回读到用户当时的选择。</p>
 *
 * <p>键名统一 camel：本仓约定自有 JSON 面不新增 snake 键（守卫 check-json-key-case 会拦）。</p>
 */
public final class KnowledgeMetadataOverrides {

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    /** metadata 里承载逐文档覆盖的键。 */
    public static final String OVERRIDES_KEY = "processOverrides";

    private KnowledgeMetadataOverrides() {
    }

    /** 逐文档的推荐问题开关；未覆盖返回 null（调用方回落到 KB 级配置）。 */
    public static Boolean questionGenerationEnabled(JsonNode metadata) {
        JsonNode node = overridesOf(metadata).path("questionGenerationConfig").path("enabled");
        return node.isBoolean() ? node.booleanValue() : null;
    }

    /** 逐文档的推荐问题数量；未覆盖返回 null。 */
    public static Integer questionCount(JsonNode metadata) {
        JsonNode node = overridesOf(metadata).path("questionGenerationConfig").path("questionCount");
        return node.isInt() || node.isLong() ? node.asInt() : null;
    }

    /** 取 metadata 里的覆盖对象；没有则空对象（便于链式 path）。 */
    public static JsonNode overridesOf(JsonNode metadata) {
        if (metadata == null || !metadata.isObject()) {
            return MAPPER.createObjectNode();
        }
        JsonNode overrides = metadata.path(OVERRIDES_KEY);
        return overrides.isObject() ? overrides : MAPPER.createObjectNode();
    }

    /**
     * 把逐文档覆盖合并进 metadata：保留既有键，只替换 {@code processOverrides}。
     *
     * @param metadata      现有 metadata（可为 null 或非对象）
     * @param processConfig 客户端传入的覆盖（null/空对象/非对象 → 原样返回 metadata）
     */
    public static JsonNode merge(JsonNode metadata, JsonNode processConfig) {
        if (processConfig == null || !processConfig.isObject() || processConfig.isEmpty()) {
            return metadata;
        }
        ObjectNode base = metadata instanceof ObjectNode obj ? obj.deepCopy() : MAPPER.createObjectNode();
        base.set(OVERRIDES_KEY, processConfig);
        return base;
    }
}
