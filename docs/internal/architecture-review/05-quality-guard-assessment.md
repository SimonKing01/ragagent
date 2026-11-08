# 质量体系评估：测试与架构守卫（05 / quality-guard-assessment）

- 评估人：测试工程师「严过关」（mvp-dev-expert-team-qa）
- 日期：2026-10-20 · 只读评估（未修改/删除任何现有代码、配置、文档）
- 仓库：/Users/billy/ragagent · HEAD 实测
- 口径说明：本报告中所有计数均为本次实测（grep/脚本化统计）；文档声称值另标注。凡未能本机执行的守卫（全仓扫描型，本沙箱内存限制下被 kill），均标注「代码走读」而非「执行验证」。

---

## 1. 结论摘要（verdict）

**整体 verdict：这套质量体系是我在生成式代码项目里少见的高强度基础设施——按生产就绪记分卡，「测试+回归」与「契约」两维均可评 Silver（生产稳健），整体不因质量体系本身卡上线；但离 Gold 的差距不在「守卫数量」，而在三处结构性缺口：契约金片的「值」没有机械防伪（且 1349 的计数有 ~7% 水分）、测试覆盖在域之间与模块之间严重不均（最薄域 2,291 行主码配 1 条测试）、以及一切棘轮的签收都是社会化的（`--write` 无机械约束）。**

数据底座（全部实测）：

| 指标 | 实测值 | 说明 |
|---|---|---|
| 测试文件（src/test） | **488 个** | 另有 28 个 testFixtures 支撑类（桩服务器/夹具加载器） |
| 测试方法 | **4,652 个 `@Test` + 37 个 `@ParameterizedTest` = 4,689** | README/评估文档称「约 5000 用例」——参数化展开后可信，口径不虚 |
| 测试代码量 | **134,719 行** vs 主码 300,535 行 | 测试/主码 LOC 比 **0.45** |
| 脚本守卫 | **42 条**（`scripts/run-guards.sh`，单一入口，CI 与本地同口径） | 评估文档称「40 条」、CI yml 注释称「含 32 条」——**两处都已漂移**（脚本自计数，打印值正确） |
| ArchUnit 规则 | **28 条**（`ArchitectureRulesTest` 22 + `ContractBoundaryRulesTest` 6，均在 `boot/src/test/.../arch/`） | 与文档一致，零漂移 |
| 契约金片 | **1,349 个 JSON**（`domains/src/testFixtures/resources/contracts/`） | 其中 **~100 个（7.4%）不被任何 Java 测试引用**，只存在于 `scripts/record-*-golden.sh` 录制清单 |
| Go 录制金片 | **8 个 bundle + .sha256**（`platform/src/testFixtures/resources/golden/`） | 加载时 SHA-256 逐字节校验，**比 HTTP 金片更强** |
| CI | 4 job（guards / backend / docker / frontend），`backend` 起真 ParadeDB + 装 redis-server | 见 `.github/workflows/ci.yml` |
| 守卫执行状态 | **39/42 条脚本守卫本机执行验证全绿**；1 条（check-stray-dirs）因本评估产出目录未被 git 跟踪而红（**提交后自愈**，非存量缺陷）；2 条全仓扫描型（check-package-cycles / check-module-graph）沙箱内被 kill，改用代码走读 + 基线文件核对 | ArchUnit 28 条未执行（禁 gradle），以代码走读 + 既往 CI 记录为准 |

一句话结论：**守卫层是这套体系里最接近「商业级」的部分（棘轮哲学贯彻得很彻底）；测试层是「总量充裕、结构失衡」；金片层是「机制先进、完整性有洞」。**

---

## 2. 测试分布量化表

### 2.1 按模块（实测）

| 模块 | src/test 文件 | @Test (+PT) | 测试 LOC | 主码 LOC | 测试/主码 | 集成测试文件（@SpringBootTest） |
|---|---|---|---|---|---|---|
| domains | 254 | 2,413 (+24) | 69,734 | 210,885 | 0.33 | 0（域内单测） |
| boot | 101 | 1,315 (+6) | 38,135 | 2,201 | 17.3 | 87 个中的绝大部分（HTTP 契约/集成） |
| retrieval-engine | 34 | 316 | 9,761 | 20,484 | 0.48 | 2（*IT 另走 integrationTest 任务） |
| agent-engine | 36 | 156 | 6,086 | 10,815 | 0.56 | 0 |
| platform | 37 | 238 | 4,899 | 17,984 | 0.27 | 少量 |
| model-runtime | 24 | 203 (+7) | 5,937 | 17,925 | 0.33 | 0 |
| contract | 2 | 11 | 167 | 20,241 | **0.008** | 0 |
| **合计** | **488** | **4,689** | **134,719** | **300,535** | **0.45** | 87 |

测试类型构成（实测）：48 个 `*ContractTest.java`（HTTP 契约金片断言）、87 个文件带 `@SpringBootTest`（真实 Spring 上下文 + MockMvc/H2）、2 个 ArchUnit 规则类、其余为纯单测（AssertJ + Mockito）。前端另有 96 个 `tsx --test` 用例（`frontend/package.json` → `npm test`）。

**两个结构性观察**：
1. **boot 是集成测试聚合地**（1,315 @Test 打在 2,201 行主码上）——这是刻意的组合根设计（`check-module-graph.py` 判据 E 显式豁免 boot 的 src/test 按业务包组织），代价是「改任何域都要重跑全量 Spring 上下文」的 CI 时间成本，收益是契约金片直接钉 HTTP 线格式。
2. **contract 模块（自称「前端契约、API 形状与可测试边界的唯一来源」，20,241 行主码）只有 2 个测试文件 / 11 条用例 / 167 行测试**（`contract/src/test/.../json/JsonValuesTest.java`、`.../storage/support/ModeTest.java`）。契约面的纯度由 ArchUnit（B258-R1）+ check-contract-purity.py 硬零守护，载荷形状由 48 个 HTTP 契约测试间接覆盖，但**契约面自身的记录校验/紧凑构造器/视图逻辑没有直接单测**——20k 行的「唯一真相源」是体系中单测密度最低的模块。

