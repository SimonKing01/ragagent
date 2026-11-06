package com.ragagent.session.qa;


import com.ragagent.contract.error.BizException;
import com.ragagent.contract.event.Event;
import com.ragagent.contract.event.payload.AgentCompleteData;
import com.ragagent.contract.event.payload.AgentFinalAnswerData;
import com.ragagent.contract.event.payload.AgentThoughtData;
import com.ragagent.contract.event.payload.ErrorData;
import com.ragagent.contract.session.MessageAttachment;
import com.ragagent.contract.session.MessageExecutionContext;
import com.ragagent.contract.session.MessageImage;
import com.ragagent.contract.session.SessionLastRequestState;
import com.ragagent.contract.session.SseSink;
import com.ragagent.contract.tenant.TenantConfigLookup;
import com.ragagent.platform.access.TenantContextSnapshot;
import com.ragagent.platform.access.TenantScope;
import com.ragagent.platform.event.EventType;
import com.ragagent.platform.stream.StreamManager;
import com.ragagent.session.conversation.MessageService;
import com.ragagent.session.conversation.SessionLookupScope;
import com.ragagent.session.domain.message.Message;
import com.ragagent.session.service.QaSupport;
import com.ragagent.session.service.QaSupport.QaMode;
import com.ragagent.session.service.QaSupport.QaRequestContext;
import com.ragagent.session.service.QaSupport.SseStreamContext;
import com.ragagent.session.service.SessionAgentQaService;
import com.ragagent.session.service.SessionKnowledgeQaService;
import com.ragagent.session.service.SteerRunCoordinator;
import com.ragagent.session.service.TurnLifecycle;
import com.ragagent.session.support.AgentConfigSnapshot;
import io.micrometer.core.instrument.Metrics;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.springframework.stereotype.Component;

/**
 * {@code KnowledgeQaController} 的**执行/落库簇**：一整轮 QA 的编排（模式分派、
 * follow-up 交接与回滚、租户作用域）、轮次消息落库（用户消息构造/回滚）、并发守卫。
 *
 * <p>共享项处理：{@code turnFinalizer}/{@code sseOrchestrator}/{@code attachmentResolver}
 * 均为已建协作者，直接持有转发；{@code appendQuickAnswerReasoning} 是 static → 留控制器按类名引用。</p>
 */
@Component
public final class QaTurnExecutor {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(QaTurnExecutor.class);

    private final MessageService messageService;
    private final StreamManager streamManager;
    private final SessionKnowledgeQaService knowledgeQaService;
    private final SessionAgentQaService agentQaService;
    private final SteerRunCoordinator steerCoordinator;
    private final QaSseOrchestrator sseOrchestrator;
    private final QaTurnFinalizer turnFinalizer;
    private final QaAttachmentResolver attachmentResolver;
    private final TenantConfigLookup tenantService;

    public QaTurnExecutor(MessageService messageService, StreamManager streamManager, SessionKnowledgeQaService knowledgeQaService, SessionAgentQaService agentQaService, SteerRunCoordinator steerCoordinator, QaSseOrchestrator sseOrchestrator, QaTurnFinalizer turnFinalizer, QaAttachmentResolver attachmentResolver,
            org.springframework.beans.factory.ObjectProvider<TenantConfigLookup> tenantServiceProvider) {
        this.messageService = messageService;
        this.streamManager = streamManager;
        this.knowledgeQaService = knowledgeQaService;
        this.agentQaService = agentQaService;
        this.steerCoordinator = steerCoordinator;
        this.sseOrchestrator = sseOrchestrator;
        this.turnFinalizer = turnFinalizer;
        this.attachmentResolver = attachmentResolver;
        // 租户服务可选（缺 bean 时降级，与控制器原先的手工装配语义一致）
        this.tenantService = tenantServiceProvider.getIfAvailable();
    }

