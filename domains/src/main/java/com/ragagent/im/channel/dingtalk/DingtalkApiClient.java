package com.ragagent.im.channel.dingtalk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.im.runtime.AdapterInterfaces;
import com.ragagent.im.runtime.ImTypes;
import com.ragagent.im.runtime.IncomingMessage;
import com.ragagent.im.runtime.ReplyMessage;
import com.ragagent.im.runtime.ThinkDisplay;
import com.ragagent.platform.security.SsrfGuard;
import com.ragagent.platform.web.JsonMappers;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 钉钉 HTTP 面：token、发送、下载三件事。
 *
 * <p>token 用单飞合并并发未命中——锁只保护状态，HTTP 在锁外由 leader 执行，
 * follower 等同一个 future，避免并发打 token 端点。缓存余量 5 分钟；{@code expireIn}
 * 小于 5 分钟时至少缓存 60 秒，不能算出过去时刻（否则 token 会永远处于“已过期”）。</p>
 */
final class DingtalkApiClient {

    private static final Logger log = LoggerFactory.getLogger(DingtalkApiClient.class);
    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    static final String DEFAULT_API_BASE_URL = "https://api.dingtalk.com";
    /** 临时下载链接的放行后缀。 */
    static final List<String> ALLOWED_DOWNLOAD_HOST_SUFFIXES =
            List.of(".aliyuncs.com", ".dingtalk.com");

    private final String clientId;
    private final String clientSecret;
    private final String apiBaseUrl;
    private final SsrfGuard ssrfGuard;
    private final HttpClient http;

    private final Object tokenLock = new Object();
    private String token = "";
    private Instant tokenExpiresAt = Instant.EPOCH;
    private CompletableFuture<String> tokenFlight;

