package com.ragagent.platform.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.platform.security.JsonSecrets;

/**
 * jsonb 配置列的加密版：写前逐字段加密、读后逐字段解密，**结构不变**，读方无感。
 *
 * <p>只用在**含凭据的配置列**（租户的 storage / web-search / parser 三列）；
 * 不要全局注册 —— 普通 jsonb 列（会话上下文、检索元数据）不需要这层编解码开销。</p>
 *
 * <p>读侧对历史明文行原样返回（{@link JsonSecrets} 只认 {@code enc:v1:} 前缀），
 * 所以启用本 handler 不需要停机迁移；存量行在下次写回时自动转密文。</p>
 */
public class SecretJsonTypeHandler extends PgJsonTypeHandler {

    public SecretJsonTypeHandler(Class<?> type) {
        super(type);
    }

    @Override
    public String toJson(Object obj) {
        return super.toJson(obj instanceof JsonNode node ? JsonSecrets.encrypt(node) : obj);
    }

    @Override
    public Object parse(String json) {
        Object parsed = super.parse(json);
        return parsed instanceof JsonNode node ? JsonSecrets.decrypt(node) : parsed;
    }
}
