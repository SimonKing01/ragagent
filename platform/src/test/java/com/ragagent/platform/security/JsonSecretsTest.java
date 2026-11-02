package com.ragagent.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.platform.web.JsonMappers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** JSON 配置的逐字段加解密：只动敏感键、结构不变、历史明文与无密钥降级可读。 */
class JsonSecretsTest {

    private static final String KEY_A = "0123456789abcdef0123456789abcdef";
    private static final String KEY_B = "fedcba9876543210fedcba9876543210";

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    private String originalKey;

    @BeforeEach
    void installKey() {
        originalKey = CryptoService.rawAesKey();
        CryptoService.installAesKey(KEY_A);
    }

    @AfterEach
    void restoreKey() {
        CryptoService.installAesKey(originalKey);
    }

    private static JsonNode json(String raw) throws com.fasterxml.jackson.core.JsonProcessingException {
        return MAPPER.readTree(raw);
    }

    @Test
    void encryptsOnlySensitiveStringValues() throws com.fasterxml.jackson.core.JsonProcessingException {
        JsonNode out = JsonSecrets.encrypt(json("""
                {"endpoint":"https://s3.example.com","accessKeyId":"AK0",
                 "secretAccessKey":"SK0","bucketName":"b0","maxTokens":4096}
                """));

        assertThat(out.get("endpoint").asText()).isEqualTo("https://s3.example.com");
        assertThat(out.get("bucketName").asText()).isEqualTo("b0");
        assertThat(out.get("maxTokens").asInt()).isEqualTo(4096);
        assertThat(out.get("accessKeyId").asText()).startsWith("enc:v1:");
        assertThat(out.get("secretAccessKey").asText()).startsWith("enc:v1:");
    }

    @Test
    void roundTripsNestedObjectsAndArrays() throws com.fasterxml.jackson.core.JsonProcessingException {
        JsonNode original = json("""
                {"defaultProvider":"s3","s3":{"secretKey":"SK","region":"ap-east-1"},
                 "rules":[{"apiKey":"k1","name":"a"}]}
                """);

        JsonNode encrypted = JsonSecrets.encrypt(original);

        assertThat(encrypted.get("s3").get("secretKey").asText()).startsWith("enc:v1:");
        assertThat(encrypted.get("rules").get(0).get("apiKey").asText()).startsWith("enc:v1:");
        assertThat(encrypted.get("s3").get("region").asText()).isEqualTo("ap-east-1");
        assertThat(JsonSecrets.decrypt(encrypted)).isEqualTo(original);
    }

    @Test
    void keepsLegacyPlaintextReadable() throws com.fasterxml.jackson.core.JsonProcessingException {
        JsonNode plaintext = json("{\"apiKey\":\"legacy-key\"}");

        assertThat(JsonSecrets.decrypt(plaintext).get("apiKey").asText()).isEqualTo("legacy-key");
    }

    @Test
    void degradesToPlaintextWithoutKey() throws com.fasterxml.jackson.core.JsonProcessingException {
        CryptoService.installAesKey(null);
        JsonNode plaintext = json("{\"apiKey\":\"k\"}");

        assertThat(JsonSecrets.encrypt(plaintext).get("apiKey").asText()).isEqualTo("k");
    }

    @Test
    void returnsEmptyWhenKeyRotated() throws com.fasterxml.jackson.core.JsonProcessingException {
        JsonNode encrypted = JsonSecrets.encrypt(json("{\"apiKey\":\"k\"}"));

        CryptoService.installAesKey(KEY_B);

        assertThat(JsonSecrets.decrypt(encrypted).get("apiKey").asText()).isEmpty();
    }

    @Test
    void doesNotDoubleEncrypt() throws com.fasterxml.jackson.core.JsonProcessingException {
        JsonNode once = JsonSecrets.encrypt(json("{\"apiKey\":\"k\"}"));

        assertThat(JsonSecrets.encrypt(once).get("apiKey").asText())
                .isEqualTo(once.get("apiKey").asText());
    }

    @Test
    void matchesKnownSensitiveNames() {
        assertThat(JsonSecrets.isSensitiveKey("apiKey")).isTrue();
        assertThat(JsonSecrets.isSensitiveKey("mineruApiKey")).isTrue();
        assertThat(JsonSecrets.isSensitiveKey("secretAccessKey")).isTrue();
        assertThat(JsonSecrets.isSensitiveKey("botToken")).isTrue();
        assertThat(JsonSecrets.isSensitiveKey("endpoint")).isFalse();
        assertThat(JsonSecrets.isSensitiveKey("bucketName")).isFalse();
    }

    @Test
    void leavesEmptyStringsAlone() throws com.fasterxml.jackson.core.JsonProcessingException {
        JsonNode out = JsonSecrets.encrypt(json("{\"apiKey\":\"\"}"));

        assertThat(out.get("apiKey").asText()).isEmpty();
    }
}
