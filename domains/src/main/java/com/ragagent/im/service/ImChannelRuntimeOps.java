package com.ragagent.im.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.im.domain.ImChannelEntity;
import com.ragagent.im.repository.ImChannelRepository;
import com.ragagent.im.runtime.AdapterInterfaces.Adapter;
import com.ragagent.im.runtime.ImRedisKeys;
import com.ragagent.im.runtime.ImRedisStore;
import com.ragagent.im.runtime.IncomingMessage;
import com.ragagent.im.runtime.QaQueue;
import com.ragagent.platform.redis.RedisPubSub;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 渠道运行时：adapter 工厂注册表、渠道生命周期（起停 / 重载 / 按库重建 / 配置广播），
 * 以及 WS 长连接选主（leader 续期、抢锁重试、释放）。
 *
 * <p>自 {@link ImTurnOrchestrator} 整块外提（逐字搬迁）。这 17 个方法是一条
 * 完整链路：**选主决定哪台实例跑哪些渠道**，渠道起停又要读写 leader 锁——本就是同一关注点，
 * 与消息入口、停止链路、知识桥无关。整块在门面里本来就是连续的，故搬迁几乎零文本改动。</p>
 *
 * <p>依赖面：门面的 {@code channels}（mapper）/ {@code channelStates}（运行中渠道表，消息路径也在读）
 * / {@code qaQueue} 三项**共享引用**注入（同名，故块内代码无需改写）；{@code instanceId}、
 * {@code adapterFactories}、leader 线程表、{@code shuttingDown} 与三个 LEADER_* 常量**随迁**
 * （门面不再持有）。消息回流走构造期注入的回调（门面传 {@code this::handleMessage}）——
 * 这是本簇唯一的出向依赖，且它是适配器工厂本来就需要的 msgHandler 语义。</p>
 */
final class ImChannelRuntimeOps {

    private static final Logger log = LoggerFactory.getLogger(ImChannelRuntimeOps.class);

    /** WS 长连接 leader 锁的 TTL。 */
    private static final int LEADER_TTL_SECONDS = 15;
    /** leader 续期间隔。 */
    private static final long LEADER_RENEW_MILLIS = 5_000L;
    /** 非 leader 的抢锁重试间隔。 */
    private static final long LEADER_RETRY_MILLIS = 10_000L;

    private final ImChannelRepository channels;
    private final Map<String, ImTurnOrchestrator.ChannelState> channelStates;
    private final QaQueue qaQueue;
    private final ImRedisStore redisStore;
    private final RedisPubSub redisPubSub;
    /** 消息回流：适配器工厂拿到的 msgHandler 即门面的消息入口。 */
    private final BiConsumer<IncomingMessage, String> msgHandler;

    // ── 随迁字段（自门面外提；门面不再持有） ───────────────────────────────
    /** 渠道启动时必须注册的平台工厂。 */
    private final Map<String, ImTurnOrchestrator.AdapterFactory> adapterFactories = new ConcurrentHashMap<>();
    /** 实例标识：leader 锁的值 + 广播事件源过滤（随机 UUID）。 */
    private final String instanceId = UUID.randomUUID().toString();
    /** leader 续期线程（channelId → 线程）；停止渠道时中断。 */
    private final Map<String, Thread> leaderRenewThreads = new ConcurrentHashMap<>();
    /** 非 leader 的抢锁重试线程（channelId → 线程）；防重复的关键。 */
    private final Map<String, Thread> leaderRetryThreads = new ConcurrentHashMap<>();
    /** 订阅循环与重试调度的退出标志（停止时置位）。 */
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    ImChannelRuntimeOps(ImChannelRepository channels, Map<String, ImTurnOrchestrator.ChannelState> channelStates,
            QaQueue qaQueue, ImRedisStore redisStore, RedisPubSub redisPubSub,
            BiConsumer<IncomingMessage, String> msgHandler) {
        this.channels = channels;
        this.channelStates = channelStates;
        this.qaQueue = qaQueue;
        this.redisStore = redisStore;
        this.redisPubSub = redisPubSub;
        this.msgHandler = msgHandler;
    }

