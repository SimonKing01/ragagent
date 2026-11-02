package com.ragagent.storage.provider;

import com.ragagent.contract.error.AppError;
import com.ragagent.contract.error.BizException;
import com.ragagent.contract.storage.StorageEnvLookup;
import com.ragagent.contract.tenant.StorageEngineConfig;
import com.ragagent.platform.security.SsrfGuard;
import com.ragagent.platform.storage.fileserve.StoragePaths;
import com.ragagent.storage.dto.StorageConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 存储 provider 的单一登记点：必填规则、端点/SSRF 策略、连通性形态、凭据字段名、
 * 旧 KV 段映射与运行时创建。
 *
 * <p>各 provider 的差异（minio 的 docker 模式、cos 的探测域名、S3 兼容族的默认
 * pathPrefix）逐项保留在各自条目里；调用方只按 provider 查表，不再维护 switch。
 * 展示白名单仍由 {@code platform.storage.config.StorageAllowList} 持有（platform 不能
 * 反向依赖业务域），两处顺序需一致。</p>
 */
public final class StorageProviders {

    public static final String ENV_EXTERNAL_URL = "APP_EXTERNAL_URL";
    public static final String DEFAULT_PATH_PREFIX = "weknora/";

    private static final List<String> SCHEME_KEYS = List.of(
            "local", "minio", "cos", "tos", "s3", "oss", "ks3", "obs", "dummy");
    private static final List<String> EXPOSED_KEYS = List.of(
            "local", "minio", "cos", "tos", "s3", "oss", "ks3", "obs");
    private static final List<String> LEGACY_CANDIDATES = List.of(
            "minio", "cos", "tos", "s3", "oss", "ks3", "obs");

    /** 凭据两键的 provider 族命名（行配置 → 各厂商段字段名）。 */
    public record CredentialFields(String accessKey, String secretKey) {
    }

    /** 端点策略：跳过 SSRF 校验，或补齐 scheme 后校验。 */
    public record EndpointPolicy(boolean skipSsrf, String scheme) {
    }

    /** 连通性测试的运行环境（本机基目录 + provider + 拨号器）。 */
    public record TestEnv(String localStorageBaseDir, String provider, Dialer dialer) {
    }

    /** 远端 provider 的真实拨号（由 service 提供，便于单测注入）。 */
    @FunctionalInterface
    public interface Dialer {
        void dial(String endpoint, int fallbackPort, String provider);
    }

    /** 从租户级存储配置造运行时 FileService。 */
    @FunctionalInterface
    public interface Creator {
        FileService create(StorageEngineConfig sec, String localBaseDir, SsrfGuard ssrfGuard);
    }

    /** 连通性测试（抛 {@link ConnectorFailure} 由 service 统一清洗文案）。 */
    @FunctionalInterface
    public interface Connectivity {
        void test(StorageConfig config, TestEnv env);
    }

    /**
     * 一个 provider 的全部规则。
     *
     * @param exposed 是否出现在 /types 展示面（dummy 仅供路径解析）
     */
    public record Descriptor(
            String key,
            boolean exposed,
            CredentialFields credentials,
            Consumer<StorageConfig> validate,
            Function<StorageConfig, EndpointPolicy> endpointPolicy,
            Connectivity connectivity,
            Function<StorageEngineConfig, Object> legacySection,
            Creator creator) {
    }

    /** 远端探测的失败载体（走 SanitizeStorageConnectivityError 映射）。 */
    public static class ConnectorFailure extends RuntimeException {
        public ConnectorFailure(String message) {
            super(message);
        }

        public ConnectorFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final Map<String, Descriptor> BY_KEY = build();

    private static final Descriptor GENERIC = new Descriptor(
            "",
            false,
            new CredentialFields("accessKey", "secretKey"),
            StorageProviders::validateGeneric,
            c -> new EndpointPolicy(empty(c.endpoint), "https://"),
            (c, env) -> {
                throw new ConnectorFailure("unsupported storage provider: " + env.provider());
            },
            sec -> null,
            (sec, base, ssrf) -> {
                throw new IllegalArgumentException("unsupported provider");
            });

    private StorageProviders() {
    }

