# Go 移植欠债与泛类型返回整治（G 系列）

> 立项：2026-11-03
> 基线：`60bd7c07c`（分支 `v0.10.x`，JDK 21 + Boot 3.3.13 + Jackson 2.17.3）
> 触发：架构师指出「接口的入参回参不是具体类型」；排查后确认是 Go 逐字节复刻留下的同一根因，在多个面上都有表现

---

## 一、一句话结论

这不是一个「返回类型写法」问题，而是 **Go 语言的四类习惯被逐字搬进 Java** 后的总账：类型泛化、错误处理、多元返回、参照物失联。本方案按「有实质危害」优先，分 8 批收口。

---

## 二、判定标准（先说清什么不改，避免过度整治）

### 算欠债

| 判据 | 说明 |
|---|---|
| 编译期失能 | 类型写成 `Map`/`Object`/`?`，改字段名时编译器不报错，只能靠测试兜 |
| 对外行为不稳 | 内部异常原文直接成为 4xx 响应文案，前端看到的提示随实现漂移 |
| 语义错译 | Go 特有语义（nil slice 与空 slice 不同、多返回值）被逐字搬进 Java 后，含义变了 |
| 参照物失联 | 注释拿「已删除的 Go 实现」当基准（如"与既有实现逐字节一致"），读者无从核对 |

### 不算欠债（本方案明确保留，不列入改动）

| 项 | 理由 |
|---|---|
| MyBatis 注解里的 `"SELECT " + COLS + " FROM …"`（281 条 / 36 mapper） | 无 XML mapper、无 `<script>`，列名是编译期常量，属 MyBatis 标准写法 |
| 已有实例字段与构造器的 `*Ops` 类 | 是委托/协作对象，不是 Go 的包级函数；历轮已整治 |
| 哈希 / 加密 / 向量序列化 / HTTP wire 上的 `byte[]` | 域内正确类型 |
| ES painless 的 `ctx._source.*`、`SSLContext ctx` | 上游脚本语法与 JDK 惯例 |
| 纯无状态函数库（`DorisSql` / `QaSupport` / `WikiTextUtils` / `WikiLinkify`） | 改写成本远超收益；只盯承载编排的 `WikiIngestRunSupport` / `WikiRequestSupport` |
| `SortedMapSerializer` 里的「按 UTF-8 逐字节比较」 | 算法事实描述，不是移植黑话 |

---

## 三、现状盘点（统计口径：主码 `src/main/java`）

| # | 面 | 规模 | 现有守卫是否覆盖 |
|---|---|---|---|
| 1 | `ResponseEntity<Map<String, Object>>` 返回 | 40 处 / 12 文件 | 否 |
| 2 | `Map<String, Object>` 当方法参数 | ~107 处 | 否 |
| 3 | 内部异常原文透前端 | ≥83 处 / ~20 文件 | 否（`check-exception-semantics` 只拦空 catch） |
| 4 | Go 式错误前缀拼接 `"...: " + e.getMessage()` | ~200 处 / ~40 文件 | 否 |
| 5 | Go `append(out, item)` / nil-slice 语义 | 2 处 | 否 |
| 6 | `JdbcTemplate` + `List<Object> args` + `toArray()` | 6 处 / 5 文件 | 否 |
| 7 | 用 `boolean[]{false}` / `int[]` / `Object[]` 模拟 Go 多返回值 | 10 处 | 否 |
| 8 | 注释里的 Go 参照物（`逐字节对齐`/`与 Go 实录`） | ~65 处（主码） | 部分（`check-go-anchors` 只认 `对照 Go`/行号/`gorm`） |
| 9 | `XxxID` 大写 ID | 25 文件 / 2 个跨模块符号 | 否 |
| 10 | `byte[]` 越过业务边界 | 6 处 / 5 文件 | 否 |

> 数字为统计 + 抽样，实施时逐条核到行；允许 ±10% 修正。

---

## 四、批次表

| 批次 | 内容 | 规模 | 风险 | 验收 |
|---|---|---|---|---|
| **G1** | 接口返回类型具体化 | 40 处 / 12 文件 | 中 | 契约金片逐字节 |
| **G2** | 内部异常不透前端 | 83 处 / ~20 文件 | 低 | 错误响应金片 |
| **G3** | 错误前缀拼接收口 | ~200 处 | 低（机械） | 编译 + 单测 |
| **G4** | nil-slice 语义纠正 | 2 处 | 低 | 单测 |
| **G5** | 手工 SQL 参数化 | 6 处 / 5 文件 | 中 | 集成测试（真 PG） |
| **G6** | 多元返回模拟改 record | 10 处 | 低 | 单测 |
| **G7** | 注释去 Go 参照物 + 守卫扩面 | ~65 处 | 低 | 守卫 |
| **G8** | 小项（`byte[]` 边界 / 命名 / 公开可变字段 / 吞异常） | ~20 处 | 低 | 编译 + 单测 |

