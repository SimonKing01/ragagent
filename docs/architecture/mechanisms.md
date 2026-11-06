# 反直觉机制

这些地方"读起来应该有一个东西，实际却没有"或"看起来该这样改，实际不能"。每条按**现象 / 原因 / 去哪改**写。

## 1. 队列没有独立的 consumer

**现象**：搜"谁消费队列"找不到一个 `Consumer` 类。

**原因**：服务自己就是消费者：

- `KnowledgeProcessWorker implements KnowledgeProcessingQueue`（`domains/.../knowledge/task/KnowledgeProcessWorker.java:55`）；
- `DataSourceService implements DataSourceSyncHandler`（`domains/.../datasource/service/DataSourceService.java:90`）。

队列接口是给**跨域调用方**用的端口；实现类同时提供入队与执行，"方法即处理器"。

**去哪改**：入队与消费都在上述两个类；改重试/租约/崩溃回收时看对应 `*TaskQueue` 与 `*Wiring`。

## 2. 五套队列，各有 In-Process / Redis 双实现

| 队列 | 接口 | 进程内 | Redis |
|---|---|---|---|
| 分块抽取 | `ChunkExtractTaskQueue` | `InProcessChunkExtractTaskQueue` | `RedisChunkExtractTaskQueue` |
| 问题生成 | `QuestionGenerationTaskQueue` | `InProcessQuestionGenerationTaskQueue` | `RedisQuestionGenerationTaskQueue` |
| Wiki 摄取 | `WikiIngestTaskQueue` | `InProcessWikiIngestTaskQueue` | `RedisWikiIngestTaskQueue` |
| 记忆蒸馏 | `MemoryExtractTaskQueue` | `InProcessMemoryExtractTaskQueue` | `RedisMemoryExtractTaskQueue` |
| 数据源同步 | `DataSourceSyncTaskQueue` | `InProcessDataSourceSyncTaskQueue` | `RedisDataSourceSyncTaskQueue` |

**原因**：单实例默认用进程内；多实例/崩溃回收用 Redis。选择方式是 `@ConditionalOnProperty(prefix="<域>", name="redis-enabled")` + `@Primary` 覆盖无条件的 `@Component` 实现——**不是** `@Profile`。

**去哪改**：`KnowledgeRedisWiring`、`MemoryTaskQueueWiring`、`DataSourceTaskQueueWiring` 与 `WikiRedisWiring`；开关见 `.env.example` 的 `*_REDIS_ENABLED`。打开后启动时会 ping 一次 Redis，连不上直接失败（不静默退化）。

## 3. embedding 的取径不止一条

**现象**：索引侧与查询侧的嵌入入口不同：`domains/.../knowledge/index/ChunkVectorIndexer`、`retrieval-engine/.../HybridSearchService`、`model-runtime/.../embedding/`（`EmbedderFactory` / `BatchEmbedder` / `ConcurrencyEmbedder` / `EmbedderPooler`）各有一段。

**分叉点**在 `ChunkVectorIndexer.indexAndStore`：

- KB **绑定了外部 vector store**（`vectorWrites.boundEngine(...) != null`）→ 走引擎口：批量、超时、重试由引擎自己决定；
- **未绑定**（本地 pg 直连）→ `modelRuntimeAssembler.getEmbeddingModel(...)` 拿 `Embedder`，按 `BatchEmbedProperties.embedSize` 分批 `batchEmbed` 后落 `IndexRow`。

查询侧：`HybridSearchService` 走契约端口 `EmbeddingPort`（实现是 model-runtime 的 `ModelRuntimeEmbeddingAdapter`）；外部引擎在自己的 `batchIndex` / search 内部嵌入。

**原因**：外部引擎的批大小、超时、重试语义由引擎决定；本地 pg 保留直连路径，避免为了"统一"引入一层不必要的工厂。

**去哪改**：`ChunkVectorIndexer`、`HybridSearchService`、`model-runtime` 的 `embedding` 家族；不要只改其中一条而假定另一条也会变。

## 4. 全仓 0 处 `@Scheduled`

**现象**：`grep @Scheduled` 为空，容易得出"没有定时任务"。

**原因**：定时/周期行为是自建调度或队列驱动：

- `domains/.../datasource/task/Scheduler.java`：数据源同步的计划触发；
- knowledge / wiki / memory 的 worker：队列驱动 + 租约/重试；
- `boot/.../StartupTaskRecovery.java`：启动时回收未完成任务。

**原因**：多实例下 `@Scheduled` 会在每个副本各触发一次；自建调度要配合 Redis 锁/选主/去重。

**去哪改**：先确认是"单实例定时"还是"多实例协作"；后者看 `*RedisWiring` 与 `Scheduler`。

