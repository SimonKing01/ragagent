package com.ragagent.platform.access;

/**
 * 租户作用域：把 {@link TenantContext} 从"请求线程专用"扩展为<b>跨线程可显式携带</b>的会话。
 *
 * <p><b>为什么需要</b>：{@code TenantContext} 是 ThreadLocal——队列/虚拟线程上既没有 HTTP
 * Filter 链填充它，也不会从提交线程继承。而模型解析（{@code ModelService.getModelById} /
 * {@code modelruntime.application.ModelRuntimeAssembler}）按<b>租户可见性</b>过滤
 * （{@code tenant_id = 当前租户 OR is_builtin}），空租户（按 0 查）会让用户<b>自建模型</b>
 * 查不到 ⇒ 整条链以 "Model not found"（1003）失败。wiki 摄取早已用局部实现修过同款
 * （{@code WikiBatchSupport.enterTenantScope}）；本类把它提为共享助手，供 knowledge 处理链、
 * 富化队列、memory 抽取等<b>一切非请求线程</b>使用。</p>
 *
 * <p><b>用法</b>：{@code try (TenantScopes.TenantScope s = TenantScopes.enter(tenantId)) { … }}
 * ——{@code close()} 会<b>恢复</b>调用线程原本的会话（而不是粗暴清空），因此在同一线程上同步
 * 重放任务（测试、运维工具）也不会把请求上下文弄丢。</p>
 *
 * <p><b>跨线程</b>：提交线程先 {@code Long t = TenantContext.currentTenantId()}，任务体里再
 * {@link #enterCaptured(Long)}——ThreadLocal 不跨线程继承，必须显式传值。</p>
 */
public final class TenantScopes {

    private TenantScopes() {}

    /** 进入指定租户；{@code tenantId <= 0} 时不改动当前上下文（仅返回可恢复句柄）。 */
    public static TenantScope enter(long tenantId) {
        return enterCaptured(tenantId > 0 ? tenantId : null);
    }

    /**
     * 用<b>捕获到的</b>租户值进入（{@code null} ⇒ 不改动）：跨线程提交的标准姿势
     * ——提交线程 capture，任务体里 enter。
     */
    public static TenantScope enterCaptured(Long tenantId) {
        TenantScope prev = TenantScope.snapshot();
        if (tenantId != null && tenantId > 0) {
            TenantContext.set(tenantId, null, null, false, null, false);
        }
        return prev;
    }

    /** 会话快照 + 恢复：覆盖 {@link TenantContext} 的<b>全部</b>字段（含访客 id / 请求 id）。 */
    public static final class TenantScope implements AutoCloseable {
        private final Long tenant;
        private final TenantContext.Principal principal;
        private final String role;
        private final boolean sysAdmin;
        private final String user;
        private final boolean accessAll;
        private final String embedVisitorId;
        private final String requestId;

        private TenantScope(Long tenant, TenantContext.Principal principal, String role,
                            boolean sysAdmin, String user, boolean accessAll,
                            String embedVisitorId, String requestId) {
            this.tenant = tenant;
            this.principal = principal;
            this.role = role;
            this.sysAdmin = sysAdmin;
            this.user = user;
            this.accessAll = accessAll;
            this.embedVisitorId = embedVisitorId;
            this.requestId = requestId;
        }

        static TenantScope snapshot() {
            return new TenantScope(TenantContext.currentTenantId(),
                    TenantContext.currentPrincipal(), TenantContext.currentRole(),
                    TenantContext.isSystemAdmin(), TenantContext.currentUserId(),
                    TenantContext.canAccessAllTenants(), TenantContext.currentEmbedVisitorId(),
                    TenantContext.currentRequestId());
        }

        @Override
        public void close() {
            TenantContext.clear();
            if (tenant != null || principal != null || role != null || user != null
                    || sysAdmin || accessAll || embedVisitorId != null || requestId != null) {
                TenantContext.set(tenant, principal, role, sysAdmin, user, accessAll);
                TenantContext.setEmbedVisitorId(embedVisitorId);
                TenantContext.setRequestId(requestId);
            }
        }
    }
}
