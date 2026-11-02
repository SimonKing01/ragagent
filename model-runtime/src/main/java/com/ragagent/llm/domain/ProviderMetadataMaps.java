package com.ragagent.llm.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.platform.web.JsonMappers;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * providerMetadata 的形状转换：llm 协议面树形（{@code Map<String, JsonNode>}）⇄ 契约面不透明
 * （{@code Map<String, Object>}——{@code AgentToolCall#providerMetadata}，jsonb 读回时值是 Map/List 形态）。
 *
 * <p>契约载荷以 {@code Object} 持不透明 JSON；进出 llm 协议面时在此归一：
 * 出（契约→llm）逐值转树；入（llm→契约）浅拷贝（值本就是树实例）。</p>
 */
public final class ProviderMetadataMaps {

    private ProviderMetadataMaps() {
    }

    /** 契约侧 → llm 树形（jsonb 读回的 Map/List 值在此转树；树值原样）。 */
    public static Map<String, JsonNode> toNodeMap(Map<String, Object> src) {
        if (src == null) {
            return null;
        }
        Map<String, JsonNode> out = new LinkedHashMap<>(src.size());
        for (Map.Entry<String, Object> e : src.entrySet()) {
            out.put(e.getKey(), JsonMappers.toJsonNode(e.getValue()));
        }
        return out;
    }

    /** llm 树形 → 契约侧（浅拷贝；值为树实例，契约以 Object 持有）。 */
    public static Map<String, Object> toObjectMap(Map<String, JsonNode> src) {
        return src == null ? null : new LinkedHashMap<>(src);
    }
}
