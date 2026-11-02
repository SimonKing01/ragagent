package com.ragagent.contract.json;

import java.util.Collections;
import java.util.Map;

/**
 * 契约侧**纯 JDK** 的 JSON 值读取门面。
 *
 * <p><b>为什么有它</b>：去 Jackson 后，载荷里的 jsonb 字段是纯 JDK 值
 * （对象→{@code Map}、数组→{@code List}、标量→包装类型，见
 * {@code platform.web.JsonMappers#toJavaValue}）。这些字段若在**消费侧被读取**
 * （而非透传），就需要一套不依赖 Jackson 的读面——本类即该读面。</p>
 *
 * <h2>与 {@code JsonNode} 读面的语义对齐（逐条）</h2>
 * <ul>
 *   <li>{@code node.path(k).asText("")} ⇔ {@code asText(get(v, k), "")}：缺键/非标量 → 默认值；</li>
 *   <li>{@code node.get(k)} 缺键 → {@code null}（**不是** MissingNode ⇒ 用 {@code #isNull} 判"缺或空" ✓
 *       ——本仓消费侧的既有写法 `x == null || x.isNull()` 正是这一对）；</li>
 *   <li>{@code asInt/asLong/asDouble}：{@code Number} 直接取；{@code String} 尝试解析，失败 → 默认值；
 *       其余 → 默认值（对齐 {@code JsonNode#asInt(def)} 的宽松语义）；</li>
 *   <li>{@code asText}：{@code String} 原样；{@code Number}/{@code Boolean} → 其文本（对齐
 *       {@code JsonNode#asText()}）；{@code null}/集合 → 默认值；</li>
 *   <li>{@code asBoolean}：{@code Boolean} 原值；{@code String "true"/"false"}（忽略大小写）→ 解析值；
 *       其余 → 默认值（对齐 {@code TextNode.asBoolean(def)}）。</li>
 * </ul>
 *
 * <p><b>边界</b>：本类**只读不写**，且不做 JSON 序列化（需要 JSON 文本时由 L1 的
 * {@code JsonMappers} 承担）。传入 {@code JsonNode} 不会生效（契约不认 Jackson 类型）⇒
 * 调用方须先经 {@code JsonMappers#toJavaValue} 归一。</p>
 */
public final class JsonValues {

    private JsonValues() {
    }

    /** 缺键或空值（{@code null}）⇒ true（对齐既有 `x == null || x.isNull()` 写法）。 */
    public static boolean isNull(Object v) {
        return v == null;
    }

    public static boolean isObject(Object v) {
        return v instanceof Map<?, ?>;
    }

    public static boolean isArray(Object v) {
        return v instanceof java.util.List<?>;
    }

    /** 缺键 → {@code null}（不是 MissingNode；见类注释）。 */
    public static Object get(Object v, String key) {
        if (v instanceof Map<?, ?> map) {
            return map.get(key);
        }
        return null;
    }

    /** 数组元素（非数组 → 空迭代）；元素可能为 {@code null}（JSON null）。 */
    public static Iterable<Object> elements(Object v) {
        if (v instanceof java.util.List<?> list) {
            return Collections.unmodifiableList(new java.util.ArrayList<>(list));
        }
        return Collections.emptyList();
    }

    /** 对象键值（非对象 → 空 Map）。 */
    public static Map<String, Object> fields(Object v) {
        if (v instanceof Map<?, ?> map) {
            java.util.LinkedHashMap<String, Object> out = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        return Collections.emptyMap();
    }

    public static int size(Object v) {
        if (v instanceof Map<?, ?> map) {
            return map.size();
        }
        if (v instanceof java.util.List<?> list) {
            return list.size();
        }
        if (v instanceof String s) {
            return s.length();
        }
        return 0;
    }

    public static String asText(Object v, String def) {
        if (v instanceof String s) {
            return s;
        }
        if (v instanceof Number || v instanceof Boolean || v instanceof Character) {
            return String.valueOf(v);
        }
        return def;
    }

    public static int asInt(Object v, int def) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    public static long asLong(Object v, long def) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    public static double asDouble(Object v, double def) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    public static boolean asBoolean(Object v, boolean def) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s) {
            if ("true".equalsIgnoreCase(s)) {
                return true;
            }
            if ("false".equalsIgnoreCase(s)) {
                return false;
            }
        }
        return def;
    }

    /** 等价 {@code JsonNode#path(key).asBoolean(def)}（本仓视图派生访问器既有用法）。 */
    public static boolean boolAt(Object v, String key, boolean def) {
        return asBoolean(get(v, key), def);
    }
}
