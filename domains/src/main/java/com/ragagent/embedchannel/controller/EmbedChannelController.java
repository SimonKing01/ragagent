package com.ragagent.embedchannel.controller;

import com.ragagent.contract.error.AppError;
import com.ragagent.contract.error.BizException;
import com.ragagent.contract.session.EmbedSessionOps;
import com.ragagent.contract.session.EmbedSessionPort;
import com.ragagent.contract.session.MessageView;
import com.ragagent.contract.storage.support.StorageUrlContext;
import com.ragagent.contract.tenant.TenantRole;
import com.ragagent.contract.tool.McpApprovalOps;
import com.ragagent.embedchannel.service.EmbedChannelService;
import com.ragagent.embedchannel.support.EmbedError;
import com.ragagent.embedchannel.support.EmbedTokens;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.platform.storage.fileserve.FileProxyPort;
import com.ragagent.platform.web.ApiResponse;
import com.ragagent.platform.web.ApiResult;
import com.ragagent.platform.web.JsonBodyErrors;
import com.ragagent.platform.web.JsonMappers;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * embed 渠道 HTTP 层（管理面/公开面路由）。
 *
 * <p><b>管理面</b>（JWT + RBAC + API-Key manage_channels 门）：POST/GET
 * /agents/:id/embed-channels、GET/PUT/DELETE /embed-channels/:channelId、
 * rotate-token、preview-session、stats。</p>
 *
 * <p><b>公开面</b>（EmbedAuthFilter 已注入渠道与租户上下文）：exchange、config、
 * suggested-questions、chunks/:chunk_id、sessions(POST)、messages/:sid/load、
 * sessions/:sid/stop、suggestions GET/POST、suggestion-events、events（webhook 中继）、
 * mcp-oauth authorize-url/status、mcp-oauth-resolutions(+cancel)、tool-approvals。
 * 委托面直接调用既有控制器方法，确保字节契约同源。</p>
 *
 * <p><b>收口</b>：{@code POST /embed/:cid/knowledge-chat/:sid} 与
 * {@code POST /embed/:cid/agent-chat/:sid}（patchEmbedChatPayload + 委托
 * KnowledgeQaController 的 KnowledgeQA/AgentQA）与 {@code GET /embed/:cid/files}
 * （委托 FileProxyService.serveTenantFiles，与 /files 同 handler 体）已落地。</p>
 *
 * <h2>响应形态</h2>
 * <ul>
 * <li>管理面响应键<b>字母序</b>（TreeMap 构建）；create 是 201；</li>
 * <li>公开面 envelope {@code {"data":…,"success":true}}；错误是纯字符串
 * {@code {"error":"…"}}（webhook 中继/签名/白名单族）或 AppError 信封
 * （suggestion/MCP 委托面沿用各自控制器的异常）。</li>
 * </ul>
 */
@RestController
@ApiResult
@Tag(name = "Embed Channel")
public class EmbedChannelController {

static final ObjectMapper MAPPER = JsonMappers.lenient();
    final EmbedChannelService service;
    final EmbedSessionOps embedSessionOps;
    /** 会话对外操作面（L1 端口；实现由 session 提供）。 */
    final EmbedSessionPort sessionPort;
    /** MCP OAuth 授权与工具审批解析（L1 端口；实现由 mcp 提供）。 */
    final McpApprovalOps mcpApprovalOps;
final FileProxyPort fileProxyService;

    /** 管理面协作者。 */
    final EmbedChannelMgmtOps mgmtOps;

    /** 公开面协作者。 */
    final EmbedChannelPublicOps publicOps;

    /** 委托协作者（chat 委托/会话/建议/MCP 段）。 */
    final EmbedChannelDelegateOps delegateOps;

    public EmbedChannelController(EmbedChannelService service,
                                  EmbedSessionOps embedSessionOps,
                                  EmbedSessionPort sessionPort,
                                  McpApprovalOps mcpApprovalOps,
                                  FileProxyPort fileProxyService) {
        this.service = service;
        this.embedSessionOps = embedSessionOps;
        this.sessionPort = sessionPort;
        this.mcpApprovalOps = mcpApprovalOps;
        this.fileProxyService = fileProxyService;
        this.mgmtOps = new EmbedChannelMgmtOps(this);
        this.publicOps = new EmbedChannelPublicOps(this);
        this.delegateOps = new EmbedChannelDelegateOps(this);
    }

    // ── 请求体 ──

    record EmbedChannelRequest( String name, Boolean enabled, JsonNode allowedOrigins, String welcomeMessage, Integer rateLimitPerMinute, Integer rateLimitPerDay, String primaryColor, String pageTitle, String headerTitleMode, Boolean showSuggestedQuestions, Boolean showThinking, String widgetPosition, Boolean allowWebSearch, Boolean allowFileUpload, String defaultLocale, String webhookUrl, String webhookSecret, String agentId, String launcherIcon) {
    }


