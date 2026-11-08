# 02 · 业务链路与域划分评估报告（RagAgent）

- 评估人：许清楚（产品经理，MVP 开发专家团）
- 评估对象：/Users/billy/ragagent 七模块后端（:contract 353 / :platform 194 / :model-runtime 152 / :retrieval-engine 95 / :agent-engine 67 / :domains 1559 / :boot 19）与 20 个业务域
- 评估性质：**只读存量评估**。未修改/删除任何现有代码、配置、文档；未执行任何 Gradle 构建；所有统计来自文件读取与 grep/python 静态计数（方法见 §9）
- 评估日期：2026-10-20（所有计数为当日实测值）
- 视角：**从业务链路反向评估模块/域划分**——与 `01-architecture-assessment.md`（架构师视角：拓扑、依赖、守卫）互补，本报告回答"一条业务需求进来会穿过几个域、改一类扩展要动几处、哪些划分与业务事实错配"
- 前置阅读：`docs/architecture/{overview,business-capability-map,domain-index,qa-flow,ingest-flow}.md`

---

## 1. 结论摘要

### 整体 verdict：**pass（链路健康，病灶在"多点登记"不在"链路断裂"）**

一句话概括：**20 个业务域与 7 条核心业务链路基本同构——每条链路都有明确的单入口控制器、单编排类和单数据落点；域间直连被治理到仅剩 6 条边（80 处 import / 48 个类 / 33 个文件），且全部有"族内豁免"登记；跨模块消费 100% 经 contract 端口（唯一登记例外是 `modelcontext` 4 处，`check-module-graph.py` 认定为终态边）。真正拖慢演化的不是"域拆得碎"，而是同一能力在多个模块各有一份登记表（provider 双目录、embedding 双路径、引擎常量双写、RBAC 双策略表、agent 工具装配散落四地）。**

### 量化总览

| 维度 | 判定 | 关键数据（实测） |
|---|---|---|
| 7 条业务链路入口/编排/落点 | 健康 | 每条链路单入口 + 单编排类；七条链路直接入口控制器共 22 个（ingest 8 / chat 8 / agent 0 复用 chat / wiki 1 / IM 2 / datasource 2 / embedchannel 1） |
| 域间直连（domains 内部） | 健康（有治理） | 64 条历史边收敛到 **6 条**（80 import / 48 类 / 33 文件），全部 intra-family 豁免且冻结在 `scripts/domain-contracts.baseline.json` |
| 跨 kind 直连（产品↔集成↔控制面） | 健康 | `check-capability-kinds.py` 口径下 0 条（20 域按 6 种类分级） |
| 跨模块消费（domains → 三个 runtime） | 健康（1 处登记例外） | contract 端口 33 个 `*Port` 接口；唯一直连是 session 4 个文件 import `modelcontext`（已登记为终态边） |
| 装配权归属 | **勉强** | `@Configuration` 类：domains 内 19 个（其中 10 个含 `@Bean` 的真装配：`QaWiring`/`McpWiring`/`EmbedWiring`/`DataSourceWiring`/`*TaskQueueWiring`/`*RedisWiring` 等），boot 11 个（6 个真装配）；**IM 适配器注册在 boot**，与 datasource/knowledge/wiki/embedchannel 的域内自装配模式不一致 |
| 扩展注册机制 | **勉强** | 至少 5 套并存：静态块双处（LLM）/ 构造器 Map（websearch）/ switch 工厂（向量库、存储）/ 域内 Wiring（datasource）/ boot 装配（IM）；同类扩展成本相差 3 倍以上 |
| 能力重复登记 | **红灯** | provider 双目录（26 家 ×2）、embedding 双路径、引擎常量双写（9 vs 7）、RBAC 双策略表（593+705 行）、catalog 空挂（17 登记 / 9 实现） |
| 文档与交付物一致性 | **勉强** | 任务口径中的"评测"功能在全仓不存在（无域/表/控制器/前端页面）；`session` 文件数实测 190 vs 文档 187 |

### 最关键 5 个错配（证据见 §7）

1. **embedding 双路径**：同一"向量化"业务有两个客户端——KB 绑引擎走 `model-runtime` 9 家 embedder 全家桶（`ModelRuntimeAssembler.embedderFor` → `EmbedderFactory`），KB 未绑（本地 pg）走 `knowledge/client/EmbedderClient`（最小 OpenAI 兼容客户端，位于 knowledge 域内）。`ChunkVectorIndexer` 同时持有两条路（`indexAndStore` 176 行 vs `indexAndStoreViaEngine` 246 行）。厂商兼容性只在其中一条上。
2. **LLM provider 双目录**：运行时路由用 `model-runtime/llm/provider/ProviderRegistry`（静态块 + `allProviders()` 两处登记，类注释原话"新增厂商时两处都要加"），HTTP 目录用 `domains/model/service/ModelProviderCatalog`（199 行手写静态数据，由金片 `contracts/model-providers.json` 26 家生成，`ModelContractTest` 字节校验）。加一家 LLM 要改 4–6 处、跨 3 个模块 + 金片。
3. **agent 能力散落四处 + 工具装配靠 session 直接 import**：循环/注册表在 `agent-engine`（另自带 3 个工具），22 个工具在 `agenttools`（按 wiki/knowledge/web/datasource/sql 分 5 包），配置 CRUD 在 `agentmanagement`，而**工具装配发生在 session**——`session/service/AgentToolBackends.java`（552 行）直接 import 26 个 `agenttools` 具体类完成"工具名 → 实例"分发。加一个工具 = 改 session 的具体 import 清单。另有两个命名仅差一个 s 的控制器（`SkillCatalogController` 管理面 / `SkillsCatalogController` 选择器数据源）。
4. **RBAC 策略双表**：JWT 侧路由策略在 `boot/config/RbacRoutePolicies.java`（593 行），API-Key 侧在 `domains/auth/apikey/controller/filter/APIKeyRoutePolicies.java`（705 行），执行器在 `platform/web/RbacInterceptor.java`。新增一个受管端点要同步两张 600+ 行表，且一张在组合根、一张在 auth 域。
5. **"评测"功能不存在**：项目功能口径列有"评测"，但全仓（domains 主码 + 前端 views + 文档）检索不到任何 evaluation/eval 域、表、控制器或页面。要么补做（建议新增 `evaluation` 产品能力域，见 §8-P3），要么从功能口径中删除——**文档承诺了不存在的交付物**。

### 划分提案一句话

**总盘不动（7 模块 / 20 域与业务同构，有治理有基线），做五台定向手术：embedding 单路径化、provider 单目录化、agent 工具注册端口化（消灭 session→agenttools 直连）、RBAC 策略单表化、引擎常量单源化；中期待 6 条族内边清零后，再把 agent-runtime 族（session/agenttools/agentmanagement/memory）拆为独立 Gradle 模块。**

