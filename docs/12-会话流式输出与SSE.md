# 12. 会话、流式输出与 SSE

## 12.1 会话与消息模型

会话/消息两张表都在 `migrations/versioned/V1__baseline.sql`：

| 表 | 关键字段 |
|---|---|
| `sessions` | `id`、`tenant_id`、`user_id`、`title`、`agent_id`、`agent_config`（jsonb）、检索参数列（`max_rounds`、`vector_threshold`、`rerank_top_k` 等）、`is_pinned` |
| `messages` | `id`、`request_id`、`session_id`、`role`（`user`/`assistant`）、`content`、`is_completed`、`knowledge_references`/`agent_steps`/`images`/`attachments`/`used_memories`/`usage`（jsonb）、`execution_context`、`channel`、`agent_id`/`agent_tenant_id`/`model_id` |

**落库时机**：用户消息先落——`QaTurnExecutor.persistTurnMessages` 在建流之前把 user/assistant 两行**同事务**写入（`MessageService.createTurnMessages`），assistant 行 `is_completed=false` 占位；assistant 内容在执行中只进内存与流，最终由 `QaTurnFinalizer.completeAssistantMessage` 收尾（`MessageService.completeMessageOnce` CAS 赢家才做索引/建议/记忆等下游动作，输家只补一次幂等落库）。

**为什么不包大事务**：一轮问答从落库到收尾横跨整段 LLM 流式生成（分钟级），大事务会长时间占用连接与行锁。所以拆成三段短事务：同事务写两行 → 流式执行（零库写）→ CAS 收尾 + 异步索引/建议/记忆。并发正确性靠 `TurnLifecycle`（`STREAMING → FINALIZING → DONE/FAILED/CANCELLED`，终态单赢家）与 `completeMessageOnce` 保证，不靠事务隔离。

## 12.2 SSE 帧协议

帧由 `SseFrameWriter` 手工拼（不用 Spring `SseEmitter`）：`event:message\ndata:<JSON>\n\n`——事件名恒为 `message`，冒号后无空格；`data` 是 `StreamResponse` 的 JSON，业务类型在 `response_type` 字段（`ResponseType` 枚举共 23 个值）。四个 SSE 头（`Content-Type: text/event-stream`、`Cache-Control: no-cache`、`Connection: keep-alive`、`X-Accel-Buffering: no`）必须在**任何正文写出之前**设置（`SseContract.setSSEHeaders`），否则参数校验失败就退不回普通 400 JSON。

前端可见的事件帧来自 `AgentStreamBridge` 订阅的 16 种 `EventType`（订阅序即回调序）：

| 事件 → 帧类型 | 何时发 | 关键字段（`data`） |
|---|---|---|
| `EVENT_AGENT_THOUGHT` → `thinking` | 模型思考增量 | `eventId`、`durationMs`（done 时） |
| `EVENT_AGENT_TOOL_CALL` → `toolCall` | 引擎发起工具调用 | `toolName`、`arguments`、`toolCallId` |
| `EVENT_AGENT_TOOL_RESULT` → `toolResult`/`error` | 工具返回（成败都发） | `toolName`、`success`、`error`、`durationMs` |
| `EVENT_AGENT_REFERENCES` → `references` | 检索引用就绪 | `references`（`SearchResult` 列表） |
| `EVENT_MEMORY_RECALLED` → `memoryRecalled` | 记忆召回命中 | `memories` |
| `EVENT_CONTEXT_COMPACTED` → `contextCompacted` | 上下文被摘要压缩 | `reason`、`tokensBefore/After`、`summary` |
| `EVENT_USER_MESSAGE_INJECTED` → `userMessageInjected` | steer 注入当轮 | `steerId`、`messageId`、`content` |
| `EVENT_AGENT_FINAL_ANSWER` → `answer` | 最终答案增量 | `eventId`、`durationMs`、`isFallback` |
| `EVENT_AGENT_REFLECTION` → `reflection` | 反思增量 | — |
| `EVENT_ERROR` → `error`（done=true） | 执行异常 | `stage`、`error` |
| `EVENT_SESSION_TITLE` → `sessionTitle` | 首轮标题生成完成 | `sessionId`、`title` |
| `EVENT_AGENT_COMPLETE` → `complete` | 轮次完成（唯一一次） | `totalSteps`、`totalDurationMs`、`finalContent`、`usage` |
| `EVENT_TOOL_APPROVAL_REQUIRED/RESOLVED` → `toolApprovalRequired/Resolved` | MCP 危险工具审批 | `pendingId` |
| `EVENT_MCP_OAUTH_REQUIRED/RESOLVED` → `mcpOauthRequired/Resolved` | MCP OAuth 授权 | `pendingId` |

