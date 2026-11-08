# 术语表

每条给一句定义 + 代码落点。**历史代号**单列在最后——它们不是业务概念，读代码时可以直接跳过。

## 知识库与检索

| 术语 | 含义 | 代码落点 |
|---|---|---|
| **知识库 / KB** | 一组文档、分块、索引配置的集合；检索与问答的作用域 | `domains/.../knowledge/domain/KnowledgeBase.java` |
| **文档 / knowledge** | 上传到知识库的一份原始文件（PDF/Markdown/网页等） | `domains/.../knowledge/domain/Knowledge.java` |
| **分块 / chunk** | 文档切分后的检索单元；`parent_text` 类型是给长上下文用的父块 | `domains/.../knowledge/domain/Chunk.java` |
| **向量化 / embedding** | 把文本变成向量。注意：`EmbedChannel` **不是**它，见下 | `contract/.../model/Embedder.java`、`model-runtime/.../embedding/EmbeddingVectors.java` |
| **检索引擎 / retrieve engine** | pgvector / ES / Milvus 等后端适配；由 `RETRIEVE_DRIVER` 或 KB 绑定选择 | `retrieval-engine/.../engine/EngineRegistry.java` |
| **混合检索 / hybrid search** | 向量召回 + 关键词召回，融合后交重排 | `retrieval-engine/.../HybridSearchService.java` |
| **重排 / rerank** | 对召回结果二次打分排序；可选模型 | `model-runtime/.../rerank/`、`PluginRerank` |
| **金片 / golden** | 逐字节钉死的 HTTP 响应样本，共 1,339 份 JSON + 5 份下载/导出类（`.body`+`.headers` 成对）；改响应形状必须显式重录 | `domains/src/testFixtures/resources/contracts/` |
| **图片 OCR** | 入库时对扫描件图片做 OCR：知识库级开关（默认开启）+ 应用级 `ragagent.ocr` 配置（endpoint 为空 = 关闭；并发/大图尺寸全局可调） | `boot/.../application.yml` 的 `ragagent.ocr` 段、`scripts/ocr-server.py` |
| **逐文档覆盖 / process overrides** | 上传/重解析弹窗对单个文档覆盖分块、解析引擎、图片处理（processConfig）与图谱抽取（extractConfig）配置；载荷统一 camel，读侧保留历史 snake 兜底 | `domains/.../knowledge/support/ProcessOverridesResolver.java` |
| **删除回收 / delete_failed** | 删除/清空知识库与数据源同步删除的统一回收用例；物理清理失败落 `delete_failed` 终态，启动时自愈重试 | `domains/.../knowledge/service/management/KnowledgeRecycleService.java` |

## 问答与 Agent

| 术语 | 含义 | 代码落点 |
|---|---|---|
| **管线 / pipeline** | 一次问答按阶段推进的事件链（14 个阶段） | `domains/.../session/rag/PipelineEventType.java` |
| **阶段 / stage** | 管线上的一个事件，如 `chunk_search` / `chunk_rerank` / `chat_completion_stream` | 同上 |
| **插件 / plugin** | 挂在某个阶段上的处理单元；按注册序串成链 | `domains/.../session/rag/plugin/Plugin.java` |
| **装配 / wiring** | 把插件、端口实现、Bean 接起来的地方；读代码找不到执行体时先来这里 | `domains/.../session/assembly/QaWiring.java` |
| **Agent / ReAct** | 思考 → 行动（工具）→ 观察的循环 | `agent-engine/.../agent/AgentEngine.java` |
| **工具 / tool** | Agent 可调用的能力（检索、Wiki、SQL、网页等） | `contract/.../tool/`、`agent-engine/.../agent/tool/ToolRegistry.java` |
| **技能 / skill** | 以 `SKILL.md` 为载体的指令包，按元数据/正文/资源三级注入 | `agent-engine/.../agent/skills/Manager.java` |
| **MCP** | Model Context Protocol：接入外部工具服务的协议 | `domains/.../mcp/` |
| **modelcontext** | 模型上下文装配（消息/工具编码）的包名，**与 MCP 无关** | `model-runtime/.../modelcontext/` |
| **SSE** | Server-Sent Events；问答的流式输出协议 | `domains/.../session/controller/sse/SseFrameWriter.java` |
| **流管理器 / StreamManager** | append-only 事件流；内存 / Redis 双实现 | `platform/.../stream/StreamManager.java` |
| **steer** | 运行中转向：向正在生成的一轮追加用户指令，走独立控制面 | `StreamManager.appendSteerEvents`、`SteerSinkBridge` |

## 组织与接入

| 术语 | 含义 | 代码落点 |
|---|---|---|
| **租户 / tenant** | 数据隔离边界；所有查询都带 tenantId | `platform/.../access/TenantContext.java` |
| **owner** | 租户所有者；首位注册者自动成为 owner | `domains/.../auth/`、`tenant/` |
| **principal** | 当前认证主体（用户或 API Key） | `TenantContext.currentPrincipal()` |
| **Wiki** | 知识库内的协作页面树，与文档/分块是不同形态 | `domains/.../wiki/` |
| **数据源 / datasource** | 飞书、GitLab、Notion 等外部连接器，按计划同步文档 | `domains/.../datasource/` |
| **EmbedChannel** | embed 挂件渠道（把问答嵌到第三方网页），**不是 embedding** | `domains/.../embedchannel/` |

## 工程设施

| 术语 | 含义 | 代码落点 |
|---|---|---|
| **package-info** | 每个包的设计说明，共 357 份，是事实上的架构文档 | 各包 `package-info.java`；索引见 `architecture/packages.md` |
| **守卫 / guard** | `scripts/run-guards.sh` 里的 48 条架构/契约检查 | `scripts/` |
| **棘轮 / ratchet** | 只许减不许增的基线（大文件、域间边、命名口径等） | `scripts/*.baseline.json` |
| **契约 / contract** | 跨模块公开 API、SPI 与不可变载荷；纯 JDK | `contract/` |
| **端口 / port** | 业务域消费能力的接口；实现由组合根注入 | `contract/.../*Port.java` |

## 历史代号（读代码时可直接跳过）

`K4`、`A18`、`M2`、`R3d`、`W5γ5.19`、`B2xx`、`P#`、`R#` 都是 **Go→Java 迁移的批次编号**，不是业务术语：

- `B2xx`：较早的重构/整改批次；
- `P#` / `R#`：v0.3.0 的"载体纠偏"与"三 runtime"批次；
- `K/A/M/W` 系列：知识、Agent、模型、Wiki 等专项的波次。

对照表只存在于 `docs/internal/HANDOFF.md` 与 `git log --grep`。新人读主代码时可以忽略这些前缀；它们不影响行为。

## 前身与遗留命名

- **WeKnora**：本项目参考/复用的上游 Go 项目。代码里的 `weknora.*` 配置前缀、库名 `WeKnora` 都是历史命名，不是当前产品概念；前端对外全局对象自 2026-11 起已改名 `RagAgent`。
- **knowledage-base**：前端包名里的历史拼写错误（正确是 knowledge），仅作标识，不影响功能。