---

## 2. 全局统计（先全局，后抽样）

### 2.1 模块口径（`find <module>/src/main/java -name "*.java"` 实测）

| 模块 | 文件 | 职责 | 对业务链路的角色 |
|---|---:|---|---|
| :contract | 353 | 纯 JDK 契约（24 个子包，33 个 `*Port`） | 所有跨域/跨模块调用的唯一形状 |
| :platform | 194 | Web 外壳 / SSE 流 / 事件总线 / 租户上下文 / RBAC 执行器 | 链路公共机制（不进业务规则） |
| :model-runtime | 152 | 26+1 家 LLM provider、9 家 embedder、7 家 reranker、模型上下文协议 | chat/agent/ingest 的模型侧 |
| :retrieval-engine | 95 | 9 种检索引擎（10 个 Repository 件，ES 分 v7/v8）、混合融合、索引写入 | chat 检索、ingest 索引 |
| :agent-engine | 67 | ReAct 循环、ToolRegistry、技能、压缩、预算 | agent 链路运行时 |
| :domains | 1559 | 20 域 / 51 业务控制器 / 持久层 | 全部业务链路落点 |
| :boot | 19 | 入口 + 11 个 `@Configuration`（6 个跨模块真装配）+ RBAC 路由策略表 | 组合根 |

> 文档口径：overview 称 contract "23 个子包"（实测 24）、domains "1556 文件"（实测 1559，差在 session：文档 187 vs 实测 190）——与 01 报告的"文档计数漂移"结论一致，本报告按实测值引用。

### 2.2 域口径（实测文件数 / 控制器数 / 契约面数）

| 域 | 种类 | 文件 | 控制器 | 契约面 | 业务链路归属 |
|---|---|---:|---:|---:|---|
| knowledge | 产品能力 | 257 | 8 | 14 | ingest |
| session | 产品能力 | 190 | 8 | 3 | chat / agent（入口） |
| wiki | 产品能力 | 164 | 1 | 1 | wiki |
| mcp | 集成适配 | 148 | 4 | 2 | agent（工具供给） |
| datasource | 集成适配 | 147 | 2 | 0 | datasource 同步 |
| auth | 控制面 | 118 | 6 | 2 | 登录/租户/成员/API-Key |
| im | 集成适配 | 102 | 2 | 0 | IM 消息 |
| memory | 产品能力 | 88 | 1 | 0 | chat（召回）/ 后台蒸馏 |
| agenttools | 产品能力 | 58 | 0 | 0 | agent（工具实现） |
| storage | 集成适配 | 57 | 3 | 6 | 全域文件落点 |
| websearch | 集成适配 | 52 | 3 | 2 | chat（web 检索） |
| model | 能力配置 | 35 | 3 | 2 | 后台模型管理 |
| agentmanagement | 产品能力 | 27 | 3 | 0 | agent（配置管理） |
| embedchannel | 集成适配 | 25 | 1 | 0 | 嵌入式问答 |
| vectorstore | 能力配置 | 20 | 1 | 0 | 后台向量库配置 |
| audit | 控制面 | 20 | 1 | 1 | 审计 |
| system | 控制面 | 18 | 2 | 1 | 系统设置/信息 |
| initialization | 初始化向导 | 13 | 1 | 0 | 安装向导 |
| favorite | 产品能力 | 13 | 1 | 0 | 收藏/置顶 |
| approval | 策略库 | 7 | 0 | 0 | agent（工具审批门） |

### 2.3 跨域 import 总量（域 → 域，仅 domains 内）

对 20 个域全部 1559 个主码文件逐文件提取 `import com.ragagent.<域>.` 并排除同域，得到：

| 源域 | contract | platform | **其他域** | 合计跨包 |
|---|---:|---:|---:|---:|
| session | 562 | 228 | **61** | 851（另 modelcontext 4，见 §4.3） |
| knowledge | 300 | 155 | **7** | 462 |
| auth | 153 | 74 | 0 | 227 |
| agenttools | 151 | 76 | 0 | 227 |
| wiki | 145 | 52 | **6** | 203 |
| mcp | 101 | 80 | 0 | 181 |
| memory | 93 | 44 | 0 | 137 |
| im | 49 | 75 | 0 | 124 |
| storage | 70 | 53 | 0 | 123 |
| datasource | 39 | 79 | 0 | 118 |
| websearch | 38 | 38 | 0 | 76 |
| 其余 9 域 | — | — | 0 | <60 each |
| **合计** | **1923** | **1083** | **80** | **3086**（+4 modelcontext = 3090） |

**跨域 import 只占总跨包 import 的 2.6%（80/3086）**——这是"链路健康"最硬的一个数字：业务域之间默认不说话，说话也先经过 contract（1923 处）。且 domains 对三个 runtime 模块的直接 import 仅 4 处（session 4 个文件 import `modelcontext`，见 §4.3），其余为 0。

---

## 3. 七条核心业务链路（链路图 + 跨域 + 跨模块）

### 3.1 ingest 知识生产（摄取）

```
[HTTP 入口]                        [编排]                          [跨模块能力]                [跨域]
KnowledgeController                KnowledgeService (711 行门面)    contract.doc.DocReaderParsePort
  POST /knowledge/file             ├ KnowledgeProcessWorker (208)     └ 外部 docreader 服务
  POST /knowledge/url              │  └ 4 个阶段类:                    （实现: knowledge/api/
  POST /knowledge/manual           │     TextExtractor/ChunkWriter/     DocReaderParseAdapter）
DataSourceController (同步入口)     │     IndexStage/FinalizingStage  contract.model.EmbeddingPort
  └ 只负责"取回转文档"              └ 任务队列: Redis/InProcess 双实现   contract.retrieval.IndexEngine
                                     (AbstractRedisKnowledgeTaskQueue)  └ retrieval-engine (9 引擎)
                                   contract.knowledge.KnowledgeCatalogPort
                                     └ wiki 富化子任务（族内直连，见 §4）
```

- **入口控制器**：`knowledge/controller/KnowledgeController.java`（18 条映射注解 / 19 个路径：file/url/manual 三入口 + reparse/cancel/spans/download/preview 等运维面）；数据源入口是 `datasource/controller/DataSourceController.java`，但**只取回、不解析**（ingest-flow.md"已知边界"）。
- **跨域调用点**：仅 `knowledge → wiki` 3 个类（`WikiIngestService` / `DefaultWikiKnowledgeFinalizer` / `WikiEnqueueResult`，4 个文件：`KnowledgeProcessWorker` / `KnowledgeFinalizingStage` / `ChunkExtractService` / `QuestionGenerationService`）——wiki 富化挂接在 ingest 的 finalizing 阶段。
- **跨模块调用点**：解析（`DocReaderParsePort` → 外部 docreader 服务）、向量化（`EmbeddingPort`）、索引（`IndexEngine` → retrieval-engine）。**双路径细节见 §7-M1**。
- **判定**：链路形态是全仓最健康的样板——入口只落一行入队，worker 只做接管/租域/排序（208 行），阶段逻辑拆到 4 个阶段类，队列双实现（Redis/InProcess）隔离干净。

