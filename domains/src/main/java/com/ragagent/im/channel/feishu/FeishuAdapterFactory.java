package com.ragagent.im.channel.feishu;

import com.ragagent.im.domain.ImChannelEntity;
import com.ragagent.im.runtime.ImCredentials;
import com.ragagent.im.runtime.IncomingMessage;
import com.ragagent.im.service.ImTurnOrchestrator;
import com.ragagent.platform.security.SsrfGuard;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 飞书 / Lark 渠道工厂。
 *
 * <p>HTTP 适配器<b>两种模式都建</b>（websocket 模式下的 SendReply 也走它）；
 * 凭据 {@code app_id}/{@code app_secret}/{@code verification_token}/{@code encrypt_key}/
 * {@code api_base_url}（后者经 {@link FeishuAdapter} 校验：http(s) + SSRF，允许明文 http）。</p>
 *
 * <p>{@code websocket} 模式额外起 {@link FeishuLongConnClient}（协议自持实现：pbbp2 帧 +
 * 心跳 + 分片 + 同帧回执，对齐 lark 官方 SDK 的 ws 协议）。</p>
 */
public class FeishuAdapterFactory implements ImTurnOrchestrator.AdapterFactory {

    private final FeishuRegion region;
    private final SsrfGuard ssrfGuard;

    public FeishuAdapterFactory(FeishuRegion region, SsrfGuard ssrfGuard) {
        this.region = region == null ? FeishuRegion.FEISHU : region;
        this.ssrfGuard = ssrfGuard;
    }

    @Override
    public ImTurnOrchestrator.AdapterRegistration create(ImChannelEntity channel,
            BiConsumer<IncomingMessage, String> msgHandler) {
        Map<String, Object> creds = ImCredentials.parseCredentials(channel.getCredentials());

        FeishuAdapter adapter;
        try {
            adapter = new FeishuAdapter(region,
                    ImCredentials.getString(creds, "app_id"),
                    ImCredentials.getString(creds, "app_secret"),
                    ImCredentials.getString(creds, "verification_token"),
                    ImCredentials.getString(creds, "encrypt_key"),
                    ImCredentials.getString(creds, "api_base_url"),
                    ssrfGuard);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("create " + region.platform() + " adapter: "
                    + e.getMessage(), e);
        }

        String mode = ImCredentials.resolveMode(channel, "websocket");
        switch (mode) {
            case "webhook":
                return new ImTurnOrchestrator.AdapterRegistration(adapter, null);
            case "websocket": {
                // 长连接：HTTP 适配器照建（SendReply 两模式共用），额外起 WS 事件流
                FeishuLongConnClient longConn = new FeishuLongConnClient(region,
                        ImCredentials.getString(creds, "app_id"),
                        ImCredentials.getString(creds, "app_secret"),
                        ImCredentials.getString(creds, "api_base_url"),
                        ssrfGuard, channel.getId(),
                        (msg, cid) -> msgHandler.accept(msg, cid));
                Thread thread = new Thread(() -> {
                    try {
                        longConn.start();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }, "im-feishu-ws-" + channel.getId());
                thread.setDaemon(true);
                thread.start();
                // stop 必须真关 socket（SDK 级取消不生效），这里 abort + 置停止位
                return new ImTurnOrchestrator.AdapterRegistration(adapter, longConn::stop);
            }
            default:
                throw new IllegalArgumentException("unknown " + region.platform() + " mode: "
                        + mode);
        }
    }
}
