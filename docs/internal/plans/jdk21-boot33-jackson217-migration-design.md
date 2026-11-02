# JDK 21 + Spring Boot 3.3 + Jackson 2.17 迁移设计

> 日期：2026-11-02  
> 设计基线：`ca2778db6`（后续实施以实际集成分支基点为准）  
> 状态：**已实施**（分支 `v0.10.x`，批次 R6-17；验证读数见 `HANDOFF.md`）  
> 目标读者：迁移实施 agent、代码审查者、测试与发布负责人

## 0. 决策摘要

本项目从当前的 JDK 21 + Spring Boot 4.1 + Jackson 3 主栈，迁移为：

- **JDK 21**：保持不变，继续使用虚拟线程和 JDK 21 语言/API 能力；
- **Spring Boot 3.3.13**：作为明确锁定的框架版本；
- **Jackson 2.17.x**：使用 Boot 3.3.13 BOM 管理的补丁版本，不在子模块重复声明版本；
- **MyBatis-Plus 3.5.17**：保持版本，改用 Boot 3 starter；
- **springdoc 2.8.17**：作为目标兼容版本，PoC 失败时不得自行换版，须回到设计评审；
- **JUnit / Spring Framework / Spring Security / Spring Data / Tomcat**：全部由 Boot BOM 统一管理。

采用**单一集成分支、核心串行、叶子模块受限并行**的实施策略。中心依赖和 JSON 组件未完成前，不开放全模块并行。

本设计不等同于“降低 JDK 版本”。JDK 21 不变，因此不重写虚拟线程、执行器、租户上下文传播或并发模型。

## 1. 背景与历史证据

仓库已有以下可复用证据：

1. `version-upgrade-assessment-2026-10-17.md`：项目历史上使用过 Boot 3.3.5，并有 Boot 3.5 的实际升级记录；
2. `boot4-upgrade-assessment-2026-10-17.md`：记录了 Boot 4 + Jackson 3 的正向迁移路径、核心文件和风险；
3. `jackson-annotation-hygiene-b269.md`：证明字段顺序、setter-only 可见性和改名注解具有承重作用，不能作为风格噪音清理；
4. 当前架构守卫、契约金片、录制测试和模块依赖矩阵，可作为迁移回归基线。

历史代码只用于确认旧 API 和依赖组合。**禁止用历史文件整文件覆盖当前实现**，因为后续业务、修复和测试均已演进。

## 2. 目标与非目标

### 2.1 目标

- 在 JDK 21 上完整运行 Spring Boot 3.3.13；
- Jackson databind/core/datatype 回到 Jackson 2.17，Jackson annotations 保持官方兼容包；
- 保持现有七模块架构、业务行为、线程模型和数据访问边界；
- 默认保持 HTTP、SSE、JSONB、Redis/队列载荷和 Provider 线协议兼容；
- 所有必要线格式变化均形成可审查、可回滚的审批记录；
- 最终移除不再需要的 Jackson 3 依赖和 `tools.jackson.*` 主码/测试码引用；
- 形成其他 agent 可分批实施、独立验证的实施计划。

### 2.2 非目标

- 不迁移到 JDK 17；
- 不改变现有七模块划分，不拆微服务；
- 不重写虚拟线程、SSE 轮询、TenantScope 或 Agent 执行循环；
- 不引入 Lombok、MapStruct、BO/VO 双语系或字段注入；
- 不做全仓缩进、import、注释或格式化改造；
- 不夹带业务功能、安全修复、删除闭环或性能重构；
- 不设计生产发布、滚动兼容或停服切换；发布策略另行评审；
- 不通过刷新测试基线掩盖真实回归。

## 3. 兼容性决策

### 3.1 默认零契约变化

迁移默认要求以下面保持现有形状和语义：

- HTTP API 状态码、响应外壳、字段名、字段顺序、空值与省略规则；
- SSE 事件名、事件顺序、帧字段、终态和错误帧；
- JSONB 已持久化内容的读写；
- Redis Stream、任务队列和跨实例载荷；
- LLM、Embedding、Rerank、OAuth、MCP、IM 和连接器的外部协议载荷；
- OpenAPI 的消费语义；实现版本号或非业务元数据变化需单独说明。

