package com.ragagent.agentmanagement.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.agentmanagement.domain.CustomAgentEntity;
import com.ragagent.agentmanagement.mapper.CustomAgentMapper;
import com.ragagent.agentmanagement.service.CustomAgentService;
import com.ragagent.agentmanagement.service.builtin.BuiltinAgentRegistry;
import com.ragagent.agentmanagement.service.config.AgentConfigJson;
import com.ragagent.contract.agent.AgentLookup;
import com.ragagent.platform.web.JsonMappers;
import java.io.IOException;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@link AgentLookup} 的 agentmanagement 侧实现：读路径（行查询 / 视图成形 / 推荐问题）都发生在属主域。
 *
 * <p>语义与迁移前调用方内联的实现逐一对齐：embedchannel 的 {@code ensureAgentOwned} / {@code tryAgent} /
 * {@code suggestedQuestions}（含"mapper 异常 ⇒ 视为未知"与"内建注册表兜底"两条）；im 的换绑校验
 * （{@link #hasAgentRow}：仅数据行存在，无内建兜底）与渠道列表（{@link #builtinAgentIds}）。</p>
 */
@Component
public class AgentLookupAdapter implements AgentLookup {

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    private final CustomAgentMapper agentMapper;
    private final CustomAgentService agentService;
    private final BuiltinAgentRegistry registry;

    public AgentLookupAdapter(CustomAgentMapper agentMapper, CustomAgentService agentService,
            BuiltinAgentRegistry registry) {
        this.agentMapper = agentMapper;
        this.agentService = agentService;
        this.registry = registry;
    }

    @Override
    public boolean isAgentOwned(long tenantId, String agentId) {
        String id = agentId == null ? "" : agentId.trim();
        if (id.isEmpty()) {
            return false;
        }
        try {
            if (agentMapper.getByIdAndTenant(id, tenantId) != null) {
                return true;
            }
        } catch (RuntimeException e) {
            // 查询异常＝不可用 ⇒ 与"未知 agent"同归 false（调用方按各自错误语义收口）
            return false;
        }
        return BuiltinAgentRegistry.isBuiltinAgentId(id);
    }

    @Override
    public boolean hasAgentRow(long tenantId, String agentId) {
        String id = agentId == null ? "" : agentId.trim();
        if (id.isEmpty()) {
            return false;
        }
        try {
            CustomAgentEntity row = agentMapper.getByIdAndTenant(id, tenantId);
            return row != null && row.getTenantId() != null && row.getTenantId() == tenantId;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public AgentView view(String agentId, String locale) {
        try {
            CustomAgentEntity row = agentService.getAgentById(agentId, locale).row();
            if (row == null) {
                return null;
            }
            return new AgentView(row.getId(), row.getName(), row.getDescription(), row.getAvatar(),
                    row.getConfig(), row.getTenantId());
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public List<String> builtinAgentIds() {
        return registry.orderedIds();
    }

    @Override
    public Object defaultedConfig(String configJson) {
        if (configJson == null || configJson.isEmpty()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(configJson);
            if (!(node instanceof ObjectNode obj)) {
                return null;
            }
            // 边界归一：JsonNode → 纯 JDK 值（契约面口径，）
            return JsonMappers.toJavaValue(AgentConfigJson.ensureDefaults(obj));
        } catch (RuntimeException | IOException e) {
            return null;
        }
    }

    @Override
    public java.util.List<Object> suggestedQuestions(String agentId, List<String> kbIds, int limit) {
        return JsonMappers.toJavaArray(agentService.getSuggestedQuestions(agentId, kbIds, null, null, limit, null));
    }
}
