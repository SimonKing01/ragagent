# ragagent 交接文档（v0.8.x 工作版）

> **内部迁移作战文档（非新人入口）**：新人请从仓库根 `README.md` → `docs/README.md` 开始。
> 本文件记录重构/迁移的过程与决策，路径与读数随批次变化，不是当前架构说明。

> **版本谱系**：v0.1.0 归档 `docs/internal/handoff/archive/HANDOFF-v0.1.0-2026-10-13.md`；**v0.2.0 全文归档 `docs/internal/handoff/archive/HANDOFF-v0.2.0-2026-10-16.md`**（含 B232~B267 全部逐批记录、v0.2.0 结构规范与 C/G1/D/E 系列过程记录）。
> 本版 2026-10-16 生效：**只留活信息**；历史一概在归档，追溯用 `git log --grep`。
> 工作分支：`v0.10.x`（2026-11-02 自 `v0.9.x` 的 `ca2778db6` 切出，做 JDK 21 + Boot 3.3 + Jackson 2.17 回退）。

## ⭐ 接手须知（5 分钟）

1. **开工顺序**：读 §4 两条判定标准（2026-10-16 拍板）→ §5 执行方案（详情＝`docs/internal/plans/java-remediation-implementation-plan-v2-2026-10-16.md`）→ §9 方法论 → §10 纪律 → 干活 → 回填 §12。
2. **基线核对**：先跑 §6 的全量闸门，确认 `BUILD SUCCESSFUL` + 各模块测试条数（**numbers 守恒**；只信汇总数字，`boot`/`platform` 条数缺失＝静默少跑）。
3. **待办与读数都会过期**：宣称"未完成/待办"或据此动手前先实测（`git log` / 守卫 / 脚本输出）；**以守卫为准**。
4. **提交**：批次号 `(P#)`/`(R#)`，写明验证方式；**推送等用户发话**（历史纪律，一字不改）。

## 0. 总目标与当前阶段

- **终态（方案 §5.1）**：7 个 Maven 模块 —— contract / platform / agent-engine / retrieval-engine / model-runtime / domains / boot；单进程分层单体；跨模块只经 contract。
- **现状（2026-10-24）**：**7 模块** —— `contract` · `platform` · `model-runtime` · `retrieval-engine` · `agent-engine` · `domains` · `boot`。`:common` 与 `:engine` 均已解散；构建已迁 Maven（X 系列）。
- **当前阶段（v0.8.x）**：v0.5.0 / v0.6.0 / v0.7.x 均已收官；v0.8.x 已落地 K/L/M 收尾 + N/P 系列 + C 系列 contract 还乡（328→293 类型）+ 更名 RagAgent（模块/命令/环境变量）+ 四语言 i18n + 指标面（actuator）+ prompt 行为调优。**2026-10-24 第五轮评估核实后立项 Q 系列「配置单源与状态收敛」**（方案 `docs/internal/plans/convergence-remediation-q-series.md`），Q0 核实已完成，批次待推进（见 §5.0g）。

## 1. 仓库身份与分支

- 远程 `origin` = `https://github.com/pmbilly/ragagent.git`（公开）；主分支 `main`（与 `v0.8.x` 同指 `99f4f720e`，2026-10-24）。
- 分支谱系：`v0.1.0`（归档）· `v0.2.0`（R 系列 36 批，已合并 main 并推送）· `v0.3.0`（P1~P5 两模块纠偏）· `v0.4.0`（R1~R6 三 runtime，已合并 main）· `v0.5.0`（可读性专项 L1~L50，已合并 main）· `v0.6.x`（业务内聚专项 H 系列，已合并）· `v0.7.x`（Maven 迁移 X 系列，已合并）· **`v0.8.x`（当前工作分支，N/P/C 系列 + 更名 RagAgent，2026-10-24 已与 `main` 同步）**。
- 网络实录：本机↔github 的 https 不稳；用 `git -c http.version=HTTP/1.1 push …` + 重试。
- 兼容边界：**系统未上线、无历史数据包袱、前端适配后端**（知识库模块三约束，全线适用）。

## 2. 已定决策（勿再重新讨论）

1. **保留分层单体，渐进整改**；不整体重写、不拆微服务（整改方案 §0/§13）。
2. **域内命名**：`controller / service / mapper / repository / domain / dto / config`（方案 §6）；`agenttools`/`agentmanagement` 命名（§7，B257 已执行）。
3. **模块名 = 顶层包名**：契约面取**单数 `contract`**（不取方案字面的 `contracts`；理由见 `contract/pom.xml` 头注释，已登记）。
4. **三个 runtime 平级、互不引用实现**；跨模块协作经 contract、由 boot 注入（方案 §5.2）。
5. **域间只经契约（R10）**：①可清零档 = **0** ✅；②属主边 2 条（`auth→tenant`、`system→auth`）**登记维持**；③同族 5 条（agent 家族）**冻结**。
6. **线格式（1,346 金片）逐字节不变**；协议变更属**产品决策**、不夹带（`check-contract-sync` 是执行面）。
7. **agent-engine 结论（2026-10-16 架构复核）**：**做**——但必须"**先建 `contract.agent` 契约面 + 工具 SPI，再搬 `domains/agent` 实现**"；不设计边界直接搬 = 把族内协作变成跨模块直连（比现状更差）。
8. **platform 的"落点法则"作废**：v0.2.0 曾以"去不了契约的跨域件落 platform"补缺口；v0.3.0 起按 §4 判定标准——platform **不放业务形状**。
9. **R1 余项判定（2026-10-16 拍板）**：`contract.retrieval` 缺口 **并入 R3**（域侧直注的是实现类，索引面签名穿透 `Embedder`{模型族}与可变 setter `IndexInfo`）；`contract.agent` 6 件 **并入 R5**（今日跨域消费者仅 boot 一处测试）；`contract.execution` **判"不建"**（取消/元数据已存在于 `contract.tool.ToolCancellation`/`ToolExecContext`，再建即三套取消语义并存）。

## 3. 三条红线（本版口径）

1. **载体红线**：不得上提持久化实体（`@TableName`/TypeHandler/可变 setter）到 L1；contract 不放**内部件/持久化形状**；platform 不放**业务形状**。
2. **线格式红线**：金片签名 1,346 逐字节不变；确需变更 ⇒ 显式签字 + 前端确认（产品决策）。
3. **纪律红线**：**不刷新 baseline 掩盖违例**；**守卫必须能红**（新增/改守卫必配红态探针）；**闸门退出码显式取用**（任何 `| head` 不得代替）。

## 4. 两条判定标准（2026-10-16 拍板 ★ 本版核心）

**contract**：跨域词汇表的**唯一一处**；零框架依赖；**每一个类都能回答"谁在跨域消费我"**。

**platform**：只剩**技术适配**（框架、HTTP、存储、提示词、追踪、事件基建）；**任何业务形状出现即红灯**。

口径：
- 判定标准是**原则**，优先于方案 §8.1/§8.2 的枚举清单（方案原文："只公布跨模块确有消费者的 API/SPI"；"不为 DDD 完整编造空层"）。
- **跨域端口/视图面扩容 = 追认**（A③ 域间契约化的必然产物）；**platform 业务形状 = 归正**（去框架化进 contract / 中性化 / 归域 / 归 agent-engine，见实施计划 v2 §1.2）。
- 验收工具：`check-contract-purity.py`（C1/C2/C3 全零）+ **C8**（platform 内 `*View/*Port/*Request/*Response/*Payload/*Facts` 命名红灯，例外须登记理由）。

## 5. 执行方案

### 5.0 当前阶段：v0.5.0「可读性专项」（2026-10-18 起）

> 完整方案：`docs/internal/plans/readability-remediation-v0.5.0.md`。总目标：一条问答链路必读文件数 20 → ≤10；迁移叙事 769 → 0；控制器 catch 151 → 0；`package-info` 覆盖 48% → 100%。

**边界**：不重写 RAG 管线 / `StreamManager` / `TenantContext`；不改 DB schema；不为架构形式拆模块；前端大文件单独立项。**允许**：删类改名、合并包、拆 `:knowledge`、一次清掉 Go 兼容语义。

| 批 | 内容 | 状态 |
|---|---|---|
| **L1** | 迁移叙事清零（退役/旧/历史/备案/已知差异/Go，共 769 处） | ✅ 基线 196 → **0**（守卫硬零） |
| **L2** | 行号与 Go 语法锚点（`L141-144`/`uuid.New()`/旧模块名 `common`）+ 构建脚本批次号 | ✅ 16 处注释 + 8 个构建脚本 |
| **L3** | 修 7 处文档失真（README ×4 + 3 个类注释） | ✅ `check-doc-truth` 上线 |
| **L4** | 补 156 个 `package-info`（职责/依赖/从哪读起） | ✅ 覆盖率 100%（322 包） |
| **L5** | 注释装饰清理：491 行纯装饰分隔线删除、216 行统一格式 | ✅ `check-comment-decor` 硬零 |
| **L6** | "未实现/暂缺"收敛到 `docs/known-gaps.md` | ✅ 18 项 + 9 处指针 |
| **L7** | 死代码删除（空包、`@Deprecated`、`platform/session`；`platform/tool` 归 L22） | ✅ `@Deprecated` 归零 |
| **L8** | 日志分级：404/跳过 → debug、配置缺失 → info（不设总数门） | ✅ 116 处降级（893→777） |
| **L9** | 删纯转发的控制器 catch（12 处），统一交 `GlobalExceptionHandler` | ✅ 其余带业务语义或金片钉住，保留 |
| **L10** | 错误形态统一为 `{code,message,data}`（**行为变更**） | ✅ 删 2 个异常类 + RawJsonError；金片 8 条 |
| **L11** | 异常语义守卫扩到 domains 全量 | 待做 |
| **L12** | 数据访问定规 + controller 纯度守卫 | ✅ controller 直连 Mapper 实为 0（原 22 是 ObjectMapper 误报） |
| **L13** | 4 个核心实体 → `XxxView` record | ⏸ 评估后不做：收益（架构纯粹性）小于成本（157 金片 + 序列化细节）与可读性损失（多一层跳转） |
| **L14** | 事务模板收敛 + 队列 worker 补事务边界说明 | ✅ 2 类合并为 `platform.persistence.TxTemplate` |
| **L15** | `ChatManage` 拆分 | ⏸ 评估后不做：127 方法 = 60 字段 × 2，是 JavaBean 固有成本；拆分只把复杂度搬到两个类，插件链需持两个引用 |
| **L16** | `executeQA` 200 行 → 7 个具名步骤 | ✅ 主方法 ~30 行 |
| **L17** | 插件表自动生成 + `--check` 守卫 | ✅ `pipeline-map.md`（14 事件 / 17 插件） |
| **L18** | 插件契约三行（读/写/失败） | ✅ 写在 `Plugin` 接口（统一规则不逐个重复） |
| **L19** | 拆 `:knowledge`（241 文件） | ⏪ **已回退**（L23）：只换来独立编译，知识库代码仍分散 4 处 |
| **L20** | `modelcontext` 下沉 `contract` | ⏸ 不可行：入口类 `Registry` 依赖 Jackson，搬进 contract 破坏「纯 JDK」红线 |
| **L21** | `platform` 业务残留归位 | ⏸ 不可行：9 类被 6 个模块共享，移到 domains 会让 retrieval/agent-engine 反向依赖 |
| **L22** | `BaseTool` 归位 `agent-engine` | ⏸ 不可行：domains/mcp 的 2 个工具用它，移过去会让 domains 反向依赖 agent-engine（R5c 刚清零） |

**新增守卫**（各配红态探针）：`check-comment-narrative` / `check-doc-truth` / `check-controller-purity` / `check-pipeline-map` / `check-known-gaps`；另加 `scripts/readability-report.py` 输出 6 个指标。

### 5.0b v0.6.0「业务内聚专项」（H 系列，主体已落地，尾巴见下表）

> 完整方案：`docs/internal/plans/architecture-remediation-v0.6.0.md`。
> 同日评估（现状盘点与 G 系列出处）：`docs/internal/plans/architecture-assessment-2026-10-20.md`。
> 定位：**不改 7 模块骨架**，只收三件事——链路内聚（H1~H3）、横切内聚（H4/H5）、边界内聚（H6~H8）。

| 批 | 内容 | 状态 |
|---|---|---|
| **H0** | 判据先行：端口准入 / 契约形状预算 / 平台依赖面三条守卫 + 业务能力映射一页纸 | ✅ 2026-10-20 |
| **H1a** | 问答终态单赢家：`TurnLifecycle`（收尾闸门 + SSE/续传两处观察者口径） | ✅ 2026-10-20 |
| **H1b** | H1b-1 ✅ 去 `controller.sse` 反向依赖；H1b-2 收锁与状态持久化 待做 | H1b-1 ✅ 2026-10-20 |
| **H2a** | 问答运行时段：抽 `TurnRuntime`（3 字段） | ✅ 2026-10-20 |
| **H2b** | H2b-1 ✅ 只读视图；H2b-2 ✅ 插件签名改 `PipelineState`（插件包 0 处 `ChatManage` 引用）；H2b-3 ✅ `QaRequestContext` 收口（P1.7，2026-10-21） | H2b 全部 ✅ |
| **H2c** | 状态段六组全部抽离（Answer / Rerank / Merged / QueryPlan / Entity / SearchOutcome）；`ChatManage` 只剩 history / usedMemories 等流转字段 | ✅ 2026-10-20 |
| **H3** | ✅ H3-1 阶段枚举/尝试状态 + H3-2 迁移收口；**两项核验后不做**：`KnowledgeTaskExecutor` 是 8 个服务的通用执行器（删了会回退 G4），5 个 stage 的通用接口需 god `IngestContext` 且与 `IngestJob` 重复 | ✅ 2026-10-20 |
| **H4a** | 任务契约：`TaskQueue`（命名/启停/测试钩子）+ `RetryDelayOverride`；6 套队列接口继承；钩子机制 5→1。H4b 迁实现 | H4a-1 ✅ 2026-10-20 |
| **H4b** | 任务实现：**datasource ✅ + wiki ✅**（均迁到 `RedisRetryingTaskQueue`）；memory/knowledge 已在基座 | H4b-2 ✅ 2026-10-20 |
| **H5a** | 分页统一：**H5a-1 ✅** 形状收敛（`PageResult<T>`，11 类型 → 1）；H5a-2 待做（管理端点加分页参数 + 前端） | H5a-1 ✅ 2026-10-20 |
| **H5b** | 软删校验守卫：`SoftDeleteFilterGuard`（26 表 / 默认 enforce）；实测 0 条存量违规，允许清单为空 | ✅ 2026-10-20 |
| **H6** | H6-1 ✅ `KnowledgeCatalogPort` 38→7 窄端口（复合接口兼容）；余 `*RowView` 回域、字段 20→12、其余 4 个宽端口 | H6-1 ✅ 2026-10-20 |
| **H7** | platform 判据换血（依赖面 + 域词汇） | 待做 |
| **H8** | 适配层逻辑化（不切模块、零搬迁）：协议包落点守卫 + `integration-map.md` | 待做 |
| **H9** | 管理面模板化（凭证 4→1、DTO 后缀 5→3） | 待做 |

**顺序**：G1（P0）→ G10 → H0 → G2/G3/G4 → **H5a（分页最急）** → H4a → H4b → H5b → H1 → H2a/H2b/H2c → H3 → H6/H7/H8 → H9。
**拍板结论（2026-10-20）**：H2 渐进三刀（配消费点棘轮）· H4 先端口后按域迁实现（wiki 最后）· H5 不做自动注入（先分页、后校验守卫）· H8 不切模块。详见方案 §7。

### 5.0c 并行专项：K 系列「扩展面与文档真实性」（v0.6.x）

> 方案：`docs/internal/plans/architecture-remediation-k-series.md`（含对 2026-10-20 架构评估的核实结论：4 处需更正，`1,433 金片`不实）。

| 批 | 内容 | 状态 |
|---|---|---|
| **K0** | 文档口径：README/overview/构建注释计数对齐 + `check-doc-truth.py` 扩 D2（业务控制器+探针）/D4（金片双口径）/D5（provider 注册数） | ✅ 2026-10-20 |
| **K4** | 装配点登记：22 个 `@Configuration` 三档（组合根 8 / 域内 10 / 模块内 4）+ `check-wiring-points.py`（W1~W4）+ agent-engine 组件扫描面登记 | ✅ 2026-10-20 |
| **K1** | `EngineFactory` 两表合表（9 引擎一张 `SPECS`，构造 + 地址策略同行；完整性测试）+ `FileServiceFactory` 死集合清理 | ✅ 2026-10-20 |
| **K2** | 跨域实体清零：`AgentView` 补 `tenantId`、8 文件改持视图、删恒 false 残留字段；守卫增 E1 判据（基线 0） | ✅ 2026-10-20 |
| **K3** | LLM 目录单源化：`ModelProviderCatalog` 由 `ProviderRegistry.list()` 生成；parity 测试换成注册表完整性测试；`validateConfig` 登记已知缺口 | ✅ 2026-10-20 |

**否决项**：不建通用 `CapabilityRegistry<T>`；不搬 `QaWiring` 上移 boot（理由见方案 §2）。

### 5.0d 并行专项：L 系列「业务链路对齐」（v0.6.x）

> 方案：`docs/internal/plans/business-link-remediation-l-series.md`（含对 2026-10-20 业务链路评估的核实结论：4 处更正）。

| 批 | 内容 | 状态 |
|---|---|---|
| **L0** | README 删"评测"承诺（能力清单 + 端点表两处）；报告更正落档 | ✅ 2026-10-20 |
| **L1** | 引擎类型常量单源化：`contract.vectorstore.VectorEngineTypes`（9 全集 / 7 可配置，差额 = postgres/sqlite） | ✅ 2026-10-20 |
| **L3** | 工具清单守卫（`check-tool-registry.py`，第 44 条）+ API-Key 幽灵规则检查 + `Skill(s)` 控制器改名 | ✅ 2026-10-20 |
| **L2** | embedding 单路径：L2a `EmbeddingVectors` 统一校验（条数/空向量/维度/index 归位）；L2b 删 `EmbedderClient`、pg 路径改走 `ModelRuntime`、`EmbeddingPort` 由运行时适配器实现 | ✅ 2026-10-20 |

**否决项**：RBAC 双表不合并（主语/语法/语义都不同）；工具装配暂不端口化（请求级构造 + 三个分发 switch，先加守卫）。

### 5.0e 并行专项：M 系列「后端代码质量收口」（v0.6.x，代码项已完成，M8 待会签）

> 方案：`docs/internal/plans/code-quality-remediation-m-series.md`（含对 2026-10-20 后端代码质量评估 03 的核实：5 处数字失真、2 条建议已过期或不成立）。
> 定位：不追 300 行硬指标；只收五类欠账——并发正确性（M2）、重复实现（M3）、事务边界（M4）、Memory 内聚（M5）、7 个大文件（M6）。

| 批 | 内容 | 状态 |
|---|---|---|
| **M0** | 核实 03 报告：大文件/事务/SHA/软删/N+1 实测；产出 M 系列方案 | ✅ 2026-10-20 |
| **M1** | 规模棘轮：方法 >150 行基线 32（新增即红、存量只减，+10 行 bugfix 余量）+ 红态探针；**不设 300 行硬门**（沿用 600/30） | ✅ 2026-10-20 |
| **M2a** | 钉钉：流表 key 加 channelId、token 单飞 + 负 TTL；拆解析/API/卡片流 | ✅ 2026-10-20 |
| **M2b** | 会话批量删除批量化 + 知识检索 KB 缓存（去逐条查/重复查） | ✅ 2026-10-20 |
| **M3a** | `platform.security.Hashes`：SHA-256 19 处替换（contract 1 处登记为例外） | ✅ 2026-10-20 |
| **M3b** | `platform.audit.ActivityAuditRecorder`：合并 3 份 `recordKbActivity` | ✅ 2026-10-20 |
| **M3c** | `LogSanitizer` 替换 4 份 `sanitizeForLog`；`trimUnicodeWhitespace` 入 `platform.text` | ✅ 2026-10-20 |
| **M4a** | `UserService.register` 改 `@Transactional` + 失败注入测试；删外层手写补偿 | ✅ 2026-10-20 |
| **M4b** | 事务边界约定（`mechanisms.md`）+ mcp/storage/session/im/datasource 多写审计 | ✅ 2026-10-20 |
| **M5a** | Memory 开关单次求值：`MemoryScopeGate` 返回 `(scope,cfg,ok,reason)`，`enabledScope`/`scopeDisableReason` 同源；清孤儿节注释 | ✅ 2026-10-20 |
| **M5b** | Memory 写管线/模型解析提取（`MemoryWritePipeline` / `MemoryExtractionModelResolver`）；保留门面委托 | ✅ 2026-10-20 |
| **M6a-1** | FaqEntryCommandService：`FaqEntryProjector`（4 处尾块归一，tag 每次只查一次）+ `FaqDuplicateChecker`（72 行规则抽类） | ✅ 2026-10-20 |
| **M6a-2** | FAQ 批量字段更新（`FaqBatchFieldUpdater`，150 行方法） | ✅ 2026-10-20 |
| **M6b** | UserService：`LoginTenantResolver` + `UserRegistrationService`；保留 `generateTokensFor(Object)` | ✅ 2026-10-20 |
| **M6c-1** | StorageFileResolver：删重复 provider 完备性 switch（工厂 Single Source）+ 提 `ResourceCatalogFileService` | ✅ 2026-10-20 |
| **M6c-2** | StorageFileResolver：`StorageBackendLocator` / `StorageConfigProjector` / `StorageEnvProvisioner` 提取 | ✅ 2026-10-20 |
| **M6d** | McpCatalog：`McpJsonCodec` / `McpToolNaming`；保留提示词常量与 DTO 巢居 | ✅ 2026-10-20 |
| **M6e-1** | KnowledgeService：类 javadoc 重写 + 悬空 javadoc/多余空行清理（注释腐烂） | ✅ 2026-10-20 |
| **M6e-2** | KnowledgeService：`KnowledgeTagBatchService`（标签批量与 N+1 写） | ✅ 2026-10-20 |
| **M7** | 卫生：Service/Ops/Support 判据、api/ 包说明；响应信封登记待拍板 | ✅ 2026-10-20 |

**收口（2026-10-20）**：M0~M7 全部完成；全量 5046 / 0 / 4，45 守卫绿。M8 响应信封统一是线格式变更，待产品/前端会签，当前不动代码。

**不排期**：M8 响应信封统一（线格式变更，需产品/前端会签）。

### 5.0f 并行专项：X 系列「构建迁移 Gradle → Maven」（`v0.7.x`）

> 方案：`docs/internal/plans/build-migration-maven-x-series.md`。动因（2026-10-20 拍板）：公司/团队标准是 Maven。
> 硬约束：Gradle 保留到「同一提交下 `mvn verify` 与 `./gradlew build` 同时全绿」才删；业务代码只允许 3 处构建耦合例外（`ContractPaths`、`ArchitectureRulesTest` 排除规则、迁移脚本路径）。

| 批 | 内容 | 状态 |
|---|---|---|
| **X1** | 骨架与依赖：根 pom + 7 子 pom、Boot BOM、proto/gRPC（xolstice 0.6.1）、repackage | ✅ 2026-10-21 |
| **X2** | 测试面：夹具改 **test-jar**（不搬文件）、surefire 分叉/环境/工作目录、ContractPaths 双候选、ArchUnit 四形态排除 | ✅ 2026-10-21 |
| **X3** | 质量闸门：spotless ratchet / javadoc `-Werror` / jacoco profile / 45 守卫挂 boot verify / 迁移脚本 | ✅ 2026-10-21 |
| **X4** | 切换收尾：6 守卫重写 + 11 处路径过滤、脚本/CI/Docker/文档、删 Gradle | ✅ 2026-10-21 |

**X1 读数（2026-10-21）**：`mvn -Dmaven.test.skip=true package` 绿 · `java -jar` 起服 `Started 22.0s` + `/health` 200 + 认证端点 401 · fat jar **189M = Gradle 189M** · 依赖清单差异 8 项全部定性 · 45 守卫绿。

**X2 读数（2026-10-21）**：`mvn test` 连跑两遍全绿 · **有效用例 5042 = Gradle 5042**（Gradle 5046−4 跳过 / Maven 5044−2 跳过）· 45 守卫绿 · `./gradlew build` 2m2s 仍绿。
夹具方案实测修正：拆独立模块会撞 `domains → domains-fixtures → domains` 的 reactor 循环 ⇒ 改用 build-helper（挂 test 源集）+ test-jar（`-tests.jar`）。
已知债：boot 的 `FaqContractTest` 与先跑测试共享 Spring 上下文，单 JVM 串行时走云存储分支失败（Gradle 4 分叉掩盖）；Maven 按同口径 `forkCount=2` 后一致，隔离债待修。

**X3 读数（2026-10-21）**：`mvn verify` **连跑两遍全绿**（spotless 3.10.4 ratchet + javadoc `-Werror` + 5042 有效用例 + 45 守卫）· **三类红态探针各红一次**（乱序 import / `{@link NoSuchTypeXYZ}` / 游离子目录）· `-Pcoverage -pl contract verify` 产出 `jacoco.exec` + 报告。

**X4 读数（2026-10-21，Gradle 已删）**：`mvn -B verify` 全绿（5042 有效用例 + 45 守卫）· `java-server-up.sh` 起服 `ready` + `/health` 200 + 认证端点 401 · `docker build` 成功 + 镜像自检（`app.jar` 与 `V1__baseline.sql` 都在）· 全量 verify 约 5–6 分钟（冷编译，Maven 无增量）。
四个实测修正：① 第三处构建耦合 `SourceRoots.repoRoot()`（删 Gradle 后 `JsonPropertyHygieneTest` 红）；② `spring-data-redis` 的 lettuce 是 optional（Gradle 传递、Maven 不传 ⇒ fat jar 缺 Redis 客户端，起服报 `StringRedisTemplate` 缺失）；③ `mvn -pl boot -am spring-boot:run` 会对每个 `-am` 模块执行 goal，起服改 `package + java -jar`；④ 版本/契约纯度/文档事实守卫的读数源全部改根 pom。
四个实测坑：① surefire 必须 `@{argLine}`（`${argLine}` 配置期解析，jacoco agent 挂不上）；② 守卫绑 boot 的 verify（根 pom 的 verify 在 reactor 最前面）；③ `ContractBoundaryRulesTest` 的 `DO_NOT_INCLUDE_TESTS` 对 `-tests.jar` 无效（补显式排除）；④ `StorageRuntimeEnv` 静态快照污染（`DefaultStorageBackendProvisionerTest` 配了 s3 不恢复 ⇒ `FaqContractTest` CSV 走云分支），已加 `@AfterAll` 恢复。

### 5.0g 并行专项：Q 系列「配置单源与状态收敛」（`v0.8.x` 续作，待推进）

> 方案：`docs/internal/plans/convergence-remediation-q-series.md`。输入评估：`docs/internal/plans/architecture-assessment-2026-10-24.md`。
> 定位：不拆 7 模块；只收四件事——扩展描述符单源（模型/向量库/存储）、问答请求段与终态、入库装配面、前端巨石。
> 核实结论：报告 4 处重复已完成/已否决项，5 处落法需改写；数字口径 6 处更正（详见方案 §1/§2）。

| 批 | 内容 | 状态 |
|---|---|---|
| **Q0** | 评估核实 + 删除收口（4 份文档按删除处理；引用迁 `docs/api/README.md` / `HANDOFF 11.1` / `HANDOFF §7` / getting-started） | ✅ 2026-10-24（48/48） |
| **Q1** | 模型配置结构校验：`ModelConfigValidator` 窄端口 + `ModelService` 三处接线（不联网） | ✅ 2026-10-24（5088/0/2） |
| **Q2** | SQL 下沉（复用 `KnowledgeTagRepository`）+ 删 `PipelinePorts` 占位 Port | ✅ 2026-10-24（5088/0/2） |
| **Q3** | 请求段冻结：`rerankThreshold` 归位 `RerankOutcome` → 不可变快照 + `fork()` 白名单 | ✅ 2026-10-24（5099/0/2） |
| **Q4** | 向量库 Descriptor：contract 只放控制面规则；`retrieval-engine` 持工厂/探测 | ✅ 2026-10-24（5092/0/2） |
| **Q5** | 存储 Provider Descriptor（域内）：校验/端点/探测/创建/清单单源化 | ✅ 2026-10-24（5097/0/2） |
| **Q6** | `KnowledgeProcessWorker` 构造面 20 → 约 5（三阶段移装配点） | 待做 |
| **Q7** | 终态收尾残余（并入 H1b-2，先补状态矩阵测试；不建 Coordinator） | ✅ 2026-10-24（Q7a/Q7b-1/Q7b-2，5104/0/2） |
| **Q8** | 前端流式判别联合 + `ChatMessage` 强类型 + 组件特征测试 | Q8a ✅ 2026-10-24（776/0）；Q8b（AgentStreamDisplay 拆分）待做 |
| **Q9** | `WikiBrowser` 拆分（首刀 `useWikiDirectory`） | 待做 |
| **Q10** | `AgentEditorModal` 拆分（先 `useAgentEditorForm` + 序列化/校验） | 待做 |
| **Q11** | 前端 ESLint / knip（report-only）+ 规模棘轮 | 待做 |

