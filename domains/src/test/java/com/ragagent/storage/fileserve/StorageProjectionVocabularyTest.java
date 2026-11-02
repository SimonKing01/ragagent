package com.ragagent.storage.fileserve;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.platform.security.CryptoService;
import com.ragagent.platform.storage.config.StorageRuntimeEnv;
import com.ragagent.storage.config.StorageProviderEnv;
import com.ragagent.storage.domain.StorageBackend;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 存储 provider 配置的<b>两面一源</b>契约钉。
 *
 * <p>同一个「环境变量 → provider 配置」的投影，对外有两个面：</p>
 *
 * <ul>
 *   <li><b>落库面（camel）</b>——{@code StorageProviderEnv.*.writeConfig} 的输出，与
 *       {@code dto/StorageConfig} 同族（{@code accessKeyId}/{@code bucketName}/{@code pathPrefix}…），
 *       消费者是 {@code storage_backends.config} 的读写两侧
 *       （{@code StorageBackendService.configOf/serializeConfig}，忽略未知键）。</li>
 *   <li><b>引擎面（camel）</b>——{@code StorageFileResolver.toStorageEngineConfig} 的
 *       {@code renameConfigKeys(provider)} 单点派生，消费者是 {@code StorageEngineConfig}
 *       各 provider 段与解析器的完备性自校验。落库面与引擎面现已同词汇，唯一的差是
 *       <b>凭据两键的 provider 分族命名</b>（见下）。</li>
 * </ul>
 *
 * <p><b>为什么不许再出现第二份投影</b>：这里曾有两套手写实现、两套键名，产出过两个静默
 * 缺陷——①（引擎面）凭据键被统一写成 minio 形态，s3/tos/oss/ks3/obs/cos 段的凭据被 Jackson
 * 静默丢弃；②（落库面）供给器写 snake，被 camel 的行读侧静默丢空。本测试把「两面各自的键集合」
 * 与「两面之差＝凭据命名」一起钉住：任何一处再长出一份手写投影都会立刻变红。</p>
 *
 * <p><b>输入一律「全开值」</b>：落库面是空值省略语义（空串/假值整键省略），喂空值会一个键
 * 都不写、钉出空集合（首版实测踩过）；全开值才能让最大键集合显现。</p>
 */
class StorageProjectionVocabularyTest {

    /** 落库面（camel）：{@code StorageProviderEnv.*.writeConfig} 在全开输入下的键集合。 */
    private static final Map<String, List<String>> ROW_FACE_KEYS = new LinkedHashMap<>();

    /** 引擎面（camel）：经 {@code renameConfigKeys(provider)} 后的键集合。 */
    private static final Map<String, List<String>> ENGINE_FACE_KEYS = new LinkedHashMap<>();

    /** 凭据键的两族写法：两面之差只会出现在这两族之间（两族都是 camel）。 */
    private static final List<String> CREDENTIAL_CAMEL = List.of("accessKeyId", "secretAccessKey");
    private static final List<String> CREDENTIAL_ENGINE = List.of(
            "accessKeyId", "secretAccessKey", "secretId", "secretKey", "accessKey");

    static {
        ROW_FACE_KEYS.put("local", List.of("pathPrefix"));
        ROW_FACE_KEYS.put("minio", List.of("mode", "endpoint", "accessKeyId", "secretAccessKey",
                "bucketName", "pathPrefix", "useSsl"));
        ROW_FACE_KEYS.put("s3", List.of("endpoint", "region", "accessKeyId", "secretAccessKey",
                "bucketName", "pathPrefix", "useSsl", "forcePathStyle"));
        ROW_FACE_KEYS.put("cos", List.of("region", "accessKeyId", "secretAccessKey", "bucketName",
                "pathPrefix", "appId", "tempBucketName", "tempRegion"));
        ROW_FACE_KEYS.put("tos", List.of("endpoint", "region", "accessKeyId", "secretAccessKey",
                "bucketName", "pathPrefix", "tempBucketName", "tempRegion"));
        ROW_FACE_KEYS.put("oss", List.of("endpoint", "region", "accessKeyId", "secretAccessKey",
                "bucketName", "pathPrefix", "useTempBucket", "tempBucketName", "tempRegion"));
        ROW_FACE_KEYS.put("obs", List.of("endpoint", "region", "accessKeyId", "secretAccessKey",
                "bucketName", "pathPrefix", "useSsl"));

        ENGINE_FACE_KEYS.put("local", List.of("pathPrefix"));
        ENGINE_FACE_KEYS.put("minio", List.of("mode", "endpoint", "accessKeyId", "secretAccessKey",
                "bucketName", "pathPrefix", "useSsl"));
        ENGINE_FACE_KEYS.put("s3", List.of("endpoint", "region", "accessKey", "secretKey",
                "bucketName", "pathPrefix", "useSsl", "forcePathStyle"));
        ENGINE_FACE_KEYS.put("cos", List.of("region", "secretId", "secretKey", "bucketName",
                "pathPrefix", "appId", "tempBucketName", "tempRegion"));
        ENGINE_FACE_KEYS.put("tos", List.of("endpoint", "region", "accessKey", "secretKey",
                "bucketName", "pathPrefix", "tempBucketName", "tempRegion"));
        ENGINE_FACE_KEYS.put("oss", List.of("endpoint", "region", "accessKey", "secretKey",
                "bucketName", "pathPrefix", "useTempBucket", "tempBucketName", "tempRegion"));
        ENGINE_FACE_KEYS.put("obs", List.of("endpoint", "region", "accessKey", "secretKey",
                "bucketName", "pathPrefix", "useSsl"));
    }

