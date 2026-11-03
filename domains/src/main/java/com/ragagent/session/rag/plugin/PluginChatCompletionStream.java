package com.ragagent.session.rag.plugin;


import com.ragagent.contract.event.Event;
import com.ragagent.contract.event.EventBusInterface;
import com.ragagent.contract.event.payload.AgentFinalAnswerData;
import com.ragagent.contract.event.payload.AgentThoughtData;
import com.ragagent.contract.event.payload.ErrorData;
import com.ragagent.contract.model.ChatOptions;
import com.ragagent.contract.model.ChatStream;
import com.ragagent.contract.model.LlmChatClient;
import com.ragagent.contract.model.ResponseType;
import com.ragagent.contract.model.StreamResponse;
import com.ragagent.contract.pipeline.PipelinePorts;
import com.ragagent.modelcontext.Registry;
import com.ragagent.modelcontext.StreamDecoder;
import com.ragagent.platform.access.TenantContextSnapshot;
import com.ragagent.platform.access.TenantScope;
import com.ragagent.platform.event.EventType;
import com.ragagent.session.rag.PipelineCommon;
import com.ragagent.session.rag.PipelineEventType;
import com.ragagent.session.rag.PipelineLog;
import com.ragagent.session.rag.PipelineState;
import com.ragagent.session.rag.support.ReferencesSupport;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CHAT_COMPLETION_STREAM 阶段插件：
 * 流式生成——组消息 → 建流 → 消费流 chunk 并直接 emit 事件。
 *
 * <h2>chunk → 事件路由</h2>
 * <ul>
 *   <li>ERROR chunk → EventError（stage=chat_completion_stream）后继续消费；</li>
 *   <li>THINKING chunk → 经独立 StreamDecoder 馈给 EventAgentThought（同一 thinkingID）；
 *       done 时 flush 后 closeThinking（open=false 才不发 done 事件）；</li>
 *   <li>ANSWER chunk → 经独立 StreamDecoder 馈给 EventAgentFinalAnswer（同一 answerID）；
 *       done=true 后后续 ANSWER 重复完成被丢弃（complete 之后不得再出答案）。</li>
 * </ul>
 *
 * <p>流终止约定：消费在「done=true 且非 THINKING」的元素处理完处收束（生产者的终态元素恒
 * ANSWER/ERROR+done；THINKING+done 是生产者中途补的 thinking-done 标记，其后仍有
 * 分片，须继续消费）。收束路径 flushDecoders + closeThinking（悬挂句柄尾巴不能丢）。</p>
 *
 * <p>提前离开（120s 无新 chunk / 中断）时 {@code close()} 句柄——A3：不关会让 provider
 * 连接继续读、并发 permit 继续被占。</p>
 */
public final class PluginChatCompletionStream implements Plugin {

    private final PipelinePorts.ModelService modelService;

    public PluginChatCompletionStream(PipelinePorts.ModelService modelService) {
        this.modelService = modelService;
    }

    @Override
    public PipelineEventType[] activationEvents() {
        return new PipelineEventType[] {PipelineEventType.CHAT_COMPLETION_STREAM};
    }