    public static Descriptor find(String provider) {
        String p = provider == null ? "" : provider.trim().toLowerCase(java.util.Locale.ROOT);
        return BY_KEY.get(p);
    }

    /** 未登记 provider 抛与旧工厂一致的文案。 */
    public static Descriptor require(String provider) {
        Descriptor d = find(provider);
        if (d == null) {
            String p = provider == null ? "" : provider.trim().toLowerCase(java.util.Locale.ROOT);
            throw new IllegalArgumentException("unsupported provider \"" + p + "\"");
        }
        return d;
    }

    /** 校验/端点/探测面：未登记 provider 走 generic（与旧 switch 的 default 一致）。 */
    public static Descriptor forValidation(String provider) {
        Descriptor d = find(provider);
        return d == null ? GENERIC : d;
    }

    public static CredentialFields credentials(String provider) {
        Descriptor d = find(provider);
        return d == null ? GENERIC.credentials() : d.credentials();
    }

    public static Object legacySection(String provider, StorageEngineConfig config) {
        Descriptor d = find(provider);
        return d == null ? null : d.legacySection().apply(config);
    }

    public static List<String> schemeKeys() {
        return SCHEME_KEYS;
    }

    public static List<String> exposedKeys() {
        return EXPOSED_KEYS;
    }

    public static List<String> legacyCandidates() {
        return LEGACY_CANDIDATES;
    }

    // ── 注册表 ──────────────────────────────────────────────────────────────

    private static Map<String, Descriptor> build() {
        Map<String, Descriptor> m = new LinkedHashMap<>();
        m.put("local", new Descriptor(
                "local", true, new CredentialFields("accessKey", "secretKey"),
                c -> {
                },
                c -> new EndpointPolicy(true, ""),
                StorageProviders::testLocal,
                sec -> sec == null ? null : sec.getLocal(),
                StorageProviders::createLocal));
        m.put("minio", new Descriptor(
                "minio", true, new CredentialFields("accessKeyId", "secretAccessKey"),
                StorageProviders::validateMinio,
                c -> new EndpointPolicy("docker".equals(trim(c.mode)), c.useSsl ? "https://" : "http://"),
                StorageProviders::testMinio,
                sec -> sec == null ? null : sec.getMinio(),
                StorageProviders::createMinio));
        m.put("cos", new Descriptor(
                "cos", true, new CredentialFields("secretId", "secretKey"),
                c -> {
                    required("region", c.region);
                    required("access_key_id", c.accessKeyId);
                    required("secret_access_key", c.secretAccessKey);
                    required("bucket_name", c.bucketName);
                },
                c -> new EndpointPolicy(true, ""),
                StorageProviders::testCos,
                sec -> sec == null ? null : sec.getCos(),
                StorageProviders::createCos));
        m.put("tos", genericS3("tos", StorageProviders::createTos));
        m.put("s3", genericS3("s3", StorageProviders::createS3));
        m.put("oss", genericS3("oss", StorageProviders::createOss));
        m.put("ks3", genericS3("ks3", StorageProviders::createKs3));
        m.put("obs", genericS3("obs", StorageProviders::createObs));
        m.put("dummy", new Descriptor(
                "dummy", false, new CredentialFields("accessKey", "secretKey"),
                c -> {
                },
                c -> new EndpointPolicy(true, ""),
                (c, env) -> {
                },
                sec -> null,
                (sec, base, ssrf) -> {
                    throw new IllegalArgumentException("unsupported provider \"dummy\"");
                }));
        return Map.copyOf(m);
    }

    private static Descriptor genericS3(String key, Creator creator) {
        return new Descriptor(
                key, true, new CredentialFields("accessKey", "secretKey"),
                StorageProviders::validateGeneric,
                c -> new EndpointPolicy(empty(c.endpoint), "https://"),
                StorageProviders::testRemote,
                sec -> legacyS3Section(sec, key),
                creator);
    }

    // ── 校验 ────────────────────────────────────────────────────────────────

    private static void validateGeneric(StorageConfig c) {
        required("endpoint", c.endpoint);
        required("region", c.region);
        required("access_key_id", c.accessKeyId);
        required("secret_access_key", c.secretAccessKey);
        required("bucket_name", c.bucketName);
    }