另有 `agentQuery`（`QaSseOrchestrator.writeAgentQueryEvent` 直写入流，客户端收到的第一帧）与 `stop`（`TurnStreamPump.writeStopFrame`）。

> **不要改的顺序契约**（核实自源码）：
> 1. `complete` 只发一次——`handleComplete` 每轮只追加一条 `COMPLETE` 事件，终态提交由 `TurnLifecycle` CAS 保证单赢家；
> 2. 错误帧在前、终态在后——`answer/thinking/reflection` 是增量型走扣留缓冲，`complete/error` 是终态型，写终态帧前必须 `flushHeldStreamContent` 冲出增量尾巴；
> 3. SSE 头必须先写——`setLiveRun`（409/503）在头之前，非法 `resource_urls` 的 400 也在头之前，`continue-stream` 先解析 `resource_urls` 再设头；
> 4. 消息先落库再建流，建流失败回滚两行；
> 5. steer carry-over 必须在 `setLiveRun` 公布 live 之前 append。

## 12.3 流管理器

`StreamManager` 是极简 **append-only** 设计：所有流状态都由事件承载（元信息、引用、完成标记都是事件），无独立元数据存储。双实现由 `StreamManagerConfig` 按 `weknora.stream.type`（环境变量 `STREAM_MANAGER_TYPE`）选择：`memory`（默认，`MemoryStreamManager`，进程内 `ConcurrentHashMap`）或 `redis`（精确匹配字符串 `redis`，`RedisStreamManager`，Redis List 存储）。**选 redis 时启动即 Ping 一次 Redis，连不上服务起不来**——不静默退化；事件列表与 live-run 标记的 TTL 默认 1 小时。steer 那组 API（`appendSteerEvents`/`getSteerEvents`/`updateSteerEventData`/`deleteSteerEvent`）落在独立子列表上，是控制面。

`TurnStreamPump` 是一轮问答唯一的读流循环：有客户端 100ms 间隔（`SSE_INTERVAL_MILLIS`）、客户端断开后降级为 300ms 无头监控（`HEADLESS_INTERVAL_MILLIS`）继续盯 `stop`/终态并发出取消——取消能力不随连接消失；单轮上限 2 小时（`MAX_DURATION_MILLIS`），超时走同一 `stop` 协议取消。标题等待窗口 3 秒（`TITLE_WAIT_MILLIS`，50ms 轮询）。

## 12.4 会话的其他能力

- **建议问题**：`MessageSuggestionController`（`POST/GET /api/v1/sessions/{sessionId}/messages/{messageId}/suggestions`、`POST .../suggestion-events`）。追问建议在主流程结束后异步发起——依据 assistant 行 `execution_context` 里的 `AgentConfigSnapshot`（配置哈希 + 建议开关）判断是否生成；集合进入 generating 态返回 202。
- **附件与图片**：内联 base64 图片（需 agent `imageUploadEnabled`，SSRF 防护先清空客户端 url/caption）与 `attachmentUploads`（每请求最多 5 个、单文件 100MB、总量 100MB）在 `QaRequestParser` 解析；预上传附件走 `TemporaryDocumentController`（`POST/GET/DELETE /api/v1/sessions/{sessionId}/attachments...`，含 preview）。
- **消息文件代理**：`MessageFileProxyController` 的 `GET /api/v1/sessions/{id}/messages/{messageId}/files` 经 `FileProxyPort` 代理消息产物文件。
- **标题生成**：会话 `title` 为空时 `SessionService.generateTitleAsync` 起虚拟线程异步生成，完成后发 `EVENT_SESSION_TITLE` 事件（经 `AgentStreamBridge` 变成 `sessionTitle` 帧）；首轮推流泵会为它多等 3 秒，避免标题帧永远到不了客户端。

> **边界**：`SseContract.sendCompletionEvent` 是刻意的空实现——完成信号由 `complete` 帧承载，再补一个 `done=true` 的空 `answer` 帧会扰乱前端状态机；`Content-Type` 最终被 SSE 渲染器无条件覆盖为 `text/event-stream;charset=utf-8`（`SseFrameWriter.RENDERED_CONTENT_TYPE`），`Cache-Control: no-cache` 保持不变。


---

[上一章：11. 工具、技能与 MCP](11-工具技能与MCP.md) ｜ [返回目录](详细技术文档.md) ｜ [下一章：13. 记忆体系](13-记忆体系.md)
