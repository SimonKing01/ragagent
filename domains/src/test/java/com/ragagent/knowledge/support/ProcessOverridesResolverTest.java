package com.ragagent.knowledge.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBaseChunkingConfig;
import com.ragagent.platform.web.JsonMappers;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 逐文档覆盖叠加到 KB 配置上的口径：覆盖优先、缺省回落。 */
class ProcessOverridesResolverTest {

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    private static Knowledge doc(String metadata) throws Exception {
        Knowledge k = new Knowledge();
        k.setId("k1");
        if (metadata != null) {
            k.setMetadata(MAPPER.readTree(metadata));
        }
        return k;
    }

    private static KnowledgeBaseChunkingConfig kbChunking() {
        KnowledgeBaseChunkingConfig cfg = new KnowledgeBaseChunkingConfig();
        cfg.setChunkSize(512);
        cfg.setChunkOverlap(80);
        cfg.setSeparators(List.of("\n\n", "\n"));
        cfg.setStrategy("auto");
        cfg.setTokenLimit(0);
        return cfg;
    }

    @Test
    void chunkingOverrideWinsAndKeepsOtherKeysFromKb() throws Exception {
        Knowledge k = doc("{\"processOverrides\":{\"chunkingConfig\":"
                + "{\"chunkSize\":256,\"chunkOverlap\":32}}}");

        KnowledgeBaseChunkingConfig effective = ProcessOverridesResolver.chunking(k, kbChunking());

        assertThat(effective.getChunkSize()).isEqualTo(256);
        assertThat(effective.getChunkOverlap()).isEqualTo(32);
        // 覆盖里没出现的键沿用 KB
        assertThat(effective.getSeparators()).containsExactly("\n\n", "\n");
        assertThat(effective.getStrategy()).isEqualTo("auto");
    }

    @Test
    void chunkingWithoutOverrideKeepsKbValues() throws Exception {
        KnowledgeBaseChunkingConfig kbCfg = kbChunking();

        KnowledgeBaseChunkingConfig effective = ProcessOverridesResolver.chunking(doc(null), kbCfg);

        assertThat(effective.getChunkSize()).isEqualTo(512);
        assertThat(effective.getChunkOverlap()).isEqualTo(80);
        // 返回副本，改动不回写 KB
        assertThat(effective).isNotSameAs(kbCfg);
    }

    @Test
    void chunkingToleratesMissingKbConfig() throws Exception {
        Knowledge k = doc("{\"processOverrides\":{\"chunkingConfig\":{\"chunkSize\":128}}}");

        KnowledgeBaseChunkingConfig effective = ProcessOverridesResolver.chunking(k, null);

        assertThat(effective.getChunkSize()).isEqualTo(128);
        assertThat(effective.getChunkOverlap()).isZero();
    }

    @Test
    void parserEngineRulesPreferTopLevelThenNested() throws Exception {
        Knowledge top = doc("{\"processOverrides\":{\"parserEngineRules\":"
                + "[{\"fileTypes\":[\"pdf\"],\"engine\":\"mineru\"}]}}");
        assertThat(ProcessOverridesResolver.parserEngineRules(top).size()).isEqualTo(1);

        Knowledge nested = doc("{\"processOverrides\":{\"chunkingConfig\":{\"parserEngineRules\":"
                + "[{\"fileTypes\":[\"pptx\"],\"engine\":\"markitdown\"}]}}}");
        assertThat(ProcessOverridesResolver.parserEngineRules(nested).size()).isEqualTo(1);

        assertThat(ProcessOverridesResolver.parserEngineRules(doc(null))).isNull();
    }

    @Test
    void graphEnabledCannotBeForcedOnWhenKbDisabled() throws Exception {
        Knowledge k = doc("{\"processOverrides\":{\"graphEnabled\":true}}");
        assertThat(ProcessOverridesResolver.graphEnabled(k, false)).isFalse();
    }

    @Test
    void graphEnabledAllowsDocumentLevelOff() throws Exception {
        Knowledge off = doc("{\"processOverrides\":{\"graphEnabled\":false}}");
        assertThat(ProcessOverridesResolver.graphEnabled(off, true)).isFalse();
        assertThat(ProcessOverridesResolver.graphEnabled(doc(null), true)).isTrue();
    }
}
