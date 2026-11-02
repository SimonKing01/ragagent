package com.ragagent.platform.knowledge;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.contract.knowledge.FaqChunkMetadata;
import com.ragagent.platform.web.JsonMappers;

/**
 * FaqChunkMetadata 的 JSON 侧（原挂在该类上的 JSON 常量与 fromJson/toJsonNode 两方法）。
 *
 * <p>形状类已入零依赖 contract，而 JSON 读写要 Jackson ⇒ 属"映射面"，
 * 按本仓定式（形状面入契约、映射面留 L1/域）留在 L1；调用方（knowledge / agenttools /
 * chatpipeline）均可达 L1。</p>
 */
public final class FaqChunkMetadataCodec {

    private FaqChunkMetadataCodec() {
    }

    public static final ObjectMapper JSON =
            JsonMappers.lenient();

    /** 从 chunks.metadata 的 JsonNode 解析；null/空 → null。 */
    public static FaqChunkMetadata fromJson(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode() || node.isEmpty()) {
            return null;
        }
        try {
            return JSON.treeToValue(node, FaqChunkMetadata.class);
        } catch (JacksonException e) {
            return null;
        }
    }


    /** 形状 → JSON 树（原实例方法 {@code toJsonNode()} 的静态等价）。 */
    public static ObjectNode toJsonNode(FaqChunkMetadata meta) {
        return JSON.valueToTree(meta);
    }

}
