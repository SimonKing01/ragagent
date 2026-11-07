package com.ragagent.im.service;

import com.ragagent.contract.agent.AgentLookup;
import com.ragagent.contract.event.EventBusFactory;
import com.ragagent.contract.event.EventBusInterface;
import com.ragagent.contract.event.payload.AgentCompleteData;
import com.ragagent.contract.event.payload.AgentFinalAnswerData;
import com.ragagent.contract.event.payload.ErrorData;
import com.ragagent.contract.session.MessageView;
import com.ragagent.contract.session.QaRequestPayload;
import com.ragagent.contract.session.SessionView;
import com.ragagent.im.runtime.AdapterInterfaces.FullOutputProgressSender;
import com.ragagent.im.runtime.AdapterInterfaces.StreamSender;
import com.ragagent.im.runtime.ImFormat;
import com.ragagent.im.runtime.IncomingMessage;
import com.ragagent.im.runtime.QaQueue;
import com.ragagent.im.runtime.ReplyMessage;
import com.ragagent.im.service.ImTurnOrchestrator.InflightEntry;
import com.ragagent.im.service.ImTurnOrchestrator.QaAttach;
import com.ragagent.im.service.ImTurnOrchestrator.QaOutcome;
import com.ragagent.im.service.ImTurnOrchestrator.QaTask;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.platform.event.EventType;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * IM QA 执行编排：队列 worker 的租户上下文绑定与在途登记、full-output / 流式 /
 * 非流式三条发送路径、同步 runQA（事件收集 + 消息落库）。
 */
final class ImQaRunner {

    private static final Logger log = LoggerFactory.getLogger(ImQaRunner.class);

    private final ImTurnOrchestrator service;
    private final EventBusFactory eventBusFactory;

    ImQaRunner(ImTurnOrchestrator service, EventBusFactory eventBusFactory) {
        this.service = service;
        this.eventBusFactory = eventBusFactory;
    }

    // ── QA 执行 ──────────────────────────────────────────────────────────

    void executeQARequest(QaTask task) {
        // 队列 worker 是独立虚拟线程：租户上下文必须显式携带。
        TenantContext.set(task.tenantId(), new TenantContext.Principal(
                TenantContext.PrincipalTypes.IM_USER, "system-" + task.tenantId()),
                "viewer", false, "system-" + task.tenantId(), false);
        QaQueue.QaRequest req = task.queueReq();
        InflightEntry entry = new InflightEntry(req, () -> {
            req.cancel();
            return true;
        });
        service.inflight.put(task.userKey, entry);
        try {
            // 执行前 /stop（排队期间由别的实例发起）直接跳过。
            if (service.checkAndClearStopMarker(task.userKey)) {
                log.info("[IM] Request cancelled by remote /stop before execution: {}", task.userKey);
                return;
            }
            // 排队期间被 /stop 的直接跳过。
            if (req.isCancelled()) {
                return;
            }
            IncomingMessage msg = task.msg;
            QaAttach attach = task.attach(entry);
            // 附件准备（下载 + 解析）；失败按固定文案回复并终止。
            ImAttachmentPreparer.Prepared prepared;
            try {
                prepared = service.attachmentPreparer.prepare(msg, attach.adapter());
            } catch (Exception e) {
                log.warn("[IM] attachment preparation failed", e);
                service.sendReplyQuiet(attach.adapter(), msg,
                        new ReplyMessage("❌ 无法读取此附件，请重试或改用文字描述。", false, true));
                return;
            }
            // 附件异步入渠道绑定的知识库（后台工作，无用户可见通知）
            service.ingestAttachmentToKnowledgeBase(attach.channel(), prepared);
            boolean streamDisabled = "full".equals(attach.channel().getOutputMode());

            if (streamDisabled) {
                if (attach.adapter() instanceof FullOutputProgressSender progress
                        && progress.supportsFullOutputProgress()) {
                    try {
                        handleMessageFullOutput(msg, attach, prepared, progress);
                    } catch (Exception e) {
                        log.error("[IM] Full-output QA failed", e);
                    }
                    return;
                }
            } else if (attach.adapter() instanceof StreamSender streamer) {
                try {
                    service.streamPipeline.handleMessageStream(msg, attach, prepared, streamer);
                } catch (Exception e) {
                    log.error("[IM] Stream QA failed", e);
                }
                return;
            }

            // 非流式兜底：收集完整答案再发。
            QaOutcome outcome = runQA(attach, prepared);
            String answer = outcome.answer();
            if (outcome.error() != null) {
                log.error("[IM] QA failed: {}, sending fallback reply", outcome.error());
                answer = ImFormat.imQAFailureReply(outcome.error());
            }
            String display = service.formatIMOutboundAnswerOrFallback(answer,
                    attach.adapter().rendersMarkdownImages());
            service.sendReplyQuiet(attach.adapter(), msg, new ReplyMessage(display, false, true));
            log.info("[IM] Reply sent: channel={} platform={} user={} answer_len={}",
                    attach.channelId(), msg.platform, msg.userId, answer.length());
        } finally {
            service.inflight.remove(task.userKey);
            service.unbindInflight(task.userKey);
            // 释放取消标志：让本轮的 stop watcher 退出
            req.cancel();
            TenantContext.clear();
        }
    }


