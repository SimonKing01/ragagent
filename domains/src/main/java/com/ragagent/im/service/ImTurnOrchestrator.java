package com.ragagent.im.service;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.contract.agent.AgentChannelCleaner;
import com.ragagent.contract.agent.AgentLookup;
import com.ragagent.contract.doc.DocReaderParsePort;
import com.ragagent.contract.event.EventBusFactory;
import com.ragagent.contract.knowledge.KnowledgeCatalogPort;
import com.ragagent.contract.session.SessionQaPort;
import com.ragagent.contract.session.SessionView;
import com.ragagent.contract.storage.support.Resolver;
import com.ragagent.im.domain.ChannelSessionEntity;
import com.ragagent.im.domain.ImChannelEntity;
import com.ragagent.im.repository.ChannelSessionRepository;
import com.ragagent.im.repository.ImChannelRepository;
import com.ragagent.im.runtime.AdapterInterfaces.Adapter;
import com.ragagent.im.runtime.AdapterInterfaces.StreamSender;
import com.ragagent.im.runtime.Commands;
import com.ragagent.im.runtime.Commands.CommandContext;
import com.ragagent.im.runtime.Commands.CommandRegistry;
import com.ragagent.im.runtime.Commands.CommandResult;
import com.ragagent.im.runtime.ImCommandSet;
import com.ragagent.im.runtime.ImFormat;
import com.ragagent.im.runtime.ImRedisRuntime;
import com.ragagent.im.runtime.ImRedisStore;
import com.ragagent.im.runtime.ImTypes;
import com.ragagent.im.runtime.IncomingMessage;
import com.ragagent.im.runtime.QaQueue;
import com.ragagent.im.runtime.ReplyMessage;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.platform.access.TenantContextSnapshot;
import com.ragagent.platform.access.TenantScope;
import com.ragagent.platform.redis.RedisPubSub;
import com.ragagent.platform.stream.StreamManager;
import com.ragagent.platform.web.JsonMappers;
import jakarta.annotation.PreDestroy;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * IM 执行体核心。
 *
 * <h2>Redis 面（内存形态先行）</h2>
 * 以下能力当前为进程内实现，多实例部署换成 Redis 实现即可
 * （键名常量届时随 Redis 实现一并引入）：
 * <ul>
 *   <li>去重：进程内 map + TTL 清理；</li>
 *   <li>WS leader 选举：单实例恒 leader——不做 Redis SETNX 竞选；</li>
 *   <li>跨实例 /stop 标记与 inflight 映射：进程内 map；</li>
 *   <li>渠道配置 pub/sub：本进程内直接失效。</li>
 * </ul>
 */
@Service
public class ImTurnOrchestrator implements AgentChannelCleaner {

    private static final Logger log = LoggerFactory.getLogger(ImTurnOrchestrator.class);
    static final ObjectMapper JSON = JsonMappers.lenient();


    final ImChannelRepository channels;
    final ChannelSessionRepository channelSessions;
    final SessionQaPort sessionQaPort;
    final AgentLookup agentLookup;
    final Resolver storageResolver;

    // ── 调谐参数 ─────────────────────────────────────────────────────────

    final ImQaRunner qaRunner;
    final ImStreamPipeline streamPipeline;
    final ImOutboundFormatter outboundFormatter;
    final ImSessionResolver sessionResolver;
    final ImQaRequests qaRequests;
    final ImAttachmentPreparer attachmentPreparer;
    /** 跨实例 /stop 全链路（自本类外提；与门面共享 inflight 表）。 */
    final ImStopOps stopOps;
    /** 消息入口闸门：去重与限流（自本类外提；两张进程内回落表随之搬家）。 */
    final ImInboundGuardOps inboundGuard;
    /** IM ↔ 知识库域接面：命令的 KB/检索读取 + 附件异步入库（自本类外提）。 */
    final ImKnowledgeBridgeOps knowledgeBridge;
    /** 渠道运行时：adapter 注册表 + 生命周期 + 选主/配置广播（自本类外提）。 */
    final ImChannelRuntimeOps channelRuntime;

