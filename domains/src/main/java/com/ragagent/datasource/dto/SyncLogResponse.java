package com.ragagent.datasource.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.datasource.domain.SyncLog;
import java.time.OffsetDateTime;

/** 同步日志响应：字段名/顺序对齐实体既有 JSON。 */
public record SyncLogResponse(
        String id,
        String dataSourceId,
        Long tenantId,
        String status,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        int itemsTotal,
        int itemsCreated,
        int itemsUpdated,
        int itemsDeleted,
        int itemsSkipped,
        int itemsFailed,
        String errorMessage,
        JsonNode result,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static SyncLogResponse from(SyncLog log) {
        return new SyncLogResponse(
                log.getId(),
                log.getDataSourceId(),
                log.getTenantId(),
                log.getStatus(),
                log.getStartedAt(),
                log.getFinishedAt(),
                log.getItemsTotal(),
                log.getItemsCreated(),
                log.getItemsUpdated(),
                log.getItemsDeleted(),
                log.getItemsSkipped(),
                log.getItemsFailed(),
                log.getErrorMessage(),
                log.getResult(),
                log.getCreatedAt(),
                log.getUpdatedAt());
    }
}
