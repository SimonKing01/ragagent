package com.ragagent.auth.controller;


import com.fasterxml.jackson.annotation.JsonProperty;
import com.ragagent.auth.domain.User;
import com.ragagent.auth.dto.AuthLoginResponse;
import com.ragagent.auth.dto.TenantResponse;
import com.ragagent.auth.dto.TokenPairResponse;
import com.ragagent.auth.service.LoginResult;
import com.ragagent.auth.service.UserService;
import com.ragagent.contract.error.AppError;
import com.ragagent.contract.error.BizException;
import com.ragagent.contract.tenant.TenantRole;
import com.ragagent.platform.web.JsonBodyErrors;
import com.ragagent.platform.web.RequestFields;
import com.ragagent.platform.web.ToolJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * AuthController 的会话簇：logout / refresh / switch-tenant 三端点执行体与
 * refresh body 绑定（RefreshTokenRequest 随簇）、active tenant 组装、switch 请求解析。
 */
final class AuthSessionOps {

    private static final Logger log = LoggerFactory.getLogger(AuthSessionOps.class);

    private final AuthController service;

    AuthSessionOps(AuthController service) {
        this.service = service;
    }

    ResponseEntity<Void> logout(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        log.info("Start user logout");
        if (authHeader == null || authHeader.isEmpty()) {
            throw new BizException(AppError.validation("Authorization header is required"));
        }
        String[] tokenParts = authHeader.split(" ", -1);
        if (tokenParts.length != 2 || !"Bearer".equals(tokenParts[0])) {
            throw new BizException(AppError.validation("Invalid Authorization header format"));
        }
        try {
            service.userService.logout(tokenParts[1]);
        } catch (UserService.LogoutException e) {
            throw new BizException(AppError.internal("Logout failed").withDetails(e.getMessage()));
        }
        return ResponseEntity.noContent().build();
    }

    // ── refresh ──

    ResponseEntity<TokenPairResponse> refreshToken(
            @RequestBody(required = false) String rawBody) {
        log.info("Start token refresh");
        RefreshTokenRequest req = bindRefreshBody(rawBody);
        if (req == null || req.refreshToken() == null || req.refreshToken().isEmpty()) {
            throw service.invalidParams("Invalid refresh token request",
                    service.bindingError("RefreshToken", "required"));
        }
        String[] tokens;
        try {
            tokens = service.userService.refreshToken(req.refreshToken());
        } catch (UserService.RefreshTokenException e) {
            throw new BizException(AppError.unauthorized("Token refresh failed").withDetails(e.getMessage()));
        }
        return ResponseEntity.ok(new TokenPairResponse(tokens[0], tokens[1]));
    }

