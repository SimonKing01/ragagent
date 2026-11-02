package com.ragagent.im.channel.dingtalk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.im.runtime.ImTypes;
import com.ragagent.im.runtime.IncomingMessage;
import com.ragagent.platform.web.JsonMappers;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 钉钉 AI 卡片流：建卡、节流更新、定稿、结束。
 *
 * <p>流表是进程级的（跨 adapter 重建仍在），但 key 带渠道维度：
 * {@code dt:<channelId>:<userId>:<messageId>}。只带 userId 时，同一钉钉企业的
 * staffId 在不同租户/渠道下可能相同；加上渠道行 id 后互不覆盖。</p>
 *
 * <p>没有卡片模板时不发任何卡片请求，内容只在本地累积，{@code endStream} 时整段
 * 退回 sessionWebhook / OpenAPI。</p>
 */
final class DingtalkCardStream {

    private static final Logger log = LoggerFactory.getLogger(DingtalkCardStream.class);
    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    /** 连续卡片更新的最小间隔。 */
    static final long MIN_CARD_UPDATE_INTERVAL_MS = 500;
    static final long STREAM_ORPHAN_TTL_MS = 5 * 60 * 1000L;

    /** 全局流表，key = 带渠道维度的 stream ID。 */
    static final Map<String, StreamState> STREAMS = new ConcurrentHashMap<>();

    /** 一条流的状态。 */
    static final class StreamState {
        final long createdAt = System.currentTimeMillis();
        final Object lock = new Object();
        final StringBuilder content = new StringBuilder();
        String sessionWebhook = "";
        String outTrackId = "";
        long lastUpdate;
    }

    private final DingtalkApiClient api;
    private final String clientId;
    private final String cardTemplateId;
    private final String channelKey;

    DingtalkCardStream(DingtalkApiClient api, String clientId, String cardTemplateId,
                       String channelKey) {
        this.api = api;
        this.clientId = clientId == null ? "" : clientId;
        this.cardTemplateId = cardTemplateId == null ? "" : cardTemplateId;
        this.channelKey = channelKey == null ? "" : channelKey;
    }

    String startStream(IncomingMessage incoming) throws Exception {
        purgeOrphans();
        String sessionWebhook = incoming.extra == null ? ""
                : incoming.extra.getOrDefault("session_webhook", "");
        String streamId = streamId(incoming);

        StreamState state = new StreamState();
        state.sessionWebhook = sessionWebhook;

        // 配了卡片模板才建 AI 卡片；建卡失败退回 sessionWebhook 整段回复（只警告）
        if (!cardTemplateId.isEmpty()) {
            try {
                state.outTrackId = createAndDeliverCard(incoming);
            } catch (Exception e) {
                log.warn("[DingTalk] Failed to create AI card, falling back to sessionWebhook", e);
            }
        }
        STREAMS.put(streamId, state);
        log.info("[DingTalk] Streaming started: stream_id={}, card={}",
                streamId, !state.outTrackId.isEmpty());
        return streamId;
    }

    void updateStreamContent(IncomingMessage incoming, String streamId, String fullContent)
            throws Exception {
        if (fullContent == null || fullContent.isEmpty()) {
            return;
        }
        StreamState state = STREAMS.get(streamId);
        if (state == null) {
            throw new IllegalStateException("unknown stream ID: " + streamId);
        }
        synchronized (state.lock) {
            state.content.setLength(0);
            state.content.append(fullContent);
            if (state.outTrackId.isEmpty()) {
                return;
            }
            // 节流：距上次卡片更新不足 500ms 就只更新本地累积
            if (System.currentTimeMillis() - state.lastUpdate < MIN_CARD_UPDATE_INTERVAL_MS) {
                return;
            }
            state.lastUpdate = System.currentTimeMillis();
        }
        try {
            streamingUpdateCard(state.outTrackId, fullContent, false);
        } catch (Exception e) {
            log.warn("[DingTalk] Failed to update card stream", e);
        }
    }

    void finalizeStream(IncomingMessage incoming, String streamId, String finalContent)
            throws Exception {
        StreamState state = STREAMS.get(streamId);
        if (state == null) {
            throw new IllegalStateException("unknown stream ID: " + streamId);
        }
        String outTrackId;
        synchronized (state.lock) {
            state.content.setLength(0);
            state.content.append(finalContent == null ? "" : finalContent);
            outTrackId = state.outTrackId;
        }
        if (!outTrackId.isEmpty()) {
            try {
                streamingUpdateCard(outTrackId, finalContent == null ? "" : finalContent, false);
            } catch (Exception e) {
                log.warn("[DingTalk] Failed to finalize card stream", e);
            }
        }
    }

