# 25. HTTP 接口速查

## 25.1 基址与约定

基址 **`/api/v1`**（静态文件代理 `/files`、`/r/{token}` 与健康检查除外）。认证五种：`Authorization: Bearer <jwt>`（Web 控制台）、`X-API-Key`（服务端集成）、平台 Key + `X-Tenant-ID`（跨空间）、`X-API-Key` + `X-External-User-ID/Token`（外部用户）、`Authorization: Embed <publish-token>`（嵌入挂件）。统一响应外壳 `{code, message, data}`：成功 `code = 0` 且 `data` 恒存在；失败 `code != 0`、`message` 可直接展示。两个例外：`GET /health` 固定 `{"status":"ok"}`；`/api/v1/im/callback/*` 按第三方平台要求原样返回。分页统一 `{items, page, pageSize, total}`，`page` 从 1 起，`pageSize` 上限常见 100（会话列表 1000），越界返回 400 不做钳位。

**API 文档开关**：`springdoc.api-docs.enabled` 由环境变量 `SPRINGDOC_ENABLED` 控制（默认 `false`，生产不暴露）。开启后 OpenAPI JSON 在 `/v3/api-docs`，本地访问 `http://localhost:8083/scalar` 看 Scalar UI（`boot/src/main/java/com/ragagent/config/ScalarUi.java`，同一开关控制，关闭时 404）。

下表的角色列取自 `docs/architecture/route-policy-map.md`（由 `scripts/check-route-policy.py --map` 从控制器注解与 `RbacRoutePolicies` 生成，方法+路径已抽查与控制器源码一致）。**角色只表达下限**：所有权、API Key 范围、签名校验等守卫在控制器或服务内。`PUBLIC` 表示匿名可达（仍走认证链解析身份）。

## 25.2 接口速查表

认证与账号（`AuthController`）：

| 方法 | 路径 | 最低角色 | 说明 |
|---|---|---|---|
| POST | `/api/v1/auth/login` | PUBLIC | 登录，返回 JWT |
| POST | `/api/v1/auth/register` | PUBLIC | 注册（邀请链接注册为 `/api/v1/auth/register-by-invite`） |
| POST | `/api/v1/auth/auto-setup` | PUBLIC | 首次部署初始化管理员 |
| POST | `/api/v1/auth/refresh` | PUBLIC | 刷新 JWT |
| GET | `/api/v1/auth/me` | PUBLIC | 当前用户信息 |
| PUT | `/api/v1/auth/me/preferences` | PUBLIC | 更新个人偏好 |
| GET | `/api/v1/auth/config` | PUBLIC | 登录页配置（OIDC 开关等） |
| POST | `/api/v1/auth/switch-tenant` | PUBLIC | 切换激活空间 |

空间 / 成员 / API Key：

| 方法 | 路径 | 最低角色 | 说明 |
|---|---|---|---|
| GET · POST | `/api/v1/tenants` | PUBLIC | 可见空间列表 / 创建空间（`TenantCatalogController`） |
| PUT · DELETE | `/api/v1/tenants/{id}` | OWNER | 更新 / 删除空间 |
| GET | `/api/v1/tenants/{id}/members` | VIEWER | 成员列表 |
| POST | `/api/v1/tenants/{id}/members` | OWNER | 添加成员 |
| PUT · DELETE | `/api/v1/tenants/{id}/members/{userId}` | OWNER | 改角色 / 移除成员（`TenantMemberController`） |
| POST | `/api/v1/tenants/{id}/invitations` | OWNER | 发空间邀请 |
| GET · POST | `/api/v1/tenants/{id}/api-keys/` | OWNER（或 system_admin） | API Key 列表 / 签发 |
| DELETE | `/api/v1/tenants/{id}/api-keys/{keyId}` | OWNER（或 system_admin） | 吊销 Key |

知识库 / 文档 / 分块 / FAQ / 标签：

