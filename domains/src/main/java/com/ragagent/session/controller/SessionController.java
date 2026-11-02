package com.ragagent.session.controller;

import com.ragagent.contract.error.AppError;
import com.ragagent.contract.error.BizException;
import com.ragagent.contract.pagination.PageResult;
import com.ragagent.contract.session.MessageArtifact;
import com.ragagent.contract.session.SessionView;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.platform.security.LogSanitizer;
import com.ragagent.platform.web.ApiResponse;
import com.ragagent.platform.web.ApiResult;
import com.ragagent.session.conversation.MessageService;
import com.ragagent.session.conversation.SessionReadService;
import com.ragagent.session.conversation.SessionService;
import com.ragagent.session.conversation.SessionStopService;
import com.ragagent.session.dto.ArtifactView;
import com.ragagent.session.dto.BatchDeleteSessionsRequest;
import com.ragagent.session.dto.CreateSessionRequest;
import com.ragagent.session.dto.GenerateTitleRequest;
import com.ragagent.session.dto.GenerateTitleResponse;
import com.ragagent.session.dto.SessionListItem;
import com.ragagent.session.dto.SessionListQuery;
import com.ragagent.session.dto.SessionListResponse;
import com.ragagent.session.dto.SessionPinResponse;
import com.ragagent.session.dto.StopSessionRequest;
import com.ragagent.session.dto.UpdateSessionRequest;
import com.ragagent.session.support.MessageNotFoundException;
import com.ragagent.session.support.SessionNotFoundException;
import com.ragagent.session.support.SessionOwnerIds;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话 HTTP 层。
 *
 * <h2>响应形态</h2>
 * <ul>
 * <li>创建 → <b>201</b> + 裸 {@code Session}；读取/更新 → 200 + 裸 {@code Session}；</li>
 * <li>列表 → {@code {items,page,pageSize,total}}（{@link SessionListResponse}）；</li>
 * <li>删除 / 批量删除 / 清空消息 → <b>200</b> + {@code ApiResponse} 外壳（旧
 * {@code {"message":…,"success":true}} 裸文案与空体 204 均不再使用，前端不再解析服务端文案）；</li>
 * <li>置顶 → 200 + {@code {"pinned":bool}}（{@link SessionPinResponse}）；</li>
 * <li>产物列表 → 裸数组 {@code [ArtifactView]}；生成标题 → {@code {"title":"…"}}；</li>
 * <li>停止生成 → 成功 <b>200</b> + {@code ApiResponse} 外壳，无业务数据。</li>
 * </ul>
 *
 * <h2>错误门槛顺序</h2>
 * <ol>
 * <li>路径参数 sanitize 后为空 → 400 {@code "invalid session id"}；</li>
 * <li>请求体绑定失败 → 400，文案由全局处理器给（空体/字面量 null → {@code 请求体不能为空}；
 * 畸形 JSON → {@code 请求体格式不正确}；缺 {@code messages} → {@code messages: 不能为空}）；</li>
 * <li>上下文无租户 → 401 {@code "Unauthorized"}；</li>
 * <li>服务层 ErrSessionNotFound → 404 {@code "session not found"}（code 1003）；</li>
 * <li>其余服务层错误 → 500 + 错误 message。</li>
 * </ol>
 * <p>⚠️ {@code stop} 端点的错误仍是**纯字符串信封** {@code {"error":"…"}}，
 * 与组内其他端点的 AppError 信封不同。</p>
 *
 * <h2>SanitizeForLog 为什么参与查找</h2>
 * <p>路径参数先过 {@link LogSanitizer} 再查库——
 * 这不只是日志卫生：批量删除会把 sanitize 后的 id 列表**当作真实入参**。</p>
 */
@RestController
@ApiResult
@Tag(name = "Session")
public class SessionController {

    private static final Logger log = LoggerFactory.getLogger(SessionController.class);

    private final SessionService sessionService;
    private final SessionReadService sessionReadService;
    private final MessageService messageService;
    private final SessionStopService sessionStopService;