    public void executeQA(QaRequestContext reqCtx, QaMode mode, boolean generateTitle,
            SseSink sink) throws IOException {
        executeQA(reqCtx, mode, generateTitle, sink, null);
    }
    /**
     * @param asyncDone skipSSE 调用方（steer follow-up）的完成信号：异步 runner 收尾后
     *                  complete；HTTP 调用方传 null。
     */
    private void executeQA(QaRequestContext reqCtx, QaMode mode, boolean generateTitle,
            SseSink sink, CompletableFuture<Void> asyncDone) throws IOException {
        String sessionId = reqCtx.sessionId;
        Metrics.counter("ragagent.qa.turns", "mode", mode.name().toLowerCase(Locale.ROOT)).increment();

        persistLastRequestStateAsync(reqCtx, mode);
        if (mode == QaMode.AGENT) {
            rejectIfOtherAgentRunLive(reqCtx);
        }
        // agent 模式的 query 帧由 setupSSEStream 内的 writeAgentQueryEvent 直写流；
        // 不向空 EventBus 发事件（该实例无任何订阅者）。

        boolean createdUser = reqCtx.userMessageId.isEmpty();
        boolean createdAssistant =
                reqCtx.assistantMessage == null || reqCtx.assistantMessage.getId().isEmpty();
        persistTurnOrFail(reqCtx);
        carryOverSteerEvents(reqCtx, sessionId);

        SseStreamContext streamCtx = setupSseOrRollback(
                reqCtx, mode, generateTitle, createdUser, createdAssistant);
        if (mode == QaMode.NORMAL) {
            registerQuickAnswerHandlers(streamCtx, reqCtx, sessionId);
        }
        startAsyncRunner(reqCtx, mode, streamCtx, sessionId, asyncDone);

        if (sink == null) {
            // 无 SSE 客户端（steer follow-up 等）：仍需盯 STOP/终态，否则取消无人接收
            sseOrchestrator.startHeadlessPump(sessionId, reqCtx.assistantMessage.getId(), streamCtx);
            return;
        }
        boolean shouldWaitForTitle = generateTitle && reqCtx.session.getTitle() != null
                && reqCtx.session.getTitle().isEmpty();
        sseOrchestrator.handleAgentEventsForSSE(sink, sessionId, reqCtx.assistantMessage.getId(),
                reqCtx.requestId, streamCtx, shouldWaitForTitle, reqCtx.resourceRewriter);
    }

    /**
     * 输入条状态（纯 UI memo）异步写。
     *
     * <p>派生线程没有 ThreadLocal：不显式捕获-重放，租户会读到 0、owner 为空，
     * {@code updateSessionLastRequestState} 按 (tenant, owner) 过滤后静默 0 行。</p>
     */
    private void persistLastRequestStateAsync(QaRequestContext reqCtx, QaMode mode) {
        final TenantContextSnapshot memoTenant = TenantContextSnapshot.capture();
        final String sessionId = reqCtx.sessionId;
        final SessionLastRequestState snapshot = turnFinalizer.lastRequestStateSnapshot(reqCtx, mode);
        Thread.ofVirtual().start(() -> TenantScope.run(memoTenant, () -> {
            SessionLookupScope.mark();
            try {
                turnFinalizer.persistLastRequestState(sessionId, snapshot);
            } finally {
                SessionLookupScope.clear();
            }
        }));
    }

    /** 消息落库；此刻流尚未建立，失败可直接走 HTTP 500。 */
    private void persistTurnOrFail(QaRequestContext reqCtx) {
        try {
            persistTurnMessages(reqCtx);
        } catch (RuntimeException e) {
            log.warn("Failed to persist turn", e);
            throw BizException.internal("failed to persist turn");
        }
    }

