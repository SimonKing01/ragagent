package com.ragagent.agenttools.web;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** 入参类型错误的文案钉子（LLM 可见）。 */
class WebSearchToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void fieldTypeErrorsUseFieldWording() throws Exception {
        assertThatThrownBy(() -> WebSearchTool.parseInput(MAPPER.readTree("{\"query\":1}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("field 'query' must be a string, got number");
        assertThatThrownBy(() -> WebSearchTool.parseInput(
                MAPPER.readTree("{\"query\":\"a\",\"count\":\"x\"}")))
                .hasMessage("field 'count' must be an integer, got string");
        assertThatThrownBy(() -> WebSearchTool.parseInput(
                MAPPER.readTree("{\"query\":\"a\",\"content\":\"yes\"}")))
                .hasMessage("field 'content' must be a boolean, got string");
        assertThatThrownBy(() -> WebSearchTool.parseInput(
                MAPPER.readTree("{\"query\":\"a\",\"country\":[]}")))
                .hasMessage("field 'country' must be a string, got array");
    }
}
