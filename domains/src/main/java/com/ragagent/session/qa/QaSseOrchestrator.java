package com.ragagent.session.qa;


import com.ragagent.contract.agent.AgentStep;
import com.ragagent.contract.agent.AgentToolCall;
import com.ragagent.contract.event.EventBusFactory;
import com.ragagent.contract.event.EventBusInterface;
import com.ragagent.contract.event.payload.AgentToolCallData;
import com.ragagent.contract.event.payload.AgentToolResultData;
import com.ragagent.contract.model.ResponseType;
import com.ragagent.contract.session.SseSink;
import com.ragagent.contract.tool.ToolResult;
import com.ragagent.platform.access.TenantContextSnapshot;
import com.ragagent.platform.access.TenantScope;
import com.ragagent.platform.event.EventType;
import com.ragagent.platform.storage.support.StreamRewriter;
import com.ragagent.platform.stream.LiveRunExistsException;
import com.ragagent.platform.stream.StreamEvent;
import com.ragagent.platform.stream.StreamManager;
import com.ragagent.session.conversation.AgentStreamBridge;
import com.ragagent.session.conversation.MessageService;
import com.ragagent.session.conversation.SessionService;
import com.ragagent.session.domain.message.Message;
import com.ragagent.session.service.QaSupport.QaMode;
import com.ragagent.session.service.QaSupport.QaRequestContext;
import com.ragagent.session.service.QaSupport.SseStreamContext;
import com.ragagent.session.service.SteerSinkBridge;
import com.ragagent.session.service.TurnLifecycle;
import com.ragagent.session.service.TurnMessageGate;
import com.ragagent.session.service.TurnMessageHandle;
import com.ragagent.session.service.sse.SseFrameSink;
import com.ragagent.session.service.sse.StreamEventEmitter;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * {@code KnowledgeQaController} 的**SSE 编排簇**：建立 SSE 流上下文、写
 * {@code agent_query} 请求帧、看门狗线程（只认流内 STOP/COMPLETE/终态事件，到点即收尾；
 * 客户端断连由 SSE 写出路径的 IOException 处理，不归看门狗）、把 agent 事件转发进 SSE，
 * 以及 quick answer 时间线的录制（步进/工具调用）。
 *
 * <p>共享行为：{@code runWithTenant} / {@code completeAssistantMessage} 在
 * {@link QaTurnFinalizer}，本类**持有它**转发（不回调控制器）；{@code ensureQuickAnswerStep}
 * 是 static 且执行簇也在用 → 留控制器，按类名引用。</p>
 */
@Component
public final class QaSseOrchestrator {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(QaSseOrchestrator.class);

    /** STOP 兜底窗口：执行体未在此时限内收尾则强制提交 CANCELLED。 */
    private static final long STOP_FALLBACK_MILLIS = 3000L;

    private final StreamManager streamManager;
    private final StreamEventEmitter emitter;
    private final SessionService sessionService;
    private final MessageService messageService;
    private final SseFrameSink sseFrameWriter;
    private final QaTurnFinalizer turnFinalizer;

    /** 每轮一个独立总线（生命周期随轮次）；经工厂端口创建，不 new 实现类。 */
    private final EventBusFactory eventBusFactory;

    public QaSseOrchestrator(StreamManager streamManager, StreamEventEmitter emitter, SessionService sessionService, MessageService messageService, SseFrameSink sseFrameWriter, QaTurnFinalizer turnFinalizer, EventBusFactory eventBusFactory) {
        this.streamManager = streamManager;
        this.emitter = emitter;
        this.sessionService = sessionService;
        this.messageService = messageService;
        this.sseFrameWriter = sseFrameWriter;
        this.turnFinalizer = turnFinalizer;
        this.eventBusFactory = eventBusFactory;
    }

