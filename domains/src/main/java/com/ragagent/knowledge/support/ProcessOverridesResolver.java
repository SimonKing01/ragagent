package com.ragagent.knowledge.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBaseChunkingConfig;
import java.util.ArrayList;
import java.util.List;

/**
 * 逐文档处理覆盖（{@code metadata.processOverrides}）的解析：把上传/重解析弹窗里的选择
 * 叠加到知识库级配置上，产出本次解析实际生效的配置。
 *
 * <p>口径：**覆盖优先，缺省回落 KB**；覆盖里没出现的键一律沿用 KB 值，避免"部分覆盖"把
 * 其余配置清零。</p>
 */
public final class ProcessOverridesResolver {

    private ProcessOverridesResolver() {
    }

    /** 文档级覆盖对象；没有则返回空对象（便于链式 {@code path}）。 */
    public static JsonNode overrides(Knowledge k) {
        return KnowledgeMetadataOverrides.overridesOf(k == null ? null : k.getMetadata());
    }

    /**
     * 分块配置：KB 值为底，逐字段叠加覆盖。
     *
     * @param kbCfg KB 级分块配置（可为 null，此时按默认值起步）
     */
    public static KnowledgeBaseChunkingConfig chunking(Knowledge k, KnowledgeBaseChunkingConfig kbCfg) {
        KnowledgeBaseChunkingConfig base = copyOf(kbCfg);
        JsonNode cc = overrides(k).path("chunkingConfig");
        if (!cc.isObject()) {
            return base;
        }
        if (cc.path("chunkSize").canConvertToInt() && cc.path("chunkSize").asInt() > 0) {
            base.setChunkSize(cc.path("chunkSize").asInt());
        }
        if (cc.path("chunkOverlap").canConvertToInt() && cc.path("chunkOverlap").asInt() >= 0) {
            base.setChunkOverlap(cc.path("chunkOverlap").asInt());
        }
        if (cc.path("separators").isArray() && !cc.path("separators").isEmpty()) {
            base.setSeparators(strings(cc.path("separators")));
        }
        if (cc.path("strategy").isTextual() && !cc.path("strategy").asText().isBlank()) {
            base.setStrategy(cc.path("strategy").asText());
        }
        if (cc.path("tokenLimit").canConvertToInt()) {
            base.setTokenLimit(cc.path("tokenLimit").asInt());
        }
        if (cc.path("languages").isArray()) {
            base.setLanguages(strings(cc.path("languages")));
        }
        if (cc.path("enableParentChild").isBoolean()) {
            base.setEnableParentChild(cc.path("enableParentChild").asBoolean());
        }
        if (cc.path("parentChunkSize").canConvertToInt() && cc.path("parentChunkSize").asInt() > 0) {
            base.setParentChunkSize(cc.path("parentChunkSize").asInt());
        }
        if (cc.path("childChunkSize").canConvertToInt() && cc.path("childChunkSize").asInt() > 0) {
            base.setChildChunkSize(cc.path("childChunkSize").asInt());
        }
        if (cc.path("tableMetadataInstructions").isTextual()) {
            base.setTableMetadataInstructions(cc.path("tableMetadataInstructions").asText());
        }
        return base;
    }

    /**
     * 逐文档解析引擎规则（顶层 {@code parserEngineRules} 或分块内的同名键）。
     * 返回 null 表示未覆盖，调用方回落到 KB 级规则。
     */
    public static JsonNode parserEngineRules(Knowledge k) {
        JsonNode root = overrides(k);
        JsonNode top = root.path("parserEngineRules");
        if (top.isArray() && !top.isEmpty()) {
            return top;
        }
        JsonNode nested = root.path("chunkingConfig").path("parserEngineRules");
        return nested.isArray() && !nested.isEmpty() ? nested : null;
    }

    /**
     * 图谱抽取开关：KB 关闭时不能被单文档打开（依赖 KB 建库策略与图库），
     * KB 打开时允许单文档关闭。
     */
    public static boolean graphEnabled(Knowledge k, boolean kbEnabled) {
        if (!kbEnabled) {
            return false;
        }
        JsonNode node = overrides(k).path("graphEnabled");
        return !node.isBoolean() || node.asBoolean();
    }


    /**
     * 图谱抽取配置：以 KB 的 {@code extractConfig} 为底，叠加逐文档覆盖
     * （{@code text} / {@code tags} / {@code nodes} / {@code relations} / {@code customInstructions}）。
     *
     * <p>{@code enabled} 只能改「关」不能改「开」：KB 未启用抽取时，文档级打开也没有
     * 提示词模板与图库命名空间可用。</p>
     *
     * @return 生效配置；KB 未配置（null/非对象）时返回 null，调用方跳过抽取
     */
    public static JsonNode extractConfig(Knowledge k, JsonNode kbCfg) {
        if (kbCfg == null || !kbCfg.isObject()) {
            return null;
        }
        ObjectNode effective = ((ObjectNode) kbCfg).deepCopy();
        JsonNode ov = overrides(k).path("extractConfig");
        if (!ov.isObject()) {
            return effective;
        }
        if (ov.path("enabled").isBoolean()) {
            boolean kbEnabled = kbCfg.path("enabled").asBoolean(false);
            effective.put("enabled", kbEnabled && ov.path("enabled").asBoolean());
        }
        for (String key : new String[] {"text", "customInstructions"}) {
            if (ov.path(key).isTextual()) {
                effective.put(key, ov.path(key).asText());
            }
        }
        for (String key : new String[] {"tags", "nodes", "relations"}) {
            if (ov.path(key).isArray()) {
                effective.set(key, ov.path(key).deepCopy());
            }
        }
        return effective;
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(node -> {
            if (node.isTextual()) {
                out.add(node.asText());
            }
        });
        return out;
    }

    private static KnowledgeBaseChunkingConfig copyOf(KnowledgeBaseChunkingConfig src) {
        KnowledgeBaseChunkingConfig dst = new KnowledgeBaseChunkingConfig();
        if (src == null) {
            return dst;
        }
        dst.setChunkSize(src.getChunkSize());
        dst.setChunkOverlap(src.getChunkOverlap());
        dst.setSeparators(src.getSeparators());
        dst.setParserEngineRules(src.getParserEngineRules());
        dst.setEnableParentChild(src.isEnableParentChild());
        dst.setParentChunkSize(src.getParentChunkSize());
        dst.setChildChunkSize(src.getChildChunkSize());
        dst.setStrategy(src.getStrategy());
        dst.setTokenLimit(src.getTokenLimit());
        dst.setLanguages(src.getLanguages());
        dst.setTableMetadataInstructions(src.getTableMetadataInstructions());
        return dst;
    }
}
