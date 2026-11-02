package com.ragagent.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.contract.pipeline.ToolRegistryPort;
import com.ragagent.contract.tool.AgentTool;
import com.ragagent.contract.tool.ToolCancellation;
import com.ragagent.contract.tool.ToolExecContext;
import com.ragagent.contract.tool.ToolResult;
import com.ragagent.platform.web.JsonMappers;
import java.util.List;

/**
 * 注册表端口的**包内实现**：外部（mcp 装配层等）只能看到这些动作，工具表本身仍是私有。
 *
 * <p>：原为 {@link ToolRegistry} 里的**包内匿名类**，契约面搬迁后守卫 只认顶层实现类
 * （匿名/嵌套都不计，"契约悬空"会误报）⇒ 抽成同包顶层类；语义与锁口径逐字不变
 * （所有访问仍经注册表的实例锁访问器）。</p>
 */
final class ToolRegistryPortImpl implements ToolRegistryPort {

    private final ToolRegistry registry;

    ToolRegistryPortImpl(ToolRegistry registry) {
        this.registry = registry;
    }

    @Override
    public AgentTool get(String name) {
        return registry.toolOrNull(name);
    }

    @Override
    public List<AgentTool> tools() {
        return registry.toolsSnapshot();
    }

    @Override
    public void registerTool(AgentTool tool) {
        registry.registerTool(tool);
    }

    @Override
    public void registerDeferredTool(AgentTool tool) {
        registry.registerDeferredTool(tool);
    }

    @Override
    public void remove(String name) {
        registry.removeBoth(name);
    }

    @Override
    public void setDeferred(String name, boolean deferredFlag) {
        registry.putDeferred(name, deferredFlag);
    }

    @Override
    public ToolResult executeInternal(ToolCancellation cancellation, ToolExecContext meta,
            AgentTool tool, Object argsArg) {
        // 契约面用不透明 JSON：还原后再走内部实现（调用方恒传 JsonNode）
        JsonNode args = argsArg instanceof JsonNode node ? node : JsonMappers.toJsonNode(argsArg);
        return registry.executeInternal(cancellation, meta, tool, args);
    }
}
