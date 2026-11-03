package com.ragagent.mcp.api;

import com.ragagent.contract.pipeline.McpToolRegistrationPort;
import com.ragagent.contract.pipeline.McpToolingGateway;
import com.ragagent.contract.pipeline.ToolRegistryPort;
import com.ragagent.mcp.approval.ToolApprovalGate;
import com.ragagent.mcp.domain.McpService;
import com.ragagent.mcp.protocol.McpClientManager;
import com.ragagent.mcp.service.McpMetadataService;
import com.ragagent.mcp.service.McpServiceManagement;
import com.ragagent.mcp.tool.McpExposure;
import com.ragagent.mcp.tool.McpTooling;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link McpToolRegistrationPort} 的 MCP 域实现：注册编排（列表 → 启用过滤 →
 * 按需暴露）逐字保留自会话装配的原块——"selected 模式列出所选服务"、"只注册
 * 已启用服务"、各分支日志文案与级别均不变；身份与装载参数说明见
 * {@link McpExposure} / {@code McpOAuthSupport}。
 */
@Component
public class McpToolRegistrationAdapter implements McpToolRegistrationPort {

    private static final Logger log =
            LoggerFactory.getLogger(McpToolRegistrationAdapter.class);

    private final McpServiceManagement mcpServiceService;
    private final McpMetadataService mcpMetadataService;
    private final McpClientManager mcpClientManager;
    private final ToolApprovalGate toolApprovalGate;

    public McpToolRegistrationAdapter(McpServiceManagement mcpServiceService,
                                      McpMetadataService mcpMetadataService,
                                      McpClientManager mcpClientManager,
                                      ToolApprovalGate toolApprovalGate) {
        this.mcpServiceService = mcpServiceService;
        this.mcpMetadataService = mcpMetadataService;
        this.mcpClientManager = mcpClientManager;
        this.toolApprovalGate = toolApprovalGate;
    }

    @Override
    public McpToolingGateway createTooling(ToolRegistryPort registry) {
        return new McpTooling(registry);
    }

    @Override
    public void registerOnDemandTools(long tenantId, String selectionMode,
            List<String> selectedServiceIds, ToolRegistryPort registry,
            McpToolingGateway tooling, int authWaitTimeoutSeconds) {
        if (tenantId == 0) {
            // 租户缺失直接 return；补一条日志——否则该分支
            // 完全静默，装配线程丢租户时表现为"MCP 工具凭空消失"（排查成本高）。
            log.info("Skipping MCP registration: no tenant in execution context");
            return;
        }

        List<McpService> services;
        try {
            if ("selected".equals(selectionMode)) {
                services = mcpServiceService.listMCPServicesByIDs(tenantId, selectedServiceIds);
                log.info("Using {} selected MCP services from agent config", services.size());
            } else {
                services = mcpServiceService.listMCPServices(tenantId);
            }
        } catch (RuntimeException e) {
            log.warn("Failed to list MCP services", e);
            return;
        }

        List<McpService> enabled = new ArrayList<>();
        for (McpService service : services) {
            if (service != null && service.isEnabled()) {
                enabled.add(service);
            }
        }
        if (enabled.isEmpty()) {
            return;
        }

        try {
            int registered = McpExposure.registerMcpTools(
                    registry,
                    tooling,
                    enabled,
                    mcpClientManager,
                    toolApprovalGate,
                    authWaitTimeoutSeconds,
                    tenantId,
                    mcpServiceService::getMCPServiceById,
                    new McpExposure.McpMetadataIO(
                            mcpMetadataService::getMCPMetadata,
                            mcpMetadataService::persistMCPMetadata),
                    // hasToolExecContext=false：装配发生在引擎准备阶段，没有 per-turn 的
                    // ToolExecContext；OAuth 服务无快照时给出"先去授权"的方向。
                    false,
                    toolApprovalGate::requestOAuthAndWait);
            log.info("Registered {} MCP service(s) for on-demand discovery", registered);
        } catch (Exception e) {
            log.warn("Failed to register MCP directory", e);
        }
    }
}