### 为什么这个顺序

1. **G2 排最前**：它是唯一对外有影响的（信息泄漏 + 前端文案不稳），改法统一，风险最低。
2. **G1 排第二**：用户与架构师最关心的面，但动的是 HTTP 出口，必须靠金片兜底，放在异常收口之后能复用同一批回归。
3. **G3 排第三**：面最大但纯机械，单独一批好回滚。
4. **G4~G6** 都是小面高确定性，穿插做，风险近零。
5. **G7 最后**：注释 + 守卫一起做，守卫落地后自动防回流。
6. **G8** 零散收尾。

---

## 五、每批的改法

### G1 接口返回类型具体化

**判据**：返回结构固定（哪怕只有一个字段）→ 定义 record；确实是任意键值透传（如动态配置对象）→ 保留 `Map` 并在注释写明理由。

**改法**：
1. 为每个端点定义 `public record XxxResponse(...)`（放对应 `dto` 包或控制器内嵌）。
2. 用该 record 构造替代 `LinkedHashMap` 手工 `put`。
3. **键名与键序必须与现状逐字节一致**——金片是权威；若某端点是 `LinkedHashMap` 保序，record 按声明序输出即可对上。
4. 统一外壳（`{code,message,data}`）不动。

**风险点**：`EmbedChannelRows.row()` 这类"行视图工具"返回 `Map` 且可能被多处复用，改造时要么整族换 record，要么先只换签名保留内部 Map 构造（再逐步收）。优先选前者，避免出现"签名是 record、内部还是 Map"的半吊子。

### G2 内部异常不透前端

**现状三种形态**：
```java
throw BizException.badRequest(e.getMessage());   // 把 NPE/SQL 原文当 400 文案
throw BizException.internal(e.getMessage());
return ResponseEntity.ok(...) 之前 catch 后直接拼 e.getMessage()
```

**改法**：
- 对**客户端可解释**的异常（参数校验、业务规则）→ 保留业务文案常量。
- 对**内部异常**（IO/SQL/NPE/框架异常）→ 对外固定文案（`"internal error"` 或既有统一文案），**原始异常写日志并带 stack**。
- 新增一个 `BizException.internalGeneric(String op, Throwable cause)` 之类的出口，避免每个调用点各写一套。

**红线**：错误响应的 `code` 与 `message` 属于契约面，任何变化都要过金片；本批只允许「把可变文案换成固定文案」这一类变化，且必须逐条比对金片。

### G3 错误前缀拼接收口

**现状**：`throw new IllegalStateException(prefix + ": " + e.getMessage(), e)`（Go `fmt.Errorf("%s: %w")` 的逐字搬运）。

**改法**：
- 统一走一个 `ErrorWrap.of(String op, Throwable cause)`，保留「操作名 + 原因」的信息量，但不再各处手拼。
- 机械替换，可脚本化。

**不改**：日志里的字符串拼接（日志不是契约，且拼进去便于检索）。

### G4 nil-slice 语义纠正

- `GitLabConnector.append(List out, item)` 的「一条都没有 = null」→ 返回 `List.of()`，调用方按空列表判断。
- `HeuristicSplitter.appendChunk(List out, ...)` 返回同一个 `out` 的 Go `append` 写法 → 改为在方法内构造并返回。

### G5 手工 SQL 参数化

`JdbcTemplate` + `List<Object> args` + `toArray()` + 三目拼 WHERE → `NamedParameterJdbcTemplate` + `MapSqlParameterSource`。

**收益**：参数与 SQL 同处、条件拼装不再是字符串手术；顺手吃掉同文件的 `Map<String,Object>` 结果集。

### G6 多元返回模拟改 record

- `boolean[]{false}` 闭包出参 → `AtomicBoolean`。
- `int[]{-1,-1,MAX}` + `boolean[]{false}` → `record SplitResult(int idx, int len, boolean found)`。
- `Object[] q`（`q[0]`=question / `q[1]`=source）→ `record QuestionRef(String text, String source)`。
- `List<T> out` 当出参 → 改为返回列表（与 G4 同法）。

### G7 注释去 Go 参照物 + 守卫扩面

