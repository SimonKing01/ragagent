package com.ragagent.im.channel.feishu;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.platform.web.JsonMappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 含图回答的成品卡：非流式 + markdown 正文（含 img_key）。
 *
 * <p>流式卡片里 markdown 图片只显示占位，关流式后又拒绝更新元素（飞书 code=300309），
 * 所以带图回答必须整条改用非流式卡片发送——这里钉住卡片 JSON 的关键字段。</p>
 */
class FeishuCardJsonTest {

    @Test
    @DisplayName("成品卡：streaming_mode=false，正文原样进 markdown 元素")
    void finalCardIsNotStreaming() throws Exception {
        JsonNode card = JsonMappers.lenient()
                .readTree(FeishuCardStreamOps.buildContentCardJson(
                        "看这三款车：\n\n![i60](img_v3_02178abc)\n"));

        assertThat(card.path("schema").asText()).isEqualTo("2.0");
        assertThat(card.path("config").path("streaming_mode").asBoolean()).isFalse();
        JsonNode element = card.path("body").path("elements").get(0);
        assertThat(element.path("tag").asText()).isEqualTo("markdown");
        assertThat(element.path("content").asText()).contains("img_v3_02178abc");
        assertThat(element.path("content").asText()).contains("看这三款车");
    }

    @Test
    @DisplayName("成品卡：空正文不炸")
    void finalCardToleratesNullContent() throws Exception {
        JsonNode card = JsonMappers.lenient()
                .readTree(FeishuCardStreamOps.buildContentCardJson(null));
        assertThat(card.path("body").path("elements").get(0).path("content").asText())
                .isEmpty();
    }
}
