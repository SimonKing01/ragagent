package com.ragagent.platform.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.platform.web.JsonMappers;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JSON 树内**按字段名**加解密敏感值（用于 jsonb 配置列）。
 *
 * <p>为什么不是整块加密：这些列（租户的 storage/web-search/parser 配置）被大量读方按字段消费，
 * 整块加密会让所有读方都得先解密。逐字段只把 {@code apiKey}/{@code secretKey}/… 的值换成
 * {@code enc:v1:...}，其余结构与明文配置完全一致，读方无感。</p>
 *
 * <p>匹配规则：键名小写后含 {@code secret / apikey / accesskey / token / password /
 * credential / privatekey / aeskey} 之一，且值是**非空字符串**（数字字段如 {@code maxTokens}
 * 天然跳过）。宁可多加密一个字段也不漏：读侧对非密文原样返回、对密文宽容解密，
 * 误加密只多一层编解码，不影响取值。</p>
 *
 * <p>解密失败（密钥缺失/轮换）返回空串并记日志，与 MCP 加密列同口径 —— 不让密文冒充满值。</p>
 */
public final class JsonSecrets {

    private static final Logger log = LoggerFactory.getLogger(JsonSecrets.class);

    private static final String PREFIX = "enc:v1:";
    private static final List<String> MARKERS = List.of(
            "secret", "apikey", "accesskey", "token", "password", "credential", "privatekey", "aeskey");

    private static final ObjectMapper MAPPER = JsonMappers.lenient();
    private static final CryptoService CRYPTO = new CryptoService();

    private JsonSecrets() {
    }

    /** 写路径：把敏感字段替换为密文（已加密的保持不动）。 */
    public static JsonNode encrypt(JsonNode node) {
        return walk(node, true);
    }

    /** 读路径：把密文还原为明文（历史明文原样返回）。 */
    public static JsonNode decrypt(JsonNode node) {
        return walk(node, false);
    }

    /** 键名是否按敏感处理（大小写不敏感的子串匹配）。 */
    public static boolean isSensitiveKey(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        for (String marker : MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static JsonNode walk(JsonNode node, boolean encrypting) {
        if (node == null) {
            return null;
        }
        if (node instanceof ObjectNode obj) {
            ObjectNode out = MAPPER.createObjectNode();
            obj.fieldNames().forEachRemaining(name -> {
                JsonNode value = obj.get(name);
                if (value != null && value.isTextual() && isSensitiveKey(name) && !value.asText().isEmpty()) {
                    out.set(name, MAPPER.getNodeFactory().textNode(
                            encrypting ? encryptValue(value.asText()) : decryptValue(value.asText())));
                } else {
                    out.set(name, walk(value, encrypting));
                }
            });
            return out;
        }
        if (node instanceof ArrayNode arr) {
            ArrayNode out = MAPPER.createArrayNode();
            for (JsonNode item : arr) {
                out.add(walk(item, encrypting));
            }
            return out;
        }
        return node;
    }

    private static String encryptValue(String plaintext) {
        if (plaintext.startsWith(PREFIX)) {
            return plaintext;
        }
        byte[] key = CRYPTO.getAESKey();
        if (key == null || key.length == 0) {
            return plaintext;
        }
        return CRYPTO.encryptAESGCM(plaintext, key);
    }

    private static String decryptValue(String stored) {
        if (!stored.startsWith(PREFIX)) {
            return stored;
        }
        CryptoService.LenientResult result = CRYPTO.decryptStoredSecretLenient(stored);
        if (!result.ok()) {
            log.warn("[crypto] json secret: decrypt failed (SYSTEM_AES_KEY missing/rotated?), "
                    + "treating as empty");
            return "";
        }
        return result.plaintext();
    }
}