    @AfterEach
    void tearDown() {
        // 快照是全局静态量：用完即清，避免污染其它测试
        StorageRuntimeEnv.install("", "", "");
    }

    @Test
    @DisplayName("落库面：类型化记录输出 camel 键集合（omitempty 语义）")
    void rowFaceIsCamelVocabulary() {
        for (Map.Entry<String, List<String>> e : ROW_FACE_KEYS.entrySet()) {
            ObjectNode cfg = JsonMapper.builder().build().createObjectNode();
            providerFamily(e.getKey()).writeConfig(cfg);
            assertThat(fieldNames(cfg)).as("%s：落库面键集合", e.getKey())
                    .containsExactlyInAnyOrderElementsOf(e.getValue());
        }
    }

    @Test
    @DisplayName("引擎面：env 回落行经统一次名器派生出该 provider 的键集合")
    void engineFaceVocabulary() {
        for (Map.Entry<String, List<String>> e : ENGINE_FACE_KEYS.entrySet()) {
            JsonNode cfg = engineFace(e.getKey());
            assertThat(cfg).as("%s：应有 provider 段", e.getKey()).isInstanceOf(ObjectNode.class);
            assertThat(fieldNames(cfg)).as("%s：引擎面键集合", e.getKey())
                    .containsExactlyInAnyOrderElementsOf(e.getValue());
        }
    }

    @Test
    @DisplayName("两面之差只在凭据命名：其余键两面一致（引擎面也是 camel）")
    void facesDifferOnlyInCredentials() {
        for (String provider : ROW_FACE_KEYS.keySet()) {
            List<String> nonCredentialCamel = ROW_FACE_KEYS.get(provider).stream()
                    .filter(k -> !CREDENTIAL_CAMEL.contains(k)).toList();
            List<String> expectedEngine = nonCredentialCamel;
            List<String> actualEngineNonCredential = ENGINE_FACE_KEYS.get(provider).stream()
                    .filter(k -> !CREDENTIAL_ENGINE.contains(k)).toList();
            assertThat(actualEngineNonCredential).as("%s：非凭据键两面一致", provider)
                    .containsExactlyInAnyOrderElementsOf(expectedEngine);
        }
    }

    // ── 取两侧投影 ────────────────────────────────────────────────────────

    /** 落库面：该 provider 的类型化记录（全开值，见类注释）。 */
    private static StorageProviderEnv.ProviderEnvFamily providerFamily(String provider) {
        String v = "v";
        String on = "true";
        return switch (provider) {
            case "local" -> new StorageProviderEnv.Local(v);
            case "minio" -> new StorageProviderEnv.Minio(v, v, v, v, v, on);
            case "s3" -> new StorageProviderEnv.S3(v, v, v, v, v, v, on, on);
            case "cos" -> new StorageProviderEnv.Cos(v, v, v, v, v, v, v, v);
            case "tos" -> new StorageProviderEnv.Tos(v, v, v, v, v, v, v, v);
            case "oss" -> new StorageProviderEnv.Oss(v, v, v, v, v, v, v, v);
            case "obs" -> new StorageProviderEnv.Obs(v, v, v, v, v, v, on);
            default -> throw new IllegalArgumentException("未登记的 provider: " + provider);
        };
    }

    /** 引擎面：装好 STORAGE_TYPE 与 provider 族 → 取 env 回落行 → 过统一次名器 → 该 provider 段。 */
    private static JsonNode engineFace(String provider) {
        StorageRuntimeEnv.install("", provider, "");
        StorageFileResolver resolver = new StorageFileResolver(null, null, List.of(providerFamily(provider)));
        StorageBackend row = resolver.storageBackendFromEnvironment(1L);
        assertThat(row).as("%s：env 回落行应存在", provider).isNotNull();

        CryptoService crypto = new CryptoService() {
            @Override
            public byte[] getAESKey() {
                return "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
        };
        JsonNode engine = StorageFileResolver.toStorageEngineConfig(row, crypto);
        assertThat(engine.path("defaultProvider").asText()).isEqualTo(provider);
        return engine.path(provider);
    }

    private static List<String> fieldNames(JsonNode node) {
        return java.util.stream.StreamSupport.stream(
                ((Iterable<String>) () -> node.fieldNames()).spliterator(), false).toList();
    }
}
