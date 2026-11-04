package com.ragagent.knowledge.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.domain.KnowledgeBaseChunkingConfig;
import com.ragagent.knowledge.domain.KnowledgeBaseImageProcessingConfig;
import com.ragagent.knowledge.repository.KnowledgeBaseRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

/** KB 级 parserEngineRules 的唯一消费点：按扩展名解析引擎。 */
class KnowledgeTextExtractorParserRuleTest {

    private final KnowledgeBaseRepository kbRepository = mock(KnowledgeBaseRepository.class);
    private final KnowledgeTextExtractor extractor =
            new KnowledgeTextExtractor(null, null, null, kbRepository, null, null);

    private static Knowledge knowledge(String kbId, String fileType) {
        Knowledge k = new Knowledge();
        k.setId("k1");
        k.setKnowledgeBaseId(kbId);
        k.setFileType(fileType);
        return k;
    }

    private static KnowledgeBase kbWithRule(String fileType, String engine) {
        KnowledgeBase kb = new KnowledgeBase();
        KnowledgeBaseChunkingConfig cfg = new KnowledgeBaseChunkingConfig();
        KnowledgeBaseChunkingConfig.ParserEngineRule rule =
                new KnowledgeBaseChunkingConfig.ParserEngineRule();
        rule.setFileTypes(List.of(fileType));
        rule.setEngine(engine);
        cfg.setParserEngineRules(List.of(rule));
        kb.setChunkingConfig(cfg);
        return kb;
    }

    @Test
    void resolvesRuleByFileType() {
        when(kbRepository.findByIdOnly("kb1")).thenReturn(kbWithRule("pdf", "mineru"));

        assertThat(extractor.resolveParserEngine(knowledge("kb1", "pdf"))).isEqualTo("mineru");
    }

    @Test
    void unmatchedTypeKeepsServerDefault() {
        when(kbRepository.findByIdOnly("kb1")).thenReturn(kbWithRule("pdf", "mineru"));

        assertThat(extractor.resolveParserEngine(knowledge("kb1", "pptx"))).isEmpty();
        assertThat(extractor.resolveParserEngine(knowledge("kb1", "docx"))).isEmpty();
    }

    @Test
    void missingKbOrRulesYieldsEmpty() {
        assertThat(extractor.resolveParserEngine(knowledge("missing", "pdf"))).isEmpty();
        when(kbRepository.findByIdOnly("kb1")).thenReturn(new KnowledgeBase());
        assertThat(extractor.resolveParserEngine(knowledge("kb1", "pdf"))).isEmpty();
    }

    // ── 图片 OCR 开关：文档级覆盖优先于 KB 级 ──

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static KnowledgeBase kbWithOcr(boolean enabled) {
        KnowledgeBase kb = new KnowledgeBase();
        KnowledgeBaseImageProcessingConfig cfg = new KnowledgeBaseImageProcessingConfig();
        cfg.setOcrEnabled(enabled);
        kb.setImageProcessingConfig(cfg);
        return kb;
    }

    @Test
    void ocrDefaultsToOnWhenKbHasNoConfig() {
        when(kbRepository.findByIdOnly("kb1")).thenReturn(new KnowledgeBase());
        assertThat(extractor.resolveOcrEnabled(knowledge("kb1", "pdf"))).isTrue();
    }

    @Test
    void ocrFollowsKnowledgeBaseSwitch() {
        when(kbRepository.findByIdOnly("kb1")).thenReturn(kbWithOcr(false));
        assertThat(extractor.resolveOcrEnabled(knowledge("kb1", "pdf"))).isFalse();
    }

    @Test
    void documentOverrideBeatsKnowledgeBaseSwitch() throws Exception {
        when(kbRepository.findByIdOnly("kb1")).thenReturn(kbWithOcr(false));
        Knowledge k = knowledge("kb1", "pdf");
        k.setMetadata(MAPPER.readTree(
                "{\"processOverrides\":{\"imageProcessingConfig\":{\"ocrEnabled\":true}}}"));
        assertThat(extractor.resolveOcrEnabled(k)).isTrue();

        when(kbRepository.findByIdOnly("kb1")).thenReturn(kbWithOcr(true));
        k.setMetadata(MAPPER.readTree(
                "{\"processOverrides\":{\"imageProcessingConfig\":{\"ocrEnabled\":false}}}"));
        assertThat(extractor.resolveOcrEnabled(k)).isFalse();
    }
}
