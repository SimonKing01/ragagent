package com.ragagent.storage.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.contract.storage.StorageBackendPort;
import com.ragagent.contract.storage.StorageBackendView;
import com.ragagent.contract.storage.StorageConnectivityPort;
import com.ragagent.contract.tenant.TenantConfigLookup;
import com.ragagent.contract.tenant.TenantConfigLookup.TenantStorageView;
import com.ragagent.platform.security.SsrfGuard;
import com.ragagent.platform.storage.config.StorageAllowList;
import com.ragagent.platform.web.JsonMappers;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * 存储引擎的可用性判定与连通性探测。
 *
 * <p>7 个厂商（minio / cos / tos / s3 / oss / ks3 / obs）的必填校验、字段格式、
 * SSRF 拦截与错误文案分派都在这里——这属于存储域知识，不该由 system 的信息面持有。</p>
 *
 * <p><b>失败文案是实现边界</b>：各 SDK 的错误串不完全一致，403 / 404 的分派只能按子串
 * 近似；金片只覆盖缺配置 / SSRF / 禁用 provider 等确定性分支。</p>
 */
@Service
public class StorageConnectivityService implements StorageConnectivityPort {

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    /** 厂商配置里的 region/bucket 名称格式（同一正则）。 */
    private static final Pattern FIELD_PATTERN = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,62}$");

    /** 展示序：契约要求与 /storage-backends/types 一致。 */
    private static final List<String> DISPLAY_ORDER =
            List.of("local", "minio", "cos", "tos", "s3", "oss", "ks3", "obs");

    private static final Map<String, String> DESCRIPTIONS = Map.of(
            "local", "本地文件系统存储，仅适合单机部署",
            "minio", "S3 兼容的自托管对象存储，适合内网和私有云部署",
            "cos", "腾讯云对象存储服务，适合公有云部署，支持 CDN 加速",
            "tos", "火山引擎对象存储服务，适合公有云部署",
            "s3", "AWS S3 与兼容对象存储服务，适合公有云与混合云部署",
            "oss", "阿里云对象存储服务，适合公有云部署，支持 S3 兼容协议",
            "ks3", "金山云对象存储服务，适合公有云部署",
            "obs", "华为云对象存储服务，适合公有云部署");

    private final StorageAllowList allowList;
    private final TenantConfigLookup tenantConfigLookup;
    private final StorageBackendPort storageBackendPort;
    private final SsrfGuard ssrfGuard;
    private final Environment environment;

    public StorageConnectivityService(StorageAllowList allowList,
            TenantConfigLookup tenantConfigLookup,
            StorageBackendPort storageBackendPort,
            SsrfGuard ssrfGuard,
            Environment environment) {
        this.allowList = allowList;
        this.tenantConfigLookup = tenantConfigLookup;
        this.storageBackendPort = storageBackendPort;
        this.ssrfGuard = ssrfGuard;
        this.environment = environment;
    }

    // ── 状态合成 ─────────────────────────────────────────────────────────

    /** 按展示序合成状态页。 */
    @Override
    public Status status(long tenantId) {
        Map<String, Boolean> activeBackend = activeBackendProviders(tenantId);
        var allowed = allowList.allowedMap();
        boolean minioEnvAvailable = isMinioEnvAvailable();
        Map<String, Boolean> configured = new LinkedHashMap<>();
        configured.put("minio", isMinioConfigured(tenantId) || truthy(activeBackend.get("minio")));
        configured.put("cos", isCosConfigured(tenantId) || truthy(activeBackend.get("cos")));
        configured.put("tos", isTosConfigured(tenantId) || truthy(activeBackend.get("tos")));
        configured.put("s3", isS3Configured(tenantId) || truthy(activeBackend.get("s3")));
        configured.put("oss", isOssConfigured(tenantId) || truthy(activeBackend.get("oss")));
        configured.put("ks3", isKs3Configured(tenantId) || truthy(activeBackend.get("ks3")));
        configured.put("obs", isObsConfigured(tenantId) || truthy(activeBackend.get("obs")));

        List<EngineStatus> engines = new ArrayList<>(DISPLAY_ORDER.size());
        for (String provider : DISPLAY_ORDER) {
            boolean isConfigured = "local".equals(provider)
                    ? true
                    : "minio".equals(provider)
                            ? configured.getOrDefault("minio", false) || minioEnvAvailable
                            : configured.getOrDefault(provider, false);
            engines.add(new EngineStatus(provider, allowed.contains(provider), isConfigured,
                    DESCRIPTIONS.get(provider)));
        }

        List<String> allowedProviders = new ArrayList<>();
        for (String provider : allowList.supported()) {
            if (allowed.contains(provider)) {
                allowedProviders.add(provider);
            }
        }
        return new Status(engines, allowedProviders, minioEnvAvailable);
    }

    /** 部署级 MinIO env 是否齐备（MINIO_ENDPOINT / ACCESS_KEY / SECRET_KEY）。 */
    public boolean isMinioEnvAvailable() {
        return env("MINIO_ENDPOINT") && env("MINIO_ACCESS_KEY_ID") && env("MINIO_SECRET_ACCESS_KEY");
    }

    @Override
    public boolean isAllowed(String provider) {
        return allowList.isAllowed(provider);
    }

    // ── 连通性探测 ───────────────────────────────────────────────────────

    /**
     * 按 provider 探测。
     *
     * <p>未识别的 provider（含 {@code local}）恒为成功——本地存储无需探测。</p>
     */
    @Override
    public CheckResult check(String provider, Map<String, Object> config, long tenantId) {
        return checkNode(provider, config == null ? null : JsonMappers.toJsonNode(config), tenantId);
    }

    private CheckResult checkNode(String provider, JsonNode cfg, long tenantId) {
        return switch (provider) {
            case "minio" -> checkMinio(cfg, tenantId);
            case "cos" -> checkCos(cfg, tenantId);
            case "tos" -> checkGeneric(cfg, "TOS", tenantId,
                    "Endpoint", "Region", "Access Key", "Secret Key", "Bucket 名称");
            case "s3" -> checkS3(cfg, tenantId);
            case "oss" -> checkOss(cfg, tenantId);
            case "ks3" -> checkGeneric(cfg, "KS3", tenantId,
                    "Endpoint", "Region", "Access Key", "Secret Key", "Bucket 名称");
            case "obs" -> checkObs(cfg, tenantId);
            // default（含 local 与空 provider）：本地存储无需检测
            default -> new CheckResult(true, "本地存储无需检测");
        };
    }

    private CheckResult checkMinio(JsonNode cfg, long tenantId) {
        if (cfg == null || cfg.isNull()) {
            return new CheckResult(false, "未提供 MinIO 配置");
        }
        String bucketName = cfg.path("bucket_name").asText("");
        if (!bucketName.isEmpty() && !FIELD_PATTERN.matcher(bucketName).matches()) {
            return new CheckResult(false, "Bucket 名称格式不正确，仅允许字母、数字、点、连字符");
        }
        // mode != "remote" → env 兜底（任一缺失即拒绝）
        String endpoint = cfg.path("endpoint").asText("");
        String accessKey = cfg.path("access_key_id").asText("");
        String secretKey = cfg.path("secret_access_key").asText("");
        if (!"remote".equals(cfg.path("mode").asText(""))) {
            endpoint = orEnv(endpoint, "MINIO_ENDPOINT");
            accessKey = orEnv(accessKey, "MINIO_ACCESS_KEY_ID");
            secretKey = orEnv(secretKey, "MINIO_SECRET_ACCESS_KEY");
        }
        if (endpoint.isEmpty() || accessKey.isEmpty() || secretKey.isEmpty()) {
            return new CheckResult(false, "Endpoint、Access Key、Secret Key 不能为空");
        }
        if ("remote".equals(cfg.path("mode").asText(""))) {
            String blocked = blockedStorageEndpoint(endpoint);
            if (blocked != null) {
                return new CheckResult(false, blocked);
            }
        }
        return connectivityFallback("minio", cfg, endpoint, bucketName, tenantId);
    }

    private CheckResult checkCos(JsonNode cfg, long tenantId) {
        if (cfg == null || cfg.isNull()) {
            return new CheckResult(false, "未提供 COS 配置");
        }
        String secretId = cfg.path("secret_id").asText("");
        String secretKey = cfg.path("secret_key").asText("");
        String region = cfg.path("region").asText("");
        String bucketName = cfg.path("bucket_name").asText("");
        if (secretId.isEmpty() || secretKey.isEmpty() || region.isEmpty() || bucketName.isEmpty()) {
            return new CheckResult(false, "Secret ID、Secret Key、Region、Bucket 名称不能为空");
        }
        if (!FIELD_PATTERN.matcher(region).matches()) {
            return new CheckResult(false, "Region 格式不正确，仅允许字母、数字、点、连字符");
        }
        if (!FIELD_PATTERN.matcher(bucketName).matches()) {
            return new CheckResult(false, "Bucket 名称格式不正确，仅允许字母、数字、点、连字符");
        }
        return connectivityFallback("cos", cfg, region, bucketName, tenantId);
    }

    private CheckResult checkS3(JsonNode cfg, long tenantId) {
        if (cfg == null || cfg.isNull()) {
            return new CheckResult(false, "未提供 S3 配置");
        }
        String region = cfg.path("region").asText("");
        String bucketName = cfg.path("bucket_name").asText("");
        String accessKey = cfg.path("access_key").asText("");
        String secretKey = cfg.path("secret_key").asText("");
        if (region.isEmpty() || bucketName.isEmpty()) {
            return new CheckResult(false, "Region、Bucket 名称不能为空");
        }
        if (accessKey.isEmpty() != secretKey.isEmpty()) {
            return new CheckResult(false, "Access Key 与 Secret Key 必须同时填写或同时留空（使用 AWS 默认凭证链）");
        }
        String endpoint = cfg.path("endpoint").asText("");
        if (!endpoint.isEmpty()) {
            String blocked = blockedStorageEndpoint(endpoint);
            if (blocked != null) {
                return new CheckResult(false, blocked);
            }
        }
        return connectivityFallback("s3", cfg, endpoint, bucketName, tenantId);
    }

    private CheckResult checkOss(JsonNode cfg, long tenantId) {
        if (cfg == null || cfg.isNull()) {
            return new CheckResult(false, "未提供 OSS 配置");
        }
        String endpoint = cfg.path("endpoint").asText("");
        String accessKey = cfg.path("access_key").asText("");
        String secretKey = cfg.path("secret_key").asText("");
        String bucketName = cfg.path("bucket_name").asText("");
        if (endpoint.isEmpty() || accessKey.isEmpty() || secretKey.isEmpty() || bucketName.isEmpty()) {
            return new CheckResult(false, "Endpoint、Access Key、Secret Key、Bucket Name 不能为空");
        }
        String ssrfEndpoint = endpoint.replaceFirst("^https://", "").replaceFirst("^http://", "");
        String blocked = blockedStorageEndpoint(ssrfEndpoint);
        if (blocked != null) {
            return new CheckResult(false, blocked);
        }
        String region = cfg.path("region").asText("");
        if (!FIELD_PATTERN.matcher(region).matches()) {
            return new CheckResult(false, "Region 格式不正确，仅允许字母、数字、点、连字符");
        }
        if (!FIELD_PATTERN.matcher(bucketName).matches()) {
            return new CheckResult(false, "Bucket 名称格式不正确，仅允许字母、数字、点、连字符");
        }
        return connectivityFallback("oss", cfg, endpoint, bucketName, tenantId);
    }

    private CheckResult checkObs(JsonNode cfg, long tenantId) {
        if (cfg == null || cfg.isNull()) {
            return new CheckResult(false, "未提供 OBS 配置");
        }
        String endpoint = cfg.path("endpoint").asText("");
        String region = cfg.path("region").asText("");
        String accessKey = cfg.path("access_key").asText("");
        String secretKey = cfg.path("secret_key").asText("");
        String bucketName = cfg.path("bucket_name").asText("");
        if (endpoint.isEmpty() || region.isEmpty() || accessKey.isEmpty() || secretKey.isEmpty()
                || bucketName.isEmpty()) {
            return new CheckResult(false, "Endpoint、Region、Access Key、Secret Key、Bucket 名称不能为空");
        }
        String ssrfEndpoint = endpoint.replaceFirst("^https://", "").replaceFirst("^http://", "");
        String blocked = blockedStorageEndpoint(ssrfEndpoint);
        if (blocked != null) {
            return new CheckResult(false, blocked);
        }
        if (!FIELD_PATTERN.matcher(region).matches()) {
            return new CheckResult(false, "Region 格式不正确，仅允许字母、数字、点、连字符");
        }
        if (!FIELD_PATTERN.matcher(bucketName).matches()) {
            return new CheckResult(false, "Bucket 名称格式不正确，仅允许字母、数字、点、连字符");
        }
        return connectivityFallback("obs", cfg, endpoint, bucketName, tenantId);
    }

    /** TOS/KS3 共用（同样的必填与 SSRF 分支顺序）。 */
    private CheckResult checkGeneric(JsonNode cfg, String label, long tenantId, String... requiredFields) {
        if (cfg == null || cfg.isNull()) {
            return new CheckResult(false, "未提供 " + label + " 配置");
        }
        String endpoint = cfg.path("endpoint").asText("");
        String region = cfg.path("region").asText("");
        String accessKey = cfg.path("access_key").asText("");
        String secretKey = cfg.path("secret_key").asText("");
        String bucketName = cfg.path("bucket_name").asText("");
        if (endpoint.isEmpty() || region.isEmpty() || accessKey.isEmpty() || secretKey.isEmpty()
                || bucketName.isEmpty()) {
            return new CheckResult(false, String.join("、", requiredFields) + "不能为空");
        }
        String blocked = blockedStorageEndpoint(endpoint);
        if (blocked != null) {
            return new CheckResult(false, blocked);
        }
        return connectivityFallback(label.toLowerCase(), cfg, endpoint, bucketName, tenantId);
    }

    /**
     * 真实连通性检测：复用存储后端端口的探测（同一套 SDK 探测）。
     *
     * <p>失败文案按异常消息子串分派（403 / 404|NoSuchBucket|NotFound）；各 SDK 错误串
     * 不完全一致 → 失败文案是实现边界。</p>
     */
    private CheckResult connectivityFallback(
            String provider, JsonNode cfg, String endpoint, String bucketName, long tenantId) {
        StorageBackendView backend = new StorageBackendView();
        backend.setTenantId(tenantId);
        backend.setProvider(provider);
        ObjectNode config = MAPPER.createObjectNode();
        config.put("mode", "remote");
        config.put("endpoint", endpoint);
        config.put("region", cfg.path("region").asText(""));
        config.put("access_key_id", accessKeyOf(provider, cfg));
        config.put("secret_access_key", secretKeyOf(provider, cfg));
        config.put("bucket_name", bucketName);
        config.put("use_ssl", cfg.path("use_ssl").asBoolean(false));
        backend.setConfig(config);
        try {
            storageBackendPort.test(backend);
        } catch (RuntimeException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("403") || msg.contains("AccessDenied")) {
                return new CheckResult(false, authFailedMessage(provider));
            }
            if (msg.contains("404") || msg.contains("NoSuchBucket") || msg.contains("NotFound")) {
                return new CheckResult(false, "Bucket「" + bucketName + "」不存在，请检查名称和 Region");
            }
            return new CheckResult(false, storageBackendPort.sanitizeConnectivity(msg));
        }
        String message = "连接成功，Bucket「" + bucketName + "」已确认存在";
        if ("minio".equals(provider) && bucketName.isEmpty()) {
            message = "连接成功";
        }
        return new CheckResult(true, message);
    }

    private static String accessKeyOf(String provider, JsonNode cfg) {
        return "minio".equals(provider)
                ? cfg.path("access_key_id").asText("")
                : cfg.path("access_key").asText("");
    }

    private static String secretKeyOf(String provider, JsonNode cfg) {
        return "minio".equals(provider)
                ? cfg.path("secret_access_key").asText("")
                : cfg.path("secret_key").asText("");
    }

    private static String authFailedMessage(String provider) {
        return switch (provider) {
            case "minio" -> "认证失败，请检查 Access Key / Secret Key 是否正确";
            case "cos" -> "认证失败，请检查 Secret ID / Secret Key 是否正确";
            case "tos" -> "认证失败，请检查 Access Key / Secret Key 是否正确";
            case "s3" -> "认证失败，请检查静态密钥或 AWS IAM Role / 默认凭证链权限";
            case "oss" -> "认证失败，请检查 Access Key / Secret Key 是否正确";
            case "ks3" -> "认证失败，请检查 Access Key / Secret Key 是否正确";
            default -> "认证失败，请检查 Access Key / Secret Key 是否正确";
        };
    }

    private String blockedStorageEndpoint(String endpoint) {
        String trimmed = endpoint == null ? "" : endpoint.trim();
        if (trimmed.isEmpty()) {
            return "无效的地址";
        }
        try {
            ssrfGuard.validateURLForSSRF(trimmed);
            return null;
        } catch (RuntimeException e) {
            return ssrfGuard.formatSSRFError("存储 Endpoint", trimmed, e);
        }
    }

    // ── 租户配置的"是否已配置"判定 ───────────────────────────────────────

    private TenantStorageView tenantView(long tenantId) {
        return tenantId <= 0 ? null : tenantConfigLookup.storageView(tenantId);
    }

    private JsonNode storageConfig(long tenantId, String provider) {
        TenantStorageView tenant = tenantView(tenantId);
        if (tenant == null || tenant.storageEngineConfig() == null) {
            return null;
        }
        JsonNode conf = JsonMappers.toJsonNode(tenant.storageEngineConfig()).get(provider);
        return conf == null || conf.isNull() ? null : conf;
    }

    /** 租户是否已配置 MinIO（remote 三要素齐全，或部署 env 齐备）。 */
    public boolean isMinioConfigured(long tenantId) {
        JsonNode minio = storageConfig(tenantId, "minio");
        if (minio != null && "remote".equals(minio.path("mode").asText(""))) {
            return !minio.path("endpoint").asText("").isEmpty()
                    && !minio.path("access_key_id").asText("").isEmpty()
                    && !minio.path("secret_access_key").asText("").isEmpty();
        }
        return isMinioEnvAvailable();
    }

    private boolean hasAllNonEmpty(long tenantId, String provider, List<String> fields, boolean envFallback) {
        JsonNode conf = storageConfig(tenantId, provider);
        if (conf != null) {
            for (String f : fields) {
                if (conf.path(f).asText("").isEmpty()) {
                    return false;
                }
            }
            return true;
        }
        return envFallback;
    }

    private boolean isTosConfigured(long tenantId) {
        return hasAllNonEmpty(tenantId, "tos",
                List.of("endpoint", "region", "access_key", "secret_key", "bucket_name"),
                isMinioEnvAvailable() && envAll("TOS_ENDPOINT", "TOS_REGION",
                        "TOS_ACCESS_KEY", "TOS_SECRET_KEY", "TOS_BUCKET_NAME"));
    }

    private boolean isObsConfigured(long tenantId) {
        return hasAllNonEmpty(tenantId, "obs",
                List.of("endpoint", "region", "access_key", "secret_key", "bucket_name"),
                envAll("OBS_ENDPOINT", "OBS_REGION", "OBS_ACCESS_KEY", "OBS_SECRET_KEY", "OBS_BUCKET_NAME"));
    }

    private boolean isCosConfigured(long tenantId) {
        return hasAllNonEmpty(tenantId, "cos",
                List.of("secret_id", "secret_key", "region", "bucket_name"), false);
    }

    private boolean isOssConfigured(long tenantId) {
        return hasAllNonEmpty(tenantId, "oss",
                List.of("endpoint", "region", "access_key", "secret_key", "bucket_name"), false);
    }

    private boolean isKs3Configured(long tenantId) {
        return hasAllNonEmpty(tenantId, "ks3",
                List.of("endpoint", "region", "access_key", "secret_key", "bucket_name"), false);
    }

    /** region/bucket 非空 + (accessKey 空) == (secretKey 空)。 */
    private boolean isS3Configured(long tenantId) {
        JsonNode s3 = storageConfig(tenantId, "s3");
        if (s3 != null) {
            String accessKey = s3.path("access_key").asText("");
            String secretKey = s3.path("secret_key").asText("");
            return !s3.path("region").asText("").isEmpty()
                    && !s3.path("bucket_name").asText("").isEmpty()
                    && (accessKey.isEmpty()) == (secretKey.isEmpty());
        }
        return false;
    }

    /**
     * status=active 的多实例后端 provider 集合
     * （小写去空白；查询失败回落空集合走 legacy 检查，同为 best-effort）。
     */
    public Map<String, Boolean> activeBackendProviders(long tenantId) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        if (tenantId <= 0) {
            return result;
        }
        try {
            for (StorageBackendView backend : storageBackendPort.list(tenantId)) {
                if (backend == null || !"active".equals(backend.getStatus())) {
                    continue;
                }
                String provider = backend.getProvider() == null ? "" : backend.getProvider();
                result.put(provider.toLowerCase().trim(), true);
            }
        } catch (RuntimeException e) {
            // best-effort：查询失败回落 legacy 配置检查（空集合）
        }
        return result;
    }

    // ── env 读取 ─────────────────────────────────────────────────────────

    private boolean env(String name) {
        String v = environment.getProperty(name);
        return v != null && !v.isEmpty();
    }

    private boolean envAll(String... names) {
        for (String n : names) {
            if (!env(n)) {
                return false;
            }
        }
        return true;
    }

    private String orEnv(String value, String envName) {
        return value == null || value.isEmpty() ? environment.getProperty(envName, "") : value;
    }

    private static boolean truthy(Boolean b) {
        return Boolean.TRUE.equals(b);
    }
}
