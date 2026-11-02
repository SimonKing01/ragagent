package com.ragagent.mcp.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.contract.agent.ToolCallTarget;
import com.ragagent.contract.model.ChatMessage;
import com.ragagent.contract.model.ToolCall;
import com.ragagent.contract.pipeline.McpToolingGateway;
import com.ragagent.contract.pipeline.ToolRegistryPort;
import com.ragagent.contract.tool.AgentTool;
import com.ragagent.contract.tool.ToolDefinitions;
import com.ragagent.mcp.domain.McpService;
import com.ragagent.platform.web.JsonMappers;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * MCP 工具编排（原 {@code com.ragagent.agent.tool.ToolRegistry} 的 MCP 目录方法与状态，逐行搬出）。
 *
 * <p>存在意义：这些逻辑必须依赖 {@code com.ragagent.mcp.*} 与审批栈（业务域类型），
 * 留在引擎里会让 agent 无法独立成模块。抽到本类后，引擎只通过
 * {@link McpToolingGateway} / {@link ToolRegistryPort} 两个端口与之交互，
 * 方向恒为「业务域 → 引擎」。</p>
 *
 * <p>本类随 MCP 适配层一起搬往业务域包（见 F 波计划 A-3），届时可见性与 import 同步调整。</p>
 */
public final class McpTooling implements McpToolingGateway {

    private final ToolRegistryPort registry;
    private boolean prepared;
    private boolean direct;

    public McpTooling(ToolRegistryPort registry) {
        this.registry = registry;
    }

    /** 已安装的 MCP 目录（discover_mcp_tools 持有；无则 null）。 */
    McpCatalog catalog() {
        if (registry.get(ToolDefinitions.TOOL_DISCOVER_MCP_TOOLS) instanceof McpDiscoverTool discovery) {
            return discovery.catalog();
        }
        return null;
    }

    @Override
    public synchronized boolean isPrepared() {
        return prepared;
    }

    @Override
    public synchronized boolean isDirect() {
        return direct;
    }

