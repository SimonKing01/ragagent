package com.ragagent.platform.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.platform.web.JsonMappers;

/**
 * 工具 schema 的技术适配：JSON 字符串 → 纯 JDK 值（{@code Map}/{@code List}/标量），
 * 供契约基类 {@code contract.tool.BaseTool} 使用。
 */
public final class ToolSchemas {

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    private ToolSchemas() {
    }

    public static Object parse(String schemaJson) {
        try {
            return JsonMappers.toJavaValue(MAPPER.readTree(schemaJson));
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid tool schema JSON", e);
        }
    }
}
