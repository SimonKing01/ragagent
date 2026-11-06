package com.ragagent.modelruntime.adapter.provider.asr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.contract.model.AsrTranscriber;
import com.ragagent.platform.http.LlmTransport;
import com.ragagent.platform.security.SsrfGuard;
import com.ragagent.platform.web.JsonMappers;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * 百炼（DashScope）**原生多模态** ASR：适用于专属部署实例
 * （{@code /api/v1/services/aigc/multimodal-generation/generation}）这类
 * 不提供 OpenAI 兼容 {@code /audio/transcriptions} 的端点。
 *
 * <p>请求体按 DashScope 原生协议组装：音频以 base64 data URL 放在
 * {@code input.messages[].content[].audio}，模型名原样透传。</p>
 *
 * <p>响应取 {@code output.choices[].message.content[].text}；若上游直接给
 * {@code output.text} / {@code text} 也兼容。</p>
 */
public class DashScopeAsrTranscriber implements AsrTranscriber {

    /** 与 OpenAI 兼容实现保持同一超时口径（长音频转写慢）。 */
    private static final Duration ASR_DEFAULT_TIMEOUT = Duration.ofSeconds(300);

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    private final SsrfGuard ssrfGuard;

    public DashScopeAsrTranscriber(SsrfGuard ssrfGuard) {
        this.ssrfGuard = ssrfGuard;
    }

    /** 该 Base URL 是否走 DashScope 原生多模态协议。 */
    public static boolean supports(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        String url = baseUrl.toLowerCase(Locale.ROOT);
        return url.contains("/multimodal-generation")
                || url.contains("maas.aliyuncs.com")
                || url.contains("/services/aigc/");
    }

    @Override
    public TranscriptionResult transcribe(AsrConfig config, byte[] audioBytes, String fileName) {
        if (config.baseUrl() != null && !config.baseUrl().isEmpty()) {
            try {
                ssrfGuard.validateURLForSSRF(config.baseUrl());
            } catch (RuntimeException e) {
                throw new AsrCreateException("base URL SSRF check failed: " + e.getMessage(), e);
            }
        }
        if (audioBytes == null || audioBytes.length == 0) {
            throw new AsrTranscribeException("ASR transcription request failed: audio bytes are empty");
        }
        String url = config.baseUrl();
        byte[] body = requestBody(config, audioBytes, fileName);

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + (config.apiKey() == null ? "" : config.apiKey()))
                .timeout(ASR_DEFAULT_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (config.customHeaders() != null) {
            config.customHeaders().forEach(builder::header);
        }

        HttpResponse<InputStream> resp;
        try {
            resp = LlmTransport.send(builder.build());
        } catch (IOException e) {
            throw new AsrTranscribeException("ASR transcription request failed: Post \"" + url
                    + "\": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AsrTranscribeException("ASR transcription request failed: interrupted");
        }

        byte[] respBody;
        try (InputStream stream = resp.body()) {
            respBody = stream == null ? new byte[0] : stream.readAllBytes();
        } catch (IOException e) {
            throw new AsrTranscribeException("ASR transcription request failed: " + e.getMessage(), e);
        }
        int status = resp.statusCode();
        if (status < 200 || status >= 300) {
            throw new AsrTranscribeException("ASR transcription request failed: HTTP " + status
                    + " " + reasonText(respBody));
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(respBody);
        } catch (IOException e) {
            throw new AsrTranscribeException(
                    "ASR transcription request failed: decode response: " + e.getMessage(), e);
        }
        String text = extractText(node);
        if (text.isEmpty()) {
            throw new AsrTranscribeException(
                    "ASR transcription request failed: empty transcript: " + abbreviate(respBody));
        }
        return new TranscriptionResult(text, List.of());
    }

    /** 组装 DashScope 原生请求体（音频走 base64 data URL）。 */
    byte[] requestBody(AsrConfig config, byte[] audioBytes, String fileName) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", config.modelName() == null ? "" : config.modelName());

        ObjectNode input = root.putObject("input");
        ArrayNode messages = input.putArray("messages");
        ObjectNode message = messages.addObject();
        message.put("role", "user");
        ArrayNode content = message.putArray("content");
        // 官方协议：content[].type=input_audio + input_audio.data（URL 或 base64 Data URI）
        ObjectNode audioPart = content.addObject();
        audioPart.put("type", "input_audio");
        audioPart.putObject("input_audio").put("data", audioDataUrl(audioBytes, fileName));

        // parameters.format 必填（缺了上游报 "format is empty"）；sample_rate 与
        // language_hints 可选。我们统一送 16k 单声道 mp3（见 FfmpegMedia 抽音轨）。
        ObjectNode parameters = root.putObject("parameters");
        parameters.put("format", audioFormat(fileName));
        parameters.put("sample_rate", "16000");
        if (config.language() != null && !config.language().isBlank()) {
            parameters.putArray("language_hints").add(config.language().trim());
        }
        try {
            return MAPPER.writeValueAsBytes(root);
        } catch (IOException e) {
            throw new AsrTranscribeException("ASR transcription request failed: encode request: "
                    + e.getMessage(), e);
        }
    }