- 把「与既有实现逐字节一致」「逐字节对齐既有客户端」改成「由契约金片钉住」。
- 保留算法事实类描述（UTF-8 字节序比较等）。
- `scripts/check-go-anchors.py` 的 `PATTERNS` 增加 `逐字节对齐|逐字节一致|与 Go 实录|Go 版|移植` 一类键，并对算法事实登记白名单（棘轮基线，只许减）。

### G8 小项

- IM 域 6 处 `byte[]` 越过业务边界 → 收一个 `RawBody`（带 contentType + 懒解码）。
- `batchUpdateChunkTagID` / `getModelID` → `Id`（动契约接口，需查前端与匿名实现）。
- `QaSupport.userMessageID` 公开可变字段 → 私有 + `userId`。
- `catch (RuntimeException e) { return null; }` / `{ return new LinkedHashMap<>(); }` → 至少记日志并返回空对象，不假装成功。
- `AgentPrompts` 的 `ctx` → `section`。

---

## 六、守卫设计（防回流）

| 守卫 | 判据 | 形态 |
|---|---|---|
| 新增 `check-controller-return-types.py` | 控制器公开方法不得返回 `ResponseEntity<Map<String, Object>>` / `ResponseEntity<Object>` | 棘轮基线（G1 完成后登记剩余合法项，只许减） |
| 扩 `check-exception-semantics.py` | 禁止 `BizException.*(e.getMessage())` 直达客户端 | 硬零 |
| 扩 `check-go-anchors.py` | 增加 `逐字节对齐/逐字节一致/与 Go 实录` 键 | 棘轮 + 白名单 |
| 新增 `check-multi-return-sim.py` | 禁止 `boolean[]{` / `Object[]{` 出现在业务方法体内 | 硬零 |

每条守卫配套**红态探针**：故意造一个违例，确认脚本非零退出后才计入。

---

## 七、验证口径

每批收尾：

1. **定向测试**（按域点名，10~30 秒）。
2. **全量闸门**（每批一次）：`mvn -B clean verify` + `scripts/run-guards.sh`。
3. **契约守恒**：金片变更必须逐条列出「文件 / 前值 / 后值 / 根因 / 是否获批」。除 G1 允许「键序在 record 声明序下重排但深比较等价」外，任何字节变化都要单独签字。
4. **冒烟**：`/health` 200、`/v3/api-docs`（enabled）200、未认证 401、坏 JSON 400。

---

## 七点五、实施记录与三处判断修正（2026-11-03）

盘点阶段的统计口径在逐条核到行之后被修正了三次。**这三处修正是本方案最重要的产出**——它们把"看起来该改"变成了"确认不该改"。

### 修正一：G4 的 `GitLabConnector` 不是错译，撤回

原判据把它当成 Go 的 nil-slice 语义搬运。实际情况：

- `GitLabConnector` 类注释有独立小节 `<h2>空结果返回 null</h2>`，明确说明"一条都没有时返回 null（不是空列表），FetchedItem 的 JSON 形态本身就是契约"，并主动标注"与本项目其它模块相反"。
- `GitLabConnectorTest.fetchAllReturnsNullWhenNothingMatched`（:398）直接断言 `.isNull()`。

**结论**：有意契约 + 有测试守护 → **保留**。G4 只剩 `HeuristicSplitter` 的 `appendChunk` / `appendOversizeBlock`（返回同一个 List 的 Go append 写法），已改为 `void`。

### 修正二：G3 从"200 处前缀拼接"收敛为"38 处补 cause"

593 处 `"...: " + e.getMessage()` 中：

| 形态 | 数量 | 处理 |
|---|---|---|
| 已保留 cause（`..., e)`） | 439 | 标准 Java 写法，**不动** |
| 抛异常但丢 cause | 33~38 | **补 `, e`**（排障时不再丢 stack） |
| 返回错误字符串 / 设错误字段 | 119 | 字符串无处放 cause，**不动** |

补 cause 时连带为 10 个自定义异常类新增 `(String, Throwable)` 构造器（纯增量重载，不破坏现有调用）。

### 修正三：G1 的 40 处里有一半不适合改

`ResponseEntity<Map<String, Object>>` 有两种成因，只有一种该改：

| 成因 | 特征 | 处理 |
|---|---|---|
| **结构固定** | 方法只有一个返回形状 | 改 record |
| **同方法多形状** | 参数校验失败时 `return plain(400, "...")`，成功时返回行视图；或按 `status` 分支返回不同键 | **现改不了** |

第二种本质是 Go handler "直接写响应 + 直接设状态码"的风格。要收成单一返回类型，必须先把 `return plain(400, ...)` 改成 `throw BizException.badRequest(...)`，让全局异常处理器统一写出——**这属于 G2 的下游**，且会改动错误响应的线格式，需单独确认。

