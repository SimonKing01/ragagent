package com.ragagent.agent.tool.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agent.fixtures.RecordingSupport;
import com.ragagent.agent.fixtures.ToolContractGoldenA;
import org.junit.jupiter.api.Test;

/**
 * NormalizeToolCallId 的录制判定（9 条）。确定性生成（空 id → 同一 tool+index 同值）、
 * 非法字符替换、64 字符截断带哈希，全部逐字比对。
 */
class NormalizeToolCallIdRecordingTest {

    private static final String[] CASES = {
            "R_NORMID_NORMAL",
            "R_NORMID_EMPTY",
            "R_NORMID_EMPTY_IDX1",
            "R_NORMID_WHITESPACE",
            "R_NORMID_SPECIAL",
            "R_NORMID_CJK",
            "R_NORMID_EXACT64",
            "R_NORMID_OVER64",
            "R_NORMID_UNICODE_MIXED",
    };

    @Test
    void corpusMatchesAgentPromptGolden() {
        for (String name : CASES) {
            JsonNode r = RecordingSupport.rec(field(name));
            String got = NormalizeToolCallId.normalize(
                    r.get("in").asText(), r.get("tool").asText(), r.get("index").asInt());
            assertThat(got)
                    .as("normalize %s", r.get("id").asText())
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