    private final CommandRegistry cmdRegistry = new CommandRegistry();
    private final QaQueue qaQueue;

    /** 运行中的渠道（channelId → 状态）。 */
    private final Map<String, ChannelState> channelStates = new ConcurrentHashMap<>();
    /** 在途请求（/stop 的本地取消面）。 */
    final Map<String, InflightEntry> inflight = new ConcurrentHashMap<>();

    /** 运行中的渠道状态。 */
    record ChannelState(ImChannelEntity channel, Adapter adapter,
            AtomicReference<Runnable> adapterStop) {
    }

    static final class InflightEntry {
        /** 队列层请求（/stop 取消与引擎取消探针共享同一取消标志）。 */
        final QaQueue.QaRequest queueReq;
        final java.util.function.BooleanSupplier cancel;
        volatile String sessionId = "";
        volatile String assistantMessageId = "";

        InflightEntry(QaQueue.QaRequest queueReq, java.util.function.BooleanSupplier cancel) {
            this.queueReq = queueReq;
            this.cancel = cancel;
        }
    }

    public ImTurnOrchestrator(ImChannelRepository channels, ChannelSessionRepository channelSessions,
            SessionQaPort sessionQaPort,
            EventBusFactory eventBusFactory,
            AgentLookup agentLookup,
            ObjectProvider<Resolver> storageResolver,
            ImRedisRuntime redisRuntime,
            ObjectProvider<DocReaderParsePort> docReaders,
            ObjectProvider<KnowledgeCatalogPort> knowledgeServices,
            @Value("${im.workers:5}") int workers,
            @Value("${im.max-queue:50}") int maxQueue,
            @Value("${im.max-per-user:3}") int maxPerUser,
            @Value("${im.global-max-workers:0}") int globalMaxWorkers,
            @Value("${im.rate-limit-window-sec:60}") int rateLimitWindowSec,
            @Value("${im.rate-limit-max:10}") int rateLimitMax) {
        this.channels = channels;
        this.channelSessions = channelSessions;
        this.sessionQaPort = sessionQaPort;
        this.agentLookup = agentLookup;
        this.storageResolver = storageResolver.getIfAvailable();
        this.streamPipeline = new ImStreamPipeline(this, eventBusFactory);
        this.outboundFormatter = new ImOutboundFormatter(this);
        this.sessionResolver = new ImSessionResolver(this);
        this.qaRequests = new ImQaRequests(this);
        this.qaRunner = new ImQaRunner(this, eventBusFactory);
        this.attachmentPreparer = new ImAttachmentPreparer(docReaders);
        ImRedisStore store = redisRuntime.store();
        RedisPubSub pubSub = redisRuntime.pubSub();
        this.qaQueue = new QaQueue(workers, maxQueue, maxPerUser, task -> {
            QaTask t = (QaTask) task.attach();
            qaRunner.executeQARequest(t);
        }, store, globalMaxWorkers, null);
        this.qaQueue.start();
        this.stopOps = new ImStopOps(store, qaQueue, inflight, () -> streamManagerRef);
        this.inboundGuard = new ImInboundGuardOps(store, rateLimitWindowSec, rateLimitMax);
        this.knowledgeBridge = new ImKnowledgeBridgeOps(knowledgeServices);
        this.channelRuntime = new ImChannelRuntimeOps(channels, channelStates, qaQueue,
                store, pubSub, this::handleMessage);
        ImCommandSet.registerDefaults(this.cmdRegistry,
                knowledgeBridge.kbLister(), knowledgeBridge.knowledgeSearcher());
        if (pubSub != null) {
            channelRuntime.startChannelConfigSubscriber();
        }
    }

    // ── 渠道生命周期（adapter 注册表） ─────────────────────────────────────