### 2.2 按业务域（domains 单测 + boot 契约测试合并，实测）

| 域 | 主码 LOC | 单测(@Test) | boot 契约(@Test) | 合计 | 测试/kLOC | 备注 |
|---|---|---|---|---|---|---|
| approval | 926 | 42 | 0 | 42 | **45.4** | 覆盖率最高 |
| audit | 1,446 | 54 | 12 | 66 | 45.6 | |
| memory | 12,784 | 169 | 178 | 347 | 27.1 | |
| datasource | 26,423 | 582 | 191 | 773 | 29.3 | 绝对数最高 |
| wiki | 23,406 | 308 | 159 | 467 | 20.0 | |
| auth | 12,938 | 113 | 88 | 201 | 15.5 | |
| mcp | 16,524 | 172 | 76 | 248 | 15.0 | |
| session | 30,455 | 224 | 231 | 455 | 14.9 | 规模最大 |
| model | 2,760 | 0 | 32 | 32 | 11.6 | 单测 0，靠 boot 契约 |
| websearch | 5,539 | 56 | 8 | 64 | 11.6 | |
| agenttools | 11,386 | 125 | 0 | 125 | 11.0 | |
| knowledge | 28,185 | 159 | 151 | 310 | 11.0 | |
| storage | 7,912 | 63 | 20 | 83 | 10.5 | |
| system | 2,082 | 0 | 19 | 19 | 9.1 | 单测 0 |
| im | 17,770 | 154 | 4 | 158 | 8.9 | |
| favorite | 415 | 0 | 5 | 5 | 12.0 | 小域 |
| agentmanagement | 3,072 | 5 | 8 | 13 | **4.2** | ⚠️ |
| embedchannel | 2,982 | 10 | 2 | 12 | **4.0** | ⚠️ |
| vectorstore | 1,589 | 0 | 3 | 3 | **1.9** | ⚠️ |
| initialization | 2,291 | 1 | 0 | 1 | **0.4** | ⚠️⚠️ |

对账说明：本表「单测」列合计 2,237，与 domains 模块总数 2,413 的差 176 条在 6 个非域测试包（`domains/src/test/java/com/ragagent/{arch,embedding,event,llm,platform,rerank}`——其中 10 个文件正是 `MISPLACED_TEST_ALLOW` 登记的跨模块错位测试，见 §4.2 #2）。

**覆盖最薄的 3 个域（按测试/kLOC）**：
1. **initialization（0.4/kLOC）**：2,291 行主码（含 157 行的 `InitializationConfigService.configResponse`、已知缺口 §3 的 `storageBackendId` 不解析分支）只有 **1 条**单测（`domains/src/test/.../initialization/` 1 个文件）。
2. **vectorstore（1.9/kLOC）**：20 个主码文件（service/config/controller/mapper/repository 全套）只有 3 条 boot 契约测试，域内单测 0。
3. **agentmanagement（4.2/kLOC）**：27 个文件 / 3,072 行，域内 3 个测试文件 5 条用例 + boot 3 个文件 8 条——Agent 管理面（README 头条能力）的测试密度不到全仓中位数的 1/3。
   （embedchannel 4.0/kLOC 紧随其后：2,982 行主码、12 条测试，且其 65 个 `emb-*` 金片中相当部分只被录制脚本引用。）

反向证据（说明这不是「抽样误差」）：30 天热点图（`docs/internal/plans/architecture-assessment-2026-10-20.md` §8）显示 knowledge/session/wiki 既是活动中心也有相应测试；而初始化向导是每次部署的入口链路，1 条测试与它的关键性完全不匹配。

### 2.3 命名规范与用例构成抽样

- 命名：行为句式 camelCase（`duplicateMessageIsSkipped`、`pinnedSessionsFloatToTheTop`、`emitsTerminalAnswerWhenQueueEndsWithoutDone`、`preserveTokenOnTemporaryRefreshFailure`），抽样未见 `test1/test2` 式命名；少量批次代号残留（`section6_testAndDelete`、`w5a*`、`qa46d*`）。
- 测试自含策略（可维护性亮点）：契约/单测走 H2 内存库 + EmbeddedRedis + 手写 HTTP 桩（`stub-llm-server.py` / `stub-ollama-server.py` / `GitLabServerStub` / `OAuthServerStub` / `NotionStubServer` 等），不依赖 Testcontainers；CI 另起真 ParadeDB 服务容器支撑两个连真 PG 的录测试 + 全新库灌迁移守卫（`ci.yml:43-77`）。
- 跳过治理（亮点）：`scripts/test-summary.py` 把失败与跳过都变成 CI 注解，且 `ALLOWED_SKIPS` 是**棘轮**（B218：未登记跳过 → 退出码 1）。当前登记 3 条且都带理由：`SpringRedisPubSubTest.*`（本地无 redis-server，CI 已装）、`RemoteApiChatTest.liveRemoteApiChat*`（需真 API key）、`JiebaTokenizerDiffTest.*`（需 Go 对照产物）。
- IT 测试隔离（亮点）：`@EnabledIfEnvironmentVariable` 的 `*IT` 类由独立的 `integrationTest` 任务承接、**不接 check**，且默认 `test` 任务显式 `exclude("**/*IT.class")`（`retrieval-engine/build.gradle.kts`）——env 门控测试不会污染跳过棘轮。

