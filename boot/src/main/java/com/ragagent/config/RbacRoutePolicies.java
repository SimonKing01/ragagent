package com.ragagent.config;

import com.ragagent.contract.tenant.TenantRole;
import com.ragagent.platform.web.RbacInterceptor;

/**
 * RBAC 规则表（集中登记所有带角色下限的路由声明，以及显式公开/自服务路由）。
 *
 * <p>与 {@code APIKeyRoutePolicies} 在角色维度互为镜像：Java 侧路由注册分散在各控制器的
 * {@code @RequestMapping} 上，没有等价的"同一条语句"钩子，所以角色下限矩阵集中到本类——
 * <b>一处声明，启动时由组合根（{@code WebConfig}）一次性灌进 {@link RbacInterceptor}</b>。</p>
 *
 * <h2>登记顺序即语义（重要）</h2>
 * <p>拦截器用 AntPathMatcher 匹配，取<b>首个</b>命中：静态段规则（如
 * {@code /api/v1/models/providers}）必须先于 {@code /{id}} 通配规则
 * （如 {@code /api/v1/models/*}）登记；两段路径（Ant 的 {@code *} 不跨 {@code /}）
 * 同理先登记。<b>不要对本类条目重排序。</b></p>
 *
 * <h2>角色下限 ≠ 完整授权</h2>
 * <p>本表只设角色下限；所有权类判定（OwnedKBOrAdmin、per-user 会话自查等）在
 * 各控制器/服务内，不在本表。</p>
 */
final class RbacRoutePolicies {

    private RbacRoutePolicies() {
    }

    /**
     * 把当前已实现路由的角色下限全部登记进 {@code rbac}。
     *
     * <p>由组合根（{@code WebConfig}）在装配时调用一次；调用方随后把同一个
     * {@code rbac} 注册为拦截器。条目顺序必须与路由声明的静态段/通配段关系一致
     * （首个命中生效）。</p>
     */
    static void registerAll(RbacInterceptor rbac) {
        registerModels(rbac); registerKnowledge(rbac); registerChunksFaqTags(rbac);
        registerMcp(rbac); registerWikiAuditSystem(rbac); registerSessionsMessagesMemory(rbac);
        registerDataSources(rbac); registerInfrastructure(rbac); registerTenantsAndKeys(rbac);
        registerMisc(rbac); registerAgentsAndInit(rbac); registerEmbedAndIm(rbac);
        registerPublic(rbac);
    }
    private static void registerModels(RbacInterceptor rbac) {
        // /models 组
        rbac.addRule("GET", "/api/v1/models", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/models/providers", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/models/*/debug", TenantRole.ADMIN, false);
        rbac.addRule("POST", "/api/v1/models", TenantRole.ADMIN, false);
        rbac.addRule("PUT", "/api/v1/models/*/credentials", TenantRole.ADMIN, true);
        rbac.addRule("DELETE", "/api/v1/models/*/credentials/*", TenantRole.ADMIN, true);
        rbac.addRule("PUT", "/api/v1/models/*", TenantRole.ADMIN, true);
        rbac.addRule("DELETE", "/api/v1/models/*", TenantRole.ADMIN, false);
        rbac.addRule("GET", "/api/v1/models/*", TenantRole.VIEWER, false);
    }