| 方法 | 路径 | 最低角色 | 说明 |
|---|---|---|---|
| GET · POST | `/api/v1/knowledge-bases/` | VIEWER / CONTRIBUTOR | 列表（分页）/ 创建 |
| PUT · DELETE | `/api/v1/knowledge-bases/{id}` | CONTRIBUTOR | 更新 / 删除 |
| POST | `/api/v1/knowledge-bases/{id}/knowledge/file` | CONTRIBUTOR | 上传文档入库（网页接入为同组 `/knowledge/url`、手写为 `/knowledge/manual`） |
| GET | `/api/v1/knowledge-bases/{id}/knowledge` | VIEWER | 文档列表 |
| GET · PUT · DELETE | `/api/v1/knowledge/{id}` | VIEWER / CONTRIBUTOR | 文档详情 / 更新 / 删除 |
| POST | `/api/v1/knowledge/{id}/reparse` | VIEWER | 重新解析 |
| GET | `/api/v1/chunks/{knowledgeId}` | VIEWER | 分块列表 |
| PUT · DELETE | `/api/v1/chunks/{knowledgeId}/{id}` | VIEWER | 编辑 / 删除分块（`ChunkController`） |
| POST | `/api/v1/knowledge-bases/{id}/faq/entries` | VIEWER | 新建 FAQ 条目 |
| POST | `/api/v1/knowledge-bases/{id}/tags` | VIEWER | 建标签 |

问答（SSE）与会话 / 消息：

| 方法 | 路径 | 最低角色 | 说明 |
|---|---|---|---|
| POST | `/api/v1/knowledge-chat/{sessionId}` | VIEWER | 知识问答，SSE 流式（`KnowledgeQaController`） |
| POST | `/api/v1/agent-chat/{sessionId}` | VIEWER | Agent 问答（ReAct + 工具），SSE |
| POST | `/api/v1/knowledge-search` | VIEWER | 纯检索，不生成回答 |
| GET | `/api/v1/sessions/continue-stream/{sessionId}` | VIEWER | 断线续流，SSE（`SessionStreamController`） |
| POST | `/api/v1/sessions/{sessionId}/steer` | VIEWER | 运行中转向 |
| POST | `/api/v1/sessions/{sessionId}/stop` | VIEWER | 停止生成（`SessionController`） |
| GET · POST | `/api/v1/sessions` | VIEWER | 会话列表 / 建会话 |
| GET · PUT · DELETE | `/api/v1/sessions/{id}` | VIEWER | 详情 / 改名等 / 删除 |
| POST | `/api/v1/sessions/{sessionId}/generate_title` | VIEWER | 生成会话标题 |
| GET | `/api/v1/messages/{sessionId}/load` | PUBLIC | 会话历史（分享链接亦走此入口） |
| POST | `/api/v1/messages/search` | VIEWER | 消息全文搜索 |

Agent / 技能 / 模型：

| 方法 | 路径 | 最低角色 | 说明 |
|---|---|---|---|
| GET · POST | `/api/v1/agents` | VIEWER / CONTRIBUTOR | Agent 列表 / 创建 |
| PUT · DELETE | `/api/v1/agents/{id}` | VIEWER | 更新 / 删除（所有权在服务内校验） |
| POST | `/api/v1/agents/{id}/copy` | CONTRIBUTOR | 复制 Agent |
| GET | `/api/v1/skills` | VIEWER | 已安装技能 |
| GET · POST | `/api/v1/skills/catalog` | ADMIN | 技能目录列表 / 上传 zip（nginx 对该路由放宽 body 上限） |
| GET · POST | `/api/v1/models/` | VIEWER / ADMIN | 模型列表 / 接入 |
| PUT · DELETE | `/api/v1/models/{id}` | ADMIN（或 system_admin） | 更新（含凭据）/ 删除 |
| POST | `/api/v1/models/{id}/debug` | ADMIN | 模型连通性调试 |

向量库 / 存储 / 网页搜索 / MCP：

| 方法 | 路径 | 最低角色 | 说明 |
|---|---|---|---|
| GET · POST | `/api/v1/vector-stores/` | VIEWER / ADMIN | 向量库列表 / 接入 |
| GET · POST | `/api/v1/storage-backends/` | VIEWER / ADMIN | 存储后端列表 / 接入 |
| PUT | `/api/v1/storage-backends/{id}/default` | ADMIN | 设默认存储 |
| GET · POST | `/api/v1/web-search-providers/` | VIEWER / ADMIN | 网页搜索提供方列表 / 接入 |
| GET · POST | `/api/v1/mcp-services/` | VIEWER / ADMIN | MCP 服务列表 / 接入 |
| GET | `/api/v1/mcp-services/{id}/tools` | VIEWER | 工具清单 |
| PUT | `/api/v1/mcp-services/{id}/tool-approvals/{toolName}` | ADMIN | 工具审批策略 |
| POST | `/api/v1/mcp-services/{id}/oauth/authorize-url` | VIEWER | 发起 MCP OAuth 授权 |
| GET | `/api/v1/mcp-oauth/callback` | PUBLIC | OAuth 回调 |
| POST | `/api/v1/agent/mcp-oauth-resolutions/{pendingId}` | VIEWER | 问答进行中的 OAuth 授权决议 |

