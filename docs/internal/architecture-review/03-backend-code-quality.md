# 后端生产代码质量评估报告

- 评估人：贝洛奇（后端工程师，MVP 开发专家团）
- 评估对象：/Users/billy/ragagent 后端生产代码（domains / platform / contract / boot / model-runtime / retrieval-engine / agent-engine）
- 评估性质：只读评估，未修改任何现有代码
- 评估日期：2026-10-20

---

## 1. 结论摘要

**整体 verdict：conditional-pass（架构纪律优秀，文件治理与事务健壮性存在系统性欠账）**

这个代码库不是典型的"AI 生成烂摊子"。它在**分层纪律**上达到了商业级水准——而且是用测试强制的，不是靠自觉：

- 51 个控制器中，**0 个** import 任何域的 `repository` / `mapper` 包（实测，见 §4.1）；
- 265 个 service 目录文件中，**0 个** import **其他域**的 `repository` / `mapper` 包（实测，见 §4.1）；
- `boot/src/test/java/com/ragagent/arch/ArchitectureRulesTest.java`（1135 行，22 个 ArchUnit 测试）把分层反向依赖、Mapper 包归属、禁裸 `System.getenv`、禁懒注入、JSON 工厂统一等规则做成了**编译期可执行的棘轮**（`noLayerInversions` / `mapperInterfacesLiveInMapperPackages` / `rawJdbcOnlyFromWhitelist` 等，行 563-1120）；
- 注释密度与质量异常高（大量 javadoc 解释"为什么这样写"而非"写了什么"），
  测试约 13.5 万行 vs 生产 30 万行，测试/生产比约 0.45。

但存在五个系统性欠账，按严重度排序：

1. **大文件治理失败**：生产代码 2436 个文件中 **301 个超过 300 行**（12.4%），这些文件承载 **142,126 行 = 全部生产 LOC 的 47.3%**。最大 778 行。这不是个别文件失控，是结构性的。
2. **门面-协作者模式退化成了"委托样板 + 孤儿注释"**：MemoryService 770 行中约 175 行（23%）是单行委托；KnowledgeService 711 行中约 35 个方法是单行委托，且残留 4 处无主注释（§3）。
3. **事务策略跛脚**：domains 生产代码共 **22 处 `@Transactional`，只集中在 wiki/knowledge/memory 三个域**；auth 的注册流程（`UserService.register`，`domains/.../auth/service/UserService.java:432-521`，90 行）用手写补偿代替事务，mcp/session/storage/im/datasource 等多写域零声明式事务。
4. **跨模块重复未下沉**：SHA-256 手工实现 **18 处**（5 个模块）；`TenantContext.currentTenantId()` 调用点 **146 处 / 87 文件**；显式软删过滤 `deleted_at IS NULL` **约 160 处**；`BizException` 抛出点 **862 处**，其中 **534 处在 controller 层**（含 controller 包内协作者）——HTTP 层承载了大量本属业务层的校验。
5. **N+1 查询已实物可见**：`SessionService` 3 处循环内单条查询/删除（§5.4）；`ChunkAccessGuard` 树遍历逐层查库；`KnowledgeSearchService` 逐 scope 查 KB。

---

## 2. 量化总览

### 2.1 规模基线

| 指标 | 数值 | 来源 |
|---|---|---|
| 后端 Java 文件总数 | 2,957 | find 统计（排除 frontend/build） |
| 生产 main 文件 / LOC | 2,436 / 300,433 | wc -l 全量 |
| 测试 LOC（src/test + testFixtures） | 141,293 | 同上 |
| 控制器数量 | 51（与团队认知一致） | `*Controller.java` 统计 |
| service 目录文件数 | 265 | find |
| package-info.java 覆盖 | 249 / 261 目录（95.4%） | find，README 声称基本属实 |
| mapper 接口 | 36 个，约 270 条注解 SQL | Grep 统计 |
| MyBatis-Plus BaseMapper 使用 | 39 文件 | Grep 统计 |
| mapper XML | 0（SQL 全在注解/Java 常量） | find |

### 2.2 大文件全景（>300 行，仅生产代码）

| 指标 | 数值 |
|---|---|
| >300 行文件数 | **301（占生产文件 12.4%）** |
| 这些文件的总 LOC | **142,126（占生产 LOC 47.3%）** |
| >500 行文件数 | 17 |
| >700 行文件数 | 13 |

按域聚合（>300 行文件数 / 该域代表文件）：

| 域 | 数量 | 域 | 数量 |
|---|---|---|---|
| session | 38 | mcp | 16 |
| knowledge | 33 | agenttools | 15 |
| datasource | 32 | auth | 12 |
| retrieval-engine | 29 | storage | 8 |
| wiki | 22 | websearch | 4 |
| im | 19 | agentmanagement | 3 |
| model-runtime | 18 | system / model / initialization / embedchannel | 各 2 |
| memory | 16 | vectorstore / approval | 各 1 |

最大的 17 个生产文件（>690 行）：

