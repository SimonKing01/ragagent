package com.ragagent.mcp.controller;

import com.ragagent.contract.tool.McpApprovalOps;
import com.ragagent.mcp.oauth.OAuthManager;
import com.ragagent.mcp.oauth.error.OAuthCallbackException;
import com.ragagent.mcp.protocol.McpClientManager;
import com.ragagent.mcp.service.McpOAuthFlowService;
import com.ragagent.mcp.service.McpOAuthFlowService.AuthorizeRequest;
import com.ragagent.mcp.service.McpOAuthFlowService.ResolveRequest;
import com.ragagent.platform.security.LogSanitizer;
import com.ragagent.platform.web.ApiResponse;
import com.ragagent.platform.web.ApiResult;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 每用户维度的 MCP OAuth2 授权码流程 HTTP 层。
 *
 * <h3>端点清单（路由前缀 {@code /api/v1} 由主会话在 WebConfig 注册）</h3>
 * <ol>
 *   <li>{@code POST /mcp-services/{id}/oauth/authorize-url} —— 发起授权，返回浏览器应打开的
 *       授权地址与本次尝试 ID（RBAC：<b>Viewer+</b>）；</li>
 *   <li>{@code GET /mcp-services/{id}/oauth/status} —— 查询授权状态；带
 *       {@code authorizationAttempt} 时只认本次流程（RBAC：<b>Viewer+</b>）；</li>
 *   <li>{@code DELETE /mcp-services/{id}/oauth/token} —— 撤销当前用户的 token，成功回 200 + 外壳
 *       （RBAC：<b>Viewer+</b>）；</li>
 *   <li>{@code GET /mcp-oauth/callback} —— <b>公开、无鉴权</b>（授权服务器回跳不带 WeKnora
 *       bearer；靠一次性 state 自证）。该路径已在
 *       {@code AuthFilter.NO_AUTH_API} 中放行，<b>不得</b>加 RBAC 规则；</li>
 *   <li>{@code POST /agent/mcp-oauth-resolutions/{pendingId}} —— 对话内 OAuth 授权完成后恢复
 *       被暂停的 Agent 工具调用（RBAC：<b>Viewer+</b>）；</li>
 *   <li>{@code POST /agent/mcp-oauth-resolutions/{pendingId}/cancel} —— 用户跳过授权，
 *       解除 Agent 阻塞（RBAC：<b>Viewer+</b>）。</li>
 * </ol>
 *
 * <p><b>主会话注册路由时的路径注意</b>：回调刻意注册在 {@code /mcp-services} 组<b>之外</b>
 * （避开静态段与 {@code /{id}} 动态段的路由歧义）。
 * Spring 的 {@code PathPattern} 对字面量段天然优先于变量段，但为避免任何歧义，
 * 请仍把回调挂在 {@code /api/v1/mcp-oauth/callback} 下。</p>
 *
 * <p>两个依赖刻意用 {@link ObjectProvider} 而非直接注入：{@code ToolApprovalGate} 与
 * {@code McpClientManager} 的 bean 都可能缺失（未接线时），使用处各自做了
 * 缺省判断。</p>
 */
@RestController
@ApiResult
@RequestMapping("/api/v1")
@Tag(name = "MCP")
public class McpOAuthController {

    private static final Logger log = LoggerFactory.getLogger(McpOAuthController.class);

    /** 前端地址缺失时的兜底。 */
    static final String FALLBACK_REDIRECT = "/";

    private final OAuthManager oauth;
    private final ObjectProvider<McpClientManager> mcpManager;
    private final McpOAuthFlowService flowService;

    public McpOAuthController(OAuthManager oauth,
                              ObjectProvider<McpClientManager> mcpManager,
                              McpOAuthFlowService flowService) {
        this.oauth = oauth;
        this.mcpManager = mcpManager;
        this.flowService = flowService;
    }

    // ── 1. 发起授权 ────────────────────────────────────────────────────

    @PostMapping("/mcp-services/{id}/oauth/authorize-url")
    public ResponseEntity<McpApprovalOps.AuthorizationUrl> authorizeUrl(@PathVariable("id") String serviceId,
                                                            @RequestBody(required = false) AuthorizeRequest req) {
        return ResponseEntity.ok(flowService.authorizeUrl(serviceId, req));
    }

    // ── 2. 公开回调 ────────────────────────────────────────────────────