---

## 3. 契约金片机制评估

### 3.1 机制实况（两套金片，强度不同）

| 维度 | HTTP 契约金片 | Go 录制金片 |
|---|---|---|
| 位置 | `domains/src/testFixtures/resources/contracts/*.json`（1,349 个） | `platform/src/testFixtures/resources/golden/`（8 bundle + .sha256） |
| 消费 | `GoldenContract.assertEquals`（`domains/src/testFixtures/java/com/ragagent/fixtures/GoldenContract.java`）+ 48 个 `*ContractTest` | `GoldenResources.get(bundle,key)`（platform testFixtures） |
| 对比语义 | **语义级**：两侧走 `ContractJson.deep`（键排序 + 数字归一 + 时间归 UTC + 内嵌 JSON 递归归一）后等值；非 JSON 走 strip 文本断言 | **字节级**：加载时按同名 `.sha256` 校验，「手改资源一个字节，引用它的测试立即失败」 |
| 变更签字 | `check-contract-sync.py`：金片**键集合** SHA1 前 12 位 + 键数 vs `scripts/contract-signature.baseline.json`，不符即红；`--write` 前打印全部差异 | 无基线文件——sha256 边车即基线，重录=重生成边车 |
| 重录入口 | `-Dcontract.refresh=true` 单开关，把掩码后实际响应写回夹具 | 重跑 Go 录制程序 + 重新生成 sha256 |

对 task brief 中「1349 份逐字节钉死」的说法需修正：**HTTP 金片是语义归一后等值断言（键序/数字/时间格式不敏感），不是逐字节**；真正逐字节的是 8 个 Go  bundle。这不削弱机制价值，但意味着「字段值类型从字符串变数字」这类变化金片**抓不到**（deep 数字归一会吸收），只能靠键签名守卫发现键集合变化。

### 3.2 评估：保护了什么

1. **防「接口悄悄变了」对前端联动的保护是真实存在的**，且机理正确：金片是期望值，代码改动导致响应值漂移 → 契约测试红；键集合变化 → `check-contract-sync.py` 红 → 强制人确认前端是否同步（前端 31 个 api 文件 / 265 个 interface 全手写无 codegen，这层签字是主要防线）。辅以 `check-fe-contract-keys.py`（前端 snake/后端 camel 棘轮）与 `check-json-key-case.py --strict`（换锚棘轮 + 冻结覆盖快照）形成三面夹击。
2. **粒度合理**：1,349 个夹具按「端点 × 场景」命名（`adm-create-badbody` / `ks-search-none` / `att-delete-again`），错误分支（400/401/403/404/冲突）与正常路径都有样本；掩码（UUID/时间戳）两侧同函数，金片稳定。
3. **重录成本低且有安全阀**：`-Dcontract.refresh=true` 一条命令；`GoldenContract` 把四件套（读夹具+掩码+断言+重录）收拢为一份共享基建，`ContractPaths` 统一仓库根定位（历史上 97 处因模块改名 `server→boot` 失败的教训已固化）。

### 3.3 评估：洞在哪（按严重度）

1. **「值」无机械防伪（最重要）**。`check-contract-sync.py` 只守**键名集合签名**。把某个金片里的错误码从 `1003` 改成 `1999`、把分页 `total` 从 42 改成 41——键集合不变、签名不变、守卫全绿、测试也绿（夹具=夹具）。Go 金片有 sha256 边车，HTTP 金片没有。按反作弊规范（`test-integrity-anti-gaming.md` §1「改基线/期望文件」形态），**HTTP 金片目前只靠 git history 保护，没有一道「作者改不动」的确定性校验**。
2. **~100 个孤儿夹具（7.4%）虚增计数**。实测 1,349 个夹具中 1,249 个被 Java 测试按字面名引用；剩余 ~100 个（如 `doc-get.json`、`api-key-401.json`、`att-delete.json`、`ct-search-keyword.json`、`adm-list-page.json`）只出现在 `scripts/record-*-golden.sh` 的录制清单里——**录了但没有任何断言消费**，零保护价值。项目自己知道这个问题（`scripts/audit-contract-fixtures.sh` 的注释记载 B31 实测「115 个孤儿里 102 个是录制清单」），但：① 该审计脚本**不在 run-guards.sh**（纯人工工具）；② 它引用的路径是 `server/src/test/resources/contracts`——**模块早已改名 boot，脚本今天跑起来就会找不到目录而静默无产出**；③ 注释明确「禁止按输出批量删除」，于是孤儿永久滞留。
3. **部分高价值 HTTP 面无金片**（详见 §5 实例 1）：`/api/v1/skills/catalog**`（Skills 管理面，README 头条能力）、`/api/v1/sessions/continue-stream/*`、非 embed 渠道的主聊天 SSE 面，均无任何测试引用。`check-route-policy.py` 守的是**权限覆盖**（384 路由/372 规则，实测通过），不守契约覆盖——「路由有 RBAC 规则」与「路由有线格式金片」是两个无人关联的口径。
4. **重录开关无 CI 侧禁用**：`-Dcontract.refresh=true` 是普通系统属性，任何人本地/CI 手滑带上它跑一遍再提交，夹具就被静默改写。没有守卫断言「CI 中不得开启 refresh」。

### 3.4 过度 or 不足？

**不过度，但不足与失真并存**。1,349 个夹具没有导致「合理演进成本高」的实证——演进通道（refresh + 键签名签字）是顺滑的；真正的问题是**计数失真（7% 孤儿）+ 值级无防伪 + 覆盖面有缺口**。建议方向是**收紧**（sha256 边车 + 孤儿清理 + 补面），不是放松。

