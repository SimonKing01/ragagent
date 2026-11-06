package com.ragagent.mcp.protocol;

import com.ragagent.mcp.domain.McpService;
import com.ragagent.platform.access.TenantContext;

/**
 * 构造 MCP 客户端所需的配置。
 *
 * <p>OAuth 相关字段只在 {@code service.AuthConfig.AuthType == oauth} 时被消费：
 * token 按 {@code (tenantId, principal, serviceId)} 隔离，每个身份用自己的
 * access/refresh token 连接。</p>
 */
public final class McpClientConfig {

    private final McpService service;
    /** 租户 ID（OAuth token 的作用域之一）。 */
    private final Long tenantId;
    /** 调用者身份。 */
    private final TenantContext.Principal principal;
    /**
     * 兜底身份：principal 缺失/无效时按 web_user 构造 OAuth 作用域
     * （当前主码构造点均不传，仅兼容路径保留）。
     */
    private final String userId;
    /** OAuth 装配（可为 null——非 OAuth 服务不需要）。 */
    private final McpOAuthSupport oauthSupport;

    public McpClientConfig(McpService service) {
        this(service, null, null, null, null);
    }

    public McpClientConfig(McpService service, Long tenantId, TenantContext.Principal principal,
                           String userId, McpOAuthSupport oauthSupport) {
        this.service = service;
        this.tenantId = tenantId;
        this.principal = principal;
        this.userId = userId;
        this.oauthSupport = oauthSupport;
    }

    public McpService service() {
        return service;
    }

    public Long tenantId() {
        return tenantId;
    }

    public TenantContext.Principal principal() {
        return principal;
    }

    public String userId() {
        return userId;
    }

    public McpOAuthSupport oauthSupport() {
        return oauthSupport;
    }

    public McpClientConfig withPrincipal(TenantContext.Principal newPrincipal) {
        return new McpClientConfig(service, tenantId, newPrincipal, userId, oauthSupport);
    }
}
