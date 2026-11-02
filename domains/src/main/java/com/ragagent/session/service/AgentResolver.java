package com.ragagent.session.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.contract.agent.AgentLookup;
import com.ragagent.contract.agent.AgentLookup.AgentView;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.platform.web.JsonMappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * agent 解析。
 * 空间分享裁撤后只有自有 agent 一条路径：本租户直查；查询失败用默认空配置。
 *
 * <p>经 {@link AgentLookup} 读面取视图——agent 的持久化形状留在属主域，
 * 本域只消费契约视图（id / 配置串 / 租户归属）。</p>
 */
@Component
public class AgentResolver {

    private static final Logger log = LoggerFactory.getLogger(AgentResolver.class);
    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    private final AgentLookup agentLookup;

    public AgentResolver(AgentLookup agentLookup) {
        this.agentLookup = agentLookup;
    }

    /** 解析结果：agent 视图（可为 null）+ 有效租户（恒当前租户，0 = 未解析到）。 */
    public record ResolvedAgent(AgentView agent, long effectiveTenantId) {
    }

    public ResolvedAgent resolve(String agentId, long sourceTenantId) {
        if (agentId == null || agentId.isEmpty()) {
            return new ResolvedAgent(null, 0);
        }
        AgentView agent = null;
        long effectiveTenantId = 0;
        Long currentTenant = TenantContext.currentTenantId();
        if (currentTenant != null && currentTenant != 0) {
            try {
                agent = agentLookup.view(agentId);
                if (agent != null) {
                    effectiveTenantId = currentTenant;
                }
            } catch (RuntimeException e) {
                log.warn("Failed to get custom agent, agent ID: {}, using default config", agentId, e);
            }
        }
        return new ResolvedAgent(agent, effectiveTenantId);
    }

    /**
     * 给未带实体的请求补 {@code agentRow}（im 面：请求只带 agentId）。
     * 已解析（web 面已填视图）或 id 为空 ⇒ 不动；解析失败 ⇒ 保持 null（与 {@link #resolve} 的宽松语义一致）。
     */
    public void resolveInto(QaSupport.QaRequest req) {
        if (req.agentRow != null || req.agentId == null || req.agentId.isEmpty()) {
            return;
        }
        req.agentRow = resolve(req.agentId, 0).agent();
    }

    /** config 列（jsonb 文本）→ ObjectNode；非法 → RuntimeException。 */
    public static ObjectNode parseAgentConfig(AgentView agent) {
        String config = agent.configJson();
        try {
            return (ObjectNode) MAPPER.readTree(config == null ? "{}" : config);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("failed to parse agent config: " + e.getMessage(), e);
        }
    }
}