---

## 4. 守卫规则清单与强度评级

### 4.1 总览

- **42 条脚本守卫**（`scripts/run-guards.sh` 单一入口，`set -uo pipefail` 逐条跑不早退，退出码聚合；CI guards job 与本地同一份——B168「本地绿 CI 红」的教训已固化为入口设计）。
- **28 条 ArchUnit 规则**（随 `:boot` 测试套件在 backend job 跑，代码级判据）。
- 另有 CI 步：`test-summary.py`（跳过棘轮）、`spotlessCheck`（ratchet 自 seed）、`ci-smoke.sh`（真实 jar 冒烟）。

### 4.2 脚本守卫清单（42 条，按族分组；强度：★强=硬零或双向棘轮 / ◐中=单向棘轮或有已知盲区 / ○弱=风格/文本级）

**模块与依赖（7）**

| # | 守卫 | 判据 | 强度 | 实测状态 |
|---|---|---|---|---|
| 1 | check-package-cycles.py | R1 两两环 / R1b 顶层域 SCC / R2 域不得 import config / R3 L2 能力层不依赖 L3 业务层 / R8 禁内联全限定名 / R9 包级 SCC，全部基线棘轮 | ★ | 基线：环 0、SCC 0、config 0、L2→L3 0、包级 SCC 5 组（全部登记为「插件模式/契约词汇内聚」）；代码走读（本机全仓扫描被沙箱 kill） |
| 2 | check-module-graph.py | A 主码依赖矩阵（只许减）/ B 主码无跨模块同包 / C 测试期边 ⊆ 矩阵∪ALLOWED_TEST_EXTRA / D 夹具不占别模块主码包 + D2 跨模块重复 FQN=0 / E 测试不住别模块主码包（MISPLACED_TEST_ALLOW 10 条，**登记失效也红**） | ★ | 代码走读；登记 10 条与实测一一对应（embedding 1/event 1/llm 2/platform 5/rerank 1） |
| 3 | check-domain-contracts.py | 域→域边三档：clearable（目标 0）/ owner（同数据属主，登记允许）/ intra-family（族内豁免+冻结）；新增边/新增类即红；「登记不是新增许可」；`--write` 有新增即拒 | ★ | 执行验证：**6 条边 / 33 类，全在 ③ intra-family**（agent-runtime 族 4 边、identity 1、knowledge 2）；clearable/owner 均已清零 |
| 4 | check-capability-kinds.py | 跨 kind（产品能力/集成适配/控制面/能力配置/初始化向导/策略库）直连=0 | ★ | 执行验证通过（20 域 / 0 跨 kind） |
| 5 | check-contract-purity.py | contract 纯 JDK：导入根 / 构建依赖 / 框架形状，硬零不棘轮 | ★ | 执行验证：353 个文件三项全 0 |
| 6 | check-duplicate-classes.py | 同一简单类名不得出现在多模块主码 | ◐ | 执行验证通过 |
| 7 | check-package-dirs.py | 源码目录必须与 package 声明一致 | ◐ | 执行验证通过 |

**分层与纯度（7）**

| # | 守卫 | 判据 | 强度 | 实测状态 |
|---|---|---|---|---|
| 8 | check-controller-purity.py | C1 控制器不得 import `*Mapper`（硬零）/ C2 `catch(RuntimeException)` 只许减 | ◐ | 执行验证：C1=0；C2 **139 → 119（净减，但绝对值仍高：25 个控制器 119 处裸 catch）** |
| 9 | check-controller-entity-imports.py | C10 控制器 import 领域实体只许降 | ★ | 基线 `files: {}`（0 违例，硬零态势） |
| 10 | check-port-budget.py | 域内端口 / 无实现端口 = 0 | ★ | 执行验证：0/0 |
| 11 | check-contract-shape.py | 端口 ≤7 方法 / 行视图禁入 / 字段 ≤20 | ◐ | 执行验证：端口 33 / 行视图 8 / 字段预算命中 9（基线 22 条） |
| 12 | check-platform-shapes.py | platform 业务词包/文件/红旗命名三档，只许减 | ◐ | 执行验证：16 命中全部有登记 |
| 13 | check-exception-semantics.py | 检索层不得 catch 内清空条件；非边界层无空转 catch | ◐ | 执行验证通过 |
| 14 | check-tenant-scope.py | T1 禁 `.replay()` / T2 capture 必须同文件用 TenantScope，硬零 | ★ | 执行验证通过 |

**命名与 API 一致性（8）**

| # | 守卫 | 判据 | 强度 | 实测状态 |
|---|---|---|---|---|
| 15 | check-json-key-case.py --strict | snake 键换锚棘轮 + 冻结覆盖快照（离开/新进冻结面都红） | ◐ | 执行验证通过；脚本自述「文本匹配会命中 SQL 参数/列名，`--list` 是待判清单」——诚实但有噪音 |
| 16 | check-fe-contract-keys.py | 前端出现「后端只认 camel」的 snake 契约键，只许减 | ◐ | 执行验证通过；三口径窄匹配避免误伤，但脚本自述「七种写法扫不到」——文本层找契约键的固有上限 |
| 17 | check-event-face-case.py | 事件面/路由面命名口径 | ◐ | 执行验证通过 |
| 18 | check-api-envelope.py | R-a 新控制器必须 @ApiResult 或登记 pending / R-b pending 只许减 / R-c 无幽灵 / R-d 禁双壳 | ★ | 执行验证：全部控制器已迁或已登记 |
| 19 | check-contract-sync.py | 金片键签名签字（见 §3） | ◐ | 执行验证：1,349 个金片签名与基线一致（**只守键，不守值**） |
| 20 | check-route-policy.py | 路由权限覆盖：未登记公开路由/幽灵规则/对照表过期 | ★ | 执行验证：384 路由 / 372 规则；受保护 330 + 登记公开/自服务 54 |
| 21 | check-version-consistency.py | Boot/springdoc/jsqlparser/wrapper/Dockerfile 版本一致 | ○ | 执行验证通过 |
| 22 | check-internal-codes.py | 主码注释禁批次号/日期戳；旧夹具名禁回流 | ◐ | 执行验证：0 处 |

