package com.ragagent.agenttools.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** items 参数错误的文案钉子（LLM 可见）。 */
class WebFetchToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void numericLimitFromModelIsAccepted() throws Exception {
        // 模型批量 JSON 常把整数写成 4000.0：不能因为浮点就把整条抓取判失败
        WebFetchTool.WebFetchItem item =
                WebFetchTool.parseItemElement(MAPPER.readTree("{\"url\":\"https://example.com\",\"limit\":4000.0}"));
        assertThat(item).isNotNull();
        assertThat(item.url()).isEqualTo("https://example.com");
        assertThat(item.limit()).isEqualTo(4000);

        assertThat(WebFetchTool.parseItemElement(
                MAPPER.readTree("{\"url\":\"https://example.com\",\"offset\":2.0}"))).isNotNull();
    }

    @Test
    void nonStringUrlIsStillRejected() throws Exception {
        assertThat(WebFetchTool.parseItemElement(MAPPER.readTree("{\"url\":1}"))).isNull();
        assertThat(WebFetchTool.parseItemElement(MAPPER.readTree("true"))).isNull();
        assertThat(WebFetchTool.parseItemElement(
                MAPPER.readTree("{\"url\":\"https://example.com\",\"limit\":\"4000\"}"))).isNull();
    }

    @Test
    void itemsTypeErrorUsesArgumentWording() throws Exception {
        assertThat(WebFetchTool.itemsTypeMessage(MAPPER.readTree("true")))
                .isEqualTo("invalid argument 'items': expected an array of {url, offset?, limit?}, got boolean");
        assertThat(WebFetchTool.itemsTypeMessage(MAPPER.readTree("{\"url\":1}")))
                .isEqualTo("invalid argument 'items': expected an array of {url, offset?, limit?}, got object");
    }
}