    /** 注册平台工厂。 */
    public void registerAdapterFactory(String platform, ImTurnOrchestrator.AdapterFactory factory) {
        adapterFactories.put(platform, factory);
    }

    /** 渠道行 → 就绪的适配器；运行态缺失则先尝试启动。 */
    public Adapter adapterFor(ImChannelEntity channel) {
        if (channel == null || !channel.isEnabled()) {
            return null;
        }
        ImTurnOrchestrator.AdapterFactory factory = adapterFactories.get(channel.getPlatform());
        if (factory == null) {
            return null;
        }
        ImTurnOrchestrator.ChannelState state = channelStates.get(channel.getId());
        if (state == null) {
            startChannel(channel);
            state = channelStates.get(channel.getId());
        }
        return state == null ? null : state.adapter();
    }

    /** 启动渠道：经工厂建适配器并进入运行态（独占长连接先做跨实例选主）。 */
    public synchronized void startChannel(ImChannelEntity channel) {
        ImTurnOrchestrator.AdapterFactory factory = adapterFactories.get(channel.getPlatform());
        if (factory == null) {
            log.warn("[IM] no adapter factory for platform {} (channel {})",
                    channel.getPlatform(), channel.getId());
            return;
        }
        // 独占长连接（websocket 模式）：多实例下只许一个实例持有连接（跨实例选主）
        boolean leaderHeld = false;
        if (redisStore != null && isExclusiveChannel(channel)) {
            if (!redisStore.tryAcquireLeader(leaderKey(channel.getId()), instanceId,
                    LEADER_TTL_SECONDS)) {
                log.info("[IM] Channel {} owned by another instance, will retry", channel.getId());
                scheduleLeaderRetry(channel.getId());
                return;
            }
            leaderHeld = true;
        }
        AtomicReference<Runnable> stopRef = new AtomicReference<>();
        ImTurnOrchestrator.AdapterRegistration reg;
        try {
            reg = factory.create(channel, msgHandler);
        } catch (RuntimeException e) {
            // 工厂失败（凭据不全 / 出站校验不过 / 平台未实现该模式）时渠道起不来，
            // 适配器不入运行态——回调路径因此走 "adapter not active"
            // （503 "channel not available"），而不是把异常冒成 500。
            log.warn("[IM] Channel start failed: id={} platform={} mode={}", channel.getId(), channel.getPlatform(), channel.getMode(), e);
            if (leaderHeld) {
                releaseLeader(channel.getId()); // 启动失败回滚选主
            }
            return;
        }
        stopRef.set(reg.stop());
        channelStates.put(channel.getId(), new ImTurnOrchestrator.ChannelState(channel, reg.adapter(), stopRef));
        log.info("[IM] Channel started: id={} platform={} mode={}", channel.getId(),
                channel.getPlatform(), channel.getMode());
        if (leaderHeld) {
            startLeaderRenewLoop(channel.getId());
        }
    }

    /** 停止渠道并拆除适配器（含 leader 线程与选主锁）。 */
    public synchronized void stopChannel(String channelId) {
        stopLeaderWatch(channelId);
        ImTurnOrchestrator.ChannelState cs = channelStates.remove(channelId);
        if (cs != null && cs.adapterStop() != null && cs.adapterStop().get() != null) {
            cs.adapterStop().get().run();
        }
        releaseLeader(channelId);
    }

    /**
     * 软删该 agent
     * 的全部 IM 渠道并停止运行中的适配器——概览列表与运行中的适配器不得比 agent
     * 活得更久（自定义 agent 删除时调用）。
     *
     * <p>逐条广播给其他实例（{@code publishChannelConfigChange}）。</p>
     */
    public void deleteChannelsByAgent(String agentId, long tenantId) {
        java.util.List<ImChannelEntity> found = channels.listByAgent(agentId, tenantId);
        if (found.isEmpty()) {
            return;
        }
        java.time.OffsetDateTime now = java.time.OffsetDateTime.now();
        for (ImChannelEntity ch : found) {
            channels.softDelete(ch.getId(), tenantId, now);
            stopChannel(ch.getId());
            publishChannelConfigChange(ch.getId());
        }
    }