    @PostMapping("/api/v1/agents/{id}/embed-channels")
    public ResponseEntity<Map<String, Object>> create(@PathVariable("id") String agentId,
                                                      @RequestBody(required = false) String rawBody) {
        return mgmtOps.create(agentId, rawBody);
    }

    @GetMapping("/api/v1/agents/{id}/embed-channels")
    public ResponseEntity<List<Map<String, Object>>> listByAgent(@PathVariable("id") String agentId) {
        return mgmtOps.listByAgent(agentId);
    }

    @GetMapping("/api/v1/embed-channels")
    public ResponseEntity<List<Map<String, Object>>> listAll() {
        return mgmtOps.listAll();
    }

    @GetMapping("/api/v1/embed-channels/{channelId}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable("channelId") String channelId) {
        return mgmtOps.get(channelId);
    }

    @PutMapping("/api/v1/embed-channels/{channelId}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable("channelId") String channelId,
                                                      @RequestBody(required = false) String rawBody) {
        return mgmtOps.update(channelId, rawBody);
    }

    @DeleteMapping("/api/v1/embed-channels/{channelId}")
    public ApiResponse<Void> delete(@PathVariable("channelId") String channelId) {
        return mgmtOps.delete(channelId);
    }

    @PostMapping("/api/v1/embed-channels/{channelId}/rotate-token")
    public ResponseEntity<Map<String, Object>> rotate(@PathVariable("channelId") String channelId) {
        return mgmtOps.rotate(channelId);
    }

    @PostMapping("/api/v1/embed-channels/{channelId}/preview-session")
    public ResponseEntity<Map<String, Object>> preview(@PathVariable("channelId") String channelId) {
        return mgmtOps.preview(channelId);
    }

    @GetMapping("/api/v1/embed-channels/{channelId}/stats")
    public ResponseEntity<Map<String, Object>> stats(@PathVariable("channelId") String channelId) {
        return mgmtOps.stats(channelId);
    }


    @PostMapping("/api/v1/embed/{channelId}/exchange")
    public ResponseEntity<Map<String, Object>> exchange(@PathVariable("channelId") String channelId) {
        return publicOps.exchange(channelId);
    }

    @GetMapping("/api/v1/embed/{channelId}/config")
    public ResponseEntity<tools.jackson.databind.node.ObjectNode> config(@PathVariable("channelId") String channelId) {
        return publicOps.config(channelId);
    }

    @GetMapping("/api/v1/embed/{channelId}/suggested-questions")
    public ResponseEntity<Map<String, Object>> suggestedQuestions(
            @PathVariable("channelId") String channelId,
            @RequestParam(name = "limit", required = false) String limit) {
        return publicOps.suggestedQuestions(channelId, limit);
    }

    @GetMapping("/api/v1/embed/{channelId}/chunks/{chunkId}")
    public ResponseEntity<?> chunk(@PathVariable("chunkId") String chunkId) {
        return publicOps.chunk(chunkId);
    }

    @GetMapping("/api/v1/embed/{channelId}/sessions")
    public ResponseEntity<Map<String, Object>> listSessions(
            @PathVariable("channelId") String channelId) {
        return publicOps.listSessions(channelId);
    }

    @PostMapping("/api/v1/embed/{channelId}/sessions")
    public ResponseEntity<Map<String, Object>> createSession(
            @PathVariable("channelId") String channelId) {
        return publicOps.createSession(channelId);
    }

    @DeleteMapping("/api/v1/embed/{channelId}/sessions/{sessionId}")
    public ResponseEntity<Map<String, Object>> deleteSession(
            @PathVariable("channelId") String channelId,
            @PathVariable("sessionId") String sessionId) {
        return publicOps.deleteSession(channelId, sessionId);
    }


    @PostMapping("/api/v1/embed/{channelId}/knowledge-chat/{sessionId}")
    public void knowledgeChat(@PathVariable("sessionId") String sessionId,
                              @RequestBody(required = false) String rawBody,
                              @RequestParam(name = "resource_urls", required = false) String resourceUrls,
                              jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        delegateOps.knowledgeChat(sessionId, rawBody, resourceUrls, response);
    }

    @PostMapping("/api/v1/embed/{channelId}/agent-chat/{sessionId}")
    public void agentChat(@PathVariable("sessionId") String sessionId,
                          @RequestBody(required = false) String rawBody,
                          @RequestParam(name = "resource_urls", required = false) String resourceUrls,
                          jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        delegateOps.agentChat(sessionId, rawBody, resourceUrls, response);
    }