    private static void validateMinio(StorageConfig c) {
        if (empty(c.mode)) {
            c.mode = "remote";
        }
        if (!"docker".equals(c.mode)) {
            required("endpoint", c.endpoint);
            required("access_key_id", c.accessKeyId);
            required("secret_access_key", c.secretAccessKey);
        }
        required("bucket_name", c.bucketName);
    }

    private static void required(String name, String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new BizException(AppError.validation(name + " is required"));
        }
    }

    // ── 连通性 ──────────────────────────────────────────────────────────────

    private static void testLocal(StorageConfig c, TestEnv env) {
        String baseDir = env.localStorageBaseDir() == null || env.localStorageBaseDir().trim().isEmpty()
                ? "/data/files"
                : env.localStorageBaseDir().trim();
        Path base = Path.of(baseDir).toAbsolutePath().normalize();
        Path safe = safeJoinUnderBase(base, c.pathPrefix);
        try {
            Files.createDirectories(safe);
        } catch (IOException e) {
            throw new ConnectorFailure("create local storage directory: " + e.getMessage(), e);
        }
        if (!Files.isDirectory(base)) {
            throw new ConnectorFailure("storage directory not accessible: " + base);
        }
    }

    private static void testMinio(StorageConfig c, TestEnv env) {
        String endpoint = c.endpoint;
        if ("docker".equals(c.mode)) {
            endpoint = envOr("MINIO_ENDPOINT", "");
            c.accessKeyId = envOr("MINIO_ACCESS_KEY_ID", "");
            c.secretAccessKey = envOr("MINIO_SECRET_ACCESS_KEY", "");
            if (empty(c.bucketName)) {
                c.bucketName = envOr("MINIO_BUCKET_NAME", "");
            }
        }
        env.dialer().dial(endpoint, c.useSsl ? 443 : 80, "minio");
    }

    private static void testCos(StorageConfig c, TestEnv env) {
        String region = trim(c.region);
        String bucket = trim(c.bucketName);
        if (region.isEmpty() || bucket.isEmpty()) {
            throw new ConnectorFailure("dial cos: region and bucket are required");
        }
        env.dialer().dial(bucket + ".cos." + region + ".myqcloud.com", 443, "cos");
    }

    private static void testRemote(StorageConfig c, TestEnv env) {
        env.dialer().dial(c.endpoint, 443, env.provider());
    }

    // ── 旧 KV 段与运行时创建 ────────────────────────────────────────────────

    private static Object legacyS3Section(StorageEngineConfig sec, String key) {
        if (sec == null) {
            return null;
        }
        return switch (key) {
            case "s3" -> sec.getS3();
            case "oss" -> sec.getOss();
            case "ks3" -> sec.getKs3();
            case "obs" -> sec.getObs();
            case "tos" -> sec.getTos();
            default -> null;
        };
    }

    private static FileService createLocal(StorageEngineConfig sec, String localBaseDir, SsrfGuard ssrf) {
        String base = localBaseDir == null || localBaseDir.trim().isEmpty()
                ? StoragePaths.localStorageBaseDir()
                : localBaseDir.trim();
        String dir = base;
        if (sec != null && sec.getLocal() != null
                && sec.getLocal().getPathPrefix() != null
                && !sec.getLocal().getPathPrefix().trim().isEmpty()) {
            String joined = safeJoinUnderBase(base, sec.getLocal().getPathPrefix().trim());
            if (joined != null) {
                dir = joined;
            }
        }
        return new LocalFileService(dir, envOr(ENV_EXTERNAL_URL, ""));
    }

    private static FileService createS3(StorageEngineConfig sec, String localBaseDir, SsrfGuard ssrf) {
        StorageEngineConfig.S3EngineConfig c = sec == null ? null : sec.getS3();
        if (c == null || trim(c.getRegion()).isEmpty() || trim(c.getBucketName()).isEmpty()
                || trim(c.getAccessKey()).isEmpty() != trim(c.getSecretKey()).isEmpty()) {
            throw new IllegalArgumentException("incomplete s3 config");
        }
        String prefix = trim(c.getPathPrefix()).isEmpty()
                ? DEFAULT_PATH_PREFIX : trim(c.getPathPrefix());
        return new S3CompatibleFileService(new S3CompatibleFileService.Config(
                "s3", trim(c.getEndpoint()), trim(c.getAccessKey()), trim(c.getSecretKey()),
                trim(c.getBucketName()), trim(c.getRegion()), prefix,
                c.isForcePathStyle()), ssrf);
    }