### 3.2 chat 知识消费（问答，SSE）

```
KnowledgeQaController (3 端点挤一类!)   QaEntryRunner → QaTurnExecutor (7 步)
  POST /knowledge-chat/{sid}      →     SessionKnowledgeQaService (701)
  POST /agent-chat/{sid}               EventManager (14 事件, 17 插件)
  POST /knowledge-search               PluginSearch → HybridSearchPort ──→ retrieval-engine
                                       PluginChatCompletionStream → LlmChatClient ──→ model-runtime
                                       PluginMemoryRecall → memory.api.MemoryAccess（族内）
                                       StreamManager 推流（platform）
```

- **入口控制器**：`session/controller/KnowledgeQaController.java`——**一个类扛三个端点**（KnowledgeQA / AgentQA / SearchKnowledge），类注释自认"类名只反映了第一个"，不拆的理由是"同一条 SSE 装配路径"。
- **编排**：`QaEntryRunner`（绑定→解析）→ `QaTurnExecutor`（7 步：memo 异步写→并发守卫→落库→carryOver→建流→快答 handler→异步执行）→ `SessionKnowledgeQaService`（701 行，三条入口流单一状态机）→ `EventManager` + 17 个插件（`PluginLoadHistory/PluginSearch/PluginRerank/PluginMerge/PluginWikiBoost/PluginMemoryRecall/...`，全部在 `session/assembly/QaWiring.java` 394 行内注册）。
- **跨域调用点**：`session → agenttools`（42 import，§4）、`session → agentmanagement`（15 import / 4 类，含 ORM 实体 `CustomAgentEntity`）、`session → memory`（4 import，仅 `memory.api.MemoryAccess` 接缝）。
- **跨模块调用点**：检索 `contract.retrieval.HybridSearchPort`（实现 `retrieval-engine/.../HybridSearchService`，`@Service` 组件扫描注入）、模型 `contract.model.LlmChatClient`（model-runtime 4 个实现）、审批 `contract.tool.ToolApprovalGate`（approval 域 `Gate.java` 656 行）。
- **判定**：主链路编排分层清晰（入口/执行/服务/事件/插件五层各有单一职责类）；**入口控制器命名与端点错位**是唯一明显瑕疵（§7-M6）。

### 3.3 agent 执行

```
（同 chat 入口: agentEnabled / agentId）
SessionAgentQaService → contract.agent.AgentEngineFactory ──→ agent-engine（ReAct 循环）
                        AgentStreamBridge (666 行, 事件桥)
Tool 供给三源:
  agenttools 22 个工具（extends BaseTool 实测：wiki 10 / knowledge 7 / web 2 / datasource 2 / sql 1，另 4 个支撑类）
      ↑ 由 session/service/AgentToolBackends (552 行) 直接 import 26 个具体类装配
  agent-engine 自带 3 工具（SequentialThinking / TodoWrite / SkillReadFile）+ ToolRegistry
  mcp 动态工具（McpTooling → contract.pipeline.ToolRegistryPort）
审批: approval/Gate.java（contract.tool.ToolApprovalGate，Redis Pub/Sub 跨实例）
配置: agentmanagement（CustomAgentService / BuiltinAgentRegistry / AgentConfigJson）
技能: agent-engine/skills（Skill/SkillCatalogService）+ agentmanagement 两个 Catalog 控制器
```

- **入口**：无独立控制器，复用 chat 的 `/agent-chat/{sessionId}`（`KnowledgeQaController` 第二端点）。
- **跨域调用点**：`session → agenttools` 42 import / 33 类 / 6 文件（`QaWiring` / `AgentWebPages` / `AgentToolKbBackends` / `AgentToolWikiBackends` / `AgentEngineAssembler` / `AgentToolBackends`）——**这是全仓最重的域间直连边**；`session → agentmanagement` 15 import / 4 类 / 11 文件（`CustomAgentEntity` / `CustomAgentService` / `AgentSuggestedQuestions` / `AgentConfigJson`）。
- **跨模块调用点**：全部经 `contract.agent.*`（28 个文件：Factory/Handle/Request/RunSpec/Step/ToolCall/SteerSink/...）。
- **判定**：运行时（agent-engine）与配置（agentmanagement）分离正确；**工具装配层放错地方**——工具实现按业务域分包在 agenttools，但"哪些工具对哪个 agent 可见"的分发逻辑写在 session（§7-M3，提案 P1-3）。

### 3.4 wiki

```
KnowledgeController(finalizing) → wiki/service/ingest/WikiIngestService（知识库交接，直连）
WikiPageController（页面 CRUD/修订/移动/lint）
wiki 域内 6 个子域: folder / graph / index / ingest / issue / page（164 文件）
agenttools/wiki（31 文件）= 第二套 wiki API（WikiPages/WikiPageRendering/WikiRouteResolver/10 个 *Tool）
```

- **入口控制器**：`wiki/controller/WikiPageController.java`（单控制器）。
- **跨域调用点**：双向直连——`knowledge → wiki`（3 类，ingest 交接）；`wiki → knowledge`（3 类，全部走 `knowledge.api.*` 接缝：`ChunkPortAdapter` / `KnowledgeFinalizeAdapter` / `FinalizeResult`）。
- **跨模块**：经 `contract.wiki.WikiPageOpsPort`（session/AgentToolBackends 与 embedchannel 消费）。
- **判定**：**域内有域**——wiki 自身 164 文件含 ingest 管线（队列/锁/死信/幂等 claim）、页面/修订/链接图/lint 六个子域，复杂度接近一个独立产品；同时 `agenttools/wiki` 31 文件是面向工具的第二套 wiki API。wiki 功能实际三处落地（§7-M7）。

### 3.5 IM 消息（9 渠道）

```
ImCallbackController (GET/POST /im/callback/{channelId})
ImTurnOrchestrator（归一化 → 会话解析 →  QA）
  ├ contract.session.SessionQaPort ──→ session/api/SessionQaAdapter（域内接缝）
  ├ contract.knowledge.KnowledgeCatalogPort / contract.agent.AgentLookup
  ├ contract.doc.DocReaderParsePort（附件解析）
  └ SseSink / StreamManager（回写渠道）
渠道 9 包: dingtalk/feishu(+lark)/mattermost/qqbot/slack/telegram/wechat/wecom/yunzhijia
适配器工厂注册: boot/config/ImAdapterWiringConfig.java（9 行 register，4 个 @Component 注入 + 5 个 new+guard）
```