| 行数 | 文件 |
|---|---|
| 778 | domains/src/main/java/com/ragagent/storage/fileserve/StorageFileResolver.java |
| 770 | domains/src/main/java/com/ragagent/memory/service/MemoryService.java |
| 770 | domains/src/main/java/com/ragagent/knowledge/service/faq/FaqEntryCommandService.java |
| 761 | domains/src/main/java/com/ragagent/im/runtime/ToolDisplay.java |
| 758 | retrieval-engine/src/main/java/com/ragagent/retrieval/HybridSearchService.java |
| 758 | model-runtime/src/main/java/com/ragagent/modelruntime/adapter/provider/chat/anthropic/AnthropicChat.java |
| 754 | domains/src/main/java/com/ragagent/im/channel/dingtalk/DingtalkAdapter.java |
| 748 | domains/src/main/java/com/ragagent/wiki/service/ingest/WikiIngestCitePipeline.java |
| 740 | model-runtime/src/main/java/com/ragagent/modelcontext/ModelOutput.java |
| 738 | domains/src/main/java/com/ragagent/auth/service/UserService.java |
| 729 | domains/src/main/java/com/ragagent/wiki/service/page/DefaultWikiPageService.java |
| 725 | domains/src/main/java/com/ragagent/memory/service/MemoryExtractionService.java |
| 724 | domains/src/main/java/com/ragagent/embedchannel/service/EmbedChannelService.java |
| 723 | domains/src/main/java/com/ragagent/mcp/tool/McpCatalog.java |
| 719 | domains/src/main/java/com/ragagent/datasource/connector/feishu/core/DocxBlocks.java |
| 711 | domains/src/main/java/com/ragagent/knowledge/service/management/KnowledgeService.java |
| 709 | agent-engine/src/main/java/com/ragagent/agent/AgentEngine.java |

> 说明：测试侧大文件更多（最大 JsonContractRoundTripTest 1409 行、ArchitectureRulesTest 1135 行），本报告聚焦生产代码，测试文件仅作背景。

---

## 3. 大文件深读（7 个个案）

深读方法：全文通读 + 结构切分 + 根因判定 + 拆分方案。覆盖任务要求的 7 个目标文件。

### 3.1 StorageFileResolver（778 行）——职责混杂：一个类里住了 8 个内聚主题

文件：`domains/src/main/java/com/ragagent/storage/fileserve/StorageFileResolver.java`

**结构切分**（按行号）：

| 区块 | 行号 | 行数 | 主题 |
|---|---|---|---|
| 类 javadoc（自述三职责） | 30-54 | 25 | 文档 |
| backend 解析（resolveBackend/hydrateTenantStorage） | 98-202 | 105 | A. 存储后端解析 |
| provider 完备性 switch（8 家云） | 204-334 | 131 | B. 配置校验分支膨胀 |
| 写面解析（resolveProviderService/buildProviderRaw） | 352-430 | 79 | C. 原始 provider 解析 |
| 实例行→引擎配置投影+凭据解密 | 451-549 | 99 | D. 配置投影/密钥解密 |
| env 快照装配 | 551-582 | 32 | E. 环境变量→行 |
| 租户回落编排 | 584-607 | 24 | F. 回落策略 |
| resource catalog 装饰器（嵌套 record） | 609-764 | 156 | G. 装饰器+mime/哈希工具 |

**根因判定：职责混杂（主）+ 分支膨胀（次）+ 工具函数驻留（次）**。类 javadoc 自己承认"三个职责"（行 30-40），实际是 8 个。B 区是典型分支膨胀：8 个 provider 分支结构近同（各查 4-7 个必填字段），错误文案硬编码在 switch 里，且与工厂 `FileServiceFactory` 的校验文案**重复**（javadoc 行 336-343 自述"完备性校验的文案由工厂产出……与上面各分支的字符串一致"——同一规则两处维护，漂移风险）。G 区把 sha256Hex/probeMimeType/resourceKind/baseName 四个纯工具塞进嵌套 record。

**拆分方案**（公开方法签名不变，无契约变更）：

1. `StorageBackendResolver`：A + F（backend 解析与回落编排）；
2. `StorageProviderSpec` + `ProviderConfigValidator`：B 的 switch 改为声明式 spec 表（每 provider 一行：必填字段 + env 回落键），消灭 8 个近同分支与文案重复；
3. `StorageConfigProjector`：D（行→引擎配置、按 provider 族改键名、凭据解密——这是曾出过静默丢凭据 bug 的高危区，javadoc 行 502-505 有历史记录，值得独立文件和独立测试）；
4. `ResourceCatalogFileService`：G 提为顶层文件，mime/kind/哈希工具移入 `ResourceFileSupport`；
5. `StorageEnvProvisioner`：E。

**证据**：行 224-333（8 分支 switch）；行 469-541（投影+解密）；行 697-763（工具函数）；行 30-54（自述多职责）。

### 3.2 MemoryService（770 行）——门面委托样板占 23%，残留 4 处孤儿注释

文件：`domains/src/main/java/com/ragagent/memory/service/MemoryService.java`

**结构切分**：

| 区块 | 行号 | 行数 | 性质 |
|---|---|---|---|
| 协作者单行委托（topicWasForgotten…searchMemory） | 120-294 | **175** | 纯委托样板 |
| workspaceConfig 加载 | 296-328 | 33 | 配置门控 |
| enabledScope / scopeDisableReason / memoryAvailable | 330-406 | 77 | 三层开关（**两套并行实现**） |
| touchAsync（虚拟线程） | 428-448 | 21 | 异步使用追踪 |
| 写路径核心（writeReplacing 等） | 450-615 | 166 | 真正的用例 |
| enforceCapacity / rebuildBlock | 638-685 | 48 | 容量维护 |
| 抽取模型解析 | 695-744 | 50 | 模型选择 |
| 兄弟类访问器 | 748-768 | 21 | 包级辅助 |

**根因判定：门面跛行（协作者模式只拆了实现没拆门面）+ 关注点残余混杂**。协作者（MemoryCatalogOps/MemoryInsightOps/MemoryRecallOps）已在同级包拆分——方向正确——但门面为保 API 稳定保留 30 个单行委托，占全文件 23%。更重的两个问题：