    private static FileService createMinio(StorageEngineConfig sec, String localBaseDir, SsrfGuard ssrf) {
        StorageEngineConfig.MinioEngineConfig c = sec == null ? null : sec.getMinio();
        if (c == null) {
            throw new IllegalArgumentException("incomplete minio config");
        }
        boolean remote = "remote".equalsIgnoreCase(trim(c.getMode()));
        String endpoint = remote ? trim(c.getEndpoint()) : envOr("MINIO_ENDPOINT", "");
        String accessKey = remote ? trim(c.getAccessKeyId()) : envOr("MINIO_ACCESS_KEY_ID", "");
        String secretKey = remote ? trim(c.getSecretAccessKey()) : envOr("MINIO_SECRET_ACCESS_KEY", "");
        String bucket = trim(c.getBucketName()).isEmpty()
                ? envOr("MINIO_BUCKET_NAME", "") : trim(c.getBucketName());
        if (endpoint.isEmpty() || accessKey.isEmpty() || secretKey.isEmpty() || bucket.isEmpty()) {
            throw new IllegalArgumentException("incomplete minio config");
        }
        String url = endpoint.contains("://")
                ? endpoint : (c.isUseSsl() ? "https://" : "http://") + endpoint;
        return new S3CompatibleFileService(new S3CompatibleFileService.Config(
                "minio", url, accessKey, secretKey, bucket, "us-east-1", "", true), ssrf);
    }

    private static FileService createObs(StorageEngineConfig sec, String localBaseDir, SsrfGuard ssrf) {
        StorageEngineConfig.ObsEngineConfig c = sec == null ? null : sec.getObs();
        String endpoint = firstNonEmpty(c == null ? "" : trim(c.getEndpoint()),
                envOr("OBS_ENDPOINT", ""));
        String region = firstNonEmpty(c == null ? "" : trim(c.getRegion()), envOr("OBS_REGION", ""));
        String accessKey = firstNonEmpty(c == null ? "" : trim(c.getAccessKey()),
                envOr("OBS_ACCESS_KEY", ""));
        String secretKey = firstNonEmpty(c == null ? "" : trim(c.getSecretKey()),
                envOr("OBS_SECRET_KEY", ""));
        String bucket = firstNonEmpty(c == null ? "" : trim(c.getBucketName()),
                envOr("OBS_BUCKET_NAME", ""));
        String prefix = firstNonEmpty(c == null ? "" : trim(c.getPathPrefix()),
                envOr("OBS_PATH_PREFIX", ""));
        if (prefix.isEmpty()) {
            prefix = DEFAULT_PATH_PREFIX;
        }
        if (endpoint.isEmpty() || region.isEmpty() || accessKey.isEmpty()
                || secretKey.isEmpty() || bucket.isEmpty()) {
            throw new IllegalArgumentException("incomplete obs config");
        }
        return new S3CompatibleFileService(new S3CompatibleFileService.Config(
                "obs", endpoint, accessKey, secretKey, bucket, region, prefix, true), ssrf);
    }

    private static FileService createKs3(StorageEngineConfig sec, String localBaseDir, SsrfGuard ssrf) {
        StorageEngineConfig.Ks3EngineConfig c = sec == null ? null : sec.getKs3();
        if (c == null || trim(c.getEndpoint()).isEmpty() || trim(c.getRegion()).isEmpty()
                || trim(c.getAccessKey()).isEmpty() || trim(c.getSecretKey()).isEmpty()
                || trim(c.getBucketName()).isEmpty()) {
            throw new IllegalArgumentException("incomplete ks3 config");
        }
        String prefix = trim(c.getPathPrefix()).isEmpty()
                ? DEFAULT_PATH_PREFIX : trim(c.getPathPrefix());
        return new S3CompatibleFileService(new S3CompatibleFileService.Config(
                "ks3", trim(c.getEndpoint()), trim(c.getAccessKey()), trim(c.getSecretKey()),
                trim(c.getBucketName()), trim(c.getRegion()), prefix, true), ssrf);
    }

