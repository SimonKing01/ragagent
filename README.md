<p align="center">
  <img alt="RagAgent" src="https://github.com/pmbilly/ragagent/blob/main/frontend/src/assets/img/RagAgent.webp?raw=true" width="40%">
</p>

<p align="center">
  <img alt="JDK" src="https://img.shields.io/badge/JDK-21-orange">
  <img alt="Spring Boot" src="https://img.shields.io/badge/Spring%20Boot-3.3-6DB33F">
  <img alt="Vue" src="https://img.shields.io/badge/Vue-3.5-42B883">
  <img alt="PostgreSQL" src="https://img.shields.io/badge/PostgreSQL-17%20%2B%20pgvector-4169E1">
  <img alt="License" src="https://img.shields.io/badge/License-MIT-blue">
</p>

<p align="center">
[<a href="https://github.com/pmbilly/ragagent">GitHub</a>] · [<a href="https://github.com/pmbilly/ragagent/tree/main/docs">文档</a>] · [<a href="https://github.com/pmbilly/ragagent/issues">Issues</a>]
</p>

# RagAgent

**企业级 RAG + Agent 平台**：一套可自托管的后端服务与 Web 控制台 —— 文档摄取、知识库问答、Wiki 协作、Agent 工具编排、多渠道接入与 MCP 生态。

---

## 目录

