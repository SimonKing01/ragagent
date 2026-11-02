package com.ragagent.im.channel.dingtalk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.im.runtime.AdapterInterfaces;
import com.ragagent.im.runtime.CallbackExchange;
import com.ragagent.im.runtime.ImAdapterVerify;
import com.ragagent.im.runtime.ImTypes;
import com.ragagent.im.runtime.IncomingMessage;
import com.ragagent.im.runtime.ReplyMessage;
import com.ragagent.platform.security.SsrfGuard;
import com.ragagent.platform.web.JsonMappers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

/**
 * 钉钉适配器（薄壳）：验签 + 三个接口的转发。
 *
 * <p>解析在 {@link DingtalkCallbackParser}，HTTP/token 在 {@link DingtalkApiClient}，
 * AI 卡片流在 {@link DingtalkCardStream}。</p>
 *
 * <p>验签：{@code Timestamp}/{@code Sign} 头，时间窗 ±3600 秒，期望签名 =
 * Base64(HMAC-SHA256({@code <timestamp>\n<clientSecret>}, clientSecret))
 * （走共享 {@link ImAdapterVerify#dingtalkExpectedSignature}），定长比较；
 * clientSecret 为空跳过。</p>
 *
 * <p>流 ID 由卡片流生成：有渠道 id 时 {@code dt:<channelId>:<userId>:<messageId>}，
 * 直接构造（测试/无渠道）时回退 {@code dt:<userId>:<messageId>}。</p>
 */
public class DingtalkAdapter implements AdapterInterfaces.Adapter,
        AdapterInterfaces.StreamSender, AdapterInterfaces.FileDownloader {

    public static final String DEFAULT_API_BASE_URL = DingtalkApiClient.DEFAULT_API_BASE_URL;

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    private final String clientSecret;
    private final DingtalkApiClient apiClient;
    private final DingtalkCardStream cardStream;

    public DingtalkAdapter(String clientId, String clientSecret, String cardTemplateId,
                           String apiBaseUrl, SsrfGuard ssrfGuard) {
        this(clientId, clientSecret, cardTemplateId, apiBaseUrl, ssrfGuard, "");
    }

    public DingtalkAdapter(String clientId, String clientSecret, String cardTemplateId,
                           String apiBaseUrl, SsrfGuard ssrfGuard, String channelKey) {
        this.clientSecret = clientSecret == null ? "" : clientSecret;
        this.apiClient = new DingtalkApiClient(clientId, clientSecret, apiBaseUrl, ssrfGuard);
        this.cardStream = new DingtalkCardStream(apiClient, clientId, cardTemplateId, channelKey);
    }

    @Override
    public String platform() {
        return ImTypes.PLATFORM_DINGTALK;
    }

    /** 钉钉不走 URL 挑战。 */
    @Override
    public boolean handleURLVerification(CallbackExchange exchange) {
        return false;
    }

    /** Sign/Timestamp 头 + ±1 小时时间窗 + 定长比较。 */
    @Override
    public Exception verifyCallback(CallbackExchange exchange) {
        if (clientSecret.isEmpty()) {
            return null;
        }
        String timestamp = exchange.header("Timestamp");
        String sign = exchange.header("Sign");
        if (timestamp == null || timestamp.isEmpty() || sign == null || sign.isEmpty()) {
            return new AdapterInterfaces.VerifyException("missing timestamp or sign header");
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            return new AdapterInterfaces.VerifyException("invalid timestamp: " + e.getMessage());
        }
        long diff = Instant.now().toEpochMilli() - ts;
        if (diff > 3600L * 1000 || diff < -3600L * 1000) {
            return new AdapterInterfaces.VerifyException("timestamp expired");
        }
        String expected = ImAdapterVerify.dingtalkExpectedSignature(clientSecret, timestamp);
        if (!MessageDigest.isEqual(sign.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8))) {
            return new AdapterInterfaces.VerifyException("invalid signature");
        }
        return null;
    }

    @Override
    public IncomingMessage parseCallback(CallbackExchange exchange) throws Exception {
        JsonNode msg;
        try {
            msg = MAPPER.readTree(exchange.body() == null ? new byte[0] : exchange.body());
        } catch (Exception e) {
            throw new IllegalArgumentException("parse callback: " + e.getMessage(), e);
        }
        if (msg == null || msg.isMissingNode() || msg.isNull()) {
            return null;
        }
        return DingtalkCallbackParser.parseCallbackMessage(msg);
    }

    @Override
    public DownloadedFile downloadFile(IncomingMessage msg) throws Exception {
        return apiClient.downloadFile(msg);
    }

    @Override
    public void sendReply(IncomingMessage incoming, ReplyMessage reply) throws Exception {
        apiClient.sendReply(incoming, reply);
    }

    @Override
    public String startStream(IncomingMessage incoming) throws Exception {
        return cardStream.startStream(incoming);
    }

    @Override
    public void updateStreamContent(IncomingMessage incoming, String streamId, String fullContent)
            throws Exception {
        cardStream.updateStreamContent(incoming, streamId, fullContent);
    }

    @Override
    public void finalizeStream(IncomingMessage incoming, String streamId, String finalContent)
            throws Exception {
        cardStream.finalizeStream(incoming, streamId, finalContent);
    }

    @Override
    public void endStream(IncomingMessage incoming, String streamId) throws Exception {
        cardStream.endStream(incoming, streamId);
    }

    /** 供测试观察。 */
    String apiBaseUrl() {
        return apiClient.apiBaseUrl();
    }
}