    private static void registerKnowledge(RbacInterceptor rbac) {
// 知识库
        rbac.addRule("POST", "/api/v1/knowledge-bases", TenantRole.CONTRIBUTOR, false);
        rbac.addRule("GET", "/api/v1/knowledge-bases", TenantRole.VIEWER, false);
        rbac.addRule("PUT", "/api/v1/knowledge-bases/*/pin", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/knowledge-bases/*/move-targets", TenantRole.VIEWER, false);
        rbac.addRule("PUT", "/api/v1/knowledge-bases/*", TenantRole.CONTRIBUTOR, false);
        rbac.addRule("DELETE", "/api/v1/knowledge-bases/*", TenantRole.CONTRIBUTOR, false);
        rbac.addRule("GET", "/api/v1/knowledge-bases/*", TenantRole.VIEWER, false);
        // copy 是静态段，必须先于 /knowledge-bases/* 通配登记（AntPathMatcher 取首个命中）；
        // hybrid-search 的 POST/GET 都登记；duplicate=Contributor（create 档）。
        rbac.addRule("POST", "/api/v1/knowledge-bases/copy", TenantRole.CONTRIBUTOR, false);
        rbac.addRule("GET", "/api/v1/knowledge-bases/copy/progress/*", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/knowledge-bases/*/hybrid-search", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/knowledge-bases/*/hybrid-search", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/knowledge-bases/*/duplicate", TenantRole.CONTRIBUTOR, false);
        // 重建索引（评审补端点）：索引策略变更后的全量重处理，与
        // duplicate 同档（KB 级重写面，Contributor+）
        rbac.addRule("POST", "/api/v1/knowledge-bases/*/rebuild-index", TenantRole.CONTRIBUTOR, false);
        // KB 图片代理：Viewer 下限（KB 访问判定在 KnowledgeBaseFileProxyController 内）
        rbac.addRule("GET", "/api/v1/knowledge-bases/*/files", TenantRole.VIEWER, false);
        // 播放用签名 URL（视频/音频直连，Range + 浏览器缓存）
        rbac.addRule("GET", "/api/v1/knowledge-bases/*/files/signed-url", TenantRole.VIEWER, false);
        // 文档（OwnedKBOrAdmin 的所有权判定在 controller/service 层，拦截器只做角色下限）
        rbac.addRule("POST", "/api/v1/knowledge-bases/*/knowledge/file", TenantRole.CONTRIBUTOR, false);
        rbac.addRule("POST", "/api/v1/knowledge-bases/*/knowledge/url", TenantRole.CONTRIBUTOR, false);
        rbac.addRule("POST", "/api/v1/knowledge-bases/*/knowledge/manual", TenantRole.CONTRIBUTOR, false);
        rbac.addRule("GET", "/api/v1/knowledge-bases/*/knowledge/folders", TenantRole.VIEWER, false);
        // 清空 KB 内容：Admin+，比同组写端更严
        rbac.addRule("DELETE", "/api/v1/knowledge-bases/*/knowledge", TenantRole.ADMIN, false);
        // 重命名文件夹：无角色门（所有权 + 写权限判定在控制器内）→ 取最低的 VIEWER 下限
        rbac.addRule("PUT", "/api/v1/knowledge-bases/*/knowledge/folders", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/knowledge-bases/*/knowledge", TenantRole.VIEWER, false);
        // 文档操作面：
        // 静态段（batch/tags/folder/batch-*）先于 /knowledge/* 通配登记（AntPathMatcher 取首个命中）；
        // 带 :id 的写端没有角色门（ownership 在控制器内）→ 一律 VIEWER 下限；
        // download 是 Contributor（比 preview 严）、批处理写是 Contributor。
        rbac.addRule("GET", "/api/v1/knowledge/batch", TenantRole.VIEWER, false);
        rbac.addRule("PUT", "/api/v1/knowledge/tags", TenantRole.CONTRIBUTOR, false);
        rbac.addRule("POST", "/api/v1/knowledge/batch-delete", TenantRole.CONTRIBUTOR, false);
        rbac.addRule("POST", "/api/v1/knowledge/batch-reparse", TenantRole.CONTRIBUTOR, false);
        rbac.addRule("POST", "/api/v1/knowledge/folder", TenantRole.CONTRIBUTOR, false);
        // search/move/progress 与
        // 批处理同组——静态段先于 /knowledge/* 通配（move 是两段静态，search 单段）
        rbac.addRule("GET", "/api/v1/knowledge/search", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/knowledge/move/progress/*", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/knowledge/move", TenantRole.CONTRIBUTOR, false);
        // 两段路径（Ant 的 * 不跨 /，与 /knowledge/* 互不遮蔽，仍按静态段先登记）
        rbac.addRule("GET", "/api/v1/knowledge/*/stages", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/knowledge/*/spans", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/knowledge/*/download", TenantRole.CONTRIBUTOR, false);
        rbac.addRule("GET", "/api/v1/knowledge/*/preview", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/knowledge/*/regenerate-summary", TenantRole.VIEWER, false);
        rbac.addRule("PUT", "/api/v1/knowledge/manual/*", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/knowledge/*/reparse", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/knowledge/*/cancel-parse", TenantRole.VIEWER, false);
        rbac.addRule("PUT", "/api/v1/knowledge/image/*/*", TenantRole.VIEWER, false);
        rbac.addRule("PUT", "/api/v1/knowledge/*", TenantRole.CONTRIBUTOR, false);
        rbac.addRule("DELETE", "/api/v1/knowledge/*", TenantRole.CONTRIBUTOR, false);
        rbac.addRule("GET", "/api/v1/knowledge/*", TenantRole.VIEWER, false);
    }

    private static void registerChunksFaqTags(RbacInterceptor rbac) {
// chunks 读组：读 = Viewer+；写无角色门，ownership 守卫在 ChunkController 内判定
        rbac.addRule("GET", "/api/v1/chunks/by-id/*", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/chunks/*/*/revisions", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/chunks/*", TenantRole.VIEWER, false);
        // chunks 写组：写权限是"KB 创建者本人 OR Admin+"（无 Contributor
        // 下限——Viewer 创建的 KB 其本人可写）→ 拦截器只设 VIEWER 下限，
        // "creator OR Admin+" 判定在 ChunkAccessGuard（requireOwnedChunkKb*）内，
        // 与 FAQ/Wiki 写路由同款。静态段 by-id 先于通配登记。
        rbac.addRule("DELETE", "/api/v1/chunks/by-id/*/questions", TenantRole.VIEWER, false);
        rbac.addRule("PUT", "/api/v1/chunks/by-id/*/questions", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/chunks/by-id/*/questions/regenerate", TenantRole.VIEWER, false);
        rbac.addRule("DELETE", "/api/v1/chunks/*/*", TenantRole.VIEWER, false);
        rbac.addRule("PUT", "/api/v1/chunks/*/*", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/chunks/*/*/revert", TenantRole.VIEWER, false);
        rbac.addRule("DELETE", "/api/v1/chunks/*", TenantRole.VIEWER, false);
        // FAQ：读 = Viewer+
        // （KBAccessRead 在 FaqController）；写路由是 OwnedKBOrAdmin +
        // KBAccessWrite、**无角色门**（Viewer 创建的 KB 其本人可写）→ 一律 VIEWER 下限，
        // 所有权判定在 FaqController 内（requireKbWrite）。静态段（entries/fields/tags、
        // entry、search、import）先于 /entries/* 通配登记（AntPathMatcher 取首个命中）；
        // search 是 POST 但走 faqRead（retrieve 能力）。
        rbac.addRule("GET", "/api/v1/knowledge-bases/*/faq/entries/export", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/knowledge-bases/*/faq/entries", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/knowledge-bases/*/faq/entries", TenantRole.VIEWER, false);
        rbac.addRule("DELETE", "/api/v1/knowledge-bases/*/faq/entries", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/knowledge-bases/*/faq/entry", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/knowledge-bases/*/faq/search", TenantRole.VIEWER, false);
        rbac.addRule("PUT", "/api/v1/knowledge-bases/*/faq/entries/fields", TenantRole.VIEWER, false);
        rbac.addRule("PUT", "/api/v1/knowledge-bases/*/faq/entries/tags", TenantRole.VIEWER, false);
        rbac.addRule("PUT", "/api/v1/knowledge-bases/*/faq/import/last-result/display", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/knowledge-bases/*/faq/entries/*/similar-questions", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/knowledge-bases/*/faq/entries/*", TenantRole.VIEWER, false);
        rbac.addRule("PUT", "/api/v1/knowledge-bases/*/faq/entries/*", TenantRole.VIEWER, false);
        // FAQ 导入进度（KB 作用域外）：Viewer+
        rbac.addRule("GET", "/api/v1/faq/import/progress/*", TenantRole.VIEWER, false);
        // KB 标签 CRUD：读 = Viewer + KBAccessRead；写 = OwnedKBOrAdmin + KBAccessWrite、**无角色门**
        // （与 KB 主体的 creator OR Admin+ 矩阵一致）→ VIEWER 下限，
        // 所有权判定在 KnowledgeTagController 内。静态段先于 /tags/* 通配登记。
        rbac.addRule("GET", "/api/v1/knowledge-bases/*/tags", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/knowledge-bases/*/tags", TenantRole.VIEWER, false);
        rbac.addRule("PUT", "/api/v1/knowledge-bases/*/tags/*", TenantRole.VIEWER, false);
        rbac.addRule("DELETE", "/api/v1/knowledge-bases/*/tags/*", TenantRole.VIEWER, false);
    }

    private static void registerMcp(RbacInterceptor rbac) {
// MCP 服务
        // 更具体的路径必须排在 /mcp-services/* 之前（首个命中生效）
        rbac.addRule("POST", "/api/v1/mcp-services", TenantRole.ADMIN, false);
        rbac.addRule("GET", "/api/v1/mcp-services", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/mcp-services/*/test", TenantRole.ADMIN, false);
        rbac.addRule("GET", "/api/v1/mcp-services/*/tools", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/mcp-services/*/resources", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/mcp-services/*/metadata/refresh", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/mcp-services/*/metadata", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/mcp-services/*/usage-instructions/generate", TenantRole.ADMIN, false);
        rbac.addRule("PUT", "/api/v1/mcp-services/*/credentials", TenantRole.ADMIN, false);
        rbac.addRule("DELETE", "/api/v1/mcp-services/*/credentials/*", TenantRole.ADMIN, false);
        rbac.addRule("GET", "/api/v1/mcp-services/*/tool-approvals", TenantRole.VIEWER, false);
        rbac.addRule("PUT", "/api/v1/mcp-services/*/tool-approvals/*", TenantRole.ADMIN, false);
        // OAuth：发起/查询/撤销都是 Viewer+
        rbac.addRule("POST", "/api/v1/mcp-services/*/oauth/authorize-url", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/mcp-services/*/oauth/status", TenantRole.VIEWER, false);
        rbac.addRule("DELETE", "/api/v1/mcp-services/*/oauth/token", TenantRole.VIEWER, false);
        rbac.addRule("PUT", "/api/v1/mcp-services/*", TenantRole.ADMIN, false);
        rbac.addRule("DELETE", "/api/v1/mcp-services/*", TenantRole.ADMIN, false);
        rbac.addRule("GET", "/api/v1/mcp-services/*", TenantRole.VIEWER, false);
        // agent 会话内的审批 / OAuth 决议
        rbac.addRule("POST", "/api/v1/agent/tool-approvals/*", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/agent/mcp-oauth-resolutions/*", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/agent/mcp-oauth-resolutions/*/cancel", TenantRole.VIEWER, false);
        // 注意：/api/v1/mcp-oauth/callback 是**公开路由**（靠一次性 state 自证），不注册规则
    }

    private static void registerWikiAuditSystem(RbacInterceptor rbac) {
// Wiki
        // 注意路径前缀是 /knowledgebase（**无连字符**，与知识库的 /knowledge-bases 不同）
        // 写端点是"Admin 或创建者本人"，**没有 Contributor 下限**
        // （Viewer 创建的 KB 其本人可写），所以这里只设 VIEWER 下限，所有权判定在控制器内。
        rbac.addRule("GET", "/api/v1/knowledgebase/*/wiki/pages", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/knowledgebase/*/wiki/pages", TenantRole.VIEWER, false);
        rbac.addRule("PUT", "/api/v1/knowledgebase/*/wiki/move-page", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/knowledgebase/*/wiki/pages/**", TenantRole.VIEWER, false);
        rbac.addRule("PUT", "/api/v1/knowledgebase/*/wiki/pages/**", TenantRole.VIEWER, false);
        rbac.addRule("DELETE", "/api/v1/knowledgebase/*/wiki/pages/**", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/knowledgebase/*/wiki/revisions/**", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/knowledgebase/*/wiki/revert", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/knowledgebase/*/wiki/folders", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/knowledgebase/*/wiki/folders", TenantRole.VIEWER, false);
        rbac.addRule("PUT", "/api/v1/knowledgebase/*/wiki/folders/*", TenantRole.VIEWER, false);
        rbac.addRule("DELETE", "/api/v1/knowledgebase/*/wiki/folders/*", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/knowledgebase/*/wiki/index", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/knowledgebase/*/wiki/graph", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/knowledgebase/*/wiki/stats", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/knowledgebase/*/wiki/search", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/knowledgebase/*/wiki/rebuild-links", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/knowledgebase/*/wiki/lint", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/knowledgebase/*/wiki/auto-fix", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/knowledgebase/*/wiki/issues", TenantRole.VIEWER, false);
        rbac.addRule("PUT", "/api/v1/knowledgebase/*/wiki/issues/*/status", TenantRole.VIEWER, false);

        // 审计日志
        rbac.addRule("GET", "/api/v1/tenants/*/audit-log", TenantRole.ADMIN, false);
        rbac.addRule("GET", "/api/v1/knowledge-bases/*/activity", TenantRole.VIEWER, false);
        // 平台级审计：**仅系统管理员**（租户角色再高也不放行）
        rbac.addSystemAdminRule("GET", "/api/v1/system/admin/audit-log");

        // 系统管理端：
        // /system 组读端 Viewer+（"is the parser reachable"），主动探测远端的 check/
        // reconnect/storage-check Admin+（会拿租户凭据发起网络扇出）。/system/admin 组
        // 全部**仅系统管理员**（逐条 addSystemAdminRule；
        // 静态段先于通配段登记，AntPathMatcher 取首个命中）。
        // POST /system/sandbox-check 未实现：不登记规则（Spring 404，落地 handler 时随批恢复）。
        rbac.addRule("GET", "/api/v1/system/capabilities", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/system/info", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/system/parser-engines", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/system/parser-engines/check", TenantRole.ADMIN, false);
        rbac.addRule("POST", "/api/v1/system/docreader/reconnect", TenantRole.ADMIN, false);
        rbac.addRule("GET", "/api/v1/storage/engine-status", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/storage/engine-check", TenantRole.ADMIN, false);
        rbac.addSystemAdminRule("POST", "/api/v1/system/admin/promote");
        rbac.addSystemAdminRule("POST", "/api/v1/system/admin/revoke");
        rbac.addSystemAdminRule("GET", "/api/v1/system/admin/list");
        rbac.addSystemAdminRule("POST", "/api/v1/system/admin/users/reset-password");
        rbac.addSystemAdminRule("POST", "/api/v1/system/admin/users/create");
        rbac.addSystemAdminRule("GET", "/api/v1/system/admin/api-keys");
        rbac.addSystemAdminRule("POST", "/api/v1/system/admin/api-keys");
        rbac.addSystemAdminRule("DELETE", "/api/v1/system/admin/api-keys/*");
        rbac.addSystemAdminRule("GET", "/api/v1/system/admin/settings");
        rbac.addSystemAdminRule("GET", "/api/v1/system/admin/settings/*");
        rbac.addSystemAdminRule("PUT", "/api/v1/system/admin/settings/*");
        rbac.addSystemAdminRule("DELETE", "/api/v1/system/admin/settings/*");
        rbac.addSystemAdminRule("GET", "/api/v1/system/admin/runtime/queues");
        rbac.addSystemAdminRule("GET", "/api/v1/system/admin/runtime/queues/*/tasks");
        rbac.addSystemAdminRule("POST", "/api/v1/system/admin/runtime/queues/*/tasks/*/actions/*");
        rbac.addSystemAdminRule("DELETE", "/api/v1/system/admin/runtime/queues/*/archived");
        rbac.addSystemAdminRule("POST", "/api/v1/system/admin/tenants/apply-default-storage-quota");
    }

    private static void registerSessionsMessagesMemory(RbacInterceptor rbac) {
// 会话：sessions 是 per-user 资源，handler 内自查 ownership；组内全部端点
        // Viewer+（把已吊销账号挡在门外），无逐路由差异 → 全部 VIEWER 下限。
        rbac.addRule("GET", "/api/v1/sessions/continue-stream/*", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/sessions", TenantRole.VIEWER, false);
        rbac.addRule("DELETE", "/api/v1/sessions/batch", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/sessions", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/sessions/*", TenantRole.VIEWER, false);
        rbac.addRule("PUT", "/api/v1/sessions/*", TenantRole.VIEWER, false);
        rbac.addRule("DELETE", "/api/v1/sessions/*", TenantRole.VIEWER, false);
        rbac.addRule("DELETE", "/api/v1/sessions/*/messages", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/sessions/*/generate_title", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/sessions/*/attachments", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/sessions/*/attachments", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/sessions/*/attachments/*/preview", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/sessions/*/attachments/*", TenantRole.VIEWER, false);
        rbac.addRule("DELETE", "/api/v1/sessions/*/attachments/*", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/sessions/*/stop", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/sessions/*/steer", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/sessions/*/steer", TenantRole.VIEWER, false);
        rbac.addRule("DELETE", "/api/v1/sessions/*/steer/*", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/sessions/*/steer/*/inject", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/sessions/*/pin", TenantRole.VIEWER, false);
        rbac.addRule("DELETE", "/api/v1/sessions/*/pin", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/sessions/*/messages/*/suggestions", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/sessions/*/messages/*/suggestions", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/sessions/*/suggestion-events", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/sessions/*/artifacts", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/sessions/*/messages/*/artifacts", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/sessions/*/messages/*/artifacts/*/download", TenantRole.VIEWER, false);

        // 消息面：message history 是 tenant-wide 面，
        // Viewer+ 把非成员挡在外面。静态段（search/chat-history-stats）先于通配。
        rbac.addRule("POST", "/api/v1/messages/search", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/messages/chat-history-stats", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/messages/*", TenantRole.VIEWER, false);
        rbac.addRule("DELETE", "/api/v1/messages/*/*", TenantRole.VIEWER, false);

        // 消息图片代理：Viewer
        rbac.addRule("GET", "/api/v1/sessions/*/messages/*/files", TenantRole.VIEWER, false);

        // presigned-preview 诊断：Admin——对 API-Key 主体短路，Key 由 FileProxyController 显式拒绝；
        // presigned 与 /r/* 无角色门 → 不登记规则，拦截器放过无规则路径）
        rbac.addRule("GET", "/api/v1/files/presigned-preview", TenantRole.ADMIN, false);

        // chat 三入口：
        // knowledge-chat / agent-chat / knowledge-search 全部 Viewer+（逐会话/逐 KB
        // 授权在 handler 内做）。API-Key 侧 chat/retrieve 能力见 APIKeyRoutePolicies。
        rbac.addRule("POST", "/api/v1/knowledge-chat/*", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/agent-chat/*", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/knowledge-search", TenantRole.VIEWER, false);

        // 长期记忆
        // 守卫**只有 Viewer**：路径里没有任何 subject 参数，记忆空间一律从请求主体推导，
        // 所以不存在需要所有权判定的"别人的资源"。这里也**没有**管理端。
        // API-Key 侧要求 full-access（见 APIKeyRoutePolicies）——记忆空间属于个人，
        // scoped 集成 Key 不该继承一个。
        // 静态段先于通配段登记（首个命中生效）：AntPathMatcher 取**首个**匹配，
        // 而 `/items/**` 这种 Ant 模式连 `/items` 本身都能匹配上。
        rbac.addRule("GET", "/api/v1/memory/settings", TenantRole.VIEWER, false);
        rbac.addRule("PUT", "/api/v1/memory/settings", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/memory/items", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/memory/items", TenantRole.VIEWER, false);
        rbac.addRule("DELETE", "/api/v1/memory/items", TenantRole.VIEWER, false);
        // 路径参数 `{id}` 是**单段**，但 AntPathMatcher 的 `*` 不跨 `/`，
        // 够不到 `/items/:id/confirm` 这类两段路径 → 用 `/**` 覆盖（同 Wiki 段的写法）。
        rbac.addRule("PUT", "/api/v1/memory/items/**", TenantRole.VIEWER, false);
        rbac.addRule("DELETE", "/api/v1/memory/items/**", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/memory/items/**", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/memory/topics", TenantRole.VIEWER, false);
        rbac.addRule("DELETE", "/api/v1/memory/topics/**", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/memory/topics/**", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/memory/documents", TenantRole.VIEWER, false);
        rbac.addRule("DELETE", "/api/v1/memory/documents/**", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/memory/export", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/memory/consolidate", TenantRole.VIEWER, false);
    }

    private static void registerDataSources(RbacInterceptor rbac) {
// 数据源
        // 读端（types / 列表 / 详情 / 同步日志）Viewer+，其余（CRUD、校验、资源枚举、
        // 同步控制、凭据子资源）Admin+。数据源持有外部服务凭据、并能触发改动整个
        // 知识库内容的同步任务，所以写端门槛取 Admin。
        // 两段路径的规则先登记（Ant 的 `*` 不跨 `/`，够不到 `/x/sync` 这类两段路径）。
        rbac.addRule("GET", "/api/v1/datasource/types", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/datasource/validate-credentials", TenantRole.ADMIN, false);
        rbac.addRule("GET", "/api/v1/datasource/logs/*", TenantRole.VIEWER, false);
        rbac.addRule("PUT", "/api/v1/datasource/*/credentials", TenantRole.ADMIN, false);
        rbac.addRule("DELETE", "/api/v1/datasource/*/credentials/*", TenantRole.ADMIN, false);
        rbac.addRule("POST", "/api/v1/datasource/*/validate", TenantRole.ADMIN, false);
        rbac.addRule("GET", "/api/v1/datasource/*/resources", TenantRole.ADMIN, false);
        rbac.addRule("POST", "/api/v1/datasource/*/resource-ancestors", TenantRole.ADMIN, false);
        rbac.addRule("POST", "/api/v1/datasource/*/sync", TenantRole.ADMIN, false);
        rbac.addRule("POST", "/api/v1/datasource/*/pause", TenantRole.ADMIN, false);
        rbac.addRule("POST", "/api/v1/datasource/*/resume", TenantRole.ADMIN, false);
        rbac.addRule("GET", "/api/v1/datasource/*/logs", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/datasource", TenantRole.ADMIN, false);
        rbac.addRule("GET", "/api/v1/datasource", TenantRole.VIEWER, false);
        rbac.addRule("PUT", "/api/v1/datasource/*", TenantRole.ADMIN, false);
        rbac.addRule("DELETE", "/api/v1/datasource/*", TenantRole.ADMIN, false);
        rbac.addRule("GET", "/api/v1/datasource/*", TenantRole.VIEWER, false);
    }

    private static void registerInfrastructure(RbacInterceptor rbac) {
// 基础设施配置三组：
        // 租户级基础设施，读 Viewer+、写与连通测试 Admin+（无 ownership 守卫）。
        // 静态段（types/test/*/credentials/*/default）先于 /x/* 通配登记（首个命中生效）。
        rbac.addRule("GET", "/api/v1/web-search-providers/types", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/web-search-providers/test", TenantRole.ADMIN, false);
        rbac.addRule("POST", "/api/v1/web-search-providers", TenantRole.ADMIN, false);
        rbac.addRule("GET", "/api/v1/web-search-providers", TenantRole.VIEWER, false);
        rbac.addRule("PUT", "/api/v1/web-search-providers/*/credentials", TenantRole.ADMIN, false);
        rbac.addRule("DELETE", "/api/v1/web-search-providers/*/credentials/*", TenantRole.ADMIN, false);
        rbac.addRule("POST", "/api/v1/web-search-providers/*/test", TenantRole.ADMIN, false);
        rbac.addRule("PUT", "/api/v1/web-search-providers/*", TenantRole.ADMIN, false);
        rbac.addRule("DELETE", "/api/v1/web-search-providers/*", TenantRole.ADMIN, false);
        rbac.addRule("GET", "/api/v1/web-search-providers/*", TenantRole.VIEWER, false);
        // 运行时 provider 列表（兼容端点，Viewer+）；原始 group 注册 → API Key default-deny
        rbac.addRule("GET", "/api/v1/web-search/providers", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/vector-stores/types", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/vector-stores/test", TenantRole.ADMIN, false);
        rbac.addRule("POST", "/api/v1/vector-stores", TenantRole.ADMIN, false);
        rbac.addRule("GET", "/api/v1/vector-stores", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/vector-stores/*/test", TenantRole.ADMIN, false);
        rbac.addRule("PUT", "/api/v1/vector-stores/*", TenantRole.ADMIN, false);
        rbac.addRule("DELETE", "/api/v1/vector-stores/*", TenantRole.ADMIN, false);
        rbac.addRule("GET", "/api/v1/vector-stores/*", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/storage-backends/types", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/storage-backends/test", TenantRole.ADMIN, false);
        rbac.addRule("POST", "/api/v1/storage-backends", TenantRole.ADMIN, false);
        rbac.addRule("GET", "/api/v1/storage-backends", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/storage-backends/*/test", TenantRole.ADMIN, false);
        // /default 是两段路径，必须先于 /storage-backends/* 登记（Ant 的 * 不跨 /）
        rbac.addRule("PUT", "/api/v1/storage-backends/*/default", TenantRole.ADMIN, false);
        rbac.addRule("PUT", "/api/v1/storage-backends/*", TenantRole.ADMIN, false);
        rbac.addRule("DELETE", "/api/v1/storage-backends/*", TenantRole.ADMIN, false);
        rbac.addRule("GET", "/api/v1/storage-backends/*", TenantRole.VIEWER, false);
    }

    private static void registerTenantsAndKeys(RbacInterceptor rbac) {
// 跨空间租户目录：
        // 跨租户守卫（flag + CanAccessAllTenants，不受 EnableRBAC 调制）。
        // POST /tenants 不登记规则——只挂认证（自助创建对普通用户开放）。
        // GET /tenants 同样**无角色门**（只回活动空间自身）→ 不登记规则。
        // tenants CRUD 的 per-id 三条：
        // GET=Viewer+（读空间设置）；PUT/DELETE=Owner+（改/删空间）。
        // PathTenantMatch 对 /api/v1/tenants/{id}/** 自动生效（RbacInterceptor 内建），
        // "Invalid workspace ID" 的 handler 检查是死代码。
        rbac.addRule("GET", "/api/v1/tenants/*", TenantRole.VIEWER, false);
        rbac.addRule("PUT", "/api/v1/tenants/*", TenantRole.OWNER, false);
        rbac.addRule("DELETE", "/api/v1/tenants/*", TenantRole.OWNER, false);
        // 租户 KV 配置分发器：GET Viewer+、PUT Admin+；
        // 三条敏感 key 的 admin 门在控制器内（CanViewIntegrationSecrets）。
        rbac.addRule("GET", "/api/v1/tenants/kv/*", TenantRole.VIEWER, false);
        rbac.addRule("PUT", "/api/v1/tenants/kv/*", TenantRole.ADMIN, false);

        // 租户 API Key 管理：Owner+。
        // 刻意**不**登记进 API-Key 策略表——Key 不能给自己扩权。
        // 下限取 OWNER 而非 ADMIN：ADMIN 可自建 full-access Key 属权限放大（与成员管理同款下限）。
        rbac.addRule("GET", "/api/v1/tenants/*/api-keys", TenantRole.OWNER, true);
        rbac.addRule("POST", "/api/v1/tenants/*/api-keys", TenantRole.OWNER, true);
        rbac.addRule("PUT", "/api/v1/tenants/*/api-keys/*", TenantRole.OWNER, true);
        rbac.addRule("DELETE", "/api/v1/tenants/*/api-keys/*", TenantRole.OWNER, true);

        // 空间成员 / 邀请 / API-Principal：
        // 列表=Viewer+（任意成员可看名册）；一切变更=Owner+（**不是**
        // ADMIN 下限——成员/角色是租户内最高影响操作）；/leave=Viewer+（成员可自助退出）。
        // PathTenantMatch 对 /api/v1/tenants/{id}/** 自动生效（RbacInterceptor 内建）。
        // /api/v1/me/invitations** 无角色门（只挂认证）→ 不登记规则、
        // 拦截器 pattern 也未覆盖 /me/**。
        rbac.addRule("GET", "/api/v1/tenants/*/members", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/tenants/*/members", TenantRole.OWNER, false);
        rbac.addRule("PUT", "/api/v1/tenants/*/members/*", TenantRole.OWNER, false);
        rbac.addRule("DELETE", "/api/v1/tenants/*/members/*", TenantRole.OWNER, false);
        rbac.addRule("POST", "/api/v1/tenants/*/leave", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/tenants/*/invitations", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/tenants/*/invitations", TenantRole.OWNER, false);
        rbac.addRule("DELETE", "/api/v1/tenants/*/invitations/*", TenantRole.OWNER, false);
        rbac.addRule("POST", "/api/v1/tenants/*/invite-links", TenantRole.OWNER, false);
        // api-principal 三条：配置与测试签发都是 Owner 面
        rbac.addRule("GET", "/api/v1/tenants/*/api-principal-config", TenantRole.OWNER, false);
        rbac.addRule("PUT", "/api/v1/tenants/*/api-principal-config", TenantRole.OWNER, false);
        rbac.addRule("POST", "/api/v1/tenants/*/api-principal-test-token", TenantRole.OWNER, false);
    }

    private static void registerMisc(RbacInterceptor rbac) {
// 收藏与 chunker 调试面：
        // 收藏是"做收藏动作的人"的资源（不属资源创建者），Viewer+ 即可；
        // preview 是 KB 编辑器调试面板的只读端点。
        rbac.addRule("GET", "/api/v1/user/favorites", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/user/favorites", TenantRole.VIEWER, false);
        rbac.addRule("DELETE", "/api/v1/user/favorites/*/*", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/chunker/preview", TenantRole.VIEWER, false);
        // 沙箱配置面：
        // 静态段（workspace-policy/templates/query）先于 /:id 通配登记（AntPathMatcher
        // 取首个命中）；:id/sandboxes 在 :id 之前。List/Get 是 Viewer+，其余 Admin+。
        // GET /skills（指令型技能目录，agent/management/SkillController）：选择器数据源，
        // Viewer+。
        rbac.addRule("GET", "/api/v1/skills", TenantRole.VIEWER, false);
        // 技能管理面（agent/management/SkillController）：技能归属空间（另有平台内置
        // 只读层），管理面由「空间 admin」掌控。静态段 /catalog 与 /catalog/* 的 files
        // 子路径逐条登记（AntPathMatcher 取首个命中）。列出/编辑的对象是
        // 「平台内置层（只读）+ 当前空间」，写入只作用于当前空间。
        rbac.addRule("GET", "/api/v1/skills/catalog", TenantRole.ADMIN, false);
        rbac.addRule("POST", "/api/v1/skills/catalog", TenantRole.ADMIN, false);
        rbac.addRule("GET", "/api/v1/skills/catalog/*", TenantRole.ADMIN, false);
        rbac.addRule("PUT", "/api/v1/skills/catalog/*", TenantRole.ADMIN, false);
        rbac.addRule("DELETE", "/api/v1/skills/catalog/*", TenantRole.ADMIN, false);
        rbac.addRule("GET", "/api/v1/skills/catalog/*/files", TenantRole.ADMIN, false);
        rbac.addRule("GET", "/api/v1/skills/catalog/*/files/content", TenantRole.ADMIN, false);
    }

    private static void registerAgentsAndInit(RbacInterceptor rbac) {
// ── agents CRUD 家族 ──
        // 静态段先于 /agents/* 通配登记（AntPathMatcher 取首个命中）。
        // placeholders/type-presets/list/get：Viewer+；create/copy：Contributor+；
        // update/delete：OwnedAgentOrAdmin（无角色门——下限 VIEWER，所有权判定在控制器内）。
        rbac.addRule("GET", "/api/v1/agents/placeholders", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/agents/type-presets", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/agents", TenantRole.CONTRIBUTOR, false);
        rbac.addRule("GET", "/api/v1/agents", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/agents/*", TenantRole.VIEWER, false);
        rbac.addRule("PUT", "/api/v1/agents/*", TenantRole.VIEWER, false);
        rbac.addRule("DELETE", "/api/v1/agents/*", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/agents/*/copy", TenantRole.CONTRIBUTOR, false);
        rbac.addRule("GET", "/api/v1/agents/*/suggested-questions", TenantRole.VIEWER, false);

        // ── initialization 两条（模型写只走 /models 主资源，Admin+） ──
        // GET=KBAccessRead（Viewer+）；PUT=OwnedKBOrAdmin + KBAccessWrite
        //（与 PUT /knowledge-bases/:id 同矩阵：CONTRIBUTOR 下限，所有权判定在控制器内）。
        rbac.addRule("GET", "/api/v1/initialization/config/*", TenantRole.VIEWER, false);
        rbac.addRule("PUT", "/api/v1/initialization/config/*", TenantRole.CONTRIBUTOR, false);

        // ── initialization 系统级 8 条（ollama 6 条已归 model） ──
        // "不绑某个 KB 的系统级检测/下载"：JWT 侧只读探测 Viewer+、变更 Admin+。
        rbac.addRule("GET", "/api/v1/models/ollama/status", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/models/ollama/models", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/models/ollama/models/check", TenantRole.ADMIN, false);
        rbac.addRule("POST", "/api/v1/models/ollama/models/download", TenantRole.ADMIN, false);
        rbac.addRule("GET", "/api/v1/models/ollama/download/progress/*", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/models/ollama/download/tasks", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/initialization/remote/check", TenantRole.ADMIN, false);
        rbac.addRule("POST", "/api/v1/initialization/embedding/test", TenantRole.ADMIN, false);
        rbac.addRule("POST", "/api/v1/initialization/rerank/check", TenantRole.ADMIN, false);
        rbac.addRule("POST", "/api/v1/initialization/asr/check", TenantRole.ADMIN, false);
        rbac.addRule("POST", "/api/v1/initialization/multimodal/test", TenantRole.ADMIN, false);
        rbac.addRule("POST", "/api/v1/initialization/extract/text-relation", TenantRole.ADMIN, false);
        rbac.addRule("POST", "/api/v1/initialization/extract/fabri-tag", TenantRole.ADMIN, false);
        rbac.addRule("POST", "/api/v1/initialization/extract/fabri-text", TenantRole.ADMIN, false);
    }

    private static void registerEmbedAndIm(RbacInterceptor rbac) {
// ── embed 管理面 + im channels 清单面 ──
        // 管理端点：写（create/update/delete/rotate/toggle）Admin+，读（list/get/
        // preview/stats）Viewer+；wechat 扫码组全 Admin+（成功扫码会把个人
        // 微信绑到空间）。静态段先于通配段登记（AntPathMatcher 取首个命中）。
        // /api/v1/embed/**（公开面）登记为 addPublicRule（C9b）：不设角色下限，
        // 但不再落入“未命中规则默认放行”的盲区；鉴权仍由 EmbedAuthFilter 的 publish token 自证。
        rbac.addRule("POST", "/api/v1/agents/*/embed-channels", TenantRole.ADMIN, false);
        rbac.addRule("GET", "/api/v1/agents/*/embed-channels", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/embed-channels", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/embed-channels/*", TenantRole.VIEWER, false);
        rbac.addRule("PUT", "/api/v1/embed-channels/*", TenantRole.ADMIN, false);
        rbac.addRule("DELETE", "/api/v1/embed-channels/*", TenantRole.ADMIN, false);
        rbac.addRule("POST", "/api/v1/embed-channels/*/rotate-token", TenantRole.ADMIN, false);
        rbac.addRule("POST", "/api/v1/embed-channels/*/preview-session", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/embed-channels/*/stats", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/agents/*/im-channels", TenantRole.ADMIN, false);
        rbac.addRule("GET", "/api/v1/agents/*/im-channels", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/im-channels", TenantRole.VIEWER, false);
        rbac.addRule("PUT", "/api/v1/im-channels/*", TenantRole.ADMIN, false);
        rbac.addRule("DELETE", "/api/v1/im-channels/*", TenantRole.ADMIN, false);
        rbac.addRule("POST", "/api/v1/im-channels/*/toggle", TenantRole.ADMIN, false);
        rbac.addRule("POST", "/api/v1/wechat/qrcode", TenantRole.ADMIN, false);
        rbac.addRule("POST", "/api/v1/wechat/qrcode/status", TenantRole.ADMIN, false);
    }

    private static void registerPublic(RbacInterceptor rbac) {
// ── 公开/自服务路由（C9b）──
        // 无角色下限，但并非“无鉴权”：登录态由 AuthFilter、渠道 token 由 EmbedAuthFilter、
        // 文件由签名、邀请由 token、webhook 由签名、会话/消息由所有权守卫负责。
        // 登记它们是为了关闭“/api/v1/** 未命中规则默认放行”的静默越权面。
        rbac.addPublicRule("GET", "/api/v1/auth/config");
        rbac.addPublicRule("GET", "/api/v1/auth/me");
        rbac.addPublicRule("GET", "/api/v1/auth/oidc/callback");
        rbac.addPublicRule("GET", "/api/v1/auth/oidc/config");
        rbac.addPublicRule("GET", "/api/v1/auth/oidc/start");
        rbac.addPublicRule("GET", "/api/v1/auth/oidc/url");
        rbac.addPublicRule("GET", "/api/v1/auth/validate");
        rbac.addPublicRule("DELETE", "/api/v1/embed/*/sessions/*");
        rbac.addPublicRule("GET", "/api/v1/embed/*/chunks/*");
        rbac.addPublicRule("GET", "/api/v1/embed/*/config");
        rbac.addPublicRule("GET", "/api/v1/embed/*/files");
        rbac.addPublicRule("GET", "/api/v1/embed/*/messages/*/load");
        rbac.addPublicRule("GET", "/api/v1/embed/*/sessions");
        rbac.addPublicRule("GET", "/api/v1/embed/*/sessions/*/mcp-services/*/oauth/status");
        rbac.addPublicRule("GET", "/api/v1/embed/*/sessions/*/messages/*/suggestions");
        rbac.addPublicRule("GET", "/api/v1/embed/*/suggested-questions");
        rbac.addPublicRule("GET", "/api/v1/files/presigned");
        rbac.addPublicRule("GET", "/api/v1/im/callback/*");
        rbac.addPublicRule("GET", "/api/v1/mcp-oauth/callback");
        rbac.addPublicRule("GET", "/api/v1/me/invitations");
        rbac.addPublicRule("GET", "/api/v1/me/invitations/pending-count");
        rbac.addPublicRule("GET", "/api/v1/messages/*/load");
        rbac.addPublicRule("GET", "/api/v1/tenants");
        rbac.addPublicRule("GET", "/files");
        rbac.addPublicRule("GET", "/r/*");
        rbac.addPublicRule("HEAD", "/api/v1/files/presigned");
        rbac.addPublicRule("HEAD", "/files");
        rbac.addPublicRule("HEAD", "/r/*");
        rbac.addPublicRule("POST", "/api/v1/auth/auto-setup");
        rbac.addPublicRule("POST", "/api/v1/auth/change-password");
        rbac.addPublicRule("POST", "/api/v1/auth/invitations/lookup");
        rbac.addPublicRule("POST", "/api/v1/auth/login");
        rbac.addPublicRule("POST", "/api/v1/auth/logout");
        rbac.addPublicRule("POST", "/api/v1/auth/refresh");
        rbac.addPublicRule("POST", "/api/v1/auth/register");
        rbac.addPublicRule("POST", "/api/v1/auth/register-by-invite");
        rbac.addPublicRule("POST", "/api/v1/auth/switch-tenant");
        rbac.addPublicRule("POST", "/api/v1/embed/*/agent-chat/*");
        rbac.addPublicRule("POST", "/api/v1/embed/*/exchange");
        rbac.addPublicRule("POST", "/api/v1/embed/*/knowledge-chat/*");
        rbac.addPublicRule("POST", "/api/v1/embed/*/sessions");
        rbac.addPublicRule("POST", "/api/v1/embed/*/sessions/*/events");
        rbac.addPublicRule("POST", "/api/v1/embed/*/sessions/*/mcp-oauth-resolutions/*");
        rbac.addPublicRule("POST", "/api/v1/embed/*/sessions/*/mcp-oauth-resolutions/*/cancel");
        rbac.addPublicRule("POST", "/api/v1/embed/*/sessions/*/mcp-services/*/oauth/authorize-url");
        rbac.addPublicRule("POST", "/api/v1/embed/*/sessions/*/messages/*/suggestions");
        rbac.addPublicRule("POST", "/api/v1/embed/*/sessions/*/stop");
        rbac.addPublicRule("POST", "/api/v1/embed/*/sessions/*/suggestion-events");
        rbac.addPublicRule("POST", "/api/v1/embed/*/sessions/*/tool-approvals/*");
        rbac.addPublicRule("POST", "/api/v1/im/callback/*");
        rbac.addPublicRule("POST", "/api/v1/me/invitations/accept-by-token");
        rbac.addPublicRule("POST", "/api/v1/me/invitations/*/accept");
        rbac.addPublicRule("POST", "/api/v1/me/invitations/*/decline");
        rbac.addPublicRule("POST", "/api/v1/tenants");
        rbac.addPublicRule("PUT", "/api/v1/auth/me/preferences");
    }

}
