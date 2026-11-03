package com.ragagent.im.channel.wecom;

import com.ragagent.im.domain.ImChannelEntity;
import com.ragagent.im.runtime.ImCredentials;
import com.ragagent.im.runtime.IncomingMessage;
import com.ragagent.im.service.ImTurnOrchestrator;
import com.ragagent.platform.security.SsrfGuard;
import java.util.Map;
import java.util.function.BiConsumer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 企业微信渠道工厂。
 *
 * <p>凭据：webhook 模式 {@code corp_id}/{@code agent_secret}/{@code token}/
 * {@code encoding_aes_key}/{@code corp_agent_id}/{@code api_base_url}；
 * websocket（智能机器人长连接）取 {@code bot_id}/{@code bot_secret}/
 * {@code ws_endpoint}/{@code bot_name}。</p>
 */
@Component
public class WecomAdapterFactory implements ImTurnOrchestrator.AdapterFactory {

    private final SsrfGuard ssrfGuard;

    @Autowired
    public WecomAdapterFactory(ObjectProvider<SsrfGuard> ssrfGuard) {
        this(ssrfGuard.getIfAvailable());
    }

    /** 测试用直传构造。 */
    WecomAdapterFactory(SsrfGuard ssrfGuard) {
        this.ssrfGuard = ssrfGuard;
    }

    @Override
    public ImTurnOrchestrator.AdapterRegistration create(ImChannelEntity channel,
            BiConsumer<IncomingMessage, String> msgHandler) {
        Map<String, Object> creds = ImCredentials.parseCredentials(channel.getCredentials());
        String mode = ImCredentials.resolveMode(channel, "websocket");

        switch (mode) {
            case "webhook": {
                int corpAgentId = intOf(creds.get("corp_agent_id"));
                return new ImTurnOrchestrator.AdapterRegistration(new WecomWebhookAdapter(
                        ImCredentials.getString(creds, "corp_id"),
                        ImCredentials.getString(creds, "agent_secret"),
                        ImCredentials.getString(creds, "token"),
                        ImCredentials.getString(creds, "encoding_aes_key"),
                        corpAgentId,
                        ImCredentials.getString(creds, "api_base_url"),
                        ssrfGuard), null);
            }
            case "websocket": {
                WecomLongConnClient client = new WecomLongConnClient(
                        ImCredentials.getString(creds, "bot_id"),
                        ImCredentials.getString(creds, "bot_secret"),
                        ImCredentials.getString(creds, "ws_endpoint"),
                        ImCredentials.getString(creds, "bot_name"),
                        channel.getId(),
                        msgHandler,
                        ssrfGuard);
                Thread thread = new Thread(client::start, "im-wecom-ws-" + channel.getId());
                thread.setDaemon(true);
                thread.start();
                // 先关 socket 再给取消信号（才能同步停投递）
                return new ImTurnOrchestrator.AdapterRegistration(
                        new WecomWSAdapter(client, ssrfGuard), client::stop);
            }
            default:
                throw new IllegalArgumentException("unknown WeCom mode: " + mode);
        }
    }

    /** 数字或数字字符串两形态取值。 */
    static int intOf(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }
}