    public SseStreamContext setupSSEStream(QaRequestContext reqCtx, boolean generateTitle, QaMode mode) {
        SseStreamContext streamCtx = new SseStreamContext();
        streamCtx.assistantMessage = reqCtx.assistantMessage;
        streamCtx.tenantSnapshot = TenantContextSnapshot.capture();

        EventBusInterface eventBus = eventBusFactory.create();
        streamCtx.eventBus = eventBus;

        // Mid-run steering：仅 agent 模式有引擎排空点
        if (mode == QaMode.AGENT && reqCtx.agentConfig != null) {
            SteerSinkBridge sink = new SteerSinkBridge(reqCtx.sessionId, reqCtx.requestId,
                    messageService, streamManager,
                    TenantContextSnapshot.capture());
            streamCtx.steerSink = sink;
            reqCtx.steerSink = sink;
            try {
                streamManager.setLiveRun(reqCtx.sessionId, reqCtx.assistantMessage.getId(), reqCtx.requestId);
            } catch (RuntimeException e) {
                log.error("SetLiveRun failed for session {}", reqCtx.sessionId, e);
                streamCtx.liveRunFailed = true;
                streamCtx.liveRunErr = e.getMessage();
                streamCtx.liveRunExists = e instanceof LiveRunExistsException;
                return streamCtx;
            }
        }

        // agent_query 事件写流
        writeAgentQueryEvent(reqCtx);

        // stop 事件处理器
        long sessionTenantId = reqCtx.session.getTenantId();
        // A5：处理器可能跑在 stop watcher 的虚拟线程上（那里身份为空）——注册时在本线程
        // （身份完整）捕获快照，触发时用它补齐；否则收尾的索引/建议/记忆 SessionNotFound。
        TenantContextSnapshot stopIdentity = TenantContextSnapshot.capture();
        eventBus.on(EventType.EVENT_STOP, evt -> TenantScope.run(stopIdentity, () -> {
            // STOP 只发起取消：置位 + 打断执行体，终态与消息完成交给唯一 finalizer
            // （执行体 finally / 下面的兜底），避免"停止即完成"抢先写空内容与空 userId
            log.info("Received stop event, requesting cancel for session: {}", reqCtx.sessionId);
            streamCtx.cancelled = true;
            Thread runner = streamCtx.runner;
            if (runner != null) {
                runner.interrupt();
            }
            Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(STOP_FALLBACK_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (streamCtx.lifecycle.finish(TurnLifecycle.State.CANCELLED)) {
                    QaTerminalMetrics.record(mode, TurnLifecycle.State.CANCELLED);
                    turnFinalizer.runWithTenant(sessionTenantId, stopIdentity, () ->
                            turnFinalizer.completeAssistantMessage(streamCtx.assistantMessage,
                                    reqCtx.query, reqCtx.userMessageID, sessionTenantId));
                }
            });
        }));

        // AgentStreamBridge 订阅（16 种事件）
        AgentStreamBridge bridge = new AgentStreamBridge(reqCtx.sessionId, reqCtx.assistantMessage.getId(),
                reqCtx.requestId, OffsetDateTime.now(),
                new TurnMessageHandle(reqCtx.assistantMessage, streamCtx.messageGate),
                streamManager, eventBus);
        bridge.subscribe();

