package com.ragagent.im.channel.telegram;

import com.ragagent.im.domain.ImChannelEntity;
import com.ragagent.im.runtime.ImCredentials;
import com.ragagent.im.runtime.IncomingMessage;
import com.ragagent.im.service.ImTurnOrchestrator;
import java.util.Map;
import java.util.function.BiConsumer;
import org.springframework.stereotype.Component;

/**
 * Telegram 渠道工厂。
 *
 * <p>凭据取 {@code bot_token}（+ webhook 模式下的 {@code secret_token}），
 * 模式由 {@code channel.mode} 决定、缺省 {@code websocket}（长轮询）：</p>
 * <ul>
 *   <li>{@code webhook} → 只出站（回调由 HTTP 面驱动），stop 为 null；</li>
 *   <li>{@code websocket} → 起守护线程跑 {@link TelegramLongPollingClient}，
 *       stop = 停轮询。</li>
 * </ul>
 */
@Component
public class TelegramAdapterFactory implements ImTurnOrchestrator.AdapterFactory {

    @Override
    public ImTurnOrchestrator.AdapterRegistration create(ImChannelEntity channel,
            BiConsumer<IncomingMessage, String> msgHandler) {
        Map<String, Object> creds = ImCredentials.parseCredentials(channel.getCredentials());
        String botToken = ImCredentials.getString(creds, "bot_token");
        String mode = ImCredentials.resolveMode(channel, "websocket");

        switch (mode) {
            case "webhook": {
                String secretToken = ImCredentials.getString(creds, "secret_token");
                return new ImTurnOrchestrator.AdapterRegistration(
                        new TelegramAdapter(botToken, secretToken), null);
            }
            case "websocket": {
                TelegramLongPollingClient client =
                        new TelegramLongPollingClient(botToken, channel.getId(), msgHandler);
                Thread thread = new Thread(client::start, "im-telegram-poll-" + channel.getId());
                thread.setDaemon(true);
                thread.start();
                return new ImTurnOrchestrator.AdapterRegistration(
                        new TelegramAdapter(botToken, ""), client::stop);
            }
            default:
                throw new IllegalArgumentException("unsupported telegram mode: " + mode);
        }
    }
}
