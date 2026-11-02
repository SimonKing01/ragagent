package com.ragagent.mcp.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.contract.pipeline.McpToolingGateway;
import com.ragagent.contract.pipeline.ToolRegistryPort;
import com.ragagent.contract.tool.AgentTool;
import com.ragagent.contract.tool.BaseTool;
import com.ragagent.contract.tool.ToolRequest;
import com.ragagent.contract.tool.ToolResult;
import com.ragagent.platform.tool.ToolSchemas;
import com.ragagent.platform.web.JsonMappers;
import com.ragagent.platform.web.ToolJson;
import java.util.ArrayList;
import java.util.List;

/**
 * call_mcp_tool：解析目录引用并调用其 MCP 目标。Parameters 只广告完整定义已在
 * 当前函数列表里的引用；执行仍复验作用域、schema 与策略。
 */
public class McpCallTool extends BaseTool {

    private final McpCatalog catalog;
    private final ToolRegistryPort registry;
    private final McpToolingGateway mcpTooling;

    McpCallTool(String name, String description, String parameters, McpCatalog catalog,
            ToolRegistryPort registry, McpToolingGateway mcpTooling) {
        super(name, description, ToolSchemas.parse(parameters));
        this.catalog = catalog;
        this.registry = registry;
        this.mcpTooling = mcpTooling;
    }

    /** 只广告已 describe 的 refs（mcpPrepared 时来自注册的引用）。 */
    @Override
    public Object getParameters() {
        List<String> refs = new ArrayList<>();
        List<McpRegisteredTool> registered = new ArrayList<>();
        for (AgentTool candidate : registry.tools()) {
            if (candidate instanceof McpRegisteredTool mcpTool) {
                registered.add(mcpTool);
            }
        }
        if (mcpTooling != null && mcpTooling.isPrepared()) {
            for (McpRegisteredTool tool : registered) {
                refs.add(tool.ref());
            }
        }
        refs.sort(String::compareTo);
        String schema = McpJsonCodec.mcpSchemaWithEnum(McpCatalog.MCP_CALL_SCHEMA, "toolRef", refs);
        return parseTree(schema);
    }

    private static JsonNode parseTree(String json) {
        try {
            return JsonMappers.lenient().readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("invalid mcp call schema", e);
        }
    }

    /** 解析 catalog 目标并跑共享执行管线。 */
    @Override
    public ToolResult execute(ToolRequest request) {
        ResolveResult resolved = resolve(request, JsonMappers.toJsonNode(request.args()));
        if (resolved.error() != null) {
            return McpJsonCodec.mcpDiscoveryFailure(resolved.error(), "unavailable");
        }
        return registry.executeInternal(request.cancellation(), request.execMeta(), resolved.tool(), resolved.argsNode());
    }

    record ResolveResult(McpToolWrapper tool, JsonNode argsNode, String error) {
    }

    /** 只在受限定目录内解析，绝不碰全局 registry。 */
    private ResolveResult resolve(ToolRequest request, JsonNode raw) {
        String authErr = catalog.authorizeExecution();
        if (authErr != null) {
            return new ResolveResult(null, null, authErr);
        }
        McpJsonCodec.DecodeResult decoded;
        try {
            decoded = McpJsonCodec.decodeMcpCall(raw);
        } catch (IllegalArgumentException e) {
            return new ResolveResult(null, null, e.getMessage());
        }
        String ref = decoded.toolRef();
        McpToolWrapper known = catalog.cachedTool(ref);
        if (known == null) {
            return new ResolveResult(null, null,
                    "unknown tool_ref; do not construct references from names. Copy server_id from "
                            + "discover_mcp_tools source summaries (or list_servers), then list_tools and describe "
                            + "the exact tool. Wait for describe and copy its tool_ref verbatim before retrying");
        }
        McpCatalog.SnapshotResult snap = catalog.snapshot(known.service.getId(), false);
        if (snap.error() != null) {
            return new ResolveResult(null, null, snap.error());
        }
        if (snap.tools() != null) {
            for (McpToolWrapper tool : snap.tools()) {
                if (McpToolNaming.mcpToolRef(tool).equals(ref)) {
                    String checkErr = catalog.checkEnabled(tool);
                    if (checkErr != null) {
                        return new ResolveResult(null, null, checkErr);
                    }
                    if (!catalog.knownCallableRef(ref)) {
                        return new ResolveResult(null, null, String.format(
                                "tool schema has not been described; use discover_mcp_tools(mode=\"describe\", "
                                        + "serverId=%s, tool_name=%s) before calling",
                                ToolJson.quoted(tool.service.getId()),
                                ToolJson.quoted(tool.mcpTool.getName())));
                    }
                    return new ResolveResult(tool, decoded.arguments(), null);
                }
            }
        }
        return new ResolveResult(null, null, "MCP tool is no longer available or enabled; rediscover its definition");
    }

    /** 展示层的鉴权（registry.mcpCallTarget 用；与执行鉴权同源）。 */
    String authorizeForPresentation() {
        return catalog.authorizeExecution();
    }

    McpToolWrapper cachedToolForPresentation(String ref) {
        return catalog.cachedTool(ref);
    }

    boolean knownCallableForPresentation(String ref) {
        return catalog.knownCallableRef(ref);
    }
}
