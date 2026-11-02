package com.ragagent.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agent.fixtures.ContractJson;
import com.ragagent.agent.fixtures.RecordingSupport;
import com.ragagent.agent.fixtures.ToolContractGoldenA;
import com.ragagent.platform.web.ToolJson;
import org.junit.jupiter.api.Test;

/**
 * ToolJson 的录制语料（9 条：把 JSON 解析成树再重编码）。
 *
 * <p>HTML 转义形态不再构成断言目标（对比经
 * {@link com.ragagent.agent.fixtures.ContractJson#deep} 语义归一）；**键序**改由本类的
 * {@code keysAreSortedAlphabetically} 单独钉住（它是 LLM 载荷的确定性前提）。</p>
 */
class ToolJsonRecordingTest {

    private static final String[] CASES = {
            "R_CODEC_SORT_HTML",
            "R_CODEC_NESTED_SORT",
            "R_CODEC_UNICODE",
            "R_CODEC_ESCAPES",
            "R_CODEC_FLOATS",
            "R_CODEC_EMPTY_OBJ",
            "R_CODEC_EMPTY_ARR",
            "R_CODEC_NULL_VAL",
            "R_CODEC_BOOL_FALSE",
    };

    @Test
    void reencodeMatchesAgentPromptGolden() {
        for (String name : CASES) {
            JsonNode r = RecordingSupport.rec(field(name));
            JsonNode tree = RecordingSupport.readTree(r.get("in").asText());
            assertThat(ContractJson.deep(ToolJson.write(tree)))
                    .as("codec %s", r.get("id").asText())
                    .isEqualTo(ContractJson.deep(r.get("out").asText()));
        }
    }

    /** 键序铁律：所有层级 map 键按字母序（LLM 载荷的确定性前提，单独钉住）。 */
    @Test
    void keysAreSortedAlphabetically() {
        JsonNode tree = RecordingSupport.readTree("{\"b\":1,\"a\":{\"d\":2,\"c\":[3]}}");
        assertThat(ToolJson.write(tree)).isEqualTo("{\"a\":{\"c\":[3],\"d\":2},\"b\":1}");
    }

    private static String field(String name) {
        try {
            return (String) ToolContractGoldenA.class.getField(name).get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