**本批实际完成**：`SkillController`（4 处：目录、选择器、文件列表、文件内容）+ `AgentController.list`（1 处）。其余 35 处登记为 G1 待办，前置条件是 G2。

### 修正四：G7 取消

主码 64 处 `逐字节` 中，绝大多数描述的是"**与既有数据 / 契约金片一致**"（例如 RSS 游标要落 `last_sync_cursor` 这个 jsonb 列、`ModelView` 由具名金片钉住），**读者可以核对**，不属于失联参照物。

真正提到 Go 的只有 2 处，且都是 `gofeed` —— 那是真实的第三方 RSS 解析库名，保留反而比改写更准确。

**结论**：G7 无实质欠债，取消。守卫也不扩（扩了会误伤大量合理表述）。

---

## 七点六、G1/G2 收口结果（2026-11-03）

**G1 泛类型返回：40 → 6**

| 处理 | 数量 | 说明 |
|---|---|---|
| 收成 record | 34 | skill 4 · agents 列表 1 · embedchannel 13 · MCP 授权 URL 3（含契约方法）· IM 5 · 其余零散 |
| 保留 `Map` | 6 | Steer 4 + `ok()` 辅助（同一端点按 `status` 分支返回不同键，改 record 会变线格式）· `WikiRequestSupport.rawError`（错误辅助，与 IM 同类问题，未纳入本轮） |

**G2 异常出口：三步全部完成**

- 第一步 12 处：`internal(e.getMessage())` → 固定文案 + 原始异常进日志。1 处按证据回退（该 catch 兜的是入参校验）。
- 第二步 5 处：写库/索引失败不再冒充 400（全部在 `ChunkQuestionService`）。
- 第三步 5 处：业务异常不再被降级成 400，保留原 code。

**IM 错误格式统一（附带契约变更）**

`ImChannelController.plain()` 原手写 `{"error": msg}` 裸体，且把 4xx 状态藏在 200 响应里
（`{"code":0,"data":{"error":…},"message":"ok"}`）。改为抛 `BizException` 走统一外壳后：

```diff
- {"code":0,"data":{"error":"field 'platform' is required"},"message":"ok"}
+ {"code":1000,"data":null,"message":"field 'platform' is required"}
```

9 条金片随之更新；`scripts/contract-signature.baseline.json` 已签字。
前端无需同步：`frontend/src/utils/request.ts` 的拦截器本就同时兼容 `message` 与 `error` 两种形态。

---

## 七点七、G5 / G8 收口（2026-11-03 续）

**G5 动态 IN 命名参数化：8 处**

| 文件 | 处数 | 形态 |
|---|---|---|
| `KnowledgeSpanRepository` | 3 | 条件 WHERE + BFS 批量查询 + 批量 UPDATE |
| `HousekeepingService` | 3 | 三处 `Collections.nCopies` 手工占位符 |
| `KnowledgeStartupRecovery` | 2 | 复位 UPDATE + 复位后复检查询 |

均为「参数与 SQL 靠顺序对齐」的写法，改用 `MapSqlParameterSource` + `IN (:ids)` 原生展开。
三处都有真 PG 集成测试覆盖（`KnowledgeSpanRepositoryTest` 8 条 / `HousekeepingServiceTest` 15 条）。

注意：`NamedParameterJdbcTemplate` **不继承** `JdbcTemplate`，因此仓库同时持有两个模板（命名参数走前者，
既有位置参数调用点走 `namedJdbc.getJdbcTemplate()`），避免为了改 3 处而重写 9 处。

**G8 只做 2 处，其余三项明确不做**

| 项 | 决定 | 理由 |
|---|---|---|
| `catch { return null }` / `{ return new LinkedHashMap<>() }` | ✅ 已改（补 warn 日志） | 原来连日志都没有，排障时无从发现 |
| `byte[]` 越过业务边界（6 处） | 不做 | 需新增 `RawBody` 包装并改 5 个签名，落在 IM 回调解析主路径，收益是类型表达力，留独立批 |
| `XxxID` → `Id`（25 文件） | 不做 | 动两个跨模块契约方法名（`batchUpdateChunkTagID` / `getModelID`），纯命名统一无功能收益 |
| `QaSupport.userMessageID` 公开可变字段 | 不做 | 20+ 处访问点，改名收益低 |

---

## 八、不做的事

- 不引入新框架（不换 ORM、不引 MapStruct）。
- 不做纯命名美化（`XxxOps` 类名保持；只动 G8 列出的三个符号）。
- 不重写已界定的合理静态函数库。
- 不改动 Go 仓（已冻结）。