**顺序**：Q0 → Q1 → Q2 → Q4 → Q5 → Q6 → Q3 → Q7 → Q8 → Q9 → Q10 → Q11。
**不做**：`TurnCompletionCoordinator` 重建、`KnowledgeService` 三分、端口改名、contract 放 `runtimeFactory`、OpenAPI 全量生成、租户 KV Registry（观察）。

### 5.1 历史批次（v0.3.0 P1~P5 / v0.4.0 R1~R6，全部 ✅）

> **阶段路线（续，2026-10-16）**：**R1~R6（三 runtime + 模块收口）在 `v0.4.0` 全部完成** ⇒ **推送 `v0.4.0` + 合并 `main`**。 **✅ 2026-10-16 已执行**：`v0.4.0` 推送（995696f9..e3dce136）→ `main` 快进合并并推送（`c07786fe` → 含本记录的 tip）。**R1~R6 收口读数**：七模块 → 六模块（`:engine` 解散）、末轮增出 `:agent-engine`（七模块新图，见 §8.1 终检）；domains 对全部运行时模块只剩契约 + `modelcontext` 终态边；**16 守卫全绿**（新增模块图守卫）· 测试 5000 / 0 / 4。

> **阶段路线（2026-10-16 用户拍板）**：**P1~P5（两模块纠偏）在 `v0.3.0` 全部完成** ⇒ **推送 `v0.3.0` + 合并 `main`** ⇒ 新建 **`v0.4.0`** 分支，R1~R6（三 runtime）在 v0.4.0 上继续。 **✅ 2026-10-16 已执行**：`v0.3.0` 推送（95349098..c07786fe）→ `main` 合并推送（ef89e4ab..c07786fe）→ `v0.4.0` 新建并推送；R1~R6 在 v0.4.0 继续。

| 批 | 内容 | 状态 |
|---|---|---|
| **P1** | platform 域 DTO 归位示范：`StopSessionRequest` → `session/dto` + 端口中性签名 | 见 §12 |
| **P2** | KB config 形状三层拆分：7 件 Config 回 `knowledge/domain`；`KnowledgeBaseRowView` 载体改吃视图；View 的 from/toDomain 映射外移；`UserPreferences`→auth、`ParserEngineConfig`→tenant（余 2 件并入 P5） | ✅ 675b782b |
| **P3** | 跨域形状去框架化（mixin 机制、金片护航）：`SearchResult`→contract.retrieval；`AgentStep/AgentToolCall/ToolCallTarget`→contract.agent；`MessageView`→contract.session；`MemoryMessageCursor`→contract.memory | ✅ 744fdcb2 / 2a9b3453 / cac610c2 / 5ef29e07 |
| **P4** | 端口中性化进 contract：`AdminUserPort/AdminUserView/ApiKeyAuthPort` · `SessionQaPort` · `KnowledgeCatalogPort` · `TenantConfigLookup` · `EmbedSessionPort`（`SseSink`/`EmbedReply`）；config/settings 五件裁定留 platform（C8 豁免登记） | ✅ **6 刀全落**（163ddfcd/9db5b06d/d792a002/92c5eb70/151c7f25/4d7accdb）——**platform 业务形状清零** |
| **P5** | platform 收尾：fileserve/support 逐件裁；目录与 package-info 清理；**C8 门禁上线**（业务形状零命中验收） | ✅ e6f99afd / 1851b3ad / 1b020e97 |
| **R1** | 契约面先行：`contract.model.chat`（llm.domain 11 件）+ `contract.retrieval` 缺口 + `contract.agent` 6 件 + `contract.execution` 按需 | ✅ **收官**：R1a `ae0a17c0`（11 件 → `contract.model.chat`）· R1b-1 `b5a36912`（4 件 → `contract.websearch`）；余项 **R1b-2→R3 / R1c→R5 / R1d 不建**（判定见 §2.9） |
| **R2** | `model-runtime` 提取（**实际 153 件** = llm 58 / modelruntime 46 / embedding 21 / rerank 15 / modelcontext 13；计划的"163 件"含 tracing 6 + webfetch 4——**按计划自身 R4/R3 行裁定：tracing 归 R4、webfetch 归 R3**）；公开面分层（契约类 vs 实现面） | 🔄 **R2a ✅ `925071a2`+`f0355a7f`** · **R2b ✅ `c160b749`**（模块切出） · **R2c-1a ✅ `c25d90e7`**（`ModelRuntimePort`→`contract.model.ModelRuntime`、`VlmConfig` 契约化） · **R2c-1b ✅ `76c30539`**（装配器+配置映射入 model-runtime、共享传输归位 platform.http、模块内环清零）；**R2c-2 ✅ `fda13bbe`**（六处自建客户端改走端口工厂面 `chatClientFor`/`embedderFor`/`rerankerFor`/`chatConfigFor`；domains 对模型族的 import 70 处/22 文件 → **40 处/17 文件**）；**R2c-3a/3b ✅ `2e914b31`**（ASR 端口化 + `QaWiring` 装配走端口；`AsrTranscriber` 实现抽成独立类并自持 `@Component`，摘掉 domains 的手工 @Bean）⇒ domains 对模型族的 import **40 处/17 文件 → 27 处/14 文件**；**R2c-3c ✅ `1377e4ab` + `f83e2976`**（thinking 判定收进端口 `effectiveThinkingControl`——provider 词汇 5 处退场，`ModelDebugController` 对模型族 import 清零；顺带清 `InitializationController` 死参数）⇒ domains 余 **22 处/13 文件**；**R2c-3d 待做**（余项多为设计项：① `VlmDescriberWiring` 的 VLM 并发闸门（`ConcurrencyGovernor`+`Release`）建议收进端口（`ModelRuntime` 给"按模型闸门"或整个 VLM 调用）② `OllamaManageService` 的 Ollama 能力（status/models/pull）需契约端口 ③ `llm.extract` 族 9 处与 R4b 一并裁定（`PipelineConfig` 随 chatpipeline 走，`ExtractPrompts`/`EntityExtraction` 归 knowledge 或入契约）④ `PromptCache`/`ImageResolver` 各 1 逐条裁定；⑤ `modelcontext` 4 处**不动**——R5 之后是 `agent-engine → model-runtime` 的合法终态边）**R2c-3d 部分 ✅ `bafd7030`**（图片解析桥接 `ChatLocalImageResolverWiring` 归位组合根 boot ⇒ domains 余 **20 处/12 文件**）。**R2c 实质收敛**：余下 20 处里 4 处是合法终态边（`modelcontext` → R5），12 处与 R3/R4b 天然耦合（`retrieval.vlm` 的 VLM 客户端 + `ConcurrencyGovernor`/`Release` 闸门 ⇒ 随 R3 的 VLM 面一并；`llm.extract` 族 9 处 ⇒ 随 R4b：`PipelineConfig` 随 chatpipeline 走、`ExtractPrompts`/`EntityExtraction` 归 knowledge 或入契约），只剩 2 项可独立做：`OllamaService`/`OllamaModelInfo` 4 处（需 Ollama 能力端口）与 `PromptCache` 1 处（纯函数拆分到 platform）。⇒ **收窄 engine 的 `api(:model-runtime)` 应排到 R3/R4b 之后**
| **R3** | **retrieval-engine 模块提取**（实测 85 件 + 30 测试） | ✅ **收官**：**R3a** `a3d26936`（VLM 面归位 ⇒ retrieval 对模型族 = 0）· **R3b-1** `f9870dc6`（自足件上提）· **R3c** `8b0b3782`（`:retrieval-engine` 切出）· **R3b-3** `9b10c247`/`19d578d5`/`f322f5e2`/`6bb310d7`（向量店面 / 读面 / 写面+解析面 / fallback 变体）· **R3b-4** `a0b8f121`·**R3b-5** `195ed447`·**R3b-6** `8b1380a5`（工具面 / 落点裁定 / 观测面拆类）· **R3d-1..5** `3bf266fc`/`6ab7b52a`/`2e2fb0e1`/`616d3650`/`1c23a2f0`（补漏 / pg 直连句柄 / verifyBinding / 连通性面 / 工具面归位 + 依赖收窄）。**验收读数**：`retrieval` 对模型族 import = 0 · **`domains` 主码对 `com.ragagent.retrieval.*` import = 0** · `engine` 已摘 `:retrieval-engine` 依赖 · `boot` 显式声明 |
| **R4** | 归位：`tracing`(6) → `platform.tracing.decorators`；`chatpipeline`(46) → `domains/session/service.rag`（实落 `session/service/rag`）；`webfetch`(4) → `websearch` 域 fetch 面 | ✅ **收官**：**R4a** `fff4d3ba`（tracing 六件 + 契约流句柄三件）、**R4b-1** `89df46b3`（管线 SPI 4 端口 → `contract.pipeline`）· **R4b-2a** `10faf9f7`（`PipelinePorts` 契约化）· **R4b-2b** `592309b8`（webfetch 归位 + 抓取面两端口）· **R4b-2c-1** `91ec80ae`（`KnowledgeQaRunner` 端口，解评估域阻塞）· **R4b-2c-2** `3ecf29af`（本体 43 件归位 `session/service/rag` + `:engine` 解散） |
| **R5** | agent-engine 自 `domains/agent`（80 件）外提 + `contract.agent`（并入判定）+ 工具 SPI | ✅ **收官**：**R5a** `e69c99c9`（切出 `:agent-engine`，七模块）· **R5b-1..4** `8321006e`/`39cb352f`/`6a111e7a`/`02b19f11`（纯形状上提 · 预算/转向面 · `DocChunkSupport` 归 platform · `SkillCatalogPort`）· **R5c-1..3** `533d626e`/`ff9b8d21`（`AgentRunSpec` 读面 · 装配权下沉 `AgentEngineFactory`+句柄+A18 保真 · 过渡边摘除）。**验收**：domains 主码对 agent-engine import **55 → 0**；模块图 domains → contract/platform（agent-engine → model-runtime 的 `modelcontext` 为合法终态边）· 余：R6 模块图终检 + 两处检索错位测试迁移（`HybridResultOpsContextExpansionTest`/`ArtifactReferenceRewriterTest`）与随之摘 `domains` 的测试期检索依赖 |
| **R6** | engine 解散收口（剩余包归位 + 收窄 `api` 传递 + 模块图终检） | ✅ **实做部分随 R4b-2c-2 完成** `3ecf29af`：`:engine` 整模块解散（settings/jacoco/守卫清单/Dockerfile/依赖全摘）⇒ 两条 `api` 传递边自动消失；**余**：模块图终检放到 R5 之后（agent-engine 落位后一次跑）　+ **终检 ✅**：`e16c2fa8`（测试期检索依赖收口）· `d02f89db`（模块图守卫上线，**16 守卫**；夹具包改名消同名）。**实测：主码 16 条边全在矩阵内 · 主码同包 0 · 重复 FQN 0 · 夹具占用 0** |

## 6. 环境与运行

- 后端端口 **8083**；PG 独立库名 **`ragagent`**（同实例 :15432 下另有旧仓 `WeKnora` 库 —— **排查数据前先确认库名**：`grep DB_NAME .env` 或看启动日志 jdbc url）。
- 前端：`VITE_DEV_PROXY_TARGET=http://localhost:8083`（vite 默认值已改 8083）。
- `LOCAL_STORAGE_BASE_DIR` 必须持久目录（严禁 /tmp，macOS 会清理 ⇒ 原始文件丢失）。
- **起停服**：`bash scripts/java-server-up.sh` / `bash scripts/java-server-down.sh`（up 自挡端口占用；down 是唯一正确停服方式）。
- **指标面**：`/actuator/health`、`/actuator/metrics`（只暴露 health/info/metrics）；与业务接口同一认证链，匿名 401，带 JWT 200。QA 轮次计数在 `ragagent.qa.turns` / `ragagent.qa.failures`（按 mode 打 tag），终态在 `ragagent.qa.terminal{mode,outcome=done|failed|cancelled}`，首次问答后出现。
- **⚠️ 跑完 `mvn verify` 必须重启后端**（jar 被替换而进程仍跑旧代码 ⇒ 表现为"改了没生效"；用 `spring-boot:run` 时则是 `target/classes` 被就地重写）。
- CI 三 job：guards（`bash scripts/run-guards.sh` 为准）· backend（spotless ratchet + 全量测试 + 灌 `V1__baseline.sql` 进全新 ParadeDB）· frontend（type-check + test + build）。`seed` tag 已推送（spotless ratchet 需要）。
- 全量闸门（**推送/批次收尾必跑**；迭代中按 §9 分档）：
  ```bash
  cd ~/ragagent && env -u SYSTEM_AES_KEY JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -B verify
  ```
  期望：`BUILD SUCCESSFUL` + 各模块测试汇总条数守恒（最近基数 **5082 / 失败 0 / 跳过 2**，2026-10-24 实测；含 agent-engine 157 条——旧口径 4867 漏算该模块）。`PATH` 须 `/opt/homebrew/bin` 在前（python3.9 会让 file-size 假红）。
  **环境前置**：PG `max_connections=100`，多个 boot jar 残留会占满连接（实测 5 个实例 = 50 条 ⇒ `too many clients`）。跑闸门前先 `ps -Ao pid,command | grep '[b]oot/build/libs/boot'` 清残留。
- **dev 数据修复（2026-10-20）**：软删守卫误拦窗口（H5b~H5b-fix 之间）里生成失败的 5 条 embed 历史回答，已按会话用 embed `ensure` 补建 `message_suggestion_sets`（状态 `suppressed/disabled`，该智能体追问关闭）；全库 embed 历史已无「缺建议集合」行。
- 前端（触契约时）：`cd frontend && npx vue-tsc --build --force` + `npm test`。

## 7. 测试与安全网

- 回归网：**~5082 用例 / 1,346 契约金片**；每一步（哪怕纯移动）结束必须全绿。
- 已知偶发（全量并发下，单独重跑即绿，勿误判回归）：`WebToolsRecordingTest.searchWithContentFetchesLeadingPagesViaSharedFetchTool` · `EvaluationContractTest.getTerminalRunsExecution` · `EmbedRateLimiterTest.redisPathSharesBudgetAcrossInstances`。
- **Redis 瞬态族已修（2026-10-19）**：根因是 `EmbeddedRedis` 用 `ServerSocket(0)` 探端口在 `maxParallelForks` 下会撞——后到者借用先到者的实例，先到者 close 杀进程后连接全断。现改为固定端口池 + 跨进程 `FileLock`，红态探针 `EmbeddedRedisPortAllocationTest` 守着；全量连跑 5 遍 0 失败。再见到同类失败按真回归查。
- 覆盖率：**未执行基线**（`-Pcoverage`）⇒ 本项目**不声称任何覆盖率数字**（开放项）。
- 前端：`vue-tsc` 是最终文件集上的最后一步（`tsx --test` 只转译不查类型）。
- **契约金片**：`platform/src/testFixtures/resources/golden/`（`agent-45a/45b/45c/46/46b`、`chat-pipeline-46c`、`modelcontext-46a`，各带 `.sha256`），加载器 `platform.fixtures.GoldenResources` 逐字节校验；"重录"= 按当前实现同步常量并重算 sha256，禁止手改 JSON 或 sha256；新增 bundle 时使用方模块依赖 `platform` 的 `test-jar`。

## 8. 结构地图与守卫总表

### 8.1 现状模块（7）与目标（7）

> 计数口径：`<模块>/src/main/java/**/*.java`，**不含 package-info**（2026-10-16 R5a 后实测）。

```
现状（2026-10-24 实测，含 C 系列还乡）      目标（方案 §5.1）
contract      293 件（纯 JDK，零依赖块）  →  contracts/
platform      174 件（技术适配 + 追踪装饰/流句柄/共享传输 + 提示词/配置）→ platform/
model-runtime 134 件（llm 52 · modelruntime 39 · embedding 19 ·
                 modelcontext 12 · rerank 12）
                                        →  model-runtime（已成）
retrieval-engine 80 件（engine 69 · 根 4 · graph 3 · support/artifact/config/telemetry 各 1）
                                        →  retrieval-engine（已成）
agent-engine   63 件（根 19 · tool 15 · compaction 11 · skills 8 · prompt 7 · budget 2 · domain 1）
                                        →  agent-engine（已成）
domains      1366 件（knowledge 232 · session 182〔含 rag 50〕· mcp 155 · wiki 145 ·
                 datasource 128 · auth 104 · im 88 · memory 77 · agenttools 53 · …）
                                        →  domains
boot           17 件                      →  boot

依赖方向：boot → 全部；domains / 三个 runtime → platform, contract；platform → contract；contract → JDK
（**`:engine` 已解散**：chatpipeline → `domains/session/service/rag`、webfetch/抓取面 → `domains/websearch/fetch`
 ⇒ 旧的 `api(:model-runtime)`/`api(:retrieval-engine)` 传递边随模块消失；domains/boot 显式声明
  各 runtime（含 R5a 的 `:agent-engine`，domains 那条是**过渡边**待 R5b/R5c 收窄）
 ——其中 domains→model-runtime 是**过渡边**，待 R2c-3d 余项收完）
```
### 8.2 域内分层（方案 §6）

`controller`（绑定/帧写出）· `service`（用例与政策）· `domain`（状态与不变量）· `mapper` · `repository` · `dto` · `client` · `task` · `api` · `support` · `config`（只装配）。
禁止：`service` 引边界层/具体技术（A16/A17）· `domain` 引 Spring/Mapper · `repository` 回引 service · `controller` 跑编排 · `mapper` 放仓储门面（A11b）。

### 8.3 守卫与规则总表（**全部守卫 + ArchUnit**；新增规则前先查这里）

| 守卫 | 语义 | 基线 |
|---|---|---|
| `check-package-cycles.py` | 环/分层/域依赖/SCC（R1~R9） | `package-cycles.baseline.json`（只许减；包级 SCC 5 组） |
| `check-domain-contracts.py` | **R10 域间只经契约**（分档①②③） | `domain-contracts.baseline.json`（**6 条边 / 27 类**；clearable 0） |
| `check-contract-purity.py` | C1 导入根（纯 JDK）· C2 构建依赖 · C3 框架形状 | 硬零，无基线 |
| `check-contract-sync.py` | 金片键签名变化必须签字 | 1,346 金片 |
| `check-json-key-case.py --strict` | 换锚棘轮 + 冻结覆盖快照 | 冻结面登记 |
| `check-event-face-case.py` | 事件面/路由面命名口径 | — |
| `check-file-size.py` | 大文件棘轮（>600 棘轮，≥800 须登记） | `file-size.baseline.json` |
| `check-hygiene-budget.py` | 卫生预算计数（>100 行方法 / 通用 trim 副本 / ≥8 参构造器） | `hygiene-budget.baseline.json`（106 / 11 / 53，只许减） |
| `check-tool-registry.py` | 工具注册一致性（T1~T4 实现/分发/装配；T5 工具名常量只许在 ToolDefinitions；T6 前端能力表键 ⊆ 后端工具名） | 22 实现 / 22 条分发 / 33 个常量 |
| `check-go-anchors.py` | Go 锚点注释棘轮 | 0 |
| `check-fe-contract-keys.py` | 前端契约键棘轮 | 只许减 |
| `check-stray-dirs.py` | 游离/死包目录 | 0 |
| `check-dead-members.py` | 死成员（main+test+testFixtures） | 0 |
| `check-api-envelope.py` | 统一响应外壳 | 只许减 |
| `check-exception-semantics.py` | 检索引擎 catch 不得清空边界条件 | 0 |
| `check-package-dirs.py` | 包声明 ↔ 目录一致（含 testFixtures） | 0 |
| `check-module-graph.py` | 主码依赖矩阵 · 同包 · 测试边登记 · 测试错位登记 · 夹具包 | 登记表（只许减） |
| `check-platform-shapes.py` | platform 业务形状（业务词包 / 业务词文件 / 红旗命名三档） | `platform-shapes.baseline.json`（15 条） |
| `check-contract-shape.py` | R1/R2 契约形状预算（端口 ≤7 方法 / 行视图禁入 / 字段 ≤20） | `contract-shape.baseline.json`（19 条 = 端口 4 / 行视图 7 / 字段 8，只许减） |
| `gen-contract-consumers.py --check` | 每个 public 契约类型至少有一个消费者（跨模块 import 或 contract 内同包引用） | 硬零 |
| `check-port-budget.py` | R1 端口准入（域内 / 无实现端口 = 0） | `port-budget.baseline.json`（空） |
| `gen-arch-index.py --check` | `package-info` 与 `docs/architecture/packages.md` 同步 | 硬零 |
| `check-tenant-scope.py` | 跨线程身份必须走 `TenantScope`；禁止裸 capture/replay | 硬零 |
| `check-log-exceptions.py` | log 里禁止 `Throwable.toString()` / `.getMessage()` | 硬零 |
| `check-duplicate-classes.py` | 同一简单类名不得出现在多个模块主码 | 硬零 |
| `check-internal-codes.py` | 主码注释禁止批次号/裸日期戳（ISO-8601 时间示例保留）；旧夹具名不得回流 | 硬零 |
| `gen-module-map.py --check` | 模块/包根、业务域文件/控制器数量与 `module-package-map.md` 同步 | 硬零 |
| `check-version-consistency.py` | Boot/springdoc/MyBatis-Plus jsqlparser/README/wrapper/Dockerfile 版本一致 | 硬零 |
| `check-method-length.py` | 方法长度棘轮（>150 行只许减，新增即红） | `method-length.baseline.json`（只许减） |
| `check-wiring-points.py` | 装配点清单（W1~W4：@Configuration 必须登记；分类/理由/overview 三处一致） | `wiring-points.baseline.json` |
| `check-doc-truth.py` | 文档事实（README 的模块数/控制器数/package-info 覆盖率必须与代码一致） | 硬零 |
| `check-known-gaps.py` | 已知缺口（类注释声明缺口必须指向 `docs/known-gaps.md`） | 硬零 |
| `check-log-level.py` | 日志级别（warn 不用于 404 / 跳过语义） | 硬零 |
| `check-controller-purity.py` | 控制器纯度（HTTP 面只编排：禁直连 Mapper；裸转发 catch 只许减） | `controller-catch.baseline.json`（只许减） |
| `check-route-policy.py` | 路由权限覆盖（未登记公开路由/幽灵规则/对照表过期） | `route-policy.baseline.json`（384 路由 / 372 规则） |
| `check-controller-entity-imports.py` | 控制器实体导入（C10：只许降） | `controller-entity-imports.baseline.json`（0 / 0） |
| `check-ingest-flow.py` | 入库链路文档（`ingest-flow.md` 引用的类必须存在） | 硬零 |
| `gen-pipeline-map.py --check` | 管线事件表（`pipeline-map.md` 必须与插件代码同步） | 硬零 |
| `gen-port-map.py --check` | Port 对照表（`port-map.md` 必须与接口/实现/调用方同步） | 硬零 |
| `check-qa-flow.py` | 链路文档（`qa-flow.md` 引用的类必须存在） | 硬零 |
| `check-schema-parity.py` | schema 对账（TestSchema 建表 vs migrations/versioned） | 硬零（55 张表） |
| `check-reference-truth.py` | 引用真实性（package-info/公开文档不得断链、旧包名、死路径） | 硬零 |
| `check-capability-kinds.py` | 能力种类边界（跨 kind 直连禁止；同 kind 允许） | 硬零 |
| `check-package-info-fields.py` | 业务域 package-info 四要素（种类/入口/契约面/依赖） | 硬零 |
| `check-package-size.py` | 业务域包规模（>30 只许降；26-30 报警） | `package-size.baseline.json` |
| `check-comment-narrative.py` | 迁移叙事棘轮（注释只讲现在，不写迁移史） | `comment-narrative.baseline.json`（0） |
| `check-comment-decor.py` | 注释装饰（禁止整行横线分隔，统一 `// ── 段名 ──`） | 硬零 |
| `gen-domain-index.py --check` | 业务域索引（`domain-index.md` 必须与分类/源码同步） | 硬零 |
| ArchUnit `ArchitectureRulesTest` | A1~A20（含 A16/A17 边界禁令、A18 状态所有权、A19 分层、A20 装配契约） | 各基线只许减 |

> 守卫总数以 `scripts/run-guards.sh` 的 `run` 行数为准（当前 48 条）；本表是语义索引，二者增删要同步。

## 9. 落刀方法论（精要；**全量判据见归档 v0.2.0 §13/§14**）

1. **固定套路**：侦察（图分析+引用面）→ 动前断言（零入度/引用面白名单）→ `git mv` → FQN/import 修复（编译驱动）→ 定向测试 → 全量闸门 → 提交；**一次只搬一个包簇**。
2. **搬前三道判据**：① 同包裸名共居检查；② 消费域核查（只被本域用 ⇒ 回域，不进契约）；③ §5.1 三测 + **持久化形状排除**（内部件/实体字段类型不得进 contract）。
3. **三维闭包预检**：import + **同包裸名** + **字符串 FQN/注册名**（三类全查，缺一必栽）。
4. **`git mv` 后必须 `--rerun-tasks`**（时间戳让增量编译认为"已编译" ⇒ 测试静默少跑；每批必须核对测试条数守恒）。
5. **换锚五处**：`FROZEN_PREFIXES`（json-key）· `file-size.limits` 键 · json 键文件路径 · SCC 基线成员 · 扫描名单（`_source_roots.py` 的 `MODULE_DIRS` 一行覆盖多脚本）。
6. **编译是最硬判据**：同包裸引用/测试树同名包只有编译能揭穿；"改实现"必须**同步清 import**（R10 按 import 计边）。
7. **javadoc 是模块边界探针**：跨模块 `{@link}` 断链即报；契约模块内严格（FQN 降级 `{@code}`）、其它模块宽松，**不要用一把尺子扫全仓**。
8. **守卫必须能红**：新增/改守卫配红态探针；**判据自证**（退出码不经管道；数字能自计数就自计数）。
9. **搬构建插件必须连配置块**；**资源归属看"谁加载"**；新模块建好核对"测试条数总量守恒"（`build` 绿 ≠ 测试跑了）。
10. **先测后改**：行为变更先给失败测试/反例；金片/合约测试会挡住"看起来对但违约"的改动。
11. **改名/批量替换**：只替换"匹配文本本身即完整类型"的 FQN；带左边界排除（`(?<![\w.])`）；每批编译 + 跑 `check-package-cycles.py`。
12. **baseline `--write` 不是消红按钮**：先区分 ①换锚 ②分类缺失 ③真违例；存在新增时 `--write` 必须拒绝。
13. **用例层欠账收敛三手法**（L36~L45 清零 69 类时定型）：① **门面化**——service 注入 mapper ⇒ 建 `repository/XxxRepository` 薄转发；② **端口化**——service 依赖具体类（task 执行器 / client / 调度的 Scheduler）⇒ 抽 `domain` 或 `support` 的窄接口，实现侧 `implements`；③ **落点纠偏**——类本身没错、住错包（适配器 → `api`、`@Configuration` → `config`、Redis 实现 → `repository`/`runtime`）。**落点先过三关**：C10（控制器不 import `..domain..`）· A16（包名任一段等于 `controller/mapper/task/client/channel`）· 包规模红线（>30 文件）——三者常互斥，`support` 往往是唯一都放行的地方。

## 10. 批次纪律（v0.2.0 §15.2 十二条，全部保留）

1. 一次只动一个轴、独立提交独立全绿、双端闸门。2. "同 PR 带前端"核到视图层字段读取。3. 键名替换分清三类键（线格式/系统设置与 i18n/本地态）。4. 触 HTTP 面做真实冒烟；金片重录先看对比器。5. 每批写回 §12。
6. **规则型/守卫型测试必须发明可证伪的探针**（没探针的规则等于没规则）。7. 注册类扩展点必须实测行为（静默失效）。8. 拿日志/行为当证据前先读被验对象的类注释。9. **动包/类落点的批次，包结构守卫必跑**（build 已含 guards，此为显式复核）。
10. 跨进程 JSON 键名按"权威"分桶（REST=camel/SSE=对外权威面/RFC 原文/词表）。11. **闸门退出码必须显式取用**。12. **"看得见的数字"要自计数**。

## 11. 非目标（冻结面，勿列入修复）

**① 外部决定**：connector 第三方线格式 · provider 请求体 · websearch metadata · image_info（docreader）· rerank `RankResult` · MCP OAuth 载荷 · langfuse `lf_*` · IM 平台 ACK · i18n 与系统设置键。
**② 协议/行为面（需拍板）**：引用协议标记 · wiki 摄取契约标签 13 个 · chatpipeline/evaluation 模板标签 · 工具名 36 个与 schema enum。
**③ 落库/DDL 形态**：列名与 SQL 参数 · jsonb 列 · 检索引擎索引文档字段。
**Go 零值时间哨兵**：形状变更，另批。

### 11.1 已知缺口登记（代码注释只留一行指针：`见 HANDOFF §11.1`）

> 口径：状态（设计取舍 / 待实现 / 待核查）、影响、替代路径。新增缺口登记在这里，不在类注释里展开。