### 3.2 审批后变更

允许因框架差异产生少量契约变化，但必须满足：

1. 先证明无法通过现有 Mapper、Module、Mixin 或边缘适配保持兼容；
2. 记录涉及文件、前后差异、根因、消费者、回滚方式和审批人；
3. 同步更新金片、前端类型/消费方、API 文档和迁移说明；
4. 契约变化独立提交，不与机械包名替换混合；
5. 未获审批的金片变化一律视为迁移未完成，而非直接重录通过。

## 4. 目标依赖矩阵

| 组件 | 当前方向 | 目标 | 实施约束 |
|---|---|---|---|
| JDK | 21 | **21** | 不改 `java.version`，保留虚拟线程 |
| Spring Boot | 4.1.x | **3.3.13** | 根 POM 唯一版本来源 |
| Spring Framework | 7.x | Boot 管理的 6.1.x | 不手工覆盖单个子模块版本 |
| Spring Security / Data | Boot 4 代际 | Boot 3.3 BOM | 验证 Filter、RBAC、Repository 和测试上下文 |
| Jackson core/databind | `tools.jackson.*` | `com.fasterxml.jackson.*` 2.17.x | annotations 例外，不改包 |
| Jackson datatype | `tools.jackson.datatype.*` | `com.fasterxml.jackson.datatype.*` | 版本由 BOM 管理 |
| Jackson annotations | `com.fasterxml.jackson.annotation` | **不变** | 不按“去 Jackson 2”误删 |
| MyBatis-Plus | Boot 4 starter | **3.5.17 Boot 3 starter** | 保留 `mybatis-plus-jsqlparser` 拆分依赖 |
| springdoc | 3.1.1 | **2.8.17** | PoC 验证 Boot 3.3.13；失败回到设计评审 |
| JUnit / Platform | Boot 4 管理 | Boot 3.3 管理 | 检查 Surefire 与 launcher 对齐 |
| Tomcat / Servlet | Tomcat 11 / Servlet 6.1 | Boot 管理的 Tomcat 10.1 / Servlet 6.0 | 验证 Filter、SSE、错误响应和异步行为 |

`pom.xml` 继续作为版本唯一来源。子模块只声明依赖，不复制版本号。

## 5. Jackson 迁移设计

### 5.1 中心组件先行

第一批中心文件至少包括：

- `platform/.../web/JsonMappers.java`；
- `platform/.../web/ContractJsonModule.java`；
- `platform/.../web/ContractJsonMixins*.java`；
- `platform/.../web/ToolJson.java`；
- `model-runtime/.../modelcontext/ModelJsonValues.java`；
- `boot/.../config/WebConfig.java` 的 Mapper 自定义器；
- Event、Stream、Langfuse 和其他独立 Mapper 创建点。

中心组件未形成稳定编译接口前，不安排业务模块并行迁移。

### 5.2 包与 API 映射

| Jackson 3 | Jackson 2.17 目标 |
|---|---|
| `tools.jackson.core.*` | `com.fasterxml.jackson.core.*` |
| `tools.jackson.databind.*` | `com.fasterxml.jackson.databind.*` |
| `tools.jackson.datatype.*` | `com.fasterxml.jackson.datatype.*` |
| `ValueSerializer` | `JsonSerializer` |
| `ValueDeserializer` | `JsonDeserializer` |
| `SerializationContext` | `SerializerProvider` |
| `JsonParser.getString()` | 按语义使用 Jackson 2 的文本读取 API |
| Boot 4 Mapper 自定义器 | Boot 3 的 `Jackson2ObjectMapperBuilderCustomizer` |

Jackson 2 的 `JsonMapper.builder()` 继续作为统一构造方式，避免退回散落的可变 Mapper 配置。

### 5.3 Mapper 创建点审计