**规模与卫生（6）**

| # | 守卫 | 判据 | 强度 | 实测状态 |
|---|---|---|---|---|
| 23 | check-file-size.py | R-a 新增 >600 行主码红 / R-b 基线文件回涨 >30 行红 / R-c ≥800 必须登记 / R-d 例外必须 javadoc 自述 / R-e 自述数字相符 / R-f 单行 >64KB 红 / R-g 文件 >512KB 红（**R-f/R-g 覆盖 main+test+testFixtures**） | ★ | 执行验证：58 个 >600 行（最大 778）、**≥800 行 0 个**、exempt 0 条（无欠债登记）、无超长行/超重文件 |
| 24 | check-package-size.py | 业务域包 >30 件只许降，26-30 报警 | ◐ | 执行验证：2 个已登记包未增长；`session/service` 27 件报警 |
| 25 | check-stray-dirs.py | 游离目录（无跟踪文件、未 ignore、不在白名单） | ○ | **执行验证：当前红**——`docs/architecture-review/` 是本评估的产出目录（尚未提交 git），提交后自愈 |
| 26 | check-dead-members.py | 死 import/未使用/自 import/遗留 Logger 字段（扫 main+test） | ◐ | 执行验证通过 |
| 27 | check-log-exceptions.py | log 里禁 Throwable.toString()/getMessage() | ◐ | 执行验证：1,803 条 log 调用 0 命中 |
| 28 | check-log-level.py | warn 不用于 404/跳过语义 | ○ | 执行验证通过 |

**文档同步（12）**

| # | 守卫 | 判据 | 强度 |
|---|---|---|---|
| 29 | check-doc-truth.py | README「七个模块/控制器数/package-info 全覆盖」↔ 代码；锚点消失也红 | ◐（执行验证通过） |
| 30 | gen-arch-index.py --check | packages.md ↔ package-info 同步 | ◐ |
| 31 | gen-module-map.py --check | module-package-map.md ↔ 源码 | ◐ |
| 32 | gen-domain-index.py --check | domain-index.md ↔ 分类/源码 | ◐ |
| 33 | gen-pipeline-map.py --check | pipeline-map.md ↔ 插件代码 | ◐ |
| 34 | gen-port-map.py --check | port-map.md ↔ 接口/实现/调用方 | ◐ |
| 35 | check-qa-flow.py | qa-flow.md 引用类必须存在 | ◐ |
| 36 | check-ingest-flow.py | ingest-flow.md 引用类必须存在 | ◐ |
| 37 | check-known-gaps.py | 类注释声明缺口必须指向 docs/known-gaps.md | ◐（known-gaps.md 实测 8+ 项，状态/影响/替代路径三要素齐全） |
| 38 | check-package-info-fields.py | 20/20 域 package-info 四要素 | ◐（执行验证 20/20） |
| 39 | check-reference-truth.py | package-info/公开文档不断链、无旧包名、无死路径 | ◐ |
| 40 | check-comment-narrative.py | 注释只讲现在不写迁移史（棘轮） | ○ |
| 41 | check-comment-decor.py | 注释装饰风格统一 | ○ |
| 42 | check-go-anchors.py | Go 锚点注释只许减 | ○ |

（29-42 均执行验证通过，未逐一展开。）

### 4.3 ArchUnit 规则清单（28 条）

`ArchitectureRulesTest`（22 条）：A1 禁裸 `System.getenv`（自证导入面 >500 类）/ A2 属性类必须被 `@ConfigurationPropertiesScan` 覆盖（防静默取默认值）/ A3 属性类不得双装配 / A4 `install*` 只许装配层调用 / A5 禁裸 NUL 字节 / A6 禁 `@Lazy`（**零基线**，解环专项已清零）/ A7 裸 JDBC 白名单（27 条带理由）/ A8 禁字符串列名 wrapper（基线 1 条带理由）/ A9 `.last(` 只许纯字面量（**零基线**）/ A10 分层倒挂（service→controller、domain→service/controller；`domain.access` 守卫面有意豁免）/ A11 `*Mapper` 必须落 `..mapper..` 包 / A11b 仓储门面不得留 mapper 包（零基线，且按注解判 MyBatis mapper 修过假阳性）/ A13 生命周期钩子必须在 Spring bean 上（防注解静默失效）/ A15 JSON mapper 必须来自 JsonMappers 工厂（基线 8 条带理由）/ A16 service 不得依赖边界层（**零基线**，立规时 69 类已清零）/ A17 service 不得依赖具体技术（**零基线**，立规时 60 类已清零）/ A18a agent 协作者不得回引 AgentEngine（基线 9）/ A18b 运行态禁公共可变字段（基线 1）/ A19 domain 不得依赖 Spring 构造型/mapper 包 + repository 不得依赖 service（基线 3 + 零基线）/ A20 契约端口恰一个域侧实现（0 个=悬空、≥2=提供方不唯一，零基线）/ B257 agenttools/agentmanagement 不得直连业务域 service/mapper（零基线）/ B260 Agent 运行面不得依赖工具实现面（零基线）。