    /** 渠道启动时必须注册的平台工厂。 */
    public interface AdapterFactory {
        /** 返回适配器与停止函数（长连接的拆除柄）。webhook 型适配器 stop 可为 null。 */
        AdapterRegistration create(ImChannelEntity channel,
                java.util.function.BiConsumer<IncomingMessage, String> msgHandler);
    }

    public record AdapterRegistration(Adapter adapter, Runnable stop) {
    }


    // ── 附件异步入库 ────────────────────────────────────────────────────────

    private long channelTenantIdOrThrow(String channelId) {
        ChannelState st = channelStates.get(channelId);
        if (st != null) {
            return st.channel().getTenantId();
        }
        ImChannelEntity ch = channels.getById(channelId);
        if (ch == null) {
            throw new IllegalStateException("channel not found: " + channelId);
        }
        return ch.getTenantId();
    }

    // ── 消息入口 ─────────────────────────────────────────────────────────

    /**
     * IM 消息总入口：去重 → 限长 → 适配器解析 → 限流 → 空消息提示 → 会话解析 →
     * 命令分派 → QA 排队。全程绑定渠道租户的合成身份
     * （"system-<tenantID>" 合成用户 + viewer 最小权限——
     * 组织共享 KB 的解析要求非空 UserID）。
     */
    public void handleMessage(IncomingMessage msg, String channelId) {
        TenantContextSnapshot previous =
                TenantContextSnapshot.capture();
        TenantScope.run(previous, () -> runHandleMessage(msg, channelId));
    }