Jackson 2 与 Jackson 3 在未知属性等默认行为上存在差异。必须扫描并分类所有：

- `new ObjectMapper()`；
- `new JsonMapper()`；
- `JsonMapper.builder()`；
- Spring 注入的 Mapper；
- 测试和 fixtures 中的 Mapper；
- 第三方 SDK 自带的 Mapper。

每个创建点必须明确：复用 `JsonMappers.lenient()`、注册 `ContractJsonModule`，或登记为独立外部协议 Mapper 并说明配置理由。禁止假定“中心工厂已改，所以所有 JSON 路径都已覆盖”。

### 5.4 注解和字段顺序冻结

本次不得顺手清理：

- `@JsonProperty` 改名、字段序和 setter-only 可见性注解；
- `@JsonPropertyOrder`；
- `@JsonInclude` 三态/省略协议；
- 外部协议所需的 snake_case 键；
- 现有 Mixin 映射表。

record 与 class 的字段顺序行为分别验证。不得把 Jackson 3 时代的默认行为假设带入 Jackson 2。

### 5.5 临时双栈边界

迁移过程中允许 Jackson 2/3 短暂共存，但必须满足：

- 仅在集成分支存在；
- 不将 Jackson 2 与 Jackson 3 的 `JsonNode`、`ObjectMapper`、`Module` 混作同一类型传递；
- 不让长期业务代码新增 `tools.jackson.*` 引用；
- 最终验收前删除不再需要的 Jackson 3 依赖和兼容桥。

## 6. Spring Boot 回退设计

### 6.1 优先处理依赖与自动配置

- 根 POM 切换 Boot 3.3.13；
- MyBatis-Plus 改为 Boot 3 starter；
- springdoc 改为 2.8.17；
- 由 BOM 对齐 Spring、JUnit、Tomcat 和 Jackson；
- 恢复 Boot 3 对应的 Jackson Mapper 自定义器；
- 检查条件装配、配置属性和自动配置名称变化。

### 6.2 行为验证重点

- Web 安全链、API Key、RBAC 和 Filter 顺序；
- Controller 参数绑定、Validation 和异常映射；
- SSE 写出、续传、客户端断开和 STOP；
- MyBatis 租户/软删守卫、事务和分页；
- Actuator、健康检查和指标；
- Redis 队列与 StreamManager；
- 应用启动、可执行 jar 和容器内迁移脚本路径。

### 6.3 不在本批修改的内容

- JDK 虚拟线程调用；
- 业务类层次和包名；
- API 路径与角色策略；
- 数据库 schema；
- 业务超时、重试或取消策略。

除非迁移直接暴露阻断问题，否则这些主题另开批次。

## 7. 代码风格约束

参考 `~/users/billy/gac-app-sever` 的薄 Controller、独立映射层、按域错误码和用例事务边界，但保留本项目已经形成的现代风格。

### 7.1 本次触碰代码必须遵守

- Controller 只做协议适配，复杂业务分支进入用例 Service；
- Service 表达用例，Repository/Mapper 只处理持久化；
- DTO 继续使用 Request / Response / View / record，不引入 BO/VO；
- 使用显式构造器注入，不引入 Lombok 或字段注入；
- 复杂映射独立成类，不在本迁移中强制引入 MapStruct；
- 可复用错误使用稳定错误码或常量，不新增魔法数字；
- 数据库原子写使用 `@Transactional(rollbackFor = Exception.class)`；
- 日志只记录必要 ID、状态和耗时，不记录完整请求或 DTO；
- 保持现有 Spotless、import 顺序、4 空格和 `package-info` 规则。

### 7.2 风格改造范围

仅约束本次迁移新增或修改的代码。不得为符合风格而额外重排未触碰文件，避免扩大 diff 和合并冲突。

## 8. 协作与依赖设计

采用单一集成分支，工作分为三类：

### 8.1 核心串行任务