- **入口控制器**：`im/controller/ImCallbackController.java`（渠道回调单入口）+ `ImChannelController.java`（渠道管理）。
- **跨域调用点**：**0 条直连**——全部经 contract 端口（`SessionQaPort` / `KnowledgeCatalogPort` / `AgentLookup` / `DocReaderParsePort`）。这是七条链路里域隔离最彻底的一条。
- **判定**：渠道接入层干净；**装配在 boot** 是与 datasource 模式的最大不一致（§6.1、§7-M8），且同文件内两种工厂风格混排。

### 3.6 数据源同步

```
DataSourceController → DataSourceService (650) → DataSourceSyncExecutor (488)
  └ ConnectorRegistry（按 type 派发）
       ← DataSourceWiring（域内 @Configuration，9 实例显式注册，失败=启动失败）
连接器实现 6 族: feishu(wiki+drive 20)/gitlab(6)/ima(8)/notion(24)/rss(15)/yuque(8)
catalog 登记 17 类型（含 8 个无实现的"空挂"类型）
产物: contract.knowledge.KnowledgeIngest ──→ knowledge ingest 链路（MapperKnowledgeBridge）
```

- **入口控制器**：`datasource/controller/DataSourceController.java` + `DataSourceCredentialsController.java`。
- **跨域调用点**：**0 条直连**——经 `contract.knowledge.KnowledgeIngest` / `KnowledgeCatalogPort` 交接给 knowledge 域。
- **判定**：**扩展机制的正面样板**——`ConnectorRegistry` + 域内 `DataSourceWiring`（注册失败聚合后启动失败，注释明确"绝不静默假装成功"）。但 catalog 登记 17 类型 vs 9 实现，8 个类型对用户可见不可用（§7-M10）。

### 3.7 embedchannel 嵌入式问答

```
EmbedChannelController（29 端点: 管理面 JWT+RBAC / 公开面 token / 委托面）
  ├ EmbedAuthFilter（token → 渠道+租户上下文）
  ├ contract.session.EmbedSessionPort ──→ session/api/EmbedSessionAdapter（域内接缝）
  ├ contract.storage / contract.tool.McpApprovalOps（公开面代理 mcp-oauth/tool-approvals）
  └ RedisEmbedTokenStore / EmbedRateLimiter
```

- **入口控制器**：`embedchannel/controller/EmbedChannelController.java`（29 个端点，按面拆到 4 个文件：MgmtOps/PublicOps/DelegateOps/Rows，委托面"直接调用既有控制器方法，确保字节契约同源"）。
- **跨域调用点**：**0 条直连**——经 `contract.session.EmbedSessionPort` / `contract.tool.McpApprovalOps` / `contract.storage.*`。
- **判定**：嵌入域是"渠道即适配器"的正确形态；公开面把 MCP OAuth/工具审批也代理进来，说明 embed 场景复用了 agent 全链路——接缝选择正确。

---

## 4. 跨域证据 Top10（domains 内）

### 4.1 全量域间直连边（只有 6 条，按 import 数排序）

| # | 边 | import 语句 | 涉及类 | 文件 | 涉及文件（证据） | 豁免依据 |
|---|---|---:|---:|---:|---|---|
| 1 | session → agenttools | 42 | 33 | 6 | `session/assembly/QaWiring.java`、`session/service/AgentToolBackends.java`（26 类）、`AgentToolKbBackends.java`、`AgentToolWikiBackends.java`、`AgentEngineAssembler.java`、`AgentWebPages.java` | intra-family `agent-runtime` |
| 2 | session → agentmanagement | 15 | 4 | 11 | `session/service/AgentResolver.java`、`SessionAgentQaService.java`、`qa/QaRequestParser.java`、`QaKbScope.java`、`QaChatManageOverrides.java` 等（import `CustomAgentEntity`/`CustomAgentService`/`AgentSuggestedQuestions`/`AgentConfigJson`） | intra-family `agent-runtime` |
| 3 | knowledge → wiki | 7 | 3 | 4 | `knowledge/task/KnowledgeProcessWorker.java`、`KnowledgeFinalizingStage.java`、`service/chunk/ChunkExtractService.java`、`service/enrichment/QuestionGenerationService.java`（import `WikiIngestService`/`DefaultWikiKnowledgeFinalizer`/`WikiEnqueueResult`） | intra-family `knowledge` |
| 4 | system → auth | 6 | 4 | 3 | system 管理面用户查询（`SystemAdminUserService` 等） | intra-family `identity` |
| 5 | wiki → knowledge | 6 | 3 | 5 | `wiki/service/DefaultWikiChunkCleaner.java`、`DefaultWikiKnowledgeFinalizer.java`、`service/ingest/WikiIngestBatchHandler.java`、`WikiIngestCitePipeline.java`、`DefaultWikiImageEnricher.java`（全部走 `knowledge.api.ChunkPortAdapter` 等接缝） | intra-family `knowledge` |
| 6 | session → memory | 4 | 1 | 4 | `session/qa/QaTurnFinalizer.java`、`service/AgentEngineAssembler.java`、`AgentToolBackends.java`、`SessionAgentQaService.java`（仅 `memory.api.MemoryAccess`） | intra-family `agent-runtime` |
| | **合计** | **80** | **48** | **33** | | |

**Top10 只有 6 条**——这不是统计残缺，而是治理结果：`scripts/domain-contracts.baseline.json` 记录了 R10 棘轮从立规时的 **64 条边 / 约 500 处引用**收敛到 6 条，三档分级（clearable 目标 0 / owner 同数据属主 / intra-family 族内豁免），"只许减不许增"，`--write` 拒绝吞掉新增。族定义：`agent-runtime={agentmanagement, agenttools, memory, session}`、`knowledge={knowledge, wiki}`、`identity={agentmanagement, auth, system}`。

### 4.2 治理机制（为什么这 6 条不是"烂账"）

1. `scripts/check-capability-kinds.py`（C0c）：**跨 kind 直连 = 0**（产品能力/集成适配/控制面/能力配置/初始化向导/策略库六类，类间直连必须同 kind）。
2. `scripts/check-domain-contracts.py`（R10）：域间只经契约或装配层；上表 6 条全部登记且冻结；新增边或边内新增类即红。
3. `scripts/check-module-graph.py`：模块级依赖矩阵 + `MISPLACED_TEST_ALLOW` 登记 10 个跨模块测试。

### 4.3 模块级直连（domains → runtime）