    /** steer carry-over：本 run 公布为 live 之前接上。 */
    private void carryOverSteerEvents(QaRequestContext reqCtx, String sessionId) {
        if (reqCtx.steerCarryOver != null && !reqCtx.steerCarryOver.isEmpty()
                && reqCtx.assistantMessage != null) {
            try {
                streamManager.appendSteerEvents(
                        sessionId, reqCtx.assistantMessage.getId(), reqCtx.steerCarryOver);
            } catch (RuntimeException e) {
                log.warn("steer carry-over append failed for session {}", sessionId, e);
            }
        }
    }

    /**
     * 建流并公布 live。
     *
     * <p>失败时先回滚已落库的消息，再抛：409 = 本会话已有另一轮在跑；503 = 公布失败。</p>
     */
    private SseStreamContext setupSseOrRollback(QaRequestContext reqCtx, QaMode mode,
            boolean generateTitle, boolean createdUser, boolean createdAssistant) {
        SseStreamContext streamCtx = sseOrchestrator.setupSSEStream(reqCtx, generateTitle, mode);
        if (streamCtx.liveRunFailed) {
            rollbackTurnMessages(reqCtx, createdUser, createdAssistant);
            if (streamCtx.liveRunExists) {
                throw BizException.conflict("another turn is already running in this session");
            }
            throw BizException.serviceUnavailable("Failed to publish running turn");
        }
        return streamCtx;
    }

    /** 快答路径：timeline 记录器 + reasoning 累积 + 完成事件。 */
    private void registerQuickAnswerHandlers(
            SseStreamContext streamCtx, QaRequestContext reqCtx, String sessionId) {
        sseOrchestrator.registerQuickAnswerTimelineRecorder(streamCtx.eventBus,
                streamCtx.assistantMessage, streamCtx.messageGate);
        streamCtx.eventBus.on(EventType.EVENT_AGENT_THOUGHT, evt -> {
            if (evt.getData() instanceof AgentThoughtData data && !data.getContent().isEmpty()) {
                synchronized (streamCtx.messageGate.monitor()) {
                    QaTurnSupport.appendQuickAnswerReasoning(
                            streamCtx.assistantMessage, data.getContent());
                }
            }
        });

        // 事件在桥接虚拟线程触发，TenantContext 是 ThreadLocal——注册时捕获
        // session 租户，触发时 replay（与 stop 处理器同款纪律 #1）。
        final long normalSessionTenantId = reqCtx.session.getTenantId();
        streamCtx.eventBus.on(EventType.EVENT_AGENT_FINAL_ANSWER, evt -> {
            if (!(evt.getData() instanceof AgentFinalAnswerData data)) {
                return;
            }
            Message am = streamCtx.assistantMessage;
            synchronized (streamCtx.messageGate.monitor()) {
                am.setContent(am.getContent() + QaSupport.orEmpty(data.getContent()));
                if (data.isFallback()) {
                    am.setFallback(true);
                }
                if (data.isDone()) {
                    // 终态帧只表示“流关闭”：outcome=FAILED 说明是异常/超时收束，不是业务成功
                    boolean failed = "FAILED".equals(data.getOutcome());
                    TurnLifecycle.State terminal = failed
                            ? TurnLifecycle.State.FAILED : TurnLifecycle.State.DONE;
                    if (!streamCtx.lifecycle.finish(terminal)) {
                        return; // 已由另一条收尾路径赢下终态
                    }
                    QaTerminalMetrics.record(QaMode.NORMAL, terminal);
                    log.info("Knowledge QA service {} for session: {}",
                            failed ? "failed" : "completed", sessionId);
                    turnFinalizer.runWithTenant(normalSessionTenantId, () -> {
                        turnFinalizer.completeAssistantMessage(am, reqCtx.query,
                                reqCtx.userMessageId, normalSessionTenantId);
                        if (!failed) {
                            Event done = new Event();
                            done.setType(EventType.EVENT_AGENT_COMPLETE);
                            done.setSessionId(sessionId);
                            AgentCompleteData cd = new AgentCompleteData();
                            cd.setFinalAnswer(am.getContent());
                            done.setData(cd);
                            streamCtx.eventBus.emit(done);
                        }
                    });
                }
            }
        });
    }