| # | 缺口 | 状态 | 影响 / 替代 |
|---|---|---|---|
| 1 | 跨租户共享（org-share / shared-agent） | 设计取舍 | 不实现跨租户授予链；同租户按成员角色授权，API Key 按租户隔离 |
| 2 | 资源目录 / 共享授权解析（产物、附件） | 设计取舍 | 产物下载恒 404；消息文件代理对 owner ≠ caller 恒 403；公开资源走预签名 URL |
| 3 | 初始化 `storageBackendId` 解析分支 | 待实现 | 该字段被忽略、按缺省存储处理；先建后端再切换 |
| 4 | `POST /system/sandbox-check` | 待实现 | 路由未登记，Spring 返回 404 |
| 5 | 连接器目录中的规划项 | 待实现 | 目录 17 条 / 实现 9 个；未实现类型不可创建 |
| 6 | Agent 推荐问题的 Wiki fallback | 设计取舍 | 无 FAQ/chunk 的知识库推荐问题恒空；手工配置开场问题 |
| 7 | `allowed_tools → capability` 派生表 | 待实现 | 只做白名单过滤，不派生能力表 |
| 8 | 模型运行时 debug / langfuse 装饰器 | 设计取舍 | 只有 `LangfuseChatClient` / `LangfuseReranker`；embedding/rerank debug 装饰不存在 |
| 9 | Langfuse 概率采样 | 待实现 | `sampleRate` 在 (0,1) 时不生效，trace 恒采样 |
| 10 | 提示词缓存指纹元数据 | 待实现（非行为面） | `prompt_prefix_fingerprint` / `call_purpose` 未写入 span |
| 11 | 消息建议的 Langfuse span | 待实现 | 该链路无 trace |
| 12 | 向量店进程内引擎注册表 | 设计取舍 | 注册表为空，连通性探测走厂商直连 |
| 13 | OpenSearch 超大批量 scroll | 待实现 | 单次写入超批量上限时不分页；调小分块批量 |
| 14 | Ollama 私部署分支 | 设计取舍 | 只支持 `localhost`，不处理自定义私部署路径 |
| 15 | 工具 SPI 迁入 `contract.tool` | 已关闭 | `AgentTool` / `BaseTool` / `ToolDefinitions` 均在契约层 |
| 16 | 审批闸门未装配 | 设计取舍 | MCP OAuth / 工具审批受理分支返回 500，不静默放行 |
| 17 | IM 知识桥接的存疑接线 | 待核查 | 生产 `/info` 与 `/search` 的 KB/检索面恒为空 |
| 18 | Wiki 页面 → chunks 同步 | 待实现 | Wiki 内容不写入 `chunks`，不参与常规 RAG；走 Wiki 专用检索 |
| 19 | `Provider.validateConfig()` 生产接线 | 已关闭（Q1） | `ModelConfigValidator` 在模型保存（创建/更新/凭据）路径统一调用，只做结构校验 |

## 12. 执行记录（v0.3.0 起；模板：批次｜内容｜读数｜验证）

