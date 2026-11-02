package com.ragagent.session.controller;

import com.ragagent.contract.error.BizException;
import com.ragagent.contract.session.MessageView;
import com.ragagent.platform.security.LogSanitizer;
import com.ragagent.platform.web.ApiResponse;
import com.ragagent.platform.web.ApiResult;
import com.ragagent.session.conversation.MessageHistoryService;
import com.ragagent.session.conversation.MessageService;
import com.ragagent.session.dto.ChatHistoryKbStats;
import com.ragagent.session.dto.MessageSearchResult;
import com.ragagent.session.dto.MessageViewMapper;
import com.ragagent.session.dto.SearchMessagesRequest;
import com.ragagent.session.support.MessageNotFoundException;
import com.ragagent.session.support.SessionNotFoundException;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 消息 HTTP 层。
 *
 * <h2>响应形态</h2>
 * <ul>
 * <li>load → 裸 {@code [Message]}；search → 裸 {@link MessageSearchResult}；
 * stats → 裸 {@code ChatHistoryKbStats}；</li>
 * <li>删除 → <b>200</b> + {@code ApiResponse} 外壳（旧 {@code {"message":…,"success":true}}
 * 裸文案与空体 204 均不再使用）。</li>
 * </ul>
 *
 * <h2>错误门槛</h2>
 * <ul>
 * <li>{@code resourceUrls} 非法：public 拒绝 → 403，其他坏值 → 400；</li>
 * <li>{@code limit} 非整数**容错**回落 20（解析失败 → 默认值，不是 400）；</li>
 * <li>{@code beforeTime} 解析失败 → 400（固定中文文案）；</li>
 * <li>会话不可见 → 404 "session not found"；消息不存在 → 404 "record not found"
 * （两个 404 文案**不同**）；</li>
 * <li>搜索 {@code query} 缺失/为空 → 400 {@code query: 不能为空}（DTO 上的 {@code @NotBlank}）。</li>
 * </ul>
 */
@RestController
@ApiResult
@Tag(name = "Session")
public class MessageController {

    private static final Logger log = LoggerFactory.getLogger(MessageController.class);

    /** 消息不存在时 404 的文案。 */
    private static final String RECORD_NOT_FOUND = "record not found";

    private final MessageService messageService;
    private final MessageHistoryService messageHistory;

    public MessageController(MessageService messageService,
                             MessageHistoryService messageHistory) {
        this.messageService = messageService;
        this.messageHistory = messageHistory;
    }

    // ── 加载消息历史 ──

    /**
     * <p>⚠️ {@code limit} 是**容错**的：非整数回落默认 20（解析失败 → 记日志 +
     * 默认值），与分页参数解析失败即 400 不同；limit 进日志前也过
     * {@code SanitizeForLog}。</p>
     */
    @GetMapping("/api/v1/messages/{sessionId}/load")
    public ResponseEntity<List<MessageView>> loadMessages(
            @PathVariable("sessionId") String sessionId,
            @RequestParam(name = "limit", required = false) String limit,
            @RequestParam(name = "beforeTime", required = false) String beforeTime,
            @RequestParam(name = "resourceUrls", required = false) String resourceUrls) {
        // 解析/消毒/引用重写在 MessageHistoryService（HTTP 与 embed 委托面共用）
        return ResponseEntity.ok(messageHistory.loadMessages(
                        sessionId, limit, beforeTime, resourceUrls).stream()
                .map(MessageViewMapper::from).toList());
    }

    // ── 删除消息 ──

    /**
     * 两个 404 文案**刻意不同**：
     * 会话不可见是 "session not found"；消息不存在是 "record not found"。
     */
    @DeleteMapping("/api/v1/messages/{sessionId}/{id}")
    public ApiResponse<Void> deleteMessage(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("id") String messageId) {
        String sid = LogSanitizer.sanitize(sessionId);
        String mid = LogSanitizer.sanitize(messageId);
        try {
            messageService.deleteMessage(sid, mid);
        } catch (SessionNotFoundException e) {
            log.debug("Session not found, ID: {}", sid);
            throw BizException.notFound(e.getMessage());
        } catch (MessageNotFoundException e) {
            log.debug("Message not found, session ID: {}, message ID: {}", sid, mid);
            throw BizException.notFound(RECORD_NOT_FOUND);
        } catch (RuntimeException e) {
            throw toInternal(e);
        }
        return ApiResponse.ok();   // 204 退役（空体与「外壳恒存在」冲突）
    }

    // ── 搜索 ──

    /**
     * <p>⚠️ {@code query} 空串/缺失在 DTO 校验层就被拒（{@code @NotBlank}，
     * 文案 {@code query: 不能为空}）；handler 内没有第二道空 query 检查。</p>
     * <p>{@code query} 进搜索前还要过一遍 {@code SanitizeForLog}。</p>
     */
    @PostMapping("/api/v1/messages/search")
    public ResponseEntity<MessageSearchResult> searchMessages(
            @RequestBody @Valid SearchMessagesRequest request) {
        MessageSearchResult result;
        result = messageService.searchMessages(
                LogSanitizer.sanitize(request.query()),
                request.mode(),
                request.limit() == null ? 0 : request.limit(),
                request.sessionIds());

        return ResponseEntity.ok(result);
    }

    // ── 聊天历史统计 ──

    @GetMapping("/api/v1/messages/chat-history-stats")
    public ResponseEntity<ChatHistoryKbStats> getChatHistoryKbStats() {
        ChatHistoryKbStats stats;
        stats = messageService.getChatHistoryKbStats();
        return ResponseEntity.ok(stats);
    }

    // ── 公共 ──

    /**
     * public 拒绝 → 403，
     * 其他坏值 → 400。缺 bean 降级与 SessionStreamController 的 ObjectProvider 模式一致。
     */
    /**
     * 已带形态的业务错误必须原样透传（二次包装会把 "error code: N, error message: "
     * 前缀叠两层，见 docs/known-issues/02-wave-0-1.md 的记录）。
     */
    private static BizException toInternal(RuntimeException e) {
        if (e instanceof BizException biz) {
            return biz;
        }
        log.warn("unhandled exception mapped to internal error", e);
        return BizException.internal("internal error");
    }
}
