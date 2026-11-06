/**
 * 工具契约面：工具执行结果载荷与工具取消/能力词汇。
 *
 * <p>当前成员：{@link com.ragagent.contract.tool.ToolResult}（结果载荷）、
 * {@link com.ragagent.contract.tool.ToolCancellation}（取消探测）、
 * {@link com.ragagent.contract.tool.ToolCapabilities}（内建工具的 KB 能力词汇，与前端
 * {@code tool-capabilities.ts} 镜像）、{@link com.ragagent.contract.tool.McpApprovalOps}
 * （embed 渠道转 MCP 授权/工具审批的操作面）。审批闸门与策略族已回
 * {@code com.ragagent.mcp.approval}，本包不再保留审批 SPI。</p>
 *
 * <p>工具 SPI：{@link com.ragagent.contract.tool.AgentTool} / {@link com.ragagent.contract.tool.BaseTool}
 * （纯 JDK 基类，schema 为 {@code Object}）/ {@code ToolDefinitions} / {@code ToolRequest} /
 * {@code ToolExecContext} / {@code ToolRegistryPort}。</p>
 */
package com.ragagent.contract.tool;
