package com.ragagent.session.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.platform.web.JsonMappers;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 智能体配置快照：追问建议能否生成，完全取决于这份快照是否随消息落库。
 */
class AgentConfigSnapshotTest {

    private static JsonNode config(String json) throws Exception {
        return JsonMappers.lenient().readTree(json);
    }

    @Test
    void emptyConfigIsReportedAsNoAgentConfig() throws Exception {
        assertThat(AgentConfigSnapshot.of(null).configHash())
                .isEqualTo(AgentConfigSnapshot.NO_CONFIG_HASH);
        assertThat(AgentConfigSnapshot.of(null).questionSuggestions()).isNull();
        assertThat(AgentConfigSnapshot.of(config("{}")).configHash())
                .isEqualTo(AgentConfigSnapshot.NO_CONFIG_HASH);
    }

    @Test
    void hashIsStableAndChangesWithConfig() throws Exception {
        String one = AgentConfigSnapshot.hashOf(config("{\"modelId\":\"m1\"}"));
        String same = AgentConfigSnapshot.hashOf(config("{\"modelId\":\"m1\"}"));
        String other = AgentConfigSnapshot.hashOf(config("{\"modelId\":\"m2\"}"));

        assertThat(one).hasSize(16).isEqualTo(same);
        assertThat(other).isNotEqualTo(one);
    }

    @Test
    void followUpsArePassedThroughForLaterGeneration() throws Exception {
        JsonNode cfg = config("""
                {"modelId":"m1","questionSuggestions":{"starters":{"enabled":true},
                 "followUps":{"enabled":true,"count":3,"mode":"hybrid","modelId":"m1"}}}
                """);

        Map<String, Object> suggestions = AgentConfigSnapshot.of(cfg).questionSuggestions();

        assertThat(suggestions).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> followUps = (Map<String, Object>) suggestions.get("followUps");
        assertThat(followUps).containsEntry("enabled", true).containsEntry("count", 3);
        assertThat(AgentConfigSnapshot.of(cfg).configHash()).isNotEqualTo(
                AgentConfigSnapshot.NO_CONFIG_HASH);
    }

    @Test
    void missingSuggestionsBlockYieldsNull() throws Exception {
        assertThat(AgentConfigSnapshot.of(config("{\"modelId\":\"m1\"}")).questionSuggestions())
                .isNull();
    }
}
