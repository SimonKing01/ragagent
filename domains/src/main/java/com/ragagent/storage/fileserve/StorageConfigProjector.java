package com.ragagent.storage.fileserve;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.platform.security.CryptoService;
import com.ragagent.platform.web.JsonMappers;
import com.ragagent.storage.domain.StorageBackend;
import com.ragagent.storage.provider.StorageProviders;

/**
 * 存储实例行 → 引擎面配置投影：camelCase 键改名、凭据解密。
 *
 * <p>这是曾出过“静默丢凭据”bug 的高危区：除 minio 外的 provider 凭据键名不同，
 * 统一改写成 minio 形态会被 Jackson 静默丢弃（转换器忽略未知键）。改键规则与
 * 解密语义集中在此，单测覆盖两族凭据。</p>
 */
final class StorageConfigProjector {

    private static final CryptoService CRYPTO = new CryptoService();

    private StorageConfigProjector() {
    }

    /** 实例模型 → 单例配置投影（生产密钥）。 */
    static JsonNode toStorageEngineConfig(StorageBackend b) {
        return toStorageEngineConfig(b, CRYPTO);
    }

    /** 实例行 → provider 段（凭据必须解密）。 */
    static JsonNode toStorageEngineConfig(StorageBackend b, CryptoService crypto) {
        var cfg = new ObjectNode(JsonMappers.lenient().getNodeFactory());
        cfg.put("defaultProvider", b.getProvider());
        JsonNode c = b.getConfig();
        switch (b.getProvider() == null ? "" : b.getProvider()) {
            case "local" -> {
                var local = cfg.putObject("local");
                local.put("pathPrefix", textValue(c, "pathPrefix"));
            }
            default -> {
                if (c != null) {
                    var copy = c.deepCopy();
                    renameConfigKeys(copy, b.getProvider());
                    decryptCredentials(copy, crypto);
                    cfg.set(b.getProvider(), copy);
                }
            }
        }
        return cfg;
    }

    /**
     * 行配置（camelCase）→ 各 provider 段字段名。自由键原样保留。
     *
     * <p>凭据两键按 provider 分族：Minio 认 {@code accessKeyId}/{@code secretAccessKey}、
     * COS 认 {@code secretId}/{@code secretKey}、其余认 {@code accessKey}/{@code secretKey}。</p>
     */
    private static void renameConfigKeys(JsonNode providerConfig, String provider) {
        if (!(providerConfig instanceof ObjectNode obj)) {
            return;
        }
        StorageProviders.CredentialFields fields = StorageProviders.credentials(
                provider == null ? "" : provider.trim().toLowerCase(java.util.Locale.ROOT));
        String accessField = fields.accessKey();
        String secretField = fields.secretKey();
        moveKey(obj, "accessKeyId", accessField);
        moveKey(obj, "secretAccessKey", secretField);
    }

    /** 改键：目标键已存在时保留现状（行里显式给的引擎面键优先）。 */
    private static void moveKey(ObjectNode obj, String from, String to) {
        var v = obj.remove(from);
        if (v != null && !obj.has(to)) {
            obj.set(to, v);
        }
    }

    /** 解密两族凭据命名（只有带 {@code enc:v1:} 前缀的才是密文，其余原样）。 */
    private static void decryptCredentials(JsonNode providerConfig, CryptoService crypto) {
        for (String field : new String[]{
                "accessKeyId", "secretAccessKey", "secretId", "secretKey", "accessKey"}) {
            JsonNode value = providerConfig.get(field);
            if (value != null && value.isTextual()) {
                ((ObjectNode) providerConfig)
                        .put(field, crypto.decryptStoredSecret(value.asText()));
            }
        }
    }

    private static String textValue(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText();
    }
}