    private void runHandleMessage(IncomingMessage msg, String channelId) {
        if (TenantContext.currentTenantId() == null) {
            // 回调线程无认证上下文——绑渠道租户（QA 管线的仓库查询要求租户在上下文）。
            long tid = channelTenantIdOrThrow(channelId);
            TenantContext.set(tid, new TenantContext.Principal(
                    TenantContext.PrincipalTypes.IM_USER, "system-" + tid),
                    "viewer", false, "system-" + tid, false);
        }
        if (msg.messageId != null && !msg.messageId.isEmpty() && inboundGuard.isDuplicate(msg.messageId)) {
            log.info("[IM] Skipping duplicate message: {}", msg.messageId);
            return;
        }
        // 限长（按 code point 计）
        if (msg.content.codePointCount(0, msg.content.length()) > ImFormat.MAX_CONTENT_LENGTH) {
            log.warn("[IM] Message too long, truncating to {}", ImFormat.MAX_CONTENT_LENGTH);
            msg.content = msg.content.substring(0,
                    msg.content.offsetByCodePoints(0, ImFormat.MAX_CONTENT_LENGTH));
        }

        ChannelState state = channelStates.get(channelId);
        Adapter adapter = state == null ? null : state.adapter();
        if (adapter == null) {
            ImChannelEntity ch = channels.getById(channelId);
            if (ch == null) {
                throw new IllegalStateException("channel not found: " + channelId);
            }
            channelRuntime.startChannel(ch);
            state = channelStates.get(channelId);
            adapter = state == null ? null : state.adapter();
            if (adapter == null) {
                throw new IllegalStateException(
                        "channel adapter not available after start: " + channelId);
            }
        }
        ImChannelEntity channel = state.channel();

        String threadId = ImTypes.SESSION_MODE_THREAD.equals(channel.getSessionMode())
                ? msg.threadId : "";

        boolean isCommand = cmdRegistry.isRegistered(msg.content);
        if (!isCommand && !inboundGuard.rateLimitAllow(ImInboundGuardOps.makeRateKey(channelId, msg.userId, msg.chatId, threadId))) {
            log.warn("[IM] Rate limited: channel={} user={} chat={}", channelId, msg.userId, msg.chatId);
            sendReplyQuiet(adapter, msg, new ReplyMessage("您的消息发送过于频繁，请稍后再试。", false, true));
            return;
        }

        if (ImTypes.MESSAGE_TYPE_FILE.equals(msg.messageType)
                || ImTypes.MESSAGE_TYPE_IMAGE.equals(msg.messageType)) {
            msg.content = ImFormat.fileMessageQAContent(msg);
        }
        var empty = emptyIncomingMessageReply(msg);
        if (empty.present()) {
            sendReplyQuiet(adapter, msg, new ReplyMessage(empty.hint(), false, true));
            return;
        }

        long tenantId = channel.getTenantId();
        String agentId = channel.getAgentId() == null ? "" : channel.getAgentId();

        // 会话解析（租户已在上下文；ChannelSession/Session 另带显式租户列）。
        ChannelSessionEntity channelSession =
                resolveSession(msg, tenantId, agentId, channelId, channel.getSessionMode());
        AgentLookup.AgentView agent = agentId.isEmpty() ? null
                : agentLookup.view(agentId, "zh-CN");

        Commands.ParseResult cmd = cmdRegistry.parse(msg.content);
        if (cmd.matched()) {
            handleCommand(cmd.command(), cmd.args(), msg, adapter, channel, channelSession,
                    agent);
            return;
        }
        if (Commands.looksLikeCommand(msg.content)) {
            sendReplyQuiet(adapter, msg,
                    new ReplyMessage("未知指令，发送 `/help` 查看所有可用指令。", false, true));
            return;
        }

        SessionView session = sessionQaPort.getSession(channelSession.getSessionId());
        if (session == null) {
            // 会话被删：回收陈旧映射并重解析
            channelSessions.softDelete(channelSession.getId(), OffsetDateTime.now());
            channelSession = resolveSession(msg, tenantId, agentId, channelId,
                    channel.getSessionMode());
            session = sessionQaPort.getSession(channelSession.getSessionId());
            if (session == null) {
                throw new IllegalStateException(
                        "get session (retry): " + channelSession.getSessionId());
            }
        }

        String userKey = ImFormat.makeUserKey(channelId, msg.userId, msg.chatId, threadId);
        QaQueue.QaRequest req = new QaQueue.QaRequest(userKey, msg);
        QaTask task = new QaTask(msg, session, agent, adapter, channel, channelId, userKey);
        task.bind(req);
        req.attach(task);
        try {
            qaQueue.enqueue(req);
        } catch (QaQueue.RejectedException e) {
            log.warn("[IM] Queue rejected: user={} reason=", msg.userId, e);
            sendReplyQuiet(adapter, msg,
                    new ReplyMessage("当前排队较多，请稍后再试。", false, true));
        }
    }


    /** 空消息的提示语（按原始消息类型给出指引）。 */
    record EmptyHint(String hint, boolean present) {
    }

    static EmptyHint emptyIncomingMessageReply(IncomingMessage msg) {
        if (msg == null || !msg.content.strip().isEmpty()) {
            return new EmptyHint("", false);
        }
        boolean hasAttachment = ImTypes.MESSAGE_TYPE_FILE.equals(msg.messageType)
                || ImTypes.MESSAGE_TYPE_IMAGE.equals(msg.messageType)
                || !msg.fileKey.strip().isEmpty();
        if (hasAttachment) {
            return new EmptyHint("", false);
        }
        String rawType = msg.extra == null ? ""
                : java.util.Optional.ofNullable(msg.extra.get("raw_msgtype")).orElse("").strip().toLowerCase();
        return switch (rawType) {
            case "audio" -> new EmptyHint("未能识别这条语音中的文字内容。请改用纯文本发送，或再说一遍。", true);
            case "video" -> new EmptyHint("暂不支持视频消息。请改用纯文本发送；图片或文件请单独发送。", true);
            default -> new EmptyHint("未能识别这条消息中的文字内容。请改用纯文本发送；图片或文件请单独发送。", true);
        };
    }

    ChannelSessionEntity resolveSession(IncomingMessage msg, long tenantId, String agentId,
            String imChannelId, String sessionMode) {
        return sessionResolver.resolveSession(msg, tenantId, agentId, imChannelId, sessionMode);
    }