    /** 启动时拉起全部 enabled 渠道。 */
    public void loadAndStartChannels() {
        for (ImChannelEntity ch : channels.listEnabled()) {
            startChannel(ch);
        }
    }

    /**
 * 应用就绪后从库拉起全部 enabled 渠道（否则重启后渠道全部沉默）。失败只 WARN，不阻塞启动。
 *
 * <p><b>本类不是 Spring bean</b>（由门面 `new` 出来）⇒ 触发入口（{@code @EventListener} /
 * {@code @PreDestroy}）**必须挂在门面** {@link ImTurnOrchestrator} 上，这里只保留实现。外提时
 * 曾把注解一起搬进来 ⇒ 注解静默失效（渠道不再自动启动、停机不再清理），已修回并加
 * ArchUnit 守卫这类问题。</p>
 */
    public void startChannelsOnReady() {
        try {
            loadAndStartChannels();
        } catch (RuntimeException e) {
            log.warn("[IM] Failed to load channels from database", e);
        }
    }

    /**
     * 停机时停 QA 队列、全部运行中的渠道适配器与后台线程。
     *
     * <p>触发入口是门面 {@link ImTurnOrchestrator#stop()} 上的 {@code @PreDestroy}（本类非 Spring bean）。</p>
     */
    public void stop() {
        shuttingDown.set(true);
        try {
            qaQueue.stop();
        } catch (RuntimeException e) {
            log.warn("[IM] qa queue stop failed", e);
        }
        for (String id : new java.util.ArrayList<>(channelStates.keySet())) {
            try {
                stopChannel(id);
            } catch (RuntimeException e) {
                log.warn("[IM] channel {} stop failed", id, e);
            }
        }
        for (Thread t : leaderRetryThreads.values()) {
            t.interrupt();
        }
    }

    // ── 渠道配置广播 + WS 长连接选主（多实例面） ─────────────────────────────

    /**
     * 渠道行变更后的运行时同步（本实例按库重建 + 跨实例广播）；
     * 由渠道 CRUD 与订阅回调共用。
     */
    public void onChannelChanged(String channelId) {
        reloadChannelFromDb(channelId);
        publishChannelConfigChange(channelId);
    }

    /**
     * 按库重建渠道运行时：删除/禁用 → 停；启用的独占长连接（websocket）→
     * 停旧启新（长连接必须主动建立）；webhook 型保持惰性启动（回调到达时才建适配器）。
     */
    private void reloadChannelFromDb(String channelId) {
        ImChannelEntity fresh = channels.getById(channelId);
        if (fresh == null || !fresh.isEnabled()) {
            stopChannel(channelId);
            return;
        }
        if (redisStore != null && isExclusiveChannel(fresh)) {
            stopChannel(channelId);
            startChannel(fresh);
        }
    }

    /** 独占长连接渠道：websocket 模式（多实例下只许一个实例持有连接）。 */
    static boolean isExclusiveChannel(ImChannelEntity channel) {
        return "websocket".equals(channel.getMode());
    }