    /**
     * 异步执行段：租户快照 → 引擎调用 → 错误事件 → 收尾。
     *
     * <p>纪律 #1：TenantContext 是 ThreadLocal，跨虚拟线程必须显式 capture/replay。
     * 共享 agent 以源租户为执行租户（模型/KB/MCP 解析范围；身份不变）。</p>
     */
    private void startAsyncRunner(QaRequestContext reqCtx, QaMode mode, SseStreamContext streamCtx,
            String sessionId, CompletableFuture<Void> asyncDone) {
        // 共享 agent → 异步段以源租户为
        // 执行租户（模型/KB/MCP 解析范围；身份不变）；租户不存在则不切换。
        final TenantContextSnapshot requestTenant =
                reqCtx.effectiveTenantId != 0
                        && tenantService.storageView(reqCtx.effectiveTenantId) != null
                ? TenantContextSnapshot.capture()
                        .withTenantId(reqCtx.effectiveTenantId)
                : TenantContextSnapshot.capture();
        if (reqCtx.effectiveTenantId == requestTenant.tenantId() && reqCtx.effectiveTenantId != 0) {
            log.info("Using effective tenant {} for shared agent (model/KB/MCP)",
                    reqCtx.effectiveTenantId);
        }
        Thread.Builder.OfVirtual runner = Thread.ofVirtual();
        runner.start(() -> TenantScope.run(requestTenant, () -> {
            streamCtx.runner = Thread.currentThread();
            boolean failed = false;
            try {
                // 本线程上的会话/消息查询按会话属主
                // 租户范围——共享 agent 场景下当前主体不是属主，带 user 范围会查不到。
                SessionLookupScope.mark();
                attachmentResolver.resolveTemporaryAttachments(streamCtx, reqCtx);
                QaSupport.QaRequest qaReq = reqCtx.buildQaRequest();
                // 用户停止 → 引擎取消（取消探针贯穿 think/act/审批等待三条路）：
                // 探针读 streamCtx.cancelled（stop 处理器置位），语义 null=未取消。
                qaReq.cancellationProbe = () -> streamCtx.cancelled ? "context canceled" : null;
                if (mode == QaMode.NORMAL) {
                    knowledgeQaService.knowledgeQA(qaReq, streamCtx.eventBus);
                } else if (agentQaService.agentQA(qaReq, streamCtx.eventBus)
                        == TurnLifecycle.State.FAILED) {
                    failed = true;
                }
            } catch (RuntimeException serviceErr) {
                failed = true;
                Metrics.counter("ragagent.qa.failures", "mode", mode.name().toLowerCase(Locale.ROOT))
                        .increment();
                log.error("QA service failed for session {}", sessionId, serviceErr);
                Event errEvt = new Event();
                errEvt.setType(EventType.EVENT_ERROR);
                errEvt.setSessionId(sessionId);
                ErrorData errData = new ErrorData();
                // 错误文案取 wireText：**带** "error code: N, error message: M" 前缀，
                // 前缀本身是契约（见 docs/known-issues/09-e2e-observations.md 的记录），
                // 不要剥成裸 message；只需保证不吐 Java 异常类名。
                errData.setError(BizException.wireText(serviceErr));
                errData.setStage(mode == QaMode.NORMAL ? "knowledge_qa_execution" : "agent_execution");
                errData.setSessionId(sessionId);
                errEvt.setData(errData);
                try {
                    streamCtx.eventBus.emit(errEvt);
                } catch (RuntimeException ignore) {
                    // 流已终止
                }
            } finally {
                if (mode == QaMode.AGENT) {
                    TurnLifecycle.State terminal = streamCtx.cancelled
                            ? TurnLifecycle.State.CANCELLED
                            : (failed ? TurnLifecycle.State.FAILED : TurnLifecycle.State.DONE);
                    if (streamCtx.lifecycle.finish(terminal)) {
                        QaTerminalMetrics.record(mode, terminal);
                    }
                    Message am = streamCtx.assistantMessage;
                    // agent 收尾：steer 交接 + 完成 + 清除 live 标记
                    //
                    // 顺序纪律：收尾必须在**本线程上下文仍完整**时进行；TenantScope 的恢复
                    // 在它之后。早先实现先 clear 再 runWithTenant，prev 已空、只剩 tenantId——
                    // completeAssistantMessage 的异步索引/follow-up 快照因此丢了
                    // principal/userId，owner 推导成 ""，embed（owner=embed_session:…）
                    // 与平台（owner=<userId>）会话双双 SessionNotFound。
                    Long sessionTenant = reqCtx.session.getTenantId();
                    turnFinalizer.runWithTenant(sessionTenant, () -> {
                        if (streamCtx.cancelled) {
                            Set<String> injected = streamCtx.steerSink != null
                                    ? streamCtx.steerSink.injectedIds() : Set.of();
                            steerCoordinator.discardSteerBacklog(sessionId, am.getId(), injected);
                            turnFinalizer.completeAssistantMessage(am, reqCtx.query, reqCtx.userMessageId, sessionTenant);
                        } else {
                            boolean kicked = steerCoordinator.kickNextRunFromSteerBacklog(
                                    reqCtx, streamCtx, this::runFollowUp);
                            turnFinalizer.completeAssistantMessage(am, reqCtx.query, reqCtx.userMessageId, sessionTenant);
                            if (!kicked) {
                                steerCoordinator.kickNextRunFromSteerBacklog(reqCtx, streamCtx, this::runFollowUp);
                            }
                        }
                        try {
                            streamManager.clearLiveRun(sessionId, am.getId());
                        } catch (RuntimeException e) {
                            log.warn("live run cleanup failed for session {}", sessionId, e);
                        }
                        log.info("Agent QA service completed for session: {}", sessionId);
                    });
                } else if (failed || streamCtx.cancelled) {
                    // NORMAL 早段失败：事件管线不会发出 done，完成位必须在此兜底——
                    // 否则该行消息永久 completed=false（AGENT 侧一直有 finally 收尾，
                    // 这条不对称正是缺陷来源）。completeAssistantMessage 是 CAS 幂等，
                    // 与成功路径的事件收尾并发时只有一方赢。
                    finalizeNormalOnTerminal(reqCtx, streamCtx);
                }
                // 收尾（含身份相关的库写）完成后再清会话范围标记；租户上下文由 TenantScope 恢复
                SessionLookupScope.clear();
                if (asyncDone != null) {
                    asyncDone.complete(null);
                }
            }
        }));
    }
    /**
     * NORMAL 失败收尾：把 assistant 消息提交为完成态。
     *
     * <p>包级可见是为定向测试能直接钉住这条不变量——它不是一个独立用例，只是
     * finally 里的兜底动作。</p>
     */
    void finalizeNormalOnTerminal(QaRequestContext reqCtx, SseStreamContext streamCtx) {
        TurnLifecycle.State terminal = streamCtx.cancelled
                ? TurnLifecycle.State.CANCELLED : TurnLifecycle.State.FAILED;
        if (streamCtx.lifecycle.finish(terminal)) {
            QaTerminalMetrics.record(QaMode.NORMAL, terminal);
        }
        Message am = streamCtx.assistantMessage;
        if (am == null) {
            return;
        }
        Long sessionTenant = reqCtx.session.getTenantId();
        turnFinalizer.runWithTenant(sessionTenant,
                () -> turnFinalizer.completeAssistantMessage(
                        am, reqCtx.query, reqCtx.userMessageId, sessionTenant));
    }

