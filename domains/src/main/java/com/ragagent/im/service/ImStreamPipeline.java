package com.ragagent.im.service;

import com.ragagent.contract.agent.AgentLookup;
import com.ragagent.contract.event.Event;
import com.ragagent.contract.event.EventBusFactory;
import com.ragagent.contract.event.EventBusInterface;
import com.ragagent.contract.event.payload.AgentCompleteData;
import com.ragagent.contract.event.payload.AgentFinalAnswerData;
import com.ragagent.contract.event.payload.AgentThoughtData;
import com.ragagent.contract.event.payload.AgentToolCallData;
import com.ragagent.contract.event.payload.AgentToolResultData;
import com.ragagent.contract.event.payload.ErrorData;
import com.ragagent.contract.session.MessageView;
import com.ragagent.contract.session.QaRequestPayload;
import com.ragagent.contract.session.SessionView;
import com.ragagent.im.runtime.AdapterInterfaces.StreamSender;
import com.ragagent.im.runtime.ImFormat;
import com.ragagent.im.runtime.IncomingMessage;
import com.ragagent.im.runtime.StreamSection;
import com.ragagent.im.runtime.ThinkDisplay;
import com.ragagent.im.runtime.ToolDisplay;
import com.ragagent.im.service.ImTurnOrchestrator.QaAttach;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.platform.event.EventType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * IM 流式回复管线：事件订阅组、流缓冲袋、300ms 冲刷循环与 finalize/收尾落库。
 */
final class ImStreamPipeline {

    private static final Logger log = LoggerFactory.getLogger(ImStreamPipeline.class);

    private final ImTurnOrchestrator service;
    private final EventBusFactory eventBusFactory;

    ImStreamPipeline(ImTurnOrchestrator service, EventBusFactory eventBusFactory) {
        this.service = service;
        this.eventBusFactory = eventBusFactory;
    }

