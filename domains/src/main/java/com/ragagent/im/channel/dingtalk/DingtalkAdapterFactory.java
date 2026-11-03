package com.ragagent.im.channel.dingtalk;

import com.ragagent.im.domain.ImChannelEntity;
import com.ragagent.im.runtime.ImCredentials;
import com.ragagent.im.runtime.IncomingMessage;
import com.ragagent.im.service.ImTurnOrchestrator;
import com.ragagent.platform.security.SsrfGuard;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 钉钉渠道工厂。
 *
 * <p>凭据 {@code client_id}/{@code client_secret} 必填，{@code card_template_id} 可选
 * （配了才走 AI 卡片流式，否则退回 sessionWebhook 整段回复）。</p>
 *
 * <p>HTTP 适配器<b>两种模式都建</b>（websocket 模式下的回复也走 sessionWebhook/OpenAPI）；
 * {@code websocket} 额外起 {@link DingtalkStreamClient} 消费事件，stop 句柄关连接。</p>
 */
public class DingtalkAdapterFactory implements ImTurnOrchestrator.AdapterFactory {

    private final SsrfGuard ssrfGuard;
    private final String apiBaseUrl;

    public DingtalkAdapterFactory(SsrfGuard ssrfGuard) {
        this(ssrfGuard, null);
    }

    /** {@code apiBaseUrl} 非空可指向本地 stub。 */
    public DingtalkAdapterFactory(SsrfGuard ssrfGuard, String apiBaseUrl) {
        this.ssrfGuard = ssrfGuard;
        this.apiBaseUrl = apiBaseUrl;
    }

    @Override
    public ImTurnOrchestrator.AdapterRegistration create(ImChannelEntity channel,
            BiConsumer<IncomingMessage, String> msgHandler) {
        Map<String, Object> creds = ImCredentials.parseCredentials(channel.getCredentials());

        DingtalkAdapter adapter = new DingtalkAdapter(
                ImCredentials.getString(creds, "client_id"),
                ImCredentials.getString(creds, "client_secret"),
                ImCredentials.getString(creds, "card_template_id"),
                apiBaseUrl, ssrfGuard, channel.getId());

        String mode = ImCredentials.resolveMode(channel, "websocket");
        switch (mode) {
            case "webhook":
                return new ImTurnOrchestrator.AdapterRegistration(adapter, null);
            case "websocket": {
                // Stream 模式：HTTP 适配器两种模式都建（回复同样走 sessionWebhook/OpenAPI），
                // 额外起 WS 长连接消费事件
                DingtalkStreamClient stream = new DingtalkStreamClient(
                        ImCredentials.getString(creds, "client_id"),
                        ImCredentials.getString(creds, "client_secret"),
                        apiBaseUrl, ssrfGuard, channel.getId(),
                        (msg, cid) -> msgHandler.accept(msg, cid));
                Thread thread = new Thread(stream::start, "im-dingtalk-ws-" + channel.getId());
                thread.setDaemon(true);
                thread.start();
                return new ImTurnOrchestrator.AdapterRegistration(adapter, stream::stop);
            }
            default:
                throw new IllegalArgumentException("unsupported dingtalk mode: " + mode);
        }
    }
}
