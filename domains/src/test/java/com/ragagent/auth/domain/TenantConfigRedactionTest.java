package com.ragagent.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ragagent.contract.tenant.ParserEngineConfig;
import com.ragagent.contract.tenant.StorageEngineConfig;
import com.ragagent.contract.tenant.StorageEngineConfig.CosEngineConfig;
import com.ragagent.contract.tenant.StorageEngineConfig.Ks3EngineConfig;
import com.ragagent.contract.tenant.StorageEngineConfig.MinioEngineConfig;
import com.ragagent.contract.tenant.StorageEngineConfig.ObsEngineConfig;
import com.ragagent.contract.tenant.StorageEngineConfig.OssEngineConfig;
import com.ragagent.contract.tenant.StorageEngineConfig.S3EngineConfig;
import com.ragagent.contract.tenant.StorageEngineConfig.TosEngineConfig;
import com.ragagent.contract.tenant.WebSearchConfig;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 租户配置脱敏（{@link TenantConfigRedaction}）：密钥不得明文出响应面。
 *
 * <p>为什么单测值得写在这里：这几个方法是**纯函数**（无 Spring、无库、无外部依赖），
 * 却是密钥出境的唯一闸口。脱敏漏一个 provider，就是把该家的 accessKey/secretKey
 * 明文发给前端 —— 而"漏"的形态极其安静：编译照过、其余测试全绿、只在真实响应里露出来。
 *
 * <p>因此这里除了逐 provider 断言，还有一条**清单钉子**（{@link #providerFieldListIsPinned}）：
 * 用反射把 {@code StorageEngineConfig} 上的 provider 字段集钉死，将来新增 provider
 * 会让本测试变红，逼你同时补上打码与合并两条分支。
 */
class TenantConfigRedactionTest {

    private static final String REDACTED = TenantConfigRedaction.REDACTED;
    private static final String SECRET = "sk-live-REAL-SECRET";

    // ── preserveIfRedacted：三态 ─────────────────────────────────────────────

    @Test
    @DisplayName("preserveIfRedacted：null / 空串 / 占位符都保留旧值，真值才覆盖")
    void preserveIfRedactedKeepsExistingOnBlankOrPlaceholder() {
        assertThat(TenantConfigRedaction.preserveIfRedacted(null, "old")).isEqualTo("old");
        assertThat(TenantConfigRedaction.preserveIfRedacted("", "old")).isEqualTo("old");
        assertThat(TenantConfigRedaction.preserveIfRedacted(REDACTED, "old")).isEqualTo("old");
        assertThat(TenantConfigRedaction.preserveIfRedacted("new", "old")).isEqualTo("new");
        // 旧值为 null 时给出空串而非 null（调用方据此直接写库/写响应）
        assertThat(TenantConfigRedaction.preserveIfRedacted("", null)).isEmpty();
        assertThat(TenantConfigRedaction.preserveIfRedacted(null, null)).isEmpty();
    }

    // ── 存储引擎：响应面打码 ─────────────────────────────────────────────────

    @Test
    @DisplayName("响应面：7 个云 provider 的密钥全部打码（漏一个即泄露）")
    void storageEngineForResponseRedactsEveryCloudProvider() {
        StorageEngineConfig cfg = allCloudProvidersWithSecrets();

        TenantConfigRedaction.storageEngineForResponse(cfg);

        assertThat(cfg.getMinio().getAccessKeyId()).isEqualTo(REDACTED);
        assertThat(cfg.getMinio().getSecretAccessKey()).isEqualTo(REDACTED);
        assertThat(cfg.getCos().getSecretId()).isEqualTo(REDACTED);
        assertThat(cfg.getCos().getSecretKey()).isEqualTo(REDACTED);
        assertThat(cfg.getTos().getAccessKey()).isEqualTo(REDACTED);
        assertThat(cfg.getTos().getSecretKey()).isEqualTo(REDACTED);
        assertThat(cfg.getS3().getAccessKey()).isEqualTo(REDACTED);
        assertThat(cfg.getS3().getSecretKey()).isEqualTo(REDACTED);
        assertThat(cfg.getOss().getAccessKey()).isEqualTo(REDACTED);
        assertThat(cfg.getOss().getSecretKey()).isEqualTo(REDACTED);
        assertThat(cfg.getKs3().getAccessKey()).isEqualTo(REDACTED);
        assertThat(cfg.getKs3().getSecretKey()).isEqualTo(REDACTED);
        assertThat(cfg.getObs().getAccessKey()).isEqualTo(REDACTED);
        assertThat(cfg.getObs().getSecretKey()).isEqualTo(REDACTED);
    }

    @Test
    @DisplayName("响应面：密钥为空时不打码（空值不是秘密，打码反而误导前端以为配过）")
    void storageEngineForResponseLeavesBlankSecretsBlank() {
        StorageEngineConfig cfg = new StorageEngineConfig();
        cfg.setMinio(new MinioEngineConfig());

        TenantConfigRedaction.storageEngineForResponse(cfg);

        assertThat(cfg.getMinio().getAccessKeyId()).isEmpty();
        assertThat(cfg.getMinio().getSecretAccessKey()).isEmpty();
    }

    @Test
    @DisplayName("响应面：入参 null → null（调用方在信封层输出 data:null）")
    void responseRedactionIsNullSafe() {
        assertThat(TenantConfigRedaction.storageEngineForResponse(null)).isNull();
        assertThat(TenantConfigRedaction.parserEngineForResponse(null)).isNull();
        assertThat(TenantConfigRedaction.webSearchForResponse(null)).isNull();
    }

    /**
     * 清单钉子：provider 字段集与打码/合并两条分支必须同步增长。
     *
     * <p>{@code local} 也在名单里（它没有密钥，但同样是本配置的一个 provider 分支）；
     * 新增任何 {@code *EngineConfig} 字段都会让本断言红。
     */
    @Test
    @DisplayName("防回归：provider 字段清单被钉死（新增 provider 必红 ⇒ 补打码与合并）")
    void providerFieldListIsPinned() {
        Set<String> actual = Arrays.stream(StorageEngineConfig.class.getDeclaredFields())
                .filter(f -> f.getType().getSimpleName().endsWith("EngineConfig"))
                .map(Field::getName)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(actual).containsExactly("cos", "ks3", "local", "minio", "obs", "oss", "s3", "tos");
    }

    // ── 存储引擎：更新面合并 ─────────────────────────────────────────────────

    @Test
    @DisplayName("合并：S3 空串 = 切 AWS 默认凭据链（**保留空**，不清旧值）——与其余 provider 语义相反")
    void mergeStorageEngineS3BlankMeansIamChain() {
        StorageEngineConfig existing = new StorageEngineConfig();
        S3EngineConfig prev = new S3EngineConfig();
        prev.setAccessKey("old-ak");
        prev.setSecretKey("old-sk");
        existing.setS3(prev);

        S3EngineConfig incomingS3 = new S3EngineConfig();
        incomingS3.setAccessKey("");
        incomingS3.setSecretKey("");
        StorageEngineConfig incoming = new StorageEngineConfig();
        incoming.setS3(incomingS3);

        TenantConfigRedaction.mergeStorageEngine(incoming, existing);

        assertThat(incoming.getS3().getAccessKey()).isEmpty();
        assertThat(incoming.getS3().getSecretKey()).isEmpty();
    }

    @Test
    @DisplayName("合并：S3 只认占位符回退；其余 provider 空串也回退（同一动作两种语义）")
    void mergeStorageEnginePlaceholderRestoresOldForS3WhileBlankRestoresForOthers() {
        StorageEngineConfig existing = new StorageEngineConfig();
        S3EngineConfig prevS3 = new S3EngineConfig();
        prevS3.setAccessKey("old-s3-ak");
        prevS3.setSecretKey("old-s3-sk");
        existing.setS3(prevS3);
        OssEngineConfig prevOss = new OssEngineConfig();
        prevOss.setAccessKey("old-oss-ak");
        prevOss.setSecretKey("old-oss-sk");
        existing.setOss(prevOss);

        S3EngineConfig inS3 = new S3EngineConfig();
        inS3.setAccessKey(REDACTED);
        inS3.setSecretKey(REDACTED);
        OssEngineConfig inOss = new OssEngineConfig();
        inOss.setAccessKey("");
        inOss.setSecretKey("");
        StorageEngineConfig incoming = new StorageEngineConfig();
        incoming.setS3(inS3);
        incoming.setOss(inOss);

        TenantConfigRedaction.mergeStorageEngine(incoming, existing);

        assertThat(incoming.getS3().getAccessKey()).isEqualTo("old-s3-ak");
        assertThat(incoming.getS3().getSecretKey()).isEqualTo("old-s3-sk");
        assertThat(incoming.getOss().getAccessKey()).isEqualTo("old-oss-ak");
        assertThat(incoming.getOss().getSecretKey()).isEqualTo("old-oss-sk");
    }

    @Test
    @DisplayName("合并：真值覆盖旧值；无存量（existing=null）时空串得空串")
    void mergeStorageEngineRealValueOverwritesAndNullExistingIsSafe() {
        MinioEngineConfig inMinio = new MinioEngineConfig();
        inMinio.setAccessKeyId("new-ak");
        inMinio.setSecretAccessKey(REDACTED);
        StorageEngineConfig incoming = new StorageEngineConfig();
        incoming.setMinio(inMinio);

        TenantConfigRedaction.mergeStorageEngine(incoming, null);

        assertThat(incoming.getMinio().getAccessKeyId()).isEqualTo("new-ak");
        assertThat(incoming.getMinio().getSecretAccessKey()).isEmpty();
    }

    // ── WebSearch ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("WebSearch 响应面：proxy_url 打码，并做 Effective 归一化")
    void webSearchForResponseRedactsProxy() {
        WebSearchConfig cfg = new WebSearchConfig();
        cfg.setProxyUrl("http://proxy.internal:8080");

        WebSearchConfig out = TenantConfigRedaction.webSearchForResponse(cfg);

        assertThat(out.getProxyUrl()).isEqualTo(REDACTED);
        // Effective 归一化随之生效（响应面不吐"未配置"的原始零值）
        assertThat(out.getMaxResults()).isPositive();
        assertThat(out.getCompressionMethod()).isNotEmpty();
        assertThat(out.getBlacklist()).isNotNull();
    }

    @Test
    @DisplayName("WebSearch 响应面：proxy_url 为空白时不打码（没配就不是秘密）")
    void webSearchForResponseKeepsBlankProxyBlank() {
        WebSearchConfig cfg = new WebSearchConfig();
        cfg.setProxyUrl("   ");

        assertThat(TenantConfigRedaction.webSearchForResponse(cfg).getProxyUrl()).isBlank();
    }

    @Test
    @DisplayName("WebSearch 更新面：空串保留旧 proxy，真值覆盖")
    void mergeWebSearchPreservesProxy() {
        WebSearchConfig existing = new WebSearchConfig();
        existing.setProxyUrl("http://old.proxy");

        WebSearchConfig incoming = new WebSearchConfig();
        incoming.setProxyUrl("");
        WebSearchConfig merged = TenantConfigRedaction.mergeWebSearch(incoming, existing);

        assertThat(merged.getProxyUrl()).isEqualTo("http://old.proxy");
    }

    // ── ParserEngine ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("ParserEngine 响应面：两个密钥非空才打码")
    void parserEngineForResponseRedactsBothTokens() {
        ParserEngineConfig cfg = new ParserEngineConfig();
        cfg.setMineruApiKey(SECRET);
        cfg.setPaddleOcrVlCloudToken(SECRET);

        TenantConfigRedaction.parserEngineForResponse(cfg);

        assertThat(cfg.getMineruApiKey()).isEqualTo(REDACTED);
        assertThat(cfg.getPaddleOcrVlCloudToken()).isEqualTo(REDACTED);
    }

    @Test
    @DisplayName("ParserEngine 更新面：密钥 preserve + 未携带规则时保留存量 legacy 规则")
    void mergeParserEnginePreservesTokensAndLegacyRules() {
        ParserEngineConfig existing = new ParserEngineConfig();
        existing.setMineruApiKey("old-mineru");
        existing.setPaddleOcrVlCloudToken("old-paddle");
        List<Object> inheritedRules =
                List.of((Object) new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode());
        existing.setChatParserEngineRules(inheritedRules);

        ParserEngineConfig incoming = new ParserEngineConfig();
        incoming.setMineruApiKey(REDACTED);
        incoming.setPaddleOcrVlCloudToken("");

        ParserEngineConfig merged = TenantConfigRedaction.mergeParserEngine(incoming, existing);

        assertThat(merged.getMineruApiKey()).isEqualTo("old-mineru");
        assertThat(merged.getPaddleOcrVlCloudToken()).isEqualTo("old-paddle");
        assertThat(merged.getChatParserEngineRules()).isSameAs(inheritedRules);
    }

    // ── 夹具 ─────────────────────────────────────────────────────────────────

    /** 7 个云 provider 各填一份"真实"密钥 —— 打码测试的输入。 */
    private static StorageEngineConfig allCloudProvidersWithSecrets() {
        StorageEngineConfig cfg = new StorageEngineConfig();

        MinioEngineConfig minio = new MinioEngineConfig();
        minio.setAccessKeyId(SECRET);
        minio.setSecretAccessKey(SECRET);
        cfg.setMinio(minio);

        CosEngineConfig cos = new CosEngineConfig();
        cos.setSecretId(SECRET);
        cos.setSecretKey(SECRET);
        cfg.setCos(cos);

        TosEngineConfig tos = new TosEngineConfig();
        tos.setAccessKey(SECRET);
        tos.setSecretKey(SECRET);
        cfg.setTos(tos);

        S3EngineConfig s3 = new S3EngineConfig();
        s3.setAccessKey(SECRET);
        s3.setSecretKey(SECRET);
        cfg.setS3(s3);

        OssEngineConfig oss = new OssEngineConfig();
        oss.setAccessKey(SECRET);
        oss.setSecretKey(SECRET);
        cfg.setOss(oss);

        Ks3EngineConfig ks3 = new Ks3EngineConfig();
        ks3.setAccessKey(SECRET);
        ks3.setSecretKey(SECRET);
        cfg.setKs3(ks3);

        ObsEngineConfig obs = new ObsEngineConfig();
        obs.setAccessKey(SECRET);
        obs.setSecretKey(SECRET);
        cfg.setObs(obs);

        return cfg;
    }
}