- 基线、依赖清单和契约快照；
- 根 POM、BOM、starter 和构建插件；
- Jackson 中心 Mapper、Module、Mixin 和 Web 自定义器；
- 最终删除 Jackson 3、全量集成和验收。

这些任务存在强依赖，不并行修改。

### 8.2 受限并行任务

中心 API 在目标依赖下编译稳定后，才可按不重叠文件集分配：

- model-runtime；
- retrieval-engine + agent-engine；
- domains 的外部连接器；
- domains 的 session/knowledge/wiki 等业务切片；
- boot 的 Web/集成测试与启动验证。

每个 agent 必须获得明确文件清单、禁止修改范围和验收命令。共享文件由核心任务负责人修改，不通过“同时改同一文件”解决依赖。

### 8.3 集成纪律

- 子任务可以形成独立小提交，但未通过集成验收不得合入主分支；
- 每个可交付批次在合入前必须全绿；
- 不用 `git checkout` 历史文件覆盖当前实现；
- 不通过刷新 baseline、删除测试或放宽 `-Werror` 获得假绿；
- 所有契约审批变化独立提交并附审批记录。

## 9. 测试与验收设计

### 9.1 迁移前基线

在 JDK 21 下执行并保存：

```bash
mvn -B verify
bash scripts/run-guards.sh
```

同时保存依赖清单、契约金片签名、测试数量、跳过数量和当前启动冒烟结果。若当前基线不全绿，先区分并修复或登记既有问题，再开始迁移。

### 9.2 每层验收

- 编译：主码、测试码、testFixtures 和 Javadoc；
- 单元/合约：全量测试，数量不得静默减少；
- 契约：金片、SSE、CSV/下载响应和 OpenAPI；
- 数据：JSONB 往返、租户/软删、事务与迁移 schema 对账；
- 外部协议：LLM、Embedding、Rerank、OAuth、MCP、IM、连接器；
- 集成：真实 PostgreSQL/Redis 关键链路按既有 CI 口径执行；
- 启动：dev jar、健康检查、认证端点、Scalar/OpenAPI 开关；
- 架构：模块矩阵、包边界、文档事实和版本一致性守卫。

### 9.3 最终完成条件

- 使用 JDK 21 + Boot 3.3.13 + Jackson 2.17；
- 无不再需要的 `tools.jackson.*` 引用和 Jackson 3 依赖；
- 全量 `mvn -B verify` 与 `run-guards.sh` 通过；
- 金片无未审批变化；
- 测试数量、跳过项和失败项有明确对账；
- 可执行 jar 冒烟通过；
- 文档、版本守卫、依赖清单和实施记录同步；
- 不包含生产发布结论，发布另行评审。

## 10. 回滚与停止条件

### 10.1 回滚原则

- 迁移仅进入独立集成分支，未全绿不进入主分支；
- 无计划内数据库 schema 变更，因此代码回滚不应依赖数据回滚；
- 若存在审批后的契约变化，回滚必须同时评估前端、消费者和已写入数据；
- 最终回滚单位为完整迁移合并，而不是在主分支逐文件反向修补。

### 10.2 必须停止并回到设计评审的情况

- springdoc 2.8.17 或 MyBatis-Plus 目标组合无法形成稳定启动；
- 为保持线格式需要引入长期双栈或复杂自定义转换层；
- 出现大范围金片变化，且无法由明确的框架差异解释；
- 关键第三方依赖不支持 Boot 3.3.13/Jackson 2.17；
- 为通过构建需要删除测试、放宽架构守卫或关闭契约校验；
- 迁移开始影响虚拟线程、事务或租户上下文的既有语义。

## 11. 实施计划输入

后续实施计划应把本设计拆成带以下信息的任务：

- 任务 ID、目标、前置依赖和负责文件；
- 明确禁止修改的共享文件；
- 可并行/不可并行标记；
- 具体修改步骤；
- 定向测试与集成验收命令；
- 契约审批记录要求；
- 完成定义和回滚方式。

实施计划不得扩大本设计范围，也不得把 JDK 17、Lombok、MapStruct 或业务重构重新引入。