1. **`enabledScope`（338-365）与 `scopeDisableReason`（371-395）是同一三层开关的两份并行实现**（workspace 开关 → agent 开关 → subject 开关）。javadoc 行 397-403 明确要求两者"绝不能有分歧"，但没有任何机制保证——这是典型的**沉默逻辑错误温床**：改一处忘另一处，`memoryAvailable()` 与禁用原因的解释会悄悄分叉，且不报错。
2. **孤儿注释**：行 687-693 连续 4 个节标题注释（"记忆管理器（列表/主题/文档/CRUD）""检索条件化""按需查找""抽取模型的解析"）下面**没有任何代码**——是拆出协作者后没清理的尸位。行 570-573 还有一个双 javadoc 叠放。

**拆分方案**：
1. 删 175 行委托样板：让 MemoryController 与 chat_pipeline 直接注入三个协作者（它们本就是包级可见的 Spring 组件即可，需把 `new MemoryXxxOps(this)` 构造改为 Bean 注入——这是**行为等价**重构，无契约变更）；
2. 提 `MemoryScopeGate`：把 enabledScope/scopeDisableReason 合并为单一 `evaluate()` 返回 `(scope, cfg, ok, reason)`，一处实现两处消费，从根上消灭分叉；
3. 提 `MemoryWritePipeline`（writeReplacing + findContainedDuplicate + statusForWrite + enforceCapacity + rebuildBlock）与 `MemoryExtractionModelResolver`（extractionModelId + workspaceChatModelId）；
4. 清理 4 处孤儿注释。

**证据**：行 120-294（委托）；行 338-395（双实现）；行 687-693（孤儿注释）。

### 3.3 FaqEntryCommandService（770 行）——上帝命令服务 + 4 处复制粘贴尾块 + 契约锁定的"先落库后失败"

文件：`domains/src/main/java/com/ragagent/knowledge/service/faq/FaqEntryCommandService.java`

**结构切分**：

| 区块 | 行号 | 行数 | 性质 |
|---|---|---|---|
| createEntry | 107-192 | 86 | 创建用例（含索引失败回滚 chunk） |
| updateEntry | 200-283 | 84 | 更新用例（**先落库后失败**，契约锁定） |
| addSimilarQuestions | 286-380 | 95 | 相似问追加 |
| updateEntryFieldsBatch | 385-547 | 163 | 批量字段更新（最复杂方法） |
| deleteEntries | 555-598 | 44 | 删除用例 |
| recordKbActivity | 635-671 | 37 | 审计投影 |
| checkFAQQuestionDuplicate | 677-748 | 72 | 重复校验 |
| appendSampleTitles / faqChunkQuestion / sha256Hex | 601-629, 753-765 | 42 | 工具 |

**根因判定：上帝命令服务（5 个用例共居）+ 复制粘贴 + 审计/哈希工具驻留**。

1. **同一尾块复制 4 遍**："tagSeqIdMap 装配 → chunkToFAQEntry → withTagName"（167-182、261-276、325-332+367-374 三处半），每次约 15 行——任何 tag 装配规则变更要改 4 处。
2. **审计投影是横切关注点**：recordKbActivity（635-671）含 TenantContext 兜底解析、details 字母序 JSON 组装、best-effort 语义——与 FAQ 用例无关，且该模式在其他域必有同类（应下沉 platform 或抽 `KbActivityRecorder`）。
3. **sha256Hex（753-765）是第 4 个手工 SHA-256 实现**（另见 §4.3）。
4. `updateEntryFieldsBatch`（163 行单方法）里 flag 位运算（setFlags/clearFlags）、byTag/byId 两条分支、引擎同步三态（boundEngine / vectorStore / 抛错）缠在一起——**分支膨胀 + 职责混杂**。

**拆分方案**：
1. `FaqEntryProjector`：收敛 4 处尾块为 `toEntry(chunk, kb)` 一处；
2. `FaqDuplicateChecker`：checkFAQQuestionDuplicate 独立（纯函数式，可单测）；
3. `FaqBatchFieldUpdater`：updateEntryFieldsBatch 的两个分支各提一个方法，flag 运算提 `ChunkFlags` 值对象；
4. `KbActivityRecorder`：recordKbActivity + appendSampleTitles 上移 knowledge 域共享或 platform；
5. sha256Hex → 平台工具（见 §6 backlog #3）。

**证据**：行 167-182 / 261-276 / 325-332 / 367-374（4 处尾块）；行 635-671（审计）；行 385-547（163 行方法）；行 753-765（第 4 个 SHA-256）。

### 3.4 UserService（738 行）——上帝服务 + 手写补偿事务 + 登录空间状态机

文件：`domains/src/main/java/com/ragagent/auth/service/UserService.java`

**结构切分**：

| 区块 | 行号 | 行数 | 性质 |
|---|---|---|---|
| login | 77-115 | 39 | 登录用例 |
| validateToken | 125-156 | 32 | JWT 校验 |
| 查询直通（7 个方法） | 161-213 | 53 | 仓储透传 |
| buildLoginMemberships | 221-262 | 42 | 响应组装 |
| **登录空间解析状态机** | 267-384 | **118** | preference→home→membership 解析 + 自愈写库 |
| token 签发 | 391-413 | 23 | 签发+落库 |
| **register** | 432-521 | **90** | **手写补偿事务** |
| changePassword | 590-619 | 30 | 改密+吊销 |
| session 三操作委托 | 639-651 | 13 | 委托 UserSessionOps |
| 异常分类（5 个内部异常类） | 654-670 | 17 | 错误通道 |
| 静态工具（sanitizeForLog/trimUnicodeWhitespace） | 693-726 | 34 | 字符串工具 |

**根因判定：上帝服务（认证域核心啥都干）+ 手写补偿 + 工具驻留**。