    void handleMessageStream(IncomingMessage msg, QaAttach attach,
            ImAttachmentPreparer.Prepared prepared, StreamSender streamer) throws Exception {
        String streamId;
        try {
            streamId = streamer.startStream(msg);
        } catch (Exception e) {
            log.warn("[IM] StartStream failed, falling back to non-streaming", e);
            service.runFallbackNonStream(attach, prepared);
            return;
        }

        EventBusInterface eventBus = eventBusFactory.create();
        StreamBuffers buf = new StreamBuffers();
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch complete = new java.util.concurrent.CountDownLatch(1);

        AgentLookup.AgentView agent = attach.agent();
        boolean useAgent = agent != null && ImQaRequests.isAgentMode(agent.configJson());
        // 能不能渲染 markdown 图片由**渠道适配器**决定（流式 sender 只是传输通道，
        // 不是 Adapter；此前按 streamer 判能力恒为 false，飞书卡片被误降级成链接）
        boolean rendersImages = attach.adapter().rendersMarkdownImages();

        subscribeStreamEvents(eventBus, buf, done, complete, useAgent);

        SessionView session = attach.session();
        String requestId = UUID.randomUUID().toString();
        MessageView userMsg = service.qaRequests.createUserMessage(session.getId(), msg.content,
                requestId, prepared.attachments());
        MessageView assistantMsg = service.qaRequests.createAssistantMessage(session.getId(), requestId);
        buf.assistantMessage = assistantMsg;
        // 在途登记：跨实例 /stop 的 IDs 映射 + stop watcher（与 runQA 路径同款）
        service.bindInflight(attach, assistantMsg.getId());

        // 租户上下文（同 runQA）。
        long tenantId = attach.channel().getTenantId();
        TenantContext.set(tenantId, new TenantContext.Principal(
                TenantContext.PrincipalTypes.IM_USER, "system-" + tenantId), "viewer",
                false, "system-" + tenantId, false);
        try {
            QaRequestPayload qaReq = service.qaRequests.buildIMQARequest(session, msg.content,
                    assistantMsg.getId(), userMsg.getId(), agent, msg.quote, attach.inflight());
            ImAttachmentPreparer.applyTo(qaReq, prepared);
            Exception runErr;
            try {
                if (useAgent) {
                    service.sessionQaPort.agentQa(qaReq, eventBus);
                } else {
                    service.sessionQaPort.knowledgeQa(qaReq, eventBus);
                }
                runErr = null;
            } catch (Exception e) {
                runErr = e;
            }
            if (runErr != null) {
                log.error("[IM] QA stream execution error", runErr);
                buf.qaErr = new RuntimeException("QA execution error: " + runErr.getMessage(), runErr);
                done.countDown();
                complete.countDown();
            } else {
                // 冲刷循环：300ms 批量把缓冲推给平台（holdback 防半个引用/标签）。
                long flushInterval = 300;
                long deadlineWait = java.util.concurrent.TimeUnit.MINUTES.toMillis(10);
                long start = System.currentTimeMillis();
                while (true) {
                    if (done.await(flushInterval, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        break;
                    }
                    flushStream(msg, streamer, streamId, buf, useAgent, rendersImages);
                    if (System.currentTimeMillis() - start > deadlineWait) {
                        break;
                    }
                }
                if (useAgent) {
                    complete.await(10, java.util.concurrent.TimeUnit.SECONDS);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            TenantContext.clear();
        }

        String resolvedAnswer = buf.pickStoredAnswer();
        ThinkDisplay.IMStreamParts parts = buf.parts(useAgent);
        if (parts.answer.isEmpty()) {
            parts.answer = resolvedAnswer;
        }
        String answer = resolvedAnswer;
        Exception finalErr = buf.qaErr;
        boolean noVisibleContent = !buf.streamedAny && resolvedAnswer.strip().isEmpty();

        String finalDisplay = service.cleanIMContent(
                ThinkDisplay.formatIMFinalFromParts(parts), rendersImages);
        if (noVisibleContent || finalDisplay.isEmpty()) {
            String fallback = finalErr != null ? ImFormat.imQAFailureReply(finalErr)
                    : ImFormat.IM_NO_ANSWER_FALLBACK;
            finalDisplay = fallback;
            if (answer.isEmpty()) {
                answer = fallback;
            }
        }

        try {
            streamer.finalizeStream(msg, streamId, finalDisplay);
        } catch (Exception e) {
            log.warn("[IM] FinalizeStream failed", e);
        }
        try {
            streamer.endStream(msg, streamId);
        } catch (Exception e) {
            log.warn("[IM] EndStream failed", e);
        }
        if (answer.isEmpty()) {
            answer = ImFormat.IM_NO_ANSWER_FALLBACK;
        }
        assistantMsg.setContent(answer);
        assistantMsg.setCompleted(true);
        // 落库需要租户上下文（上面的 finally 已清空），这里重设并在结束时清理；
        // 否则助手消息存不进内容，下一轮读历史时为空。
        TenantContext.set(tenantId, new TenantContext.Principal(
                TenantContext.PrincipalTypes.IM_USER, "system-" + tenantId), "viewer",
                false, "system-" + tenantId, false);
        try {
            service.sessionQaPort.updateMessage(assistantMsg);
        } catch (Exception e) {
            log.warn("[IM] Failed to update assistant message", e);
        } finally {
            TenantContext.clear();
        }
        log.info("[IM] Stream reply sent: platform={} user={} answer_len={}",
                msg.platform, msg.userId, answer.length());
    }

    /** 流缓冲袋（管线内共享的可变状态）。 */
    static final class StreamBuffers {
        final StreamSection reasoningInner = new StreamSection();
        final StreamSection agentInner = new StreamSection();
        final StringBuilder agentLiveAnswer = new StringBuilder();
        final StringBuilder answerOuter = new StringBuilder();
        final StringBuilder answerBuilder = new StringBuilder();
        Exception qaErr;
        boolean agentDone;
        boolean streamedAny;
        MessageView assistantMessage;
        String agentCompleteFinalAnswer = "";
        final Map<String, Boolean> seenToolCalls = new ConcurrentHashMap<>();
        final Map<String, Integer> agentToolIdx = new ConcurrentHashMap<>();
        final Map<String, Integer> pipelineIdx = new ConcurrentHashMap<>();
        final List<ToolDisplay.IMToolStep> agentToolSteps = java.util.Collections.synchronizedList(new ArrayList<>());
        final List<ToolDisplay.IMToolStep> pipelineToolSteps = java.util.Collections.synchronizedList(new ArrayList<>());

        synchronized ThinkDisplay.IMStreamParts parts(boolean useAgent) {
            ThinkDisplay.IMStreamParts p = new ThinkDisplay.IMStreamParts();
            p.mode = useAgent ? ThinkDisplay.IM_STREAM_MODE_AGENT
                    : ThinkDisplay.IM_STREAM_MODE_QUICK_QA;
            p.pipelineToolSteps = List.copyOf(pipelineToolSteps);
            p.reasoningInner = reasoningInner.text();
            p.agentInner = agentInner.text();
            p.agentToolSteps = List.copyOf(agentToolSteps);
            p.liveAnswer = agentLiveAnswer.toString();
            p.answer = answerOuter.toString();
            return p;
        }

        synchronized String pickStoredAnswer() {
            for (String s : List.of(answerBuilder.toString(), answerOuter.toString(),
                    agentLiveAnswer.toString(), agentCompleteFinalAnswer)) {
                if (!s.strip().isEmpty()) {
                    return s;
                }
            }
            return "";
        }

        synchronized void mergeBuffers(String completeFinal) {
            if (!answerBuilder.isEmpty()) {
                return;
            }
            if (!agentLiveAnswer.isEmpty()) {
                String live = agentLiveAnswer.toString();
                answerBuilder.append(live);
                if (answerOuter.isEmpty()) {
                    answerOuter.append(live);
                }
            } else if (!answerOuter.isEmpty()) {
                answerBuilder.append(answerOuter);
            } else if (!completeFinal.strip().isEmpty()) {
                answerBuilder.append(completeFinal);
                answerOuter.append(completeFinal);
            }
        }
    }

    /** 事件订阅组。 */
    private void subscribeStreamEvents(EventBusInterface eventBus, StreamBuffers buf,
            java.util.concurrent.CountDownLatch done,
            java.util.concurrent.CountDownLatch complete, boolean useAgent) {
        eventBus.on(EventType.EVENT_AGENT_FINAL_ANSWER, evt -> {
            if (!(evt.getData() instanceof AgentFinalAnswerData data)) {
                return;
            }
            String content = data.getContent();
            synchronized (buf) {
                if (useAgent && !buf.agentDone) {
                    if (content != null && !content.isEmpty()) {
                        buf.agentLiveAnswer.append(content);
                        buf.streamedAny = true;
                    }
                } else if (content != null) {
                    buf.answerOuter.append(content);
                    buf.answerBuilder.append(content);
                    buf.streamedAny = true;
                }
            }
            if (data.isDone()) {
                done.countDown();
            }
        });
        eventBus.on(EventType.EVENT_ERROR, evt -> {
            String text = evt.getData() instanceof ErrorData d
                    ? d.getError() : String.valueOf(evt.getData());
            synchronized (buf) {
                buf.qaErr = new RuntimeException("QA pipeline error: " + text);
            }
            done.countDown();
            complete.countDown();
        });
        eventBus.on(EventType.EVENT_AGENT_COMPLETE, evt -> {
            if (!(evt.getData() instanceof AgentCompleteData data)) {
                return;
            }
            synchronized (buf) {
                buf.agentDone = true;
                buf.agentCompleteFinalAnswer = data.getFinalAnswer() == null ? "" : data.getFinalAnswer();
                if (buf.assistantMessage != null) {
                    buf.assistantMessage.setCompleted(true);
                }
                buf.mergeBuffers(buf.agentCompleteFinalAnswer);
            }
            complete.countDown();
        });
        eventBus.on(EventType.EVENT_AGENT_REFERENCES, evt -> {
            // 引用进 assistant 消息（web 端的交互 UI 在 IM 无意义，不入最终文本）。
        });
        eventBus.on(EventType.EVENT_AGENT_THOUGHT, evt -> {
            String content = evt.getData() instanceof AgentThoughtData d
                    && d.getContent() != null ? d.getContent() : "";
            synchronized (buf) {
                if (content.isEmpty()) {
                    return;
                }
                if (useAgent) {
                    buf.agentInner.write(content);
                    buf.streamedAny = true;
                } else {
                    buf.reasoningInner.write(content);
                    buf.streamedAny = true;
                }
            }
        });
        eventBus.on(EventType.EVENT_AGENT_TOOL_CALL, evt -> {
            ToolEvent t = toolOf(evt);
            if (t == null || !ImFormat.isToolVisibleToUser(t.toolName)) {
                return;
            }
            synchronized (buf) {
                if (buf.seenToolCalls.putIfAbsent(t.toolCallId, Boolean.TRUE) != null) {
                    if (useAgent) {
                        upsert(buf.agentToolSteps, buf.agentToolIdx, t.toolCallId, step -> {
                            if (t.arguments != null) {
                                step.arguments = t.arguments;
                            }
                        });
                        buf.streamedAny = true;
                    }
                    return;
                }
                if (!useAgent && ThinkDisplay.isRAGPipelineToolName(t.toolName)) {
                    upsert(buf.pipelineToolSteps, buf.pipelineIdx, t.toolCallId, step -> {
                        step.toolName = t.toolName;
                        step.pending = true;
                        step.arguments = t.arguments;
                    });
                    buf.streamedAny = true;
                } else if (useAgent) {
                    // 乐观答案收回 think 块（Web: superseded preamble）。
                    if (!buf.agentLiveAnswer.isEmpty()) {
                        if (!buf.agentInner.text().isEmpty()) {
                            buf.agentInner.ensureNewlineBefore();
                        }
                        buf.agentInner.write(buf.agentLiveAnswer.toString());
                        buf.agentLiveAnswer.setLength(0);
                    }
                    upsert(buf.agentToolSteps, buf.agentToolIdx, t.toolCallId, step -> {
                        step.toolName = t.toolName;
                        step.pending = true;
                        step.arguments = t.arguments;
                    });
                    buf.streamedAny = true;
                }
            }
        });
        eventBus.on(EventType.EVENT_AGENT_TOOL_RESULT, evt -> {
            ToolEvent t = toolOf(evt);
            if (t == null || !ImFormat.isToolVisibleToUser(t.toolName)) {
                return;
            }
            synchronized (buf) {
                if (!useAgent && ThinkDisplay.isRAGPipelineToolName(t.toolName)) {
                    upsert(buf.pipelineToolSteps, buf.pipelineIdx, t.toolCallId, step -> {
                        step.toolName = t.toolName;
                        step.pending = false;
                        step.success = t.success;
                        step.data = t.data;
                        step.output = t.output;
                    });
                    buf.streamedAny = true;
                } else if (useAgent) {
                    upsert(buf.agentToolSteps, buf.agentToolIdx, t.toolCallId, step -> {
                        step.toolName = t.toolName;
                        step.pending = false;
                        step.success = t.success;
                        step.data = t.data;
                        step.output = t.output;
                    });
                    buf.streamedAny = true;
                }
            }
        });
    }

    /** 工具事件的统一视图（event.Data 的字段面）。 */
    private record ToolEvent(String toolCallId, String toolName, boolean success,
            Map<String, Object> arguments, Map<String, Object> data, String output) {
    }

    private static ToolEvent toolOf(Event evt) {
        Object d = evt.getData();
        if (d instanceof AgentToolCallData c) {
            return new ToolEvent(c.getToolCallId(), c.getToolName(), false,
                    c.getArguments(), null, "");
        }
        if (d instanceof AgentToolResultData r) {
            return new ToolEvent(r.getToolCallId(), r.getToolName(), r.isSuccess(),
                    null, r.getData(), r.getOutput() == null ? "" : r.getOutput());
        }
        return null;
    }

    /** 工具步骤 upsert（有则更新、无则插入）。 */
    private static void upsert(List<ToolDisplay.IMToolStep> steps, Map<String, Integer> index,
            String id, java.util.function.Consumer<ToolDisplay.IMToolStep> update) {
        Integer i = index.get(id);
        if (i != null) {
            update.accept(steps.get(i));
            return;
        }
        ToolDisplay.IMToolStep step = new ToolDisplay.IMToolStep(id, "");
        update.accept(step);
        index.put(id, steps.size());
        steps.add(step);
    }

    /** 冲刷一次中间帧。 */
    private void flushStream(IncomingMessage msg, StreamSender streamer, String streamId,
            StreamBuffers buf, boolean useAgent, boolean rendersImages) {
        ThinkDisplay.IMStreamParts parts;
        boolean agentRunning;
        synchronized (buf) {
            parts = buf.parts(useAgent);
            agentRunning = useAgent && !buf.agentDone;
        }
        String displaySource = ThinkDisplay.formatIMIntermediateFromParts(parts, agentRunning);
        if (displaySource.isEmpty()) {
            return;
        }
        int cut = ImFormat.holdbackCutoff(displaySource);
        if (cut < displaySource.length()) {
            displaySource = displaySource.substring(0, cut);
        }
        String display = service.cleanIMContent(displaySource, rendersImages);
        try {
            streamer.updateStreamContent(msg, streamId, display);
        } catch (Exception e) {
            log.warn("[IM] UpdateStreamContent failed", e);
        }
    }

}
