package com.ragagent.im.channel.qqbot;

import com.ragagent.im.domain.ImChannelEntity;
import com.ragagent.im.runtime.ImCredentials;
import com.ragagent.im.runtime.IncomingMessage;
import com.ragagent.im.service.ImTurnOrchestrator;
import com.ragagent.platform.security.SsrfGuard;
import java.util.Map;
import java.util.function.BiConsumer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * QQ 机器人渠道工厂。
 *
 * <p><b>只支持 websocket</b>（长连接，非 websocket 即报错）；凭据
 * {@code app_id}/{@code client_secret}/{@code api_base_url}/
 * {@code gateway_url}，其中后两者经 {@link QqBotClient} 的 SSRF 白名单校验。</p>
 */
@Component
public class QqBotAdapterFactory implements ImTurnOrchestrator.AdapterFactory {

    private final SsrfGuard ssrfGuard;

    /**
     * Spring 装配面（生产）：SSRF 守卫是可选 bean。
     * 显式 {@code @Autowired}——类里还有一个测试用的包内构造，不标注时 Spring 挑错构造
     * （实测 NoSuchMethodException）。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public QqBotAdapterFactory(ObjectProvider<SsrfGuard> ssrfGuard) {
        this(ssrfGuard.getIfAvailable());
    }

    /** 直传守卫（测试用；生产走上面的 ObjectProvider 面）。 */
    QqBotAdapterFactory(SsrfGuard ssrfGuard) {
        this.ssrfGuard = ssrfGuard;
    }

    @Override
    public ImTurnOrchestrator.AdapterRegistration create(ImChannelEntity channel,
            BiConsumer<IncomingMessage, String> msgHandler) {
        Map<String, Object> creds = ImCredentials.parseCredentials(channel.getCredentials());

        String mode = ImCredentials.resolveMode(channel, "websocket");
        if (!"websocket".equals(mode)) {
            throw new IllegalArgumentException(
                    "unsupported qqbot mode: " + mode + " (only websocket is supported)");
        }

        QqBotClient client = new QqBotClient(
                ImCredentials.getString(creds, "app_id"),
                ImCredentials.getString(creds, "client_secret"),
                ImCredentials.getString(creds, "api_base_url"),
                ImCredentials.getString(creds, "gateway_url"),
                ssrfGuard);

        QqBotGatewayClient gateway = new QqBotGatewayClient(client, channel.getId(), msgHandler);
        Thread thread = new Thread(gateway::start, "im-qqbot-ws-" + channel.getId());
        thread.setDaemon(true);
        thread.start();
        return new ImTurnOrchestrator.AdapterRegistration(new QqBotAdapter(client), gateway::stop);
    }
}