`ContractBoundaryRulesTest`（6 条）：B258-R1 contract 只依赖 JDK 与自身 / B258-R2 client 不得引用他域 mapper / B258-R3 模块 DAG 向下 / platform 面不得依赖业务域 / retrieval 面不得依赖业务域 / model-runtime 面不得依赖业务域。

**强度总评**：这套规则不是「为了有规则而写」——几乎每条都挂在真实事故上（`@Lazy` 掩盖环、钩子注解静默失效、Inline FQN 让依赖图失真、`asURI` 导入选项把 jar 类整段排除导致规则永远绿……类注释里有完整的踩坑史），且棘轮是**双断言**（基线外新增即红 + 基线条目不再违例也红，防规则空转）。这是本体系最值钱的部分。

### 4.4 「只许减不许增」棘轮的实现与漏洞（task 3d 专项）

实现形态（四类，都验证过）：
1. **计数型**：`controller-catch.baseline.json`（139→119）、`package-cycles.baseline.json`（cycles/sccs/l2_to_l3 全 0）、`contract-shape`（22 条）。
2. **清单型**：`file-size.baseline.json` 的 limits（59 条）/ exempt（0 条）、`api-envelope.baseline.json` pending、`platform-shapes.baseline.json`（16）、`json-key-freeze.baseline.json`、`domain-contracts.baseline.json`（edges+tiers）。
3. **代码内基线**：ArchUnit 的 `ratchet(baseline, actual)` 双断言（A6-A9、A15-A20、B257/B260）。
4. **登记型**：`MISPLACED_TEST_ALLOW`（10）、`ALLOWED_TEST_EXTRA`（2）、`ALLOWED_SKIPS`（3）——**登记失效也红**（防悄悄过期）。

漏洞与软肋（按严重度）：
1. **`--write` 无机械约束，签收是社会化过程**。任何有写权限的人都可以 `python3 scripts/check-*.py --write` 把当前状态登记为新基线。多数脚本有「有新增则拒绝 --write」的安全阀（domain-contracts、package-cycles），但 file-size 的 `--write` 会**把 limits 重置为当前值**——配合 R-b 的 30 行松弛，意味着**一个文件可以每次 PR +25 行、每次 --write 重置基线，慢性膨胀永不变红**（ImService 一周 +427 行被抓到只因单次超过 30；每次 +25 则永远绿）。
2. **intra-family 豁免允许无限新增**（check-domain-contracts ③ 档）：族内跨域**新增边与新增类都放行**（脚本注释明言「含新增边与新增类」）。当前 3 个族覆盖 7/20 个域（agent-runtime：session/agentmanagement/agenttools/memory；identity：agentmanagement/auth/system；knowledge：knowledge/wiki），33 个类已在此豁免下——「域间只经契约」的原则在族内实际不设增长上限。
3. **R10 域清单是手工的**：`DOMAINS` 列表硬编码 20 个域，脚本自述「新增域时同步这里——否则新域的跨域引用不会被本守卫看见」。新建顶层域包（无论 inbound 还是 outbound）会**整体逃逸**域间守卫，直到有人想起来改列表。
4. **绝对值永不归零**：棘轮只防增，不逼减。119 处控制器裸 catch、58 个 >600 行文件、6 条域间边、16 个 platform 业务形状、~100 个孤儿夹具——每一项都可以合法地永续存在。
5. **守卫计数文档漂移**：实际 42 条，CI yml 注释写「32 条」，评估文档写「40 条」（`check-doc-truth.py` 只校验 README 的模块/控制器数，不校验守卫数）。

---

## 5. 守卫漏网实例（3 个，全部带证据）

### 实例 1：Skills 管理面与 continue-stream 的 HTTP 面无任何测试，而 route-policy 守卫给不出信号

- 证据 A（无测试）：全仓 grep `knowledge-search|continue-stream|catalog`，`/api/v1/sessions/continue-stream/*` 与 `/api/v1/skills/catalog**` 只出现在**主码**（`boot/src/main/java/com/ragagent/config/RbacRoutePolicies.java` 的 addRule 与 `WebConfig.java` 的白名单），没有任何 `src/test` 文件引用这两个路径；`SessionStreamController.continueStream`（177 行，全仓第 10 大方法）零直接测试。
- 证据 B（守卫为何沉默）：`check-route-policy.py` 的判据是「路由必须有权限规则或登记公开」——它只回答「有没有人管权限」，不回答「有没有线格式保护」。1,349 个金片、48 个契约测试都没覆盖这两个面。
- 证据 C（影响面）：Skills 是 README 头条能力（「技能（Skills）：以 SKILL.md 为载体的三级注入」），`SkillCatalogController` + `SkillsCatalogController` 两个控制器的增删改查面**没有任何契约钉死**——后端改字段前端无声（正是 check-fe-contract-keys.py 注释里描述的事故形态）。域内服务层（agent-engine 的 `SkillCatalogServiceTest`/`DbSkillSourceTest`）有单测，但 HTTP 形状（外壳、分页结构、错误信封）无保护。
- 补充：主聊天 SSE 面（`/api/v1/knowledge-chat/{id}`、`/api/v1/agent-chat/{id}` 的非 embed 渠道）同样无 HTTP 级测试，只有 embed 渠道变体被 `W5dTerminalEmbedContractTest` 覆盖；流式链路本身由 `chat-pipeline-46c` Go 金片 + 单测保护。**即：产品最核心的「流式问答」端到端线格式，靠的是手工 AB 脚本（`scripts/ab-*.sh`）而非自动化契约。**

### 实例 2：~100 个孤儿金片 + 审计工具腐烂——「1349 份金片」的计数失真

