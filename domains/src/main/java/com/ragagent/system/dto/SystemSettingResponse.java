package com.ragagent.system.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.system.domain.SystemSetting;
import java.time.OffsetDateTime;
import java.util.List;

/** 系统设置响应：字段名/顺序对齐实体既有 JSON；虚拟行 id 归一为 0。 */
public record SystemSettingResponse(
        Long id,
        String key,
        JsonNode value,
        String valueType,
        String category,
        String description,
        boolean isSecret,
        boolean requiresRestart,
        String lastModifiedBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<String> enumOptions,
        String lastModifiedByName) {

    public static SystemSettingResponse from(SystemSetting row) {
        return new SystemSettingResponse(
                row.getId() == null ? 0L : row.getId(),
                row.getKey(),
                row.getValue(),
                row.getValueType(),
                row.getCategory(),
                row.getDescription(),
                row.isIsSecret(),
                row.isRequiresRestart(),
                row.getLastModifiedBy(),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.getEnumOptions(),
                row.getLastModifiedByName());
    }
}
