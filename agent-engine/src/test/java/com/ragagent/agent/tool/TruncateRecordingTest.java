package com.ragagent.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agent.fixtures.RecordingSupport;
import com.ragagent.agent.fixtures.ToolContractGoldenA;
import com.ragagent.contract.tool.ToolOutput;
import org.junit.jupiter.api.Test;

/**
 * ToolOutput.truncateToolOutput 的录制边界（15 条）。覆盖：上限内原样/恰等上限/零与负上限/
 * 头尾 70/30 拆分/CJK 按码点计/24000 默认边界/200 码点标记保留的
 * 临界（199/200/201）。
 */
class TruncateRecordingTest {

    private static final String[] CASES = {
            "R_TRUNC_SHORT_UNCHANGED",
            "R_TRUNC_EXACT_LIMIT",
            "R_TRUNC_ZERO_MAX",
            "R_TRUNC_NEG_MAX",
            "R_TRUNC_HT_20000_5000",
            "R_TRUNC_ABC_15000_5000",
            "R_TRUNC_CJK_10000_5000",
            "R_TRUNC_MIXED_12000_5000",
            "R_TRUNC_CJK_WITHIN",
            "R_TRUNC_AT_24000_DEFAULT",
            "R_TRUNC_OVER_24000_DEFAULT",
            "R_TRUNC_TINY_MAX_50",
            "R_TRUNC_MAX_199",
            "R_TRUNC_MAX_200",
            "R_TRUNC_MAX_201",
    };

    @Test
    void boundariesMatchAgentPromptGolden() {
        for (String name : CASES) {
            JsonNode r = RecordingSupport.rec(field(name));
            String input = RecordingSupport.buildTruncInput(r);
            int maxChars = r.get("maxChars").asInt();
            String want = r.get("out").asText();

            String got = ToolOutput.truncateToolOutput(input, maxChars);
            assertThat(got)
                    .as("truncate %s", r.get("id").asText())
                    .isEqualTo(want);
            assertThat(got.codePointCount(0, got.length()))
                    .as("truncate %s rune count", r.get("id").asText())
                    .isEqualTo(r.get("outRunes").asInt());
        }
    }

    @Test
    void markerTextMatchesGoFormat() {
        // "... [output truncated: 20000 → 5000 chars, showing first 3360 + last 1440] ..."
        JsonNode r = RecordingSupport.rec(field("R_TRUNC_HT_20000_5000"));
        String got = ToolOutput.truncateToolOutput(RecordingSupport.buildTruncInput(r), 5000);
        assertThat(got).contains("\n\n... [output truncated: 20000 → 5000 chars, showing first 3360 + last 1440] ...\n\n");
        assertThat(got.codePointCount(0, got.length())).isLessThanOrEqualTo(5000 + 200);
    }

    private static String field(String name) {
        try {
            return (String) ToolContractGoldenA.class.getField(name).get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