- 证据 A：实测 1,349 个夹具中 1,249 个被 Java 测试字面引用；`doc-get.json`、`api-key-401.json`、`att-delete.json`、`ct-search-keyword.json`、`adm-list-page.json` 等 ~100 个只出现在 `scripts/record-*-golden.sh`（如 `record-ct-golden.sh: req ct-search-keyword.json GET '/tenants/search?keyword=beta'`）——录了、没人断。
- 证据 B：项目自建的审计工具 `scripts/audit-contract-fixtures.sh` ① 不在 `run-guards.sh`（不进门禁）；② 指向 `server/src/test/resources/contracts`——模块 `server` 已改名 `boot`（B165 迁移），脚本今天运行会直接找不到目录；③ 注释自述「禁止按输出批量删除」。三者叠加 = 孤儿问题被承认 3 个月（B31 实测 115 个）仍无机械治理。
- 违反的原则：README/评估文档以「契约金片 1349 份」作为质量证据——其中 7.4% 不提供任何保护。这正是反作弊规范里的「用基线/期望文件数量冒充覆盖」。

### 实例 3：22 个 >150 行的巨型方法无守卫——file-size 守卫的盲区

- 证据 A：`scripts/method-length-report.py`（存在且可跑）实测 **22 个 >150 行方法**，最大 `domains/src/main/java/com/ragagent/agenttools/sql/SqlSelectDeepChecker.java:268 deepCheck` **275 行**；`wiki/service/page/WikiLintService.runLint` 253 行；`agenttools/wiki/WikiReadSourceDocTool.execute` 246 行；`session/rag/plugin/PluginRerank.onEvent` 212 行；`session/controller/SessionStreamController.continueStream` 177 行。
- 证据 B：`method-length-report.py` **不在 run-guards.sh 的 42 条里**——没有棘轮、没有 CI 门禁。file-size 守卫按**文件行数**判（且允许 +30 行/PR 的松弛），一个 600 行文件里裹一个 275 行方法完全合法。
- 证据 C：这些巨型方法与实例 1 重叠（continueStream 既巨又无测试）——「最大方法」与「最少测试的面」是同一批代码，说明覆盖缺口不是随机的，而是集中在流程编排最复杂处。
- 违反的原则：README 三条设计原则之三「服务端分层：HTTP 面只编排、领域服务承载用例」——275 行的 deepCheck/246 行的 execute 实质是把用例塞进了一个方法，分层只是包级分层，方法级无任何机制兜底。

（候选但未入选的两个：① intra-family 族内无限新增——那是**有意设计**（分层解耦），列为 §4.4 软肋而非违例；② `-Dcontract.refresh=true` 无 CI 禁用——列为 §3.3 洞而非违例。）

---

## 6. 测试脆弱性抽样

| 指标 | 实测 | 判定 |
|---|---|---|
| `Thread.sleep` 总数 | **89 处**（40+ 个文件） | 需分层看 |
| — 其中 @Test 方法内**固定 sleep**（sleep 完直接断言） | **50 处** | ⚠️ 主要脆弱源。典型：`boot/.../im/ImPipelineTest.java:166`（sleep 500 后断言去重）、`SessionQueryPagedTest`（sleep 后断言置顶排序）、`SessionRepositoryTest/MessageRepositoryTest`（sleep 后断言分页/时序）、`PipelineLifecycleRecordingTest`（4 处） |
| — 其中 await()/轮询模式（sleep 在 helper 里） | 20 处 | ✅ 可接受（队列测试惯用法） |
| — 其中 stub/doAnswer 内的刻意延迟 | 13 处 | ✅ 有意为之（模拟慢依赖） |
| Awaitility / 等待库 | **未引入**（classpath 无） | 轮询全靠手写 helper |
| `@Disabled` | **1 处**（`RemoteApiChatTest`，需真 API key，已在 ALLOWED_SKIPS 登记） | ✅ |
| `@EnabledIfEnvironmentVariable` | 2 处（Weaviate/Milvus `*IT`，独立 integrationTest 任务、不接 check、默认 test 任务显式 exclude） | ✅ 模式正确 |
| `assumeTrue/assumeFalse` | 0 处 | ✅（Redis 类跳过走 `@BeforeAll + assumeTrue` 且有 ALLOWED_SKIPS 兜底） |
| `@Order` / `@TestMethodOrder` | **0 处** | ✅ 无显式顺序依赖 |
| 测试类静态可变字段 | **0 处** | ✅ 无共享可变状态 |
| `System.currentTimeMillis()` | 6 个文件（队列 TTL/限流测试为主） | ✅ 基本合理（测的就是时间行为） |
| 环境依赖但未登记理由的跳过 | 0（跳过棘轮 B218 全数登记） | ✅ |

**结论**：脆弱性图谱是「**50 处固定 sleep 集中在异步/Redis/流式边界**」——这些测试在慢机器或 CI 高负载下有 flake 风险，且拖慢套件；但没有顺序依赖、没有未登记跳过、没有静态污染，基线卫生好。脆弱性是**局部可修**（引入 Awaitility 或把固定 sleep 改条件轮询），不是体系病。

---

## 7. 改进建议（按优先级）

### Top 1（本周可做，直接补上金片完整性缺口）：给 HTTP 契约金片加 sha256 边车，并复活孤儿审计

1. 复用 Go 金片已验证的模式：`contracts/<name>.json` 旁生成 `<name>.json.sha256`，`GoldenContract.assertEquals` 加载时校验（与 `GoldenResources.load` 同款 fail-fast）。这给「值级篡改」补上机械防伪——改期望值必须连边车一起改，且 diff 里可见。
2. 修 `audit-contract-fixtures.sh` 的 `server/` → `boot/` 路径腐化，接入 `run-guards.sh`（或至少月度定时跑），按注释约定的三类处置（真孤儿删 / 录制清单证据链保留并标注 / 补断言）。
3. CI 中显式断言 `-Dcontract.refresh` 未开启（backend job 加一行 `./gradlew build -Dcontract.refresh=false` 或在 test 任务里 fail-fast 检测该属性），堵住「手滑重录」。

