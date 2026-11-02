package com.ragagent.contract.knowledge;

import com.ragagent.contract.json.JsonValues;


/**
 * 契约面**纯 JDK** JSON 值读取助手（视图派生访问器用）。
 *
 * <p>去 Jackson 后视图里的 jsonb 字段是纯 JDK 值（对象→{@code Map}、数组→{@code List}、
 * 标量→包装类型，见 {@code platform.web.JsonMappers#toJavaValue}）。视图内需要读个别键时
 * 用本助手，语义与 {@code JsonNode#path(key).asBoolean(def)} **逐条对齐**：</p>
 * <ul>
 *   <li>非对象 / 缺键 / null → {@code def}；</li>
 *   <li>{@code Boolean} → 原值；</li>
 *   <li>{@code String} 且为 {@code "true"}/{@code "false"}（忽略大小写）→ 解析值（对齐
 *       Jackson 的 {@code TextNode.asBoolean(def)}）；其余字符串 → {@code def}。</li>
 * </ul>
 */
public final class JsonValueSupport {

    private JsonValueSupport() {
    }

    /** 等价 {@code JsonNode#path(key).asBoolean(def)}（null 安全）。 */
    public static boolean boolAt(Object value, String key, boolean def) {
        // 单一实现：契约侧通用读取门面
        return JsonValues.boolAt(value, key, def);
    }
}