    public SessionController(SessionService sessionService,
                             SessionReadService sessionReadService,
                             MessageService messageService,
                             SessionStopService sessionStopService) {
        this.sessionService = sessionService;
        this.sessionReadService = sessionReadService;
        this.messageService = messageService;
        this.sessionStopService = sessionStopService;
    }

    /**
     * **已带形态的业务错误**
     * （BizException，比如渠道筛选的 {@code "error code: 1002, error message: …"}）
     * 必须原样透传——二次包装会把前缀叠两层（真实踩过，golden 抓到）。
     */
    private static BizException toInternal(RuntimeException e) {
        if (e instanceof BizException biz) {
            return biz;
        }
        log.warn("unhandled exception mapped to internal error", e);
        return BizException.internal("internal error");
    }

    // ── 创建 ──

    /** 创建会话：<b>201</b> + 裸 {@code Session}。 */
    @PostMapping("/api/v1/sessions")
    public ResponseEntity<SessionView> createSession(@RequestBody @Valid CreateSessionRequest request) {
        // 请求体绑定校验（@Valid）先于租户校验
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null) {
            throw new BizException(AppError.unauthorized("Unauthorized"));
        }
        // API-Key 调用方按外部身份隔离；否则落到普通 user id / 空（历史行语义）
        String ownerId = SessionOwnerIds.currentSessionOwnerId();
        SessionView saved = sessionReadService.create(
                tenantId, request.title(), request.description(), ownerId);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    // ── 读取 ──

    @GetMapping("/api/v1/sessions/{id}")
    public ResponseEntity<SessionView> getSession(@PathVariable("id") String id) {
        String sessionId = LogSanitizer.sanitize(id);
        if (sessionId.isEmpty()) {
            throw new BizException(AppError.badRequest("invalid session id"));
        }
        try {
            return ResponseEntity.ok(sessionReadService.get(sessionId));
        } catch (SessionNotFoundException e) {
            log.debug("Session not found, ID: {}", sessionId);
            throw BizException.notFound(e.getMessage());
        } catch (RuntimeException e) {
            throw toInternal(e);
        }
    }

