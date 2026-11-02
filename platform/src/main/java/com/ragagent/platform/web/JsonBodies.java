package com.ragagent.platform.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.contract.error.AppError;
import com.ragagent.contract.error.BizException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 请求体 JSON 的通用读取助手：绑定对象、取文本字段、ISO 时间投影。
 *
 * <p>供各域协作者直接复用；放在 platform 使跨域调用不产生域间依赖。</p>
 */
public final class JsonBodies {

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    private JsonBodies() {
    }

    /**
     * 绑定请求体为 JSON 对象。
     *
     * <p>空体 / 语法错 / 非对象都转 400 信封；字面 {@code null} 按空对象处理。</p>
     */
    public static JsonNode bindObject(String rawBody) {
        if (rawBody == null || rawBody.isEmpty()) {
            throw new BizException(AppError.badRequest(JsonBodyErrors.EMPTY_BODY));
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(rawBody);
        } catch (Exception e) {
            throw new BizException(AppError.badRequest(e.getMessage()));
        }
        if (node == null) {
            node = MAPPER.createObjectNode();
        }
        if (!node.isObject()) {
            throw new BizException(AppError.badRequest(ToolJson.expectedObjectMessage(node)));
        }
        return node;
    }

    /** 取文本字段；缺失与 null 都返回空串。 */
    public static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    /** null → 空串。 */
    public static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    /** 保留原 offset 的 ISO-8601 时间；null 输出零值。 */
    public static String timeAsIs(OffsetDateTime value) {
        return value == null ? "0001-01-01T00:00:00Z"
                : value.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    /** 转系统时区的 ISO-8601 时间；null 输出零值（线格式契约）。 */
    public static String isoTime(OffsetDateTime value) {
        if (value == null) {
            return "0001-01-01T00:00:00Z";
        }
        return value.atZoneSameInstant(ZoneId.systemDefault()).toOffsetDateTime()
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