    private void runFollowUp(QaRequestContext followUp) {
        // follow-up 是 skipSSE 的服务端自启轮（新虚拟线程执行）
        Thread.ofVirtual().start(() -> {
            try {
                executeQaInternal(followUp, QaMode.AGENT, false, null);
            } catch (Throwable e) {
                // follow-up 失败必须自救：claimNextSteerFollowUp 已落 user/assistant 两行并
                // 抢占 live-run，异常路径若不清理会把这个会话的 agent 模式持续 409 锁死。
                log.error("steer follow-up run failed", e);
                recoverFailedFollowUp(followUp);
            }
        });
    }
    /** follow-up 启动失败的兜底：收尾半成品 assistant 行 + 清 live-run（尽力而为）。 */
    private void recoverFailedFollowUp(QaRequestContext followUp) {
        try {
            String amId = followUp.assistantMessage == null ? "" : followUp.assistantMessage.getId();
            long sessionTenant = followUp.session == null ? 0 : followUp.session.getTenantId();
            if (followUp.assistantMessage != null && !amId.isEmpty() && sessionTenant != 0) {
                turnFinalizer.runWithTenant(sessionTenant, () -> turnFinalizer.completeAssistantMessage(
                        followUp.assistantMessage, followUp.query, followUp.userMessageId, sessionTenant));
            }
        } catch (RuntimeException e) {
            log.warn("follow-up completion recovery failed", e);
        }
        try {
            String amId = followUp.assistantMessage == null ? "" : followUp.assistantMessage.getId();
            if (!amId.isEmpty()) {
                streamManager.clearLiveRun(followUp.sessionId, amId);
            }
        } catch (RuntimeException e) {
            log.warn("follow-up live-run cleanup failed for session {}", followUp.sessionId, e);
        }
    }
    private void executeQaInternal(QaRequestContext reqCtx, QaMode mode, boolean generateTitle,
            SseSink sink) throws IOException {
        if (sink == null) {
            // skipSSE 路径：等待异步段完成后返回
            CompletableFuture<Void> asyncDone = new CompletableFuture<>();
            try {
                executeQA(reqCtx, mode, generateTitle, null, asyncDone);
            } catch (RuntimeException | IOException e) {
                // runner 未起就失败：放行等待者，异常交给 runFollowUp 的 recover 路径
                asyncDone.complete(null);
                throw e;
            }
            asyncDone.join();
            return;
        }
        executeQA(reqCtx, mode, generateTitle, sink, null);
    }
    private void persistTurnMessages(QaRequestContext reqCtx) {
        if (reqCtx.assistantMessage == null) {
            Message am = new Message();
            am.setSessionId(reqCtx.sessionId);
            am.setRole("assistant");
            am.setCompleted(false);
            am.setRequestId(reqCtx.requestId);
            am.setCreatedAt(OffsetDateTime.now());
            reqCtx.assistantMessage = am;
        }
        applyAgentConfigSnapshot(reqCtx.assistantMessage, reqCtx);
        if (reqCtx.userMessageId.isEmpty() && reqCtx.assistantMessage.getId().isEmpty()) {
            // 两条消息同事务写入：任一失败一起回滚，避免孤儿 user 行
            List<MessageAttachment> userMessageAttachments = new ArrayList<>(reqCtx.attachments);
            userMessageAttachments.addAll(reqCtx.attachmentMetas);
            Message[] created = messageService.createTurnMessages(
                    buildUserMessage(reqCtx, userMessageAttachments), reqCtx.assistantMessage);
            reqCtx.userMessageId = created[0].getId();
            reqCtx.userCreatedAt = created[0].getCreatedAt();
            reqCtx.assistantMessage = created[1];
            return;
        }
        if (reqCtx.userMessageId.isEmpty()) {
            List<MessageAttachment> userMessageAttachments = new ArrayList<>(reqCtx.attachments);
            userMessageAttachments.addAll(reqCtx.attachmentMetas);
            Message userMsg = messageService.createMessage(buildUserMessage(reqCtx, userMessageAttachments));
            reqCtx.userMessageId = userMsg.getId();
            reqCtx.userCreatedAt = userMsg.getCreatedAt();
        }
        if (reqCtx.assistantMessage.getId().isEmpty()) {
            reqCtx.assistantMessage.setCreatedAt(OffsetDateTime.now());
            Message created = messageService.createMessage(reqCtx.assistantMessage);
            reqCtx.assistantMessage = created;
        }
    }
    /**
     * 把智能体配置快照挂到 assistant 消息上：追问建议在主流程结束后异步发起，
     * 那时只能靠这份快照判断「是否开启 / 用哪个模型 / 配置有没有变」。
     */
    private void applyAgentConfigSnapshot(Message assistant, QaRequestContext reqCtx) {
        if (assistant == null) {
            return;
        }
        AgentConfigSnapshot.Snapshot snapshot = AgentConfigSnapshot.of(reqCtx.agentConfig);
        MessageExecutionContext ctx = assistant.getExecutionContext();
        if (ctx == null) {
            ctx = new MessageExecutionContext();
        }
        ctx.setAgentConfigHash(snapshot.configHash());
        ctx.setQuestionSuggestions(snapshot.questionSuggestions());
        assistant.setExecutionContext(ctx);
    }