1. **register 手写补偿事务**（432-521）：创建顺序 tenant→user→member，失败时"先删用户再删租户"（行 489-499、505-519），注释明言"任一步失败按既定顺序回滚"。这段 90 行手工补偿本应是 `@Transactional`——auth 域 **0 处** `@Transactional`（§5.1 实测）。补偿写在业务代码里的问题：补偿本身失败只能 log（行 494-496、509-516），租户残留无告警；且与 MyBatis 自动提交的交互全靠约定。
2. **登录空间解析状态机**（267-384，5 个私有方法）是独立内聚主题：preference 优先 → home → 首个 membership，带"失效自愈"（clearStaleHomeTenant 写 NULL 避开 FK，行 320-339 注释记录了 `tenant_id=0` 炸 FK、jsonb 三参 set 两个坑）。这段值得独立成类并配专项测试——它复杂、脆弱、且注释里已埋了两个已知偏差（行 333-334"已记录为已知偏差"）。
3. **静态字符串工具驻留 service**：sanitizeForLog / trimUnicodeWhitespace（693-726）是被 controller 跨包调用的 public static——应入 platform 工具（且 `AuthController` 直接 `UserService.sanitizeForLog(...)` 行 218-219，反向依赖具体 service 拿工具函数）。
4. `generateTokensFor(Object user)`（676-678）为让 controller 不 import User 做了 **unchecked downcast**——用类型系统换 import 干净，运行时才炸。

**拆分方案**：`LoginTenantResolver`（状态机 5 方法独立 + 专项测试）；`UserRegistrationService`（register 改 `@Transactional` + 保留错误文案逐字符兼容）；token 签发与吊销留本类；静态工具入 `platform/text`；`generateTokensFor(Object)` 改类型化重载。

**证据**：行 432-521（补偿事务）；行 267-384（状态机）；行 693-726（工具）；行 676-678（unchecked cast）。

### 3.5 KnowledgeService（711 行）——门面积肿：35 个单行委托 + 注释腐烂

文件：`domains/src/main/java/com/ragagent/knowledge/service/management/KnowledgeService.java`

**结构切分**：

| 区块 | 行号 | 行数 | 性质 |
|---|---|---|---|
| 构造器（16 个协作者注入） | 107-141 | 35 | 装配 |
| 直接创建路径（file/url/manual/passage） | 161-337 | 177 | 真正残留的用例 |
| 读/改/删 + tags 批量 | 371-620 | 250 | 用例 + 80 行批量打标 |
| **单行委托给协作者** | 469-704 | 散布约 35 处 | 门面 |

**根因判定：门面积肿（拆分只做了一半）+ 注释腐烂（Go 移植残留）**。协作者拆分（move/clone/search/folder/span/summary/file/parse/batchOps 九个服务）方向完全正确，但门面仍暴露 50+ 方法，其中约 35 个是单行委托。更刺眼的是**注释腐烂**：

- 类 javadoc（57-71）出现"① reparse 的资源清理只对齐……② 刷新型摘要为进程内虚拟线程……"等**无主语的编号笔记**， reads like 迁移清单混进了类文档；
- 行 80-81 一条 javadoc（"duplicate 的配置克隆用：知识实体带 OffsetDateTime……"）**下面没有字段**——悬空；
- 行 365-366"同包开放（KnowledgeSummaryPipelineService.updateManualKnowledge 复用）"悬空；
- 行 2-3、407-411 多处空 javadoc 残壳。

这些是"边迁移边留痕"的典型产物：注释说的事已经做完了/搬走了，壳没删。对可读性是持续伤害——读者无法判断哪些描述还有效。

**拆分方案**：门面只保留 controller 真正调用的面（预计可从 50+ 方法降到 15-20），协作者直接注入 controller；`updateKnowledgeTagBatch`（541-606，含 N+1 写：609-620 循环内 `deleteRelations`+`insertRelation`）提 `KnowledgeTagBatchService`；发起一轮**注释卫生清理**（悬空 javadoc 全删）。

**证据**：行 469-704（委托）；行 57-71 / 80-81 / 365-366（腐烂注释）；行 609-620（循环内写）。

### 3.6 McpCatalog（723 行）——Go 单文件直译：提示词常量 + DTO 巢居 + 工具汤

文件：`domains/src/main/java/com/ragagent/mcp/tool/McpCatalog.java`

**结构切分**：

| 区块 | 行号 | 行数 | 性质 |
|---|---|---|---|
| 提示词/JSON-Schema 常量 | 37-129 | 93 | 纯字符串常量（discovery 说明 + 两个 schema + 信任边界声明） |
| 嵌套类型（Server/ToolSummary/Page/Args/结果） | 162-307 | 146 | DTO 巢居 |
| 目录核心（构造/授权/snapshot） | 309-454 | 146 | 状态机 + 锁 |
| 工具可见性/ref 哈希 | 459-531 | 73 | 权限复检 + 引用绑定 |
| **静态工具汤** | 537-722 | 186 | shortByRunes/mcpJsonResult/mcpSchemaWithEnum/decodeMcpCall/utf8/sha256/hex/b64… |

**根因判定：Go 单文件直译 + 三类异质内容挤一文件**。证据链：`quoteGo`（711）、码点截断 shortByRunes（548-560）、64 字符函数名限制（677 `ToolDefinitions.MAX_FUNCTION_NAME_LENGTH`）——这是把 Go 的 catalog struct + 方法 + 包级函数**原样搬进了一个 Java 类**。后果：

1. **186 行静态工具汤**：utf8/sha256/hex/b64UrlEncode 是通用编码工具（且 sha256/hex 是全库第 5、6 个手工实现）；mcpJsonResult/mcpSchemaWithEnum/decodeMcpCall 是 MCP 协议编码——两类都跟"目录状态机"无关。
2. **93 行提示词常量**：MCP_DISCOVERY_DESCRIPTION 是一整段面向模型的提示词工程文本（行 37-54），塞在 Java 源码里；schema 同理。改提示词要动编译。
3. DTO 巢居本身合理（字节级 JSON 契约，注释行 205/228/258 自述"键序固定"），但 6 个嵌套类型可以提为同包文件。

