# 文档索引

文档分两轨：**新人向**（仓库根 `README.md` + 本目录）与**维护者向**（`internal/`，迁移/整改的过程记录）。新人不要从 `internal/` 开始读。

## 新人路径（按顺序）

| 顺序 | 文档 | 内容 |
|---|---|---|
| 1 | [`../README.md`](../README.md) | 项目是什么、四条命令跑起来 |
| 2 | [`architecture/getting-started.md`](architecture/getting-started.md) | 30 / 60 / 120 分钟上手与常见失败对照 |
| 3 | [`architecture/overview.md`](architecture/overview.md) | 7 个模块、依赖矩阵、代码在哪 |
| 4 | [`architecture/request-path.md`](architecture/request-path.md) | 一次问答经过哪些模块与类、日志关键字 |
| 5 | [`architecture/rag-pipeline.md`](architecture/rag-pipeline.md) | 14 个管线阶段与插件装配表 |
| 6 | [`architecture/ingest-flow.md`](architecture/ingest-flow.md) | 一份文档从入口到入库、富化与收口的一页纸 |
| 7 | [`architecture/mechanisms.md`](architecture/mechanisms.md) | 队列、事务、embedding、定时任务等反直觉机制 |
| 8 | [`architecture/glossary.md`](architecture/glossary.md) | 业务术语与历史代号（K4 / A18 / R3d / B2xx） |
| 9 | [`详细技术文档.md`](详细技术文档.md) | 详细技术文档（目录页，按章拆为 docs/ 下 30 份）：模型层 / 检索引擎 / 问答与入库管线 / Agent / 渠道 / 安全 / 配置总表 / 部署基线 / 扩展点总表 |

查业务配置：

- 内置模型：[`architecture/builtin-models.md`](architecture/builtin-models.md) —— 启动时声明式建模型，第一次问答必需
- 权限与角色：[`architecture/rbac.md`](architecture/rbac.md) —— 四级角色、路由下限、API Key 范围
- IM 渠道接入：[`architecture/im-integration.md`](architecture/im-integration.md) —— 企业微信/微信/飞书/钉钉/Slack
- 知识图谱：[`architecture/knowledge-graph.md`](architecture/knowledge-graph.md) —— Neo4j 启用与实体关系抽取
- API 概览：[`25-HTTP接口速查.md`](25-HTTP接口速查.md) —— 认证、响应外壳、错误码与主要接口（原 `api/README.md` 内容并入此章）

查表（三份由守卫强制与代码同步的链路/接口表）：

- 管线事件表：[`architecture/pipeline-map.md`](architecture/pipeline-map.md) —— 14 个管线阶段的事件与插件
- Port 对照表：[`architecture/port-map.md`](architecture/port-map.md) —— 域端口 ↔ 实现 ↔ 调用方
- 问答链路：[`architecture/qa-flow.md`](architecture/qa-flow.md) —— 一次问答的类级调用链

查表：[`architecture/packages.md`](architecture/packages.md) —— 357 份 `package-info` 的包索引（由脚本生成，勿手改）。

按业务链路查：[`architecture/business-capability-map.md`](architecture/business-capability-map.md) —— 六条链路（知识生产 / 问答 / Agent / 渠道 / 配置 / 初始化）→ 入口、编排、落点。

先看模块：[`architecture/module-package-map.md`](architecture/module-package-map.md) —— 7 个模块的包根、19 个业务域的包/控制器数量（由脚本生成，勿手改）。

先看业务分类：[`architecture/domain-index.md`](architecture/domain-index.md) —— 19 个域按 5 类能力组织，含入口、契约面与前端页面（由脚本生成，勿手改）。

查权限：[`architecture/route-policy-map.md`](architecture/route-policy-map.md) —— 384 条路由 → RBAC 角色下限/公开自服务登记（由脚本生成，勿手改）。

> 代码注释里的「HANDOFF §x」指 [`internal/HANDOFF.md`](internal/HANDOFF.md)。

## 维护者路径

- [`internal/HANDOFF.md`](internal/HANDOFF.md) —— 当前批次、判定标准、闸门与回填；§11 含已知缺口登记，§7 含契约金片布局与重录
- [`internal/plans/`](internal/plans/) —— 整改方案（v2 与历史版本）
- [`internal/plans/xiaoling-governance-mvp.md`](internal/plans/xiaoling-governance-mvp.md) —— RagAgent 融合小灵管理能力的 MVP 实施清单与分阶段验收标准（实施前方案）
- [`internal/handoff/`](internal/handoff/) —— 批次记录、专项计划与归档
- [`internal/architecture-review/`](internal/architecture-review/) —— 历轮架构评审报告（均带评估日期与基线快照声明，非现状文档）

## 约定

- 新人向文档只写**当前事实**；过程、批次号、临时读数一律进 `internal/`。
- `internal/` 里的路径与数字会过期；动手前以代码、守卫和 `git log` 为准。
