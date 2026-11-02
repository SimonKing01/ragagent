package com.ragagent.datasource.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.datasource.domain.DataSource;
import com.ragagent.datasource.dto.DataSourceOwnership;
import com.ragagent.datasource.dto.DataSourceResponse;
import com.ragagent.datasource.dto.ResourceResponse;
import com.ragagent.datasource.dto.SyncLogResponse;
import com.ragagent.platform.web.JsonMappers;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 数据源控制面读模型：把实体装配/映射收在这里，控制器只拿响应 DTO。 */
@Service
public class DataSourceReadService {

    private final DataSourceService dataSourceService;

    public DataSourceReadService(DataSourceService dataSourceService) {
        this.dataSourceService = dataSourceService;
    }

    public DataSourceResponse create(JsonNode body, long tenantId) {
        DataSource ds = toEntity(body);
        ds.setTenantId(tenantId);
        return DataSourceResponse.from(dataSourceService.createDataSource(ds));
    }

    public DataSourceResponse get(String id) {
        return DataSourceResponse.from(dataSourceService.getDataSource(id));
    }

    public List<DataSourceResponse> list(String kbId) {
        return dataSourceService.listDataSources(kbId).stream()
                .map(DataSourceResponse::from).toList();
    }

    public DataSourceResponse update(String id, JsonNode body, long tenantId, String kbId) {
        DataSource ds = toEntity(body);
        ds.setId(id);
        ds.setTenantId(tenantId);
        ds.setKnowledgeBaseId(kbId);
        return DataSourceResponse.from(dataSourceService.updateDataSource(ds));
    }

    public DataSourceOwnership ownership(String id) {
        var ds = dataSourceService.getDataSource(id);
        return ds == null ? null
                : new DataSourceOwnership(
                        ds.getId(), ds.getKnowledgeBaseId());
    }

    public boolean updateCredentials(String id, Map<String, Object> credentials) {
        var updated = dataSourceService.updateDataSourceCredentials(id, credentials);
        try {
            var parsed = updated.parseConfig();
            return parsed != null && parsed.hasConfiguredCredentials(updated.getType());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public List<ResourceResponse> resources(String dsId, String parentId) {
        var resources = dataSourceService.listAvailableResources(dsId, parentId);
        return resources == null ? List.of()
                : resources.stream().map(ResourceResponse::from).toList();
    }

    public SyncLogResponse manualSync(String dsId) {
        return SyncLogResponse.from(dataSourceService.manualSync(dsId));
    }

    public List<SyncLogResponse> syncLogs(String dsId, int limit, int offset) {
        var logs = dataSourceService.getSyncLogs(dsId, limit, offset);
        return logs == null ? List.of() : logs.stream().map(SyncLogResponse::from).toList();
    }

    public SyncLogResponse syncLog(String logId) {
        return SyncLogResponse.from(dataSourceService.getSyncLog(logId));
    }

    private static DataSource toEntity(JsonNode body) {
        try {
            return JsonMappers.lenient().treeToValue(body, DataSource.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid request", e);
        }
    }
}
