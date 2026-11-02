# 上手：30 / 60 / 120 分钟

目标不是"读完所有文档"，而是依次拿到三个确定性结果：

1. **30 分钟**：本机跑通一轮"注册 → 建库 → 上传 → 提问 → 看到流式回答"。
2. **60 分钟**：能说清一次问答经过哪几个模块，第一个迷路点在哪。
3. **120 分钟**：能独立做一个小改动，并跑对该跑的测试与守卫。

前置：JDK 21、Docker、Node 22+。不需要预先安装 PostgreSQL / Redis。

> 构建用 Maven：本机需自备 JDK 21 并设置 `JAVA_HOME`（Maven 不自动下载 JDK）；版本属性、Boot BOM 与公共插件配置集中在根 `pom.xml`，模块 pom 只声明依赖。

---

## 0–30 分钟：跑通

### 1. 配置与起服

```bash
cp .env.example .env          # dev 默认值：8083 / postgres 检索 / 固定 JWT 密钥
cp deploy/builtin_models.yaml.example config/builtin_models.yaml   # 模型声明，见下
bash scripts/doctor.sh        # 体检：缺什么会直接给修复命令
bash scripts/dev-up.sh        # 基础设施 + 后端；或按 README 四条命令手动起
```

**模型是问答的前提，系统不自带 LLM。** 上面复制的样例默认声明两个模型（对话 + 向量），走 OpenAI 兼容接口，
需要你在 `.env` 里补两项：

```bash
LLM_BASE_URL=https://api.openai.com/v1     # 换成你的供应商地址
LLM_API_KEY=sk-...                         # 也支持把样例里的 ${LLM_API_KEY} 换成真实值
```

用本地 Ollama 的话，改样例末尾那段（`source: local`、`baseUrl: http://localhost:11434`，不需要 key），
把默认的两条注释掉。**不配模型时页面能打开、知识库能上传，但第一次提问必然失败**——这是新人最常见的卡点。

起服日志里应看到三行关键信息：

```
[capabilities] retrieval=postgres engines=1 | jwt=env | storage=/Users/<you>/ragagent-data/files | redis=localhost:16379 | stream=memory
[builtin-models] applied: 2 upserted, 0 pruned from ./config/builtin_models.yaml
Tomcat started on port 8083 (http)
```

第二行说明模型声明已生效；**看不到这行**通常意味着文件没复制、路径不对（工作目录不是仓库根）或 YAML 解析失败，
此时提问会报"没有可用模型"。`retrieval=OFF` 或 `jwt=RANDOM` 则都是**降级信号**，先按日志里的 WARN 修 `.env`，不要继续。

### 2. 确认后端与前端

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8083/health   # 200
cd frontend && npm ci && npm run dev                                    # http://localhost:5173
```

前端所有 `/api` 请求由 Vite 代理到 **8083**。如果你的后端在别的端口，设 `VITE_DEV_PROXY_TARGET`，否则请求会静默落空。

### 3. 注册并跑一轮问答

```bash
curl -s -X POST http://localhost:8083/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"demo","email":"demo@example.com","password":"Passw0rd!"}'
```

浏览器打开 `http://localhost:5173`，用刚注册的账号登录：

1. 新建知识库；
2. 上传一个小 PDF 或 Markdown，等状态从 processing 变为 completed；
3. 在会话里选择该知识库并提问；
4. 应看到逐字输出的流式回答，以及引用片段。

如果第 4 步"有回答但没有引用"，先看起服日志的 `RETRIEVE_DRIVER` 与 `engines`，再看 `doctor.sh`。

### 4. 拿到一个可复用的 token

```bash
TOKEN=$(bash scripts/token.sh 8083)
curl -s -H "Authorization: Bearer ${TOKEN}" http://localhost:8083/api/v1/system/info
```

`system/info` 会回 `vectorStoreEngine` / `keywordIndexEngine`，可作为"检索是否真的开着"的第二个证据。

---

## 30–60 分钟：看懂骨架

按顺序读三篇，不要跳：

1. [`architecture/overview.md`](architecture/overview.md) —— 7 个模块与依赖方向；
2. [`architecture/request-path.md`](architecture/request-path.md) —— 一次问答的完整链路；
3. [`glossary.md`](glossary.md) —— 术语与历史代号。

然后做三个"定位练习"（比通读代码有效）：

| 练习 | 目标文件 |
|---|---|
| 找到 `/api/v1/knowledge-chat/{sessionId}` 的入口 | `domains/.../session/controller/KnowledgeQaController.java` |
| 找到 14 个管线阶段 | `domains/.../session/rag/PipelineEventType.java` |
| 找到 `keyword-threshold` 这类检索参数的落点 | `boot/src/main/resources/application.yml` 的 `conversation:` 段，再反查属性类 |