`domains` 对 `:model-runtime` 的直连**仅 4 处、全部是 `modelcontext` 包**：`session/rag/plugin/PluginChatCompletionStream.java`、`session/rag/support/ReferencesSupport.java`、`session/service/SessionQaFallback.java`（import `modelcontext.Registry` / `StreamDecoder`）。`check-module-graph.py` 注释明确这是"方案认可的终态边"（模型输出上下文协议属模型侧词汇），并有 `domains/src/test/java/com/ragagent/arch/AgentEngineBoundaryTest.java` 钉住（`assertThat(isDomainImport("import com.ragagent.modelcontext.Registry;")).isFalse()` 之类断言）。**结论：domains → 三个 runtime 的其余直连为 0，"端口下沉、实现在下"在模块级是事实。**

---

## 5. 管理后台功能盘点表

| 功能 | 落点域 | 入口控制器 | 关键实现类 | 运行时在哪 | 盘点判定 |
|---|---|---|---|---|---|
| 模型管理 | model | `ModelController` `ModelCredentialsController` `ModelDebugController` | `ModelService` `ModelProviderCatalog` `BuiltinModelsReconciler` | model-runtime `ProviderRegistry` + `ModelRuntimeAssembler` | **配置/运行时分离正确；但 provider 元数据双目录（§7-M2）** |
| 向量库配置 | vectorstore | `VectorStoreController` | `VectorStoreConfigService` `VectorStoreLookupAdapter` | retrieval-engine `EngineRegistry`/`EngineFactory` | 配置面干净；**引擎常量双写（§7-M5）** |
| WebSearch 提供商 | websearch | `WebSearchProviderController` + Credentials + `WebSearchController` | `WebSearchProviderRegistry`（13 家，构造器 Map） | 同域 provider 包 | **单点登记，最佳形态** |
| 存储后端 | storage | `StorageBackendController` `StorageEngineController` `FileProxyController` | `FileServiceFactory`（8 provider switch）`StorageFileResolver`(778) | 同域 provider 包 | 形态正确；switch 工厂是五套注册机制里唯一无注册表的（§6.2） |
| 系统设置 | system | `SystemController` `SystemAdminController` | `SystemSettingService`(548) `SystemInfoService` `DeploymentCapabilitiesHolder` | — | **"系统设置"与 auth 的租户成员管理、system 的管理面用户管理三处分治（§7-M9）** |
| 评测 | — | — | — | — | **不存在（§7-M10'）**：全仓无 evaluation 域/表/控制器/前端 |
| 审批 | approval | 无（策略库） | `Gate.java`(656) `ToolApprovalGateFactoryImpl` | contract.tool.ToolApprovalGate | 纯策略域形态正确；等待者在内存 + Redis Pub/Sub 跨实例 |
| 审计 | audit | `AuditLogController` | `AuditLogService` `AuditWriteAdapter` `WikiActivityAuditRecorder` `RbacDeniedAuditorRegistrar` | boot `OpenSearchAuditSinkAdapter`（OpenSearch 驱动 → 审计） | **AuditWritePort 被 7 域 9 文件消费，端口化彻底**；wiki 活动录制器落在 audit 域（合理：审计族） |
| RBAC | 三处 | — | boot `RbacRoutePolicies`(593) + auth `APIKeyRoutePolicies`(705) + platform `RbacInterceptor` | — | **双策略表（§7-M4）** |
| 初始化向导 | initialization | `InitializationController` | `InitializationConfigService`(693) `OllamaManageService` `ModelConnectivityTestService` | 只调契约端口 | 薄编排层形态正确（13 文件） |
| MCP | mcp | `McpServiceController` `McpOAuthController` `McpCredentialsController` `AgentToolApprovalController` | `McpCatalog`(723) `McpTooling` | agent-engine `ToolRegistry` | 集成适配域 + OAuth 子包完整；工具经 `McpToolRegistrationPort` 进 agent |
| 记忆 | memory | `MemoryController` | `MemoryService`(770) `MemoryExtractionService`(725) 等 18 个 service | 同域（含向量/话题/蒸馏） | 自包含产品能力；经 `MessageTranscriptPort` 读会话（干净） |
| 嵌入渠道 | embedchannel | `EmbedChannelController`(29 端点) | `EmbedChannelService`(724) | session `EmbedSessionAdapter` | 渠道适配形态正确 |

### 5.1 "一个功能散落多域"专项

| 业务功能 | 散落位置 | 判定 |
|---|---|---|
| LLM 提供商目录 | `model-runtime/llm/provider/ProviderRegistry`（运行时路由，静态块双处）+ `domains/model/service/ModelProviderCatalog`（HTTP 目录，金片生成，199 行） | **真散落**：两侧靠 `ProviderCatalogParityTest` 兜底，但加一家改两份手写清单；`ProviderRegistry` 类注释原话"⚠️ 与 ModelProviderCatalog 不是一回事" |
| embedding 客户端 | `model-runtime/embedding/`（9 家 + 工厂 + 池，20 文件）+ `knowledge/client/EmbedderClient`（最小 OpenAI 兼容） | **真散落**：`ChunkVectorIndexer` 同持两条路（§7-M1） |
| agent 工具 | agent-engine（3 工具 + 注册表）+ agenttools（22 工具 5 包）+ session（`AgentToolBackends` 552 行装配）+ agentmanagement（配置） | **真散落**：跨 3 域 1 模块（§7-M3） |
| wiki 读写 | wiki 域（164 文件）+ agenttools/wiki（31 文件第二套 API）+ 前端 `views/knowledge/wiki` | **真散落**：域内有域 + 工具侧平行 API |
| RBAC 策略 | boot（593 行）+ auth（705 行）+ platform（执行器） | **真散落**（§7-M4） |
| 引擎类型常量 | retrieval-engine `EngineTypes`（9 常量）+ domains/vectorstore `VectorStoreEngines`（7 白名单） | **真散落**（§7-M5） |

### 5.2 域职责重叠三问（任务指定）

1. **model vs model-runtime vs embedchannel？** model = 纯配置 CRUD（35 文件，无 runtime）；model-runtime = 运行时实现；**embedchannel 与模型无关**（它是嵌入式问答渠道，易被名字误导）。真正重叠的是 model 与 model-runtime 之间的 **provider 目录双份**（M2），不是三者重叠。
2. **agenttools vs agentmanagement？** 两者不同层（工具实现 vs Agent 配置 CRUD），重叠在 **session 的装配层把两者直接 import**（M3）；另外 `SkillCatalogController`（管理 CRUD）与 `SkillsCatalogController`（选择器只读数据源）命名仅差一个 s，同域双控制器易混淆。
3. **knowledge vs wiki vs memory？** knowledge = 文档/chunk/向量主链路；wiki = 页面/修订/链接图（独立管线，ingest-flow.md 明示"wiki 是独立管线"）；memory = 对话蒸馏与召回（经 `MessageTranscriptPort` 读会话）。**三者职责实际不重叠**，重叠的只是 ingest finalizing 处的交接（knowledge→wiki 直连 3 类，已族内豁免）。