    @GetMapping("/api/v1/embed/{channelId}/files")
    public void embedFiles(jakarta.servlet.http.HttpServletRequest request,
                           jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        delegateOps.embedFiles(request, response);
    }

    @GetMapping("/api/v1/embed/{channelId}/messages/{sessionId}/load")
    public ResponseEntity<List<MessageView>> load(@PathVariable("sessionId") String sessionId,
                                                    @RequestParam(name = "limit", required = false) String limit,
                                                    @RequestParam(name = "before_time", required = false) String beforeTime,
                                                    @RequestParam(name = "resource_urls", required = false) String resourceUrls) {
        return delegateOps.load(sessionId, limit, beforeTime, resourceUrls);
    }

    @PostMapping("/api/v1/embed/{channelId}/sessions/{sessionId}/stop")
    public ApiResponse<Void> stop(@PathVariable("sessionId") String sessionId,
                                  @RequestBody(required = false)
                                  String rawBody) {
        return delegateOps.stop(sessionId, rawBody);
    }

    @GetMapping("/api/v1/embed/{channelId}/sessions/{sessionId}/messages/{messageId}/suggestions")
    public ResponseEntity<?> suggestionsGet(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("messageId") String messageId) {
        return delegateOps.suggestionsGet(sessionId, messageId);
    }

    @PostMapping("/api/v1/embed/{channelId}/sessions/{sessionId}/messages/{messageId}/suggestions")
    public ResponseEntity<?> suggestionsEnsure(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("messageId") String messageId,
            @RequestBody(required = false) String rawBody) {
        return delegateOps.suggestionsEnsure(sessionId, messageId, rawBody);
    }

    @PostMapping("/api/v1/embed/{channelId}/sessions/{sessionId}/suggestion-events")
    public ApiResponse<Void> suggestionEvents(@PathVariable("sessionId") String sessionId,
                                              @RequestBody(required = false) String rawBody) {
        return delegateOps.suggestionEvents(sessionId, rawBody);
    }

    @PostMapping("/api/v1/embed/{channelId}/sessions/{sessionId}/events")
    public ApiResponse<Void> events(@PathVariable("sessionId") String sessionId,
                                    @RequestBody(required = false) String rawBody) {
        return delegateOps.events(sessionId, rawBody);
    }

    @PostMapping("/api/v1/embed/{channelId}/sessions/{sessionId}/mcp-services/{svcId}/oauth/authorize-url")
    public ResponseEntity<Map<String, Object>> mcpAuthorize(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("svcId") String serviceId,
            @RequestBody(required = false) String rawBody) {
        return delegateOps.mcpAuthorize(sessionId, serviceId, rawBody);
    }

    @GetMapping("/api/v1/embed/{channelId}/sessions/{sessionId}/mcp-services/{svcId}/oauth/status")
    public ResponseEntity<?> mcpStatus(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("svcId") String serviceId) {
        return delegateOps.mcpStatus(sessionId, serviceId);
    }

    @PostMapping("/api/v1/embed/{channelId}/sessions/{sessionId}/mcp-oauth-resolutions/{pendingId}")
    public ApiResponse<Void> mcpResolve(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("pendingId") String pendingId,
            @RequestBody(required = false) String rawBody) {
        return delegateOps.mcpResolve(sessionId, pendingId, rawBody);
    }

    @PostMapping("/api/v1/embed/{channelId}/sessions/{sessionId}/mcp-oauth-resolutions/{pendingId}/cancel")
    public ApiResponse<Void> mcpResolveCancel(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("pendingId") String pendingId) {
        return delegateOps.mcpResolveCancel(sessionId, pendingId);
    }

    @PostMapping("/api/v1/embed/{channelId}/sessions/{sessionId}/tool-approvals/{pendingId}")
    public ApiResponse<Void> toolApprovals(@PathVariable("sessionId") String sessionId,
                                           @PathVariable("pendingId") String pendingId,
                                           @RequestBody(required = false) String rawBody) {
        return delegateOps.toolApprovals(sessionId, pendingId, rawBody);
    }

    // ── ensureSession ──

