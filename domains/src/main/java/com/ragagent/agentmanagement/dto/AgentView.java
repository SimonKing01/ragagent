package com.ragagent.agentmanagement.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.Map;

/**
 * agents CRUD 的响应体（具体类型试点）。
 *
 * <p>此前由 {@link AgentResponses#agent} 手工装配 {@code LinkedHashMap}，形状靠测试兜底；
 * 改成 record 后字段与类型在编译期可见，序列化顺序仍由 {@link JsonPropertyOrder} 钉死，
 * 与手搓 Map 时期逐字节一致。</p>
 *
 * <p>两个不能想当然的字段：</p>
 * <ul>
 *   <li>{@code createdAt}/{@code updatedAt} 用 {@link Object}：零值时间要输出
 *       {@code "0001-01-01T00:00:00Z"} 而非 null，装配侧给的是常量字符串或
 *       {@link java.time.OffsetDateTime}，两种形态必须都保留。</li>
 *   <li>{@code deletedAt} 恒输出 null（前端依赖键存在）；{@code creatorName} 为空则整键省略
 *       ——所以只有它挂 {@link JsonInclude}。</li>
 * </ul>
 */
@JsonPropertyOrder({
    "id", "name", "description", "avatar", "builtin", "tenantId", "createdBy",
    "config", "createdAt", "updatedAt", "deletedAt", "creatorName"
})
public record AgentView(
        String id,
        String name,
        String description,
        String avatar,
        boolean builtin,
        long tenantId,
        String createdBy,
        Map<String, Object> config,
        Object createdAt,
        Object updatedAt,
        Object deletedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) String creatorName) {
}
