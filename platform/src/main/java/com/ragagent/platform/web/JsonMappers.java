package com.ragagent.platform.web;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 读 JSON 的统一策略：忽略未知属性 + 标准 java.time 支持。
 *
 * <p>落库 jsonb 与模型/外部返回的 JSON 都可能带有本仓未知的键，逐类挂
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} 是把同一条策略复制多份；
 * 统一由本工厂（以及 Spring MVC 侧的 {@code spring.jackson} 配置）承担。
 *
 * <p><b>java.time 为什么必须在本工厂注册</b>：jsonb 读路径用的是这里造的 mapper，
 * 而裸 {@code JsonMappers.lenient()} 未注册 JSR-310 模块，遇到 {@code java.time} 字段会抛
 * {@code InvalidDefinitionException}。时间类型由本工厂统一按 ISO-8601 处理
 * （{@code WRITE_DATES_AS_TIMESTAMPS} 关闭，输出带偏移的字符串而非 epoch 数字）。</p>
 */
public final class JsonMappers {

    private JsonMappers() {
    }

    /**
     * 宽松 reader/writer：未知属性不报错，时间类型按 ISO-8601 处理，其余与
     * {@code new ObjectMapper()} 等价。
     */
    public static JsonMapper lenient() {
        return JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .addModule(new JavaTimeModule())
                // 契约载荷的线格式（键名/省略策略/枚举字面量）由 mixin 模块承载（契约面纯 JDK）
                .addModule(new ContractJsonModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }

    /**
     * 配置面读取用：在 {@link #lenient()} 之上**禁止标量强转**——把数字/布尔塞进字符串字段
     * 是配置错误，静默强转只会得到一个"看起来合法的坏配置"。
     */
    public static ObjectMapper lenientStrictScalars() {
        return lenient().rebuild()
                .withCoercionConfig(LogicalType.Textual, c -> c
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
                .build();
    }

    /**
     * {@code JsonNode} → 纯 JDK 值（对象→{@code LinkedHashMap}、数组→{@code ArrayList}、
     * 文本/数值/布尔/null→对应 JDK 类型）——供**去 Jackson 的契约载荷**在边界转换，
     * 转换后由 Jackson 序列化的字节与直接序列化原 {@code JsonNode} **一致**（键序保留）。
     */
    public static Object toJavaValue(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return lenient().convertValue(node, Object.class);
    }

    /**
     * 纯 JDK 值 → {@code JsonNode}（{@link #toJavaValue} 的逆）。**null 保持 null**（不产
     * {@code NullNode}）——域内多处用 {@code == null} 判定"未设置"并套默认值（如
     * {@code TenantService.createTenant} 的 retriever_engines 兜底），返回 {@code NullNode}
     * 会让这些分支失效 ⇒ 必须保 null。需要显式 JSON {@code null} 字面量时由调用方自行
     * {@code MAPPER.nullNode()}。
     */
    public static com.fasterxml.jackson.databind.JsonNode toJsonNode(Object value) {
        return value == null ? null : lenient().valueToTree(value);
    }

    /** {@code List<JsonNode>} → 纯 JDK 列表（逐元素 {@link #toJavaValue}；null 保持 null）。 */
    public static java.util.List<Object> toJavaList(java.util.List<com.fasterxml.jackson.databind.JsonNode> nodes) {
        if (nodes == null) {
            return null;
        }
        java.util.List<Object> out = new java.util.ArrayList<>(nodes.size());
        for (com.fasterxml.jackson.databind.JsonNode n : nodes) {
            out.add(toJavaValue(n));
        }
        return out;
    }

    /** 纯 JDK 列表 → {@code List<JsonNode>}（逐元素 {@link #toJsonNode}；null 保持 null）。 */
    public static java.util.List<com.fasterxml.jackson.databind.JsonNode> toJsonList(java.util.List<Object> values) {
        if (values == null) {
            return null;
        }
        java.util.List<com.fasterxml.jackson.databind.JsonNode> out = new java.util.ArrayList<>(values.size());
        for (Object v : values) {
            out.add(toJsonNode(v));
        }
        return out;
    }

    /** 数组节点 → 纯 JDK 列表（逐元素 {@link #toJavaValue}；非数组/ null ⇒ 空列表）。 */
    public static java.util.List<Object> toJavaArray(com.fasterxml.jackson.databind.JsonNode node) {
        java.util.List<Object> out = new java.util.ArrayList<>();
        if (node == null || !node.isArray()) {
            return out;
        }
        for (com.fasterxml.jackson.databind.JsonNode n : node) {
            out.add(toJavaValue(n));
        }
        return out;
    }

    /** 纯 JDK 值 → {@code ObjectNode}（对象 ⇒ 节点；非对象 / null ⇒ {@code null}）。 */
    public static com.fasterxml.jackson.databind.node.ObjectNode toObjectNode(Object value) {
        com.fasterxml.jackson.databind.JsonNode node = toJsonNode(value);
        return node instanceof com.fasterxml.jackson.databind.node.ObjectNode obj ? obj : null;
    }
}
