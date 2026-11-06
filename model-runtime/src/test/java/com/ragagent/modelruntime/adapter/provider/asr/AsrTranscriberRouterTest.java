package com.ragagent.modelruntime.adapter.provider.asr;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.contract.model.AsrTranscriber;
import com.ragagent.platform.web.JsonMappers;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 百炼专属实例（原生多模态）与 OpenAI 兼容端点的分流与报文形状。 */
class AsrTranscriberRouterTest {

    private static final AsrTranscriber.AsrConfig DASHSCOPE = new AsrTranscriber.AsrConfig(
            "https://ws-71b1cf8eo06x2dn1.cn-beijing.maas.aliyuncs.com"
                    + "/api/v1/services/aigc/multimodal-generation/generation",
            "qwen-audio-3.1-asr-flash", "sk-test", "m1", "zh", null);

    private static final AsrTranscriber.AsrConfig OPENAI = new AsrTranscriber.AsrConfig(
            "https://api.openai.com/v1", "whisper-1", "sk-test", "m2", "", null);

    @Test
    void dashScopeEndpointsAreRoutedToNativeProtocol() {
        assertThat(DashScopeAsrTranscriber.supports(DASHSCOPE.baseUrl())).isTrue();
        assertThat(DashScopeAsrTranscriber.supports("https://dashscope.aliyuncs.com/compatible-mode/v1"))
                .isFalse();
        assertThat(DashScopeAsrTranscriber.supports("")).isFalse();
        assertThat(DashScopeAsrTranscriber.supports(null)).isFalse();
    }

    @Test
    void routerPicksImplementationByBaseUrl() {
        AsrTranscriber openAi = (c, b, n) -> new AsrTranscriber.TranscriptionResult("openai", List.of());
        AsrTranscriber dashScope = (c, b, n) -> new AsrTranscriber.TranscriptionResult("dashscope", List.of());
        AsrTranscriberRouter router = new AsrTranscriberRouter(openAi, dashScope);

        assertThat(router.transcribe(DASHSCOPE, new byte[] {1}, "a.mp3").text()).isEqualTo("dashscope");
        assertThat(router.transcribe(OPENAI, new byte[] {1}, "a.mp3").text()).isEqualTo("openai");
    }

    @Test
    void requestBodyFollowsMultimodalGenerationShape() throws Exception {
        DashScopeAsrTranscriber transcriber = new DashScopeAsrTranscriber(null);
        byte[] body = transcriber.requestBody(DASHSCOPE, "abc".getBytes(StandardCharsets.UTF_8), "v.mp3");

        JsonNode node = JsonMappers.lenient().readTree(body);
        assertThat(node.path("model").asText()).isEqualTo("qwen-audio-3.1-asr-flash");
        JsonNode content = node.path("input").path("messages").get(0).path("content");
        assertThat(content.isArray()).isTrue();
        assertThat(content.get(0).path("type").asText()).isEqualTo("input_audio");
        assertThat(content.get(0).path("input_audio").path("data").asText())
                .startsWith("data:audio/");
        // 官方协议要求 parameters.format 必填，缺了上游会报 "format is empty"
        assertThat(node.path("parameters").path("format").asText()).isEqualTo("mp3");
        assertThat(node.path("parameters").path("sample_rate").asText()).isEqualTo("16000");
        assertThat(node.path("parameters").path("language_hints").get(0).asText()).isEqualTo("zh");
    }

    @Test
    void transcriptIsReadFromChoicesContentArray() throws Exception {
        JsonNode node = JsonMappers.lenient().readTree("""
                {"output":{"choices":[{"message":{"content":[{"text":" 你好世界 "},
                 {"text":"第二句"}]}}]},"request_id":"r1"}
                """);

        assertThat(DashScopeAsrTranscriber.extractText(node)).isEqualTo("你好世界\n第二句");
    }

    @Test
    void transcriptFallsBackToPlainTextShapes() throws Exception {
        assertThat(DashScopeAsrTranscriber.extractText(
                JsonMappers.lenient().readTree("{\"output\":{\"text\":\" 整段 \"}}"))).isEqualTo("整段");
        assertThat(DashScopeAsrTranscriber.extractText(
                JsonMappers.lenient().readTree("{\"text\":\" raw \"}"))).isEqualTo("raw");
        assertThat(DashScopeAsrTranscriber.extractText(null)).isEmpty();
    }
}
