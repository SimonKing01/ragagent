package com.ragagent.system.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.platform.persistence.PgJsonTypeHandler;
import com.ragagent.platform.web.ZeroTimeSerializer;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * system_settings 表实体（迁移 000053）。
 *
 * <p>JSON 响应形态（键名 = Java 字段名；可空字段显式 null）：
 * id, key, value（jsonb 原样内联）, valueType, category, description, isSecret,
 * requiresRestart, lastModifiedBy, createdAt, updatedAt, enumOptions, lastModifiedByName。</p>
 *
 * <p>⚠️ 虚拟行（registry 有、DB 无）的时间戳固定为 "0001-01-01T00:00:00Z"
 * ——字段默认值本身即持有该值（null 序列化不走自定义序列化器）。</p>
 */
@TableName(value = "system_settings", autoResultMap = true)
public class SystemSetting {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** H2 保留字 → 列名带引号（PG 侧不加引号等价；MP 生成 SQL 时原样携带） */
    @com.baomidou.mybatisplus.annotation.TableField("\"key\"")
    private String key;

    /**
     * jsonb 列，响应里原样内联（JsonNode 直接输出）：
     * int → 42、string → "foo"、bool → true、string_list → ["a","b"]。
     * H2 保留字 → 列名带引号。
     */
    @TableField(value = "\"value\"", typeHandler = PgJsonTypeHandler.class)
    private JsonNode value;

    private String valueType;

    private String category;

    private String description;

    private boolean isSecret;

    private boolean requiresRestart;

    /** DB NULL → ""（getter 归一化，恒输出） */
    private String lastModifiedBy;

    /** 虚拟行 = 零值占位时间（0001-01-01T00:00:00Z）；持久行 = DB 值 */
    private OffsetDateTime createdAt = ZeroTimeSerializer.ZERO_DATE_TIME;

    private OffsetDateTime updatedAt = ZeroTimeSerializer.ZERO_DATE_TIME;

    /** registry 元数据，落库前必须清空（未产出时显式 null） */
    @TableField(exist = false)
    private List<String> enumOptions;

    /** handler enrich（username → email 回落），未产出时显式 null */
    @TableField(exist = false)
    private String lastModifiedByName;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public JsonNode getValue() { return value; }
    public void setValue(JsonNode value) { this.value = value; }
    public String getValueType() { return valueType; }
    public void setValueType(String valueType) { this.valueType = valueType; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    /** NULL → ""（IsBootstrapDefaultRow 依赖 trim 后非空判定） */
    public String getDescription() { return description == null ? "" : description; }
    public void setDescription(String description) { this.description = description; }
    /** 字段名带 is 前缀但 getter isIsSecret() 的隐式属性名与字段一致 → 合并为一个属性 */
    public boolean isIsSecret() { return isSecret; }
    public void setIsSecret(boolean secret) { isSecret = secret; }
    public boolean isRequiresRestart() { return requiresRestart; }
    public void setRequiresRestart(boolean requiresRestart) { this.requiresRestart = requiresRestart; }
    public String getLastModifiedBy() { return lastModifiedBy == null ? "" : lastModifiedBy; }
    public void setLastModifiedBy(String lastModifiedBy) { this.lastModifiedBy = lastModifiedBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
    public List<String> getEnumOptions() { return enumOptions; }
    public void setEnumOptions(List<String> enumOptions) { this.enumOptions = enumOptions; }
    public String getLastModifiedByName() { return lastModifiedByName; }
    public void setLastModifiedByName(String lastModifiedByName) { this.lastModifiedByName = lastModifiedByName; }
}
