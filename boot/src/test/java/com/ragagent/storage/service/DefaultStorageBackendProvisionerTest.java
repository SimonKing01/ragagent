package com.ragagent.storage.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.TestSchema;
import com.ragagent.contract.storage.StorageBackendProvisioner;
import com.ragagent.platform.storage.config.StorageRuntimeEnv;
import com.ragagent.storage.domain.StorageBackend;
import com.ragagent.storage.dto.StorageConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 存储后端默认装配：env → provider 环境变量族 → 落库 config 的形状钉子。
 *
 * <p>取值走 {@code @ConfigurationProperties} 绑定（本类用测试属性代替 env，松散绑定与 env 同一条路）。
 * 断言要点：键名＝落库面 camel（与 {@code dto/StorageConfig} 同族）、空值整键省略、
 * {@code use_ssl} 在 {@code S3_USE_SSL} 未设置时缺省为真（仅当值大小写不敏感等于 "false" 才为假）、
 * {@code force_path_style} 只在恰为 "true" 时写出。</p>
 */
@SpringBootTest(properties = {
        "storage.type=s3",
        "system.aes-key=0123456789abcdef0123456789abcdef",
        "s3.endpoint=https://s3.example.com",
        "s3.region=ap-east-1",
        "s3.access-key=AK0",
        "s3.secret-key=SK0",
        "s3.bucket-name=b0-bucket",
        "s3.path-prefix=b3/",
        "s3.force-path-style=true"
})
class DefaultStorageBackendProvisionerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long TENANT = 10002L;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StorageBackendProvisioner provisioner;
    @Autowired
    private StorageBackendService storageService;

    /**
     * 全局快照是**静态**的：本类把 storage.type 配成 s3，而 Spring 上下文复用不会重新 install，
     * 不恢复就会污染后续复用上下文的其他测试（实测：FaqContractTest 的 CSV 导出走了云分支）。
     */
    @AfterAll
    static void restoreStorageEnv() {
        StorageRuntimeEnv.install("", "", "");
    }

    @BeforeEach
    void seed() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
    }

    @Test
    void provisionsS3BackendFromEnvFamily() throws Exception {
        String id = provisioner.provisionForTenant(TENANT);

        String configText = jdbc.queryForObject(
                "SELECT config FROM storage_backends WHERE id = ?", String.class, id);
        JsonNode config = MAPPER.readTree(configText);

        assertThat(config.path("endpoint").asText()).isEqualTo("https://s3.example.com");
        assertThat(config.path("region").asText()).isEqualTo("ap-east-1");
        // 非凭据字段原样落库（camel）
        assertThat(config.path("bucketName").asText()).isEqualTo("b0-bucket");
        assertThat(config.path("pathPrefix").asText()).isEqualTo("b3/");
        // S3_USE_SSL 未设置 → 缺省真；S3_FORCE_PATH_STYLE="true" → 显式写出
        assertThat(config.path("useSsl").asBoolean()).isTrue();
        assertThat(config.path("forcePathStyle").asBoolean()).isTrue();
        // 凭据必须**加密落库**：此前供给器直接 toString() 写库，私钥是明文
        assertThat(config.path("accessKeyId").asText()).startsWith("enc:v1:");
        assertThat(config.path("secretAccessKey").asText()).startsWith("enc:v1:");

        // 消费者层断言：走唯一读写口解回来必须是明文（与 StorageBackendService.configOf 同路）
        StorageBackend row = new StorageBackend();
        row.setProvider("s3");
        row.setConfig(config);
        StorageConfig readBack = storageService.configOf(row);
        assertThat(readBack.accessKeyId).isEqualTo("AK0");
        assertThat(readBack.secretAccessKey).isEqualTo("SK0");
        assertThat(readBack.bucketName).isEqualTo("b0-bucket");
        assertThat(readBack.pathPrefix).isEqualTo("b3/");
        assertThat(readBack.useSsl).isTrue();
        assertThat(readBack.forcePathStyle).isTrue();

        assertThat(jdbc.queryForObject(
                "SELECT provider FROM storage_backends WHERE id = ?", String.class, id))
                .isEqualTo("s3");
        assertThat(jdbc.queryForObject(
                "SELECT name FROM storage_backends WHERE id = ?", String.class, id))
                .isEqualTo("System S3");
        assertThat(jdbc.queryForObject(
                "SELECT source FROM storage_backends WHERE id = ?", String.class, id))
                .isEqualTo("env");
    }
}