| 批 | 日期 | 内容 | 读数/验证 |
|---|---|---|---|
| P1 | 2026-10-16 | `StopSessionRequest` 归位 `domains/session/dto`（方案 §8.8.1 原文落点）+ `EmbedSessionPort.stopSession` 中性化（rawBody 透传、解析归会话域，口径对齐 `MessageSuggestionController.ensureSuggestions` 先例）；`platform.session.dto` 目录消亡 | 14 守卫绿 · 金片 1349 签名一致 · gate 2m16s / **4996 / 失败 0 / 跳过 4** · 端点覆盖 `EmbedContractTest` + `SessionG6ContractTest` 全绿 |
| P2 | 2026-10-16 | KB 配置形状三层归正：7 领域形状回域 + 契约视图 7 件纯化 + 域侧映射单一实现（`KnowledgeBaseConfigMaps`）+ 未生效标注收进契约 + VLM 凭据写回合并保护 | 14 守卫全绿（R10 无新增边）· 金片 1349 一致 · gate 2m14s / **4996 / 失败 0 / 跳过 4**；**契约测试当场抓到并修复 `toDomain` 缺 null 透传（EvaluationContractTest 500→绿）** |
| P3 | 2026-10-16 | 跨域形状去框架化四刀：`SearchResult`→contract.retrieval · `AgentStep` 族→contract.agent · `MessageView/PipelineMessageView`→contract.session · `MemoryMessageCursor`→contract.memory（mixin 承载注解；`ByteKeyOrder`/`ZeroTimes`/`ProviderMetadataMaps` 三共享物上提） | 四刀各自 14 守卫全绿 · 金片 1349 一致 · gate 2m21s~3m1s / **4996 / 失败 0 / 跳过 4**；platform 的 retrieval/agent/memory 三包消亡、session 收缩至 2 端口（P4 对象） |
| P4 | 2026-10-16 | 端口中性化五刀：AdminUser 族→contract.auth · SessionQaPort+QA 链 EventBusInterface→contract.session · TenantConfigLookup→contract.tenant · KnowledgeCatalogPort（去 Page）→contract.knowledge · ApiKeyAuthPort 中性化→contract.apikey；config/settings 五件裁定留 platform | 五刀各自 14 守卫全绿 · 金片 1349 一致 · gate 1m56s~2m45s / **4996 / 失败 0 / 跳过 4**；platform 的 auth/apikey/retrieval/agent/memory 消亡、knowledge 仅余 codec、session 仅余 EmbedSessionPort（P4f） |
| P4f | 2026-10-16 | `EmbedSessionPort` 中性化入契约：`SseSink`（帧出口）+ `EmbedReply`（status+body）；SSE 链全链换型（FrameWriter→Emitter→Orchestrator→Executor→Controller）+ 边界包装（DelegateOps/Adapter）；`platform.session` 包消亡 | 14 守卫全绿（R8 修复）· 金片 1349 一致 · gate 2m6s / **4996 / 失败 0 / 跳过 4** |
| P5 | 2026-10-16 | platform 收尾三件：`ParserEngineConfig` 去框架化入 `contract.tenant`（27 注解 → Core mixin）· fileserve 逐件裁（4 端口 + `OpenedFile` 上提契约；HTTP 三件 + `StoragePaths` 留登记）· **C8 门禁上线**（业务形状棘轮，3 条登记） | 15 守卫全绿 · 金片 1349 一致 · gate 1m39s~2m23s / **4996 / 失败 0 / 跳过 4**；platform 只剩技术适配 + 3 条业务形状登记 |
| —（杂项） | 2026-10-16 | 61 份过期文档清理（用户确认；活文档零引用已核）· `scripts/dev-env.sh` login 提取器适配统一响应壳（token 移入 data） | 冒烟 sessions / knowledge-bases 200 · token 265 字符 |
| R3b-6 | 2026-10-16 | **观测面拆类**：`RetrievalObs` 的纯函数族（截断/命中预览/分数汇总/段落预览/格式化）上提 `contract.retrieval.RetrievalObsRows`；引擎内部形状两法（`summarizeRetrieveOutput`/`summarizeIndexHits`，签名取 `EngineTypes.*`）留实现模块；全仓接收者重指向 + 清 6 处失效 import；json-key 按 BASELINE 登记 12 个**观测 span 既有 wire 键**（同 `agent/ActPhase.java` 同族） | 15 守卫绿 · 金片 1349 一致 · gate 2m39s / **5000 / 0 / 4** · 冒烟 200 |
| R3d-5 | 2026-10-16 | **工具面归位 + 依赖收窄**：`GrepChunkView`→**`GrepChunkHit`** + `GrepChunksScoring` → `platform.knowledge`（改名是为过 C8 红旗「View」，职责不变）；**domains 主码对检索实现面 import = 0**；`engine` 摘掉 `api(:retrieval-engine)`（engine 引用实测为 0）· `boot` 显式 `implementation` · `domains` 仅 `testImplementation`（两个错位测试登记为待迁债务） | 15 守卫绿 · 金片 1349 一致 · gate 2m49s / **5000 / 0 / 4** · 冒烟 200 |
| R3d-4 ✅ `616d3650` | 2026-10-16 | **连通性面搬迁落地**（照施工单执行）：契约面 `VectorStoreConnectivity` + 探测体整块 → `retrieval.engine.VectorStoreConnectivityProbe`；域侧 563→344 行（只剩校验+委托）。原施工单留档：`domains/vectorstore/service/VectorStoreConfigService.java` 的探测块 = `testConnection` 215-233 + 8 个私有探测法（`testElasticsearch` 244-279 · `testPostgres` 281-297 · `testQdrant` 303-313 · `testMilvus` 320-329 · `testTencentVectorDB` 336-350 · `testWeaviate` 352-381 · `testDoris` 389-401 · `testOpenSearch` 409-424）≈ 185 行；对外 5 个厂商静态口（306 Qdrant · 322 Milvus · 338-340 Tencent（含 `TencentVectorDbRestClient` 的 catch 分支）· 394 Doris · 416 OpenSearch）。**五步**：① `sed -n '215,424p'` 整块取出；② 契约面建 `VectorStoreConnectivity`（`String probe(String engineType, ConnectionConfig config)`，`ConnectionConfig` 已是契约类型 ✓）；③ 整块搬进 retrieval-engine 新类实现该端口（厂商 repo 本就在那，`ssrfGuard` 来自 platform ✓）；④ 域侧只留两条校验（`validateConnectionConfig`/`validateConnectionAddrSSRF`）与错误包装 + 委托端口调用；⑤ 编译-守卫-闸门一遍，随后即可**收窄 `engine` 的 `api(:retrieval-engine)`**（engine 主码对 retrieval import 已 = 0）| 未动手原因：185 行整块读写的预算不足，宁留下可执行施工单也不留"编译过、没验过"的中间态 |
| R3d-3 | 2026-10-16 | **解析端口补 `verifyBinding`**（归属校验收进实现侧；`KnowledgeBaseService` 去掉 registry/factories/ownership 三件）+ **`FaqEntryCommandService` 的批量更新改走既有 `VectorIndexStore` 端口**（那两个方法 R3b-3 第一片就已上端口 ⇒ 零新设计）⇒ domains 检索直连 **13 → 10 处**（余：agent 工具面 4 待 R5；厂商探测 5 + `TencentVectorDbRestClient` 1 = 连通性面整块，施工前置已勘察） | 15 守卫绿 · 金片 1349 一致 · gate 首轮 1 例 Redis 族失败（隔离 6/0 绿）· 二次全量 3 例同族（隔离 11/0 绿，本批不触 Redis 路径 ⇒ 本地环境抖动族）| 
| R3d-2 | 2026-10-16 | **pg 直连句柄落地**（先取证后动手）：先核 3 个失败测试 = `@SpringBootTest` 全上下文 ⇒ 真因是pg 引擎为 **boot 显式 @Bean**、而注册表按引擎类型查的是 env-store 面（上下文里查不到）。修法：`IndexEngineGateway` **直注 pg 引擎 bean**；`PostgresAdapter` 基于**具体类型**（实测 pg 件实现的是 `RetrieveEngineRepository`，上一版的 `RetrieveEngineService` 假设是错的）；域侧 Move/Clone 字段换端口 ⇒ **domains 对检索实现类 import 清零**（仅余 agent 工具面 4 条待 R5） | 15 守卫绿 · 金片 1349 一致 · gate 2m50s / **5000 / 0 / 4**（6 例此前失败测试全过）· 冒烟 200 |
| R3d-2 试做（已回退） | 2026-10-16 | **pg 直连句柄**：加 `IndexEngineResolver.postgresDirect()` + `PostgresAdapter`（基于 `RetrieveEngineService` 接口 —— **编译通过**，证明接口确有 save/index/delete*/copy/batchUpdate/estimate 那一套，适配器代码下次可复用），并把 Move/Clone 的 `PgVectorEngineRepository` 字段换成解析端口。**结果：6 例 move/copy 契约测试红**（KnowledgeSearchMoveContractTest×2 · KnowledgeMoveReparseTest · KnowledgeVectorRoutingTest 等）⇒ **语义不等价**：旧字段是 pg 引擎 **bean**（测试直接注入真实/桩件），新路径经 registry **按引擎类型查找**（测试上下文中查不到）。已按纪律 `git checkout` 全量回退（不为过闸门改动契约测试）。**下次正确做法**：① `postgresDirect()` 在网关里**直接注入 pg 引擎 bean**（同模块合法）而非走 registry；或 ② 契约面暴露"pg 句柄 bean"端口 + 装配层注入；两条都要先看那 4 个失败测试的构造方式。 | 守卫全绿 · 工作区回到 HEAD（gate 基线 5000 / 0 / 4）|
| R3d 施工前置（勘察 2） | 2026-10-16 | 连通性面可施工性已核：**`ConnectionConfig` 已是契约类型**（`contract/vectorstore/ConnectionConfig`，公有字段）⇒ 端口可直接收；探测块 = `VectorStoreConfigService` 的 `switch(engineType)` + 9 个私有探测方法（`testElasticsearch`/`testPostgres`/`testQdrant`/`testMilvus`/`testTencentVectorDB`/`testWeaviate`/`testDoris`/`testOpenSearch`/`sqlite→""`），它们只调各厂商 repo 的测试口 + `ssrfGuard`(platform) + 驱动属性 ⇒ **整块可迁 retrieval-engine**（厂商 repo 本就在那），域侧留两条校验（`validateConnectionConfig`/`validateConnectionAddrSSRF`）与错误包装。**pg 直连句柄**：`IndexEngineResolver` 补 `postgresDirect()`，rettrieval-engine 侧加第二个适配器（包 `PgVectorEngineRepository`：save/batchSave/estimateStorageSize/deleteBy*List/copyIndices/batchUpdate*/moveKnowledgeIndices）。两块共用一次编译-闸门循环，建议合批 | 勘察命令见本行内容 |
| R3d-1 | 2026-10-16 | 补两处漏网的实现注入（`MessageService`/`AgentToolBackends` → `HybridSearchPort`）+ 两处 javadoc 措辞。**webfetch 归属拍板**：按方案原文归 **websearch 域 fetch 面**，但**执行挪到 R4b 之后**——实测 `engine/chatpipeline/plugin/PluginWebFetch` 在用（另 `agenttools/web/WebFetchTool`）⇒ 现在迁会造成 `engine → domains` 违规。**R3d 余**：① `VectorStoreConfigService` 的探测面 11 处（各厂商 `*RetrieveRepository` 7 + `VectorStoreService` + `RetrieveEngineRegistry` + `RetrieveEngineFactories`）⇒ 需把"向量店连通性探测"整段沉进 retrieval-engine（契约端口 + `ConnectionConfig` 载体裁定）；② `PgVectorEngineRepository` 2 处（KnowledgeMove/Clone 的 pg 直连回退）⇒ 解析端口补"pg 直连句柄"；③ 工具面 4 处待 R5；④ 之后才收窄 `api(:retrieval-engine)` | 15 守卫绿 · 金片 1349 一致 · gate 2m16s / **5000 / 0 / 4** · 冒烟 200 |
| R3d 勘察 | 2026-10-16 | **engine 主码对 retrieval 的 import = 0** ✓（chatpipeline 早已走契约端口）。**收窄 `api(:retrieval-engine)` 的前置** = 清 domains 剩余 ~15 处直连：① agent 工具面 `GrepChunkView`/`GrepChunksScoring`（各 2 处）⇒ **R5 后由 agent-engine → retrieval-engine 合法消费**；② `PgVectorEngineRepository`（2 处：KnowledgeMoveService/KnowledgeCloneService 的 pg 专有操作）⇒ 需给 `IndexEngine` 或其伴生端口补 pg 专有面；③ 各厂商 `*RetrieveRepository`（7 处，集中 `VectorStoreConfigService` 的**连通性测试**：Tencent/Doris 等 `testConnection(...)`）⇒ 需契约面「向量店连通性」端口；④ `HybridSearchService` 2 处**仅 javadoc 提及**（KnowledgeQaController/SessionStreamController）⇒ 改措辞即可。另：`webfetch` 4 件**对模型族/检索零出边**（只依 contract/platform）⇒ 按方案原文可整体迁 `domains/websearch`，但需先核其消费方（若 chatpipeline 在用则须等 R4b，否则 engine → domains 违规） | 勘察命令：`grep -rh "^import com\.ragagent\.retrieval\." engine/src/main`（空）|
| R3b-5 | 2026-10-16 | **工具面落点裁定 + 零头**：`ChunkSearchUtil`/`ImageInfoEnricher`/`ImageInfoMatchUtil` → `platform.knowledge`（技术/文本解析工具，非业务形状 ⇒ 合 C8）；`GrepChunkView`/`GrepChunksScoring` 留实现模块（C8 红旗命名「View」+ 二者是 agent 工具面 ⇒ R5 后 agent-engine → retrieval-engine 合法消费）。契约面新增 `SourceTypes` （域侧两处常量换掉）；`RetrieveGraphRepository` 补 `default enabled()`（`SystemInfoService` 去 instanceof）；错位测试 `HybridSearchServiceStoreGroupTest` 随迁 retrieval-engine（`TenantService` → 契约 `TenantConfigLookup`）、`ChunkSearchUtilTest` 随件迁 platform。**未完成并说明**：`telemetry/RetrievalObs` 域侧与引擎内部消费混在一个类（内部签名取 `EngineTypes.*`）⇒ 需拆类或等内部件口径，本批不动 | 15 守卫绿 · 金片 1349 一致 · json-key 换锚 2 处（BASELINE 口径）· gate 2m30s / **5000 / 0 / 4** · 冒烟 200（含 system/info）|
| R3b-4 | 2026-10-16 | **工具面上提**：`SearchTextUtil`（322 行，纯逻辑）· `WebResultConverter`（109 行，仅契约依赖）retrieval-engine/support → `contract.retrieval`（消费方：domains 三件 + chatpipeline 六件不再穿透实现模块）。**暂留**：`ImageInfoEnricher`（579）/`GrepChunksScoring`（249）——它们引同包 `ChunkSearchUtil`/`GrepChunkView`（带 Jackson）⇒ 进不了契约（零依赖红线），落点与这两件一并裁定。json-key 按 **BASELINE 的 repo 根相对口径**换锚（`published_at` 是既有 wire 键；**没**写成冻结前缀——那是源根相对口径，写错即成死条目，本会话已踩过） | 15 守卫绿 · 金片 1349 一致 · gate 2m52s / **5000 / 0 / 4** · 冒烟 200 |
| R3b-3 第三片补 | 2026-10-16 | **解析端口补 fallback 变体**：新增 `contract.retrieval.TenantRetrievalDefault`（租户回落口径：引擎表{不透明 JSON} + 驱动名）+ `IndexEngineResolver.resolveFor(tenantId, storeId, fallback)`；`IndexEngineGateway` 按 `EffectiveEngines.of(...)` 实现（口径逐字保留）；`ChunkQuestionService` 去掉六个检索内部件 import，只留回落口径构造。**效果：domains 主码对检索实现类 import 归零**——剩余全是工具面（support 六件 + telemetry/RetrievalObs + graph 实现件）与 `EngineTypes` 常量 | 15 守卫绿 · 金片 1349 一致 · gate 2m41s / **5000 / 0 / 4** · 冒烟 200 |
| R3b-3 第三片 | 2026-10-16 | **索引写面端口化 + 绑定解析下沉**：新增 `contract.retrieval.IndexDocument`（域侧真给的 10 字段）· `IndexEngine`（已绑定 store 的写面句柄，9 方法）· `IndexEngineResolver`（租户+店 → 句柄，未绑定由调用方判定）；retrieval-engine 新增 `IndexEngineGateway`（解析口径逐字保留 + `IndexDocument→EngineTypes.IndexInfo` 一次映射，内部形状不上提）；domains 的 `KnowledgeVectorWrites` 只留域判定与转交，ChunkVectorIndexer/KnowledgeProcessWorker/FaqIndexWriter + 迁移克隆标签 FAQ 服务改走端口；测试随行为分家（租户/跨租户/虚拟线程断言 → 新 `IndexEngineGatewayTest`）。**未动**：`ChunkQuestionService` 的「无绑定回落租户有效引擎」变体（需给解析端口补 fallback 变体）· `HybridSearchServiceStoreGroupTest`（错位测试待随迁） | 15 守卫绿 · **R9 SCC 一组变形已登记理由** · 金片 1349 一致 · gate 2m47s / **5000 / 0 / 4** · 冒烟 200 |
| R3b-3 第二片 | 2026-10-16 | **混合检索读面端口化**：新增 `contract.retrieval.HybridSearchPort`（三方法 = 域侧真调用面：hybridSearch / getQueryEmbedding / resolveEmbeddingModelKeys；载体已是契约 `SearchParams`/`SearchResult` ⇒ 零设计）；`HybridSearchService implements` 之；域侧 5 消费点改走端口（KnowledgeBaseController · FaqEntryQueryService · QaWiring · AgentToolKbBackends · MessageSearch） | 15 守卫绿 · 金片 1349 一致 · gate 2m27s / **4997 / 0 / 4** · 冒烟 200 |
| R3b-3 第一片 | 2026-10-16 | **向量索引写面端口化**（路线丙）：`IndexRow` 上提 `contract.retrieval` + 新增 `VectorIndexStore` 端口（7 方法=域侧真调用面）；`VectorStoreService implements` 之（静态 estimateStorageSize 改名 *Static、端口面给实例方法）；**内部形状原地不动**（不再跨模块即可可变，载体红线不适用 ⇒ 省掉 500~800 处机械波）；域侧 4 消费点改走端口（ChunkVectorIndexer/KnowledgeTagService/FaqIndexWriter/KnowledgeProcessWorker） | 15 守卫绿 · 金片 1349 一致 · gate --rerun-tasks **4997 / 0 / 4** · 冒烟 200（首两轮各一例 Redis 族失败，隔离重跑 3/0、4/0 绿）|
| R3b-2 勘察 | 2026-10-16 | **形状 reshape 的实测规模与改道建议**（未动代码）：`EngineTypes.{IndexInfo, RetrieveParams, IndexWithScore}` reshape 波 = **构造 93 + 字段赋值 143 + 字段读取约 250~550**（字段访问 `x.f` → record 访问器 `x.f()`）/ 34+ 文件。**依据检查**：载体红线有明文（§2.1「不得上提…可变 setter 到 L1」+ 方案「contracts = 不可变载荷」）⇒ 可变形状确实不能上提，但**未被要求必须上提**。**推荐改道（丙）**：内部形状留在 retrieval-engine（不再跨模块即可保持可变 ✓ 合法），契约面改为**领域级载体 + 端口**——新增 `contract.retrieval.IndexDocument`（不可变 record，字段=域侧真给的 10 项），读面复用**已有** `SearchParams`/`SearchResult`；端口按域侧实测调用面切：写面 `batchIndex(docs)`/`deleteByChunkIdList`/`deleteByKnowledgeIdList`（取 ids + dimensions + kbType + Embedder{契约✓}）、向量店面 `saveIndexRows`/`deleteByChunkId`/`estimateStorageSize`/`IndexRow`、读面 `HybridSearchService` 的检索入口。收益：把"500~800 处全仓机械波"换成"1 个契约 record + 2~3 个端口 + 域侧 ~13 处消费点改造"，且分层更正确（引擎内部形状不再出门） | 实测命令：`grep -rn "\.\(isEnabled\|score\|matchType\|…\)\b" retrieval-engine/src/main domains/src/main | grep -v " = "` = 550 |
| R3c | 2026-10-16 | **`:retrieval-engine` 模块切出**：`com.ragagent.retrieval` 整树（main 91 / test 30）+ `jieba` 资源（hmm_model 1.1MB + 测试基线）engine → retrieval-engine，包名不变；依赖按实测 import 面（contract/platform api + Jackson/spring-context·jdbc·boot + neo4j/sqlite/Hikari implementation + mysql/postgres runtimeOnly）；**测试任务口径随迁**（integrationTest 显式入口 + test 排除 *IT——否则两台 env 门控 IT 混进 test：实测 +2 条/+2 跳过）；七处模块清单同步 + 异常语义守卫扫描根随迁（守卫自报"扫描根不存在"）+ 大文件基线 3 条换锚（ModelDebugController 出榜属收紧）。engine 过渡期 api 传递；**engine 的检索遗留依赖未删**（Gradle implementation 不传递，删前要逐个核 runtime 可达性，留 R6） | 15 守卫绿（环 0 · SCC 9 · 域间 6 条不变）· 金片 1349 一致 · gate 1m58s / **4997 / 0 / 4**（engine 315→16，新模块 292）· 冒烟 200 |
| R3b-1 | 2026-10-16 | **检索契约面第一刀**：三个自足件上提 `contract.retrieval`——`RetrieveGraphRepository`（图库写/删/读端口，签名只取 contract.graph 的 NameSpace/GraphData）· `TenantStoreOwnership`（@FunctionalInterface）· `RetrieveEngineException`（哨兵异常族：Kind + 四哨兵 + 分类/重抛工具）；裸引用补 import 10 文件；javadoc 两处跨界 {@link} 改 {@code}。**未动**：`RetrieveEngineRegistry`/`RetrieveEngineService` 取 `EngineTypes.*`（可变形状，待 reshape）；工具面 7 件与 impl 端口待后续批 | 15 守卫绿（环 0）· javadoc 0 错 · 金片 1349 一致 · gate 2m32s / **4997 / 0 / 4** · 冒烟 200 |
| R3a | 2026-10-16 | **VLM 面归位**：`VlmClient`+`VlmHttpTransport` retrieval/vlm → `modelruntime/adapter/provider/vlm`（两测试随迁；传输层 env 面换 `platform.config.AppEnvLookup`，否则 model-runtime → engine/retrieval 成环）；契约面新增 `VlmPredictor` + `ModelRuntime.vlmPredictorFor(ModelRowView)`，装配链（配置/SSRF/观测/命名闸门）整段收进 model-runtime；消费点 `VlmDescriberWiring`/`ModelDebugController` 改走端口；测试重分家（装配断言 → 新 `ModelRuntimeAssemblerVlmTest`，HTTP 形状 → 随迁的 `VlmClientTest`）。**A17 棘轮收紧**：`VlmDescriberWiring` 摘出 SERVICE_TECH_BASELINE（守卫报"基线条目已不再违例"） | 15 守卫绿 · 金片 1349 一致 · json-key 换锚 `--write`（1 出 1 入）· gate 1m46s / **4997 / 0 / 4** · 冒烟 200 · **retrieval 对模型族 import = 0**（R3 验收项达成）· domains 模型族 import 20 → 18 处 |
| R2c-3d | 2026-10-16 | **图片解析桥接归位**：`ChatLocalImageResolverWiring`（domains 存储 ↔ model-runtime `ImageResolver` 静态钩子）+ 其单测 → `boot/config`（跨模块胶水按"由 boot 注入"归组合根）。**收敛判定**：余 20 处 = 4 合法（R5）+ 12 耦合 R3/R4b + 4 Ollama 端口 + 1 PromptCache 拆分的纯函数 | 15 守卫绿 · 金片 1349 一致 · gate 2m21s / **4996 / 0 / 4** · 起服 200 |
| R2c-3c | 2026-10-16 | **thinking 判定收进端口**：`ModelRuntime.effectiveThinkingControl(ChatConfig)`（provider 能力 + extra_config 覆盖的生效解析）；`ModelDebugController` 删静态判定、改调端口 ⇒ 该文件对模型族 import 清零。实测 `ProviderName`/`ProviderRegistry` 跨模块只有 2 处，故不把 34 件的 provider 定义族搬进契约。顺带清 `InitializationController` 死参数（ConcurrencyGovernor 在 R2c-2 后无人用） | 15 守卫绿 · 金片 1349 一致 · gate 2m30s / **4996 / 0 / 4** · 起服 200（死参数清理按纪律只跑编译 + 起服） |
| R2c-3a/3b | 2026-10-16 | **ASR 端口化 + QA 装配走端口**：`AsrTranscriber`（接口 + 三形状 + 两异常）→ `contract.model`；缺省实现抽成独立类 `OpenAiAsrTranscriber`（model-runtime，自持 `@Component`）——原先实现**嵌在接口里**，逼得 InitializationWiring 手工 new 并 @Bean；QaWiring 匿名装配改委托端口（保留自身 wrap 与取数闸门）；顺带修 VlmHttpTransport 对内嵌实现静态方法的限定引用 | 15 守卫绿 · 金片 1349 一致 · gate 2m1s / **4996 / 0 / 4** （首轮两例 Redis 族失败 → 隔离重跑 10/0 绿）· 起服 200 |
| R2c-2 | 2026-10-16 | **六处"自建客户端"改走端口**：端口新增"按行视图的工厂面"四方法（不做取数、不挂观测装饰——取数口径本就因调用方而异，只统一"怎么造"）；改走：DefaultMemory/WikiModelResolver · McpUsageInstructionsOps/Controller · SessionAgentQaService（保留自身 wrap）· ModelConnectivityTestService（chat×2/embedding/rerank）· ModelDebugController。顺带清死码（域侧 appSecret 解密与 provider 分支——解密收进工厂面；对 lkeap/volcengine 现在会给 appId，属修正已注明） | 15 守卫绿 · :domains:javadoc 0 错 · 金片 1349 一致 · gate 2m21s / **4996 / 0 / 4** · 冒烟 200。（改写过程中曾误按截断读取覆盖两个 resolver 全文，已 git checkout 恢复后外科式改写） |
| R2c-1b | 2026-10-16 | **组装权下沉**：`ModelRuntimeAssembler`+`ModelRuntimeConfigs` → `modelruntime.application`（依赖换成契约端口 `ModelCatalogPort`，闸门错误文案仍在装配器拆包）；`LlmTransport`+`Wiring` → `platform.http`（共享裸 HTTP 传输，非模型语义）⇒ **模块内环清零**（2 组→0、间接环 1→0），domains 的 7 个消费点不再 import model-runtime | 15 守卫绿 · 金片 1349 一致 · gate 2m12s / **4996 / 0 / 4**（platform 195→205、model-runtime 239→229）· 起服 200 |
| R2c-1a | 2026-10-16 | 模型运行时端口契约化：`chatpipeline.spi.ModelRuntimePort` → `contract.model.ModelRuntime`（§8.1 目标名）；`VlmClient.VlmConfig` 抽为 `contract.model.VlmConfig`（纯记录 + 两个派生方法）；全仓类型名同步。**发现**：方案 §8.1 的 `ModelAccessResolver` 已被既有 `ModelCatalogPort` 覆盖，无需新建 | 15 守卫绿 · 金片 1349 一致 · gate 2m11s / 4996 / 0 / 4 · 冒烟 200 |
| R4b-1 | 2026-10-16 | **管线 SPI 契约化**：`McpToolingGateway`/`McpToolRegistrationPort`/`ToolRegistryPort`/`WebSearchExecutor` `chatpipeline.spi` → `contract.pipeline`（四者的实现散在 mcp/knowledge/evaluation/agenttools 等域，消费方是管线本体 ⇒ 放实现包里会把整条管线拖成跨域依赖）；`KnowledgeQaEventPort` 未随迁（签名取内部件 `ChatManage`）。契约纯度：两处 `JsonNode` 参数改**不透明 JSON**（`Object`），实现侧还原。**A20 逮到真问题**：`ToolRegistryPort` 的实现原为 `ToolRegistry` 里的包内匿名类（守卫只认顶层类 ⇒ 报"契约悬空"）⇒ 抽成同包顶层 `ToolRegistryPortImpl` + 补包内访问器 | 15 守卫绿 · 金片 1349 一致 · gate 1m51s / **5000 / 0 / 4** · 冒烟 200 |
| R4b-2a | 2026-10-16 | **管线端口束契约化**：`chatpipeline.PipelinePorts`（201 行，嵌套端口束 + 三对齐 record）→ `contract.pipeline`——session 之外的 4 个 api 适配器（knowledge/memory/websearch/datasource）只需这一束 ⇒ 契约化后不再依赖管线本体（本体归位的前置）；`DataAnalysisSession.execute(JsonNode)` → 不透明 JSON；顺带清 4 处"仅注释提及"的无效 import | 15 守卫绿 · 金片 1349 一致 · gate 2m14s / **5000 / 0 / 4** · 冒烟 200 |
| R4b-2b | 2026-10-16 | **webfetch 归位 websearch 域 + 抓取面契约化**：`FetchException` → `contract.websearch`（可机读码是跨域失败词汇）；`Fetcher`/`AgentMarkdown`/`BrowserRenderer` + 测试 → `domains/websearch/fetch`；抓取面拆**两个单方法端口**（各恰一顶层实现，合 A20「唯一实现」）：`PipelinePageFetcher`（15s/100KB）/`ToolPageFetcher`（markdown/2MB/60s/浏览器兜底）；消费点 `PluginWebFetch`（QaWiring 注入）· `WebFetchTool`/`WebSearchTool`（AgentToolBackends 注入）⇒ **agenttools→websearch / session→websearch 两条新域间边均未产生**（R10 仍 6 条） | 15 守卫绿 · 金片 1349 一致 · gate 1m57s / **5000 / 0 / 4**（首轮 1 例 Redis 族失败，隔离 5/0 绿）· 冒烟 200 |
| R4b-2c-1 | 2026-10-16 | **评估侧去管线本体依赖**（本体归位前置）：契约三件 `KnowledgeQaRunRequest`（评估路径实际赋值的字段子集 + 内嵌 `SummarySpec`）/ `KnowledgeQaRunResult`（检索/重排/生成三产物，均为契约形状）/`KnowledgeQaRunner`；会话域实现 `KnowledgeQaRunnerImpl`（顶层，A20 唯一实现）做 请求→`ChatManage` 还原→跑 rag 预设→回带产物；`EvaluationService` 的 `buildChatManage` 换成 `toRunRequest` ⇒ 对管线本体 import 清零 | 15 守卫绿 · 金片 1349 一致 · gate 2m13s / **5000 / 0 / 4** · 冒烟 200 |
| R4b-2c-2 + R6 | 2026-10-16 | **管线本体归位 + `:engine` 解散**：chatpipeline 本体 43 件（`ChatManage`/`EventManager`/`PipelineBuilder`/`PipelineLog`/`PipelineProgress`/`SummaryConfig`/`spi.KnowledgeQaEventPort` + plugin 24 + support 7）→ `domains/session/service/rag`（包名整体换）；测试 9 + 夹具 2 随迁；`:engine` 模块解散（settings/jacoco/`_source_roots`/stray-dirs/test-summary/dev-stale-check/Dockerfile/依赖一并摘除）；补偿：domains/boot 显式声明 `:model-runtime`（domains 那条是过渡边）、engine 的 wire 录制夹具迁入 `domains/src/testFixtures/resources/wire`；**实物损失已修复**：`rm -rf engine` 连带删掉 `engine/src/main/resources/initialization/extract_config.yaml`（致 1249 例失败）⇒ 按"资源随读者走"恢复到 `model-runtime/src/main/resources/initialization/`；换锚：R9 包级 SCC 组名 · json-key 前缀/BASELINE · B93b EVENT_MAP_FILES · 大文件基线 · A17 两个管线插件 | 15 守卫绿（环 0 · R10 仍 6 条）· 金片 1349 一致 · gate 1m35s / **5000 / 0 / 4** · 冒烟 200 · **六模块** |
| R6-1 | 2026-10-16 | **测试期检索依赖收口**（domains 摘掉 `:retrieval-engine`）：四处错位测试归位——`ArtifactReferenceRewriterTest` → retrieval-engine（随迁录制夹具 `contracts/w5g3c-artifacts.tsv`，资源随读者走）· `HybridResultOpsContextExpansionTest` → retrieval-engine（`TenantService` → 契约 `TenantConfigLookup`）· `WeaviateGqlTest` → retrieval-engine · `EffectiveEnginesTest` → **boot**（断言"租户实体默认值 = 引擎 defaults"，属跨模块一致性）· `ClasspathResourcesTest` → **boot**（核对全模块 classpath 资源）；四处 mock 实现类 → mock 契约端口（`HybridSearchService`→`HybridSearchPort` ×3 · `VectorStoreService`→`VectorIndexStore`；故障改抛契约 `RetrieveEngineException(Kind.VECTOR_STORE_UNAVAILABLE,…)`）。**domains 对检索模块主码与测试皆零依赖** | 15 守卫绿 · 金片 1349 一致 · gate 1m56s / **5000 / 0 / 4** · 冒烟 200 |
| R6-2 | 2026-10-16 | **模块图守卫（第 16 个）+ 夹具包改名**：`scripts/check-module-graph.py` 四条判据——A 主码依赖矩阵（16 条实测边全在方案矩阵内；`boot → agent-engine` 声明但为 0）· B 主码不得跨模块同包（0）· C 测试期边 ⊆ 矩阵 ∪ 登记表（两条登记附理由）· D 夹具不得占用别模块主码包（根包 `com.ragagent` 豁免）+ D2 同一 FQN 不得跨模块（0）。**为什么不用 ArchUnit**：它看类/包看不到 Gradle 模块边界。夹具包改名：`com.ragagent.agenttools`/`com.ragagent.support` → **`com.ragagent.agent.fixtures`**（72 文件机械改名；原先与 domains 主码同名 = split package 隐患） | **16 守卫全绿** · 金片 1349 一致 · gate 1m55s / **5000 / 0 / 4** · 冒烟 200 |
| R6-3 | 2026-10-17 | **测试归位（O12）**：contract 2、modelcontext 3、platform 2 共 7 个测试迁到目标模块；`ParserEngineRulesTest` 需 platform 的 `JsonMappers` ⇒ 落 platform（platform test → contract 合法）。剩余 10 个跨模块测试（platform 5 / model-runtime 4 / contract 1）依赖 domains 载荷，迁走会形成反向边 ⇒ 登记 `MISPLACED_TEST_ALLOW`（附理由，只许减；新增/失效均红）。夹具包 `com.ragagent.common` / `com.ragagent.support` → **`com.ragagent.fixtures`**（18 处 import 同步）。模块图守卫加判据 E + 幽灵夹具包硬零。 | **19 守卫全绿** · **4867 / 0 / 4**（条数守恒：domains 2746→2699、platform 219→225、model-runtime 241→271、contract 0→11）· build 1m55s |
| R6-4 | 2026-10-17 | **重复类与命名尾巴（O13）**：`LogSanitizer` 只保留 platform 版（删 domains 副本，测试改 `sanitize`）；`modelcontext.JsonValues/HtmlEntities` → `ModelJsonValues/HtmlCodec`；websearch `ZhipuProvider` → `ZhipuWebSearchProvider`；`WikiPageServiceImpl` → `DefaultWikiPageService`（file-size 基线同步）；9 个锁字段 `mu` → `lock`；`PluginError.err` 私有 + `cause()`；新增 `check-duplicate-classes.py` 硬零（2039 个简单类名唯一）。 | **20 守卫全绿** · **4867 / 0 / 4** · build 2m07s |
| R6-5 | 2026-10-17 | **ChatManageFactory（O14）**：40+ setter 收成 create / applyRetrieval / applyModel / applyGeneration / applyWeb / summaryConfig；`ChatManage` 保持可变（录制等价性测试依赖），服务层装配从 40 行降到 6 行。 | **20 守卫** · **4867 / 0 / 4** · build 2m12s |
| R6-6 | 2026-10-17 | **Go 录制外置（O15）**：7 个 `GoRecording*` 的 JSON 外置到 `platform/src/testFixtures/resources/golden/`，`GoldenResources` 按 SHA-256 加载；Java 4448→1294 行，资源 956KB；tamper 探针（改一个字节）测试红；类名行为化留 O16。 | **20 守卫** · **4867 / 0 / 4** · build 2m06s |
| R6-7 | 2026-10-17 | **迁移代号清理（O16）**：7 个 `GoRecording*` 改为行为名（`AgentPromptGolden` / `AgentExecutionGolden` / `ToolContractGoldenA/B/C` / `ChatPipelineGolden` / `ModelContextGolden`）；主码 168 文件注释中 195 处批次号/日期戳/§ 引用清零；新增 `check-internal-codes.py` 硬零（C1/C2）；`packages.md` 重生成。 | **21 守卫** · **4867 / 0 / 4** · build 2m34s |
| R6-8 | 2026-10-17 | **模块/包映射（O17）**：新增 `scripts/gen-module-map.py` 生成 `docs/architecture/module-package-map.md`（7 模块包根/包数/文件/控制器 + 22 域文件/控制器）；`--check` 接入第 22 条守卫（改包/加控制器不更新文档即红）；docs/README 与 overview 加链接。 | **22 守卫** · **4867 / 0 / 4** |
| R6-9 | 2026-10-17 | **版本升级评估（O18）**：临时 worktree 实测 Boot **3.5.16** / springdoc **2.8.17** / MyBatis-Plus **3.5.17**；主码/测试码编译通过，测试 **4867 / 4 fail / 4 skip**，4 fail 均为 Jackson 2.19 非法 JSON 列号 4→1；列出 5 类必需改动（jsqlparser 拆分、JUnit launcher、Jackson fields()、Spring 方法校验 API、4 条契约断言）；**未改主工作区版本**。 | 评估完成；建议按 O18a 落地 |
| R6-10 | 2026-10-17 | **O18a 落地**：Boot **3.5.16** / springdoc **2.8.17** / MyBatis-Plus **3.5.17**；`mybatis-plus-jsqlparser` 独立 artifact、test-conventions 显式 JUnit launcher、Jackson `properties()`、Spring 6.2 `getParameterValidationResults()`、4 条 Jackson 列号 4→1 契约断言；build 3m07、**4867 / 0 / 4**、**22 守卫**；Swagger 冒烟 OpenAPI 3.1.0（disabled 404 / enabled 200+302）。 | 升级完成；`@MockBean` 迁移留独立提交 |
| R6-11 | 2026-10-17 | **CI smoke（O19）**：新增 `check-version-consistency.py`（Boot/springdoc/MyBatis-Plus jsqlparser/README/wrapper/Dockerfile，第 23 条守卫）与 `scripts/ci-smoke.sh`（真实 boot jar：/health 200、Swagger 默认 404、进程可停）；CI backend 增加启动冒烟，guards 标签改为单一入口。 | **23 守卫** · **4867 / 0 / 4** · smoke 本地通过 |
| R6-12 | 2026-10-17 | **Scalar UI 替换 Swagger UI**：springdoc 改 API-only starter（移除 swagger-ui webjar）；新增 `ScalarUi` 条件 bean + `scalar/index.html`（CDN `@scalar/api-reference`，data-url=/v3/api-docs）；AuthFilter 白名单改 `/scalar`；默认关闭时 `/scalar` 与 `/v3/api-docs` 均 404。 | **23 守卫** · **4867 / 0 / 4** · build 2m12 · 冒烟 /scalar 200（enabled）/404（disabled） |
| R6-13 | 2026-10-17 | **Boot 4 评估**：目标 Boot 4.1.1 / Spring 7 / Jackson 3 / Jakarta EE 11 / JUnit 6；生态就绪（springdoc 3.1.1、MyBatis-Plus Boot4 3.5.17），但主码 **651 文件 / 1329 处 databind import** 需迁 `tools.jackson`（annotations 179 处不变）；另需 `javax.inject` 6 处、Jackson2 customizer 1 处。**3.5 OSS 已于 2026-06-30 结束**（3.5.16 为最后 OSS 版本）；无商业支持则 Boot 4.1 迁移应近期排期。 | 评估完成；独立分支迁移 |
| R6-14 | 2026-10-17 | **v0.4.1 Boot 4 空跑**：Boot 4.1.1 / springdoc 3.1.1 / MyBatis-Plus Boot4；starter-aop→aspectjweaver、Tomcat 包迁移、AutoConfigureMockMvc→spring-boot-webmvc-test、RequestBuilder 签名；Jackson 2 兼容桥（spring-boot-jackson2 ObjectMapper、JsonNode ser/deser、ContractJsonMixins + Jackson3 模块）；Jackson 3 默认行为对齐；wiki 空列表契约更新。 | **23 守卫** · **4867 / 0 / 4** · build 2m11 · Scalar enabled 200 |
| R6-15 | 2026-10-17 | **去 spring-boot-jackson2**：删除 Boot 4 的 Jackson 2 自动配置依赖，WebConfig 显式提供 `JsonMappers.lenient()` 的 ObjectMapper bean；业务仍用 Jackson 2，Jackson 3 迁移完成后再删该 bean。 | **23 守卫** · **4867 / 0 / 4** · build 2m10s |
| R6-16 | 2026-10-17 | **v0.4.2 Jackson 3 全量迁移**：666 个 Java 文件包名/API 切到 `tools.jackson`，移除 Jackson 2 依赖、`spring-boot-jackson2` 与过渡桥；MyBatis `PgJsonTypeHandler` 改继承 `AbstractJsonTypeHandler` + `JsonMappers.lenient()`；补齐 Jackson 3 的日期/排序/空值行为；错误消息（byte offset）与 wiki 空列表契约按实际线格式更新。 | **23 守卫** · **4867 / 0 / 0** · build 1m52 · Scalar enabled 200 |
| R6-17 | 2026-11-02 | **v0.10.x 回退到 Boot 3.3.13 + Jackson 2.17.3**（方案 `plans/jdk21-boot33-jackson217-migration-design.md`）：743 文件包名/API 回切（`tools.jackson.*` → `com.fasterxml.jackson.*`，`ValueSerializer`→`JsonSerializer`、`SerializationContext`→`SerializerProvider`、`JacksonException`→`JsonProcessingException`、`stringValue/asString/propertyNames/isString`→`textValue/asText/fieldNames/isTextual`）；根 pom 切 Boot 3.3.13 + springdoc 2.8.17 + MyBatis-Plus **Boot3** starter + Jakarta Servlet 6.0；Boot 3.3 BOM 不管 protobuf/grpc ⇒ `protobuf-java`/`grpc-protobuf`/`grpc-stub` 显式定版（protoc 与运行时同版 3.25.5）；Flyway 改 `flyway-core` + `flyway-database-postgresql`；`WebConfig` 换回 `Jackson2ObjectMapperBuilderCustomizer` 并删除 Jackson 3 过渡桥两件；`@AutoConfigureMockMvc` 与 `@MockBean` 回 Boot 3 包；**record 键序坑**：Jackson 2 会把带 `@JsonInclude` 的分量提前、且分量上的 `Include.CUSTOM` 过滤器不生效 ⇒ 5 个载荷显式 `@JsonPropertyOrder` + 过滤器移到访问器。 | **48 守卫** · **5150 / 0 / 2**（与迁移前逐项守恒）· build 4m07→4m20 · 冒烟 /health 200 + /v3/api-docs 200 + 401/400 契约 · **4 条金片变更已确认（2026-11-03）** |
| G1 | 2026-11-03 | **Go 移植欠债首批**（方案 `plans/go-debt-and-generic-return-design.md`）：接口返回类型具体化（skill 4 端点 + agents 列表）；错误前缀拼接 593 处中只修 38 处「抛异常但丢 cause」（439 处已保留 cause、119 处是错误字符串无处放）；`HeuristicSplitter` 的 Go `append` 改 `void`；`LegacySplitter` 的 `int[]`+`boolean[]` 改内部类字段；`boolean[]` 闭包出参改 `AtomicBoolean`；**修 `check-contract-shape.py` 的方法计数**（嵌套类型成员不再计入端口预算，附红态探针）。**三处判断撤回**：GitLab 空结果 null 契约是有意设计且有测试、G7 注释「逐字节」多数可核对、G1 另 35 处属「同方法多形状」需先做 G2。 | **5150 / 0 / 2** · 48 守卫 · 金片零变化 |
| R5c-1 | 2026-10-16 | **引擎配置读面契约化**：`contract.agent.AgentRunSpec`（16 getter + 2 派生 default + `UNLIMITED_MAX_ITERATIONS`）。为什么是接口不是 record：域侧配置要**从 agent 配置 jsonb 反序列化填充**（需 Jackson 注解，契约零依赖不能带），而引擎**只读**（实测无 `config.setXxx`）⇒ 契约收读面，两侧各自持有可变实现。`AgentConfig implements` 之（注解/setter 原样）；`AgentEngine` 的字段与构造参数改读面类型；`QaAgentConfig` **停止继承**、自持 16 字段存取（getter 名不变 ⇒ 会话侧零改动） | 15 守卫绿 · 金片 1349 一致 · gate 2m45s / **5000 / 0 / 4** · 冒烟 200 |
| R5c-2/3 | 2026-10-16 | **装配权下沉 + 过渡边摘除（R5 收官）**：契约三件 `AgentEngineFactory`/`AgentEngineRequest`（16 字段）/`AgentEngineHandle`（域侧真用 4 方法）；agent-engine 新增 `AgentEngineFactoryImpl`（注册表/内置工具/MCP/技能装载/模板/pinned/引擎构造/图片描述器）与 `AgentEngineHandleAdapter`（唯一适配器）；domains 的装配器改为"`planTools`（允许清单+wiki 上下文，私有记录）→ `buildTools`（外部工具列表）→ `wireWebPageFiles`（列表版就地接线）→ 请求 + 工厂"；**A18 语义原样保留**：新增 `AgentExecutionException`，适配器包裹引擎异常（message 逐字）⇒ 两级 catch 逐条不变（A18a 登记适配器并写明理由）；三个错位测试随类迁回 agent-engine；**domains 摘掉主码过渡边**（仅留 fixtures 面） | **验收：domains 主码对 agent-engine import 55 → 0 处/0 文件** · 15 守卫绿（SCC 9 组不变 · A20 两端口各一实现）· 金片 1349 一致 · gate 2m11s / **5000 / 0 / 4**（全量重跑 5004，差 4 例=随迁测试方法数 1+1+2）· 冒烟 200 |
| R5b-1 | 2026-10-16 | **agent 纯形状/纯工具上提 `contract.agent`**（12 件，零同包耦合）：`SearchAuth`（456：检索范围授权 + 4 view + 4 fetch 口）· `ThinkBlocks` · `ToolResultPersist` · 7 个 prompt 形状 · `CompactionSettings`/`CompactionOverflow`。试搬时被编译器逮到 2 件不纯并退回（`DocChunkSupport` 用 `platform.web.JsonMappers`；`Skill` 依赖包私有 + snakeyaml 的 `SkillFrontmatter`）⇒ 留后续片。**踩坑三例**：① **前缀替换误伤**（`Skill` 前缀连带命中 `SkillReadFileTool`）⇒ 修回 + 写"引用真实性校验"脚本复核 5 包 133 名 ② spotless 自报可修 ⇒ 用 `spotlessApply` + 检查 diff 只含 import 行 ③ 两处 `{@link}` 迁移后不可见 ⇒ 改 `{@code}`。**既有边界守卫 B0 按设计报红**（80→68 件 < 70 阈值）⇒ 阈值同步 60 | 15 守卫绿（R9 SCC 9 组不变）· 金片 1349 一致 · gate 1m57s / **5000 / 0 / 4** · 冒烟 200；domains 对 agent-engine **55 → 16 文件** |
| R5b-2 | 2026-10-16 | **预算/转向/清理面 + ImageDescriberFunc**：4 件纯件 → `contract.agent`（`SteerSink` 47 · `AgentBudgets` 46 · `OutputBudgets` 60 · `Cleanable` 11）；`AgentEngine.ImageDescriberFunc` 嵌套接口**抽出**为契约类型（`VlmDescriberWiring` 对 AgentEngine 的 import 随之清零）。`skills.Manager`/`DbSkillSource` 因**状态化**（锁+缓存）与依赖目录服务**未搬** | 15 守卫绿 · gate 2m20s / **5000 / 0 / 4** · 冒烟 200；消费面 **46 → 40 处 / 16 → 11 文件** |
| R5b-3 | 2026-10-16 | **`DocChunkSupport` → `platform.knowledge`**（288 行；与 `ImageInfoMatchUtil`/`ChunkSearchUtil` 同族：对契约形状做技术性组装）。两个嵌套 record 改名过 C8 红旗：`ImageInfoView`→**`ImageInfoRow`**、`KnowledgeInfoView`→**`KnowledgeInfoRow`**（改名不改职责，同 R3d-5 的 `GrepChunkView`→`GrepChunkHit`）。手法复盘：**点号形式的嵌套引用**（`DocChunkSupport.ImageInfoRow`）需单独一遍替换（我的负向后顾刻意避开点号 ⇒ 漏了嵌套类型导入，编译器立刻报 4 处） | 15 守卫绿（C8 通过）· gate 2m20s / **5000 / 0 / 4** · 冒烟 200；消费面 **40 → 18 处 / 11 → 9 文件** |
| R5b-4 | 2026-10-16 | **技能目录端口化**：新增 `contract.agent.SkillCatalogPort`（7 方法：listVisible/findVisibleById/create/update/softDelete/listReferences + `skillInstructions`【原域侧 `Skill.parseSkillFile(content).instructions`】）；随迁既有类型（按块抽取、语义逐字保留）：`SkillRow`（含 `platform()`）· `SkillReference` · 四异常 · `SkillValidationException` + `SKILL_FILE_NAME` 常量。`SkillCatalogService implements` 之；消费点三处 + 装配器收端口。**踩坑两例**：① 嵌套类型的 **import 形式** 被替换带偏 ⇒ 改 FQN + 8 文件补外层 import ② 控制器 `skillBody` 原 static 却要用实例字段 ⇒ 改实例方法 | 15 守卫绿（A20 唯一实现）· gate 2m31s / **5000 / 0 / 4** · 冒烟 200（含 skills/catalog）；消费面 **18 → 13 处 / 9 → 3 文件** |
| R5a | 2026-10-16 | **切出 `:agent-engine`**：`com.ragagent.agent` 整树（main 80 / test 33）domains → agent-engine，包名不变；出边实测极干净（contract 139 / platform 51 / modelcontext 3，**零业务域**）⇒ 依赖按实测声明（contract/platform api + `:model-runtime`（modelcontext）+ Jackson/jtokkit/MyBatis-Plus），启用 `java-test-fixtures`；录制夹具 4 件随迁（只依赖 platform）⇒ domains 以 `testFixtures(:agent-engine)` 消费（同向不成环）；七处模块清单同步；domains 显式 `implementation(:agent-engine)`（**过渡边**，55 消费文件待 R5b/R5c）；**R10 域口径同步**（agent 摘出 DOMAINS + 族收敛）⇒ 6 条边 → **3 条**（owner 1 + 族内 2）；**既有边界守卫 `AgentEngineBoundaryTest` 随迁更新**（扫描根 → 新模块；白名单摘 `agenttools`（业务域、实测 0 引用 ⇒ 收紧）与已归位的 `chatpipeline`/`webfetch`；下限 55→70（实测 80）；管理面路径改仓库相对）；大文件基线纯换锚 | 15 守卫全绿 · 金片 1349 一致 · gate 2m / **5000 / 0 / 4** · 冒烟 200（**七模块**）|
| R2c-3d-① | 2026-10-16 | **Ollama 管理面端口化**：契约面 `OllamaModelInfo`（record）上提 + 新 `OllamaPullProgress`/`OllamaProgressListener`/`OllamaAdminPort`（六方法=域侧真调用面）；端口方法名 `pullModel`（与实现侧内部 `pullWithProgress(String, ProgressCallback)` 区分——同名同元数下两个函数式接口会让 λ 歧义，实测踩到）；进度回调**不再吃原始 JsonNode**（域侧原先自己 `path("total")`）；**`InitializationWiring` 删除**——它挂在业务域的两个 @Bean（`OllamaService`/`ExtractPrompts`）都是模型运行时的件 ⇒ 迁 `boot/config/ModelRuntimeBeansWiring`；`OllamaService` 619 行（+15 行端口桥接）超 600 阈值 ⇒ 按守卫指引在 `check-file-size.py` 头写明**内聚论证**后登记；顺带清 3 条陈旧 json-key 登记 | 15 守卫绿 · 金片 1349 一致 · gate 2m4s / **5000 / 0 / 4** · 冒烟 200 |
| R2c-3d-②③ | 2026-10-16 | **提示词/配置面归 platform.prompt**：`ExtractPrompts`（config.yaml 的 extract 段装载）与 `PipelineConfig`（管线配置）`llm/extract` → `platform.prompt`（消费方横跨 knowledge/initialization/session 三域，platform 是唯一都够得着又不该进契约的落点；`extract_config.yaml` 随读者迁）；`PromptCache` 的**纯函数族**（`fingerprintPromptPrefix`/`promptPrefixFingerprint`/`buildPromptCacheKey`）拆出为 `platform.prompt.PromptFingerprints`——整件迁不了（另一半方法吃 `llm.provider.ProviderName`），故拆纯函数、`PromptCache` 三方法改薄委托（内部零改动）、wiki 侧 5 处调用改走 platform；`ClasspathResourcesTest` 读者标签更新；json-key 换锚（提取器 5 键=存量 yaml 配置键）+ 冻结快照 | 15 守卫绿 · 金片 1349 一致 · gate 2m15s / **5000 / 0 / 4** · 冒烟 200 |
| R2c-3d-④ | 2026-10-16 | **实体关系抽取端口化**（domains 对模型族过渡边清零）：契约面 `contract.graph.EntityExtractionResult`（只读 record，替代域侧直读实现内部类的可变字段）+ `contract.knowledge.EntityExtractionPort`（模板按**不透明配置载体**传入）；model-runtime 新 `EntityExtractionAdapter`（A20 唯一实现，内部形状不出模块、实现零改动）；三消费点改走端口（`ChunkExtractService` 两构造对齐 · `TextExtractionTestService` 的过滤改对列表就地过滤 · `PluginExtractEntity` + `QaWiring` 注册行）；`ChunkExtractServiceTest` 注入真实适配器（假模型驱动真解析，行为不变）；**R9 包级 SCC 再次变形**（契约词汇组加 contract.knowledge，已在守卫脚本头登记理由） | 15 守卫绿 · 金片 1349 一致 · gate 2m1s / **5000 / 0 / 4** · 冒烟 200 |
| R4a | 2026-10-16 | **tracing 归位**：6 个 Langfuse 装饰器 → `platform.tracing.decorators` + 3 个契约流句柄（QueuedChatStream/SyncChatStream/StreamLifecycle）→ `platform.stream`（装饰器要建/转发流 ⇒ 句柄须在更低层）；错位测试随迁；engine 的 tracing 目录清空。**本批是 R2c 的解阻塞项**（装饰器已只依赖契约面）。顺带换锚 ArchUnit A16 禁用前缀（不改则 service 违规静默失明） | 15 守卫绿 · json-key 换锚（5 出 5 入）· 金片 1349 一致 · gate 2m4s / **4996 / 失败 0 / 跳过 4**（platform 188→195、engine 322→315）· 冒烟 200 |
| R2b | 2026-10-16 | **`:model-runtime` 模块切出**：五包（llm/modelruntime/embedding/rerank/modelcontext）148 件主源码 + 19 件测试 `git mv`；模块清单四处同步（settings · `_source_roots.py`（守卫单一来源）· 根 jacoco 聚合 · stray-dirs/test-summary/dev-stale-check）；engine 过渡期 `api(:model-runtime)`；**Dockerfile 顺带校正**——原写已裁撤的 `common/` 且漏拷 contract/platform，`docker build` 必失败 | 15 守卫绿（R9 SCC 9 组不变 · 域间契约 6 条不变）· 大文件守卫按路径换锚 4 条（另回退 8 处无关数值刷新）· 金片 1349 一致 · gate 2m9s / **4996 / 失败 0 / 跳过 4**（model-runtime 实跑 239，engine 561→322 ⇒ 搬迁测试真跑）· 起服冒烟 200 |
| R2a-2 | 2026-10-16 | 模型面端口与结果形状上提：`embedding.Embedder`（9）/`rerank.Reranker`（8）/`rerank.RankResult`（3）→ `contract.model`；**RankResult 拆分**——纯形状进契约面，parse/marshal 移实现侧新件 `rerank.RankResultCodec`；@JsonProperty 对位进 `ContractJsonMixinsModel`（含内嵌 DocumentInfo）；同包裸引用 7 处补 import | 15 守卫绿 · 金片 1349 一致 · json-key 换锚（离开 1 / 进入 2）· gate 1m55s / **4996 / 失败 0 / 跳过 4** · 冒烟 200 |
| R2a-1 | 2026-10-16 | 模型运行时而契约化：`llm.LlmChatClient`（35 域内消费）/`llm.ChatStream`（3）/`llm.domain.StreamResponse`（8，SSE 帧核心）→ `contract.model`；StreamResponse 的 7 处 `@JsonInclude(NON_EMPTY)` 对位进 `ContractJsonMixinsModel` | 15 守卫绿 · 金片 1349 一致 · gate 2m12s / **4996 / 失败 0 / 跳过 4** · 冒烟 200。**R9 包级 SCC 8→9**（父子包内聚组 {contract.model, contract.model.chat}，理由已登记守卫脚本头；同类先例 3 组） |
| R1 收官 | 2026-10-16 | **R1 余项判定**（勘察取证 + 用户拍板）：① 检索公开面 → **并入 R3**（域侧直注实现类 `CompositeRetrieveEngine`12/`HybridSearchService`10/`VectorStoreService`9；索引面签名穿透 `Embedder`{模型族}与可变 setter `IndexInfo`；方案点名的 SearchHit/IndexService/SearchPolicy 全仓无对应物、域侧无消费者）② `contract.agent` 6 件 → **并入 R5**（跨域消费者仅 `boot/.../EngineRecordingTest`；AgentEngine 唯一生产调用方 `SessionAgentQaService` 在同模块；六件全仓无对应物）③ `contract.execution` → **判"不建"**（`contract.tool.ToolCancellation`/`ToolExecContext` 已在，引擎另有 `Supplier<String> cancellationSource`；进度为事件流无 sink 需求） | 判定入 §2.9 + 计划 v2 R1 行；R5 新情报：`AgentEngine` 直连 `platform.tracing.langfuse` 与 `platform.access/event`（拆 agent 时 platform 边比 engine 边重） |
| R1b-1 | 2026-10-16 | 契约面先行第二件：`retrieval.domain` 整包 4 件（WebSearchResult/Filters/Request/ImageInfo）→ `contract.websearch`（§8.1 目标落点）；ImageInfo 注解对位进 `ContractJsonMixinsWebSearch`；修 `AgentReferencesData` 陈旧 javadoc | 15 守卫绿 · 金片 1349 一致 · json-key 换锚 `--write`（离开 1 / 进入 1）· gate 1m57s / **4996 / 失败 0 / 跳过 4** · 冒烟 200；首轮闸门 `ImRedisStoreTest` 单例失败 → 隔离重跑 7/0 绿（已知 Redis 瞬态族） |
| R1a | 2026-10-16 | 契约面先行第一件：`llm.domain` 11 件聊天形状 `git mv` → `contract.model.chat`（ChatMessage/ChatOptions/ChatResponse/ChatTool/FunctionCall/FunctionDef/ImageUrl/MessageContentPart/ToolCall/CacheRetention/ChatConfig）+ Jackson 注解逐条对位进 `platform.web.ContractJsonMixinsModelChat`（ContractJsonModule 注册）+ 4 处不透明 JSON 字段改 `Object`（format/parameters/providerMetadata）· **逐件过跨域消费者判据**（ImageUrl 为 MessageContentPart 公开形状件随迁；StreamResponse/ProviderMetadataMaps 留 R2 公开面分层） | 15 守卫全绿（金片 1349 一致 · R10 无新增边 · C8 无新增）· json-key 换锚 `--write`（离开 5 / 进入 1，均换包）· gate 2m15s / **4996 / 失败 0 / 跳过 4** · 定向 engine 456 + domains 266 · 冒烟 sessions/knowledge-bases/models 200（后端已重启） |