---

## 6. 演进成本量化（新增一类扩展要动几处）

### 6.1 新增一个 IM 渠道

| 改点 | 位置 | 证据 |
|---|---|---|
| 1. 新渠道包（Adapter + Factory [+ 长连接客户端]） | `domains/.../im/channel/<name>/` | 参照 `telegram`（3 文件）/ `dingtalk`（754 行 Adapter） |
| 2. 平台常量 | `im/runtime/ImTypes.java` `PLATFORM_*` | 现有 10 个常量（feishu/lark 同实现双平台名） |
| 3. **boot 装配** | `boot/config/ImAdapterWiringConfig.java`（构造器 + `registerAdapterFactory` 一行） | 文件注释原话"九个渠道的出站客户端逐个落地，每落地一支在这里注册一行" |
| 4. 前端集成页 | `frontend/src/views/integrations/IntegrationSettingsSection.vue` | domain-index 登记的 IM 前端页面 |
| 5.（webhook 型）回调路由 | `im/controller/ImCallbackController.java` | 单入口按 channelId 分发，通常无需改 |

**碰 boot：是**（9 条渠道全部登记于此，4 个 `@Component` 注入 + 5 个 `new + SsrfGuard` 两种风格混排）。**碰 contract：否**。工作量：约 2–5 个新文件 + 2 处登记。

### 6.2 新增一个向量库（检索后端）

| 改点 | 位置 | 证据 |
|---|---|---|
| 1. 引擎 Repository | `retrieval-engine/.../engine/<name>/<Name>RetrieveRepository.java` | 现有 10 件（ES v7/v8 分立） |
| 2. 引擎类型常量 | `retrieval-engine/.../engine/EngineTypes.java` | 9 个 `ENGINE_*` 常量 |
| 3. 构造 switch | `EngineFactory.createFromStore`（约 91–248 行两个 switch：构造 + support 覆盖） | 每引擎一个 case |
| 4. **boot env-store 注册** | `boot/config/RetrievalEngineWiringConfig.java` | 注释"九家店至此全部落地"；注册失败只记日志不阻塞启动 |
| 5. 域白名单常量 | `domains/vectorstore/domain/VectorStoreEngines.java`（7 项，pg/sqlite 只能走 env） | **与 EngineTypes 双写** |
| 6. 前端映射 | `frontend/src/views/settings/VectorStoreSettings.vue` | domain-index 登记 |

**碰 boot：是**（env-store 通道）。**碰 contract：否**（`IndexEngine`/`VectorIndexStore` 已抽象）。**碰双写常量：是**（M5）。工作量：约 6 处 / 3 模块——五套注册机制中成本最高之一（switch 工厂不可注入、不可测试替换）。

### 6.3 新增一个 LLM 提供商

| 改点 | 位置 | 证据 |
|---|---|---|
| 1. Provider 实现 | `model-runtime/llm/provider/<Name>Provider.java` | 26 个现有实现 |
| 2. 枚举常量 | `llm/provider/ProviderName.java` | 26 个枚举 |
| 3. 静态块注册 | `ProviderRegistry` static 块 | 类注释原话"新增厂商时两处都要加" |
| 4. 清单再登记 | `ProviderRegistry.allProviders()` | 同一文件第二处 |
| 5.（非标请求形状）适配器 | `modelruntime/adapter/provider/chat/common/ProviderAdapters.java` REGISTRY | 11 个请求整形适配器 |
| 6. HTTP 目录条目 | `domains/model/service/ModelProviderCatalog.java` ENTRIES | 199 行手写静态数据 |
| 7. **契约金片** | `domains/src/testFixtures/resources/contracts/model-providers.json`（26 家）+ `boot/src/test/java/com/ragagent/model/ModelContractTest.java` | 金片字节校验，不改即红 |

**碰 boot 主码：否**（组件扫描）。**碰契约金片：是**（第 7 项，字节级）。工作量：4–7 处 / 3 模块 + 金片——五套机制中成本最高（双目录 + 金片）。

### 6.4 新增一个数据源连接器

| 改点 | 位置 | 证据 |
|---|---|---|
| 1. 连接器包 | `domains/.../datasource/connector/<name>/` | 参照 notion（24 文件）/ rss（15）/ yuque（8） |
| 2. catalog 元数据 | `datasource/catalog/ConnectorCatalog.java` | 17 类型登记（含 8 个空挂） |
| 3. 类型常量 | `DataSourceConstants.CONNECTOR_TYPE_*` | — |
| 4. **域内装配一行** | `domains/.../datasource/config/DataSourceWiring.java` `registry.register(...)` | 注册失败聚合 → 启动失败 |
| 5. 前端 | `views/knowledge/settings/DataSourceSettings.vue` | domain-index 登记 |

**碰 boot：否**。**碰 contract：否**。工作量：约 5 处、全在 datasource 域内——**五套机制中成本最低、形态最佳**（显式注册表 + 启动失败 + 零散落）。反面：catalog 登记 ≠ 实现，17 个类型里 8 个（confluence/github/google_drive/onedrive/dingtalk/web_crawler/slack/imap）用户可见但无实现（M10）。

### 6.5 成本对比结论

| 扩展类型 | 改点 | 碰 boot | 碰 contract 金片 | 机制形态 |
|---|---:|---|---|---|
| 数据源连接器 | ~5 | 否 | 否 | 注册表（最佳） |
| IM 渠道 | ~4–7 | **是** | 否 | boot 装配 + 双风格 |
| WebSearch 提供商 | ~2 | 否 | 否 | 构造器 Map |
| 向量库 | ~6 | **是** | 否（但常量双写） | switch 工厂 |
| LLM 提供商 | ~4–7 | 否 | **是** | 静态块双处 + 双目录 + 金片 |

---

## 7. 业务-架构错配清单（按严重度排序）

### M1. embedding 双路径：同一业务两个客户端，厂商兼容性只覆盖一半（P1）

- 证据：`domains/.../knowledge/index/ChunkVectorIndexer.java` 同时注入 `EmbedderClient` 与 `ModelRuntime`；`indexAndStore()`（176 行）走 `EmbedderClient.embedBatch`（最小 OpenAI 兼容），`indexAndStoreViaEngine()`（246 行）走 `modelRuntimeAssembler.getEmbeddingModel()`（model-runtime 9 家：Aliyun/Azure/Gemini/Jina/Nvidia/Ollama/OpenAI/Volcengine/Zhipu）。分岔条件是"KB 是否绑定了外部向量引擎"。
- 业务后果：同一颗 embedding 模型，绑引擎的 KB 与本地 pg 的 KB 走两条 HTTP 客户端；provider 特有参数/错误处理只在前者。厂商扩展（新 embedder）对本地 pg 路径零收益。
- 另注：`knowledge/client/EmbedderClient` 把"运行时 HTTP 客户端"放在业务域内，与 overview 的"改模型接入去 model-runtime，别在 domains 自建 HTTP 客户端"自相矛盾。