    @Override
    public PluginError onEvent(PipelineEventType eventType, PipelineState chatManage, Plugin.Chain next) {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("sessionId", chatManage.getSessionId());
        in.put("user_question", chatManage.getAnswer().getUserContent());
        in.put("history_rounds", chatManage.getHistory() == null ? 0 : chatManage.getHistory().size());
        in.put("chat_model", chatManage.getChatModelId());
        PipelineLog.info("Stream", "input", in);

        PipelineCommon.PreparedChatModel prepared;
        try {
            prepared = PipelineCommon.prepareChatModel(modelService, chatManage);
        } catch (RuntimeException e) {
            return PluginError.GET_CHAT_MODEL.withError(e);
        }
        LlmChatClient chatModel = prepared.chatModel();
        ChatOptions opt = prepared.options();

        var assembly = ReferencesSupport.prepareMessagesWithModelContext(chatManage);
        var chatMessages = assembly.registry().encodeMessages(assembly.messages());
        Map<String, Object> mr = new LinkedHashMap<>();
        mr.put("message_count", chatMessages.size());
        mr.put("system_prompt", chatMessages.get(0).getContent());
        PipelineLog.info("Stream", "messages_ready", mr);
        Map<String, Object> um = new LinkedHashMap<>();
        um.put("content", chatMessages.get(chatMessages.size() - 1).getContent());
        PipelineLog.info("Stream", "user_message", um);

        // 事件流必需 EventBus
        if (chatManage.getRuntime().getEventBus() == null) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("sessionId", chatManage.getSessionId());
            PipelineLog.error("Stream", "eventbus_missing", f);
            return PluginError.MODEL_CALL.withError(new RuntimeException("EventBus is required for streaming"));
        }
        EventBusInterface eventBus = chatManage.getRuntime().getEventBus();

        Map<String, Object> er = new LinkedHashMap<>();
        er.put("sessionId", chatManage.getSessionId());
        PipelineLog.info("Stream", "eventbus_ready", er);