## 5. 事务边界：整轮问答不开事务，多表写才开

**现象**：`domains/.../session` 没有 `@Transactional`；knowledge 的 `service/management/`（含统一删除回收的 `KnowledgeRecycleService`）与 wiki 的仓储层有；`auth` 只有 `UserRegistrationService.register` 一处（租户→用户→Owner 成员行，`UserService.register` 是它的纯委托门面）；另有少量 `TransactionTemplate`（`StorageBackendService`、`VectorStoreConfigService`）。

**原因**：一轮问答横跨 LLM 流式输出（数秒到数分钟）、外部调用与 SSE。把整轮包进一个事务会长时间持锁，且外部副作用无法回滚。因此：

- 单条消息的落库/状态推进各自成事务；
- 失败路径由 `QaTurnFinalizer` 手工补偿（提交/回滚/后续轮次交接）；
- 需要原子性的多表写才用 `TransactionTemplate`；
- 清理类动作（会话删除、数据源同步的逐条落库）刻意 **best-effort**：单条失败只记日志/留待重试，不因一条失败回滚整批。

**规则**：

1. 不要给 `QaTurnExecutor` / `SessionKnowledgeQaService` 加一个大 `@Transactional`；它会把流式期间的所有读也卷进同一个连接。
2. 多表一致性用 `@Transactional(rollbackFor = Exception.class)`；best-effort 清理不要加（失败语义会从"尽力"变成"整批回滚"）。
3. 同类自调用（`this.method()`）上 `@Transactional` 不生效；需要时用独立 bean（参照 `MemoryTxTemplate`）。

**去哪改**：`UserRegistrationService.register`、`QaTurnFinalizer`、各域 repository、`StorageBackendService`。

## 6. 身份不随虚拟线程传播

**现象**：`TenantContext` 是 ThreadLocal；虚拟线程/异步任务里 `currentTenantId()` 为空，查询静默 0 行。

**原因**：JDK 虚拟线程不继承 ThreadLocal（即使配置了 inheritable，也不适合请求级身份）。必须在边界显式快照：

```java
TenantContextSnapshot snap = TenantContextSnapshot.capture();
Thread.ofVirtual().start(() -> TenantScope.run(snap, () -> {
    // 这里能读到捕获时刻的租户/身份；退出时自动恢复原上下文
}));
```

全仓 28 个 `capture()` 站点已统一收口到 `TenantScope`（`run` / `supply` / `callable` / `runnable`），覆盖 domains / agent-engine / retrieval-engine / model-runtime / platform / boot，包括 `QaSseOrchestrator`、`QaTurnFinalizer`、`QaTurnExecutor`、`SteerSinkBridge`、`MessageSuggestionService`、`ActPhase`、`EventBus`、`HybridSearchService` 等。

**去哪改**：`platform/.../access/TenantScope.java`；守卫 `scripts/check-tenant-scope.py` 拦裸 `.replay()` 与未走 TenantScope 的 `capture()`。

## 7. StreamManager 是 append-only，SSE 靠 100ms 轮询

**现象**：生成侧写事件与输出侧读事件不是同一个线程；日志时间戳与帧顺序对不上。

**原因**：

- `AgentStreamBridge` 订阅 16 种事件，按到达序 `appendEvent(...)`；
- 主链路由 `TurnStreamPump` 一个循环读流：有 SSE 客户端时 100ms；客户端断开后降级为 300ms 无头监控（继续检测 STOP/终态，取消能力不丢）；
- 断线续传走 `SessionStreamController` / `SseFrameWriter` 的另一条 100ms 轮询（`POLL_INTERVAL_MILLIS = 100`）；
- `StreamManager` 只有 append/get，没有"推送"；`complete` 是结束信号；
- steer（运行中转向）走独立子列表，永不混进用户可见的 SSE 流。

**排障规则**：以 `messageId + event id/offset` 为唯一事实，不要用日志顺序推断帧顺序；断流检测靠写失败（`IOException`），不是连接回调。

**去哪改**：`platform/.../stream/StreamManager.java`、`AgentStreamBridge`、`TurnStreamPump`、`SessionStreamController`。

## 8. 新增 `@ConfigurationProperties` 可能"静默不生效"

**现象**：新建了一个 `@ConfigurationProperties` 类，启动不报错，但值一直是最小默认值。

**原因**：`RagAgentApplication` 用的是**白名单扫描**，不是 `com.ragagent` 根包：

```java
@ConfigurationPropertiesScan({"com.ragagent.config", "com.ragagent.platform.settings", ...})
```

类不在列表里就不会被绑定。

**去哪改**：`boot/.../RagAgentApplication.java` 的注解；新增属性类时把包加进去。不要改成根包扫描——会把一批未注册的配置类一并绑定，属行为变化。