    /**
     * 预先广告来源，describe 之后才加载完整函数。
     * 这是应用层的"按需加载"——生产 reader 用持久化元数据，不做上游发现。
     */
    @Override
    public synchronized void prepare(boolean directMode) {
        McpCatalog c = catalog();
        if (c == null || c.authorizeExecution() != null) {
            return;
        }
        prepared = true;
        direct = directMode;
        if (registry.get(ToolDefinitions.TOOL_DISCOVER_MCP_TOOLS) instanceof McpDiscoverTool discovery) {
            discovery.setExposure(directMode, true);
        }
        synchronized (c.preloadLock) {
            if (!c.preloadStarted) {
                c.preloadStarted = true;
                // 引擎准备阶段不安装 ToolExecContext——此路径无法为每个服务打开会话内 OAuth 提示。
                Thread.ofVirtual().start(() -> {
                    List<String> ids = new ArrayList<>(c.servers.keySet());
                    Collections.sort(ids);
                    int workers = Math.min(8, ids.size());
                    if (workers <= 0) {
                        return;
                    }
                    ExecutorService pool = Executors.newFixedThreadPool(workers,
                            Thread.ofVirtual().factory());
                    BlockingQueue<String> jobs = new LinkedBlockingQueue<>(ids);
                    for (int i = 0; i < workers; i++) {
                        pool.submit(() -> {
                            String id;
                            while ((id = jobs.poll()) != null) {
                                c.snapshot(id, false);
                            }
                        });
                    }
                    pool.shutdown();
                });
            }
        }
        long deadline = System.nanoTime() + McpExposure.MCP_STARTUP_GRACE.toNanos();
        while (System.nanoTime() < deadline) {
            boolean allSettled = true;
            for (McpCatalog.McpCatalogServer entry : c.servers.values()) {
                if (!"ready".equals(entry.status) && !"error".equals(entry.status)
                        && !"needs_auth".equals(entry.status) && !"unavailable".equals(entry.status)
                        && !"disabled".equals(entry.status)) {
                    // not_loaded/loading 都算未安顿
                    if (!"not_loaded".equals(entry.status) && !"loading".equals(entry.status)) {
                        continue;
                    }
                    allSettled = false;
                    break;
                }
            }
            if (allSettled) {
                break;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        refresh();
    }

    /**
     * 在模型请求之间发布就绪定义：包括经发现/OAuth 加载的目录
     * 与初始请求后刷新的目录。这里不做网络发现。定义缓存时策略检查依然新鲜。
     * 只在并行工具执行空闲时调用。
     */
    @Override
    public synchronized void refresh() {
        if (!prepared) {
            return;
        }
        McpCatalog c = catalog();
        if (c == null) {
            return;
        }
        // 为历史保留代理的可执行性，但不向模型提供空 call 面。只在有可用的完整定义时发布。
        registry.setDeferred(ToolDefinitions.TOOL_CALL_MCP_TOOL, true);
        for (AgentTool registered : registry.tools()) {
            if (registered instanceof McpRegisteredTool) {
                registry.remove(registered.getName());
            }
        }
        if (c.authorizeExecution() != null) {
            return;
        }
        List<String> ids = new ArrayList<>(c.servers.keySet());
        Collections.sort(ids);
        for (String id : ids) {
            McpCatalog.McpCatalogServer entry = c.servers.get(id);
            entry.lock.lock();
            McpService service = entry.service;
            List<McpToolWrapper> cached = entry.tools;
            String status = entry.status;
            entry.lock.unlock();
            if (!"ready".equals(status)) {
                continue;
            }
            if (c.lookup != null) {
                McpService current;
                try {
                    current = c.lookup.lookup(c.tenantId, id);
                } catch (Exception err) {
                    continue;
                }
                if (current == null || current.getId() == null || !current.getId().equals(id)
                        || !current.isEnabled()
                        || !McpCatalog.sameInstantPublic(current.getUpdatedAt(),
                                service == null ? null : service.getUpdatedAt())) {
                    continue;
                }
            }
            List<McpToolWrapper> visible;
            try {
                visible = c.visibleTools(id, cached == null ? List.of() : cached);
            } catch (Exception err) {
                continue;
            }
            for (McpToolWrapper tool : visible) {
                if (!direct && !c.advertised(tool)) {
                    continue;
                }
                c.rememberAdvertised(tool);
                McpToolWrapper bound = new McpToolWrapper(tool.service, tool.mcpTool, tool.mcpManager,
                        tool.gate, tool.authWaitTimeoutSeconds, tool.tenantId);
                bound.registeredName = McpToolNaming.mcpRegisteredName(tool);
                bound.serverInstructions = tool.serverInstructions;
                bound.withOAuthWaiter(tool.oauthWaiter());
                registry.registerTool(new McpRegisteredTool(bound, c, McpToolNaming.mcpToolRef(tool)));
                registry.setDeferred(ToolDefinitions.TOOL_CALL_MCP_TOOL, false);
            }
        }
    }

    /**
     * 把本会话已 describe 或调用过的工具重新发布，新引擎无需再 describe 一轮。
     */
    @Override
    public void rememberHistory(List<ChatMessage> messages) {
        McpCatalog c = catalog();
        if (c == null || messages == null) {
            return;
        }
        for (ChatMessage msg : messages) {
            if (msg == null || msg.getToolCalls() == null) {
                continue;
            }
            for (ToolCall call : msg.getToolCalls()) {
                String name = call.getFunction() == null ? "" : call.getFunction().getName();
                if (ToolDefinitions.TOOL_CALL_MCP_TOOL.equals(name)) {
                    try {
                        JsonNode args = PLAIN_READER.readTree(call.getFunction().getArguments());
                        String ref = args.path("toolRef").asText("");
                        if (!ref.isEmpty()) {
                            c.historyRefs.put(ref, Boolean.TRUE);
                        }
                    } catch (Exception ignored) {
                        // 解析失败即忽略该条历史
                    }
                    continue;
                }
                if (name.startsWith("mcp_") && !ToolDefinitions.TOOL_DISCOVER_MCP_TOOLS.equals(name)) {
                    c.historyNames.put(name, Boolean.TRUE);
                }
            }
        }
    }

    /**
     * UI/审计身份与模型的代理调用分离。原始调用名、参数、ID 与
     * provider 元数据保持可回放。
     */
    @Override
    public synchronized ToolCallTarget callTarget(String name, Object rawArg) {
        // 契约面用不透明 JSON：此处按其 JSON 库还原（调用方恒传 JsonNode，兼容其余形态）
        JsonNode raw = rawArg instanceof JsonNode node ? node : JsonMappers.toJsonNode(rawArg);
        AgentTool registered = registry.get(name);
        if (registered == null) {
            return null;
        }
        if (registered instanceof McpRegisteredTool directTool) {
            if (directTool.authorizeCatalog() != null) {
                return null;
            }
            Map<String, Object> args;
            try {
                args = PLAIN_READER.convertValue(raw,
                        PLAIN_READER.getTypeFactory().constructMapType(LinkedHashMap.class, String.class,
                                Object.class));
            } catch (Exception e) {
                return null;
            }
            ToolCallTarget target = new ToolCallTarget();
            target.setName(name);
            target.setArgs(args);
            target.setServiceName(directTool.service.getName());
            target.setToolName(directTool.mcpTool.getName());
            return target;
        }
        if (!(registered instanceof McpCallTool proxy)) {
            return null;
        }
        // 展示层不得在引擎发出调用事件、装好执行/审批上下文之前连接或等 OAuth。
        if (proxy.authorizeForPresentation() != null) {
            return null;
        }
        McpJsonCodec.DecodeResult decoded;
        try {
            decoded = McpJsonCodec.decodeMcpCall(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
        McpToolWrapper tool = proxy.cachedToolForPresentation(decoded.toolRef());
        if (tool == null || !proxy.knownCallableForPresentation(decoded.toolRef())) {
            // 列举会缓存目标，但在 describe 返回这个确切的 schema 引用之前，展示层保持在 call_mcp_tool。
            return null;
        }
        Map<String, Object> input;
        try {
            input = PLAIN_READER.convertValue(decoded.arguments(),
                    PLAIN_READER.getTypeFactory().constructMapType(LinkedHashMap.class, String.class,
                            Object.class));
        } catch (Exception e) {
            return null;
        }
        ToolCallTarget target = new ToolCallTarget();
        target.setName(tool.getName());
        target.setArgs(input);
        target.setServiceName(tool.service.getName());
        target.setToolName(tool.mcpTool.getName());
        return target;
    }

    @Override
    public synchronized boolean hasServer(String id) {
        McpCatalog c = catalog();
        return c != null && c.servers.containsKey(id);
    }

    private static final ObjectMapper PLAIN_READER = JsonMappers.lenient();
}
