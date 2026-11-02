package com.ragagent.memory.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.contract.memory.MemoryConfig;
import com.ragagent.contract.tenant.TenantConfigLookup;
import com.ragagent.memory.MemoryContext;
import com.ragagent.memory.domain.scope.MemoryScope;
import com.ragagent.memory.domain.scope.MemorySubject;
import com.ragagent.memory.repository.MemoryRepository;
import com.ragagent.platform.web.JsonMappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 记忆三层开关的单次求值：workspace → agent → subject。
 *
 * <p>「能不能用」与「为什么不能用」必须是同一次求值的两个出口；分成两份实现时，
 * 改一处忘另一处会让 API 的回答与写路径的判定悄悄分叉。调用方用
 * {@link MemoryService.ScopeState#ok()} / {@link MemoryService.ScopeState#reason()}
 * 消费同一结果。</p>
 */
final class MemoryScopeGate {

    private static final Logger log = LoggerFactory.getLogger(MemoryScopeGate.class);
    private static final ObjectMapper CONFIG_MAPPER = JsonMappers.lenient();

    private final MemoryRepository repo;
    private final TenantConfigLookup tenantService;

    MemoryScopeGate(MemoryRepository repo, TenantConfigLookup tenantService) {
        this.repo = repo;
        this.tenantService = tenantService;
    }

    /** 单次解析作用域并逐层判定；失败时 reason 说明卡在哪一层。 */
    MemoryService.ScopeState evaluate() {
        MemoryScope scope;
        try {
            scope = MemoryScopes.resolve();
        } catch (MemoryScopeExceptions.NoScope e) {
            return new MemoryService.ScopeState(null, null, false, "no_principal");
        }
        MemoryConfig cfg = workspaceConfig(scope.tenantId());
        if (!cfg.memoryEnabled()) {
            return new MemoryService.ScopeState(scope, cfg, false, "workspace_disabled");
        }
        if (!MemoryContext.allowedForAgent()) {
            return new MemoryService.ScopeState(scope, cfg, false, "agent_disabled");
        }
        MemorySubject subject;
        try {
            subject = repo.getSubject(scope);
        } catch (RuntimeException e) {
            log.warn("memory: load subject failed", e);
            return new MemoryService.ScopeState(scope, cfg, false, "subject_load_failed");
        }
        // 主体行在第一次写入时创建。它不存在意味着这个人还什么都没存，
        // 对写路径来说仍然算"启用"。
        if (subject != null && !subject.isEnabled()) {
            return new MemoryService.ScopeState(scope, cfg, false, "user_disabled");
        }
        return new MemoryService.ScopeState(scope, cfg, true, "");
    }

    /**
     * 读工作区的记忆开关。
     *
     * <p>租户不存在或那一列没配时返回**零值配置**（{@code enabled=false}，
     * 且**不做 normalize**）——也就是"关着"。</p>
     */
    MemoryConfig workspaceConfig(long tenantId) {
        JsonNode node;
        try {
            node = JsonMappers.toJsonNode(tenantService.memoryConfig(tenantId));
        } catch (RuntimeException e) {
            log.warn("memory: load workspace config failed", e);
            return new MemoryConfig();
        }
        if (node == null || node.isNull()) {
            return new MemoryConfig();
        }
        MemoryConfig cfg;
        try {
            cfg = CONFIG_MAPPER.treeToValue(node, MemoryConfig.class);
        } catch (Exception e) {
            log.warn("memory: unparsable workspace memory config", e);
            return new MemoryConfig();
        }
        if (cfg == null) {
            return new MemoryConfig();
        }
        cfg.normalize();
        return cfg;
    }
}
