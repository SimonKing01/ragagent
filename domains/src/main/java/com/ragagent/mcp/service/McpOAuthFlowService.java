package com.ragagent.mcp.service;

import com.ragagent.contract.error.AppError;
import com.ragagent.contract.error.BizException;
import com.ragagent.contract.tool.McpApprovalOps;
import com.ragagent.mcp.approval.Decision;
import com.ragagent.mcp.approval.ToolApprovalException;
import com.ragagent.mcp.approval.ToolApprovalGate;
import com.ragagent.mcp.domain.McpPrincipal;
import com.ragagent.mcp.domain.McpService;
import com.ragagent.mcp.oauth.OAuthManager;
import com.ragagent.mcp.oauth.model.OAuthAuthorizationStatus;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.platform.security.LogSanitizer;
import com.ragagent.platform.web.JsonBodyErrors;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * MCP OAuth 流程的用例层：发起授权、授权状态查询、会话内 OAuth 决议与取消。
 *
 * <p>HTTP 端点（{@code McpOAuthController}）与 embed 委托面
 * （{@code mcp.api.McpApprovalOpsAdapter}）共用本类——两个面语义恒一致；
 * 原先 embed 面直接回调控制器，方向倒挂（api → controller），已改经本层。
 * 公开回调（callback）与撤销（revoke）是纯 HTTP 面，留在控制器。</p>
 */
@Service
public class McpOAuthFlowService {

    private static final Logger log = LoggerFactory.getLogger(McpOAuthFlowService.class);

    /** 前端地址缺失时的兜底。 */
    static final String FALLBACK_REDIRECT = "/";

    private final OAuthManager oauth;
    private final McpServiceManagement svc;
    private final ObjectProvider<ToolApprovalGate> gate;

    public McpOAuthFlowService(OAuthManager oauth, McpServiceManagement svc,
            ObjectProvider<ToolApprovalGate> gate) {
        this.oauth = oauth;
        this.svc = svc;
        this.gate = gate;
    }

    /** 发起授权的请求体：前端按 {@code redirectUri} / {@code frontendRedirect} 提交。 */
    public record AuthorizeRequest(String redirectUri, String frontendRedirect) {
    }

    /** 会话内 OAuth 处理的请求体（键名 {@code serviceId}）。 */
    public record ResolveRequest(String serviceId, String decision) {
    }

    /** 发起授权：200 + {authorizationUrl, authorizationAttempt}。 */
    public McpApprovalOps.AuthorizationUrl authorizeUrl(String serviceId, AuthorizeRequest req) {
        long tenantId = tenantIdOrZero();
        TenantContext.Principal principal = McpPrincipal.oauthPrincipalFromContext();
        if (tenantId == 0 || !McpPrincipal.valid(principal)) {
            throw BizException.unauthorized("authentication required");
        }
        if (req == null) {
            // 空 body → 400，文案固定为 "No content to map due to end-of-input"（有契约测试钉死；直连路由同 handler 同文案）
            throw BizException.badRequest(JsonBodyErrors.EMPTY_BODY);
        }
        String redirectUri = trim(req.redirectUri());
        if (redirectUri.isEmpty()) {
            throw new BizException(AppError.validation("redirect_uri is required"));
        }
        String frontendRedirect = trim(req.frontendRedirect());
        if (frontendRedirect.isEmpty()) {
            frontendRedirect = FALLBACK_REDIRECT;
        }

        McpService service;
        try {
            service = svc.getMCPServiceById(tenantId, serviceId);
        } catch (RuntimeException e) {
            service = null;
        }
        if (service == null) {
            throw BizException.notFound("MCP service not found");
        }
        if (service.getAuthConfig() == null || !service.getAuthConfig().isOAuth()) {
            throw new BizException(AppError.validation("MCP service is not configured to use OAuth"));
        }

        OAuthManager.StartResult result;
        try {
            result = oauth.startAuthorization(service, tenantId, principal, redirectUri, frontendRedirect);
        } catch (RuntimeException e) {
            log.error("failed to start MCP OAuth authorization, service_id={}",
                    LogSanitizer.sanitize(serviceId), e);
            throw BizException.internal("failed to start authorization: " + e.getMessage());
        }

        return new McpApprovalOps.AuthorizationUrl(result.authorizationUrl(), result.attemptId());
    }