    private Message buildUserMessage(QaRequestContext reqCtx, List<MessageAttachment> attachments) {
        Message m = new Message();
        m.setSessionId(reqCtx.sessionId);
        m.setRole("user");
        m.setContent(reqCtx.query);
        m.setRequestId(reqCtx.requestId);
        m.setCreatedAt(OffsetDateTime.now());
        m.setCompleted(true);
        m.setMentionedItems(reqCtx.mentionedItems);
        List<MessageImage> images = new ArrayList<>();
        for (QaSupport.QaRequestsImage img : reqCtx.images) {
            MessageImage mi = new MessageImage();
            mi.setUrl(img.url);
            mi.setCaption(img.caption);
            images.add(mi);
        }
        m.setImages(images);
        m.setAttachments(attachments);
        m.setChannel(reqCtx.channel);
        if (reqCtx.suggestionAttribution != null) {
            MessageExecutionContext ctx =
                    new MessageExecutionContext();
            ctx.setSuggestionAttribution(reqCtx.suggestionAttribution);
            m.setExecutionContext(ctx);
        }
        return m;
    }
    private void rollbackTurnMessages(QaRequestContext reqCtx, boolean user, boolean assistant) {
        String sessionId = reqCtx.sessionId;
        if (user && !reqCtx.userMessageId.isEmpty()) {
            try {
                messageService.deleteMessage(sessionId, reqCtx.userMessageId);
                reqCtx.userMessageId = "";
            } catch (RuntimeException e) {
                log.warn("turn rollback failed for user message {}", reqCtx.userMessageId, e);
            }
        }
        if (assistant && reqCtx.assistantMessage != null && !reqCtx.assistantMessage.getId().isEmpty()) {
            try {
                messageService.deleteMessage(sessionId, reqCtx.assistantMessage.getId());
                reqCtx.assistantMessage.setId("");
            } catch (RuntimeException e) {
                log.warn("turn rollback failed for assistant message {}", reqCtx.assistantMessage.getId(), e);
            }
        }
    }
    private void rejectIfOtherAgentRunLive(QaRequestContext reqCtx) {
        var live = streamManager.getLiveRun(reqCtx.sessionId);
        String liveId = live == null ? "" : live.assistantMessageId();
        if (liveId.isEmpty()) {
            return;
        }
        String self = reqCtx.assistantMessage == null ? "" : reqCtx.assistantMessage.getId();
        if (liveId.equals(self)) {
            return;
        }
        throw BizException.conflict("another turn is already running in this session");
    }
}