    DingtalkApiClient(String clientId, String clientSecret, String apiBaseUrl,
                      SsrfGuard ssrfGuard) {
        this.clientId = clientId == null ? "" : clientId;
        this.clientSecret = clientSecret == null ? "" : clientSecret;
        String base = apiBaseUrl == null || apiBaseUrl.isBlank()
                ? DEFAULT_API_BASE_URL : apiBaseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.apiBaseUrl = base;
        this.ssrfGuard = ssrfGuard;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    String apiBaseUrl() {
        return apiBaseUrl;
    }

    /** sessionWebhook 优先，否则 OpenAPI。 */
    void sendReply(IncomingMessage incoming, ReplyMessage reply) throws Exception {
        String content = ThinkDisplay.formatIMDisplayContent(
                reply.content == null ? "" : reply.content, ThinkDisplay.STREAM_DISPLAY_FINAL);
        String sessionWebhook = incoming.extra == null ? ""
                : incoming.extra.getOrDefault("session_webhook", "");
        if (!sessionWebhook.isEmpty()) {
            replyViaSessionWebhook(sessionWebhook, content);
            return;
        }
        replyViaOpenApi(incoming, content);
    }

    /** markdown 直发（先过 SSRF 校验）。 */
    void replyViaSessionWebhook(String webhookUrl, String content) throws Exception {
        if (ssrfGuard != null) {
            try {
                ssrfGuard.validateURLForSSRF(webhookUrl);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(
                        "dingtalk sessionWebhook rejected by SSRF policy: " + e.getMessage(), e);
            }
        }
        ObjectNode markdown = MAPPER.createObjectNode();
        markdown.put("title", "Reply");
        markdown.put("text", content);
        ObjectNode body = MAPPER.createObjectNode();
        body.put("msgtype", "markdown");
        body.set("markdown", markdown);

        HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body),
                        StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("dingtalk sessionWebhook returned "
                    + response.statusCode() + ": "
                    + new String(response.body() == null ? new byte[0] : response.body(),
                    StandardCharsets.UTF_8));
        }
    }

    /** 群 groupMessages/send，私聊 oToMessages/batchSend。 */
    void replyViaOpenApi(IncomingMessage incoming, String content) throws Exception {
        String accessToken = getAccessToken();
        ObjectNode msgParam = MAPPER.createObjectNode();
        msgParam.put("title", "Reply");
        msgParam.put("text", content);

        ObjectNode body = MAPPER.createObjectNode();
        body.put("robotCode", clientId);
        body.put("msgKey", "sampleMarkdown");
        body.put("msgParam", MAPPER.writeValueAsString(msgParam));

        String path;
        if (ImTypes.CHAT_TYPE_GROUP.equals(incoming.chatType)) {
            path = "/v1.0/robot/groupMessages/send";
            body.put("openConversationId", incoming.chatId == null ? "" : incoming.chatId);
        } else {
            path = "/v1.0/robot/oToMessages/batchSend";
            ArrayNode userIds = MAPPER.createArrayNode();
            userIds.add(incoming.userId == null ? "" : incoming.userId);
            body.set("userIds", userIds);
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(apiBaseUrl + path))
                .header("Content-Type", "application/json")
                .header("x-acs-dingtalk-access-token", accessToken)
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body),
                        StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("dingtalk OpenAPI returned " + response.statusCode()
                    + ": " + new String(response.body() == null ? new byte[0] : response.body(),
                    StandardCharsets.UTF_8));
        }
    }

    /** downloadCode → 临时 URL → 白名单/SSRF 校验 → 字节。 */
    AdapterInterfaces.FileDownloader.DownloadedFile downloadFile(IncomingMessage msg) throws Exception {
        String downloadCode = msg.fileKey == null ? "" : msg.fileKey;
        if (downloadCode.isEmpty()) {
            throw new IllegalArgumentException("no downloadCode in message");
        }
        String robotCode = msg.extra == null ? "" : msg.extra.getOrDefault("robot_code", "");
        if (robotCode.isEmpty()) {
            robotCode = clientId;
        }

        Map<String, String> body = new LinkedHashMap<>();
        body.put("robotCode", robotCode);
        body.put("downloadCode", downloadCode);
        byte[] respBody = dingtalkApi("POST", "/v1.0/robot/messageFiles/download", body);

        JsonNode parsed = MAPPER.readTree(respBody);
        String downloadUrl = parsed.path("downloadUrl").asText("");
        if (downloadUrl.isEmpty()) {
            throw new IllegalStateException("download response has no downloadUrl: "
                    + new String(respBody, StandardCharsets.UTF_8));
        }
        validateFileDownloadUrl(downloadUrl);

        HttpRequest request = HttpRequest.newBuilder(URI.create(downloadUrl))
                .timeout(Duration.ofSeconds(60)).GET().build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("download file returned " + response.statusCode() + ": "
                    + new String(response.body() == null ? new byte[0] : response.body(),
                    StandardCharsets.UTF_8));
        }
        return new AdapterInterfaces.FileDownloader.DownloadedFile(response.body(),
                msg.fileName == null ? "" : msg.fileName);
    }

    /** 宿主机白名单放行，其余走 SSRF 校验。 */
    void validateFileDownloadUrl(String rawUrl) {
        if (isAllowedDownloadHost(rawUrl)) {
            return;
        }
        if (ssrfGuard != null) {
            ssrfGuard.validateURLForSSRF(rawUrl);
        }
    }

    static boolean isAllowedDownloadHost(String rawUrl) {
        String host;
        try {
            host = URI.create(rawUrl).getHost();
        } catch (RuntimeException e) {
            return false;
        }
        if (host == null) {
            return false;
        }
        String lower = host.toLowerCase(Locale.ROOT);
        for (String suffix : ALLOWED_DOWNLOAD_HOST_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /** 带 token 的通用调用（非 200 抛）。 */
    byte[] dingtalkApi(String method, String path, Object body) throws Exception {
        String accessToken = getAccessToken();
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiBaseUrl + path))
                .header("Content-Type", "application/json")
                .header("x-acs-dingtalk-access-token", accessToken)
                .timeout(Duration.ofSeconds(15))
                .method(method, HttpRequest.BodyPublishers.ofString(
                        MAPPER.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        byte[] respBody = response.body() == null ? new byte[0] : response.body();
        if (response.statusCode() != 200) {
            throw new IllegalStateException("dingtalk API " + path + " returned "
                    + response.statusCode() + ": "
                    + new String(respBody, StandardCharsets.UTF_8));
        }
        return respBody;
    }

    /** 单飞取 token：并发未命中只打一次 token 端点。 */
    String getAccessToken() throws Exception {
        CompletableFuture<String> flight;
        boolean leader = false;
        synchronized (tokenLock) {
            if (!token.isEmpty() && Instant.now().isBefore(tokenExpiresAt)) {
                return token;
            }
            if (tokenFlight != null) {
                flight = tokenFlight;
            } else {
                flight = new CompletableFuture<>();
                tokenFlight = flight;
                leader = true;
            }
        }
        if (!leader) {
            return await(flight);
        }
        try {
            FreshToken fresh = fetchAccessToken();
            synchronized (tokenLock) {
                token = fresh.value();
                tokenExpiresAt = Instant.now().plusSeconds(fresh.ttlSeconds());
                tokenFlight = null;
            }
            flight.complete(fresh.value());
            return fresh.value();
        } catch (Exception e) {
            synchronized (tokenLock) {
                tokenFlight = null;
            }
            flight.completeExceptionally(e);
            throw e;
        }
    }

    private static String await(CompletableFuture<String> flight) throws Exception {
        try {
            return flight.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw new IllegalStateException("dingtalk token fetch failed", e.getCause());
        }
    }

    private FreshToken fetchAccessToken() throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("appKey", clientId);
        body.put("appSecret", clientSecret);
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(apiBaseUrl + "/v1.0/oauth2/accessToken"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body),
                        StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("dingtalk accessToken returned " + response.statusCode()
                    + ": " + new String(response.body() == null ? new byte[0] : response.body(),
                    StandardCharsets.UTF_8));
        }
        JsonNode result = MAPPER.readTree(response.body());
        String accessToken = result.path("accessToken").asText("");
        if (accessToken.isEmpty()) {
            throw new IllegalStateException("empty access token from dingtalk");
        }
        long expireIn = result.path("expireIn").asLong(0);
        // 留 5 分钟余量；expireIn 过短时至少缓存 60 秒，绝不产生过去时刻。
        long ttl = expireIn <= 0 ? 60 : Math.max(60, expireIn - 300);
        log.debug("dingtalk access token refreshed: expireIn={}, ttl={}", expireIn, ttl);
        return new FreshToken(accessToken, ttl);
    }

    private record FreshToken(String value, long ttlSeconds) {
    }
}