## 9. 数据访问有两套写法，选择规则是确定的

| 写法 | 数量 | 适用 |
|---|---|---|
| MyBatis-Plus `Mapper`（`*/mapper/*Mapper.java`） | 48 | 单表 CRUD、条件查询 |
| `Repository`（`*/repository/*Repository.java`） | 43 | 跨表、方言差异（PostgreSQL/SQLite）、事务模板、复杂聚合 |

**选择规则**：单表且无方言分支 → `Mapper`；出现跨表、方言判断或需要事务模板 → `Repository`。

**三条硬规矩**：

1. **软删条件显式写**：每条查询显式加 `deleted_at IS NULL`，不用 `@TableLogic`（datetime 逻辑删除值在 MyBatis-Plus 各版本行为敏感）。
2. **controller 不得直接注入 Mapper**：HTTP 面只编排，数据访问在 service / repository。由 `check-controller-purity.py` 强制（当前违例 0）。
3. **软删谓词有机器探测**：`SoftDeleteFilterGuard`（25 张带 `deleted_at` 且真按软删用的表，默认 enforce）在 SELECT 主查询缺谓词时直接红；确需读已删行（来源判定/恢复/对账/管线判定）在 `ALLOWED_STATEMENTS` 登记理由。`knowledge_tags` 虽有该列但从不写（硬删），不入注册表。

**去哪改**：单表查询改 Mapper；涉及方言或跨表时在 `repository/` 加方法，别在 service 里拼两套 SQL。

## 10. 可选 Bean：缺失时的行为一览

全仓 77 处 `ObjectProvider.getIfAvailable()`——IDE 在注入点只看到接口，所以"缺 bean 时会发生什么"必须查表，不能靠猜。

| 可选 Bean | 缺失后果 | 有日志？ | 依据 |
|---|---|---|---|
| `StreamManager`（Redis 版） | 退化为进程内 Memory 版：SSE 只在当前副本可见，跨实例 `/stop` 失效 | 启动日志 | `StreamManagerConfig` |
| `SsrfGuard`（IM 出站） | 传给 IM adapter 工厂为 `null`，出站请求不做 SSRF 校验 | 无 | `ImAdapterWiringConfig` |
| knowledge 四个 Redis 队列 | 开关关闭 = 用 InProcess（单实例语义）；开关打开但连不上 = **启动失败**（不静默退化） | 启动日志 | `KnowledgeRedisWiring` 类注释 |
| 队列 handler（`DataSourceSyncHandler` 等） | 任务被丢弃，记 **ERROR**（非静默） | ✅ ERROR | `RedisDataSourceSyncTaskQueue` |
| 富化服务（`WikiImageEnricher` 等） | `getIfAvailable(WikiImageEnricher::identity)` 回退空实现，走"端口缺席"退化分支 | 部分 | `WikiIngestService`（`ObjectProvider` 注入） |
| `WikiActivityAudit` | 审计写入退化为一条 debug 日志 | ✅ debug | `contract/audit/WikiActivityAudit` |

**读代码时的判断顺序**：看到 `getIfAvailable()` → 查本表 → 表里没有 → 看该行 3 行内有没有 `null` 判断与日志。

## 11. `runtime` 包的四种含义

`*/runtime` 不是同一层，读代码前先看它属于哪一种：

| 含义 | 例子 | 判据 |
|---|---|---|
| **观测门面** | `knowledge/runtime/KnowledgeTrace`、`memory/runtime/MemoryTrace`、`session/runtime/SessionTrace` | 只把 trace 载体注入任务载荷 / 开 Span；不含业务逻辑 |
| **技术实现外提** | `memory/runtime` 的队列 + wiring、`websearch/runtime/WebSearchTempKbStateService` | 把技术实现从用例层移出，避免 service ↔ 实现成环 |
| **域共享内核** | `session/runtime/SessionModels` | 域内多个 service 共用的模型装饰器集合 |
| **业务运行时** | `im/runtime`（命令集 / 格式化 / 工具展示） | 承载真正的渠道业务逻辑 |

**规则**：

1. 新增 `runtime` 包必须在 `package-info` 首句写明属于上表哪一义；
2. 只需"注入 trace 载体"时用 `platform.tracing.TraceInjector`，不要新建 16 行门面（datasource / wiki 的两个已合并）；
3. 需要自有 Span 生命周期（start / attachTraceparent）才建域门面（`SessionTrace` / `KnowledgeTrace` / `MemoryTrace`）。

## 相关文档

- 调用链：[`request-path.md`](request-path.md)
- 管线段落：[`rag-pipeline.md`](rag-pipeline.md)
- 模块总览：[`overview.md`](overview.md)