能在 IDE 里从 URL 一路跳到 `EventManager.trigger(...)`，就算过关。

---

## 60–120 分钟：做一个小改动

建议的第一个改动：**给某个插件加一条日志**，或**改一个检索阈值的默认值**。不要从 HTTP 契约、金片、SSE 帧顺序开始。

改完跑：

```bash
# 定向测试（迭代期；单模块构建前先 `mvn -B -DskipTests install`）
mvn -B -pl domains test -Dtest='com.ragagent.session.*'

# 批末全量闸门（必跑：格式 + javadoc + 48 守卫 + 全量测试）
mvn -B verify

# 守卫（与 CI 同一份入口）
bash scripts/run-guards.sh

# 前端
cd frontend && npm run type-check && npm test
```

提交前确认：

- 测试条数没有减少（`python3 scripts/test-summary.py`）；
- 没有改 `domains/src/testFixtures/resources/contracts/` 下的金片；
- 新增/修改的注释不写批次号（历史代号只属于 `docs/internal/`）。

---

## 常见失败对照

| 症状 | 大概率原因 | 处理 |
|---|---|---|
| 前端所有 `/api` 请求失败/空响应 | 后端不在 8083，或没起 | `bash scripts/doctor.sh`；看 `VITE_DEV_PROXY_TARGET` |
| 问答有文字但没有知识库引用 | `RETRIEVE_DRIVER` 为空 / 引擎未注册 | `.env` 设 `RETRIEVE_DRIVER=postgres`，重启后端 |
| 重启后所有人掉登录 | `JWT_SECRET` 未设，进程随机生成 | 在 `.env` 设固定值并重启 |
| 上传时报 500 / 原始文件丢失 | 存储目录不可写，或指向 `/tmp` | 设 `LOCAL_STORAGE_BASE_DIR` 到持久目录 |
| Redis `NOAUTH` / `invalid password` | `.env` 的 `REDIS_PASSWORD` 与服务端不一致 | 本仓 dev Redis 无密码：注释掉该行；有密码则两边一致 |
| 端口 8083 被占用 | 旧后端还在跑 | `bash scripts/java-server-down.sh --stop` |
| 改了代码但行为没变 / 起服后全端点 500 | 服务运行期间跑过 `mvn package`：jar 被替换，但进程仍跑旧代码（`spring-boot:run` 则是 `target/classes` 被就地重写） | 停服重启（与业务代码无关） |
| `database "xxx" does not exist` | `DB_NAME` 与容器初始化库名不一致；复用旧卷但改了库名 | 改回旧库名，或新建库/迁移数据 |
| Flyway `Validate failed` / checksum mismatch | 迁移脚本被改过，或手工改过表 | 不要手改 `flyway_schema_history`；恢复原脚本或按 Flyway `repair` 流程处理 |
| `relation ... does not exist` / 扩展缺失 | 未启用 `pgvector` 等扩展 | 在目标库执行 `CREATE EXTENSION IF NOT EXISTS vector;`（按需） |
| `No retrievable indexing pipelines` | 同"没有引用" | 同上 |
| `docker compose up` 端口冲突 | 本机已有另一套 PG/Redis 占用 15432/16379 | 复用现有服务，或先停掉旧容器 |

---

## 命令速查

| 命令 | 用途 |
|---|---|
| `bash scripts/doctor.sh` | 起服前体检（工具链 / .env / 端口 / Redis 认证 / 存储） |
| `bash scripts/dev-up.sh` | 基础设施 + 后端；`--with-frontend` 连前端一起起 |
| `bash scripts/java-server-up.sh` / `-down.sh` | 起/停后端（日志 `/tmp/ragagent-java-server.log`） |
| `bash scripts/token.sh 8083` | 取测试账号 JWT |
| `mvn -B -pl domains test -Dtest='com.ragagent.session.*'` | 定向测试 |
| `mvn -B verify` | 全量闸门（格式 + javadoc + 守卫 + 全量测试） |
| `bash scripts/run-guards.sh` | 48 条架构/契约守卫 |
| `python3 scripts/test-summary.py` | 测试条数、失败与跳过清单 |
| `python3 scripts/gen-arch-index.py` | 重新生成 `docs/architecture/packages.md` |
| `SPRINGDOC_ENABLED=true bash scripts/java-server-up.sh` | 本地打开 Scalar API 文档（`:8083/scalar`） |

---

## 三条不要

1. **不要为了跑通改金片**：`contracts/` 下是逐字节钉死的 HTTP 响应样本；改响应形状要显式重录并说明理由。
2. **不要把整轮问答包进一个大事务**：SSE 是长连接，事务边界有既定设计，见 `architecture/mechanisms.md`。
3. **不要用 `System.getenv` 直接读环境变量**：配置一律走 `@ConfigurationProperties` / `Environment`，ArchUnit 会拦。