    /** parameters.format：与我们抽出的音轨一致（mp3/wav/m4a）。 */
    static String audioFormat(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".wav")) {
            return "wav";
        }
        if (lower.endsWith(".m4a") || lower.endsWith(".mp4")) {
            return "m4a";
        }
        return "mp3";
    }

    /** mp3（含其它音频）统一按 audio/mpeg 声明；上游只校验前缀。 */
    static String audioDataUrl(byte[] audioBytes, String fileName) {
        // 百炼按 mime 子类型识别格式（mpeg 会被判成 "format is empty"），用 mp3
        String mime = "audio/mp3";
        if (fileName != null && !fileName.isBlank()) {
            String lower = fileName.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".wav")) {
                mime = "audio/wav";
            } else if (lower.endsWith(".m4a") || lower.endsWith(".mp4")) {
                mime = "audio/mp4";
            }
        }
        return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(audioBytes);
    }

    /** 从响应里取转写文本；兼容 content 数组 / 字符串与 output.text。 */
    static String extractText(JsonNode node) {
        if (node == null || !node.isObject()) {
            return "";
        }
        JsonNode choices = node.path("output").path("choices");
        if (choices.isArray()) {
            for (JsonNode choice : choices) {
                JsonNode content = choice.path("message").path("content");
                String joined = joinContent(content);
                if (!joined.isEmpty()) {
                    return joined;
                }
            }
        }
        String direct = node.path("output").path("text").asText("");
        if (!direct.isBlank()) {
            return direct.trim();
        }
        return node.path("text").asText("").trim();
    }

    private static String joinContent(JsonNode content) {
        if (content == null || content.isMissingNode() || content.isNull()) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText("").trim();
        }
        if (!content.isArray()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (JsonNode part : content) {
            String text = part.path("text").asText("");
            if (!text.isBlank()) {
                parts.add(text.trim());
            }
        }
        return String.join("\n", parts).trim();
    }

    /** 错误详情：优先取 message/code，取不到退回 body 原文。 */
    private static String reasonText(byte[] body) {
        String raw = new String(body, StandardCharsets.UTF_8).trim();
        try {
            JsonNode node = MAPPER.readTree(raw);
            String message = node.path("message").asText("");
            String code = node.path("code").asText("");
            if (!message.isBlank()) {
                return code.isBlank() ? message : "(" + code + "): " + message;
            }
        } catch (IOException ignored) {
            // 非 JSON 错误体，退回原文
        }
        return abbreviate(body);
    }

    private static String abbreviate(byte[] body) {
        String raw = new String(body, StandardCharsets.UTF_8).trim();
        return raw.length() <= 300 ? raw : raw.substring(0, 300) + "…";
    }
}
