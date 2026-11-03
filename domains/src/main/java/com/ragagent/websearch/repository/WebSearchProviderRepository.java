package com.ragagent.websearch.repository;

import com.ragagent.websearch.domain.WebSearchProviderEntity;
import com.ragagent.websearch.mapper.WebSearchProviderMapper;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Repository;

/** 联网搜索提供方仓储门面（方法为 {@link WebSearchProviderMapper} 的薄转发）。 */
@Repository
public class WebSearchProviderRepository {

    private final WebSearchProviderMapper mapper;

    public WebSearchProviderRepository(WebSearchProviderMapper mapper) {
        this.mapper = mapper;
    }

    public WebSearchProviderEntity getById(long tenantId, String id) {
        return mapper.getById(tenantId, id);
    }

    public List<WebSearchProviderEntity> list(long tenantId) {
        return mapper.list(tenantId);
    }

    public int create(WebSearchProviderEntity provider, OffsetDateTime now) {
        return mapper.create(provider, now);
    }

    public int update(WebSearchProviderEntity provider, OffsetDateTime now) {
        return mapper.update(provider, now);
    }

    public int delete(long tenantId, String id) {
        return mapper.delete(tenantId, id);
    }

    /** 清掉同租户其它提供方的默认标记。 */
    public int clearDefault(long tenantId, String excludeId) {
        return mapper.clearDefault(tenantId, excludeId);
    }
}
