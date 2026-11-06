/**
 * Agent 运行面的**工具框架**（`agent.tool/`）：工具契约与基类、定义与 schema、注册表、
 * 参数校验与投射、执行上下文与预算、输出持久化、取消与能力标记、通用单件工具。
 *
 * <p>子包：{@code runtime/}（工具结果运行时修正：JSON 修复 / 消息净化 / think 块与流式切分 /
 * 工具调用 ID 归一化 / 持久化）。MCP 编排端口是 {@code contract.pipeline.McpToolingGateway}。</p>
 *
 * <p>**边界**：这里是引擎侧框架，**不含**按业务能力分族的工具实现（那些在
 * {@code com.ragagent.agenttools}）。方向固定 **实现面 → 本面**，反向由
 * {@code ArchitectureRulesTest}零容忍守卫（由 {@code agenttools} 迁入）。</p>
 */
package com.ragagent.agent.tool;