| L1a | 2026-10-18 | **迁移叙事守卫上线（v0.5.0 起点）**：新增 `scripts/check-comment-narrative.py`（只扫注释行；拦退役/备案/Go 锚点/迁移期/旧实现五类；基线 196 处 / 147 文件，棘轮只许减）；红态探针（`BaseTool` 加一行叙事 → 红 → 恢复绿）；接入 `run-guards.sh`（**24 守卫**）；新增 `docs/internal/plans/readability-remediation-v0.5.0.md`（L1~L22）并登记 §5.0。 | **24 守卫全绿**（纯脚本/文档，全量闸门并入 L1b 验证） |
| L1b | 2026-10-18 | **迁移叙事清理第一批（mcp / vectorstore / wiki）**：23 处 / 12 文件——历史说明删除、当前约束改成现在时、业务语义换中性词（连接"退役"→"关闭"、工具"退役"→"从快照移除"）；顺带修正 `WikiPageController` 引用的已不存在包 `com.ragagent.org`；基线 196 → 173。 | **24 守卫** · **5022 / 0 / 4** · spotlessCheck build 1m58s |

| L1c | 2026-10-18 | **迁移叙事清零收尾**：137 文件 / 159 处——备案→实现边界、退役→移除/关闭、旧版→早期版本或回落路径、迁移期与 Go 锚点删除；修 `KnowledgeBaseService` 的 `\uXXXX` 转义乱码段与 14 处替换后语句不通。**守卫升级为硬零**（基线 `{}`），红态探针复验（加一行叙事即红）。 | **24 守卫** · **5022 / 0 / 4** · spotlessCheck build 2m29s |

| L2 | 2026-10-18 | **行号引用与 Go 语法锚点清零**：16 处注释把 `L141-144`/`L100/L129`/`L2302` 改为类名/方法名指路，删 `uuid.New()`/`Order(...)`；8 个构建脚本的批次号/日期戳/§ 引用清零，`platform` 与 `contract` 头部注释按现状重写。守卫扩面：`check-go-anchors` 增加行号与 Go 语法模式（只扫注释行），`check-internal-codes` 增加 C3 覆盖 `*.kts`；两条各验红态探针。 | **24 守卫** · **5022 / 0 / 4** · spotlessCheck build 2m22s |

| L3+L4 | 2026-10-18 | **文档失真修正 + package-info 补齐**：README 统一外壳补「基础设施端点除外」、删除 `frontend/pnpm-workspace.yaml`（npm 残留）；`Session` 的「裸对象」改为 `ApiResultAdvice` 包壳、`SessionKnowledgeQaService` 删「hybridSearch 空实现」、`RagAgentApplication` 的 `common/*` 改 `platform` 配置包。**生成 156 个 package-info**（职责＝包内主要类型、依赖＝跨模块 import、入口＝Controller/Service/Repository），覆盖率 48%→100%（322 包）；`packages.md` 与 `module-package-map.md` 重生成；新增 `check-doc-truth.py`（D1 模块数/D2 控制器数/D3 package-info 覆盖），**第 25 条守卫**。 | **25 守卫** · **5022 / 0 / 4** · build 2m10s |
| L5 | 2026-10-18 | **注释装饰清理**：删 491 行纯装饰分隔线（`// ═══`/`// ===`/`// ---`），216 行带文字分隔线统一为 `// ── 段名 ──`；新增 `check-comment-decor.py`（**第 26 条守卫**，红态探针已验）。**不设注释密度硬指标**——密度会奖励删信息，本仓注释承载契约与线程纪律（实测 18.6%，最大类头 63 行，分布健康）。 | **26 守卫** · **5022 / 0 / 4** · build 3m04s |

| L6~L8 | 2026-10-18 | **缺口登记 + 死代码 + 日志分级**：新增 `docs/known-gaps.md`（18 项，状态/影响/替代/涉及类）；9 处类注释加指针、6 处误报改措辞；新增 `check-known-gaps.py`（第 27 条）。删 `@Deprecated getDocumentRawContent` + 2 个未用 record + 空包 `platform/session`，全仓 `@Deprecated` 归零。日志：116 处 warn 降级（95 debug / 21 info），warn 893→777；新增 `check-log-level.py`（第 28 条，warn 不得用于 404/跳过）。 | **28 守卫** · **5022 / 0 / 4** · build 2m48s |

| L9 | 2026-10-18 | **内联纯转发的 500 包装**：12 处 `catch (RuntimeException) { throw toInternal(e); }` 删除（MessageController / SessionController / TemporaryDocumentController / VectorStoreController）；保留带业务语义的 catch（notFound 转换、上下文前缀、条件分支）。WebSearchProviderController 保持原样（5 条金片钉住其 500 文案）。 | **28 守卫** · **5022 / 0 / 4** · build 2m15s |
| L10a | 2026-10-18 | **删除两个纯字符串异常**：`PlainErrorException`（22 处）→ `BizException` 工厂、`GuardForbiddenException`（7 处）→ `BizException.forbidden`；删两个类 + `GlobalExceptionHandler` 两个 handler（-40 行）；金片 8 条（`Forbidden:` 前缀去掉）、测试断言 1 条、前端 4 处 `data.error.message`→`data.message`。 | **28 守卫** · **5022 / 0 / 4** · build 1m58s · 前端 type-check 0 |
| L10b | 2026-10-18 | **删除 Wiki 的 RawJsonError 转译补丁**：43 处 `new RawJsonError(...)` → `BizException` 工厂 / `AppError.ofHttpStatus`；删嵌套异常类与 `@ExceptionHandler`；`WikiRequestSupport` 三个返回类型改 `BizException`；测试断言 3 处。**补 `docs/api-response-convention.md`**（此前被 5 处代码引用但文件不存在）与 baseline 两条例外理由。 | **28 守卫** · **5022 / 0 / 4** · build 1m53s |

| L11 | 2026-10-18 | **错误响应语法糖清理 + 守卫评估**：`DataSourceController` 35 处 `return error(400, msg)` 改 `throw BizException.*`（原写法像"返回错误"实为抛异常），删私有 `error()`。守卫评估结论：规则二已覆盖 domains 全量；规则一（catch 内清空过滤）扩面会误报 4 处（坏 JSON 宽松读），**不扩**。 | **28 守卫** · **5022 / 0 / 4** · build 1m52s |

| L12 | 2026-10-18 | **控制器纯度守卫 + 数据访问定规**：`check-controller-purity.py`（第 29 条；controller 禁直连 Mapper 硬零 + `catch (RuntimeException)` 棘轮 139 只许减，红态探针已验）；`mechanisms.md` §9 记录 Mapper（76）/Repository（28）的选择规则。**实测 controller 直连 Mapper 为 0**（此前统计的 22 处是 `ObjectMapper` 误报）。 | **29 守卫** · **5022 / 0 / 4** |
| L14 | 2026-10-18 | **事务模板收敛**：`DataSourceTxTemplate` / `ChunkTxTemplate` 合并为 `platform.persistence.TxTemplate`（Runnable + Supplier 两法），两个 Repository 改注入，删两人类；`MemoryTxTemplate` 保留（`FOR UPDATE` 行锁语义不同）；`KnowledgeProcessWorker` 补「为什么整链不包大事务」的边界说明。 | **29 守卫** · **5022 / 0 / 4** · build 2m18s |

| L16 | 2026-10-18 | **`executeQA` 拆分**：200 行 → 7 个具名步骤（memo 异步写 / 并发守卫 / 消息落库 / steer 交接 / 建流回滚 / 快答 handler / 异步执行），每步带"做什么 + 为什么"的 javadoc，顺序约束写进方法注释。纯重构，行为由 5022 条测试锁定。 | **30 守卫** · **5022 / 0 / 4** · build 1m48s |
| L17+L18 | 2026-10-18 | **控制流可见化**：新增 `gen-pipeline-map.py`——从 `activationEvents()` + `QaWiring` 注册序 + `ChatManage` getter/setter 生成 `docs/architecture/pipeline-map.md`（14 事件 / 17 插件 / 读写字段），接入第 30 条守卫（红态探针已验）；`Plugin` 接口补「读 / 写 / 失败」三条契约，plugin 目录 package-info 指向事件表。 | **30 守卫** · **5022 / 0 / 4** |

| L19 | 2026-10-18 | **拆出 `:knowledge`**：241 件主码 + 22 件测试（156 用例）迁出 domains；主码对 domains **0 出边**，测试期 4 处借 domains 存储/租户实现作夹具（单向）；新增模块构建（含 docreader proto）；守卫同步：模块图矩阵 + 测试边登记 + 域清单 + file-size 基线 + README 八模块/51 控制器；修一处跨模块 javadoc 引用。 | **30 守卫** · **5022 / 0 / 4**（domains 2543 + knowledge 156）· build 2m16s |
| L20~L22 | 2026-10-18 | **评估后不做**（三条硬约束）：L20 `modelcontext` 进 contract 会被 Jackson 破坏纯 JDK 红线；L21 platform 的 9 个业务工具被 6 个模块共享，移走会让 retrieval/agent-engine 反向依赖；L22 `BaseTool` 移 agent-engine 会让 domains 反向依赖（R5c 刚清零）。三者都是「移动即破红线」，不是成本问题。 | — |

| L23 | 2026-10-18 | **回退 `:knowledge` 合回 domains**（八模块 → 七模块）：拆分只带来看独立编译，知识库代码仍分散在 knowledge/retrieval/contract/platform 四处；11 个知识相关 Port 本就在跨域，收益小于「知识库到底在哪」的认知成本。244 主码 + 22 测试迁回，README/守卫/基线同步。 | **30 守卫** · **5022 / 0 / 4** · build 1m47s |
| L24 | 2026-10-18 | **Port 层治理**：① 删 `AuthTokenAdminPort`（auth 域内两级转发，直接内联到 `AdminUserAdapter`）；② 新增 `scripts/gen-port-map.py` 生成 39 个 Port 的「接口 → 实现 → 调用方 → 是否跨界」表（跨模块 8 / 域间 30 / 域内 1），接入**第 31 条守卫**（红态探针已验）。**两项取消**：`BaseTool` 移 agent-engine 会让 domains 反向依赖（R5c 回退）；`approval` 是纯 Port 实现域（无 HTTP/service），无需分层模板。 | **31 守卫** · **5022 / 0 / 4** · build 1m43s |

| L25 | 2026-10-18 | **分层解耦（规则 + 第一批）**：`check-domain-contracts` 的 `tier_of` 支持族定义推导（两端同族即豁免，**含新增边/新增类**）；`check-package-cycles` 族内双向环/间接环/包级 SCC 豁免；族映射改为**多族归属**（`agentmanagement` 同属 agent-runtime 与 identity）。删 4 个 memory 族内 Port（Extraction/Recall/Search/SessionMessage），`MemoryAccessAdapter` → `MemoryAccess`、`SessionMessageView` 提为独立 record。**identity 族 4 个 Port 未删**：`AdminUserPort` 内嵌 Page/CreateResult/常量，类型搬迁成本大于「-4 接口」收益。 | **31 守卫** · **5022 / 0 / 4** · build 1m41s |
| L26 | 2026-10-18 | **问答主链路一页纸**：新增 `docs/architecture/qa-flow.md`（Mermaid 时序图、4 入口判定、14 事件顺序、`executeQA` 七步表、改功能映射表、三条不变量、三个「去哪找」入口）；新增 `check-qa-flow.py`——文档里 `{@code Xxx}` 引用的类必须真实存在（红态探针已验），接入**第 32 条守卫**。 | **32 守卫** · **5022 / 0 / 4** · build 1m37s |

| L27 | 2026-10-18 | **identity 族内 4 个 Port 内联**（第二批解耦）：先把嵌套类型提为独立文件（`AdminUserPage` / `CreateUserResult` sealed / `AuthUserErrors` / `AuthUserView` / `ApiKeyAuthRequest` / `ApiKeyAuthResult` sealed），再删 `AdminUserPort` / `ApiKeyAdminPort` / `UserLookupPort` / `ApiKeyAuthPort`；4 个实现去 implements、6 处调用方改直接注入。**`contract` 的 `*Port` 39 → 31**；域间边族内 6 条 / 28 类。教训：删 Port 的真实成本在「接口里内嵌的 View/sealed/常量」，不在方法签名。 | **32 守卫** · **5022 / 0 / 4** · build 1m44s |

| L28 | 2026-10-18 | **知识族内 4 个 Port 内联**（第三批解耦）：划族 `knowledge + wiki`（双向依赖，族内允许）；删 `ChunkPort` / `WikiFinalizePort` / `WikiIngestPort` / `KnowledgeFinalizePort`；提取 `WikiEnqueueResult` / `FinalizeResult`；两处守卫修正——包级 SCC 域名提取兼容 `com.ragagent.X` 与 `contract.X`、R4「已解耦包对回流」认族。**包级 SCC 9 → 7 组**（wiki 内部环被消除）。**`contract` 的 `*Port` 39 → 27**（累计删 12 个）。 | **32 守卫** · **5022 / 0 / 4** · build 1m48s |

