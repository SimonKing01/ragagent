package com.ragagent.im.channel.slack;

import com.ragagent.im.domain.ImChannelEntity;
import com.ragagent.im.runtime.ImCredentials;
import com.ragagent.im.runtime.IncomingMessage;
import com.ragagent.im.service.ImTurnOrchestrator;
import java.util.Map;
import java.util.function.BiConsumer;
import org.springframework.stereotype.Component;

/**
 * Slack 渠道工厂。
 *
 * <p>凭据：{@code bot_token}（两种模式都要）+ {@code signing_secret}（webhook 验签）+
 * {@code app_token}（socket mode 握手）。模式缺省 {@code websocket}。</p>
 */
@Component
public class SlackAdapterFactory implements ImTurnOrchestrator.AdapterFactory {

    @Override
    public ImTurnOrchestrator.AdapterRegistration create(ImChannelEntity channel,
            BiConsumer<IncomingMessage, String> msgHandler) {
        Map<String, Object> creds = ImCredentials.parseCredentials(channel.getCredentials());
        String botToken = ImCredentials.getString(creds, "bot_token");
        String mode = ImCredentials.resolveMode(channel, "websocket");

        switch (mode) {
            case "webhook":
                return new ImTurnOrchestrator.AdapterRegistration(
                        new SlackAdapter(botToken, ImCredentials.getString(creds, "signing_secret")),
                        null);
            case "websocket": {
                SlackSocketModeClient client = new SlackSocketModeClient(
                        ImCredentials.getString(creds, "app_token"), channel.getId(), msgHandler);
                Thread thread = new Thread(client::start, "im-slack-ws-" + channel.getId());
                thread.setDaemon(true);
                thread.start();
                // Socket Mode 的入站走 WS，出站仍是 REST（同 webhook 的 api 面）——
                // 但验签只对 HTTP 回调有意义，故传空 secret。
                return new ImTurnOrchestrator.AdapterRegistration(new SlackAdapter(botToken, ""),
                        client::stop);
            }
            default:
                throw new IllegalArgumentException("unsupported slack mode: " + mode);
        }
    }
}
