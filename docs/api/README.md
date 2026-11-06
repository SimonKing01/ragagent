# API 概览

> 基址：`/api/v1`。健康检查：`GET /health`。
> 统一响应与错误格式见本文 §响应外壳 / §错误码。
> 路由级权限见 [`../rbac.md`](../rbac.md) 与 [`../architecture/route-policy-map.md`](../architecture/route-policy-map.md)。

## 认证

| 方式 | 请求头 | 适用 |
|---|---|---|
| 用户登录 | `Authorization: Bearer <jwt>` | Web 控制台、用户脚本 |
| API Key | `X-API-Key: <key>` | 服务端集成、自动化 |
| 平台 API Key | `X-API-Key` + `X-Tenant-ID` | 跨空间调用；缺 `X-Tenant-ID` 时仅少数控制面路由放行 |
| 外部用户 | `X-API-Key` + `X-External-User-ID` / `X-External-User-Token` | 以外部用户身份发起会话 |
| Embed 发布票 | `Authorization: Embed <publish-token>` | 嵌入组件；安全模式走 `/api/v1/embed/{channel}/exchange` 换短期票 |

登录：

```bash
curl -s -X POST http://localhost:8083/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"user@example.com","password":"..."}'
```

后续请求带 `Authorization: Bearer <token>`。

## 响应外壳

```json
{ "code": 0, "message": "ok", "data": { } }
```

- 成功 `code = 0`，`data` 恒存在（无载荷为 `null`）。
- 失败 `code != 0`，`message` 可直接展示；HTTP 状态码同时表达语义。
- 分页统一 `{ items, page, pageSize, total }`。
- 例外：`GET /health` 固定 `{"status":"ok"}`；`/api/v1/im/callback/*` 按第三方平台要求原样返回。

## 主要资源

| 前缀 | 用途 |
|---|---|
| `/auth` | 登录、注册、OIDC、邀请、当前用户 |
| `/tenants` | 空间、成员、角色 |
| `/agents` | Agent 配置、预设、占位符、建议问题 |
| `/knowledge-bases` | 知识库 CRUD、成员权限、检索配置 |
| `/knowledge`、`/chunks`、`/faq` | 文档、分块、FAQ、重处理 |
| `/sessions`、`/messages` | 会话与消息历史 |
| `/agent-chat`、`/knowledge-chat` | 问答（SSE 流式） |
| `/models`、`/initialization` | 模型管理、初始化配置 |
| `/im-channels`、`/embed`、`/embed-channels` | IM 与嵌入渠道 |
| `/mcp-services`、`/mcp-oauth` | MCP 服务与 OAuth |
| `/skills`、`/memory` | 技能、记忆 |
| `/storage`、`/storage-backends` | 存储配置 |
| `/system` | 版本、引擎、能力、运行状态 |
| `/tenants/{id}/audit-log` · `/system/admin/audit-log` | 审计日志（空间级 / 平台级） |
| `/actuator/health` · `/actuator/info` · `/actuator/metrics` | 运维探针与指标（三个端点都走认证链：匿名 401，登录态 200；指标含 `ragagent.qa.turns` / `ragagent.qa.failures`） |

## 典型流程

1. **登录**拿 JWT。
2. **建知识库**：`POST /api/v1/knowledge-bases`。
3. **上传/接入文档**：按知识库类型走文件上传、网页或 FAQ 接口；随后触发解析/入库。
4. **建 Agent**：`POST /api/v1/agents`，绑定知识库与模型。
5. **问答**：`POST /api/v1/agent-chat/{sessionId}`，响应为 SSE 流。
6. **看历史**：`GET /api/v1/sessions`、`GET /api/v1/messages`。

## 约定与限制

- **分页**：`page` 从 1 开始，`pageSize` 有上限（常见 100，会话列表 1000）；越界/非法值返回 400「分页参数不合法」，不做钳位。
- **幂等**：创建类接口一般不幂等；重试前先查列表或用业务唯一键。
- **SSRF**：服务端出站请求（模型、抓取、IM 回调、存储）过白名单；本地/内网地址需配置 `SSRF_WHITELIST_EXTRA` 或系统设置。
- **限流**：Embed 公开面有滑窗限流；多实例下可切 Redis 共享预算。
- **上传**：文件大小、类型由部署配置约束；超限返回 4xx。
- **500**：不透出内部异常文案，响应固定 `Internal server error`，详细堆栈看服务端日志。
- **前端解析**：`frontend/src/utils/request.ts` 对 2xx 取 `data`、非 2xx 取 `message`；业务代码不读 `data.error.message`。

## 错误码

- 通用段 `1000+`；业务按域分段，定义在 `contract.error.ErrorCode`。
- 常见：`400` 参数/绑定错误、`401` 未认证/凭据失效、`403` 角色或 API Key 范围不足、`404` 资源不存在、`405` 方法不支持、`409` 冲突（重名/被引用）、`429` 限流。
- 前端解析约定见 §约定与限制（`utils/request.ts` 的拆包规则）。