**拆分方案**：`McpCatalogServers`（嵌套类型提文件）；`McpDiscoveryText`（常量移入，后续可资源化）；`McpToolNaming`（mcpToolRef/mcpRegisteredName/sanitizeName/shortMcpDescription）；`McpJsonCodec`（编码/解码）；utf8/sha256/hex/b64 → 平台工具。核心类只留约 250 行目录状态机。

**证据**：行 37-129（常量）；行 537-722（工具汤）；行 711（quoteGo）；行 694-709（第 5/6 个 SHA-256）。

### 3.7 DingtalkAdapter（754 行）——三接口适配器直译 + 进程级全局流表

文件：`domains/src/main/java/com/ragagent/im/channel/dingtalk/DingtalkAdapter.java`

**结构切分**：

| 区块 | 行号 | 行数 | 性质 |
|---|---|---|---|
| 类 javadoc（行为规格） | 34-76 | 43 | 文档（质量很高） |
| 常量 + StreamState + **static STREAMS 全局表** | 83-104 | 22 | 全局可变状态 |
| verifyCallback | 146-173 | 28 | 验签 |
| **解析链（纯静态函数簇）** | 192-367 | 176 | 四段链 richText→file→audio→text |
| 下载 + SSRF | 369-435 | 67 | 文件下载 |
| 发送 + token 缓存 | 437-557 | 121 | 回复/OpenAPI/token |
| AI 卡片流式 | 559-748 | 190 | createAndDeliver/streaming/start/update/finalize/end |

**根因判定：多接口单文件直译（Adapter+StreamSender+FileDownloader 三接口一文件）+ 静态全局状态**。

1. **`static final Map<String, StreamState> STREAMS`（行 94）是进程级全局表**，跨所有租户/所有 adapter 实例共享。key 为 `dt:<userId>:<messageId>`（行 649）——userId 是钉钉 staffId，**不保证跨租户唯一**：两个租户绑了同一个钉钉企业时 staffId 碰撞，会互相覆盖/串流。孤儿回收（purgeOrphans，639-642）只按 TTL 清，不按租户分区。
2. **176 行静态解析函数簇**是纯函数（parseCallbackMessage/parseRichTextContent/…），与 HTTP/流式无语义耦合——应独立成 `DingtalkCallbackParser`，可无 Spring 单测。
3. token 缓存（524-557）有**惊群**问题：synchronized 只保护缓存读写（525-529），HTTP 取 token 在锁外（530-545），并发未命中会同时打钉钉；另 `expireIn - 300`（行 554）在 expireIn<300 时得到过去时刻，token 永驻"过期"态，每次调用都重新取。
4. 健壮性亮点（应保留）：所有外部调用有 15s/60s 超时；sessionWebhook/下载 URL 过 SSRF 校验（行 409-416、454-462）；签名用 `MessageDigest.isEqual` 定长比较（行 168）；卡片失败降级 sessionWebhook、再降级 OpenAPI（行 655-662、735-743）。

**拆分方案**：`DingtalkCallbackParser`（解析链）；`DingtalkApiClient`（dingtalkApi + token 缓存，修惊群与负 TTL）；`DingtalkCardStream`（卡片流 + StreamState + 流注册表，**流表改为按 (tenantId, userId, messageId) 键或实例级**）；本类留三接口薄实现约 200 行。

**证据**：行 94（static STREAMS）；行 192-367（解析簇）；行 524-557（token 缓存）；行 649（streamId 无租户维度）。

---

## 4. 分层 / 命名 / 重复代码证据

### 4.1 分层质量：测试强制的优秀，但 HTTP 层偏厚

**量化事实**：

| 检查项 | 方法 | 结果 |
|---|---|---|
| controller import 域 repository/mapper | 逐文件 grep import | **0 / 51** |
| service import **其他域** repository/mapper | 265 文件逐域比对 | **0 / 265** |
| 控制器厚度分布 | wc -l | **>300 行：22 个；200-300：8 个；100-200：13 个；≤100：8 个；中位数 259，p90 = 504** |
| BizException 抛出点分布 | Grep 计数 | controller 层（含同包协作者）**534 处** vs service 层 328 处 |
| 响应封装 | Grep | `ApiResponse.ok(` 65 处；但 DataSourceController 等约 8 个控制器**直写 `{"error": msg}` 单键体**（javadoc 行 37-40 自述"两者形态不同，别统一"） |

**判定**：
- **正向**：跨层访问为零不是偶然——ArchUnit `noLayerInversions`（ArchitectureRulesTest.java:563-580）把"service 不许依赖 controller、domain 不许依赖 service/controller"钉成了测试。controller 不碰 DB、service 不碰 HTTP 对象这两条铁律**真实成立**。
- **反向 1：控制器普遍超厚**。22/51 个控制器超 300 行，中位数 259。厚的原因不是"直接干业务"，而是**把三类编排逻辑放在了 HTTP 层**：
  a) **协议仿真逻辑**：AuthController（641 行）内含 gin binding 仿真——GIN_EMAIL 正则（行 105-114）、按码点计数（行 173、201-212）、逐条收集 binding 错误——这是为保持与 Go 后端逐字节兼容而留在 controller 的验证框架移植，本应属 validator 层；
  b) **策略解析**：AuthController.resolveRegistrationMode/resolveDefaultTenantMode/resolveTenantSelfServiceCreationEnabled（行 504-524）在 controller 里读 SystemSettingGateway 拼三级优先级（DB>env>cfg）；
  c) **授权编排**：DataSourceController 每个端点开头自取租户判 401（javadoc 行 58-66 解释了"刻意不抽切面"的理由——文案两组端点不同）。
  这些选择**有文档化的合理性**（契约锁定），但代价是 534 处 BizException 抛出点沉在 HTTP 层，单元测试只能走 HTTP 契约测试（boot/src/test 的 HttpContractTest 们确实这么干，最大 1009 行），业务规则无法在 service 层单测。