- [功能特性](#功能特性)
- [架构](#架构)
- [技术栈](#技术栈)
- [快速开始](#快速开始)
- [配置项](#配置项)
- [API 概览](#api-概览)
- [目录结构](#目录结构)
- [开发与测试](#开发与测试)
- [许可与致谢](#许可与致谢)

## 功能特性

<details open>
<summary><b>知识库与检索</b></summary>

- **文档摄取管线**：多格式解析（独立 gRPC 文档解析服务）→ 分块 → 实体/关系抽取 → 向量化，全程可重试、可重处理
- **混合检索**：向量召回 + 关键词召回，融合打分与可插拔**重排**；阈值、TopK、融合策略按知识库配置
- **九种向量/检索引擎适配**：pgvector（默认，ParadeDB）、Elasticsearch、OpenSearch、Milvus、Qdrant、Weaviate、腾讯 VectorDB、Doris、SQLite —— 知识库之间可混用
- **知识形态**：文件 / FAQ / 网页 / 知识图谱；Wiki 页面、文件夹树与链接图
- **连接外部数据源**：飞书、GitLab、Notion、语雀、RSS、IMA 等连接器按计划同步

</details>

<details open>
<summary><b>问答与 Agent</b></summary>

- **流式问答（SSE）**：检索 → 重排 → 生成 → 引用装配的完整链路，全程事件流式推送
- **Agent 执行循环**：ReAct 风格的思考/行动/观察循环，内置上下文压缩、Token 预算与用量统计
- **工具族（33 个内置工具，MCP 服务可再挂载外部工具）**：知识检索、Wiki 读写、文档与图片分析、SQL/数据表分析（DuckDB）、网页搜索与抓取、思维链、待办清单、技能读取……
- **技能（Skills）**：以 `SKILL.md` 为载体的三级注入（元数据 / 正文 / 资源），支持平台内置层与租户自建
- **会话能力**：多轮上下文、长期记忆召回、运行中转向（steer）与取消、消息附件与图片

</details>

<details open>
<summary><b>集成与接入</b></summary>

- **26 家 LLM 提供商**：OpenAI、Anthropic、Gemini、DeepSeek、通义千问（阿里云）、智谱、火山引擎、月之暗面、MiniMax、Ollama 本地部署……统一为同一套模型配置与调用面
- **Embedding / 重排客户端族**：按模型能力自动装配，支持批处理与并发控制
- **MCP（Model Context Protocol）**：接入外部 MCP 服务、按需发现工具、OAuth 授权，高危工具走人工审批闸门；另附独立交付的 MCP 参考服务
- **IM 渠道（9 种）**：飞书、企业微信、钉钉、微信、Telegram、Slack、Mattermost、QQ 机器人、云之家 —— 平台验签回调 + 长连接
- **网页搜索与抓取**：多家搜索提供商 + 受 SSRF 防护的抓取器

</details>

<details open>
<summary><b>平台与治理</b></summary>

- **多租户**：JWT 登录 + API Key 双通道，租户成员、邀请与角色权限（RBAC）
- **审计**：三条独立游标流，只追加写入
- **工具审批**：高危工具的人工闸门（申请 / 通过 / 拒绝）
- **文件与存储**：本地 / 对象存储后端、文件代理与预签名访问
- **可观测**：进程内事件总线 + SSE 流管理（内存 / Redis 双后端）、OTLP 追踪导出
- **统一契约**：统一响应外壳 `{code,message,data}` 与错误码约定（健康探针等基础设施端点除外）；前端类型与后端字段由 CI 守卫对齐

</details>

## 架构

七个 Maven 模块，**依赖单向、边界在编译期强制**：越界依赖无法编译，模块之间只认契约。

```
:boot              ──►  全部模块（组合根）
:domains           ──►  :platform  :contract  :model-runtime（模型上下文词汇）
:agent-engine      ──►  :platform  :contract  :model-runtime（模型上下文词汇）
:retrieval-engine  ──►  :platform  :contract
:model-runtime     ──►  :platform  :contract
:platform          ──►  :contract
:contract          ──►  （无依赖：纯 JDK）
```

| 模块 | 职责 | 依赖 |
|---|---|---|
| `:contract` | **契约面**：纯 JDK 的公开 API、SPI 与不可变载荷（agent / knowledge / session / model / retrieval / tool / pipeline / tenant / websearch / vectorstore …）。构建文件的生产依赖块为空（仅测试面有依赖）—— 契约纯度由编译期保证 | 无 |
| `:platform` | **技术底座**：Web 统一外壳与错误形态、SSRF 防护、事件总线、SSE 流管理、追踪装饰、提示词与配置装载、通用文本/知识工具 | contract |
| `:model-runtime` | **模型运行时**：26 家 LLM provider 的传输与出站请求体拼装、Embedding / 重排客户端族、模型上下文协议（工具调用与思考块解析）、抽取、Ollama 本地模型管理 | platform · contract |
| `:retrieval-engine` | **检索引擎**：九种向量/检索后端的适配与注册、混合检索融合、重排管线、索引写入与向量店连通性探测 | platform · contract |
| `:agent-engine` | **Agent 引擎**：ReAct 执行循环与阶段（think/act/observe/finalize）、工具注册表与工具框架、技能目录与装载、上下文压缩、预算与转向 | platform · contract · model-runtime |
| `:domains` | **业务域**：19 个业务域（知识库、会话、Wiki、Agent 管理、MCP（含工具审批）、IM、数据源、认证（含租户）、审计、记忆、收藏、系统设置……），含 51 个控制器与持久层 | platform · contract · model-runtime |
| `:boot` | **组合根**：应用入口、全局装配与跨模块 Bean 装配、扫描与打包。只出不进（无任何模块依赖它） | 全部 |

全部模块合计 **51 个业务控制器**（另有 1 个 `/health` 基础设施探针）。

**三条设计原则**

1. **契约面纯 JDK**：跨模块的公开类型集中在 `:contract`，不允许出现任何框架或实现依赖 —— 它同时是前端契约、API 形状与可测试边界的唯一来源。
2. **端口下沉，实现在下**：业务域通过契约端口消费能力模块（检索、模型、Agent），实现由装配层注入；跨模块协作不靠"互相 import"，靠端口与组合根。
3. **服务端分层**：HTTP 面只编排、领域服务承载用例、仓储封装软删与方言差异；一类型一文件，每个包有 `package-info.java` 说明职责与依赖判据。

**一次问答的数据流**

```
POST（SSE）                                     ← 前端 / 任意 HTTP 客户端
  → :domains 会话域（鉴权 · 租户作用域 · 消息落库 · SSE 事件）
    → :agent-engine 执行循环
      ├─ :retrieval-engine  混合检索（向量 + 关键词）→ 重排
      ├─ :model-runtime     LLM 调用（流式）· 工具调用解析 · 用量统计
      └─ 内置/外部工具     知识检索 · Wiki · 数据查询 · 网页抓取 · MCP
  → 事件经 :platform 事件总线与流管理器推送（统一外壳 · camelCase 键）
```

## 技术栈

| 层 | 选型 |
|---|---|
| 语言与框架 | JDK 21（虚拟线程用于管线并发与工具并行）· Spring Boot 3.3 |
| 数据访问 | MyBatis-Plus + 手写 SQL/仓储门面 · Flyway 迁移（`migrations/versioned/`） |
| 数据库 | PostgreSQL 17 + pgvector（默认以 ParadeDB 提供检索能力）· Redis 7（事件流、限流、跨实例协调） |
| 检索引擎 | pgvector · Elasticsearch · OpenSearch · Milvus · Qdrant · Weaviate · 腾讯 VectorDB · Doris · SQLite |
| 模型接入 | 26 家 LLM 提供商 · Embedding / 重排客户端族 · Ollama 本地模型 |
| 文档解析 | 独立 gRPC 服务（proto 随仓库交付，构建期生成本地客户端） |
| 其它 | 数据分析走 DuckDB JDBC · OTLP 追踪导出 · gRPC / protobuf |
| 前端 | Vue 3.5 + Vite 7 + TypeScript + Pinia + Vue Router（国际化支持） |
| 测试 | JUnit 5 + AssertJ + MockMvc + H2；手写 HTTP 桩覆盖 LLM / Ollama / OAuth / IM / 数据源 |

## 快速开始

**前置**：JDK 21、Docker、Node 22+。

```bash
# 1) 配置：dev 端口 8083、检索驱动 postgres、固定 JWT 密钥都在这里
cp .env.example .env

# 2) 配模型（**首次问答必需**）：复制样例，并在 .env 里补 LLM_BASE_URL / LLM_API_KEY
#    （用本地 Ollama 的话改样例末尾那段，不需要 key）
#    不配的话页面能打开，但第一次提问会失败——系统里没有任何可用模型
cp deploy/builtin_models.yaml.example config/builtin_models.yaml

# 3) 基础设施：PostgreSQL(15432) + Redis(16379) + 文档解析服务(50051)
docker compose up -d

# 4) 启动后端（:8083；脚本先构建再起 jar，环境变量口径与 dev-env.sh 一致）
bash scripts/java-server-up.sh

# 5) 启动前端（:5173，/api 代理到 8083）
cd frontend && npm ci && npm run dev
```

起服前可以先体检，缺什么会直接给修复命令：

```bash
bash scripts/doctor.sh
```

也可以一条命令起基础设施 + 后端（日志落在 `/tmp/ragagent-java-server.log`）：

```bash
bash scripts/dev-up.sh              # 加 --with-frontend 连同前端一起起
```

起服日志会打印一行 `[capabilities]`（检索 / JWT 密钥来源 / 存储目录 / Redis / 流管理器）；检索关闭或 JWT 随机生成会另起 WARN。

完整的文档索引（新人路径 / 维护者路径）见 [`docs/README.md`](docs/README.md)。

首次使用先注册账号（**首位注册者成为该租户的 owner**）：

```bash
curl -s -X POST http://localhost:8083/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"demo","email":"demo@example.com","password":"Passw0rd!"}'
```

**容器化部署**（镜像内默认 :8080，与 dev 的 :8083 无关）

```bash
docker build -t ragagent-server .
docker run --rm -p 8080:8080 --env-file .env ragagent-server
```

> 容器内的 `localhost` 指向容器自身：`.env` 里的 `DB_HOST=localhost` 只适用于 host-run。
> 容器部署需要把 `DB_HOST`/`REDIS_HOST` 指向容器可达的地址，并为
> `LOCAL_STORAGE_BASE_DIR`（镜像默认 `/app/data/files`）挂持久卷。
> 镜像内已包含 Flyway 迁移脚本，路径与镜像的 `WORKDIR` 绑定；调整工作目录或迁移位置时需同步 Dockerfile。

## 配置项

配置集中在 `boot/src/main/resources/application.yml`，全部为 `${ENV_VAR:default}` 形式，无 profile 分文件。常用变量：

| 变量 | 缺省 | 说明 |
|---|---|---|
| `SERVER_PORT` | 8080 | 服务端口（dev `.env` 用 8083） |
| `DB_HOST` / `DB_PORT` | localhost / 15432 | PostgreSQL（ParadeDB） |
| `DB_USER` / `DB_PASSWORD` / `DB_NAME` | — | 数据库凭据与库名 |
| `REDIS_HOST` / `REDIS_PORT` | localhost / 16379 | 事件流、限流与跨实例协调 |
| `REDIS_PASSWORD` | — | 仅当 Redis 开启 requirepass 时设置 |
| `DOCREADER_ADDR` | localhost:50051 | 文档解析 gRPC 地址 |
| `SYSTEM_AES_KEY` | — | 敏感配置（模型密钥等）的落库加密密钥，必填 |
| `JWT_SECRET` | 随机生成 | JWT 签名密钥；不设会重启掉登录，生产必须固定 |
| `RETRIEVE_DRIVER` | 空 | 检索引擎列表；**为空 = 检索关闭**，dev 填 `postgres` |
| `LOCAL_STORAGE_BASE_DIR` | `${user.home}/ragagent-data/files` | 本地存储根目录，必须持久化；勿放 `/tmp` |
| `SPRINGDOC_ENABLED` | false | 本地 Scalar API 文档 / OpenAPI 开关 |

完整清单见 [`.env.example`](.env.example)；全部可调开关（模型并发、流管理器后端、各域 Redis 开关等）见 `application.yml`。

## API 概览

- **风格**：REST + JSON（键一律 camelCase），流式接口走 SSE
- **认证**：`Authorization: Bearer <JWT>`；服务间/脚本可用 API Key
- **响应外壳**：统一 `{ "code": …, "message": …, "data": … }`，错误码与可空字段显式声明
- **API 文档**：默认关闭；本地 `SPRINGDOC_ENABLED=true bash scripts/java-server-up.sh` 后访问 `http://localhost:8083/scalar`（Scalar UI，生产保持 false）

| 资源 | 前缀 |
|---|---|
| 认证与账号 | `/api/v1/auth` |
| 知识库与文件 | `/api/v1/knowledge-bases` |
| Wiki | `/api/v1/knowledgebase/{kbId}/wiki` |
| 会话与消息 | `/api/v1/sessions` |
| Agent 与技能 | `/api/v1/agent` |
| 模型管理 | `/api/v1/models` |
| MCP 服务 | `/api/v1/mcp-services` |
| 向量库配置 | `/api/v1/vector-stores` |
| 网页搜索提供商 | `/api/v1/web-search-providers` |
| 存储后端 | `/api/v1/storage-backends` |
| 系统设置与运维 | `/api/v1/system` |
| 运维探针与指标 | `/actuator/health`（匿名可读）· `/actuator/info` · `/actuator/metrics`（需认证） |
| 文件代理 / 预签名 | `/api/v1/files/presigned` · `/r/{token}` |

## 目录结构

```
.
├── contract/            # :contract  契约面（纯 JDK）
├── platform/            # :platform  技术底座
├── model-runtime/       # :model-runtime  模型运行时
├── retrieval-engine/    # :retrieval-engine  检索引擎
├── agent-engine/        # :agent-engine  Agent 引擎
├── domains/             # :domains   业务域（19 域）
├── boot/                # :boot      组合根与应用入口
├── frontend/            # Vue 3 + Vite 控制台
├── docreader/           # 文档解析 gRPC proto
├── otlp-proto/          # OTLP 追踪 proto
├── mcp-server/          # MCP 参考服务（独立交付物，不参与 Java 构建）
├── migrations/          # Flyway 迁移脚本
├── scripts/             # 工程脚本（起停、契约校验、架构守卫）
├── deploy/              # 生产部署物料（编排、镜像定义、构建脚本、内置模型样例）
├── pom.xml              # 根 pom：版本唯一来源 + Boot BOM + 公共插件配置
├── docker-compose.yml   # 本地基础设施
└── Dockerfile           # 服务镜像
```

## 开发与测试

```bash
# 后端全量闸门：格式检查 + 架构守卫 + 单元/合约测试 + javadoc
mvn -B verify

# 定向测试（迭代用；单模块构建前需先 `mvn -B -DskipTests install`，否则跨模块夹具解析不到）
mvn -B -pl domains test -Dtest='com.ragagent.session.*'

# 仅跑架构守卫（包循环、契约纯度、模块依赖矩阵、命名口径……只许减不许增的棘轮）
bash scripts/run-guards.sh

# 前端：类型检查 + 单测
cd frontend && npm run type-check && npm test
```

**质量保障**

- **契约金片**：1,339 份逐字节钉死的 JSON 响应样本，另有 5 份下载/导出类样本（`.body` + `.headers` 成对）—— 改动响应形状必须显式重录，杜绝"接口悄悄变了"
- **架构规则**：模块依赖矩阵、契约层纯度、禁止裸读环境变量、禁止双装配与分层倒挂等，均由 ArchUnit + 脚本守卫在 CI 强制
- **测试**：约 5,000 条用例；Redis / 外部驱动等环境相关用例可跳过，但跳过项需登记理由（覆盖的消失是累积性的）
- **集成测试**：Milvus / Weaviate 等驱动集成测试需真实服务，由环境变量门控、按需触发
- **CI**：四个 job —— 架构守卫 · 后端（真实 PostgreSQL + Redis + 全量测试）· 镜像构建与迁移自检 · 前端

**参与贡献**

1. 从 `main` 切出特性分支，保持提交粒度可回滚；
2. 提交前本地跑通 `mvn -B verify`（与 CI 同口径）与前端 `npm run type-check && npm test`；
3. 涉及 HTTP 契约的改动，请同步前端类型并重录契约金片；
4. 新增依赖请登记到根 `pom.xml` 的 `<properties>` + `<dependencyManagement>`（版本唯一来源），不要写在模块 pom 里。

## 许可与致谢

本项目以 [MIT License](LICENSE) 发布。

致谢：[Tencent/WeKnora](https://github.com/Tencent/WeKnora) —— 本项目大量参考、复用了该项目。
