package com.ragagent.session.qa;

import com.ragagent.contract.error.BizException;
import com.ragagent.contract.session.SseSink;
import com.ragagent.contract.tenant.TenantConfigLookup;
import com.ragagent.contract.tenant.TenantConfigLookup.TenantStorageView;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.session.dto.QaRequests.CreateKnowledgeQARequest;
import com.ragagent.session.service.AgentResolver;
import com.ragagent.session.service.QaSupport.QaMode;
import com.ragagent.session.service.QaSupport.QaRequestContext;
import com.ragagent.session.service.SessionKnowledgeQaService;
import java.io.IOException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * QA 三入口中两个 SSE 入口（knowledge-chat / agent-chat）的**非 HTTP 入口**：
 * 绑定 → 解析（含共享/自有 agent 解析与读者租户）→ 执行循环，写面收敛到 {@link SseSink}。
 *
 * <p>HTTP 端点（{@code KnowledgeQaController}）与 embed 委托面
 * （{@code session.api.EmbedSessionAdapter}）共用本类，两个面语义恒一致；
 * 原先 embed 面直接回调控制器公共方法，方向倒挂（api → controller），已改经本入口。</p>
 *
 * <p>解析簇/执行簇（{@link QaRequestParser}/{@link QaTurnExecutor} 等）为本类协作者，
 * 由容器装配为单例——并发守卫（409）与 stop watcher 依赖单实例图。</p>
 */
@Component
public final class QaEntryRunner {

    private final QaRequestParser qaRequestParser;
    private final QaTurnExecutor executor;
    private final AgentResolver agentResolver;
    private final TenantConfigLookup tenantService;

    public QaEntryRunner(QaRequestParser qaRequestParser, QaTurnExecutor executor,
            AgentResolver agentResolver,
            ObjectProvider<TenantConfigLookup> tenantServiceProvider) {
        this.qaRequestParser = qaRequestParser;
        this.executor = executor;
        this.agentResolver = agentResolver;
        this.tenantService = tenantServiceProvider.getIfAvailable();
    }

    /** knowledge-chat 入口：绑定 → 解析 → NORMAL 模式执行。 */
    public void runKnowledgeQA(String rawSessionId, String rawBody, String resourceUrls,
            SseSink sink) throws IOException {
        CreateKnowledgeQARequest request = QaRequestBinder.bindQaRequest(rawBody);
        ParsedRequest parsed = qaRequestParser.parseQARequest(rawSessionId, request, resourceUrls, "KnowledgeQA", agentResolver, currentTenant());
        executor.executeQA(parsed.reqCtx(), QaMode.NORMAL, !request.disableTitle, sink);
    }

    /** agent-chat 入口：agent 模式判定（customAgent.agent_mode &gt; request.agent_enabled）后分派。 */
    public void runAgentQA(String rawSessionId, String rawBody, String resourceUrls,
            SseSink sink) throws IOException {
        CreateKnowledgeQARequest request = QaRequestBinder.bindQaRequest(rawBody);
        ParsedRequest parsed = qaRequestParser.parseQARequest(rawSessionId, request, resourceUrls, "AgentQA", agentResolver, currentTenant());
        QaRequestContext reqCtx = parsed.reqCtx();

        // agent 模式判定：customAgent.agent_mode > request.agent_enabled
        boolean agentModeEnabled = request.agentEnabled;
        if (reqCtx.agentConfig != null) {
            agentModeEnabled = SessionKnowledgeQaService.isAgentMode(reqCtx.agentConfig);
        }

        if (agentModeEnabled && reqCtx.agentConfig == null) {
            throw BizException.badRequest("agent_id is required when agent mode is enabled");
        }

        if (agentModeEnabled) {
            executor.executeQA(reqCtx, QaMode.AGENT, true, sink);
        } else {
            executor.executeQA(reqCtx, QaMode.NORMAL, !request.disableTitle, sink);
        }
    }

    /**
     * 读者租户实体——供 Rewriter 解析"引用不带 provider scheme 时的租户默认
     * provider"。取不到租户实体时引用一律保留成 handle（降级形态）；
     * 按 TenantContext 的 id 取实体，与 {@code SystemController} /
     * {@code HybridSearchPort} 同一写法。
     */
    private TenantStorageView currentTenant() {
        Long tid = TenantContext.currentTenantId();
        try {
            return tid == null || tid <= 0 || tenantService == null
                    ? null : tenantService.storageView(tid);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
