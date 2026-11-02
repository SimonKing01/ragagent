package com.ragagent.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agent.fixtures.RecordingSupport;
import com.ragagent.agent.fixtures.ToolContractGoldenA;
import com.ragagent.contract.tool.ToolRequest;
import org.junit.jupiter.api.Test;

/**
 * TodoWriteTool 的录制语料（5 条有效用例；bad_json 一条不录——录制侧
 * 在 JSON 解析层失败，Java 的 args 在上游已解析成 JsonNode，分层差异
 * 已在类注释说明）。output 文本与 data map 的 JSON 字节逐字比对
 * （data 用 ToolJson 编码：键序 + HTML 转义 + 数字形态）。
 */
class TodoWriteRecordingTest {

    private static final String[] CASES = {
            "R_TODO_BASIC",
            "R_TODO_NO_TASK",
            "R_TODO_NO_STEPS",
            "R_TODO_EMPTY_STEPS",
            "R_TODO_UNKNOWN_STATUS",
    };

    @Test
    void executionsMatchAgentPromptGolden() {
        for (String name : CASES) {
            JsonNode r = RecordingSupport.rec(field(name));
            JsonNode args = RecordingSupport.readTree(r.get("args").asText());
            var result = new TodoWriteTool().execute(ToolRequest.of(args));

            assertThat(result.isSuccess())
                    .as("todo %s success", r.get("id").asText())
                    .isEqualTo(r.get("success").asBoolean());
            assertThat(result.getOutput())
                    .as("todo %s output", r.get("id").asText())
                    .isEqualTo(r.get("output").asText());
            String wantData = r.get("data").asText();
            if ("null".equals(wantData)) {
                assertThat(result.getData()).as("todo %s data", r.get("id").asText()).isNull();
            } else {
                assertThat(RecordingSupport.canonicalJson(RecordingSupport.PLAIN.valueToTree(result.getData())))
                        .as("todo %s data", r.get("id").asText())
                        .isEqualTo(RecordingSupport.canonicalJson(RecordingSupport.readTree(wantData)));
            }
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