    /**
 * <p>查询参数是 camelCase（{@code page}/{@code pageSize}/{@code keyword}/{@code source}/
 * {@code agentId}，）；分页门槛的文案是标准中文（见 {@link #bindPagination}）。
 * 响应是 {@link SessionListResponse}。</p>
 */
    @GetMapping("/api/v1/sessions")
    public SessionListResponse getSessionsByTenant(
            @RequestParam(name = "page", required = false) String page,
            @RequestParam(name = "pageSize", required = false) String pageSize,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "source", required = false) String source,
            @RequestParam(name = "agentId", required = false) String agentId) {
        int p = bindPagination(page, "page", false);
        int size = bindPagination(pageSize, "pageSize", true);

        PageResult<SessionListItem> result;
        result = sessionService.listSessions(
                SessionListQuery.of(keyword, source, agentId, p, size));

        return new SessionListResponse(result.items(), result.page(), result.pageSize(),
                result.total());
    }

    /**
     * 分页参数的门槛（错误文案是标准中文）：
     * <ul>
     *   <li>参数缺席/为空 → 0（服务层再归一化）；<b>显式 {@code 0} 同样跳过</b>
     *       （历史行为：{@code page=0} → 200 且归一化）；</li>
     *   <li>非整数 → 400 {@code 分页参数不合法 / page: 必须是整数}；</li>
     *   <li>负数 → {@code page: 必须为正整数}；{@code withMax} 且 &gt;1000 → {@code pageSize: 超出上限}。</li>
     * </ul>
     */
    private static int bindPagination(String raw, String field, boolean withMax) {
        if (raw == null || raw.isEmpty()) {
            return 0;
        }
        final long value;
        try {
            value = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw paginationError(field + ": 必须是整数");
        }
        if (value == 0) {
            return 0;
        }
        if (value < 1) {
            throw paginationError(field + ": 必须为正整数");
        }
        if (withMax && value > 1000) {
            throw paginationError(field + ": 超出上限");
        }
        return (int) value;
    }

    private static BizException paginationError(String details) {
        return new BizException(
                AppError.badRequest("分页参数不合法").withDetails(details));
    }

    // ── 更新 ──

    /**
     * 更新成功后**重新加载**再返回
     * （拿完整的落库时间戳），这是响应与请求体不同源的原因。
     */
    @PutMapping("/api/v1/sessions/{id}")
    public ResponseEntity<SessionView> updateSession(
            @PathVariable("id") String id,
            @RequestBody @Valid UpdateSessionRequest request) {
        String sessionId = LogSanitizer.sanitize(id);
        if (sessionId.isEmpty()) {
            throw new BizException(AppError.badRequest("invalid session id"));
        }
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null) {
            throw new BizException(AppError.unauthorized("Unauthorized"));
        }
        try {
            return ResponseEntity.ok(sessionReadService.update(
                    sessionId, tenantId, request.title(), request.description()));
        } catch (SessionNotFoundException e) {
            log.debug("Session not found, ID: {}", sessionId);
            throw BizException.notFound(e.getMessage());
        } catch (RuntimeException e) {
            throw toInternal(e);
        }
    }



    // ── 删除 ──

    /** 删除会话：同步删除 → <b>200</b> + {@code ApiResponse} 外壳。 */
    @DeleteMapping("/api/v1/sessions/{id}")
    public ApiResponse<Void> deleteSession(@PathVariable("id") String id) {
        String sessionId = LogSanitizer.sanitize(id);
        if (sessionId.isEmpty()) {
            throw new BizException(AppError.badRequest("invalid session id"));
        }
        try {
            sessionService.deleteSession(sessionId);
        } catch (SessionNotFoundException e) {
            log.debug("Session not found, ID: {}", sessionId);
            throw BizException.notFound(e.getMessage());
        } catch (RuntimeException e) {
            throw toInternal(e);
        }
        return ApiResponse.ok();   // 204 退役（空体与「外壳恒存在」冲突）
    }

    /**
     * {@code deleteAll=true} 走全量删除；
     * 否则要求非空 ids，逐个 sanitize 后丢弃空项。成功 → <b>200</b> + {@code ApiResponse} 外壳（同步删除）。
     */
    @DeleteMapping("/api/v1/sessions/batch")
    public ApiResponse<Void> batchDeleteSessions(
            @RequestBody @Valid BatchDeleteSessionsRequest req) {
        if (Boolean.TRUE.equals(req.deleteAll())) {
            sessionService.deleteAllSessions();
        return ApiResponse.ok();   // 204 退役（空体与「外壳恒存在」冲突）
        }

        if (req.ids() == null || req.ids().isEmpty()) {
            throw new BizException(AppError.badRequest("ids are required when deleteAll is false"));
        }
        List<String> sanitizedIds = new ArrayList<>();
        for (String raw : req.ids()) {
            String sanitized = LogSanitizer.sanitize(raw);
            if (!sanitized.isEmpty()) {
                sanitizedIds.add(sanitized);
            }
        }
        if (sanitizedIds.isEmpty()) {
            throw new BizException(AppError.badRequest("no valid session IDs provided"));
        }

        try {
            sessionService.batchDeleteSessions(sanitizedIds);
        } catch (SessionNotFoundException e) {
            log.warn("No visible sessions found for batch delete (tenant={}, role={}, owner={}, requested={}, sample={})",
                    TenantContext.currentTenantId(), TenantContext.currentRole(),
                    SessionOwnerIds.currentSessionOwnerId(),
                    req.ids().size(),
                    req.ids().stream().limit(3).map(LogSanitizer::sanitize).toList());
            throw BizException.notFound(e.getMessage());
        } catch (RuntimeException e) {
            throw toInternal(e);
        }
        return ApiResponse.ok();   // 204 退役（空体与「外壳恒存在」冲突）
    }

    // ── 清空消息 ──

    /**
     * 会话本身保留，消息全软删（含建议与聊天历史知识清理——在 MessageService 里）。
     * 会话不可见 → 404 "session not found"。
     */
    @DeleteMapping("/api/v1/sessions/{id}/messages")
    public ApiResponse<Void> clearSessionMessages(@PathVariable("id") String id) {
        String sessionId = LogSanitizer.sanitize(id);
        if (sessionId.isEmpty()) {
            throw new BizException(AppError.badRequest("invalid session id"));
        }
        try {
            messageService.clearSessionMessages(sessionId);
        } catch (SessionNotFoundException e) {
            log.debug("Session not found, ID: {}", sessionId);
            throw BizException.notFound(e.getMessage());
        } catch (RuntimeException e) {
            throw toInternal(e);
        }
        return ApiResponse.ok();   // 204 退役（空体与「外壳恒存在」冲突）
    }

    // ── 置顶 ──

    /** 置顶：200 + {@code {"pinned":true}}。 */
    @PostMapping("/api/v1/sessions/{sessionId}/pin")
    public SessionPinResponse pinSession(@PathVariable("sessionId") String sessionId) {
        return setSessionPinned(sessionId, true);
    }

    @DeleteMapping("/api/v1/sessions/{id}/pin")
    public SessionPinResponse unpinSession(@PathVariable("id") String id) {
        return setSessionPinned(id, false);
    }

    /**
     * 服务层报错 → 500（不是 404）；
     * 0 行受影响（不存在/不可见）→ 404 {@code "session not found"}。
     */
    private SessionPinResponse setSessionPinned(String rawId, boolean pinned) {
        String id = LogSanitizer.sanitize(rawId);
        if (id.isEmpty()) {
            throw new BizException(AppError.badRequest("invalid session id"));
        }
        long rows;
        rows = sessionService.setSessionPinned(id, pinned);
        if (rows == 0) {
            throw BizException.notFound("session not found");
        }
        return new SessionPinResponse(pinned);
    }

    // ── 产物 ──

    /**
     * 会话全部 assistant 消息的产物元数据，**不含存储 URL**——客户端不能绕过
     * download 端点直接读 provider:// 路径。
     */
    @GetMapping("/api/v1/sessions/{id}/artifacts")
    public List<ArtifactView> listSessionArtifacts(@PathVariable("id") String id) {
        String sessionId = LogSanitizer.sanitize(id);
        if (sessionId.isEmpty()) {
            throw new BizException(AppError.badRequest("invalid session id"));
        }
        // 归属校验走 GetSession（读可见性）
        try {
            sessionService.getSession(sessionId);
        } catch (SessionNotFoundException e) {
            throw BizException.notFound(e.getMessage());
        } catch (RuntimeException e) {
            throw toInternal(e);
        }
        List<MessageArtifact> artifacts;
        artifacts = messageService.getSessionArtifacts(sessionId);
        return artifactListItems(artifacts);
    }

    /** 消息产物列表：裸数组。 */
    @GetMapping("/api/v1/sessions/{id}/messages/{messageId}/artifacts")
    public List<ArtifactView> listMessageArtifacts(
            @PathVariable("id") String id,
            @PathVariable("messageId") String messageId) {
        String sessionId = LogSanitizer.sanitize(id);
        String mid = LogSanitizer.sanitize(messageId);
        if (sessionId.isEmpty() || mid.isEmpty()) {
            throw new BizException(AppError.badRequest("session_id and message_id are required"));
        }
        try {
            sessionService.getSession(sessionId);
        } catch (SessionNotFoundException e) {
            throw BizException.notFound(e.getMessage());
        } catch (RuntimeException e) {
            throw toInternal(e);
        }
        List<MessageArtifact> artifacts;
        try {
            artifacts = messageService.getArtifacts(sessionId, mid);
        } catch (MessageNotFoundException e) {
            // 不存在 → 404 "message not found"（固定文案）
            throw BizException.notFound("message not found");
        } catch (RuntimeException e) {
            throw BizException.notFound("message not found");
        }
        return artifactListItems(artifacts == null ? List.of() : artifacts);
    }

    /**
     * <p>参数/范围分支的 400/404 文案固定；实际取文件依赖的资源目录解析
     * 未实现——恒回 404 "artifact not accessible"。</p>
     */
    @GetMapping("/api/v1/sessions/{id}/messages/{messageId}/artifacts/{index}/download")
    public ResponseEntity<Void> downloadMessageArtifact(
            @PathVariable("id") String id,
            @PathVariable("messageId") String messageId,
            @PathVariable("index") String indexParam) {
        String sessionId = LogSanitizer.sanitize(id);
        String mid = LogSanitizer.sanitize(messageId);
        if (sessionId.isEmpty() || mid.isEmpty() || indexParam == null || indexParam.isEmpty()) {
            throw new BizException(AppError.badRequest(
                    "session_id, message_id and index are required"));
        }
        final int index;
        try {
            index = Integer.parseInt(indexParam);
        } catch (NumberFormatException e) {
            throw new BizException(AppError.badRequest("invalid artifact index"));
        }
        if (index < 0) {
            throw new BizException(AppError.badRequest("invalid artifact index"));
        }
        try {
            sessionService.getSession(sessionId);
        } catch (SessionNotFoundException e) {
            throw BizException.notFound(e.getMessage());
        } catch (RuntimeException e) {
            throw toInternal(e);
        }
        List<MessageArtifact> artifacts;
        try {
            artifacts = messageService.getArtifacts(sessionId, mid);
        } catch (RuntimeException e) {
            throw BizException.notFound("message not found");
        }
        if (artifacts == null) {
            artifacts = List.of();
        }
        if (index >= artifacts.size()) {
            throw BizException.notFound("artifact index out of range");
        }
        MessageArtifact artifact = artifacts.get(index);
        if (artifact.getUrl() == null || artifact.getUrl().isEmpty()) {
            throw BizException.notFound("artifact storage path missing");
        }
        // 资源目录/共享授权解析未实现：恒落 404 "artifact not accessible"。
        throw BizException.notFound("artifact not accessible");
    }

    /** {@code index} 是列表下标，其余字段取自产物本体。 */
    private static List<ArtifactView> artifactListItems(
            List<MessageArtifact> artifacts) {
        List<ArtifactView> items = new ArrayList<>(artifacts.size());
        for (int i = 0; i < artifacts.size(); i++) {
            items.add(ArtifactView.of(i, artifacts.get(i)));
        }
        return items;
    }



    // ── 生成标题 ──

    /**
     * 写会话行，用严格 owner 范围
     * （GetOwnedSession——管理员可读不可改）。响应 {@code {"title":"…"}}。
     */
    @PostMapping("/api/v1/sessions/{sessionId}/generate_title")
    public GenerateTitleResponse generateTitle(
            @PathVariable("sessionId") String sessionId,
            @RequestBody @Valid GenerateTitleRequest request) {
        if (sessionId == null || sessionId.isEmpty()) {
            throw new BizException(AppError.badRequest("invalid session id"));
        }
        try {
            String title = sessionReadService.generateTitleForOwnedSession(
                    sessionId, request.messages());
            return new GenerateTitleResponse(title);
        } catch (SessionNotFoundException e) {
            log.debug("Session not found, ID: {}", sessionId);
            throw BizException.notFound(e.getMessage());
        } catch (RuntimeException e) {
            throw toInternal(e);
        }
    }

    // ── 停止生成 ──

    /**
     * <p>⚠️ 错误形态与组内其他端点不同：错误体是 {@code {"error": "…"}} 纯字符串信封
     * （**不是** AppError 信封），状态码有 400/401/403/404 四种。
     * 停止事件经 StreamManager 落存储（跨语言键空间），事件 type 是
     * {@code types.ResponseType(event.EventStop)} 的字符串强转 "stop"。</p>
     */
    @PostMapping("/api/v1/sessions/{sessionId}/stop")
    public ApiResponse<Void> stopSession(
            @PathVariable("sessionId") String sessionId,
            @RequestBody(required = false) StopSessionRequest request) {
        // 校验与 stop 事件写入在 SessionStopService（HTTP 与 embed 委托面共用）
        sessionStopService.stopSession(sessionId, request);
        return ApiResponse.ok();   // 204 退役（空体与「外壳恒存在」冲突）
    }
}
