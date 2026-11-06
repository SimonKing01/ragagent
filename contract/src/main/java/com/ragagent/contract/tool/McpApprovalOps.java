package com.ragagent.contract.tool;


/**
 * MCP OAuth 授权与工具审批解析的操作面（L1）：embed 渠道把访客 UI 的动作转给这两个操作族。
 *
 * <p><b>为什么是端口</b>：embedchannel 此前直接注入 mcp 的 {@code McpOAuthController} 与
 * {@code AgentToolApprovalController}（**跨域直调他域 controller**，违反 硬规则 1 ✗），
 * 还要把访客请求体解析成 mcp 的嵌套 DTO（{@code McpOAuthController.AuthorizeRequest} /
 * {@code ResolveToolApprovalRequest}）✗。本端口把这一族操作收回 mcp 实现
 * （{@code mcp/api/McpApprovalOpsAdapter}），调用方只传**原始 body 字符串**。</p>
 *
 * <p><b>为什么不直接走 {@code ToolApprovalGate.resolve}</b>：mcp 的审批 controller 在闸门之前还做了
 * 租户/主体校验与错误映射（{@code BizException} 语义）——embed 路由依赖这套校验 ✗，
 * 绕过去等于削安全边界。故端口按"**操作语义**"（＝ controller 的对外语义）暴露，而非按闸门原语。</p>
 *
 * <p><b>返回形态</b>：与既有 HTTP 面一致（恒 200 + body）——授权 URL 返回 {@link AuthorizationUrl} 载荷；
 * 状态查询返回 {@code Object}（调用方原样作响应体）。</p>
 */
public interface McpApprovalOps {

    /** 授权 URL 载荷：{authorizationUrl, authorizationAttempt}。 */
    record AuthorizationUrl(String authorizationUrl, String authorizationAttempt) {
    }

    /** 取 MCP OAuth 授权 URL；{@code rawBodyJson} 为空/null 时等价于无 body。 */
    AuthorizationUrl authorizeUrl(String serviceId, String rawBodyJson);

    /** OAuth 授权状态；{@code attemptId} 可为 null/空。 */
    Object authorizationStatus(String serviceId, String attemptId);

    /** 解析一个待决的 MCP OAuth 授权（用户在 UI 上选同意/拒绝）。 */
    void resolveAuthorization(String pendingId, String rawBodyJson);

    /** 取消待决的 MCP OAuth 授权。 */
    void cancelAuthorization(String pendingId);

    /** 解析一个待决的工具审批（用户在 UI 上允许/拒绝）。 */
    void resolveToolApproval(String pendingId, String rawBodyJson);
}