    /**
     * 会话门：失败直接抛 {@link PlainErrorException}
     * （全局处理器渲染纯字符串错误信封）；成功时上下文已改写为 embed_session 主体。
     */
void ensureSession(String sessionId) {
        var ch = EmbedChannelRows.channel(request0());
        long tenantId = ch.getTenantId() == null ? 0 : ch.getTenantId();
        if (sessionId == null || sessionId.isEmpty()) {
            throw BizException.badRequest("session_id is required");
        }
        EmbedSessionOps.OwnershipView sess = embedSessionOps.ownership(tenantId, sessionId);
        if (sess == null) {
            throw BizException.notFound("session not found");
        }
        String marker = EmbedChannelService.embedSessionDescription(ch.getId());
        if (sess.tenantId() == null || sess.tenantId().longValue() != tenantId
                || !marker.equals(sess.description())) {
            throw BizException.forbidden("session not allowed for this embed channel");
        }
        if (sess.userId() == null || sess.userId().trim().isEmpty()) {
            embedSessionOps.attachOwner(tenantId, ch.getId(), sessionId);
        }
        String sig = trim(request0().getHeader("X-Embed-Session"));
        if (!EmbedTokens.verifyHandle(ch, sessionId, sig)) {
            throw BizException.forbidden("session signature invalid");
        }
        // X-Embed-Visitor：合法时挂到上下文
        String visitor = trim(request0().getHeader("X-Embed-Visitor"));
        if (!visitor.isEmpty() && !validVisitor(visitor)) {
            throw BizException.badRequest("invalid embed visitor id");
        }
        TenantContext.set(tenantId,
                new TenantContext.Principal(TenantContext.PrincipalTypes.EMBED_SESSION,
                        tenantId + ":" + ch.getId() + ":" + sessionId),
                TenantRole.VIEWER.value(),
                false, "embed-" + ch.getId(), false);
        if (!visitor.isEmpty()) {
            TenantContext.setEmbedVisitorId(visitor);
        }
        // embed 访客恒收 resource:// 句柄
        StorageUrlContext.force();
    }

    /**
     * 渠道关闭推荐问题 → 200 裸对象 {@code {questions, status, suppressionReason}}：
     * 与委托路径（{@code MessageSuggestionController} 返回裸 {@code MessageSuggestionSet}）同形，
     * 键名取该实体的字段名（不包 data/success 信封）。
     */
    ResponseEntity<Object> suppressedIfChannelOff() {
        var ch = EmbedChannelRows.channel(request0());
        if (ch == null || !ch.isShowSuggestedQuestions()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("questions", new ArrayList<>());
            data.put("status", "suppressed");
            data.put("suppressionReason", "channel_disabled");
            return ResponseEntity.ok(data);
        }
        return null;
    }

    /** 访客 ID 校验：非空、≤128、无控制字符。 */
    static boolean validVisitor(String id) {
        if (id.isEmpty() || id.length() > 128) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return false;
            }
        }
        return true;
    }

    // ── 响应组构件 ──

    static ResponseEntity<Map<String, Object>> plainError(int status, String message) {
        throw new BizException(AppError.ofHttpStatus(status, message));   // 恒抛（调用点一行不动 ✓）
    }

    /** 管理面错误的分派（纯字符串错误信封）。 */
    static BizException writeMgmtError(EmbedError e) {
        return switch (e.kind) {
            case CHANNEL_NOT_FOUND -> BizException.notFound("embed channel not found");
            case BAD_REQUEST_TEXT -> BizException.badRequest(e.getMessage());
            case CHANNEL_DISABLED -> BizException.forbidden("embed channel is disabled");
            default -> BizException.internal("operation failed");
        };
    }

    // ── 工具 ──

    static long currentTenant() {
        Long tid = TenantContext.currentTenantId();
        return tid == null ? 0L : tid;
    }

    /** 取当前 HTTP 请求（MockMvc/Servlet 通用）。 */
    static jakarta.servlet.http.HttpServletRequest request0() {
        var attrs = org.springframework.web.context.request.RequestContextHolder
                .currentRequestAttributes();
        return ((org.springframework.web.context.request.ServletRequestAttributes) attrs).getRequest();
    }

    /**
     * 请求体绑定：空 body → 400 "No content to map due to end-of-input"，坏 JSON → 解析器原文措辞（标准 Jackson 消息）。
     */
    static EmbedChannelRequest bind(String rawBody) {
        if (rawBody == null || rawBody.isEmpty()) {
            throw BizException.badRequest(JsonBodyErrors.EMPTY_BODY);
        }
        try {
            return MAPPER.readValue(rawBody, EmbedChannelRequest.class);
        } catch (Exception e) {
            throw BizException.badRequest((e.getMessage() == null ? "" : e.getMessage()));
        }
    }

    /**
     * allowed_origins 的列值。create 路径：缺键（null 节点）落库为字符串 "null"
     * （但 handler 校验会先拒掉缺键/空数组，实际到不了 service）；存在则原样文本。
     */
    static String allowedOriginsColumn(JsonNode node) {
        return node == null ? "null" : node.toString();
    }

    /** JsonNode → List&lt;String&gt;（校验入口）。非数组 → 空列表，非字符串元素按空串。 */
    static List<String> stringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node == null) {
            return out;
        }
        if (node.isArray()) {
            for (JsonNode n : node) {
                out.add(n.asString(""));
            }
        }
        return out;
    }

    static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