| L29 | 2026-10-18 | **链路 A 注释纠错（A 档）**：删 `SessionAgentQaService` 尾部 4 段空注释 + 一段错挂在 `chatModel()` 上的 javadoc；`AgentStreamBridge` 的「应为 17」改为不写死；`EventManager.buildHandler` 补「先注册 → 外层 → 先执行」；`KnowledgeProcessWorker` 补进程内队列语义；`mechanisms.md` §10「可选 Bean 缺失行为一览」。 | **32 守卫** · **5022 / 0 / 4** |
| L30 | 2026-10-18 | **`session/service/rag` 提升为 `session/rag`**：实测 rag 44 文件与非 rag service 44 文件 **import = 0**，两个不相干子系统挤在同一包；迁移同步 6 处守卫登记；**ArchUnit A17 规则扩面**（`isServicePackage` 纳入 `rag`，否则换包即静默脱管）。`session/service` 88 → 44 文件。 | **32 守卫** · **5022 / 0 / 4** |
| L31 | 2026-10-18 | **链路 A 长方法拆分**：`mapOneDocument` 411 → 327（三个阶段方法）、`agentQA` 165 → 26（七步）、`runToolCallInner` 231 → 153（参数解析 + 失败调用 + sealed 结果）、`parseQARequest` 235 → 102（五个具名步骤）。**新增 `scripts/method-length-report.py`**（跳字符串的花括号计数）：实测 >150 行方法 **25 个**，而非外部报告的 167（`Fetcher.detectContentType` 报 374 实为 15、`ToolPolicy.normalizeMCPCallArguments` 报 276 实为 22）。 | **32 守卫** · **5022 / 0 / 4** |
| L32 | 2026-10-19 | **RBAC 注册长方法拆分**：`RbacRoutePolicies.registerAll` 526 行 → 13 个按路由族命名的私有方法（models/knowledge/chunks/faq/mcp/wiki/audit/session/datasource/infra/tenant/misc/agents/embed/public），`registerAll` 只保留固定顺序调用列表；**登记顺序即匹配优先级**，调用顺序逐段保持。文件 619 → 598，过 B121 阈值；`route-recon` 与 `W5aSundryRoutesContractTest` / `ArchitectureRulesTest` 全绿。长方法 >150：24 → 23。 | **40 守卫** · **boot 全量** · build 1m29s |
| L33 | 2026-10-19 | **Wiki 入库 Map 阶段长方法拆分**：`WikiIngestMapPhase.mapOneDocument` 327 → 102 行；并行摘要/引用分类与合并/重认领拆成 `classifyAndMerge`（含 `ClassifyResult` 记录），页面更新构建拆成 `buildUpdates`（含 `UpdateResult` 记录）；取消守卫、摘要失败重试、span 开闭与日志字段保持原语义。文件 588 行；长方法 >150：23 → 22。 | **40 守卫** · **wiki ingest 测试** · **boot 全量** · build 1m32s |
| L34a | 2026-10-19 | **wiki 队列的槽位收尾端口**：队列实现（Redis/进程内）此前直接 new `WikiIngestTaskRunner` 并持有 `WikiIngestService`，搬出 service 包会形成 repository → service 反向依赖。抽 `WikiIngestSlotReleaser`（单方法）到 `wiki.domain.ingest`，队列与 TaskRunner 只依赖该端口。 | **40 守卫** · :domains 编译 · wiki 定向测试 |
| L34b | 2026-10-19 | **wiki ingest 拆包**：技术实现移出用例层，46 → 24 文件。`wiki/repository/ingest`（5 端口 + Redis/进程内两套实现 + TaskRunner，17）；`wiki/domain/ingest`（载荷/finalize 行/超时常量，7）；`service/ingest` 只留管线门面与阶段协作者。**A17 基线收紧 6 条**；换锚：json-key 的 TaskRunner 路径、包规模基线、三份索引；javadoc 断链 5 处降级 `{@code}`。 | **40 守卫** · **clean + 5030 / 0 / 4** · javadoc 0 错 |
| L35 | 2026-10-19 | **mcp/oauth 拆子包**：34 文件 → `model`(7) / `error`(7) / `store`(7) / `wiring`(3) / 根(9)。`OAuthConfig`（持 TokenStore）与 `OAuthAuthorizationRequiredException`（持 OAuthHandler）刻意留根——放进 model/error 会让四包两两成环（实测 SCC +1，已回归 5 组）。换锚：json-key 4 条路径、包规模基线移除该条目、三份索引；javadoc 断链 7 处降级。 | **40 守卫** · **5030 / 0 / 4** · javadoc 0 错 |
| L36 | 2026-10-19 | **手写门面搬出 mapper 包**：6 个 `@Component`/`@Repository` 门面归位 `audit` / `auth.apikey` / `datasource`(×2) / `storage`(×2) 的 `repository/`；`storage/mapper` 清空删除。**A11b 基线 6 → 0**（零容忍）；**A16 基线 −7**（AuditLogService、DataSourceSupport/SyncExecutor/SyncResultOps、storage 三件）。 | **40 守卫** · **5030 / 0 / 4**（首轮 1 例 Redis 瞬态，隔离绿）· javadoc 0 错 |
| L37a | 2026-10-19 | **auth 令牌门面**：新增 `auth/repository/AuthTokenRepository`（按串查询/落库/单行吊销/整用户吊销）；`UserService` 与 `UserSessionOps` 不再持有 `AuthTokenMapper`；`TenantAPIKeyNotFoundException` 从 `mapper/` 移到 `auth/apikey/` 根包（放 `domain/` 会命中 C10「控制器不得 import 领域类型」——该规则本意是防实体泄漏，异常作为控制器捕获面不适用，故换落点而非改守卫）。**A16 −2、A17 −1**。 | **40 守卫** · **5030 / 0 / 4** |
| L37b | 2026-10-19 | **auth 邀请门面**：新增 `auth/repository/TenantInvitationRepository`，12 处持久化调用下沉（id/token/pair 查询、租户分页 count+list、收件箱列表与计数、插入、`accepted_count` 原子自增、pending 原子翻转、过期清扫）；状态常量单一来源（service 的 `STATUS_PENDING`/`STATUS_EXPIRED` 引用仓储常量）。**A16 −1、A17 −1**。 | **40 守卫** · **5030 / 0 / 4** |
| L37c | 2026-10-19 | **租户管理跨域解耦 + 成员门面**：新增 `tenant/repository/TenantAdmin`（域侧端口，返回实体，按 `TenantLookup` 注释只给 L3 用）+ `TenantAdminImpl`（12 处调用下沉）；`TenantService` 不再持有 tenant 域 `TenantMapper`。新增 `auth/repository/TenantMemberRepository`、`UserRepository`；`AuthTokenRepository` 扩展 `revokeActiveByUserId`。附带：成员分页的关键字过滤原对用户表查两次，改为解析一次 id 集复用。**A16 −2、A17 −2**。 | **40 守卫** · **5030 / 0 / 4** |
| L37d | 2026-10-19 | **UserService 收口**：`UserRepository` 扩为完整门面（邮箱/用户名/租户首用户、批量 id、插入含 tenantless 省略列、整行回写、`tenant_id` 置 NULL、恢复 tenantless、物理删除）；14 处 `userMapper` 调用下沉，service 不再 import MyBatis/mapper。**auth 域 A16/A17 清零（6 → 0）**。 | **40 守卫** · **5030 / 0 / 4** |
| L38 | 2026-10-19 | **六个小域门面化**：favorite / system / model（含 usage）/ vectorstore / embedchannel / agentmanagement（含 question）各建 `repository` 门面，9 个 service 不再注入 mapper。vectorstore 的 mapper 同时更名 `VectorStoreRepository` → `VectorStoreMapper`（A11 命名对齐，为门面腾出原名）。**A16 −9、A17 −3**。 | **40 守卫** · **5030 / 0 / 4**（首轮 2 例 Redis 瞬态，隔离绿） |
| L39 | 2026-10-19 | **IM 域门面 + Redis 运行面外提**：新增 `im/repository/ImChannelRepository`、`ChannelSessionRepository`；4 个 service 不再持有 mapper。新增 `im/runtime/ImRedisRuntime`——把 `im.redis-enabled` 判定、连接 ping 校验、`ImRedisStore`/`RedisPubSub` 创建从编排器构造体移出，编排器只取现成实例。**A16 −4、A17 −1（im 清零）**。 | **40 守卫** · **5030 / 0 / 4** |
| L40 | 2026-10-19 | **memory 运行面外提**：`MemoryTrace`（langfuse 门面）、进程内/Redis 两个蒸馏队列、队列装配搬到 `memory/runtime`；`MemoryExtractPayload`、`MemoryExtractTaskQueue` 搬到 `memory/domain`。新增 `MemoryExtractHandler` 执行端口——队列不再认 `MemoryExtractionService` 类型（否则 runtime ↔ service 成环）；service 实现该端口并把 `LangfuseTracing.inject()` 换成 `MemoryTrace.inject()`。换锚：json-key 前缀/键级基线与快照、Redis 队列测试随实现搬包。**A17 −5（memory 清零）**。 | **40 守卫** · **5030 / 0 / 4**（首轮 1 例 Redis 瞬态，隔离绿） |
| L41 | 2026-10-19 | **Redis 测试端口竞态修复（测试基建）**：`EmbeddedRedis` 弃 `ServerSocket(0)` 探测，改固定端口池（16390..16453）+ 跨进程 `FileLock`（同 JVM 重叠锁按占用跳过）；`close()` 改为先杀进程再关连接池。新增红态探针 `EmbeddedRedisPortAllocationTest`（4 并发实例断言端口互异）。**Redis 瞬态族移出「已知偶发」**。 | **40 守卫** · **全量连跑 5 遍（2 遍无缓存）0 失败** |
| L42 | 2026-10-19 | **datasource / websearch 端口化**：`DataSourceSyncHandler`/`DataSourceSyncTaskQueue` 接口搬 `datasource/domain`；新增 `DataSourceSchedulePort`（调度 + 重试/超时常量），`Scheduler` 实现之，service 不再依赖具体调度器；`DataSourceWiring` 搬 `datasource/config`；新增 `DataSourceTrace`。websearch 的 mapper 更名 `WebSearchProviderMapper`，新增 `repository/WebSearchProviderRepository` 门面；`WebSearchTempKbStateService` 搬 `websearch/runtime`。**A16 −4、A17 −2（两域清零）**。 | **40 守卫** · **5031 / 0 / 4** · javadoc 0 错 |
| L43 | 2026-10-19 | **mcp 门面 + wiki/embedchannel 运行面归位**：mcp 新增 `repository/McpServiceRepository`，3 个 service 不再注入 mapper。wiki 的 `WikiSlugLock` 接口搬 `domain/page`，进程内/Redis 实现搬 `repository/page`；新增 `WikiTrace`，`WikiIngestEnqueueOps` 不再直调 langfuse。embedchannel 的 `EmbedTokenStore` 搬 `domain`、Redis 实现搬 `repository`。**A16 −3（mcp 清零）、A17 −3**。 | **40 守卫** · **5031 / 0 / 4** · javadoc 0 错 |
| L44a | 2026-10-19 | **knowledge 任务面端口化**：新增 `support/KnowledgeTaskRunner`（submit/submitNamed），`KnowledgeTaskExecutor` 实现之，7 个 service（Housekeeping/Summary/FaqEntry/FaqImport/Clone/Move/Tag）不再依赖具体执行器；`KnowledgeTaskProgressStore`/`FaqImportTaskStore`/`KnowledgeTaskIdCodec`/`KnowledgeProcessedEvent` 从 `task` 搬 `support`（**不放 domain**：控制器要 import，会命中 C10）。**A16 −9（knowledge 15 → 6）**。 | **40 守卫** · **5031 / 0 / 4** · javadoc 0 错 |
| L44b | 2026-10-19 | **knowledge 适配器归位 + 观测门面**：`EmbeddingModelAdapter`、`KnowledgeBaseLookupAdapter` 搬 `knowledge/api`（本就是端口适配器）。新增 `runtime/KnowledgeTrace`——`startTask` 返回自有 `Scope` 包装（用例层不 import langfuse）、`previewPayload` 返回 `Object`；`ChunkExtractService`/`QuestionGenerationService` 改用它。**A16 −2（余 1）、A17 −3（knowledge 余 2）**。 | **40 守卫** · **5031 / 0 / 4** |
| L44c | 2026-10-19 | **A16 清零**：新增 `support/KnowledgePage` + `KnowledgeRepository.searchPage`（条件构造与分页插件类型收进仓储，`KnowledgeService` 不再 import MyBatis）；新增 `support/EmbeddingFacts`，`FaqIndexWriter` 改注入 `contract` 的 `EmbeddingPort`。**A16 基线 45 → 0**；A17 −1（knowledge 清零）。换锚：port-map。 | **40 守卫** · **cleanTest 5031 / 0 / 4** |
| L45 | 2026-10-19 | **A17 清零（收官）**：新增 `session/runtime/SessionModels`（`LangfuseChatClient`/`LangfuseReranker` 的 wrap 收口）与 `SessionTrace`（`start` 返回自有 `SpanHandle`、`attachTraceparent` 转发）；6 个 session 类改用它，不再 import langfuse/decorators。**三张棘轮全部归零：A16 45→0、A17 35→0、A11b 6→0**。 | **40 守卫** · **cleanTest 5031 / 0 / 4** |
| L46 | 2026-10-19 | **跨域直连清零**（评估文档点名项）：`SessionMessageView` 搬 `contract/session`（跨域读模型）；新增 `MessageTranscriptPort` + `session/api/MessageTranscriptAdapter`；`MemoryExtractionService` 改注入端口，不再直接持有 `session.repository.MessageRepository`。**memory 域对 session 的直接 import 清零（2 → 0）**。 | **40 守卫** · **cleanTest 5031 / 0 / 4** |
| L47 | 2026-10-19 | **7 厂商存储探测归位 storage**（评估文档点名项）：新增 `contract/storage/StorageConnectivityPort`（配置以纯 JDK `Map` 传入）+ `storage/service/StorageConnectivityService` 实现；`SystemController` 694 → 319 行，`SystemInfoService` 252 → 205（三个存储方法移出）。**走端口而非直接 import**——直连会给 system→storage 新增域间边（实测 `check-domain-contracts` 报红）。换锚：json-key 基线键随实现搬迁、domain-contracts 收紧 2 处。 | **40 守卫** · **cleanTest 5031 / 0 / 4** · 金片字节一致 |
| L48 | 2026-10-19 | **initialization 手动 new 改注入**：控制器构造体里 `new` 的 4 个用例服务加 `@Service`（`public final class`，注解放 final 前）并改为构造注入；控制器顺带去掉 10 个下游依赖参数 + 1 个从未使用的 `CryptoService`。**踩坑**：首轮只在 `public class` 前插注解 → `final class` 未命中，boot 1212 例上下文失败；补注解后复跑绿。 | **40 守卫** · **cleanTest 5031 / 0 / 4** |
| L49 | 2026-10-19 | **evaluation 域下线**（产品决策）：该域无持久化、前端零调用，仅 `POST/GET /api/v1/evaluation` 与内存指标算法。删 30 主文件 + 5 测试；清 APIKey 路由策略、RBAC 规则、WebConfig 排除名单、Langfuse 拦截命中名单。**保留 `APIKeyCapability.RUN_EVALUATIONS` 常量**（已持久化的 Key 可能携带，删了会解析失败）。换锚：域清单/能力分类/api-docs 映射/README 与 overview 控制器数/五份索引；`docs/site` 加入 .gitignore。**域数 22 → 21，控制器 51 → 50**。 | **40 守卫** · **cleanTest 5002 / 0 / 4**（−29 为随域删除的用例） |
| L50 | 2026-10-19 | **tenant 域并入 auth**（四处归属错配收官）：`Tenant`/类型处理器 → `auth/domain`，`TenantMapper` → `auth/mapper`，`TenantAdmin`/`TenantAdminImpl`/`TenantLookup` → `auth/repository`，`TenantStorageQuotaAdapter` → `auth/api`；删 5 个 package-info；43 文件 FQN 换锚；`FullTableWriteGuard` 的字符串白名单同步；误置的 `TenantConfigRedactionTest` 归位 `auth/domain`。**域数 21 → 20**；domain-contracts 由 7 边收紧到 6 边，**clearable/owner 两档同时归零**（只剩 intra-family）。 | **40 守卫** · **cleanTest 5002 / 0 / 4** |
| P1-1 | 2026-10-19 | **注册改返回投影**（评估文档 P0-2）：`POST /auth/register` 原直接返回 `User` 实体，`passwordHash` 只靠 `@JsonIgnore` 兜底——一次 Jackson 配置变更就漏。改返回 `UserInfo`（与 `/auth/me` 同形状）。契约差异仅去掉恒为 null 的 `deletedAt`（前端无消费，golden 与契约签名同步签署）。 | **40 守卫** · **auth 面测试绿** |
| P1-2 | 2026-10-19 | **NORMAL 失败也落完成位**（评估文档 P0-1 的后半）：`QaTurnExecutor` 的 finally 只对 AGENT 收尾，NORMAL 的完成位全靠事件管线发 done，管线早段异常时消息永久 `completed=false`。补 `failed` 标志 + finally 的 NORMAL 分支，调 `completeAssistantMessage`（CAS 幂等）；抽 `finalizeNormalOnFailure` 供测试钉住。红态验证：临时置空实现 → 测试失败，恢复即绿。 | **40 守卫** · **cleanTest 5003 / 0 / 4** |
| OC-1 | 2026-10-19 | **入库图片 OCR（本地 RapidOCR，不走大模型）**：新增 `KnowledgeImageOcr`（只认正文 `exports` 图片引用 → 并发调 OCR 服务 → 文字插回引用下方）+ `KnowledgeOcrProperties`（`knowledge.ocr.*`）；`KnowledgeTextExtractor` 解析后接入，多模态 span 由 OCR 阶段记录（删 `KnowledgeIndexStage.skipMultimodal` 固定占位）；新增 `scripts/ocr-server.py`（`POST /ocr` → `{"text"}`）。单图失败降级、`maxPages` 默认 30、endpoint 为空即关闭。 | **40 守卫** · **5006 / 0 / 4** · 真机：4-1 重解析 30 图全成（新增 6204 字 / 16.5s），分块 12→34、字符 4839→14021；检索「凯美瑞 车型定位」命中摘要 |
| OC-2 | 2026-10-19 | **OCR 输出按坐标还原多列关系**：`ocr-server.py` 保留检测框坐标——横跨页面 >45% 的宽块（标题/页脚/总结句）单独成段，其余按 x 中心聚列、列内按 y 排序；先判「同一水平带是否出现不同 x 簇」区分单列/多列，单列页仍按 y 输出，避免短标题被拆成假列。纯脚本改动，无新依赖。 | **40 守卫全绿** · 真机：四列页由「名称/价格/定位各自成行」还原为「凯美瑞+17.18-25.98万+15万级…」四组；单列页顺序不变；4-1 重解析 34 块 / 13021 字 |
| OC-3 | 2026-10-19 | **OCR 版面重排分层化**：按页面级水平空白投影（>12% 页高）把页面切成上下区块——「上 4 列 + 下 3 列」落在不同区块各自聚列；单列层（同行无不重叠文本块）直接按 y 输出，标题与正文不再被聚成假列；宽块与列块统一按起始 y 交错排序（整行标题不再固定丢页尾）；列聚类同时看中心与 x 区间重叠，合并同卡片的中英文名。纯脚本，无新依赖。 | **40 守卫全绿** · 真机：单列页顺序恢复「小结 → 1/2/3 标题+正文」；四列页仍为四组；4-1 重解析 34 块 / 13805 字 |
| OC-4 | 2026-10-19 | **OCR 阅读顺序与分段**：行内容差（2% 页高）视为同一行，行内按 x 从左到右（不再严格按 y1 排序）；块级同样按「行容差 + 行内 x」排序；行与行、列与列统一用空行分隔。 | **40 守卫全绿** · 真机：四列页同一行英文名按 CAMRY/HIGH/SIENNA 从左到右；4-1 重解析 37 块 / 13906 字 |
| OC-5 | 2026-10-19 | **OCR 分段收敛**：空行只加在列组/行块之间，列内各行恢复单换行——OC-4 的逐行空行会让分块被 `\n\n` 切碎（37 块）。阅读顺序与列合并逻辑不变。 | **40 守卫全绿** · 4-1 重解析 34 块 / 13751 字 |
| G1 | 2026-10-20 | **SSE 终态收口**：`QaSseOrchestrator.handleAgentEventsForSSE` 与 `SessionStreamController` 续传循环认 `ERROR+done` 终态（不再 100ms 空转）；两条轮询各加 2h 存活上限；title 等待加 50ms sleep 消忙转。新增红态测试 2 例（旧行为下 2/2 失败）。 | **40 守卫全绿** · **5008 / 0 / 4** · spotlessCheck build 1m47s · 冒烟 sessions/knowledge-bases/models 200 |
| H0 | 2026-10-20 | **判据先行**：新增 `check-contract-shape.py`（端口 ≤7 / `*RowView`·`*PageView` 禁入 / 字段 ≤20；22 条基线）+ `check-port-budget.py`（域内/无实现端口 = 0；空基线）；`check-platform-shapes.py` 换血为"业务词包 / 业务词文件 / 红旗命名"三档（16 条基线）；新增 `docs/architecture/business-capability-map.md`（六条链路 → 入口/编排/落点）。三条守卫各验红态探针。 | **42 守卫全绿** · guards 1 轮 |
| G2 | 2026-10-20 | **整改尾巴**：evaluation 前端勾选项移除（类型/标签保留兼容已存 Key）· approval 明示为 mcp 策略域 · 存储探测 HTTP 面迁 storage（`/api/v1/storage/engine-status|check`，新增 `StorageEngineController` + DTO 随迁）· 解析引擎目录归 knowledge（`contract.doc.ParserEngineCatalogPort` + 8 引擎实现搬 `knowledge/service/parser`；37 个配置键归 `ParserEngineConfig.overridesFrom`）· 连通性双头**不合并**（未保存配置测试 vs 已保存模型调试是两个真实场景） | **42 守卫全绿** · 前端 753 / type-check 0 · boot `SystemContractTest` · Port 32→33 |
| G3 | 2026-10-20 | **还乡 2.0（逐件核验版）**：`ToolResultPersist` 下移 `session/support`（contract 净减 1；纯 JDK、contract 内无回引、消费方仅 session 两处）。**其余三类经核验不可还乡**：① `platform/knowledge` 8 件全部被 ≥2 个业务族消费且依赖 Jackson/ImageIO（进不了 contract、归域会跨族直连）⇒ 留 platform 并重写 package-info 说明判据；② `MemoryKeys` 被 `contract.memory.MemoryConfig` 内部引用（`runeLength`/`runeSlice`）⇒ 留 contract；③ `model↔retrieval` 包环实测已不存在（SCC 5 组基线未变）。 | **42 守卫全绿** · **5008 / 0 / 4** · build 1m52s |
| G4 | 2026-10-20 | **门面尾巴**：① `SubtaskDrain` 抽取（问题生成 / 图谱抽取两份逐字递减合一；收 `Runnable` 避免新增 knowledge→wiki 边）；② `TenantAdmin` 端口折叠为 `TenantRepository`（单实现单消费方同域，删 1 接口 + 12 处 `@Override`）；③ `DataSourceTrace`/`WikiTrace` 两个 16 行双胞胎合并为 `platform.tracing.TraceInjector`（删 2 类 2 包）；④ `mechanisms.md` 增 `runtime` 包四义词典与三条规则。**未做**：finalizer 6 行 import 换 Adapter——knowledge↔wiki 是族内直连，加端口属代理税（与 R1 冲突）。 | **42 守卫全绿** · **5008 / 0 / 4** · build 1m50s |
| H5a-1 | 2026-10-20 | **分页形状统一**：新增 `contract.pagination.PageResult<T>(items,total,page,pageSize)`；11 个分页类型收敛（ChunkPageResponse / FaqEntryPage / TagPageResult / KnowledgePage / MemoryPage / AdminUserPage / TagPage / ChunkPage / SessionPage / TenantSearchPage / InvitationPage / MemberPage）；净删 6 个类、−218 行。wire 仅 `AdminUserPage.users→items`（无消费方）。**余**：12 个管理列表加分页参数 + 前端（H5a-2，需逐页定默认页大小，属产品交互）。 | **42 守卫全绿** · **5008 / 0 / 4** · build 2m1s |
| H4a-1 | 2026-10-20 | **任务契约与重试钩子统一**：新增 `platform.task.TaskQueue`（name/start/stop/setRetryDelayOverrideSeconds 默认契约）与 `RetryDelayOverride`（null=fallback、覆盖钳非负）；6 套域队列接口继承 `TaskQueue`；5 处重复的字段+setter+解析收敛为共享 holder（datasource 2 / wiki 2 / platform 1）；投递方法保留各自语义（taskId / 上限 / 超时 / 延迟）。**余**：H4b 把实现迁到 `RedisRetryingTaskQueue` 基座。 | **42 守卫全绿** · **5012 / 0 / 4**（platform +4）· build 1m57s |
| H5b | 2026-10-20 | **软删过滤探测**：新增 `platform.persistence.SoftDeleteFilterGuard`（26 张带 `deleted_at` 的表，`off/alert/enforce` 三档，默认 enforce），挂 MyBatis 插件链第 4 位；SELECT 主查询缺 `deleted_at` 谓词即抛 `MybatisPlusException`。实测存量 **0 条违规**（79 处手写谓词已覆盖），`ALLOWED_STATEMENTS` 为空；v1 边界同租户守卫（JOIN 次表/UNION/FROM 子查询不查、解析失败放行）。新增守卫单测 5 例 + 装配档位测试 1 例。 | **42 守卫全绿** · **5013 / 0 / 4**（boot +1）· build 1m45s |
| H4b-1 | 2026-10-20 | **datasource 队列迁基座**：`RedisRetryingTaskQueue` 补齐 per-task `maxRetry` / `timeoutMillis` / 幂等键 / 提交结果（`TaskSpec` / `SubmitOutcome`），成员 JSON 对缺省字段兼容；`RedisDataSourceSyncTaskQueue` 从 292 行降到 ~120 行，只保留 TaskID 去重、载荷解码与 trace 包装；6 个队列测试（去重 / 重试 / 超时 / 租约回收）全绿。**余**：wiki（死信 + 锁冲突 15s + slot releaser）。 | **42 守卫全绿** · **5013 / 0 / 4** · build 2m23s |
| H4b-2 | 2026-10-20 | **wiki 队列迁基座**：`RedisRetryingTaskQueue` 加 `FailurePolicy`（重试延迟 + 终态死信回调）与 `TaskSpec.idempotencyTtlSeconds` 覆盖；`RedisWikiIngestTaskQueue` 从 393 行降到 ~200 行，只留 TaskID 合并、载荷编解码、锁冲突 15s 与 slot 释放；成员 JSON 改为基座 `QueueMember`（在飞旧格式不兼容，pre-release 可接受）。6 个 wiki 队列测试（延迟 / 合并 / 重试 / 死信 / 租约回收）全绿。 | **42 守卫全绿** · **5017 / 0 / 4** · build 3m47s |
| H1a | 2026-10-20 | **轮次终态单赢家**：新增 `session.service.TurnLifecycle`（STREAMING→FINALIZING→DONE/FAILED，终态不可逆）；`QaTurnExecutor` 的收尾闸门由局部 `boolean[1]` 改为 `finish(DONE)`；SSE 推送与续传两个观察者循环改用 `isTerminalEvent`（COMPLETE 或 ERROR+done）统一口径。新增状态机单测 4 例。**余**：H1b 收 3 把锁与状态持久化。 | **42 守卫全绿** · **5021 / 0 / 4**（domains +4）· build 3m34s |
| H1b-1 | 2026-10-20 | **编排层去 `controller.sse` 反向依赖**：`StreamEventEmitter` / `StreamResponseBuilder` 移入 `session.service.sse`；新增 `SseFrameSink` 端口，`controller.sse.SseFrameWriter` 实现它（Servlet 直写与头部契约留在 controller）；`QaSseOrchestrator` 不再 import `controller.*`。事件命名守卫名单、包索引同步。 | **42 守卫全绿** · **5021 / 0 / 4** · build 3m12s |
| H2a | 2026-10-20 | **运行时段抽离**：新增 `session.rag.TurnRuntime`（eventBus / messageId / userMessageId）；`ChatManage` 删 3 字段 + 6 访问器，改为 `getRuntime()`；11 个文件的调用点改为 `getRuntime().getX()`；`cloneChatManage` 不再需要"故意不复制"的说明。 | **42 守卫全绿** · **5021 / 0 / 4** · build 2m19s |
| H2b-1 | 2026-10-20 | **请求段只读视图**：新增 `session.rag.TurnInputView`（41 个请求/配置 getter），`ChatManage implements`；`PipelineCommon.prepareChatModel` 与 `targets` 改收视图，模型准备不再依赖可变 `ChatManage`。插件的 `onEvent` 仍收 `ChatManage`（状态写入），签名随 H2c 状态段一起改。 | **42 守卫全绿** · **5022 / 0 / 4**（domains +1）· build 2m9s |
| H2c-1 | 2026-10-20 | **答复组抽离**：新增 `session.rag.AnswerState`（userContent / renderedContexts / chatResponse）；`ChatManage` 删 3 字段 + 6 访问器，改为 `getAnswer()`；12 个文件、51 处调用点改链式访问；`cloneChatManage` 只复制 renderedContexts（与原复制面一致）。`gen-pipeline-map.py` 支持状态组链式访问并过滤包装 getter。 | **42 守卫全绿** · **5022 / 0 / 4** · build 2m1s |
| H2c-2 | 2026-10-20 | **重排组抽离**：新增 `session.rag.RerankOutcome`（results）；`ChatManage` 删 1 字段 + 2 访问器，改为 `getRerank()`；11 个文件、41 处调用点改链式访问；管线表覆盖重排组链式访问。 | **42 守卫全绿** · **5022 / 0 / 4** · build 2m14s |
| H2c-3 | 2026-10-20 | **合并组抽离**：新增 `session.rag.MergedContext`（results）；`ChatManage` 删 mergeResult 字段 + 2 访问器，改为 `getMerged()`；10 个文件、33 处调用点改链式访问；管线表覆盖合并组链式访问。 | **42 守卫全绿** · **5022 / 0 / 4** · build 2m14s |
| H2c-4 | 2026-10-20 | **查询计划抽离**：新增 `session.rag.QueryPlan`（rewriteQuery/intent/imageDescription/quotedContext/systemPromptOverride/memoryPrompt）；`ChatManage` 删 6 字段 + 12 访问器，改为 `getQueryPlan()`；24 个文件、138 处调用点改链式访问；克隆时整组复制；`needsRetrieval` 内部读取改走查询计划。 | **42 守卫全绿** · **5022 / 0 / 4** · build 2m14s |
| H2c-5 | 2026-10-20 | **实体组抽离**：新增 `session.rag.EntityContext`（entity/entityKbIds/entityKnowledge）；`ChatManage` 删 3 字段 + 6 访问器，改为 `getEntityContext()`；5 个文件、15 处调用点改链式访问（含并发分支的 `fOrig`）；克隆整组复制且容器换新。 | **42 守卫全绿** · **5022 / 0 / 4** · build 2m7s |
| H2c-6 | 2026-10-20 | **检索产出抽离（H2c 收尾）**：新增 `session.rag.SearchOutcome`（results/graph）；`ChatManage` 删 searchResult/graphResult 字段 + 4 访问器，改为 `getSearchOutcome()`；12 个文件、64 处调用点改链式访问（含 `chunkCM`/`entityCM` 并发分支）；clone 不复制产出（结构上保证）；管线表覆盖。 | **42 守卫全绿** · **5022 / 0 / 4** · build 2m2s |
| H2b-2 | 2026-10-20 | **插件签名去 `ChatManage`**：新增 `session.rag.PipelineState`（`TurnInputView` + 六组状态 + history/usedMemories + `needsRetrieval`/`citationsEnabled`/`setRerankThreshold`/`fork`）；`ChatManage implements PipelineState`，`fork()` 统一并发副本；`Plugin.onEvent` / `EventManager` 改收 `PipelineState`；17 个插件 + 4 个辅助类 + 测试夹具同步，**插件包 `ChatManage` 引用 0**。管线表生成器识别新签名。**余**：`QaRequestContext` 34 字段收口。 | **42 守卫全绿** · **5022 / 0 / 4** · build 2m41s |
| H3-1 | 2026-10-20 | **入库阶段枚举与尝试状态**：新增 `knowledge.domain.ingest.IngestStage`（5 canonical 阶段，span 常量与 `ALL_STAGES` 的唯一来源）与 `IngestJob`（PENDING→PROCESSING→COMPLETED/FAILED，终态不可逆）；`KnowledgeProcessWorker` 按 DOC_READER→CHUNKING→EMBEDDING→POST_PROCESS 推进，失败日志带阶段/状态。**未新增持久化状态**：权威仍是 `parse_status` + span 根行。 | **42 守卫全绿** · **5025 / 0 / 4**（domains +3）· build 2m22s |
| H3-2 | 2026-10-20 | **入库状态迁移收口**：新增 `IngestStateRepository`（6 个 `parse_status` 迁移：casPendingToProcessing / promoteToFinalizing / promoteFinalizingToCompleted / completeIfOwned / failIfProcessing / markIndexedEnabled），从 `KnowledgeRepository` 整块搬出（净减 88 行）；worker / finalizing / finalize adapter 改注入状态仓储；3 个测试构造与桩同步。**未合并 SQL**：各迁移条件不同，保持独立方法。 | **42 守卫全绿** · **5025 / 0 / 4** · build 2m11s |
| H3-3 | 2026-10-20 | **收尾核验（两项不做）**：① `KnowledgeTaskExecutor` 被 8 处消费（FAQ 导入 / 移动 / 标签索引 / KB 克隆 / 摘要刷新 / housekeeping），是域级后台执行器，不是入库专用壳；删除会把这些服务重新散回 `Thread.ofVirtual()`，与 G4 的线程收口相反。② 5 个 stage 的通用接口需要 `IngestContext` 承载全部中间产物，等于把 `IngestJob` + worker 局部变量再造一遍；现有 stage 已有类型化签名与 span 跟踪，接口化无行为收益。 | **42 守卫全绿** · **5025 / 0 / 4** · 无代码变更 |
| H6-1 | 2026-10-20 | **知识目录端口拆窄**：`KnowledgeCatalogPort` 38 方法拆 7 个窄端口（KnowledgeEntryRead / ChunkFaqRead / KnowledgeBaseRead / KnowledgeBaseWrite / KnowledgeIngestLookup / KnowledgeContentWrite / KnowledgeDelete），复合接口空体继承保持兼容；适配器与 20+ 消费方零改动。`gen-port-map.py` 识别"复合面（随复合端口）"，端口准入不再把窄面当域内/无实现；契约形状基线移除 KnowledgeCatalogPort（34→0）。 | **42 守卫全绿** · **5025 / 0 / 4** · build 2m34s |
| K0 | 2026-10-20 | **文档计数对齐 + 守卫扩面**：README 27→26（3 处）、overview 23→24 与 27→26、"全部模块合计 51 个 HTTP 控制器"→"51 个业务控制器（另有 1 个 `/health` 探针）"、金片口径写实（1,349 JSON + 5 非 JSON 样本）；`contract/build.gradle.kts` 金片/ContractTest 数按实测、"零依赖"改"生产依赖块为空"；`domains/build.gradle.kts` 删"约 18 处"改指守卫。`check-doc-truth.py` 扩 D2（业务控制器 + 探针）/D4（金片双口径）/D5（provider 注册数），三条红态探针均验证。**核实评估报告**："1,433 份金片"不实、控制器属措辞、19 个 `@Configuration` 实为 10 真装配 + 7 properties；结论落 `docs/internal/plans/architecture-remediation-k-series.md` | **42 守卫全绿** · spotlessCheck 绿 · compileJava/compileTestJava 绿（无 Java 改动，未跑全量测试） |
| K4 | 2026-10-20 | **装配点清单**：新增 `scripts/wiring-points.baseline.json`（22 个 `@Configuration` 按组合根 8 / 域内 10 / 模块内 4 登记，每条带理由）与 `check-wiring-points.py`（W1 扫描集==清单集 / W2 分类与落点一致 / W3 理由非空 / W4 overview 文档同步）；`overview.md` 增「装配点」一节，并修正设计原则 2 与「跨模块 Bean」行的表述（agent-engine 组件扫描面登记为已知机制）；守卫接入 run-guards 第 43 条。**红态探针四条**：临时装配类（短形式 + FQN 两种写法）、陈旧登记、分类错位、文档缺项，均红并复原。首轮探针暴露 FQN 写法漏检，已补判据 | **43 守卫全绿** · spotlessCheck 绿（无 Java 改动） |
| K1 | 2026-10-20 | **引擎注册合表**：`EngineFactory` 的两张 switch（构造 + 运行时 SSRF）合成一张 `SPECS` 注册表，`EngineSpec` 强制"声明地址字段或显式 `addressExempt`"（否则类初始化失败）；删掉不可达的 `unsupported engine type` 兜底；新增完整性测试（遍历 `EngineTypes.ENGINE_*` 断言每个常量都有注册项）。**红态验证**：摘掉 milvus 注册项 → 完整性测试与 Milvus 构造测试同时红，恢复后绿。`FileServiceFactory` 删 `IMPLEMENTED`/`PENDING` 死集合与配套不可达分支，测试从"断言集合内容"改为行为断言（8 个 provider 认得、ceph 报 unsupported 原文）。**未做**：跨模块 SSRF 元数据共享——两条路径都 fail-closed，属口径一致性成本而非安全漏洞 | **43 守卫全绿** · spotlessCheck 绿 · build 2m16s / **5026 / 0 / 4**（+1 完整性测试） |
| K2 | 2026-10-20 | **跨域实体渗漏清零**：`contract.agent.AgentLookup.AgentView` 补 `tenantId`（复用既有端口，不新造投影）；8 个 session 文件改持视图（`AgentResolver` 注入 `AgentLookup`；两处从未使用的实体参数一并删除）；`ResolvedAgent` 删恒 false 的 `sharedAgentReadOnly`，清 6 处消费点（`QaSupport`×3 / `QaAgentConfig`×3 / `AgentConfigAssembler` 的 MCP 范围分支 / `AgentEngineAssembler` 的写工具过滤）。`check-domain-contracts.py` 增 **E1** 判据（跨域 `.domain.` import 即红，不吃族内豁免，基线 0）；红态探针：临时加回一行实体 import → 红并复原。**副作用**：域间边参考类 33 → 27（session→agentmanagement 11 → 5） | **43 守卫全绿** · 跨域实体 import 0 · build 2m28s / **5026 / 0 / 4** |
| K3 | 2026-10-20 | **LLM 目录单源化**：`ModelProviderCatalog.ENTRIES` 改为 `ProviderRegistry.list().stream().map(...)`，展示名/描述/默认 URL/模型类型全部取自 `ProviderInfo`，`toFrontend` 只做表示层映射（26 条手写清单删除）；`ProviderCatalogParityTest` 删除（单源后同义反复），新增 `ProviderRegistryCompletenessTest`（`ProviderName` 全集 == `list()`，漏注册/漏进 allProviders 即红）；`check-module-graph` 的错位测试登记随之 10 → 9；`Provider.validateConfig` 登记 `known-gaps.md` 第 19 条并在接口注释留指针（首次写法用了 § 编号，被迁移代号守卫拦下，已改为条目名） | **43 守卫全绿** · 目录金片 `model-providers*.json` 字节一致 · build 1m44s / **5026 / 0 / 4** |
| L0 | 2026-10-20 | **删除不存在的评测承诺**（用户拍板"评测不做"）：README 能力清单与端点表两处删除；`docs/internal/plans/business-link-remediation-l-series.md` 落档 02 报告的 4 处核实更正（评测承诺在 README 而非"全仓没有"、连接器 catalog 前端不可选、生成式文档自带同步守卫、端口/规模数字属报告后增长）与 P1-1~P1-5 的采纳/降级/否决理由 | **43 守卫全绿** · 无 Java 改动 |
| L1 | 2026-10-20 | **引擎类型常量单源化**：新增 `contract.vectorstore.VectorEngineTypes`（9 全集 / 7 可配置，差额恰为 postgres+sqlite）；`retrieval.engine.EngineTypes` 的 9 个 `ENGINE_*` 改为契约别名（switch case 仍为编译期常量）；`vectorstore.domain.VectorStoreEngines` 白名单改引 `CONFIGURABLE`；新增两条测试钉住"9/7/差额"与"EngineTypes 暴露集合==契约全集"；`module-package-map.md` 随源码重生成 | **43 守卫全绿** · spotless 绿 · build 2m42s / **5028 / 0 / 4**（+2 常量一致性测试） |
| L3 | 2026-10-20 | **扩展清单守卫**：① 新增 `check-tool-registry.py`（第 44 条）——`agenttools` 的 `super(ToolDefinitions.TOOL_X)` ↔ `AgentToolBackends` 三个 switch 的 `case` 双向对齐、工具名值不重复；三条判据各配红态探针。② `check-route-policy.py` 扩 API-Key 策略表：每条 `registerGin` 必须命中控制器路由（未登记 = default deny 是有意语义，只查幽灵规则），205 条规则 0 幽灵；红态探针已验证。③ 两个技能控制器改名消除"s 之差"：`SkillCatalogController`→`SkillCatalogAdminController`（管理面 `/skills/catalog/**`）、`SkillsCatalogController`→`SkillPickerController`（选择器 `GET /skills`），路由不变；生成式文档与注释同步 | **44 守卫全绿** · spotless 绿 · build 2m16s / **5028 / 0 / 4** |
| L2 | 2026-10-20 | **embedding 单路径（先校验后统一）**：① 新增 `embedding.EmbeddingVectors`——OpenAI 族 `data[].embedding` 按 `index` 归位并校验条数/空向量/批内维度/越界/重复，非 OpenAI 族在 `ConcurrencyEmbedder` 出口统一校验条数与维度；测试 6 例（4 形态 + 2 出口）。② 删 `knowledge/client/EmbedderClient`（164 行）与 `EmbedConfig`（先降为"模型 id 标记"再删）；`ChunkVectorIndexer` pg 路径改走 `ModelRuntime.getEmbeddingModel`；`EmbeddingPort` 由 `modelruntime.application.ModelRuntimeEmbeddingAdapter` 实现；`EmbeddingModelAdapter`（wiki）同步改走运行时。**实施前取证**：直接删客户端会丢掉 pg 路径的响应校验（运行时 9 个 provider 只按响应顺序 append），故先补校验再切换 | **44 守卫全绿** · spotless 绿 · build 1m45s / **5029 / 0 / 4** · 金片零变化 |
| M1 | 2026-10-20 | **方法长度棘轮**：新增 `_method_lengths.py`（词法遮罩字符串/文本块/注释后再配对花括号；只认 4 空格类体声明，含包级方法）、`check-method-length.py`（R-a 新增/R-b 回涨 10 行/R-c 失效/R-d 理由/R-e 只收紧；`--write` 不新增条目）与 `method-length.baseline.json`（32 条）。**实测修正报告**：报告称 22 个 >150 行方法，漏掉包级方法，实为 32 个。红态探针：注入 162 行包级方法 → 红；删除后绿。接入 `run-guards.sh`（第 45 条）。**不设 300 行文件硬门**（沿用 600/30） | **45 守卫全绿** · 红态探针已验证 · 全量 build 5034 / 0 / 4（与 M2 同工作区） |
| M2a | 2026-10-20 | **钉钉适配器拆三刀 + 并发修复**：`DingtalkAdapter` 754 → 约 160 行，解析归 `DingtalkCallbackParser`（纯函数）、HTTP/token 归 `DingtalkApiClient`、卡片流归 `DingtalkCardStream`；流 key 改 `dt:<channelId>:<user>:<msg>`（无渠道时保持旧格式）；token 改单飞（并发未命中只打一次），`expireIn-300` 加 60s 下限，消除负 TTL。工厂传 `channel.getId()`；`DingtalkStreamClient` 改调解析器 | 钉钉 17 测试（+3：渠道隔离/单飞/负 TTL）· 45 守卫绿 · build 2m1s / **5034 / 0 / 4** |
| M2b | 2026-10-20 | **会话批量删除去 N+1**：`SessionRepository.getByIds`（软删+owner 范围一次取）替代逐条 `get`；`MessageSuggestionRepository.deleteBySessionIds` 一条 `IN` 替代逐会话删；`KnowledgeSearchService` 首轮缓存 KB，去掉同一 scope 的第二次 `findById` | 会话/建议仓储定向测试全绿 · 全量 5034 / 0 / 4 |
| M3a | 2026-10-20 | **SHA-256 收敛**：新增 `platform.security.Hashes`（sha256/hex/hexPrefix/sha256Hex[Prefix]/sha256Base64Url）；替换 19 处手工实现，contract 的 `FaqChunkMetadata` 保留为纯 JDK 例外；`PromptFingerprints` 分段 NUL 拼接改为 `\0` 转义（替换时误写真实 NUL，已修）。**实测修正报告**：报告称 18 处，实为 20 个文件 | SHA-256 `MessageDigest` 实现 **20 → 2** · Hashes NIST 向量 4 测试 · 45 守卫绿 · build 1m58s / **5038 / 0 / 4** |
| M3c | 2026-10-20 | **日志消毒/trim 单源化**：删 4 份 `sanitizeForLog`（UserService / McpMetadataService / ModelDebugController / PipelineLog），统一 `platform.security.LogSanitizer`；新增 `platform.text.UnicodeText.trim`，收纳 auth 与 RSS 的 Unicode 空白副本；`AuthController` 不再反向依赖 `UserService` 静态工具 | `sanitizeForLog` 4 → 0 · 45 守卫绿 · build 2m9s / **5034 / 0 / 4** |
| M3b | 2026-10-20 | **KB 活动审计共享记录器**：新增 `platform.audit.ActivityAuditRecorder`（租户兜底/操作者提取/details 字母序/best-effort；任务上下文按 outcome 映射 processingStatus）；FaqEntryCommandService / KnowledgeTagService / DataSourceSupport 三份 `recordKbActivity` 的实现收敛为薄委托；删除三处各自维护的 JSON/审计样板与无用 imports | 新增 4 例记录器测试（排序/suppressed/任务字段/租户兜底）· 45 守卫绿 · build 2m22s / **5042 / 0 / 4** |
| M4a | 2026-10-20 | **register 声明式事务**：`UserService.register` 加 `@Transactional(rollbackFor = Exception.class)`，删除\"先删用户再删租户\"的外层手写补偿（保留错误文案逐字符不变）；`AuthRegisterRollbackTest` 用 `@MockitoBean TenantMemberService` 注入成员行失败，断言 tenant/user 零残留。**红绿探针**：先删补偿、不加事务 → 测试红（行残留）；加注解 → 绿。`CreateTenant` 内层默认存储后端补偿未动（管理员创建路径仍共用） | 新增 1 例回滚测试 · 45 守卫绿 · build 2m10s / **5043 / 0 / 4** |
| M4b | 2026-10-20 | **事务边界约定**：`mechanisms.md` §5 改写为\"整轮问答不开事务、多表写才开\"，补 `register` 事务、best-effort 清理（会话删除/数据源逐条落库）与自调用失效三条规则；审计 mcp/storage 建单条写、session 清理 best-effort，未发现需补事务的明确缺口 | 文档同步 · 45 守卫绿 |
| M5a | 2026-10-20 | **Memory 开关单次求值**：新增 `MemoryScopeGate`（workspace→agent→subject 一次判定，返回 `(scope,cfg,ok,reason)`）；`ScopeState` 增 reason 并保留 3 参构造兼容旧测试；`enabledScope`/`scopeDisableReason` 改同一求值，删除两套并行实现；清 4 条孤儿节注释与失效 mapper 常量。开关测试补 `no_principal`/`workspace_disabled`/`agent_disabled`/`user_disabled` 原因断言 | 45 守卫绿 · build 1m55s / **5043 / 0 / 4** |
| M5b | 2026-10-20 | **Memory 写管线/模型解析提取**：新增 `MemoryWritePipeline`（写入清洗/脱敏/墓碑/去重/容量/常驻块/向量）与 `MemoryExtractionModelResolver`（显式配置→任务携带→工作区问答模型）；`MemoryService` 保留同签名门面委托（兄弟类与测试调用点零改动），770 → 440 行 | 45 守卫绿 · build 2m18s / **5043 / 0 / 4** |
| M6a-1 | 2026-10-20 | **FAQ 投影/查重抽类**：`FaqEntryProjector` 收编创建/更新/相似问三处尾块（原每处把同一 tag 查两次，现一次）；`FaqDuplicateChecker` 收编 72 行本地规则 + 单次 DB 查询。命令服务只留用例与编排，770 → 597 行；FAQ 金片零变化。批量字段更新（153 行）留 M6a-2 | 45 守卫绿 · FAQ 契约测试绿 · build 2m11s / **5043 / 0 / 4** |
| M6a-2 | 2026-10-20 | **FAQ 批量字段更新抽类**：`FaqBatchFieldUpdater`（byTag 一条 UPDATE / byId 逐条比对 / flag 位运算 / 引擎三态同步 / 活动审计），命令服务只留入口与事务编排，597 → 450 行。方法长度基线收紧 32 → 31（旧条目随搬迁失效） | 45 守卫绿 · FAQ 契约测试绿 · build 2m7s / **5046 / 0 / 4** |
| M6b | 2026-10-20 | **UserService 拆注册与登录空间**：`UserRegistrationService`（三步同事务，注册/重名/文案不变）+ `LoginTenantResolver`（preference→home→首个 membership + 失效自愈写库）；门面保留 `register` / `resolveLoginTenantId` 委托与 `generateTokensFor(Object)`。738 → 501 行；新增登录空间解析 3 例单测（偏好命中/失效回落/无 home 回落首个成员） | 45 守卫绿 · auth 全域测试绿 · build 2m7s / **5046 / 0 / 4** |
| M6c-1 | 2026-10-20 | **StorageFileResolver 去重与装饰器外提**：删 8 分支 provider 完备性 switch（110 行）+ 重复错误文案，云 provider 统一走 `FileServiceFactory`（local 特例保留）；嵌套 `DecoratedFileService` 提为 `ResourceCatalogFileService`（读/写/URL/资源登记），`env` 改包级供其复用。770 → 531 行；presigned 400 文案与 provider 构造语义不变 | 45 守卫绿 · 存储域/boot 测试绿 · build 2m17s / **5046 / 0 / 4** |
| M6c-2 | 2026-10-20 | **Storage 协作者拆分**：`StorageBackendLocator`（backend id → legacy alias → 默认后端 + stub 租户补全）、`StorageConfigProjector`（行 camel → 引擎 snake 改名 + 两族凭据解密）、`StorageEnvProvisioner`（env 快照 → System 行）；门面保留公开入口与委托，531 → 374 行。类名避开 contract 同名（`StorageBackendResolver`） | 45 守卫绿 · 存储域/boot 测试绿 · build 2m13s / **5046 / 0 / 4** |
| M6d | 2026-10-20 | **MCP 编解码/命名拆分**：`McpJsonCodec`（插入序 struct、enum 注入、调用解码、discovery failure、cursor base64）、`McpToolNaming`（ref 哈希、注册名、描述截断、摘要）；call site 全量改指新类，McpCatalog 只留提示词常量、DTO 巢居与目录状态机，723 → 543 行 | 45 守卫绿 · MCP 域/boot 契约测试绿 · build 2m8s / **5046 / 0 / 4** |
| M6e-1 | 2026-10-20 | **KnowledgeService 注释卫生**：类 javadoc 重写为当前门面/协作者/状态机；删除 3 处悬空 javadoc（duplicate mapper、同包开放、共享批量取的乱码说明），收敛多余空行；共享批量取注释改为“当前与 getKnowledgeBatch 同实现”。无逻辑变更 | 45 守卫绿 · 知识库核心契约测试绿 · build 2m9s / **5046 / 0 / 4** |
| M6e-2 | 2026-10-20 | **标签批量抽类**：`KnowledgeTagBatchService` 承载授权范围推导、一次标签读取、逐条删旧插新与 @Transactional；门面只留委托，不再叠事务。KnowledgeService 624 行；标签批量契约（KnowledgeOperations）绿 | 45 守卫绿 · 知识库契约测试绿 · build 2m17s / **5046 / 0 / 4** |
| M7 | 2026-10-20 | **命名与文档收口**：`overview.md` 增协作者命名判据（Service/Ops/Support/Adapter/Resolver 等，禁止 Default*Service/Manager/Helper）、`api/` = contract 端口实现说明、响应信封双轨登记（M8 待会签）；Memory/Knowledge 注释腐烂已在 M5a/M6e-1 清理 | 文档守卫绿 · 无代码变更 |
| H5b-fix | 2026-10-20 | **软删守卫档位纠偏**：H5b 的「存量 0 违规」是假绿——测试 `application.yml` 覆盖了主配置且未写该键，落回 `@Value` 默认 `alert`。把 `MybatisPlusConfig` 默认改 `enforce`（与主配置一致）后红出 13 条语句并逐条定性：补谓词 9 处（FaqChunk 5 / ChunkRepository / KnowledgeRepository / MessageRepository / UserRepository）、登记 6 条确需读已删行（selectImPlatform、KB/Knowledge/Chunk/Model 的 selectById、TenantMapper.selectById）、`knowledge_tags` 移出注册表（列存在但硬删，从不写）；另登记租户守卫的过期附件回收面。 | 全量 **test 绿**（boot 1349 例含新增红态面）· 待 spotlessCheck build |
| EMB-1 | 2026-10-20 | **嵌入建议开关联动**：渠道「推荐问题」定为总闸（开场 + 追问都受它控制）；公开配置新增 `agentStarterQuestionsEnabled` / `agentFollowUpQuestionsEnabled`（读智能体 `questionSuggestions` 的两个 enabled）；widget 侧开场与追问分别按「总闸 && 分闸」门控——智能体关闭追问时不再每轮发 ensure/get（此前白跑并留下 suppressed 行）；渠道开关文案 5 语言同步为总闸口径。 | 契约金片 `emb-pub-config` 24→26 键已签字 · 前端 type-check / 753 / i18n 11 全绿 · **45 守卫绿** · **5046 / 0 / 4** · build 2m13s · 真机：公开配置两开关正确，widget 加载只发 1 次 starters、追问请求 0、控制台 0 报错 |
| EMB-1b | 2026-10-20 | **懒创建空白态不再拉历史**：新建对话把 sessionId 清空后，消息区高度变化触发的滚动事件仍会走分页加载 ⇒ `GET /messages//load` 404。`useEmbedChatSession.getmsgList` 增加空 sessionId 早退（唯一收口）。 | 红态探针（撤守卫复现 1 条空会话请求 + 2 条控制台 404，恢复后 0/0）· 前端 type-check / 753 / build 绿 · 纯前端，未跑后端闸门 |
| EMB-1c | 2026-10-20 | **启动重复请求归因，换票单飞**：打开页曾见 config ×2 / exchange ×2 / load ×2——config 是宿主外观 + iframe 配置各一次（设计）；load 是存量句柄探测（limit=1）+ 正式历史（limit=20，设计）；exchange 是宿主 `iframe load` 与 `bootstrap_request` 各推一次 `provide_token`，iframe 两次都换 ⇒ 改为同票并发单飞 + 已换过的票迟到重推直接忽略。 | 真机时序（刷新前/后）：exchange **2→1**，config=2、load=2 保持（已说明来源）· 前端 type-check / 753 / build 绿 |
| X1 | 2026-10-21 | **Maven 骨架与依赖**：根 pom + 7 子 pom（contract/platform/model-runtime/retrieval-engine/agent-engine/domains/boot）；Boot BOM 接管 protobuf 4.35.1 / grpc 1.83.1 / neo4j 6.1.0 / sqlite 3.53.2.1（toml 的声明值被 BOM 覆盖，Maven 侧必须同口径）；protoc 对齐 4.35.1（3.25 生成的 `GeneratedMessageV3` 在 4.x 刷废弃警告，与 `-Werror` 相撞）；guava 钉 `33.6.0-jre`（否则 Maven 选 android 变体）；repackage 显式 exclude servlet-api（插件会打 provided）；迁移脚本同步到仓库根 `build/` + `spring-boot:run` 工作目录钉仓库根。 | `mvn -Dmaven.test.skip=true package` 绿 · `java -jar` 起服 **Started 22.0s** / `/health` 200 / 认证端点 401 · fat jar **189M = Gradle 189M** · 依赖差异 8 项全定性（Gradle 多 6 个 dom4j optional XPath 死重；Maven 多 okio 1.12 + kotlin-stdlib-common，okhttp 2.7.5 链路）· **45 守卫绿** |
| X2 | 2026-10-21 | **测试面等价**：夹具不搬文件 —— `build-helper` 把 `src/testFixtures/{java,resources}` 挂进 test 源集 + `maven-jar-plugin:test-jar` 产出 `-tests.jar`（拆独立模块会撞 `domains → domains-fixtures → domains` 的 reactor 循环，Gradle 能忍、Maven 不许）；`ContractPaths` 仓库根双候选（`settings.gradle.kts` / **带 `<modules>` 的父 pom**，不能只判 pom 存在）；`ArchitectureRulesTest` 排除四形态（`classes/java/test`、`target/test-classes`、`-test-fixtures.jar`、`-tests.jar`）；surefire `forkCount=2` + `DOCREADER_ADDR=""` + domains 时区 2g + boot 工作目录钉仓库根；`-Werror` 收窄到 `default-compile`（plugin 级配置会误伤测试编译）。 | `mvn test` **连跑两遍全绿** · **有效用例 5042 = Gradle 5042**（Gradle 5046−4 / Maven 5044−2；surefire 对 `@BeforeAll+assumption` 不计入跳过）· 45 守卫绿 · `./gradlew build` 2m2s 仍绿 · 暴露既有债：boot `FaqContractTest` 单 JVM 串行时共享 Spring 上下文走云存储分支失败（Gradle 4 分叉掩盖，待修） |
| X3 | 2026-10-21 | **质量闸门**：spotless-maven-plugin 3.10.4（ratchetFrom seed，includes 补 testFixtures）+ javadoc doclint 绑 verify 并**收紧为 `-Werror`**（Gradle 侧只告警）+ jacoco profile（prepare-agent/report）+ 45 守卫改绑 **boot 的 verify**（根 pom 的 verify 在 reactor 最前）+ 迁移脚本同步到仓库根 `build/`。修 4 个实测坑：surefire 必须 `@{argLine}` 晚绑定、`ContractBoundaryRulesTest` 补 `-tests.jar` 排除、`StorageRuntimeEnv` 静态快照恢复（s3 配置外溢）、守卫阶段位置。 | `mvn verify` **连跑两遍全绿**（有效用例 5042 / 45 守卫 / spotless / javadoc）· **三类红态探针各红一次**（乱序 import / `{@link NoSuchTypeXYZ}` / 游离子目录）· `-Pcoverage -pl contract verify` 出 `jacoco.exec` + HTML 报告 |
| X4 | 2026-10-21 | **切换收尾（Gradle 已删）**：6 守卫重写读数源（版本→根 pom properties、契约纯度→contract/pom.xml、文档事实→根 pom modules、测试汇总→surefire-reports、陈旧检测→target/classes、CI 注解→Maven 关键字）+ 11 处源码扫描补 `target/` 排除；脚本改命令（起服 `package + java -jar`、`ci-smoke` 读 `boot/target`、`acceptance`/`migrate-domain`/`audit-contract-fixtures`/`refactor-harness`）；CI 换 `setup-java cache: maven` + `mvn -B verify`；Dockerfile 换 `maven:3.9-eclipse-temurin-21` + `/root/.m2`；README/getting-started/HANDOFF/overview 操作口径全改（历史记录原文保留）；删 8 个 build.gradle.kts + settings + wrapper + build-logic。实测修正：`SourceRoots.repoRoot()`（第三处耦合）、lettuce 的 optional 传递差异、`-am spring-boot:run` 不可用。 | `mvn -B verify` 全绿（5042 + 45 守卫）· 起服 `ready` + `/health` 200 + 401 · `docker build` 成功 + 镜像自检 OK · 全量 verify 约 5–6 分钟 |
| N1 | 2026-10-21 | **上手路径断链**：README 与 getting-started 都缺「配模型」步骤，而系统不自带 LLM ⇒ 新用户照文档走必在第一次提问失败。新增 `config/builtin_models.yaml.example`（字段说明 + OpenAI 兼容 / 本地 Ollama 两种写法），两处文档插入 `cp` 步骤与三条关键日志的判读；`.gitignore` 忽略本体（防真实 key 入库）。用 `.example` 而非本体：Reconciler 对未设置的 `${VAR}` 原样保留，直接生效会往 models 表写无效凭据行。 | 文档与样例；45 守卫绿 |
| N2 | 2026-10-21 | **IM 渠道凭据加密**：credentials 是 jsonb，不能存裸 `enc:v1:` ⇒ `ImCredentialsTypeHandler` 包成 `{"enc":"enc:v1:..."}`，读时只认单键 `enc`；历史明文宽容读取、无需迁移，写回自动转密文；无密钥降级明文、轮换解不开返回 `{}` 并告警。坑：自定义 `@Select` **不读** `@TableField` 的 typeHandler（那是 MP 给内置方法生成的）⇒ 读路径补共享 `imChannelMap`，否则 toggle 把密文原样吐给前端。 | 新增 8 条单测 · IM 契约金片零变化 · `mvn -B clean verify` 绿（5042 + 45 守卫） |
| N3 | 2026-10-21 | **租户配置三列加密**：`storage/web-search/parser` 三列 jsonb 内**按字段名**加密敏感值（apiKey/secretKey/accessKey/token/password…），结构与明文一致 ⇒ 读方无感。新增 `JsonSecrets`（递归 + 子串匹配，宁可多加密不漏）+ `SecretJsonTypeHandler`（只挂在三列，不全局注册）。读兼容历史明文、写自动加密。踩到 Jackson 3 的两个废弃 API：`isTextual()→isString()`、`textNode()→stringNode()`（platform 的 `-Werror` 直接拦下）。 | 新增 8 条单测 · `mvn -B clean verify` 绿（5042 + 45 守卫） |
| N4 | 2026-10-21 | **Maven 尾巴清零**：① javadoc 声明从 7 个模块上移到根 `build/plugins`（实测 `packaging=pom` 会自动 "Skipping report goal"，不需要特判）；② boot 的 coverage profile 加 `report-aggregate`，跨模块报告落到根 `target/site/jacoco-aggregate`；③ `FaqContractTest` 加只参与上下文缓存键的属性 `ragagent.test.context=faq-import`，强制独占上下文（此前靠"静态快照恢复 + fork 分派"侥幸稳定）。 | `mvn -B clean verify` 绿（5042 + 46 守卫）· `mvn -B -Pcoverage verify` 出聚合报告（加载 5 个模块的 `jacoco.exec`） |
| P1.9a | 2026-10-21 | **schema 对账守卫**：新增 `scripts/check-schema-parity.py`（第 46 条守卫）—— 解析 `TestSchema` 的 Java 拼接 DDL 与 `migrations/versioned/*.sql`（含 `ALTER ADD COLUMN`），对账表名/列名。红态探针已验证（删 `chunks.video_info` 即红）。**按严格对账补齐 `TestSchema`**：新增 `skills` 表 + 8 张表 20 个列（sessions 15 / tenants 2 / chunks / custom_agents / embed_channels / knowledge_bases 2 / memory_item_embeddings / user_kb_pins），删掉「allow_memory 无实体映射，不建」的有意裁剪。 | 迁移 55 表 = 测试 55 表，列名一致 · `mvn -B clean verify` 绿（5042 + 46 守卫） |
| P1.9b | 2026-10-21 | **置顶的租户维度补齐**（修 P1.9a 发现的真缺口）：`UserKbPin` 加 `tenantId`（`@TableField("tenant_id")`）；`togglePin` 写入时填 `tenantId()`；`UserKbPinRepository` 的查询/删除改为按 `(tenant_id, user_id, kb_id)` 三条件（顺带让 `(tenant_id,user_id,pinned_at)` 索引用得上）；`TestSchema` 的 `tenant_id` 恢复为严格 `NOT NULL`（去掉临时 `DEFAULT 0`）。新增 `UserKbPinTenantIsolationTest`（2 条：写入落库的租户值、跨租户不可见且不可删）。 | 红态探针：去掉查询的租户条件 ⇒ `pinIsScopedToItsTenant` 立即失败 · `mvn -B clean verify` 绿（5042 + 46 守卫） |
| P1.4a | 2026-10-21 | **T4 工具装配守卫**：`check-tool-registry.py` 从 T1~T3 扩到 T4 —— 解析 `AgentEngineAssembler.buildTools` 的 switch（到 `default ->` 为止），判据用**包含关系**而非相等：`AgentToolBackends` 的每个 case 都必须被它覆盖。理由是装配 switch 还覆盖 7 个 agent-engine 内置工具（thinking / todo_write / shell_exec / read_file / 写改列沙箱），它们不在 AgentToolBackends 里；评估原文的「≡」按实测收敛为「⊇」。该 switch 的 `default` 只 `log.warn`，漏 case 的后果是运行期静默丢工具。 | 22 agenttools 实现 / 22 条分发 / 33 个常量 / 装配覆盖 29 · 红态探针：从装配 switch 删掉 `TOOL_WEB_FETCH` 的 case ⇒ T4 立即报错 · 46 守卫绿 |
| P1.11a | 2026-10-21 | **TenantInfo 双定义合一**：`api/auth` 与 `api/tenant` 各定义一份，`id` 一处 `string` 一处 `number`（后端本就是 `Long`）⇒ `stores/auth` 只能靠 import 别名区分。合并到 `api/tenant` 作唯一真源、auth 侧 re-export；`id` 统一 `number`，六处 `id: String(x.id) \|\| ''` 的旧兜底删除；`owner_id` 顺带改 camel 并撤掉 `crossFaceKeyContract` 的 BASELINE 豁免。 | vue-tsc 通过 · 755 测试全过 · build 32s |
| P1.11b | 2026-10-21 | **目录归位**（消除两个同名陷阱）：`views/platform` 实为登录后主布局壳（Menu + RouterView + 全局设置/命令面板/邀请铃铛/新手引导），与后端 platform 模块同名纯属巧合 ⇒ `views/layout`；`views/system` 在路由里**零引用**，12 个文件全是 `views/settings/Settings.vue` 的 tab 子组件（含 agent 域的 `SkillManagement`）⇒ `views/settings/panels`。只改目录与 import 路径，路由 URL / 菜单 / tab 顺序 / 组件逻辑全未动。顺带修 `UploadConfirmDialog.test.ts` 里用 `new URL('../../platform/index.vue')` 拼的硬编码路径（绝对路径搜索抓不到）。 | vue-tsc 通过 · 755 测试全过 · build 45s · git 全部识别为 rename（历史保留） |

