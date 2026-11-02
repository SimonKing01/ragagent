package com.ragagent.agenttools.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** items 参数错误的文案钉子（LLM 可见）。 */
class WebFetchToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void itemsTypeErrorUsesArgumentWording() throws Exception {
        assertThat(WebFetchTool.itemsTypeMessage(MAPPER.readTree("true")))
                .isEqualTo("invalid argument 'items': expected an array of {url, offset?, limit?}, got boolean");
        assertThat(WebFetchTool.itemsTypeMessage(MAPPER.readTree("{\"url\":1}")))
                .isEqualTo("invalid argument 'items': expected an array of {url, offset?, limit?}, got object");
    }
}
