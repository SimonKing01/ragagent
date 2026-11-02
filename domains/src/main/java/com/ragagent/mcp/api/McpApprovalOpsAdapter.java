package com.ragagent.mcp.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.contract.error.BizException;
import com.ragagent.contract.tool.McpApprovalOps;
import com.ragagent.mcp.dto.ResolveToolApprovalRequest;
import com.ragagent.mcp.service.AgentToolApprovalService;
import com.ragagent.mcp.service.McpOAuthFlowService;
import com.ragagent.mcp.service.McpOAuthFlowService.AuthorizeRequest;
import com.ragagent.mcp.service.McpOAuthFlowService.ResolveRequest;
import com.ragagent.platform.web.JsonMappers;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@link McpApprovalOps} 的 mcp 侧实现：把 embed 渠道传来的原始 body 解析成既有 DTO，再调用例层。
 *
 * <p><b>零行为变化</b>：请求体解析（含解析失败的 {@code BizException.badRequest} 语义）由 embedchannel
 * 移到本类，其余（租户/主体校验、闸门调用、错误映射）走用例层（原先在 controller，方向倒挂已解）；
 * 返回值与 HTTP 响应体一致（恒 200 + body）。</p>
 */
@Component
public class McpApprovalOpsAdapter implements McpApprovalOps {

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    private final McpOAuthFlowService oauth;
    private final AgentToolApprovalService toolApproval;

    public McpApprovalOpsAdapter(McpOAuthFlowService oauth, AgentToolApprovalService toolApproval) {
        this.oauth = oauth;
        this.toolApproval = toolApproval;
    }

    @Override
    public Map<String, Object> authorizeUrl(String serviceId, String rawBodyJson) {
        AuthorizeRequest req =
                parse(rawBodyJson, AuthorizeRequest.class);
        return oauth.authorizeUrl(serviceId, req);
    }

    @Override
    public Object authorizationStatus(String serviceId, String attemptId) {
        return oauth.status(serviceId, attemptId);
    }

    @Override
    public void resolveAuthorization(String pendingId, String rawBodyJson) {
        oauth.resolveMcpOAuth(pendingId, parse(rawBodyJson, ResolveRequest.class));
    }

    @Override
    public void cancelAuthorization(String pendingId) {
        oauth.cancelMcpOAuth(pendingId);
    }

    @Override
    public void resolveToolApproval(String pendingId, String rawBodyJson) {
        toolApproval.resolveToolApproval(pendingId, parse(rawBodyJson, ResolveToolApprovalRequest.class));
    }

    /** 与迁移前 embedchannel 内的解析口径一致：空 body ⇒ null；解析失败 ⇒ 400 且带原始原因。 */
    private static <T> T parse(String rawBodyJson, Class<T> type) {
        if (rawBodyJson == null || rawBodyJson.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readValue(rawBodyJson, type);
        } catch (Exception e) {
            throw BizException.badRequest(e.getMessage() == null ? "" : e.getMessage());
        }
    }
}