| P1.16 | 2026-10-21 | **文档信任收口**（第四轮评估 §6-16）：`docs/README` 与 `overview` 的 352 份 package-info → 356；`TenantFilterGuard` 删掉指向已删文档 `persistence-tenant-filtering-evaluation.md` 的悬空引用与残缺的「默认档自 起」；`knowledge/task/package-info` 重写为「Redis @Primary + InProcess 回退」，并纠正 `KnowledgeTaskIdCodec` 不在本包；`IngestStage` 注明枚举顺序非依赖顺序、MULTIMODAL 只是 OCR 借用的 span 名；`platform-shapes.baseline` 修正 GrepChunksScoring（单域 agenttools、无 Jackson）与 ImageInfoMatchUtil（platform 姊妹件复用）两条失真文案；HANDOFF 域间边读数 51→27 类。 | 47 守卫绿（含受影响守卫逐个实跑） |
| P2.1 | 2026-10-21 | **卫生预算计数守卫（第 47 条）**：评估报告的「trim 私拷贝 39→72」实测是口径漂移（39=private-only 正则，72=放宽到全部 `String trimXxx(String)`）；为使回潮可验收，新增 `check-hygiene-budget.py` 固定三个可复现口径——>100 行方法 109、通用 trim 副本 11、≥8 参构造器 53，计数只许减，`--write` 拒绝上调。顺带修 `complexity-report.py` 的扫描根（原含已解散的 engine/common，漏 contract/platform/三 runtime，构造器只报 1 处而真实 38 处）。 | 红态探针：注入 101 行方法 ⇒ methodsOver100 109→110 立即红 · 47 守卫绿 · `mvn -B clean verify` 全绿（**5062 / 0 / 2**）· dev 后端已重启 |