- **反向 2：响应封装不统一**。`@ApiResult` 注解 + ApiResponse 是主流（65 处），但 DataSourceController（546 行，37 处 BizException）全家直写 `{"error":...}`，EmbedChannelController 系列用 `Map<String,Object>` 错误体（12+6+4 处）——同一系统两套错误信封，客户端要兼容两种形态。javadoc 说"别统一"，但这是技术债不是特性。

### 4.2 命名与模式一致性：三套并存的服务命名方言

| 后缀 | 文件数 | 典型 | 评价 |
|---|---|---|---|
| `XxxService` | 69 | UserService / TenantService | 主流，清晰 |
| `XxxOps` | 20 | MemoryCatalogOps / AuthOidcOps / WikiPageOps | **协作者方言**：与 Service 的分界无书面定义 |
| `XxxSupport` | 9 | WikiIngestRunSupport / WikiBatchSupport | **第三种协作者方言**，与 Ops 区别不可判 |
| `XxxResolver/Guard/Codec/Store/Pipeline/Catalog…` | 24 | MemoryTopicResolver / FaqGuard / FaqChunkCodec | 角色命名，合理 |

- CQRS 式命名（`FaqEntryCommandService` / `FaqEntryQueryService`）**全库仅此一对**（Grep：`*CommandService` 1 个、`*QueryService` 1 个）——是孤例风格，不是体系。
- `Default*Service` 全库 **1 个**（DefaultWikiPageService）——也是孤例（对应 WikiPageService 接口）。
- **判定**：命名没有灾难，但 `Service` / `Ops` / `Support` 三套协作者方言并存且**无 package-info 说明判据**，新代码无法判断该用哪个。建议在 code-organization 规范里补一条协作者命名判据（有状态编排→Ops，无状态支撑→Support，或统一其一）。

**包内结构一致性**：20 个域中 **11 个有 `api/` 子包、9 个没有**。经查证 `api/` 包的内容是**跨域适配器**（如 `knowledge/api/ChunkPortAdapter.java` 供 wiki 注入、`auth/api/AdminUserAdapter.java`）——有跨域消费者的域才有 api/。这个设计**逻辑自洽但无任何文档说明**，且 im/embedchannel/favorite/system/vectorstore/initialization/approval/datasource/agenttools 九域没有 api/ 包，其跨域消费走的是什么（直接 import service？端口在 contract？）需要一份说明。package-info.java 覆盖 249/261（95.4%），README 声称基本属实，缺失的 12 个目录待补。

**DTO/契约/领域对象边界**：以 Knowledge 概念为例，同时存在 5 层形状：实体 `knowledge/domain/Knowledge` → 投影器 `knowledge/support/KnowledgeRowViews` → 契约 `contract/knowledge/KnowledgeView`(id+title) / `KnowledgeRowView`(117 行) / `KnowledgeDocumentFacts` → HTTP DTO `knowledge/dto/doc/KnowledgeResponse`(93 行) / `KnowledgeListResponse`。全库共 **54 个 `*View/*Views/*RowView` 类 + 190 个 dto 目录文件**。分层本身有正当性（DB 形状 ≠ 跨域契约 ≠ HTTP 响应），但**边界规则不可判**：什么时候该新建 View、什么时候复用 contract 类型，没有书面判据，导致形状持续膨胀。未发现同一字段集的三套完全重复类（抽查 KnowledgeView vs KnowledgeResponse 形状不同），属"边界模糊"而非"重复造类"。

### 4.3 重复代码量化

| 重复模式 | 量化 | 分布 | 判定 |
|---|---|---|---|
| **手工 SHA-256/hex** | **18 个文件** | domains 11（含 StorageFileResolver、FaqEntryCommandService、McpCatalog、MemoryText、SpanTracker、TenantAPIKeyService…）+ platform 2 + retrieval-engine 1 + model-runtime 2 + agent-engine 1 | **必须下沉 platform**：`Hashes`/`Codecs` 工具类，一次替换 18 处 |
| TenantContext.currentTenantId() | **146 调用点 / 87 文件** | 全 domains | 租户隔离是显式传播而非框架强制。可接受（显式），但 87 个文件手写 `tid==0→401/错` 判空，建议抽 `Tenants.requireCurrent()` 收编模式 |
| 显式软删过滤 `deleted_at IS NULL` | **约 160 处 / 约 60 文件** | WikiPageMapper 32、WikiFolderMapper 11、KnowledgeTagMapper 9、MessageMapper 9、DataSourceMapper 9、StorageBackendRepository 7… | 刻意不用 MyBatis-Plus `@TableLogic`（WikiPageMapper javadoc 明示），每条 SQL 自己记得加过滤——**漏一处就是跨租户数据泄露**。这是全库最危险的一类重复 |
| BizException + AppError 错误通道 | **862 处抛出**（controller 层 534 / service 层 328） | AppError 工厂方法（badRequest/forbidden/notFound/conflict/validation/unauthorized/internal）集中定义，模式统一 | 错误模型本身设计良好（错误码+details）；问题在 §4.1 的层次分布 |
| 分页外壳 | `page/pageSize` 签名 18 文件 + `PageResult` 契约集中 | contract/pagination/PageResult | **已部分下沉**，良好；残余是各仓储自己的 limit 上下界常量（WikiPageRepository 行 62-79 定义了 5 组） |
| FAQ tag 装配尾块 | 4 处 × 约 15 行 | FaqEntryCommandService 内 | 文件内重复，见 §3.3 |