    void handleMessageFullOutput(IncomingMessage msg, QaAttach attach,
            ImAttachmentPreparer.Prepared prepared, FullOutputProgressSender streamer)
            throws Exception {
        String streamId;
        try {
            streamId = streamer.startStream(msg);
        } catch (Exception e) {
            log.warn("[IM] StartStream failed for full output, falling back", e);
            runFallbackNonStream(attach, prepared);
            return;
        }
        QaOutcome outcome = runQA(attach, prepared);
        String answer = outcome.answer();
        if (outcome.error() != null) {
            log.error("[IM] Full-output QA failed: {}, sending fallback reply", outcome.error());
            answer = ImFormat.imQAFailureReply(outcome.error());
        }
        String finalContent = service.formatIMOutboundAnswerOrFallback(answer,
                attach.adapter().rendersMarkdownImages());
        Exception finalizeErr = null;
        try {
            streamer.finalizeStream(msg, streamId, finalContent);
        } catch (Exception e) {
            finalizeErr = e;
            log.warn("[IM] FinalizeStream failed for full output", e);
        }
        try {
            streamer.endStream(msg, streamId);
        } catch (Exception e) {
            log.warn("[IM] EndStream failed for full output", e);
        }
        if (finalizeErr != null) {
            service.sendReplyQuiet(attach.adapter(), msg, new ReplyMessage(finalContent, false, true));
        }
    }


    void runFallbackNonStream(QaAttach attach, ImAttachmentPreparer.Prepared prepared) {
        QaOutcome outcome = runQA(attach, prepared);
        String answer = outcome.answer();
        if (outcome.error() != null) {
            answer = ImFormat.imQAFailureReply(outcome.error());
        }
        String display = service.formatIMOutboundAnswerOrFallback(answer,
                attach.adapter().rendersMarkdownImages());
        service.sendReplyQuiet(attach.adapter(), attach.msg(), new ReplyMessage(display, false, true));
    }

    // ── runQA：事件收集 + 消息落库 ────────────────────────────────────────