| P1.5 | 2026-10-21 | **工具名单一来源（N5 后端半边）**：删除 `agent-engine/.../AgentToolNames.java`（第二份 20 常量表），`FileOps`/`GroundingPrompt` 改用 `contract.tool.ToolDefinitions`；`ToolCapabilities` 的 20 个能力表键、`ToolPolicy` 的 49 个句柄策略键与 4 个局部常量全部改引用 `ToolDefinitions`（键集前后机器比对一致）。守卫扩 T5/T6：T5 除 `ToolDefinitions` 外不得再出现「`TOOL_*` 常量 = 已知工具名」的第二份清单（值比对，长文案常量不误报）；T6 前端 `tool-capabilities.ts` 的键必须是后端已声明工具名（可少不可凭空多）。IM 显示名表与前端图标表保留为**展示映射**（未命中回落原名，不参与注册）。 | 定向 35 用例绿（ModelContextRecording 30 / ToolPolicy 4 / ToolCapabilities 1）· 红态探针：注入第二份 `TOOL_KNOWLEDGE_SEARCH` 常量 ⇒ T5 红；前端加 `probe_tool` ⇒ T6 红 · 47 守卫绿 · `mvn -B clean verify` 绿（5062 / 0 / 2） |

| P1.8 | 2026-10-21 | **域归属尾巴（N9）**：① approval 7 文件 + 9 测试/夹具整体并入 `com.ragagent.mcp.approval`，保留运行期唯一实现 `ToolApprovalGateFactoryImpl`（`McpWiring` 注入契约接口，包移动不影响装配）；能力分类删 `approval` 域与空 `policy` 种类 ⇒ **19 域 / 5 类**。② 两个 Skill 控制器合并为 `SkillController`：`GET /skills` 选择器与 `/skills/catalog/**` 管理面同源 `SkillCatalogPort`，路由与权限不变。③ 6 个 ollama 端点从 `initialization` 归 `model`，URL 改 `/api/v1/models/ollama/**`；`OllamaManageService`/`OllamaDownloadTaskStore` 迁 `model.service`；跨服务复用的 `bindObject/text/orEmpty/timeAsIs/isoTime` 下沉 `platform.web.JsonBodies`，避免 model→initialization 反向边。④ 连通性双头写进两个域 package-info：未保存表单预检（initialization）vs 已保存模型调试（model）。 | 定向绿：approval 5 类 56 用例 / APIKey 路由 16 / JsonBodies 1 / W5b HTTP 契约 4 · 47 守卫绿 · `mvn -B clean verify` 全绿（5062 / 0 / 2） |

| P1.6 | 2026-10-21 | **还乡复核 + 契约消费者守卫（N6 可执行部分）**：① `GrepChunksScoring` 从 `platform.knowledge` 回 `agenttools.knowledge`（消费方只有 `GrepChunksTool` / `KnowledgeSearchRanking`；仍复用 platform 的 `GrepChunkHit` 结果视图），platform-shapes 豁免撤 1 条。② 复核报告「11 个单域类回域、约 −1,500 行」：**不可直接执行**——`MemoryKeys` 与 `MemoryConfig`/`MemoryKinds` 相互引用，而 `MemoryConfig` 还被 auth 与 platform 测试消费；`ToolCapabilities` 虽只有 im 主码消费，但它是与 `ToolDefinitions` 并列的工具词汇且被前端镜像；`JsonValues`/`TextConv`/`DuplicateKnowledgeDetails` 是 contract 内部助手。③ 新增 `gen-contract-consumers.py`（第 48 条守卫）：每个 public 顶层契约类型必须至少有一个消费者（跨模块 import 或 contract 内同包引用）；`--check` 零消费者即红。 | 定向编译绿 · 红态探针：加一个无引用的 `ConsumerProbe` ⇒ 328→329 / 零消费者 1 立即红 · 48 守卫绿 · `mvn -B clean verify` 绿（5062 / 0 / 2） |

| P1.7 | 2026-10-21 | **QaRequestContext 跨线程读收口（N7 实战半边）**：`persistLastRequestStateAsync` 原先把整个可变上下文交给新虚拟线程，而主线程随后还要写 `userMessageID`/`assistantMessage`。改为在**调用线程**用 `lastRequestStateSnapshot` 定型 `SessionLastRequestState`（列表 `List.copyOf`），异步段只拿 `sessionId + 快照`；`QaRequestContext` 类注释写明字段的线程归属。复核其余异步段：`startAsyncRunner` 在落库之后启动，写入先于 `Thread.start`（happens-before 成立），附件解析由 runner 线程独占；因此未做 33 字段 builder 大改，只修真实敞口。 | 新增 3 条单测（快照不随上下文变更、快照列表不可变、null 列表归一为空）· 48 守卫绿 · `mvn -B clean verify` 绿（5065 / 0 / 2） |

| P2.2 | 2026-10-22 | **长方法实拆第一刀（Faq append 校验）**：`FaqValidateOps.validateAppendMode` 239 行 → 五个具名步骤（现有索引装载 / 标准问首遍 / 批次问题索引 / 相似问过滤 / 反例过滤 / 合并后校验），主方法只留编排与进度落库；拆出的 `ExistingIndex`/`StdQPass`/`PostValidation` 三个 record 承载中间态。**行为逐字保留**（错误文案、失败行序、进度写入点未动）。 | 定向 `FaqContractTest` 13 用例绿 · >100 行方法 109→**108**、>150 行 31→**30**；两个基线已 `--write` 收紧 · 48 守卫绿 · `mvn -B clean verify` 绿（5065 / 0 / 2） |
| C1 | 2026-10-22 | **contract 还乡第一批（T1 修正版）**：新增 `gen-contract-consumers.py --relocate`（反向闭包 + 目标域 + 阻断项），并修 `consumer_label` 在绝对路径上 `re.match` 失效、domains 消费一律归为笼统 `domains` 的口径 bug。按闭包判据回迁 29 类：approval 族 14 件 → `mcp.approval`；agent-engine 6 件 → `agent.prompt`/`agent.compaction`；`WikiConstants`/`TenantRowView`/`ModelViews`/`SourceTypes`/`PipelineUsedMemoryView` 各归其域；4 个会话 wire 载荷 → `session.dto`（snake 键与 Jackson 注解原样跟随）。删除零调用端口 `KnowledgeQaRunner` 组 3 件 + 死载荷 `AgentStepData`。留存 18 类（session 视图族 / knowledge.config / Ollama / memory 族）待前置解耦，清单见 `docs/internal/plans/contract-relocation-2026-10-22.md`。 | contract 328 → **295** 类型 · 定向 mcp 80 / agent-engine 45 / payload+SSE 86 全绿 · 全量 **5072 / 0 / 2** · 48 守卫绿 |
| C2 | 2026-10-22 | **P2 第一刀 + 其余结案**：`PipelinePorts.MessageService` 只被 session 实现/调用（chatpipeline 归位后的遗留契约），整接口内化为 `session.rag.PipelineMessageStore`；`PipelineMessageView`/`PipelineMessageImageView` 随之回 `session.dto`。其余 16 类逐条给证据结案：5 个 session 字段类是 `MessageView`/`SessionView`/`MessageSuggestionView` 的 wire 字段；knowledge.config 5 类随 H6；memory 4 类被 `MemoryConfig`/`PipelinePorts` 持有；Ollama/Vlm 是跨模块端口签名；WikiException/StorageUrlContext 解耦会改行为；CodePoints 是 `Tokens` 的内部助手。 | contract 295 → **293** 类型 · 管线录音测试 49 绿 · 全量 **5072 / 0 / 2** · 48 守卫绿 |
| C2f | 2026-10-22 | **软删谓词补漏（验收现场）**：导入文档后 finalizing 阶段 `ChunkRepository.countByKnowledgeIdAndChunkType` 的 `selectCount` 未带 `deleted_at` 谓词，被 `SoftDeleteFilterGuard` 拦截（Trace 报 `SELECT chunks 未带 deleted_at 谓词`）。补 `.isNull(Chunk::getDeletedAt)`，与同类注释「全部 SELECT/UPDATE 附加 deleted_at IS NULL」一致；新增 `ChunkRepositorySoftDeleteTest`（真库插入一活一删，断言只计 1）。 | 红态探针：移除谓词 ⇒ 用例 Errors=1 且报同一条守卫文案；恢复绿 · 全量 **5073 / 0 / 2** · 48 守卫绿 |

| P2.3 | 2026-10-22 | **长方法实拆第二刀（knowledge_search 输出）**：`KnowledgeSearchOutputFormatter.formatOutput` 227 行 → 四个具名片段：空结果文案（`emptyResult`）、单条 XML（`appendResultXml`，已见/新命中两路）、结构化 data（`formattedResult`）、单文档总块数（`totalChunksFor`）；主方法只留循环与统计装配。**XML 逐字保留**（属性顺序、转义、already_seen 注释未动）。新增 3 条特征测试（chunk XML/data、二次调用 already_seen、空结果文案）补上原先为 0 的直接覆盖。 | 定向 3 用例绿 · >100 行方法 108→**107**、>150 行 30→**29**；两个基线已收紧 · 48 守卫绿 · `mvn -B clean verify` 绿（5068 / 0 / 2） |

| P2.4 | 2026-10-22 | **指标面落地（N10 半边）**：boot 引入 `spring-boot-starter-actuator`，只暴露 `health/info/metrics`（env/beans/heapdump 等不开）；domains 引入 `micrometer-core`，在 `QaTurnExecutor` 记录 `ragagent.qa.turns` 与 `ragagent.qa.failures`（tag=mode）。端点复用现有认证链：**匿名 401、带 JWT 200**，不新增匿名运维面。 | 真机：`/actuator/metrics` 带令牌 200 / 匿名 401，`/actuator/health` 200 · 48 守卫绿 · `mvn -B clean verify` 绿（5068 / 0 / 2） |

| P2.5 | 2026-10-22 | **长方法实拆第三刀（wiki reduce）**：`WikiIngestReducePhase.reduceSlugUpdatesBody` 309 行 → `PageDraft` 承载页面/分类/拼装缓冲，主体只留编排；四个具名段：`openDraft`（查页/建页/分类）、`applySummary`、`applyRetracts`、`applyAdditions`、`generateContent`（模板渲染 + 句柄编解码）。**页面字段写入序、refs 合并、失败语义逐字保留**。 | 定向 `WikiIngest*Test` 209 用例绿（含 Reduce 14）· >100 行方法 107→**106**、>150 行 29→**28**；两个基线已收紧 · 48 守卫绿 · `mvn -B clean verify` 绿（5068 / 0 / 2） |

| P1.9 | 2026-10-22 | **storage 配置收口（N8a）**：① `/api/v1/tenants/kv/storage-engine-config` 的 PUT 改 **405**，文案指向 `/api/v1/storage-backends`；GET 保留一版只读兼容。② 新增 `StorageBackendProvisioner.provisionFromLegacy`：把旧 KV 段（含 COS/TOS 的 `secretId`/`accessKey` 键）翻成行面 generic 两键，凭据仍走 `StorageConfigCodec` 加密落库，`source=legacy` + `legacyAlias=true`。③ `LegacyStorageMigrationRunner` 启动时对有 KV、无默认后端行的租户自动迁移并回写默认 id；单租户失败隔离，迁移失败不阻断启动。 | 新增 3 条迁移测试（迁移/幂等/失败隔离）· TenantCatalogContractTest 10 用例绿（含 405 新金片）· 契约金片 1349→1346（删 4 条写金片 + 新增 1 条 405）· 48 守卫绿 · `mvn -B clean verify` 绿（5071 / 0 / 2） |
| P1.10 | 2026-10-22 | **websearch 配置收口（N8c）**：从 `contract.tenant.WebSearchConfig` 删除 `provider` / `apiKey`——凭据与 provider 归 `web-search-providers` 表；租户 KV 只留 maxResults、compression、blacklist、proxy、embedding/rerank、documentFragments。`TenantConfigRedaction` 去掉 apiKey 清空/preserve；`WebSearchTool` 不再从租户配置取内联凭据；执行面 `WebSearchService.WebSearchConfig` 的 provider/apiKey 保留给 L2 直接调用（`WebSearchRequest`），租户路径不再产生。 | `TenantConfigRedactionTest` 13 + `JsonContractRoundTripTest` 35 + 租户契约 10 用例绿 · 48 守卫绿 · `mvn -B clean verify` 绿（5071 / 0 / 2） |

| P1.11 | 2026-10-22 | **parser 配置归位（N8b）**：KB 级 `chunking_config.parserEngineRules` 此前 worker 固定传 `parserEngine=null`，UI 上的规则是空操作。`KnowledgeTextExtractor` 注入 `KnowledgeBaseRepository`，按文件类型解析**显式命中**的规则并传给 `DocReaderClient.read`；未命中保持服务端默认（不注入 ppt/pptx 的 markitdown 默认，避免改变既有行为）。`ChunkingConfigView.inactiveKeys` 删除 `parserEngineRules` 项，能力矩阵不再把它标成未生效。三层作用域写进 `ParserEngineConfig` javadoc：agent 上传首层 / KB 入库 / 租户历史兜底。 | 新增 3 条规则解析测试（命中/未命中/缺 KB）· 能力矩阵 5 用例绿 · 48 守卫绿 · `mvn -B clean verify` 绿（5074 / 0 / 2） |
| —（2026-10-24 补记） | 2026-10-22~24 | **C2f 之后 16 个提交的概要回填**（未逐批立户，细节见各提交信息）：① `d9f4020c1` **更名 RagAgent**——mcp 模块/命令/环境变量同步；② `f29bb72ac`/`9310eb7f5`/`abbf07907` 三连文档同步（项目/文档/邮箱切 pmbilly/ragagent、补六篇配置接入文档、域数量/IM 渠道/actuator 端点）；③ `5d04bb482`/`1ae44b84b`/`697914b08` CI Redis 测试沉淀为本机固定 dir 实例 + 连接级 PING 就绪；④ `323ce722c` 前端品牌资源与文案切 RagAgent（widget 脚本改名 `ragagent-widget.js`）；⑤ `f1df2705c` 发布渠道分组、创建按钮、LOGO、技能抽屉与加载态统一；⑥ `c131d4e78` **主应用四语言接入**（es-ES/pt-BR/th-TH/zh-HK，embed 面九语言）+ **SSE 断流兜底**（`streamErrorFrame`/`streamRecovery`，断连补拉历史）+ **会话删除修复**（含托管行批量删，`SessionServiceDeleteTest`）；⑦ `8c3ccffb8` 精简系统提示词，46 系金片（agent-46/46b、chat-pipeline-46c、modelcontext-46a）内容更新并重签 sha256；⑧ `ac027f474`/`cce0a34e1` 列表类工具历史保留正文摘录/完整输出，消除反复翻页（先 grep 后精读）；⑨ `60aa93a59` 工具执行收敛提示（证据足够即作答）；⑩ `ed19372eb` 引导批量读取 chunk、禁止逐条精读；⑪ `99f4f720e` currentTime 断言兼容 UTC 的 Z 偏移。**行为面提示**：⑦~⑩ 改变 agent 提示词与工具输出保留策略，属线格式内 contract 文本变更（金片已重签）。 | 46 系金片 sha256 重录 · 各提交独立绿 · 全量基线刷新见 §6（2026-10-24 实测） |

| Q0 | 2026-10-24 | **第五轮评估核实 + Q 系列立项**：逐项复核 `architecture-assessment-2026-10-24.md` 的数字与技术事实（48 守卫 / 19 域 / 40 Port / 326 契约 / 356 package-info / 52 文件 / 28 方法 / 2,483 主源 / 266 分支 / 38 构造器 / 前端 771 测试全对；`session/service` 27→29、构造器口径 38 vs 棘轮 53 两处更正）。**4 处重复已完成/已否决项**：`TurnLifecycle` 已实现终态单赢家（不建 Coordinator）、`KnowledgeService` 三分与 M/L 判据冲突、端口改名无收益、`VectorStoreConnectivity` 已存在；**5 处落法改写**：contract 不放 `runtimeFactory`、ChatManage 改用不可变快照而非 4 个 Policy、`QaRequestContext` 只收复制面、租户 KV Registry 降观察、Wiki 已有 `wikiDirectoryState` 起点。**基线异常**：工作区 4 份文档未提交删除（含 `known-gaps.md`），`check-reference-truth` 红 1 处 ⇒ 当前 47/48 守卫绿。产出 `docs/internal/plans/convergence-remediation-q-series.md`（Q0~Q11）。**Q0 收口（同日）**：用户拍板"删除的文件不要了"⇒ 4 份文档按删除提交；引用迁移（`api-response-convention` → `docs/api/README.md`、`known-gaps` → `HANDOFF 11.1`、`golden-fixtures` → `HANDOFF §7`、`migration-troubleshooting` → `getting-started.md` 常见失败）；`check-known-gaps.py` 指针改为 `HANDOFF 11` 并配红态探针；`docs/README` / `qa-flow` / `api/README` 同步。 | 核实：48 条守卫实跑 · 前端 `npm test` 771/0；Q0：`run-guards.sh` **48/48** · 10 处类注释指针迁移 · 守卫红态探针已验证 |

| Q1 | 2026-10-24 | **模型配置结构校验接线**：`contract.model` 新增 `ModelConfigValidator`（1 方法）+ `ModelConfigDraft`（provider/baseUrl/apiKey/modelName，纯 JDK）；`model-runtime` 实现 `ProviderModelConfigValidator`（复用 26 家 `Provider.validateConfig`，未知/空 provider 直接放行，不联网）；`ModelService.createModel/updateModel/updateModelCredentials` 三处保存路径统一调用，失败即 400 且不落库。`Provider` 注释改为当前事实，`HANDOFF 11.1` 第 19 条关闭。 | 新增 6 条定向测试（厂商规则 3 + ModelService 落库闸门 3）· 48 守卫绿 · `mvn -B clean verify` 绿（**5088 / 0 / 2**） |

| Q2 | 2026-10-24 | **问答 SQL 下沉 + 占位 Port 清理**：`SessionKnowledgeQaService` 删除 `DataSource` 字段/构造参数与手写 `SELECT`（标签 id 查询改为 `KnowledgeEntryReadPort.listKnowledgeIdsByTagIds`，`KnowledgeCatalogAdapter` 委托既有 `KnowledgeTagRepository.listKnowledgeIdsByTagIds`；`QaSearchTargets` 直取契约面）；删 `PipelinePorts` 中 4 个空接口（SessionService / ChunkService / WebSearchStateService / WebSearchProviderRepository）及测试桩，`QaWiring`/`WebSearchTempKbStateService` 陈旧注释同步；A7 裸 JDBC 白名单删除该 Service 条目。 | 48 守卫绿 · `mvn -B clean verify` 绿（**5088 / 0 / 2**）· 编译期确认无残留引用 |

| Q4 | 2026-10-24 | **向量库控制面/运行时单源**：`contract.vectorstore` 新增 `VectorStoreEngineRules` + `VectorStoreRequiredField` + `VectorStoreAddressField`；`VectorStoreConnectivity` 扩为 **`VectorStoreEngineCatalogPort`**（`probe` + `rulesFor`），实现类 `VectorStoreConnectivityProbe` 更名 `VectorStoreEngineCatalog`；`EngineFactory` 的 `EngineSpec` 增 `controlAddressFields` + `requiredFields`（运行时/控制面两套地址函数并列，qdrant 保留 `[host]:port` vs `host:port` 差异）；`VectorStoreConfigService` 两个 switch 删除，改为执行目录规则（未登记引擎：必填放行、SSRF fail-closed 文案不变）。 | 新增 4 条目录测试（9 引擎全覆盖 / 未知 null / ES 文案 / qdrant 形态）· 48 守卫绿 · `mvn -B clean verify` 绿（**5092 / 0 / 2**）· `VectorStoreContractTest` 3 用例（含 SSRF golden）绿 |

| Q5 | 2026-10-24 | **存储 provider 单一登记点**：新增 `storage.provider.StorageProviders`（9 条描述符：必填规则 / 端点+SSRF 策略 / 连通性形态 / 凭据字段名 / 旧 KV 段映射 / 运行时创建）。`StorageBackendService` 的必填、端点、探测三个 switch 删除；`FileServiceFactory` 变薄壳（`require(provider).creator()`）；`StorageConfigProjector` 凭据键、`DefaultStorageBackendProvisioner` 旧段映射与候选序、`ResourceCatalogService`/`FileServiceResolver` 的漂移数组全部改查表；`ConnectorFailure` 随登记点上移。platform 的 `StorageAllowList` / `StoragePaths` 因模块方向保留（前者与 `exposedKeys()` 同序，测试钉住）。 | 新增 5 条登记点测试 · 既有存储测试 45 条（domains 25 + boot 20）绿 · 卫生基线 methodsOver100 106→105 · 48 守卫绿 · `mvn -B clean verify` 绿（**5097 / 0 / 2**） |

| Q3 | 2026-10-24 | **请求段冻结 + fork 共享快照**：`rerankThreshold` 从 `TurnInputView` 移入 `RerankOutcome`（写者仍是 PluginRerank 降级路径）；新增 `TurnInputSnapshot`（40 字段，构造期防御拷贝），`ChatManage.freeze()` 在 setup 结束冻结，40 个请求段 getter 读快照、setter 冻结后抛 `IllegalStateException`；`cloneChatManage()` 不再 41 行逐字段复制，fork 共享快照只复制状态段。知识检索路径同样在触发前冻结。 | 新增 2 条冻结/独立性测试 · rag 域 38 条录制测试绿 · 48 守卫绿 · `mvn -B clean verify` 绿（**5099 / 0 / 2**） |

| Q7a | 2026-10-24 | **终态矩阵测试补齐**：新增 `TurnTerminalMatrixTest`（失败后到达的成功不改写 / 终态后不再进收尾段 / 8 线程 × 20 轮并发只有一个赢家）；连同既有 `TurnLifecycleTest`、`QaTurnFinalizerCommitGateTest`（双收尾只提交一次下游）、`QaTurnExecutorNormalFailureTest`、`QaSseOrchestratorTest`（error+done 退出）构成本批矩阵。**Q7b（H1b-2：stop watcher/轮询/3 把锁收拢与状态落库）仍待做**，未在本次动实现。 | 终态相关 11 条定向测试绿 · 48 守卫绿 · `mvn -B clean verify` 绿（**5102 / 0 / 2**） |

| Q7b-1 | 2026-10-24 | **H1b-2 第一刀：单锁 + STOP 单一 owner**。新增 `TurnMessageGate`/`TurnMessageHandle`；此前同一 assistant 行由三把互不排斥的锁保护（`AgentStreamBridge.mu`、`QaTurnExecutor` 的 `synchronized(am)`、`QaSseOrchestrator` 的 `synchronized(msg)`）⇒ 三者改为本轮共享的 `SseStreamContext.messageGate`，快答 reasoning 追加也纳入同一临界区。STOP 取消只由 stop watcher 发 `EVENT_STOP`，SSE 循环只负责 flush+写 stop 帧+退出（去掉重复 emit，新增测试钉住）。 | 新增 1 条 STOP 不重复 emit 测试 · 终态/桥接 17 条定向测试绿 · 48 守卫绿 · `mvn -B clean verify` 绿（**5103 / 0 / 2**）。**未做**：两个轮询（100ms SSE / 300ms watcher）合并（会改 stop 延迟或 100ms 节拍）与生命周期状态落库（需 schema 决策） |

| Q7b-2 | 2026-10-24 | **H1b-2 第二刀：单流泵 + 终态指标**。`QaSseOrchestrator` 的两条读流循环（SSE 100ms + stop watcher 300ms）合并为 `TurnStreamPump`：有客户端 100ms 写帧；写失败降级为 300ms 无头监控，继续检测 STOP/终态并只发一次 `EVENT_STOP`（取消不随连接丢失）。`setupSSEStream` 不再起 watcher；`QaTurnExecutor` 在 `sink == null`（steer follow-up）时启动无头泵。`TurnLifecycle` 增 `CANCELLED` 终态；新增 `ragagent.qa.terminal{mode,outcome}` 指标（只由终态赢家打点）。**不做**：生命周期中间态落库（终态已由 `completeMessageOnce` 落库；中间态只服务崩溃续跑，当前无此需求）。 | 新增客户端断开降级 + STOP 单次 emit 测试 · 终态/桥接 30 条定向测试绿 · 48 守卫绿 · `mvn -B clean verify` 绿（**5104 / 0 / 2**） |

| Q8a | 2026-10-24 | **前端流式类型基础**：新增 `src/types/chatStream.ts`（`ChatMessage` 已知字段 + `AgentEvent` + `BackendStreamEvent`），`ChatMessage` 不再整体 `Record<string, unknown>`；`normalizeStreamEvent` 在 SSE 入口归一（缺 `responseType` 丢弃、未知类型落 `unknown` 并保留原文、`content/done/id` 归一）；`useChatStreamHandler.processStreamChunk` 收 `unknown` 并使用归一结果；`steerStreamFork` 复用同一类型；`AgentStreamDisplay` 的事件流改为 `ChatMessage[]`。 | 新增 5 条归一测试 · `vue-tsc` 0 · `npm test` **776 / 0** · 48 守卫绿。**Q8b 未做**：`AgentStreamDisplay` 拆分（组件无测试挂载设施，需先补行为特征测试） |

| B269① | 2026-10-26 | **同名 `@JsonProperty` 摘除 104 处（record 面 + mixin 钉序面）**：GitLabClient 13 / FeishuApiTypes 45 / DocxBlocks 27 / ExtraFieldConfig 8 / Config 2 / queue payload `tracing` 2 / mixin `@JsonPropertyOrder` 钉序 7；顺手修 `AgentFinalAnswerData` javadoc `is_fallback`→`isFallback`。**G2 class 面（~110 处）实证回退**：临时序快照 harness 证明混改名 class 删同名即全类型字段序重排 + `AuthServerMetadata.issuer` 整键消失（setter-only 可见性）——序锚 doctrine 对 class 面无条件成立；record 面零漂移（序=声明序定理实证）。方案+审计读数：`docs/internal/plans/jackson-annotation-hygiene-b269.md` | 归一化快照 diff 零差 · 48 守卫绿 · 四模块 test 绿（contract-sync/json-key-case 零触碰）|

| B269② | 2026-10-26 | **冗余 `@JsonIgnoreProperties(ignoreUnknown=true)` 摘除 99 处（B8 重裁为全删）**：依据=Jackson 3 `FAIL_ON_UNKNOWN_PROPERTIES` 默认已翻转 + `WebConfig`/`JsonMappers.lenient()` 双路径显式禁用 + 8 处手工 mapper 审计 0 黄区；32 文件连带摘空 import；R 类 3 处保留 value 部分（`outputFiles`/`retractType`/`endpoint` 历史键屏蔽承重）；D 类死重 3 文件整删 | spotless 绿 · 48 守卫绿 · 六模块 test 绿 |

| B269③ | 2026-10-26 | **守卫收紧 + 存量假绿缺陷修复**：金丝雀暴露 `SourceRoots.backend()` 契约错配（调用方传 `"main/java"`、内部再拼 `/java`）⇒ `JsonPropertyHygieneTest` 与 boot `ArchitectureRulesTest`/`ClasspathResourcesTest` **自 2026-10-10 起空扫描假绿**；修三处传参唤醒，四模块真扫描零存量违规。`memberAfter` 多行注解感知（`tracing` 族漏检根因）；混改名整文件跳过细化（record 分量豁免）；`SourceRoots.MODULES` 扩 model-runtime+platform；唤醒后唯一新违规 `MemoryExtractPayload`（Go 队列事故原型，现为 record）language/tracing 同名注解——字节校验零差后删（累计 106） | spotless 绿 · 48 守卫绿 · domains+boot 全测试绿（唤醒的 Architecture 22/ClasspathResources 2/ContractBoundary 6 全过）· 事故原型载荷改前/改后字节零差 |

| B269④ | 2026-10-26 | **面收敛盘点（doc-only）**：清理后剩余 `@JsonProperty` 467 处全部分布在外部协议镜像（连接器 223/模型 73/OAuth 21/Langfuse 7）、SSE 契约 mixin 权威面（108，即终态）、jsonb 落库键（~16）、散点 REST DTO（~19，建议维持+新增执行 knowledge 规则）——**无下一批机械清理项**，防回流由真扫描守卫承担 | 盘点表落 `jackson-annotation-hygiene-b269.md` §④ |

（历史 B232~B267 与 v0.3.0 前段全部记录 → 归档 `docs/internal/handoff/archive/HANDOFF-v0.2.0-2026-10-16.md`；追溯：`git log --grep="(B###)"`）


## 13. 文档地图与归档

| 文档 | 用途 |
|---|---|
| `HANDOFF.md`（本文） | **唯一指挥面**：现状 / 批次表 / 判定标准 / 纪律 / 环境 / 方法论精要 |
| `docs/internal/plans/java-remediation-plan-2026-10-13.md` | 整改方案（目标结构/状态所有权/规则的出处） |
| `docs/internal/plans/java-remediation-implementation-plan-v2-2026-10-16.md` | **v0.3.0 执行计划（P/R 批次全部细节与验收）** |
| `docs/internal/plans/java-remediation-implementation-plan-2026-10-13.md` | v1 历史（36 批已完成；已标注归档） |
| `docs/internal/plans/java-remediation-implementation-plan-review-2026-10-13.md` | v1 实施评审（P0-3 模块提取立项出处） |
| `docs/internal/handoff/archive/HANDOFF-v0.2.0-2026-10-16.md` | v0.2.0 全文（结构规范/方法论全量/逐批记录） |
| `docs/internal/handoff/archive/HANDOFF-v0.1.0-2026-10-13.md` | v0.1.0 全文（远古追溯） |
| `docs/internal/handoff/records/batch-records.md` | v0.1.0 早期逐批记录（~B110） |
