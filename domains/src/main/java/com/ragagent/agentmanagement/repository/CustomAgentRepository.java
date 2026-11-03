package com.ragagent.agentmanagement.repository;

import com.ragagent.agentmanagement.domain.CustomAgentEntity;
import com.ragagent.agentmanagement.mapper.CustomAgentMapper;
import java.util.List;
import org.springframework.stereotype.Repository;

/** 自定义 Agent 仓储门面（方法为 {@link CustomAgentMapper} 的薄转发）。 */
@Repository
public class CustomAgentRepository {

    private final CustomAgentMapper mapper;

    public CustomAgentRepository(CustomAgentMapper mapper) {
        this.mapper = mapper;
    }

    public CustomAgentEntity getByIdAndTenant(String id, long tenantId) {
        return mapper.getByIdAndTenant(id, tenantId);
    }

    public List<CustomAgentEntity> listByTenant(long tenantId) {
        return mapper.listByTenant(tenantId);
    }

    public void insertAgent(CustomAgentEntity entity) {
        mapper.insertAgent(entity);
    }

    public int updateAgent(CustomAgentEntity entity) {
        return mapper.updateAgent(entity);
    }

    public int softDelete(String id, long tenantId) {
        return mapper.softDelete(id, tenantId);
    }
}