---

## 5. 健壮性证据

### 5.1 @Transactional：22 处，只覆盖 3 个域

domains 生产代码 `@Transactional` 共 **22 处**（均为裸注解，无 rollbackFor/isolation/readOnly 属性），分布：wiki 仓储 10（TaskPendingOpsRepository 7、TaskDeadLetterRepository 2、WikiPageRepository 1）、knowledge 9（KnowledgeService 6、KnowledgeBatchOpsService 2、KnowledgeFolderService 2 等）、memory 2（MemoryTxTemplate）、knowledge/KnowledgeFileService 1。

- **已知的自调用失效问题被正面处理过**：`memory/repository/MemoryTxTemplate.java:16` javadoc 明言"`withSubject(...)`，`@Transactional` 根本不生效——所有写方法都会跑在……"，用独立 bean 规避。这是**加分项**（团队懂这个坑），但也说明写法是 case-by-case 补救而非统一策略。
- **缺口**：auth/mcp/session/storage/im/datasource/model/system/vectorstore/embedchannel/websearch/agentmanagement 等域 **0 处**声明式事务。其中 UserService.register（§3.4）用手写补偿；其余多写域（如 mcp 的 service+credentials+metadata 三表写、storage backend 写）要么是单语句写、要么靠"顺序写+失败不回滚"——需逐域确认是否存在跨表一致性需求，不能一概说错，但**没有一份书面的事务边界约定**是事实。

### 5.2 异步与虚拟线程：37 个使用点，异常处理总体达标

`Thread.ofVirtual` / `newVirtualThreadPerTaskExecutor` 在 domains 出现 **37 个文件**（session/qa 管线、wiki ingest 队列、memory、mcp protocol、knowledge task executor、im 长连接等）。

- **良好实践实物**：MemoryService.touchAsync（行 428-448）显式只传 scope、不读 ThreadLocal（javadoc 行 421-427 明言"跨线程读 ThreadLocal 正是禁止的"），且 try/catch 只 log；chat 管线刷新型摘要在虚拟线程上跑且失败降级。
- **遗留疑点**：wiki 的 `InProcessWikiIngestTaskQueue`、knowledge 的 `KnowledgeProcessWorker`、datasource 的 `InProcessDataSourceSyncTaskQueue` 是**进程内任务队列**（README 云函数时代的产物，javadoc 多处自述"任务队列以进程内虚拟线程队列替代"）——单 Pod 内可跑，多副本部署下队列语义（全局串行/至少一次）是否仍成立需要部署侧答案；ArchUnit 有 `agentCollaboratorsDoNotHoldTheEngine` 等规则但似乎没有覆盖任务队列拓扑。

### 5.3 外部调用：超时全覆盖，重试/幂等缺位

- 24 个 `HttpClient.newBuilder()` 站点 **100% 设置 connectTimeout**；38 个 `HttpRequest.newBuilder()` 中 35 个设了 per-request `.timeout(...)`。未设 per-request timeout 的：websearch/fetch/Fetcher（1 处）、mcp/protocol/SseTransport（3 处，长连接流属合理）、McpHttp（1 处）、WechatLongPollClient/WechatQRCodeService（各 1 处，轮询/二维码长流程）——**基本合理，个别需确认**（Fetcher 抓网页无请求超时，仅 connect 超时，慢响应会挂住调用方）。
- DingtalkAdapter 的 5 个请求全部 15s/60s 超时 + SSRF 校验 + 失败降级链（§3.7）是正面样板。
- **重试与幂等**：全库未见统一重试框架（无 @Retryable / Resilience4j 迹象）。IM 发送、卡片流式更新、LLM 调用均为**单发**。卡片流式更新 `isFull=true` 天然幂等；IM 发送失败即抛（由上层决定）——对 MVP 可接受，但支付/写外部系统类场景若出现需补幂等键。**这是已知缺口而非隐藏缺陷**。

### 5.4 N+1 查询：已实物定位 3 处

| 位置 | 证据 | 严重度 |
|---|---|---|
| `session/conversation/SessionService.java:352-371` | `for (String id : ids) { sessionRepository.get(tenantId, userId, id); }` 逐条查会话；`for (String id : visibleIds) { suggestionRepository.deleteBySessionId(tenantId, id); }` 逐条删 | 中（批量删除会话路径，一次删 N 个会话 = N 次查询） |
| `session/conversation/SessionService.java:394-404` | `for (Session session : sessions) { suggestionRepository.deleteBySessionId(...); }` 又一个逐条删 | 中 |
| `knowledge/service/access/ChunkAccessGuard.java` | `for (Chunk parent : parents) { for (Chunk child : chunkRepository.listChunkByParentId(...)) }` 递归树逐层查库 | 中高（深树 = 层数 × 查询） |
| `knowledge/service/query/KnowledgeSearchService.java:65-66, 84-85` | `for (scope : scopes) { kbRepository.findById(...) }` 逐 scope 查 KB（两处同型） | 低-中（scope 数通常小） |

修法统一：批量化（`listByIds` / `deleteBySessionIds` / 一次递归 CTE 或带层级路径的集合查询）。前两处是纯批量 API 缺失，第三处可改"按 kbId+path 前缀一次取全树"。

---

## 6. 重构 Backlog（按收益 × 风险排序）

> 工作量量级：S ≤1 天 / M 2-5 天 / L 1-2 周 / XL >2 周。
> "需改契约"指是否触及 HTTP 响应字节形态或跨模块接口。