    /**
     * 授权状态。不带 {@code authorizationAttempt} 时返回 token 生命周期状态；
     * 带的时候<b>只</b>回答"这一次尝试是否完成"（历史 token 不算数）。
     */
    public Object status(String serviceId, String attemptIdRaw) {
        long tenantId = tenantIdOrZero();
        TenantContext.Principal principal = McpPrincipal.oauthPrincipalFromContext();
        if (tenantId == 0 || !McpPrincipal.valid(principal)) {
            throw BizException.unauthorized("authentication required");
        }

        String attemptId = trim(attemptIdRaw);
        if (!attemptId.isEmpty()) {
            boolean authorized;
            try {
                authorized = oauth.isAuthorizationAttemptComplete(tenantId, principal, serviceId, attemptId);
            } catch (RuntimeException e) {
                throw BizException.internal("failed to query authorization status: " + e.getMessage());
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("authorized", authorized);
            data.put("state", authorized ? "authorized" : "pending");
            return data;
        }

        OAuthAuthorizationStatus status;
        try {
            status = oauth.authorizationStatus(tenantId, principal, serviceId);
        } catch (RuntimeException e) {
            throw BizException.internal("failed to query authorization status: " + e.getMessage());
        }
        return status;
    }

    /**
     * 前端在弹窗授权完成后调用；后端<b>先确认 token
     * 真的存在</b>再放行，免得过早/失败的授权把工具调用放回火坑再失败一次。
     */
    public void resolveMcpOAuth(String pendingId, ResolveRequest body) {
        long tenantId = tenantIdOrZero();
        TenantContext.Principal principal = McpPrincipal.oauthPrincipalFromContext();
        String gateUserId = gateUserId();
        if (tenantId == 0 || !McpPrincipal.valid(principal) || gateUserId.isEmpty()) {
            throw BizException.unauthorized("authentication required");
        }
        ToolApprovalGate approvalToolApprovalGate = gate.getIfAvailable();
        if (approvalToolApprovalGate == null) {
            throw BizException.internal("OAuth gate is not configured");
        }
        if (body == null) {
            // 空 body → 400 "No content to map due to end-of-input"（同 authorize-url）
            throw BizException.badRequest(JsonBodyErrors.EMPTY_BODY);
        }
        String serviceId = trim(body.serviceId());
        if (serviceId.isEmpty()) {
            throw new BizException(AppError.validation("service_id is required"));
        }

        String decision = trim(body.decision()).toLowerCase(Locale.ROOT);
        if (decision.isEmpty()) {
            decision = "authorize";
        }

        switch (decision) {
            case "cancel", "reject", "skip" -> {
                resolveToolApprovalGate(approvalToolApprovalGate, tenantId, gateUserId, pendingId, Decision.deny("user canceled"));
                return;
            }
            case "authorize" -> {
                // 继续往下走
            }
            default -> throw BizException.badRequest("decision must be authorize or cancel");
        }

        // 只有用户真的持有 token 才恢复：否则重试只会再撞一次 authorization-required
        boolean authorized;
        try {
            authorized = oauth.isAuthorized(tenantId, principal, serviceId);
        } catch (RuntimeException e) {
            throw BizException.internal("failed to verify authorization: " + e.getMessage());
        }
        if (!authorized) {
            throw BizException.conflict("authorization not completed yet for this MCP service");
        }

        resolveToolApprovalGate(approvalToolApprovalGate, tenantId, gateUserId, pendingId, Decision.allow());
    }

    /** 用户主动跳过授权，以"拒绝"解除 Agent 阻塞。 */
    public void cancelMcpOAuth(String pendingId) {
        long tenantId = tenantIdOrZero();
        String gateUserId = gateUserId();
        if (tenantId == 0 || gateUserId.isEmpty()) {
            throw BizException.unauthorized("authentication required");
        }
        ToolApprovalGate approvalToolApprovalGate = gate.getIfAvailable();
        if (approvalToolApprovalGate == null) {
            throw BizException.internal("OAuth gate is not configured");
        }
        resolveToolApprovalGate(approvalToolApprovalGate, tenantId, gateUserId, pendingId, Decision.deny("user canceled"));
    }

    /** 撤销授权：回收缓存连接的事留给调用方（HTTP 面在响应后回收）。 */
    public void revoke(String serviceId) {
        long tenantId = tenantIdOrZero();
        TenantContext.Principal principal = McpPrincipal.oauthPrincipalFromContext();
        if (tenantId == 0 || !McpPrincipal.valid(principal)) {
            throw BizException.unauthorized("authentication required");
        }
        try {
            oauth.revoke(tenantId, principal, serviceId);
        } catch (RuntimeException e) {
            throw BizException.internal("failed to revoke authorization: " + e.getMessage());
        }
    }

    // ── 内部工具 ───────────────────────────────────────────────────────

    /**
     * token 用 principal 走 oauthPrincipalFromContext（embed 访客会被细分），
     * 而 gate 的 userID 用<b>原始</b> principal 的 StorageID（会话所有者）。
     */
    private static String gateUserId() {
        TenantContext.Principal raw = McpPrincipal.normalize(McpPrincipal.fromContext());
        if (McpPrincipal.valid(raw)) {
            return McpPrincipal.storageId(raw);
        }
        return "";
    }

    private static long tenantIdOrZero() {
        Long tenantId = TenantContext.currentTenantId();
        return tenantId == null ? 0L : tenantId;
    }

    /**
     * {@code gate.Resolve} 的错误映射。
     *
     * <p>四类哨兵 → 404 / 400 各一，其余（INTERNAL）→ 500（文案取异常消息）。</p>
     */
    private static void resolveToolApprovalGate(ToolApprovalGate approvalToolApprovalGate, long tenantId, String gateUserId,
                                    String pendingId, Decision decision) {
        try {
            approvalToolApprovalGate.resolve(tenantId, gateUserId, pendingId, decision);
        } catch (ToolApprovalException e) {
            switch (e.kind()) {
                case PENDING_NOT_FOUND ->
                        throw BizException.notFound("pending authorization not found or already completed");
                case ALREADY_RESOLVED -> throw BizException.badRequest(
                        "pending authorization already resolved (timeout / cancel raced your action)");
                case TENANT_MISMATCH -> throw BizException.badRequest("workspace mismatch");
                case USER_MISMATCH -> throw BizException.badRequest(
                        "user mismatch: only the session owner may resolve this prompt");
                default -> {
                    log.error("failed to resolve MCP OAuth pending, pending_id={}",
                            LogSanitizer.sanitize(pendingId), e);
                    throw BizException.internal(String.valueOf(e.getMessage()));
                }
            }
        }
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