    // ── 命令执行 ─────────────────────────────────────────────────────────

    void handleCommand(Commands.ImCommand cmd, List<String> args, IncomingMessage msg,
            Adapter adapter, ImChannelEntity channel, ChannelSessionEntity channelSession,
            AgentLookup.AgentView agent) {
        CommandContext cmdCtx = new CommandContext();
        cmdCtx.incoming = msg;
        cmdCtx.session = channelSession;
        cmdCtx.tenantId = channel.getTenantId();
        cmdCtx.agentName = agent == null ? "" : agent.name();
        cmdCtx.agent = agent;
        cmdCtx.channelOutputMode = channel.getOutputMode() == null ? "" : channel.getOutputMode();

        CommandResult result;
        try {
            result = cmd.execute(cmdCtx, args);
        } catch (Exception e) {
            log.error("[IM] Command /{} error", cmd.name(), e);
            sendReplyQuiet(adapter, msg,
                    new ReplyMessage("抱歉，执行指令时出现了异常，请稍后再试。", false, true));
            return;
        }

        if (result.action == Commands.ACTION_CLEAR && channelSession != null) {
            // 软删当前映射：下条 IM 消息解析出全新会话。
            channelSessions.softDelete(channelSession.getId(), OffsetDateTime.now());
        } else if (result.action == Commands.ACTION_STOP) {
            stopOps.doLocalStop(channel, msg, channelSession);
        }

        boolean sent = false;
        if (!"full".equals(channel.getOutputMode()) && adapter instanceof StreamSender streamer) {
            try {
                sendStreamReply(msg, streamer, result.content);
                sent = true;
            } catch (Exception e) {
                log.warn("[IM] Stream reply for command /{} failed, falling back", cmd.name(), e);
            }
        }
        if (!sent) {
            sendReplyQuiet(adapter, msg, new ReplyMessage(result.content, false, true));
        }
        log.info("[IM] Command /{} executed: channel={} user={} action={}",
                cmd.name(), channel.getId(), msg.userId, result.action);
    }

    // ── 跨实例 /stop 的对外面（实现在 ImStopOps） ────────────────────

    /** 执行前 /stop 检查。实现见 {@link ImStopOps#checkAndClearStopMarker}。 */
    boolean checkAndClearStopMarker(String userKey) {
        return stopOps.checkAndClearStopMarker(userKey);
    }

    /** 在途登记（assistant message 创建后调用）。实现见 {@link ImStopOps#bindInflight}。 */
    void bindInflight(QaAttach attach, String assistantMessageId) {
        stopOps.bindInflight(attach, assistantMessageId);
    }

    /** 在途映射退场（QA 结束调用）。实现见 {@link ImStopOps#unbindInflight}。 */
    void unbindInflight(String userKey) {
        stopOps.unbindInflight(userKey);
    }

    /** 附件异步入库。实现见 {@link ImKnowledgeBridgeOps#ingestAttachmentToKnowledgeBase}。 */
    void ingestAttachmentToKnowledgeBase(ImChannelEntity channel,
            ImAttachmentPreparer.Prepared prepared) {
        knowledgeBridge.ingestAttachmentToKnowledgeBase(channel, prepared);
    }

    // ── 渠道运行时的对外面（实现在 ImChannelRuntimeOps） ──────────────

    /** 注册平台工厂。实现见 {@link ImChannelRuntimeOps}。 */
    public void registerAdapterFactory(String platform, AdapterFactory factory) {
        channelRuntime.registerAdapterFactory(platform, factory);
    }

    /** 取渠道适配器（回调入口用）。实现见 {@link ImChannelRuntimeOps}。 */
    public Adapter adapterFor(ImChannelEntity channel) {
        return channelRuntime.adapterFor(channel);
    }

