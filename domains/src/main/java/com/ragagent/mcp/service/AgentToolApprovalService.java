package com.ragagent.mcp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.contract.error.BizException;
import com.ragagent.mcp.approval.Decision;
import com.ragagent.mcp.approval.ToolApprovalException;
import com.ragagent.mcp.approval.ToolApprovalGate;
import com.ragagent.mcp.domain.McpPrincipal;
import com.ragagent.mcp.dto.ResolveToolApprovalRequest;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.platform.web.JsonBodyErrors;
import com.ragagent.platform.web.JsonMappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 待审批 MCP 工具调用的决议用例：鉴权闸门前置 + 决议解析 + gate 调用与错误映射。
 *
 * <p>HTTP 端点（{@code AgentToolApprovalController}）与 embed 委托面
 * （{@code mcp.api.McpApprovalOpsAdapter}）共用本类——两个面语义恒一致；
 * 原先 embed 面直接回调控制器，方向倒挂（api → controller），已改经本层。
 * 真正的越权防线是 gate 内的 tenant/user 校验（fail-close）。</p>
 */
@Service
public class AgentToolApprovalService {

    private static final Logger log = LoggerFactory.getLogger(AgentToolApprovalService.class);

    private static final ObjectMapper JSON = JsonMappers.lenient();

    /** 工具审批门；未接线时决议报 500 "not configured"。 */
    private final ToolApprovalGate toolApprovalGate;

    @Autowired   // 多构造器（含测试接缝）⇒ 显式指定装配用的这一个
    public AgentToolApprovalService(ObjectProvider<ToolApprovalGate> toolApprovalGateProvider) {
        this(toolApprovalGateProvider.getIfAvailable());
    }

    /** 测试接缝：直接给 gate 实例（null = 未接线，与容器语义一致）。 */
    public AgentToolApprovalService(ToolApprovalGate toolApprovalGate) {
        this.toolApprovalGate = toolApprovalGate;
    }

    /** 决议一次待审批的工具调用；错误经 BizException 映射（404/400/500 各有固定文案）。 */
    public void resolveToolApproval(String pendingId, ResolveToolApprovalRequest body) {
        Long tenantId = TenantContext.currentTenantId();
        long tenant = tenantId == null ? 0L : tenantId;
        if (tenant == 0) {
            throw BizException.badRequest("Workspace ID cannot be empty");
        }
        if (toolApprovalGate == null) {
            throw BizException.internal("Tool approval gate is not configured");
        }
        if (body == null) {
            throw BizException.badRequest(JsonBodyErrors.EMPTY_BODY);
        }

        Decision decision = decisionFrom(body);

        // 无 principal 时回落到 user_id 组成的 web_user
        String gateUserId = McpPrincipal.storageId(McpPrincipal.fromContext());
        // 前置拒绝没有已认证主体的调用。gate 的按主体鉴权本身是 fail-close 的，
        // 但在这里给出 401 能更清楚地指出"鉴权中间件没有填充上下文"。
        if (gateUserId == null || gateUserId.trim().isEmpty()) {
            throw BizException.unauthorized("authenticated user required to resolve tool approval");
        }

        try {
            toolApprovalGate.resolve(tenant, gateUserId, pendingId, decision);
        } catch (ToolApprovalException e) {
            // 四个哨兵 → 404/400 + 各自的固定文案
            switch (e.kind()) {
                case PENDING_NOT_FOUND -> throw BizException.notFound(
                        "pending approval not found or already completed");
                case ALREADY_RESOLVED -> throw BizException.badRequest(
                        "pending approval already resolved (timeout / cancel raced your action)");
                case TENANT_MISMATCH -> throw BizException.badRequest("workspace mismatch");
                case USER_MISMATCH -> throw BizException.badRequest(
                        "user mismatch: only the session owner may resolve this approval");
                default -> {
                    log.error("Failed to resolve tool approval, pending_id={}", pendingId, e);
                    throw BizException.internal(e.getMessage() == null ? "" : e.getMessage());
                }
            }
        }
    }

    /**
     * 把请求体解析成审批决定。
     *
     * <p>{@code modified_args} 只接受<b>非 null 的 JSON 对象</b>：裸的 "null" 若放行，
     * 下游工具会拿到空参数表、静默丢掉原始参数，所以这里前置拒绝。</p>
     */
    private static Decision decisionFrom(ResolveToolApprovalRequest body) {
        // 两个分支都带 reason
        String reason = body.reason() == null ? "" : body.reason();
        String rawDecision = body.decision();
        if ("approve".equals(rawDecision)) {
            JsonNode modified = body.modifiedArgs();
            // 缺失与 JSON null 等价
            if (modified == null || modified.isNull()) {
                return new Decision(true, null, reason, false, false);
            }
            // 非 JSON 对象 → 400。
            // 注意空对象 {} 是合法的"改用空参数表"。
            if (!modified.isObject()) {
                throw BizException.badRequest("modified_args must be a non-null JSON object");
            }
            // 重新序列化成 raw JSON 交给 gate
            return new Decision(true, writeJson(modified), reason, false, false);
        }
        if ("reject".equals(rawDecision)) {
            return new Decision(false, null, reason, false, false);
        }
        throw BizException.badRequest("decision must be approve or reject");
    }

    private static String writeJson(JsonNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (Exception e) {
            throw BizException.badRequest("modified_args must be a non-null JSON object");
        }
    }
}