    QaOutcome runQA(QaAttach attach, ImAttachmentPreparer.Prepared prepared) {
        EventBusInterface eventBus = eventBusFactory.create();
        StringBuilder answerBuilder = new StringBuilder();
        AtomicReference<Exception> qaErr = new AtomicReference<>();
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch complete = new java.util.concurrent.CountDownLatch(1);

        eventBus.on(EventType.EVENT_AGENT_FINAL_ANSWER, evt -> {
            if (!(evt.getData() instanceof AgentFinalAnswerData data)) {
                return;
            }
            String content = data.getContent();
            if (content != null && !content.isEmpty()) {
                synchronized (answerBuilder) {
                    answerBuilder.append(content);
                }
            }
            if (data.isDone()) {
                done.countDown();
            }
        });
        eventBus.on(EventType.EVENT_ERROR, evt -> {
            if (!(evt.getData() instanceof ErrorData data)) {
                return;
            }
            log.error("[IM] QA error: {}", data.getError());
            qaErr.set(new RuntimeException("QA pipeline error: " + data.getError()));
            done.countDown();
            complete.countDown();
        });
        eventBus.on(EventType.EVENT_MCP_OAUTH_REQUIRED, evt -> {
            // OAuth 待授权：IM 无法处理会话内提示 → 汇总成文末提示（此处仅记日志）。
            log.info("[IM] MCP OAuth required: {}", evt.getData());
        });

        AgentLookup.AgentView agent = attach.agent();
        SessionView session = attach.session();
        String requestId = UUID.randomUUID().toString();

        MessageView userMsg = service.qaRequests.createUserMessage(session.getId(), attach.msg().content,
                requestId, prepared.attachments());
        MessageView assistantMsg = service.qaRequests.createAssistantMessage(session.getId(), requestId);
        // 在途登记：跨实例 /stop 的 IDs 映射 + stop watcher
        service.bindInflight(attach, assistantMsg.getId());

        eventBus.on(EventType.EVENT_AGENT_COMPLETE, evt -> {
            if (!(evt.getData() instanceof AgentCompleteData data)) {
                return;
            }
            String finalAnswer = data.getFinalAnswer();
            if (finalAnswer != null && !finalAnswer.isEmpty()) {
                synchronized (answerBuilder) {
                    if (answerBuilder.isEmpty()) {
                        answerBuilder.append(finalAnswer);
                    }
                }
            }
            assistantMsg.setCompleted(true);
            complete.countDown();
        });

        // 租户上下文：IM 回调无 JWT——显式注入合成身份
        // （"system-<tenantID>" + TenantContext.IM_USER principal）。
        long imTenant = attach.channel().getTenantId();
        TenantContext.set(imTenant, new TenantContext.Principal(
                TenantContext.PrincipalTypes.IM_USER, "system-" + imTenant), "viewer",
                false, "system-" + imTenant, false);
        try {
            QaRequestPayload qaReq = service.qaRequests.buildIMQARequest(session, attach.msg().content,
                    assistantMsg.getId(), userMsg.getId(), agent, attach.msg().quote, attach.inflight());
            ImAttachmentPreparer.applyTo(qaReq, prepared);
            // 同步执行（QA 服务内部为虚拟线程管线，事件经 eventBus 回流到上面的订阅）。
            Exception runErr;
            try {
                if (agent != null && ImQaRequests.isAgentMode(agent.configJson())) {
                    service.sessionQaPort.agentQa(qaReq, eventBus);
                } else {
                    service.sessionQaPort.knowledgeQa(qaReq, eventBus);
                }
                runErr = null;
            } catch (Exception e) {
                runErr = e;
            }
            if (runErr != null) {
                qaErr.set(new RuntimeException("QA execution error: " + runErr.getMessage(), runErr));
                done.countDown();
                complete.countDown();
            } else {
                try {
                    // 等待最终帧。
                    if (!done.await(10, java.util.concurrent.TimeUnit.MINUTES)) {
                        qaErr.compareAndSet(null, new java.util.concurrent.TimeoutException("IM QA wait"));
                    }
                    if (agent != null && ImQaRequests.isAgentMode(agent.configJson())) {
                        complete.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        } finally {
            TenantContext.clear();
        }

        String answer;
        synchronized (answerBuilder) {
            answer = answerBuilder.toString();
        }
        Exception err = qaErr.get();
        if (answer.isEmpty() && err != null) {
            return new QaOutcome("", err);
        }
        if (answer.isEmpty()) {
            answer = ImFormat.IM_NO_ANSWER_FALLBACK;
        }
        assistantMsg.setContent(answer);
        assistantMsg.setCompleted(true);
        try {
            service.sessionQaPort.updateMessage(assistantMsg);
        } catch (Exception e) {
            log.warn("[IM] Failed to update assistant message", e);
        }
        return new QaOutcome(answer, null);
    }
}