    /** 广播渠道配置变更（载荷键为部署面契约：channel_id/source_instance；未接入 Redis 时为空操作）。 */
    void publishChannelConfigChange(String channelId) {
        RedisPubSub pubSub = redisPubSub;
        if (pubSub == null || channelId == null || channelId.isEmpty()) {
            return;
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("channel_id", channelId);
        event.put("source_instance", instanceId);
        try {
            pubSub.publish(ImRedisKeys.CHANNEL_CONFIG_CHANNEL, ImTurnOrchestrator.JSON.writeValueAsString(event));
        } catch (Exception e) {
            // DB 是权威；事件只是加速（发布失败只 WARN）
            log.warn("[IM] Publish channel config event failed for {}", channelId, e);
        }
    }

    /** 订阅其他实例的渠道配置事件；DB 权威（CRUD 与 leader 续期的查库）是事件丢失的回退。 */
    void startChannelConfigSubscriber() {
        Thread.ofVirtual().name("im-channel-config-subscriber").start(() -> {
            RedisPubSub.Subscription sub = null;
            try {
                sub = redisPubSub.subscribe(ImRedisKeys.CHANNEL_CONFIG_CHANNEL);
                if (!sub.awaitSubscribed(Duration.ofSeconds(5))) {
                    log.warn("[IM] Channel config subscriber failed to subscribe in time");
                    return;
                }
                while (!shuttingDown.get()) {
                    String payload = sub.receiveMessage(Duration.ofSeconds(1));
                    if (payload == null || payload.isEmpty()) {
                        continue;
                    }
                    JsonNode event;
                    try {
                        event = ImTurnOrchestrator.JSON.readTree(payload);
                    } catch (Exception e) {
                        log.debug("[IM] Ignore invalid channel config event", e);
                        continue;
                    }
                    String channelId = event.path("channel_id").asText("");
                    String source = event.path("source_instance").asText("");
                    if (channelId.isEmpty() || instanceId.equals(source)) {
                        continue;
                    }
                    reloadChannelFromDb(channelId);
                }
            } catch (RuntimeException e) {
                log.warn("[IM] Channel config subscriber stopped", e);
            } finally {
                if (sub != null) {
                    sub.close();
                }
            }
        });
    }

    private static String leaderKey(String channelId) {
        return ImRedisKeys.LEADER_PREFIX + channelId;
    }

    /** 续期线程：失锁或渠道被删/禁用 → 停本实例运行时；失锁再进重试队列。 */
    private void startLeaderRenewLoop(String channelId) {
        Thread old = leaderRenewThreads.remove(channelId);
        if (old != null) {
            old.interrupt();
        }
        Thread t = Thread.ofVirtual().name("im-leader-renew-" + channelId).start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(LEADER_RENEW_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (!redisStore.renewLeader(leaderKey(channelId), instanceId, LEADER_TTL_SECONDS)) {
                    log.warn("[IM] Lost leadership for channel {}, stopping adapter", channelId);
                    stopChannel(channelId);
                    scheduleLeaderRetry(channelId);
                    return;
                }
                ImChannelEntity fresh = channels.getById(channelId);
                if (fresh == null || !fresh.isEnabled()) {
                    log.info("[IM] Channel {} deleted/disabled; leader stepping down", channelId);
                    stopChannel(channelId);
                    return;
                }
            }
        });
        leaderRenewThreads.put(channelId, t);
    }

    /** 非 leader 的抢锁重试：每 10s 重新读库 + 抢锁（per-channel 单线程，防重复）。 */
    private void scheduleLeaderRetry(String channelId) {
        if (shuttingDown.get() || leaderRetryThreads.containsKey(channelId)) {
            return;
        }
        Thread t = Thread.ofVirtual().name("im-leader-retry-" + channelId).start(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Thread.sleep(LEADER_RETRY_MILLIS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    ImChannelEntity fresh = channels.getById(channelId);
                    if (fresh == null || !fresh.isEnabled()) {
                        return;
                    }
                    if (redisStore.tryAcquireLeader(leaderKey(channelId), instanceId,
                            LEADER_TTL_SECONDS)) {
                        startChannel(fresh);
                        return;
                    }
                }
            } finally {
                leaderRetryThreads.remove(channelId);
            }
        });
        leaderRetryThreads.put(channelId, t);
    }

    /** 停 leader 的 renew/retry 线程（stopChannel 与 PreDestroy 用）。 */
    private void stopLeaderWatch(String channelId) {
        Thread renew = leaderRenewThreads.remove(channelId);
        if (renew != null) {
            renew.interrupt();
        }
        Thread retry = leaderRetryThreads.remove(channelId);
        if (retry != null) {
            retry.interrupt();
        }
    }

    /** 释放 leader 锁（仅当本实例持有；Redis 未接入为空操作）。 */
    private void releaseLeader(String channelId) {
        if (redisStore != null) {
            redisStore.releaseLeader(leaderKey(channelId), instanceId);
        }
    }
}
