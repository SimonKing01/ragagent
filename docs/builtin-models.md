# 内置模型（builtin_models.yaml）

> 适用：首次部署、私有化交付、希望启动后立刻有可用模型的场景。
> 目标：不用在设置页手工建模型，启动时把声明式配置对账进 `models` 表。

## 为什么需要它

系统本身不捆绑 LLM。默认 `config/builtin_models.yaml` **不存在就完全 no-op**，不会往 `models` 表写任何行。新环境如果既没有这个文件、也没有手工建模型，第一次提问会因为没有对话/向量模型失败。

用这个文件声明“系统租户的默认模型”，启动时按 `id` 幂等写入。

## 快速开始

```bash
cp deploy/builtin_models.yaml.example config/builtin_models.yaml
# 在 .env 里补 LLM_BASE_URL / LLM_API_KEY
# 或改用样例里的本地 Ollama 写法（不需要 key）
bash scripts/java-server-down.sh
bash scripts/java-server-up.sh
```

启动后到 **设置 → 模型管理** 确认 `builtin-chat`、`builtin-embedding` 已出现。

## 字段

| 字段 | 必填 | 说明 |
|---|---|---|
| `id` | 是 | ≤64 字符；按 id 幂等 upsert，重复启动不会重复插入 |
| `tenant_id` | 否 | 缺省 `10000`（系统租户） |
| `name` | 是 | 发给供应商的模型名，如 `gpt-4o-mini`、`qwen2.5:7b` |
| `type` | 是 | `KnowledgeQA`（对话）/ `Embedding` / `Rerank` / `VLLM` / `ASR` |
| `source` | 否 | `remote`（默认，HTTP）或 `local`（本地 Ollama 等） |
| `description` | 否 | 说明文案 |
| `is_default` | 否 | 同一 `(tenant_id, type)` 桶内只允许一个默认 |
| `status` | 否 | 默认 `active` |
| `parameters.baseUrl` | 是 | 供应商地址；OpenAI 兼容写 `.../v1` |
| `parameters.apiKey` | 是 | 凭据；建议用 `${LLM_API_KEY}` 插值 |
| `parameters.interfaceType` | 否 | `openai` / `ollama`；留空按 `source` 推断 |
| `parameters.embeddingParameters.dimension` | 向量模型必填 | 必须与模型实际维度一致，否则相似度无意义 |

## 两种写法

**远程 OpenAI 兼容**

```yaml
builtin_models:
  - id: builtin-chat
    name: gpt-4o-mini
    type: KnowledgeQA
    source: remote
    is_default: true
    parameters:
      baseUrl: ${LLM_BASE_URL}
      apiKey: ${LLM_API_KEY}
      interfaceType: openai
```

**本地 Ollama（不需要 key）**

```yaml
builtin_models:
  - id: builtin-chat-ollama
    name: qwen2.5:7b
    type: KnowledgeQA
    source: local
    is_default: true
    parameters:
      baseUrl: http://localhost:11434
      apiKey: ""
```

## 生效规则

- **文件不存在 = 完全跳过**，不会写表、不会删行。
- **按 id 幂等**：重复启动不会重复插入。
- **删除即软删**：从文件里移除某条，下次启动会把它软删除。
- **默认唯一**：同一 `(tenant_id, type)` 只能有一个 `is_default: true`。
- **变量插值**：`${VAR}` 在启动时展开；**变量未设置时原样保留**（等于凭据无效，不会报“缺少变量”）。
- **路径可覆盖**：默认 `./config/builtin_models.yaml`（相对启动工作目录），用 `BUILTIN_MODELS_CONFIG` 指定其他路径。

## 与模型管理页的关系

- 文件托管的行由启动对账维护；页面上改了，下次启动仍会按文件覆盖。
- 手工在页面上新建的模型不受影响。
- 如果希望某条模型完全交给页面管理，把它从文件里移除（会触发一次软删），再在页面重建。

## 排障

| 现象 | 优先检查 |
|---|---|
| 设置页没有任何模型 | 文件是否复制、`type` 是否拼错、启动日志是否有 builtin models 相关行 |
| 模型存在但调用 401/403 | `.env` 的 `LLM_BASE_URL` / `LLM_API_KEY` 是否真的注入；`${VAR}` 未展开会原样入库 |
| 向量检索结果异常 | `embeddingParameters.dimension` 是否与模型一致 |
| 启动后重复出现模型 | `id` 是否重复；同一 `(tenant_id, type)` 是否多个 `is_default` |
| 改了文件没生效 | 文件路径是否被 `BUILTIN_MODELS_CONFIG` 覆盖；是否重启过后端 |