    /** 渠道行变更后的运行时同步。实现见 {@link ImChannelRuntimeOps}。 */
    public void onChannelChanged(String channelId) {
        channelRuntime.onChannelChanged(channelId);
    }

    /**
     * 运行时就绪后启动渠道（此处的 {@code @EventListener} 是**唯一触发入口**：
     * 实现在 {@link ImChannelRuntimeOps#startChannelsOnReady()}，而那个类不是 Spring bean）。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void startChannelsOnReady() {
        channelRuntime.startChannelsOnReady();
    }

    /**
     * 运行时停止（队列 + 全部渠道 + leader 线程）。此处的 {@code @PreDestroy}
     * 是**唯一触发入口**（实现在 {@link ImChannelRuntimeOps#stop()}）。
     */
    @PreDestroy
    public void stop() {
        channelRuntime.stop();
    }

    /** 代理删除时的渠道清理（{@link AgentChannelCleaner} 端口实现）。实现见 {@link ImChannelRuntimeOps}。 */
    @Override
    public void deleteChannelsByAgent(String agentId, long tenantId) {
        channelRuntime.deleteChannelsByAgent(agentId, tenantId);
    }

    private volatile StreamManager streamManagerRef;

    /** StreamManager 延迟接（避免与 stream 包的装配环；测试可注 stub）。 */
    public void setStreamManager(StreamManager sm) {
        this.streamManagerRef = sm;
    }

    void sendStreamReply(IncomingMessage msg, StreamSender streamer, String content)
            throws Exception {
        outboundFormatter.sendStreamReply(msg, streamer, content);
    }

    /** 排队任务：QaRequest（队列面）+ 业务束。 */
    static final class QaTask {
        private QaQueue.QaRequest queueReq;

        QaQueue.QaRequest queueReq() {
            return queueReq;
        }
        final IncomingMessage msg;
        final SessionView session;
        final AgentLookup.AgentView agent;
        final Adapter adapter;
        final ImChannelEntity channel;
        final String channelId;
        final String userKey;

        void bind(QaQueue.QaRequest r) {
            this.queueReq = r;
        }

        QaTask(IncomingMessage msg, SessionView session, AgentLookup.AgentView agent,
                Adapter adapter, ImChannelEntity channel, String channelId, String userKey) {

            this.msg = msg;
            this.session = session;
            this.agent = agent;
            this.adapter = adapter;
            this.channel = channel;
            this.channelId = channelId;
            this.userKey = userKey;
        }

        long tenantId() {
            return channel.getTenantId();
        }

        QaAttach attach(InflightEntry entry) {
            return new QaAttach(msg, session, agent, adapter, channel, channelId, userKey, entry);
        }
    }

    /** QA 输入束（qaRequest 的业务字段 + 在途登记句柄）。 */

    void runFallbackNonStream(QaAttach attach, ImAttachmentPreparer.Prepared prepared) {
        qaRunner.runFallbackNonStream(attach, prepared);
    }
    record QaAttach(IncomingMessage msg, SessionView session, AgentLookup.AgentView agent,
            Adapter adapter, ImChannelEntity channel, String channelId, String userKey,
            InflightEntry inflight) {
    }

    record QaOutcome(String answer, Exception error) {
    }
    String formatIMOutboundAnswerOrFallback(String raw) {
        return outboundFormatter.formatIMOutboundAnswerOrFallback(raw);
    }

    String formatIMOutboundAnswerOrFallback(String raw, boolean rendersImages) {
        return outboundFormatter.formatIMOutboundAnswerOrFallback(raw, rendersImages);
    }

    String cleanIMContent(String content) {
        return outboundFormatter.cleanIMContent(content);
    }

    String cleanIMContent(String content, boolean rendersImages) {
        return outboundFormatter.cleanIMContent(content, rendersImages);
    }


    void sendReplyQuiet(Adapter adapter, IncomingMessage msg, ReplyMessage reply) {
        outboundFormatter.sendReplyQuiet(adapter, msg, reply);
    }

}