### M2. LLM provider 双目录 + 金片（P1）

- 证据：`ProviderRegistry`（216 行，静态块 26 家 + `allProviders()` 26 项，注释"两处都要加"）vs `ModelProviderCatalog`（199 行，26 条 `ProviderEntry`，"由 golden（contracts/model-providers.json）生成"）；金片实测 26 家，`ModelContractTest` 字节校验；`Provider.validateConfig()` 注释自认"尚无生产调用点（仅测试）"。
- 业务后果：接一家新 LLM = 2 份手写清单 + 枚举 + 金片，漏一处即红或即裂；目录一致性靠 parity 测试兜底而非结构保证。

### M3. agent 工具装配在 session：加工具要改 session 的具体 import 清单（P1）

- 证据：`session/service/AgentToolBackends.java`（552 行）头部 import 26 个 `agenttools` 类（wiki 13 / knowledge 7 / web 2 / datasource 3 / sql 1，其中 22 个为 `extends BaseTool` 的工具类 + 4 个支撑类），类注释"工具名 → 工具实例的分发构造（allowedTools 命中即构造）"；`session/assembly/QaWiring.java` 另 import `DataAnalysisSessionFactoryAdapter`。`agenttools` 58 文件按 5 个业务域分包但**无自注册入口**。
- 业务后果：agent 工具扩展 = 跨域编辑 session；session→agenttools 42 import / 33 类是全仓最重直连边，也是"agent-runtime 族"无法拆模块的主因之一。

### M4. RBAC 策略双表，一张在 boot（P1/P2）

- 证据：`boot/config/RbacRoutePolicies.java`（593 行，JWT 路由→角色）与 `domains/auth/apikey/controller/filter/APIKeyRoutePolicies.java`（705 行，API-Key 路由→scope）两张表，执行器 `platform/web/RbacInterceptor.java`；domain-index 的"改权限"指南同时指向两处。
- 业务后果：新增受管端点要同步两张 600+ 行表；策略声明权一半在组合根一半在 auth 域，"去哪找"没有单一答案（与 01 报告"组合根名不副实"互为因果）。

### M5. 引擎常量双写：9 vs 7，规则靠注释传递（P2）

- 证据：`retrieval-engine/.../engine/EngineTypes.java`（9 个 `ENGINE_*`：含 postgres/sqlite）vs `domains/vectorstore/domain/VectorStoreEngines.java`（7 项 `VALID_ENGINE_TYPES`，注释"postgres/sqlite 只能经 env store 出现"）。
- 业务后果：加一种引擎要同时记得改两处；"哪些引擎可入库配置"的规则只存在于域白名单的注释里，无守卫钉住两者一致。

### M6. 命名错位三处（P2）

1. `KnowledgeQaController` 一类三端点（knowledge-chat / agent-chat / knowledge-search），类名只反映第一个；不拆的理由（共用 SSE 装配路径）成立，但**名字撒谎**。
2. `SkillCatalogController`（技能管理 CRUD）vs `SkillsCatalogController`（技能选择器数据源）——同域双控制器命名仅差一个 s。
3. `domains` 实测 190 个 session 文件 vs 文档 187；contract 24 子包 vs 文档 23（`check-doc-truth.py` 只钉三个数字，其余漂移无人看守——与 01 报告结论一致）。

### M7. wiki 域内有域 + agenttools/wiki 平行 API（P2）

- 证据：wiki 164 文件含 ingest（队列/锁/死信/幂等）/folder/graph/index/issue/page 六个子域（`WikiIngestCitePipeline` 748 行、`DefaultWikiPageService` 729 行、`WikiPageRepository` 708 行）；`agenttools/wiki` 31 文件是第二套 wiki API（`WikiPages`/`WikiPageRendering`/`WikiRouteResolver`/`WikiScope` + 10 个 `*Tool`）；前端还有 `views/knowledge/wiki`。
- 业务后果：wiki 一个产品功能横跨"域内 6 子域 + 工具侧平行 API + 前端独立目录"；改动页面语义要同时理解两套 API。

### M8. IM 装配在 boot，与同类模式不一致（P2）

- 证据：IM 9 渠道工厂登记在 `boot/config/ImAdapterWiringConfig.java`；而 datasource（`DataSourceWiring`）、knowledge（`KnowledgeRedisWiring`）、wiki（`WikiRedisWiring`）、embedchannel（`EmbedWiring`）、mcp（`McpWiring`）均在域内自装配。且 IM 同一构造器里 4 个工厂 `@Component` 注入、5 个 `new + SsrfGuard` 手写。
- 业务后果：加渠道要动组合根（"只出不进"的 boot 被迫知道每个渠道的存在）；两种工厂风格意味着 SSRF 闸的注入策略不统一。

### M9. 控制面用户管理三处分治（P3）

- 证据：登录/租户/成员/邀请在 auth（118 文件，其中 apikey 是一个完整垂直切片：controller/filter/domain/mapper/repository/service 八件套）；平台管理面用户在 `system/service/SystemAdminUserService.java`；系统设置在 `system/service/SystemSettingService.java`（548 行）。
- 判定：可辩护（租户自治 vs 平台超管），但"用户"这个概念跨 auth/system 两域，新人定位成本高；`APIKeyRoutePolicies` 705 行是 auth 内最大的单文件。

### M10. catalog 空挂 + "评测"不存在（P3，产品口径问题）

1. `ConnectorCatalog` 登记 17 类型、`DataSourceWiring` 只注册 9 实例——confluence/github/google_drive/onedrive/dingtalk/web_crawler/slack/imap 8 个类型对用户可见（listAvailableConnectors）但无实现，用户配了即失败。
2. 项目功能口径列"评测"，全仓（domains 主码 / 前端 views / docs）无任何 evaluation 域、表、控制器、页面——**文档承诺了不存在的交付物**。

---

## 8. 业务对齐的域/模块划分提案

### 8.0 一句话

**总盘不动（7 模块 / 20 域与七条链路同构，且有守卫有基线），做五台定向手术；中期待 6 条族内边清零后把 agent-runtime 族拆为独立 Gradle 模块。**

### 8.1 五台定向手术（按业务价值排序）