        // title 生成（GenerateTitleAsync：session title 为空时）
        if (generateTitle && (reqCtx.session.getTitle() == null || reqCtx.session.getTitle().isEmpty())) {
            String modelId = "";
            if (reqCtx.agentConfig != null) {
                modelId = reqCtx.agentConfig.path("modelId").asText("");
            }
            log.info("Session has no title, starting async title generation, session ID: {}, model: {}",
                    reqCtx.sessionId, modelId);
            sessionService.generateTitleAsync(reqCtx.session, reqCtx.query, modelId, eventBus);
        }
        return streamCtx;
    }
    private void writeAgentQueryEvent(QaRequestContext reqCtx) {
        String assistantMessageId = reqCtx.assistantMessage == null ? "" : reqCtx.assistantMessage.getId();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", reqCtx.sessionId);
        data.put("assistantMessageId", assistantMessageId);
        if (!reqCtx.userMessageID.isEmpty()) {
            data.put("userMessageId", reqCtx.userMessageID);
        }
        if (reqCtx.userCreatedAt != null) {
            data.put("userCreatedAt", reqCtx.userCreatedAt.toInstant().toString());
        }
        if (reqCtx.assistantMessage != null && reqCtx.assistantMessage.getCreatedAt() != null) {
            data.put("assistantCreatedAt", reqCtx.assistantMessage.getCreatedAt().toInstant().toString());
        }
        StreamEvent evt = new StreamEvent();
        evt.setId("query-" + System.nanoTime());
        evt.setType(ResponseType.AGENT_QUERY);
        evt.setContent("");
        evt.setDone(true);
        evt.setTimestamp(OffsetDateTime.now());
        evt.setData(data);
        try {
            streamManager.appendEvent(reqCtx.sessionId, assistantMessageId, evt);
        } catch (RuntimeException e) {
            log.error("write agent query event failed session={} message={}", reqCtx.sessionId, assistantMessageId, e);
        }
    }
    public void handleAgentEventsForSSE(SseSink sink, String sessionId,
            String assistantMessageId, String requestId, SseStreamContext streamCtx,
            boolean waitForTitle, StreamRewriter resourceRewriter) throws IOException {
        new TurnStreamPump(streamManager, emitter, sseFrameWriter)
                .run(sink, sessionId, assistantMessageId, requestId, streamCtx,
                        waitForTitle, resourceRewriter);
    }

    /** 无 SSE 客户端（steer follow-up 等）：虚拟线程里只盯 STOP/终态。 */
    public void startHeadlessPump(String sessionId, String assistantMessageId,
            SseStreamContext streamCtx) {
        new TurnStreamPump(streamManager, emitter, sseFrameWriter)
                .startHeadless(sessionId, assistantMessageId, streamCtx);
    }
    private static final Set<String> QUICK_ANSWER_TIMELINE_TOOLS = Set.of(
            "query_understand", "knowledge_search", "attachment_parsing", "image_analysis");
    public void registerQuickAnswerTimelineRecorder(EventBusInterface bus, Message msg,
            TurnMessageGate messageGate) {
        if (bus == null || msg == null) {
            return;
        }
        Map<String, Map<String, Object>> pending = new LinkedHashMap<>();
        Map<String, Long> startedAt = new LinkedHashMap<>();
        bus.on(EventType.EVENT_AGENT_TOOL_CALL, evt -> {
            if (evt.getData() instanceof AgentToolCallData data
                    && QUICK_ANSWER_TIMELINE_TOOLS.contains(data.getToolName())
                    && !data.getToolCallId().isEmpty()) {
                synchronized (messageGate.monitor()) {
                    pending.put(data.getToolCallId(), data.getArguments());
                    startedAt.put(data.getToolCallId(), System.currentTimeMillis());
                }
            }
        });
        bus.on(EventType.EVENT_AGENT_TOOL_RESULT, evt -> {
            if (evt.getData() instanceof AgentToolResultData data
                    && QUICK_ANSWER_TIMELINE_TOOLS.contains(data.getToolName())
                    && !data.getToolCallId().isEmpty()) {
                synchronized (messageGate.monitor()) {
                    Map<String, Object> args = pending.remove(data.getToolCallId());
                    Long started = startedAt.remove(data.getToolCallId());
                    long duration = data.getDurationMs();
                    if (duration == 0 && started != null) {
                        duration = System.currentTimeMillis() - started;
                    }
                    AgentToolCall call = new AgentToolCall();
                    call.setId("pipeline:" + data.getToolCallId());
                    call.setName(data.getToolName());
                    call.setArgs(args);
                    ToolResult result = new ToolResult();
                    result.setSuccess(data.isSuccess());
                    result.setOutput(data.getOutput());
                    result.setError(data.getError());
                    result.setData(data.getData());
                    call.setResult(result);
                    call.setDuration(duration);
                    appendQuickAnswerToolCall(msg, call);
                }
            }
        });
    }
    private static void appendQuickAnswerToolCall(Message msg, AgentToolCall call) {
        AgentStep step = QaTurnSupport.ensureQuickAnswerStep(msg);
        if (step.getToolCalls() != null) {
            for (int i = 0; i < step.getToolCalls().size(); i++) {
                if (step.getToolCalls().get(i).getId().equals(call.getId())) {
                    step.getToolCalls().set(i, call);
                    return;
                }
            }
        }
        if (step.getToolCalls() == null) {
            step.setToolCalls(new ArrayList<>());
        }
        step.getToolCalls().add(call);
    }
}
