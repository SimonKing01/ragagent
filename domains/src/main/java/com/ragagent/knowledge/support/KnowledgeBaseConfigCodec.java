package com.ragagent.knowledge.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.contract.error.BizException;
import com.ragagent.knowledge.domain.KnowledgeBaseChunkingConfig;
import com.ragagent.knowledge.domain.KnowledgeBaseImageProcessingConfig;
import com.ragagent.knowledge.domain.KnowledgeBaseIndexingStrategy;
import com.ragagent.platform.web.JsonMappers;

/**
 * KB 配置 jsonb → 契约配置类型的**解析边界**（承载原先挂在 4 个配置类型上的静态
 * {@code from(JsonNode)} 工厂， 去 Jackson 时外移）。
 *
 * <p><b>为什么独立</b>：配置类型归 {@code knowledge/domain}（零依赖形状），而解析要 {@code JsonMappers}
 * （L1）⇒ 工厂留在能引 Jackson 的一侧；语义**逐字保留**（含
 * {@code KnowledgeBaseChunkingConfig} 的"形状损坏 → {@link BizException} 明确报错"边界）。</p>
 */
public final class KnowledgeBaseConfigCodec {

    private KnowledgeBaseConfigCodec() {
    }

    /** 分块配置：{@code null}/缺省 → 默认值；**形状损坏 → BizException**（不静默回落）。 */
    public static KnowledgeBaseChunkingConfig chunkingFrom(JsonNode node) {
        if (node == null || node.isNull()) {
            return new KnowledgeBaseChunkingConfig();
        }
        KnowledgeBaseChunkingConfig parsed;
        try {
            parsed = JsonMappers.lenient().convertValue(node, KnowledgeBaseChunkingConfig.class);
        } catch (IllegalArgumentException e) {
            throw BizException.badRequest("chunkingConfig 解析失败：" + e.getMessage());
        }
        return parsed == null ? new KnowledgeBaseChunkingConfig() : parsed;
    }

    /** 图像处理配置：null/解析失败 → 默认值。 */
    public static KnowledgeBaseImageProcessingConfig imageProcessingFrom(JsonNode node) {
        if (node == null || node.isNull()) {
            return new KnowledgeBaseImageProcessingConfig();
        }
        try {
            KnowledgeBaseImageProcessingConfig parsed =
                    JsonMappers.lenient().convertValue(node, KnowledgeBaseImageProcessingConfig.class);
            return parsed == null ? new KnowledgeBaseImageProcessingConfig() : parsed;
        } catch (IllegalArgumentException e) {
            return new KnowledgeBaseImageProcessingConfig();
        }
    }

    /** 索引策略：null/解析失败 → 默认值（vector+keyword=true）。 */
    public static KnowledgeBaseIndexingStrategy indexingStrategyFrom(JsonNode node) {
        if (node == null || node.isNull()) {
            return KnowledgeBaseIndexingStrategy.defaultStrategy();
        }
        try {
            KnowledgeBaseIndexingStrategy parsed =
                    JsonMappers.lenient().convertValue(node, KnowledgeBaseIndexingStrategy.class);
            return parsed == null ? KnowledgeBaseIndexingStrategy.defaultStrategy() : parsed;
        } catch (IllegalArgumentException e) {
            return KnowledgeBaseIndexingStrategy.defaultStrategy();
        }
    }
}
