# 权限与角色（RBAC）

> 适用范围：租户/空间内的成员角色、路由下限、API Key 授权。
> 路由与角色的完整对照见 [`architecture/route-policy-map.md`](architecture/route-policy-map.md)（由脚本生成）。

## 角色

| 角色 | level | 典型能力 |
|---|---|---|
| `owner` | 40 | 空间所有者：成员、配置、删除等高危操作 |
| `admin` | 30 | 空间管理员：日常管理与配置 |
| `contributor` | 20 | 可创建/编辑业务内容，不能做管理面操作 |
| `viewer` | 10 | 只读 |
| 未识别 | 0 | 严格低于所有角色；等同无权限 |

权限判定只有一条：**当前角色 level ≥ 路由要求的最低 level**。

## 路由下限

每个受保护路由声明一个最低角色（`viewer` / `contributor` / `admin` / `owner`），例如：

- `GET /api/v1/agents` → `viewer`
- `POST /api/v1/agents` → `contributor`
- `POST /api/v1/agents/{id}/im-channels` → `admin`
- 登录、注册、OIDC 回调等 → `public`

完整清单以 [`architecture/route-policy-map.md`](architecture/route-policy-map.md) 为准；该文件由控制器注解与规则表生成，不要手改。

## 角色只是“下限”

路由通过后，控制器/服务里还有第二层判定，常见三类：

1. **所有权**：只能改自己创建的 Agent/知识库/会话；同空间 `owner`/`admin` 例外。
2. **API Key 范围**：Key 可能被限制到指定知识库或指定能力，见下节。
3. **签名/来源**：IM 回调、Embed 访客等按渠道凭据校验，不看租户角色。

因此“角色够但 403”是正常现象，先看第二层。

## API Key 授权

API Key 是独立于用户 JWT 的机器身份，认证头为 `X-API-Key`。

| 维度 | 取值 | 说明 |
|---|---|---|
| `scopeType` | `tenant`（默认）/ `platform` | `platform` 可跨空间；平台 Key 通常还要带 `X-Tenant-ID` |
| `fullAccess` | `true` / `false` | 为 false 时按知识库白名单与能力清单裁剪 |
| `knowledgeBaseIds` | 列表 | 限定可访问的知识库 |
| `capabilities` | 列表 | 限定允许的能力项 |

平台 Key 没带 `X-Tenant-ID` 时，只有少数控制面路由允许无空间放行；其余返回 `Workspace required`。

## 成员管理

- 入口：空间设置 → 成员（`/api/v1/tenants/...` 成员相关端点）。
- 支持邀请、角色变更、移除；邀请可配置为“需被邀请人确认”或“管理员邀请即加入”。
- 成员与角色变更会进入审计日志（空间审计页：`GET /api/v1/tenants/{id}/audit-log`）。

## 系统管理员

`is_system_admin` 是**平台级**标记，不是空间角色：

- 可跨空间查看/管理；
- 在 `orSystemAdmin` 规则上可绕过空间角色下限；
- 但不会自动获得空间内“所有权”语义，涉及所有权的操作仍按实现判定。

## 常见 403 排查

| 现象 | 检查 |
|---|---|
| 页面按钮可见但请求 403 | 该路由下限是否高于当前角色（对照 route-policy-map） |
| 同空间他人可操作、自己不行 | 是否资源所有者；是否需要 `admin`/`owner` |
| API Key 调用 403 | Key 的 `fullAccess`、知识库白名单、capabilities 是否覆盖该操作 |
| 平台 Key 报 `Workspace required` | 是否缺 `X-Tenant-ID` |
| 角色刚改仍 403 | 重新登录/刷新令牌；角色在请求上下文里是快照 |
