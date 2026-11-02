package com.ragagent.knowledge.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.contract.error.BizException;
import com.ragagent.knowledge.support.KnowledgeBaseConfigCodec;
import com.ragagent.knowledge.support.KnowledgeBaseConfigMaps;
import com.ragagent.platform.web.JsonMappers;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * K8 配置能力矩阵：**声明了的配置要么生效、要么被显式告知**，不许静默吞掉。
 *
 * <p>背景：`chunkingConfig` 是 KB 保存/更新面自由可写的 jsonb，但主摄取
 * （{@code KnowledgeProcessWorker}）只消费其中一部分——`enableParentChild` 系列、
 * `tableMetadataInstructions` 没有进摄取链路。此前两处"静默"：
 * <ol>
 *   <li>JSON 损坏 → {@code from()} 回落空默认，摄取悄悄按 512/80 跑完；</li>
 *   <li>未生效的字段照收不误，视图原样回显，用户无法分辨"生效了"和"被忽略"。</li>
 * </ol>
 */
class KnowledgeBaseChunkingConfigCapabilityTest {

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    @Test
    @DisplayName("损坏配置不得静默回落默认值：解析失败必须明确报错")
    void malformedConfigFailsLoudly() {
        JsonNode bad = read("{\"chunkSize\":\"not-a-number\"}");

        BizException e = assertThrows(BizException.class,
                () -> KnowledgeBaseConfigCodec.chunkingFrom(bad),
                "损坏的 chunkingConfig 静默回落成默认 512/80，会让摄取用错参数跑完而无人知情");

        assertTrue(e.getMessage() != null && e.getMessage().contains("chunkingConfig"),
                "错误信息要指认字段，便于定位: " + e.getMessage());
    }

    @Test
    @DisplayName("读取容错仍在：null / 空对象 / 未知键 → 默认值（只有损坏才报错）")
    void tolerantReadsStillTolerated() {
        assertEquals(0, KnowledgeBaseConfigCodec.chunkingFrom(null).getChunkSize(), "null → 默认");
        assertEquals(0, KnowledgeBaseConfigCodec.chunkingFrom(read("{}")).getChunkSize(), "空对象 → 默认");
        assertEquals(500, KnowledgeBaseConfigCodec.chunkingFrom(read("{\"chunkSize\":500,\"unknown\":1}"))
                .getChunkSize(), "未知键仍按 lenient 忽略");
    }

    @Test
    @DisplayName("未生效的配置在视图里显式标注（不静默）")
    void viewMarksInactiveFeatures() {
        KnowledgeBaseChunkingConfig cfg = new KnowledgeBaseChunkingConfig();
        cfg.setEnableParentChild(true);
        cfg.setParentChunkSize(2000);
        cfg.setChildChunkSize(300);
        cfg.setTableMetadataInstructions("表头优先");
        KnowledgeBaseChunkingConfig.ParserEngineRule rule =
                new KnowledgeBaseChunkingConfig.ParserEngineRule();
        rule.setFileTypes(List.of("xlsx"));
        rule.setEngine("excel");
        cfg.setParserEngineRules(List.of(rule));

        List<String> inactive = inactiveFeaturesOf(cfg);

        assertTrue(inactive.contains("enableParentChild"), "父子块开关未进摄取链路: " + inactive);
        assertTrue(inactive.contains("parentChunkSize"), "父子块尺寸同上: " + inactive);
        assertTrue(inactive.contains("childChunkSize"), "父子块尺寸同上: " + inactive);
        assertTrue(inactive.contains("tableMetadataInstructions"), "全仓无消费点: " + inactive);
        assertTrue(!inactive.contains("parserEngineRules"),
                "parserEngineRules 已接入 worker（按扩展名选引擎），不得再标未生效: " + inactive);
    }

    @Test
    @DisplayName("已生效的配置不得被误标（strategy / tokenLimit / languages / chunkSize 等）")
    void activeFeaturesAreNotMarked() {
        KnowledgeBaseChunkingConfig cfg = new KnowledgeBaseChunkingConfig();
        cfg.setChunkSize(500);
        cfg.setChunkOverlap(50);
        cfg.setSeparators(List.of("\n\n", "。"));
        cfg.setStrategy("heading");
        cfg.setTokenLimit(8000);
        cfg.setLanguages(List.of("zh", "en"));

        assertTrue(inactiveFeaturesOf(cfg).isEmpty(),
                "这些字段已经接线到 Chunker（toSplitterConfig），标成未生效就是撒谎");
    }

    @Test
    @DisplayName("默认配置的标注为空数组（模块约定：字段一律显式输出）")
    void defaultConfigCarriesEmptyMark() {
        JsonNode view = MAPPER.valueToTree(
                KnowledgeBaseConfigMaps.toView(new KnowledgeBaseChunkingConfig()));

        assertTrue(view.has("inactiveFeatures"),
                "字段一律显式输出（知识库模块约定，勿用 @JsonInclude 省略）");
        assertEquals(0, view.get("inactiveFeatures").size(), "全部生效 = 空数组");
    }

    /** 取视图里显式标注的未生效键（空 = 无该键）。 */
    private static List<String> inactiveFeaturesOf(KnowledgeBaseChunkingConfig cfg) {
        JsonNode view = MAPPER.valueToTree(KnowledgeBaseConfigMaps.toView(cfg));
        JsonNode node = view.get("inactiveFeatures");
        List<String> keys = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(k -> keys.add(k.asText()));
        }
        return keys;
    }

    private static JsonNode read(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("测试用 JSON 不合法: " + json, e);
        }
    }
}