    private static FileService createOss(StorageEngineConfig sec, String localBaseDir, SsrfGuard ssrf) {
        StorageEngineConfig.OssEngineConfig c = sec == null ? null : sec.getOss();
        if (c == null || trim(c.getEndpoint()).isEmpty() || trim(c.getRegion()).isEmpty()
                || trim(c.getAccessKey()).isEmpty() || trim(c.getSecretKey()).isEmpty()
                || trim(c.getBucketName()).isEmpty()) {
            throw new IllegalArgumentException("incomplete oss config");
        }
        String prefix = trim(c.getPathPrefix()).isEmpty()
                ? DEFAULT_PATH_PREFIX : trim(c.getPathPrefix());
        String tempBucket = c.isUseTempBucket() ? trim(c.getTempBucketName()) : "";
        return new OssFileService(trim(c.getEndpoint()), trim(c.getRegion()),
                trim(c.getAccessKey()), trim(c.getSecretKey()), trim(c.getBucketName()),
                prefix, tempBucket, trim(c.getTempRegion()), ssrf);
    }

    private static FileService createCos(StorageEngineConfig sec, String localBaseDir, SsrfGuard ssrf) {
        StorageEngineConfig.CosEngineConfig c = sec == null ? null : sec.getCos();
        if (c == null || trim(c.getSecretId()).isEmpty() || trim(c.getSecretKey()).isEmpty()
                || trim(c.getBucketName()).isEmpty() || trim(c.getRegion()).isEmpty()) {
            throw new IllegalArgumentException("incomplete cos config");
        }
        String prefix = trim(c.getPathPrefix()).isEmpty() ? "weknora" : trim(c.getPathPrefix());
        return new CosFileService(trim(c.getBucketName()), trim(c.getRegion()),
                trim(c.getSecretId()), trim(c.getSecretKey()), prefix,
                trim(c.getTempBucketName()), trim(c.getTempRegion()));
    }

    private static FileService createTos(StorageEngineConfig sec, String localBaseDir, SsrfGuard ssrf) {
        StorageEngineConfig.TosEngineConfig c = sec == null ? null : sec.getTos();
        if (c == null || trim(c.getEndpoint()).isEmpty() || trim(c.getRegion()).isEmpty()
                || trim(c.getAccessKey()).isEmpty() || trim(c.getSecretKey()).isEmpty()
                || trim(c.getBucketName()).isEmpty()) {
            throw new IllegalArgumentException("incomplete tos config");
        }
        return new TosFileService(trim(c.getEndpoint()), trim(c.getRegion()),
                trim(c.getAccessKey()), trim(c.getSecretKey()), trim(c.getBucketName()),
                c.getPathPrefix(), trim(c.getTempBucketName()), trim(c.getTempRegion()), ssrf);
    }

    // ── 小工具 ──────────────────────────────────────────────────────────────

    static String safeJoinUnderBase(String base, String prefix) {
        try {
            Path b = Paths.get(base).toAbsolutePath().normalize();
            Path joined = b.resolve(prefix).normalize();
            return joined.startsWith(b) ? joined.toString() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Path safeJoinUnderBase(Path base, String prefix) {
        Path result = base;
        if (prefix != null && !prefix.trim().isEmpty()) {
            Path resolved = base.resolve(prefix.trim()).normalize();
            if (!resolved.startsWith(base.toAbsolutePath().normalize())) {
                throw new ConnectorFailure("path_prefix escapes the storage base directory");
            }
            result = resolved;
        }
        return result;
    }

    private static boolean empty(String s) {
        return s == null || s.isEmpty();
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String firstNonEmpty(String a, String b) {
        return a == null || a.isEmpty() ? (b == null ? "" : b) : a;
    }

    private static String envOr(String name, String fallback) {
        String v = StorageEnvLookup.get(name);
        return v == null || v.trim().isEmpty() ? fallback : v.trim();
    }
}