        // 建流（建立失败立即抛错）
        ChatStream responseQueue;
        Map<String, Object> mc = new LinkedHashMap<>();
        mc.put("chat_model", chatManage.getChatModelId());
        PipelineLog.info("Stream", "model_call", mc);
        try {
            responseQueue = chatModel.chatStream(chatMessages, opt);
        } catch (RuntimeException e) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("chat_model", chatManage.getChatModelId());
            f.put("error", e.getMessage());
            PipelineLog.error("Stream", "model_call", f);
            return PluginError.MODEL_CALL.withError(e);
        }
        if (responseQueue == null) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("chat_model", chatManage.getChatModelId());
            f.put("error", "nil_channel");
            PipelineLog.error("Stream", "model_call", f);
            return PluginError.MODEL_CALL.withError(new RuntimeException("chat stream returned nil channel"));
        }

        Map<String, Object> ms = new LinkedHashMap<>();
        ms.put("sessionId", chatManage.getSessionId());
        PipelineLog.info("Stream", "model_started", ms);

        // 消费线程（虚拟线程）
        // 纪律 #1（A5）：ThreadLocal 不跨线程——必须在本线程 capture、在消费线程 replay，
        // 否则消费线程看到空身份（owner 推导成 ""、下游索引/建议/记忆全部 SessionNotFound）。
        final PipelineState cm = chatManage;
        final ChatStream queue = responseQueue;
        final Registry modelContext = assembly.registry();
        final TenantContextSnapshot identity = TenantContextSnapshot.capture();
        Thread.ofVirtual().start(() -> TenantScope.run(identity,
                () -> consumeStream(cm, eventBus, modelContext, queue)));

        return next.next();
    }

    /** 流消费循环（独立虚拟线程上执行）。 */
    private static void consumeStream(PipelineState chatManage, EventBusInterface eventBus,
                                      Registry modelContext,
                                      ChatStream responseQueue) {
        StreamDecoder answerDecoder = modelContext.streamDecoder();
        StreamDecoder thinkingDecoder = modelContext.streamDecoder();
        String thinkingId = String.format("%s-thinking", UUID.randomUUID().toString().substring(0, 8));
        String answerId = String.format("%s-answer", UUID.randomUUID().toString().substring(0, 8));
        AtomicBoolean thinkingOpen = new AtomicBoolean(false);
        AtomicBoolean answerCompleted = new AtomicBoolean(false);

        while (true) {
            StreamResponse response = takeQuietly(responseQueue);
            if (response == null) {
                // 队列结束（生产者关闭 / 120s 无新 chunk / 线程中断）：收束。
                // 终态帧必须补：轮侧只认「答案 done」完成消息并发 COMPLETE，缺了它
                // assistant 会停在未完成、SSE 永远轮询（A1：超时与断流路径实测如此）。
                //
                // A3：这是"提前离开"（不是读到终态）⇒ 关流，让 provider 调用与 permit 一起停。
                responseQueue.close();
                endStream(eventBus, chatManage, thinkingId, answerId,
                        thinkingDecoder, answerDecoder, thinkingOpen, answerCompleted, true);
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("sessionId", chatManage.getSessionId());
                PipelineLog.info("Stream", "channel_close", f);
                return;
            }

            if (ResponseType.ERROR == response.getResponseType()) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("sessionId", chatManage.getSessionId());
                f.put("error", response.getContent());
                PipelineLog.error("Stream", "stream_error", f);
                Event errEvt = new Event();
                errEvt.setId(String.format("%s-error", UUID.randomUUID().toString().substring(0, 8)));
                errEvt.setType(EventType.EVENT_ERROR);
                errEvt.setSessionId(chatManage.getSessionId());
                ErrorData errData = new ErrorData();
                errData.setError(response.getContent());
                errData.setStage("chat_completion_stream");
                errData.setSessionId(chatManage.getSessionId());
                errEvt.setData(errData);
                try {
                    eventBus.emit(errEvt);
                } catch (RuntimeException ignored) {
                    // 发送失败忽略
                }
                if (response.isDone()) {
                    // ERROR+done 是终态元素：收束并退出（错误事件已先发；终态帧同样要补）
                    endStream(eventBus, chatManage, thinkingId, answerId,
                            thinkingDecoder, answerDecoder, thinkingOpen, answerCompleted, true);
                    return;
                }
                continue;
            }

            if (ResponseType.THINKING == response.getResponseType()) {
                String content = thinkingDecoder.feed(response.getContent());
                if (response.isDone()) {
                    content += thinkingDecoder.flush();
                }
                if (!content.isEmpty()) {
                    thinkingOpen.set(true);
                    Event evt = new Event();
                    evt.setId(thinkingId);
                    evt.setType(EventType.EVENT_AGENT_THOUGHT);
                    evt.setSessionId(chatManage.getSessionId());
                    AgentThoughtData data = new AgentThoughtData();
                    data.setContent(content);
                    data.setDone(false);
                    evt.setData(data);
                    try {
                        eventBus.emit(evt);
                    } catch (RuntimeException ignored) {
                    }
                }
                if (response.isDone()) {
                    closeThinking(eventBus, chatManage, thinkingId, thinkingOpen);
                }
                // THINKING+done 是生产者中途补的 thinking-done 标记，其后仍有分片，继续消费
                continue;
            }

            if (ResponseType.ANSWER == response.getResponseType()) {
                // 重复的完成分片丢弃（终态之后不得再出答案）
                if (answerCompleted.get()) {
                    continue;
                }
                String content = answerDecoder.feed(response.getContent());
                if (response.isDone()) {
                    content += answerDecoder.flush();
                    answerCompleted.set(true);
                }
                closeThinking(eventBus, chatManage, thinkingId, thinkingOpen);
                Event evt = new Event();
                evt.setId(answerId);
                evt.setType(EventType.EVENT_AGENT_FINAL_ANSWER);
                evt.setSessionId(chatManage.getSessionId());
                AgentFinalAnswerData data = new AgentFinalAnswerData();
                data.setContent(content);
                data.setDone(response.isDone());
                evt.setData(data);
                try {
                    eventBus.emit(evt);
                } catch (RuntimeException ignored) {
                }
                if (response.isDone()) {
                    // ANSWER+done 是终态元素：flush 尾巴后收束
                    flushDecoders(eventBus, chatManage, thinkingId, answerId, thinkingDecoder, answerDecoder);
                    closeThinking(eventBus, chatManage, thinkingId, thinkingOpen);
                    return;
                }
            }
        }
    }

    /**
     * 异常收束的统一出口：flush 尾帧 → **补终态答案帧**（done=true，幂等） → 关思考块。
     *
     * <p>A1：轮的生命周期挂在「答案 done」上（QaTurnExecutor 的 FINAL_ANSWER 处理器只在
     * done 时完成消息并发 COMPLETE）。异常收束（队列关闭 / 空闲超时 / ERROR+done）原先
     * 只 flush 不补终态 ⇒ assistant 停在未完成、SSE 永远轮询。</p>
     */
    static void endStream(EventBusInterface eventBus, PipelineState chatManage,
                                  String thinkingId, String answerId,
                                  StreamDecoder thinkingDecoder, StreamDecoder answerDecoder,
                                  AtomicBoolean thinkingOpen, AtomicBoolean answerCompleted,
                                  boolean failed) {
        flushDecoders(eventBus, chatManage, thinkingId, answerId, thinkingDecoder, answerDecoder);
        emitTerminalAnswer(eventBus, chatManage, answerId, answerCompleted, failed);
        closeThinking(eventBus, chatManage, thinkingId, thinkingOpen);
    }

    /**
     * 补一帧终态答案（空内容 + done=true）；已终态则不重复发。
     * {@code failed=true} 时带 outcome=FAILED：终态帧只表示流关闭，收尾方据此判失败。
     */
    static void emitTerminalAnswer(EventBusInterface eventBus, PipelineState chatManage,
                                           String answerId, AtomicBoolean answerCompleted, boolean failed) {
        if (!answerCompleted.compareAndSet(false, true)) {
            return;
        }
        Event evt = new Event();
        evt.setId(answerId);
        evt.setType(EventType.EVENT_AGENT_FINAL_ANSWER);
        evt.setSessionId(chatManage.getSessionId());
        AgentFinalAnswerData data = new AgentFinalAnswerData();
        data.setContent("");
        data.setDone(true);
        if (failed) {
            data.setOutcome("FAILED");
        }
        evt.setData(data);
        try {
            eventBus.emit(evt);
        } catch (RuntimeException ignored) {
        }
    }

    /** 阻塞取队列元素（2 分钟无新 chunk 视为收束）。 */
    private static StreamResponse takeQuietly(ChatStream queue) {
        try {
            return queue.poll(120, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static void closeThinking(EventBusInterface eventBus, PipelineState chatManage,
                                      String thinkingId, AtomicBoolean thinkingOpen) {
        if (!thinkingOpen.compareAndSet(true, false)) {
            return;
        }
        Event evt = new Event();
        evt.setId(thinkingId);
        evt.setType(EventType.EVENT_AGENT_THOUGHT);
        evt.setSessionId(chatManage.getSessionId());
        AgentThoughtData data = new AgentThoughtData();
        data.setDone(true);
        evt.setData(data);
        try {
            eventBus.emit(evt);
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * 流解码器扣留的句柄尾巴在此放出（引用跨分片桥接）；
     * 正常收束与取消路径都必须调用。
     */
    private static void flushDecoders(EventBusInterface eventBus, PipelineState chatManage,
                                      String thinkingId, String answerId,
                                      StreamDecoder thinkingDecoder, StreamDecoder answerDecoder) {
        String thinkingTail = thinkingDecoder.flush();
        if (!thinkingTail.isEmpty()) {
            Event evt = new Event();
            evt.setId(thinkingId);
            evt.setType(EventType.EVENT_AGENT_THOUGHT);
            evt.setSessionId(chatManage.getSessionId());
            AgentThoughtData data = new AgentThoughtData();
            data.setContent(thinkingTail);
            evt.setData(data);
            try {
                eventBus.emit(evt);
            } catch (RuntimeException ignored) {
            }
        }
        String answerTail = answerDecoder.flush();
        if (!answerTail.isEmpty()) {
            Event evt = new Event();
            evt.setId(answerId);
            evt.setType(EventType.EVENT_AGENT_FINAL_ANSWER);
            evt.setSessionId(chatManage.getSessionId());
            AgentFinalAnswerData data = new AgentFinalAnswerData();
            data.setContent(answerTail);
            evt.setData(data);
            try {
                eventBus.emit(evt);
            } catch (RuntimeException ignored) {
            }
        }
    }
}