IM 渠道 / Embed / 数据源 / 记忆 / Wiki / 收藏：

| 方法 | 路径 | 最低角色 | 说明 |
|---|---|---|---|
| GET | `/api/v1/im-channels` | VIEWER | IM 渠道列表 |
| PUT · DELETE | `/api/v1/im-channels/{id}` | ADMIN | 更新 / 删除渠道 |
| GET · POST | `/api/v1/im/callback/{channelId}` | PUBLIC | IM 平台回调（验签、原样应答） |
| POST | `/api/v1/embed/{channelId}/exchange` | PUBLIC | 发布票换短期会话票 |
| GET | `/api/v1/embed/{channelId}/config` | PUBLIC | 挂件公开配置 |
| POST | `/api/v1/embed/{channelId}/agent-chat/{sessionId}` | PUBLIC | 挂件问答，SSE |
| POST | `/api/v1/agents/{id}/embed-channels` | ADMIN | 为 Agent 建嵌入渠道 |
| GET · POST | `/api/v1/datasource` | VIEWER / ADMIN | 数据源列表 / 接入 |
| POST | `/api/v1/datasource/{id}/sync` | ADMIN | 触发同步 |
| GET · POST | `/api/v1/memory/items` | VIEWER | 记忆条目查询 / 新增 |
| POST | `/api/v1/memory/items/{id}/confirm` | VIEWER | 确认待确认记忆 |
| GET · PUT | `/api/v1/memory/settings` | VIEWER | 记忆设置 |
| GET | `/api/v1/knowledgebase/{kbId}/wiki/index` | VIEWER | Wiki 目录 |
| PUT | `/api/v1/knowledgebase/{kbId}/wiki/pages/{*slug}` | VIEWER | 编辑 Wiki 页面 |
| GET · POST | `/api/v1/user/favorites` | VIEWER | 收藏查询 / 添加 |
| DELETE | `/api/v1/user/favorites/{type}/{id}` | VIEWER | 取消收藏 |

系统 / 审计 / 初始化 / 文件代理 / 健康：

| 方法 | 路径 | 最低角色 | 说明 |
|---|---|---|---|
| GET | `/api/v1/system/info` | VIEWER | 版本、引擎等系统信息 |
| GET · PUT | `/api/v1/system/admin/settings/{key}` | SYSTEM_ADMIN | 平台全局设置读写 |
| GET | `/api/v1/tenants/{id}/audit-log` | ADMIN | 空间级审计日志 |
| GET | `/api/v1/system/admin/audit-log` | SYSTEM_ADMIN | 平台级审计日志 |
| PUT | `/api/v1/initialization/config/{kbId}` | CONTRIBUTOR | 保存知识库初始化配置（模型/嵌入等） |
| GET | `/files` | PUBLIC | 本地存储文件代理 |
| GET | `/r/{token}` | PUBLIC | 资源能力短链（IM 渲染 `resource://` 依赖） |
| GET | `/api/v1/files/presigned` | PUBLIC | 对象存储预签名 URL |
| GET | `/api/v1/files/presigned-preview` | ADMIN | 受保护资源预签名预览 |
| GET | `/health` | 无需认证 | 存活探针，固定 `{"status":"ok"}` |
| GET | `/actuator/health` · `/actuator/info` · `/actuator/metrics` | 走认证链 | 匿名 401、登录态 200；暴露面仅这三个端点 |

> **前端拆包约定**：`frontend/src/utils/request.ts` 对 2xx 取 `data`、非 2xx 取 `message`（见 [§23.3](23-前端控制台.md)），业务代码不读 `data.error.message`。错误码分段定义在 `contract.error.ErrorCode`，通用段 `1000+`。


---

[上一章：24. 测试体系与质量门禁](24-测试体系与质量门禁.md) ｜ [返回目录](详细技术文档.md) ｜ [下一章：26. 扩展点总表](26-扩展点总表.md)
