package com.ragagent.session.service.suggestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.contract.error.BizException;
import com.ragagent.platform.security.LogSanitizer;
import com.ragagent.platform.web.JsonMappers;
import com.ragagent.session.domain.message.MessageSuggestionSet;
import com.ragagent.session.domain.message.MessageSuggestionSetNotFoundException;
import com.ragagent.session.support.MessageNotFoundException;
import com.ragagent.session.support.SessionNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 追问建议的**双面门面**：HTTP 端点与 embed 委托面共用的绑定、消毒与错误映射，
 * 收口在 {@link MessageSuggestionService} 用例之上——两个面语义恒一致
 * （原先 embed 面直接回调控制器，方向倒挂（api → controller），已改经本层）。
 *
 * <h2>writeError 的子串映射（顺序有语义，金片钉死）</h2>
 * <ul>
 *   <li>消息不存在 / 建议集合不存在 → 404 "suggestions not found"；</li>
 *   <li>会话不可见 → 404 "session not found"（独立分支）；</li>
 *   <li>"completed assistant" / "invalid suggestion event" / "requires question_id" /
 *       "does not belong" / "not allowed" → 400 + 原文；</li>
 *   <li>其余 → 500 固定文案 "message suggestion operation failed"（不透传原文）。</li>
 * </ul>
 */
@Service
public class MessageSuggestionFacade {

    private static final Logger log = LoggerFactory.getLogger(MessageSuggestionFacade.class);

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    private final MessageSuggestionService suggestionService;

    public MessageSuggestionFacade(MessageSuggestionService suggestionService) {
        this.suggestionService = suggestionService;
    }

    /**
     * ensure 的双面门面：请求体解析（空 body 合法 = regenerate=false；畸形 → 400
     * 固定文案 "invalid request body"，不是解析器原文）→ 消毒 → 用例 → writeError 映射。
     * 202/200 状态判定见 {@link #statusFor}。
     */
    public MessageSuggestionSet ensureFollowUpsBody(String sessionId, String messageId,
            String rawBody) {
        boolean regenerate = false;
        if (rawBody != null && !rawBody.isEmpty()) {
            try {
                EnsureBody request = MAPPER.readValue(rawBody, EnsureBody.class);
                regenerate = request != null && Boolean.TRUE.equals(request.regenerate());
            } catch (Exception e) {
                throw BizException.badRequest("invalid request body");
            }
        }
        try {
            return suggestionService.ensureFollowUps(LogSanitizer.sanitize(sessionId),
                    LogSanitizer.sanitize(messageId), regenerate);
        } catch (RuntimeException e) {
            throw writeError(e);
        }
    }

    /** get 的双面门面（两个通配路径的 sessionId/id 归并由调用方完成）。 */
    public MessageSuggestionSet getFollowUpsSanitized(String sessionId, String messageId) {
        try {
            return suggestionService.getFollowUps(LogSanitizer.sanitize(sessionId),
                    LogSanitizer.sanitize(messageId));
        } catch (RuntimeException e) {
            throw writeError(e);
        }
    }

    /**
     * recordEvent 的双面门面：解析失败（含空 body、required 字段缺失）→ 400 固定文案
     * "invalid request body"（binding:"required" 的字段缺失同样落此文案）。
     */
    public void recordEventBody(String sessionId, String rawBody) {
        EventBody request = null;
        if (rawBody != null && !rawBody.isBlank()) {
            try {
                request = MAPPER.readValue(rawBody, EventBody.class);
            } catch (Exception e) {
                throw BizException.badRequest("invalid request body");
            }
        }
        if (request == null || isBlank(request.suggestionSetId())
                || isBlank(request.eventType())) {
            throw BizException.badRequest("invalid request body");
        }
        try {
            suggestionService.recordEvent(LogSanitizer.sanitize(sessionId),
                    request.suggestionSetId().trim(),
                    request.questionId() == null ? "" : request.questionId().trim(),
                    request.eventType().trim());
        } catch (RuntimeException e) {
            throw writeError(e);
        }
    }

    /** 集合进入 generating 态时响应码是 202，否则 200（两个面共用）。 */
    public static int statusFor(MessageSuggestionSet set) {
        return set != null && MessageSuggestionSet.STATUS_GENERATING.equals(set.getStatus())
                ? 202 : 200;
    }

    /** 请求体键名＝Java 字段名（camelCase）。 */
    private record EnsureBody(Boolean regenerate) {
    }

    /** 请求体键名＝Java 字段名（camelCase）。 */
    private record EventBody(String suggestionSetId, String questionId, String eventType) {
    }

    private static boolean isBlank(String v) {
        return v == null || v.trim().isEmpty();
    }

    /**
     * 错误到 HTTP 状态的子串分派。顺序有语义：
     * not-found 类异常在前（消息不存在也落 "suggestions not found"），
     * 会话 404 独立分支，业务 400 靠子串匹配，其余 500 固定文案。
     */
    private static BizException writeError(RuntimeException e) {
        if (e instanceof MessageSuggestionSetNotFoundException
                || e instanceof MessageNotFoundException) {
            return BizException.notFound("suggestions not found");
        }
        if (e instanceof SessionNotFoundException) {
            // 独立分支：SessionNotFoundException 需自己的 404 文案
            return BizException.notFound("session not found");
        }
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.contains("completed assistant")
                || message.contains("invalid suggestion event")
                || message.contains("requires question_id")
                || message.contains("does not belong")
                || message.contains("not allowed")) {
            return BizException.badRequest(message);
        }
        log.error("message suggestion operation failed", e);
        return BizException.internal("message suggestion operation failed");
    }
}