| # | 问题 | 证据（文件:行） | 修法 | 量级 | 契约影响 |
|---|---|---|---|---|---|
| **1** | **大文件治理：301 个 >300 行文件占 47.3% LOC**，最重 7 个已深读（§3） | StorageFileResolver.java:1-778；MemoryService.java:1-770；FaqEntryCommandService.java:1-770；UserService.java:1-738；KnowledgeService.java:1-711；McpCatalog.java:1-723；DingtalkAdapter.java:1-754 | 按 §3 各案拆分方案执行；**先立门禁再动手**：把 300 行上限写进 ArchitectureRulesTest 的 ratchet（现有 `ratchet()` 机制可直接用，ArchitectureRulesTest.java:529），只减不增 | L（整体）/ M（单文件） | 否（公开方法签名保持不变，纯内部重构） |
| **2** | **软删过滤靠 160 处手写 `deleted_at IS NULL`，漏一处即跨租户泄露** | WikiPageMapper.java（32 处）、WikiFolderMapper.java（11）、KnowledgeTagMapper.java（9）、MessageMapper.java（9）等约 60 文件 | 二选一：a) 全面启用 MyBatis-Plus `@TableLogic`（需评估 BaseMapper 混用面，39 文件）；b) 保持显式但加 ArchUnit 规则：mapper 接口中每个 `@Select` 若涉软删表必须含过滤（可用源码扫描规则类 `noRawNulBytesInSources` 同款手法，行 225） | M-L | 否 |
| **3** | **手工 SHA-256 18 处 + 编码工具散落** | StorageFileResolver.java:697-708；FaqEntryCommandService.java:753-765；McpCatalog.java:694-709；MemoryText/SpanTracker/TenantAPIKeyService/Pkce/McpConfigFingerprint/ResourceCatalogService/ImChannelService/FeishuWecomCrypt/PromptFingerprints/ChunkSearchUtil/TencentVectorDb/RssUtil/ImaFormats/VolcengineReranker/LkeapReranker/NormalizeToolCallId | 建 `platform/security/Hashes` + `platform/text/Codecs`，18 处逐个替换（机械但需逐个验证输出等价——hex 大小写、截断长度各有契约，如 FaqEntry 取前 16 字节） | S-M | 否（注意保持字节级输出一致） |
| **4** | **MemoryService 双开关实现分叉风险 + 175 行委托样板** | MemoryService.java:338-395（enabledScope vs scopeDisableReason）；120-294（委托）；687-693（孤儿注释） | 提 `MemoryScopeGate` 单一求值；删委托（协作者改 Bean 直注）；清理孤儿注释 | M | 否 |
| **5** | **auth 域零事务 + register 手写补偿** | UserService.java:432-521；domains 全域 @Transactional 仅 22 处且不含 auth | register 改 `@Transactional(rollbackFor = Exception.class)` 保留逐字符错误文案；其余 auth 多写路径审计补事务；写一份《事务边界约定》明确"哪些用例必须事务" | M | 否 |
| **6** | **N+1 查询 4 处** | SessionService.java:352-371, 394-404；ChunkAccessGuard.java（树遍历）；KnowledgeSearchService.java:65-66, 84-85 | 批量化：`deleteBySessionIds`、`listByIds`、KB 批量查、chunk 树一次取 | S-M | 否 |
| **7** | **响应信封双轨制** | DataSourceController.java:37-40（javadoc 自述直写 `{"error"}`）；EmbedChannelController.java（12+6+4 处 Map 错误体） | 统一到 AppError 信封；如前端已依赖旧形态，先加版本头过渡 | M | **是**（HTTP 错误体形态）——需前端与 PM 会签 |
| **8** | **DingtalkAdapter 进程级全局流表跨租户串流风险** | DingtalkAdapter.java:94（static STREAMS）、649（streamId 无租户维度） | 流表改实例级或 key 加 tenantId；同时修 token 惊群（524-557）与负 TTL（554） | S | 否 |
| **9** | **协作者命名三方言（Service/Ops/Support）无判据 + api/ 包存在性无说明** | §4.2 统计；package-info 覆盖 95.4% | code-organization 规范补命名判据；给 12 个缺 package-info 的目录补全；写一份 api/ 包用途说明 | S | 否 |
| **10** | **FAQ 复制粘贴尾块 ×4 + 上帝命令服务** | FaqEntryCommandService.java:167-182, 261-276, 325-332, 367-374；385-547（163 行方法） | 按 §3.3 拆 5 块 | M | 否 |
| **11** | **McpCatalog Go 直译：93 行提示词常量 + 186 行工具汤** | McpCatalog.java:37-129, 537-722 | 常量/编码/命名各提文件；提示词评估资源化 | S-M | 否 |
| **12** | **注释卫生：悬空 javadoc 与无主语编号笔记** | KnowledgeService.java:57-71, 80-81, 365-366；MemoryService.java:687-693 | 一轮专项清理（可脚本化检测：javadoc 后无声明） | S | 否 |

**建议执行顺序**：#2（安全）→ #3（机械低风险）→ #6（性能）→ #8（并发正确性）→ #4/#10/#11（纯内部重构）→ #1 的门禁先行、拆分随价值流动 → #5（需回归测试护航）→ #7（跨团队）→ #9/#12（卫生）。

---

## 7. 方法论附注

- 全量统计：`find` + `wc -l` 对 2,957 个后端 Java 文件逐行计数（脚本产物 /tmp/allwc.txt、/tmp/bigfiles.txt）。
- 深读：7 个目标文件全文通读（StorageFileResolver / MemoryService / FaqEntryCommandService / UserService / KnowledgeService / McpCatalog / DingtalkAdapter），覆盖任务清单要求的 7 选 4。
- 分层与重复：基于全文件清单的逐文件 import 扫描 + Grep 计数，所有数字可复现。
- 未执行构建/测试（遵守只读纪律）；所有结论均为静态阅读 + 计数证据。
