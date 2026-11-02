package com.ragagent.agent.tool.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agent.fixtures.RecordingSupport;
import com.ragagent.agent.fixtures.ToolContractGoldenA;
import com.ragagent.contract.agent.ThinkBlocks;
import org.junit.jupiter.api.Test;

/**
 * ThinkBlocks.stripThinkBlocks 的录制语料（11 条）。多行块/多个块/仅块/周围空白/未闭合/
 * 嵌套外观（非贪婪正则行为）逐字比对。
 */
class StripThinkRecordingTest {

    private static final String[] CASES = {
            "R_STRIPTHINK_NONE",
            "R_STRIPTHINK_EMPTY",
            "R_STRIPTHINK_SINGLE",
            "R_STRIPTHINK_MULTILINE",
            "R_STRIPTHINK_MULTIPLE",
            "R_STRIPTHINK_ONLY",
            "R_STRIPTHINK_WS_SURROUND",
            "R_STRIPTHINK_UNTERMINATED",
            "R_STRIPTHINK_EMPTY_BLOCK",
            "R_STRIPTHINK_NESTED_LOOK",
            "R_STRIPTHINK_ADJACENT",
    };

    @Test
    void corpusMatchesAgentPromptGolden() {
        for (String name : CASES) {
            JsonNode r = RecordingSupport.rec(field(name));
            String got = ThinkBlocks.stripThinkBlocks(r.get("in").asText());
            assertThat(got)
                    .as("strip %s", r.get("id").asText())
                    .isEqualTo(r.get("out").asText());
        }
    }

    private static String field(String name) {
        try {
            return (String) ToolContractGoldenA.class.getField(name).get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