    RefreshTokenRequest bindRefreshBody(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            throw service.invalidParams("Invalid refresh token request", JsonBodyErrors.EMPTY_BODY);
        }
        com.fasterxml.jackson.databind.JsonNode root;
        try {
            root = AuthController.MAPPER.readTree(rawBody);
        } catch (Exception e) {
            throw service.invalidParams("Invalid refresh token request",
                    e.getMessage());
        }
        RefreshTokenRequest req = new RefreshTokenRequest();
        if (root == null || root.isNull()) {
            return req;
        }
        if (!root.isObject()) {
            throw service.invalidParams("Invalid refresh token request",
                    ToolJson.expectedObjectMessage(root));
        }
        com.fasterxml.jackson.databind.JsonNode node = root.get("refreshToken");
        if (node == null || node.isNull() || node.isTextual()) {
            req.refreshToken = node == null || node.isNull() ? null : node.asText();
            return req;
        }
        throw service.invalidParams("Invalid refresh token request",
                RequestFields.wrongType("RefreshToken", "string", ToolJson.nodeTypeLabel(node)));
    }

    static String extractSwitchRefreshToken(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return "";
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root = AuthController.MAPPER.readTree(rawBody);
            com.fasterxml.jackson.databind.JsonNode node = root == null ? null : root.get("refresh_token");
            if (node != null && node.isTextual()) {
                return node.asText();
            }
        } catch (Exception ignored) {
            // 语法错误已在 bindSwitchTenantRequest 报过
        }
        return "";
    }

    // ── switch-tenant ──

    ResponseEntity<AuthLoginResponse> switchTenant(
            @RequestBody(required = false) String rawBody) {
        long tenantId = bindSwitchTenantRequest(rawBody);
        String currentRefreshToken = extractSwitchRefreshToken(rawBody);

        User user = service.userService.getCurrentUser();
        if (user == null) {
            throw new BizException(AppError.unauthorized("not authenticated"));
        }

        LoginResult result;
        try {
            result = service.userService.switchTenant(user, tenantId, currentRefreshToken);
        } catch (UserService.SwitchTenantException e) {
            log.warn("SwitchTenant failed user={} target={}", user.getId(), tenantId, e);
            throw new BizException(AppError.forbidden("workspace switch failed").withDetails(e.getMessage()));
        }
        return ResponseEntity.ok(new AuthLoginResponse(result.user(), activeTenantResponse(result),
                result.memberships(), result.token(), result.refreshToken()));
    }

    TenantResponse activeTenantResponse(LoginResult result) {
        if (result.activeTenant() == null) {
            return null;
        }
        String role = AuthController.membershipRoleForTenant(result.memberships(),
                result.activeTenant().getId());
        return TenantResponse.from(result.activeTenant(),
                TenantRole.fromString(role).hasPermission(TenantRole.ADMIN));
    }

    long bindSwitchTenantRequest(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            throw service.invalidParams("Invalid workspace switch request", JsonBodyErrors.EMPTY_BODY);
        }
        com.fasterxml.jackson.databind.JsonNode root;
        try {
            root = AuthController.MAPPER.readTree(rawBody);
        } catch (Exception e) {
            throw service.invalidParams("Invalid workspace switch request",
                    e.getMessage());
        }
        if (root == null || !root.isObject()) {
            if (root == null || root.isNull()) {
                // body=null → 绑定为缺省对象 → TenantID 为 0 触发 required
                throw service.invalidParams("Invalid workspace switch request",
                        service.bindingError("TenantID", "required"));
            }
            throw service.invalidParams("Invalid workspace switch request",
                    ToolJson.expectedObjectMessage(root));
        }
        com.fasterxml.jackson.databind.JsonNode idNode = root.get("tenantId");
        if (idNode == null || idNode.isNull()) {
            throw service.invalidParams("Invalid workspace switch request",
                    service.bindingError("TenantID", "required"));
        }
        if (!idNode.isNumber()) {
            throw service.invalidParams("Invalid workspace switch request",
                    RequestFields.wrongType("TenantID", "integer", ToolJson.nodeTypeLabel(idNode)));
        }
        // uint64：非负整数，0 也合法解析（validator required 才拒）
        java.math.BigDecimal value = idNode.decimalValue();
        if (value.scale() > 0 && value.stripTrailingZeros().scale() > 0) {
            throw service.invalidParams("Invalid workspace switch request",
                    RequestFields.mustBeInteger("TenantID"));
        }
        if (value.signum() < 0 || value.compareTo(new java.math.BigDecimal("18446744073709551615")) > 0) {
            throw service.invalidParams("Invalid workspace switch request",
                    RequestFields.outOfRange("TenantID"));
        }
        long parsed = value.longValue();
        if (parsed == 0) {
            // uint64 零值 → validator required
            throw service.invalidParams("Invalid workspace switch request",
                    service.bindingError("TenantID", "required"));
        }
        return parsed;
    }


    static final class RefreshTokenRequest {
        @JsonProperty("refreshToken")
        String refreshToken;

        String refreshToken() {
            return refreshToken;
        }
    }
}