    void endStream(IncomingMessage incoming, String streamId) throws Exception {
        StreamState state = STREAMS.remove(streamId);
        if (state == null) {
            return;
        }
        String fullContent;
        String outTrackId;
        String sessionWebhook;
        synchronized (state.lock) {
            fullContent = state.content.toString();
            outTrackId = state.outTrackId;
            sessionWebhook = state.sessionWebhook;
        }

        // 有卡片 → 定稿卡片；否则退回 sessionWebhook 整段发；再没有就走 OpenAPI（都只警告）
        try {
            if (!outTrackId.isEmpty()) {
                streamingUpdateCard(outTrackId, fullContent, true);
            } else if (!sessionWebhook.isEmpty()) {
                api.replyViaSessionWebhook(sessionWebhook, fullContent);
            } else {
                api.replyViaOpenApi(incoming, fullContent);
            }
        } catch (Exception e) {
            log.warn("[DingTalk] Failed to end stream", e);
        }
        log.info("[DingTalk] Streaming ended: stream_id={}", streamId);
    }

    private String streamId(IncomingMessage incoming) {
        String user = incoming.userId == null ? "" : incoming.userId;
        String message = incoming.messageId == null ? "" : incoming.messageId;
        return channelKey.isEmpty()
                ? "dt:" + user + ":" + message
                : "dt:" + channelKey + ":" + user + ":" + message;
    }

    /** outTrackId 即流 ID 之外的卡片句柄。 */
    private String createAndDeliverCard(IncomingMessage incoming) throws Exception {
        String outTrackId = UUID.randomUUID().toString();

        ObjectNode cardParamMap = MAPPER.createObjectNode();
        cardParamMap.put("content", "");
        ObjectNode cardData = MAPPER.createObjectNode();
        cardData.set("cardParamMap", cardParamMap);

        ObjectNode body = MAPPER.createObjectNode();
        body.put("cardTemplateId", cardTemplateId);
        body.put("outTrackId", outTrackId);
        body.put("callbackType", "STREAM");
        body.set("cardData", cardData);
        body.put("userIdType", 1);

        if (ImTypes.CHAT_TYPE_GROUP.equals(incoming.chatType)) {
            body.put("openSpaceId", "dtv1.card//IM_GROUP."
                    + (incoming.chatId == null ? "" : incoming.chatId));
            ObjectNode spaceModel = MAPPER.createObjectNode();
            spaceModel.put("supportForward", true);
            body.set("imGroupOpenSpaceModel", spaceModel);
            ObjectNode deliverModel = MAPPER.createObjectNode();
            deliverModel.put("robotCode", clientId);
            deliverModel.set("extension", MAPPER.createObjectNode());
            body.set("imGroupOpenDeliverModel", deliverModel);
        } else {
            body.put("openSpaceId", "dtv1.card//IM_ROBOT."
                    + (incoming.userId == null ? "" : incoming.userId));
            ObjectNode spaceModel = MAPPER.createObjectNode();
            spaceModel.put("supportForward", true);
            body.set("imRobotOpenSpaceModel", spaceModel);
            ObjectNode deliverModel = MAPPER.createObjectNode();
            deliverModel.put("robotCode", clientId);
            deliverModel.put("spaceType", "IM_ROBOT");
            deliverModel.set("extension", MAPPER.createObjectNode());
            body.set("imRobotOpenDeliverModel", deliverModel);
        }

        api.dingtalkApi("POST", "/v1.0/card/instances/createAndDeliver", body);
        return outTrackId;
    }

    /** PUT /v1.0/card/streaming（isFull 恒 true）。 */
    private void streamingUpdateCard(String outTrackId, String content, boolean isFinalize)
            throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("outTrackId", outTrackId);
        body.put("guid", UUID.randomUUID().toString());
        body.put("key", "content");
        body.put("content", content);
        body.put("isFull", true);
        body.put("isFinalize", isFinalize);
        body.put("isError", false);
        api.dingtalkApi("PUT", "/v1.0/card/streaming", body);
    }

    /** 惰性回收：清掉超过 TTL 的孤儿流。 */
    static void purgeOrphans() {
        long cutoff = System.currentTimeMillis() - STREAM_ORPHAN_TTL_MS;
        STREAMS.entrySet().removeIf(e -> e.getValue().createdAt < cutoff);
    }
}