| # | 手术 | 业务理由 | 落点 | 预期收益 |
|---|---|---|---|---|
| P1-1 | **embedding 单路径化** | "向量化"是一个业务动作，不该有两个客户端 | 删 `knowledge/client/EmbedderClient`，本地 pg 路径改走 `ModelRuntime.getEmbeddingModel()` + 分批参数保留 `BatchEmbedProperties`；或反向把 model-runtime embedder 族降级为唯一实现、knowledge 只留配置解析 | 新 embedder 对全部 KB 生效；消除"同模型两种行为" |
| P1-2 | **provider 单目录化** | 厂商清单是同一份事实 | `ModelProviderCatalog` 改为从 `ProviderRegistry` 派生（或金片作为唯一源、Registry 从金片生成），`ProviderCatalogParityTest` 退役 | 加一家 LLM 从 4–7 处降到 2–3 处（Provider 类 + 枚举 + 单登记） |
| P1-3 | **agent 工具注册端口化** | 工具是 agent 的插件，不该由 session 点名 | 在 `contract.tool` 增 `ToolProvider` 端口（或扩展现有 `ToolRegistryPort` 的自注册），agenttools 5 个族各自 `@Component` 自注册；`AgentToolBackends` 的 26 个直接 import 改为端口查找；allowedTools 匹配逻辑随端口下沉 | session→agenttools 边（42 import / 33 类）可清零；加工具零跨域编辑 |
| P1-4 | **RBAC 策略单表化** | "谁能调什么"是一张表 | `RbacRoutePolicies` 与 `APIKeyRoutePolicies` 合并为单一声明表（建议落 auth 域，boot 只保留扫描与探针），`RbacInterceptor` 为唯一执行器 | 新端点改一处；策略声明权归位 |
| P1-5 | **引擎常量单源化** | 引擎清单是同一份事实 | `VectorStoreEngines` 白名单改为从 `EngineTypes` 派生（或共用一个 `contract.retrieval` 常量源），加守卫钉一致 | 加向量库少改一处；消除 9 vs 7 漂移 |

### 8.2 形态统一（P2）

- **P2-1 IM 装配归域**：`ImAdapterWiringConfig` 逻辑下沉为 `im/config/ImWiring.java`（域内 `@Configuration`），boot 不再登记渠道；工厂统一 `@Component` + 可选 `SsrfGuard` 注入一种风格。与 `DataSourceWiring` 对齐后，"加渠道"完全不碰 boot。
- **P2-2 switch 工厂改注册表**：`EngineFactory.createFromStore` 与 `FileServiceFactory` 的 switch 收敛为 `Map<String, Factory>` 注册表（websearch 的 `WebSearchProviderRegistry` 就是域内现成范式），使向量库/存储后端可注入、可测试替换。
- **P2-3 命名治理**：`KnowledgeQaController` 保留三类但改名 `ChatQaController`（或在类注释外补路由索引注释）；`Skill(s)CatalogController` 统一为一个 `SkillController` + 内部两个只读/管理面方法，或改名 `SkillManageController` / `SkillPickerController`；把 session 文件数、contract 子包数、金片数纳入 `check-doc-truth.py`。

### 8.3 产品口径修补（P3）

- **P3-1 评测缺口**：若路线图确有评测，新增 `evaluation` 产品能力域（复用 `contract.retrieval.SearchResult` + `HybridSearchPort` + audit，落检索质量/问答质量两类评测集），前端挂 `views/settings` 或独立页；若不打算做，从项目功能口径删除"评测"——**不留空头承诺**。
- **P3-2 catalog 空挂治理**：`ConnectorCatalog` 对无实现类型在 `listAvailableConnectors` 标注"即将支持"或直接隐藏，避免用户配置注定失败的连接器。
- **P3-3 控制面用户归口**：中期把 `SystemAdminUserService` 的平台用户管理并入 auth（或明确"平台超管域"独立成 `platformadmin`），消解"用户"两处定义。

### 8.4 远期模块化（前置条件明确）

`scripts/domain-contracts.baseline.json` 的 clearable 档注明："这也是 domains 拆 Gradle 模块的先决条件"。建议顺序：

1. 先完成 P1-3（session→agenttools 清零）与 session→agentmanagement 的 4 类契约化（`CustomAgentEntity` 等 ORM 实体至少以 `contract.agent.AgentLookup` 类端口收敛，消除持久层渗漏——01 报告已点名同一问题）；
2. 6 条边清零后，将 agent-runtime 族（session + agenttools + agentmanagement + memory）拆为 1–2 个 Gradle 模块（编译隔离收益：agent 工具/配置的改动不再触发全 domains 重编译）；
3. knowledge 族（knowledge + wiki）视 wiki 子域复杂度决定是否拆 `wiki` 独立模块。

### 8.5 明确不做

- **不拆 20 域为 20 模块**：域间直连已收敛到 6 条且全族内豁免，拆模块的编译隔离收益抵不过 1600+ 文件的 diff 与金片风险（overview 对包名先例有同样判断）。
- **不引入服务网格/事件溯源**：当前 `EventBus` + 端口形态已支撑 14 事件管线与跨实例审批，无业务证据需要更重机制。
- **不为"域数量整齐"合并小域**：favorite（13）/ approval（7）/ initialization（13）等小域各有单一职责与明确边界，合并只会制造新的巨类。

---

## 9. 评估方法与边界

- **统计方法**：模块/域文件数用 `find <path> -name "*.java" | wc -l`；跨域 import 用 Python 逐文件正则提取 `^import com\.ragagent\.<pkg>\.` 并按路径判源域，去重统计语句数/类数/文件数；控制器数按 `*Controller.java` 文件名（51 个业务控制器，另有 platform `/health` 探针，与 01 报告口径一致）。
- **抽样深度**：7 条链路全部走查编排类与入口控制器；top 30 大文件全部读过类级注释判断"门面 vs 上帝类"（结论：现有 700+ 行类均为有文档的 facade/协作者组合，**未发现无边界上帝类**——`KnowledgeService` 711 行 = 15 个协作者的门面，`MemoryService` 770 行 = 5 个兄弟类包级可见性协作，`StorageFileResolver` 778 行自述"三个职责"且同属存储解析）。
- **守卫实跑**：未执行（本次为业务视角评估）；引用了 `scripts/check-capability-kinds.py` / `check-domain-contracts.py` / `check-module-graph.py` 的注释与基线 JSON 作为治理证据。
- **边界**：未读前端源码（仅列目录与 api 模块名）；未读测试码（仅引金片路径与契约测试名）；"评测不存在"的结论基于 domains 主码 + 前端 views + docs/architecture 的关键词检索（evaluation/eval/benchmark/评测），不排除计划中的未交付功能。
- **欠账**：`Provider.validateConfig()` 无生产调用点（model-runtime 注释自认）；IM 长连接/渠道级性能数据未测。