### Top 2（本迭代，补结构缺口 + 加方法级守卫）：把「总量叙事」换成「结构记分」

4. **方法级守卫**：把 `method-length-report.py` 接入 run-guards.sh 做棘轮（>150 行只许减，top 22 个进基线），与 file-size 形成文件/方法双维度。顺带给 `SessionStreamController.continueStream`（177 行且无测试）开第一刀。
5. **最低测试数棘轮**：对 domains 各域立「测试数 ≥ f(主码 LOC)」的棘轮（如 ≥5/kLOC），当前 initialization（0.4）、vectorstore（1.9）、agentmanagement（4.2）、embedchannel（4.0）进首批清单；contract 模块补载荷/视图直接单测（20k 行 11 测试是体系里最刺眼的不对称）。
6. **补金片面**：`/api/v1/skills/catalog**`、`/api/v1/sessions/continue-stream/*`、非 embed 主聊天 SSE 面，按现有 `record-*-golden.sh` 范式补录制+断言（Skills 是头条能力，线格式裸奔不可接受）。

### Top 3（持续，收紧棘轮的绝对值与家族豁免）：

7. **棘轮加绝对值上限**：给长期高位项设「只降」的绝对红线并分季收敛——控制器裸 catch 119 → ≤60（每域每次重构必减）、>600 行文件 58 → ≤40、platform 业务形状 16 → ≤8。
8. **family 豁免改为「新增需登记」**：intra-family 目前新增类全放行；改为族内新增类进基线清单（带理由），与 owner 档同构——保住分层解耦的收益，同时让「session→agentmanagement 再加第 12 个类」需要一次显式决策。
9. **域清单机械化**：`check-domain-contracts.py` 的 `DOMAINS` 改为从目录扫描推导（与 `check-stray-dirs.py`/`gen-domain-index.py` 交叉校验），消灭「新域包整体逃逸」；守卫计数（42）进 `check-doc-truth.py` 判据，消灭 CI yml「32 条」/文档「40 条」的漂移。
10. **sleep 治理**：引入 Awaitility（或统一扩展现有 `await(` helper），把 50 处 @Test 内固定睡眠按「测时间行为（保留）/ 纯等异步（改轮询）」分类收敛；前者加注释声明「本测试刻意等待 TTL/限流窗口」。

---

## 附录 A：本报告的证据文件索引

- 测试计数：`*/src/test/**`、`*/src/testFixtures/**`（grep `@Test`/`@ParameterizedTest`/`@SpringBootTest`/`*ContractTest.java`）
- 域覆盖表：`domains/src/main/java/com/ragagent/<域>/` × `domains/src/test|boot/src/test/java/com/ragagent/<域>/`
- 金片：`domains/src/testFixtures/resources/contracts/`（1,349）、`platform/src/testFixtures/resources/golden/`（8+.sha256）、`domains/src/testFixtures/java/com/ragagent/fixtures/{GoldenContract,ContractPaths}.java`、`scripts/check-contract-sync.py`、`scripts/contract-signature.baseline.json`、`scripts/audit-contract-fixtures.sh`
- 守卫：`scripts/run-guards.sh`（42 入口）、各 `check-*.py`、`boot/src/test/java/com/ragagent/arch/{ArchitectureRulesTest,ContractBoundaryRulesTest}.java`、各 `*.baseline.json`
- CI：`.github/workflows/ci.yml`、`scripts/test-summary.py`、`scripts/ci-smoke.sh`、`retrieval-engine/build.gradle.kts`（integrationTest 任务）
- 漏网实例：`boot/src/main/java/com/ragagent/config/{RbacRoutePolicies,WebConfig}.java`、`domains/src/main/java/com/ragagent/session/controller/SessionStreamController.java`、`scripts/method-length-report.py`、`scripts/record-ct-golden.sh` 等
- 脆弱性：`boot/src/test/java/com/ragagent/im/ImPipelineTest.java:166`、`domains/src/test/java/com/ragagent/datasource/task/RedisDataSourceSyncTaskQueueTest.java` 等 89 处 sleep

## 附录 B：方法与边界声明

- 只读评估：未修改/删除任何现有文件；本报告写入 `docs/architecture-review/05-quality-guard-assessment.md`（该目录当前未被 git 跟踪，`check-stray-dirs.py` 会因此报红——**提交本目录后自愈**，非存量缺陷）。
- 42 条脚本守卫中 **39 条本机执行验证通过**；1 条（check-stray-dirs）因上述原因红（提交本目录后自愈）；2 条全仓扫描型（check-package-cycles / check-module-graph）在本沙箱触发 OOM/kill，采用「代码走读 + 基线文件核对」替代：环/SCC 状态以 `scripts/package-cycles.baseline.json` 实测内容为准（cycles 0 / sccs 0 / package_sccs 5 组全登记 / depend_on_config 0 / l2_to_l3 0）。
- 执行验证补充读数：check-json-key-case 基线 249 条已复核豁免 + 冻结覆盖 144 个文件；check-fe-contract-keys 基线 48 条；check-tenant-scope 14 个 capture 站点全部经 TenantScope；check-duplicate-classes 2,084 个简单类名跨模块唯一；gen-arch-index 354 份 package-info 同步；gen-port-map 33 个 Port；check-reference-truth 368 个文件无断链。
- 未执行 `gradle build`/`npm test`（纪律要求），测试通过率（如 HANDOFF 声称的 4,9xx/0/4）未复核，以 CI 历史为准。
