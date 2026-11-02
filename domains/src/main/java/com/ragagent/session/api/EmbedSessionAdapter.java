package com.ragagent.session.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.contract.error.AppError;
import com.ragagent.contract.error.BizException;
import com.ragagent.contract.session.EmbedReply;
import com.ragagent.contract.session.EmbedSessionPort;
import com.ragagent.contract.session.MessageSuggestionView;
import com.ragagent.contract.session.MessageView;
import com.ragagent.contract.session.SseSink;
import com.ragagent.platform.web.JsonMappers;
import com.ragagent.session.conversation.MessageHistoryService;
import com.ragagent.session.conversation.SessionStopService;
import com.ragagent.session.domain.message.Message;
import com.ragagent.session.domain.message.MessageSuggestionSet;
import com.ragagent.session.dto.StopSessionRequest;
import com.ragagent.session.qa.QaEntryRunner;
import com.ragagent.session.service.suggestion.MessageSuggestionFacade;
import java.io.IOException;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@link EmbedSessionPort} 的会话域实现：逐参数转发到与 HTTP 面**同一套用例层**
 * （QA 入口 {@link QaEntryRunner}、消息历史 {@link MessageHistoryService}、
 * 停止 {@link SessionStopService}、追问建议 {@link MessageSuggestionFacade}
 * 的双面门面），不新增校验、不改错误口径——两个面语义恒一致。
 *
 * <p>原先逐参数回调控制器公共方法（api → controller 方向倒挂），本类改为经用例层。</p>
 */
@Component
public class EmbedSessionAdapter implements EmbedSessionPort {

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    private final QaEntryRunner qaEntryRunner;
    private final MessageHistoryService messageHistory;
    private final SessionStopService sessionStop;
    private final MessageSuggestionFacade suggestionFacade;

    public EmbedSessionAdapter(QaEntryRunner qaEntryRunner,
                               MessageHistoryService messageHistory,
                               SessionStopService sessionStop,
                               MessageSuggestionFacade suggestionFacade) {
        this.qaEntryRunner = qaEntryRunner;
        this.messageHistory = messageHistory;
        this.sessionStop = sessionStop;
        this.suggestionFacade = suggestionFacade;
    }

    @Override
    public void knowledgeQa(String sessionId, String rawBody, String resourceUrls,
            SseSink sink) throws IOException {
        qaEntryRunner.runKnowledgeQA(sessionId, rawBody, resourceUrls, sink);
    }

    @Override
    public void agentQa(String sessionId, String rawBody, String resourceUrls,
            SseSink sink) throws IOException {
        qaEntryRunner.runAgentQA(sessionId, rawBody, resourceUrls, sink);
    }

    @Override
    public EmbedReply<List<MessageView>> loadMessages(String sessionId, String limit,
            String beforeTime, String resourceUrls) {
        List<Message> messages = messageHistory.loadMessages(sessionId, limit, beforeTime, resourceUrls);
        List<MessageView> views = messages == null ? null
                : messages.stream().map(SessionQaAdapter::toView).toList();
        // 成功路径恒 200（错误在用例层以 BizException 抛出，不走到这里）
        return new EmbedReply<>(200, views);
    }

    @Override
    public void stopSession(String sessionId, String rawBody) {
        StopSessionRequest request = null;
        if (rawBody != null && !rawBody.isEmpty()) {
            try {
                request = MAPPER.readValue(rawBody, StopSessionRequest.class);
            } catch (Exception e) {
                throw new BizException(AppError.badRequest("invalid request body"));
            }
        }
        sessionStop.stopSession(sessionId, request);
    }

    @Override
    public EmbedReply<MessageSuggestionView> getSuggestions(String sessionId, String messageId) {
        MessageSuggestionSet set = suggestionFacade.getFollowUpsSanitized(sessionId, messageId);
        return new EmbedReply<>(MessageSuggestionFacade.statusFor(set), toView(set));
    }

    @Override
    public EmbedReply<MessageSuggestionView> ensureSuggestions(String sessionId, String messageId,
            String rawBody) {
        MessageSuggestionSet set = suggestionFacade.ensureFollowUpsBody(sessionId, messageId, rawBody);
        return new EmbedReply<>(MessageSuggestionFacade.statusFor(set), toView(set));
    }

    @Override
    public void recordSuggestionEvent(String sessionId, String rawBody) {
        suggestionFacade.recordEventBody(sessionId, rawBody);
    }

    /** 实体 → 线格式视图（域内投影：吃实体，字段逐字搬运；null 透传）。 */
    static MessageSuggestionView toView(MessageSuggestionSet s) {
        if (s == null) {
            return null;
        }
        MessageSuggestionView v = new MessageSuggestionView();
        v.setId(s.getId());
        v.setTenantId(s.getTenantId());
        v.setSessionId(s.getSessionId());
        v.setAssistantMessageId(s.getAssistantMessageId());
        v.setAgentId(s.getAgentId());
        v.setPlacement(s.getPlacement());
        v.setConfigHash(s.getConfigHash());
        v.setLocale(s.getLocale());
        v.setStatus(s.getStatus());
        v.setAllowRegenerate(s.isAllowRegenerate());
        v.setSuppressionReason(s.getSuppressionReason());
        v.setQuestions(s.getQuestions());
        v.setModelId(s.getModelId());
        v.setPromptTokens(s.getPromptTokens());
        v.setCompletionTokens(s.getCompletionTokens());
        v.setLatencyMs(s.getLatencyMs());
        v.setErrorCode(s.getErrorCode());
        v.setGeneratedAt(s.getGeneratedAt());
        v.setCreatedAt(s.getCreatedAt());
        v.setUpdatedAt(s.getUpdatedAt());
        return v;
    }
}
