package com.ragagent.modelruntime.adapter.provider.vlm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.modelruntime.adapter.provider.asr.OpenAiAsrTranscriber;
import com.ragagent.platform.config.AppEnvLookup;
import com.ragagent.platform.http.LlmTransport;
import com.ragagent.platform.web.JsonMappers;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * {@link VlmClient.Transport} 的生产实现。
 *
 * <ul>
 *   <li>超时：{@code VLM_HTTP_TIMEOUT_SECONDS}（正整数秒）缺省 180s；</li>
 *   <li>鉴权：{@code Authorization: Bearer <apiKey>}；</li>
 *   <li>非 2xx 错误文案走 OpenAI 错误报文仿真
 *       （{@link OpenAiAsrTranscriber#openAiErrorText}）——
 *       与 ASR 共用同一套字节契约；</li>
 *   <li>网络层错误：{@code Post "<url>": <cause>} 形态。</li>
 * </ul>
 */
public class VlmHttpTransport implements VlmClient.Transport {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(180);
    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    @Override
    public String post(String url, String apiKey, Object jsonBody) throws Exception {
        byte[] body = MAPPER.writeValueAsBytes(jsonBody);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + (apiKey == null ? "" : apiKey))
                .timeout(timeout())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<InputStream> resp;
        try {
            resp = LlmTransport.send(request);
        } catch (IOException e) {
            throw new RuntimeException("Post \"" + url + "\": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Post \"" + url + "\": interrupted");
        }
        byte[] respBody;
        try (InputStream stream = resp.body()) {
            respBody = stream == null ? new byte[0] : stream.readAllBytes();
        }
        if (resp.statusCode() != 200) {
            throw new RuntimeException(
                    OpenAiAsrTranscriber.openAiErrorText(resp.statusCode(), respBody));
        }
        return new String(respBody, StandardCharsets.UTF_8);
    }

    /** 超时：env 正整数秒生效，否则 180s。 */
    static Duration timeout() {
        String raw = AppEnvLookup.get("VLM_HTTP_TIMEOUT_SECONDS");
        if (raw != null && !raw.isBlank()) {
            try {
                int secs = Integer.parseInt(raw.trim());
                if (secs > 0) {
                    return Duration.ofSeconds(secs);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return DEFAULT_TIMEOUT;
    }
}