    /**
     * <b>公开路由</b>（无 bearer），靠一次性 state 自证。
     *
     * <p>结果经 URL fragment 回传前端：成功 {@code #mcp_oauth_result=success}，
     * 失败 {@code #mcp_oauth_error=<code>}。</p>
     */
    @GetMapping("/mcp-oauth/callback")
    public ResponseEntity<Void> callback(@RequestParam(value = "state", required = false) String stateRaw,
                                         @RequestParam(value = "code", required = false) String codeRaw,
                                         @RequestParam(value = "error", required = false) String errorRaw) {
        String state = trim(stateRaw);
        String code = trim(codeRaw);
        String providerError = trim(errorRaw);

        if (!providerError.isEmpty()) {
            return redirect(FALLBACK_REDIRECT + "#mcp_oauth_error=" + urlQueryEscape(providerError));
        }
        if (state.isEmpty() || code.isEmpty()) {
            return redirect(FALLBACK_REDIRECT + "#mcp_oauth_error="
                    + urlQueryEscape("missing_code_or_state"));
        }

        String frontendRedirect;
        String serviceId;
        try {
            OAuthManager.CompleteResult result = oauth.completeAuthorization(state, code);
            frontendRedirect = result.frontendRedirect();
            serviceId = result.serviceId();
        } catch (OAuthCallbackException e) {
            frontendRedirect = e.frontendRedirect();
            serviceId = e.serviceId();
            if (frontendRedirect.isEmpty()) {
                frontendRedirect = FALLBACK_REDIRECT;
            }
            // 失败路径不回收连接（旧的传输可能仍然是好的）
            log.error("MCP OAuth callback failed (service_id={})", LogSanitizer.sanitize(serviceId), e);
            return redirect(frontendRedirect + "#mcp_oauth_error=" + urlQueryEscape("authorization_failed"));
        }
        if (frontendRedirect.isEmpty()) {
            frontendRedirect = FALLBACK_REDIRECT;
        }
        // 旧传输可能是在已被判定失效的 OAuth 客户端注册上建的；按刚落库的新 token/客户端重建
        closeClient(serviceId);
        return redirect(frontendRedirect + "#mcp_oauth_result=success");
    }

    // ── 3. 授权状态 ────────────────────────────────────────────────────

    /**
     * 不带 {@code authorizationAttempt} 时返回 token 生命周期状态；
     * 带的时候<b>只</b>回答"这一次尝试是否完成"（历史 token 不算数）。
     */
    @GetMapping("/mcp-services/{id}/oauth/status")
    public ResponseEntity<?> status(
            @PathVariable("id") String serviceId,
            @RequestParam(value = "authorizationAttempt", required = false) String attemptIdRaw) {
        return ResponseEntity.ok(flowService.status(serviceId, attemptIdRaw));
    }

    // ── 4. 撤销 ────────────────────────────────────────────────────────

    /** 撤销授权：200 + 外壳 + 回收缓存连接，让下一次调用重新走授权。 */
    @DeleteMapping("/mcp-services/{id}/oauth/token")
    public ApiResponse<Void> revoke(@PathVariable("id") String serviceId) {
        flowService.revoke(serviceId);
        closeClient(serviceId);
        return ApiResponse.ok();   // 204 退役 ⇒ 200 + 外壳
    }

    // ── 5. 会话内 OAuth 挂起 / 取消 ─────────────────────────────────────

    /**
     * 前端在弹窗授权完成后调用；后端<b>先确认 token
     * 真的存在</b>再放行，免得过早/失败的授权把工具调用放回火坑再失败一次。
     */
    @PostMapping("/agent/mcp-oauth-resolutions/{pendingId}")
    public ApiResponse<Void> resolveMcpOAuth(
            @PathVariable("pendingId") String pendingId,
            @RequestBody(required = false) ResolveRequest body) {
        flowService.resolveMcpOAuth(pendingId, body);
        return ApiResponse.ok();   // 204 退役 ⇒ 200 + 外壳
    }

    /**
     * 用户主动跳过授权，以"拒绝"解除 Agent 阻塞。
     */
    @PostMapping("/agent/mcp-oauth-resolutions/{pendingId}/cancel")
    public ApiResponse<Void> cancelMcpOAuth(@PathVariable("pendingId") String pendingId) {
        flowService.cancelMcpOAuth(pendingId);
        return ApiResponse.ok();   // 204 退役 ⇒ 200 + 外壳
    }

    // ── 内部工具 ───────────────────────────────────────────────────────

    /** manager 已接线且 serviceId 非空时，回收该服务的缓存连接。 */
    private void closeClient(String serviceId) {
        if (serviceId == null || serviceId.isEmpty()) {
            return;
        }
        McpClientManager manager = mcpManager.getIfAvailable();
        if (manager == null) {
            return;
        }
        try {
            manager.closeClient(serviceId);
        } catch (RuntimeException e) {
            // 回收失败不影响本次响应
            log.warn("failed to close MCP client after OAuth change, service_id={}",
                    LogSanitizer.sanitize(serviceId), e);
        }
    }

    private static ResponseEntity<Void> redirect(String location) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(location))
                .build();
    }

    /**
     * fragment 值的轻量转义：只替换 7 个字符，<b>不是</b>完整的 percent-encoding——
     * 顺序敏感，必须先把 {@code %} 换成 {@code %25} 再处理其余，否则会二次编码。
     */
    static String urlQueryEscape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("%", "%25")
                .replace(" ", "%20")
                .replace("#", "%23")
                .replace("&", "%26")
                .replace("+", "%2B")
                .replace("=", "%3D")
                .replace("?", "%3F");
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
