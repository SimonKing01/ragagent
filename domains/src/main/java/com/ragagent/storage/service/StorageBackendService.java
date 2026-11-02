package com.ragagent.storage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.contract.error.AppError;
import com.ragagent.contract.error.BizException;
import com.ragagent.platform.security.SsrfGuard;
import com.ragagent.platform.storage.config.StorageAllowList;
import com.ragagent.platform.web.JsonMappers;
import com.ragagent.storage.domain.StorageBackend;
import com.ragagent.storage.dto.StorageConfig;
import com.ragagent.storage.provider.StorageProviders;
import com.ragagent.storage.provider.StorageProviders.ConnectorFailure;
import com.ragagent.storage.repository.StorageBackendRepository;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;

/**
 * 存储后端管理的 HTTP 面方法：Create / Update / Delete / SetDefault / Test + Validate 链。
 *
 * <p><b>连通性测试的形态</b>：远端 provider（minio/cos/tos/s3/oss/ks3/obs）以 TCP 拨号到
 * endpoint 替代云 SDK 探测——拒连的**清洗后文案**一致（SanitizeStorageConnectivityError
 * 的中文映射），拨通后的鉴权/桶存在性检查不实现；本地 provider 完整对齐
 * （SafeJoin + mkdir + 目录可访问检查）。docker 模式的 env 回填（MINIO_*）保留。</p>
 */
@Service
public class StorageBackendService {

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    public static final String SOURCE_USER = "user";
    public static final String SOURCE_ENV = "env";
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_DISABLED = "disabled";
    public static final String REDACTED = "***";

    /**
 * 控制器读面：租户的全部存储后端（控制器不直连仓储，见包地图）。
 */
    public java.util.List<StorageBackend> listBackends(long tenantId) {
        return repo.list(tenantId);
    }

    /** 控制器读面：租户默认后端 id（控制器拿不到租户时传 null）。 */
    public String tenantDefaultBackendId(long tenantId) {
        return repo.tenantDefaultBackendId(tenantId);
    }

    /** 控制器读面：租户内按 id 取后端；不存在返回 {@code null}（控制器转 404）。 */
    public StorageBackend getBackend(long tenantId, String id) {
        return repo.getByID(tenantId, id).orElse(null);
    }

    /** 路径规范化：收敛 ../ 与 ..（近似语义，非完整实现），用于 path_prefix 规范化比较 */

    private final StorageBackendRepository repo;
    private final StorageAllowList allowList;
    private final SsrfGuard ssrfGuard;
    private final StorageConfigCodec codec;
    private final org.springframework.transaction.support.TransactionTemplate tx;
    /** 本地存储根（{@code LOCAL_STORAGE_BASE_DIR}，缺省 ${user.home}/ragagent-data/files）；测试经 weknora.storage.local-base-dir 指向 build 目录。 */
    private final String localStorageBaseDir;

    public StorageBackendService(StorageBackendRepository repo, StorageAllowList allowList,
            SsrfGuard ssrfGuard, StorageConfigCodec codec,
            org.springframework.transaction.support.TransactionTemplate tx,
            @org.springframework.beans.factory.annotation.Value(
                    "${weknora.storage.local-base-dir:${LOCAL_STORAGE_BASE_DIR:${user.home}/ragagent-data/files}}") String localStorageBaseDir) {
        this.repo = repo;
        this.allowList = allowList;
        this.ssrfGuard = ssrfGuard;
        this.codec = codec;
        this.tx = tx;
        this.localStorageBaseDir = localStorageBaseDir;
    }

    public void validate(StorageBackend b) {
        if (b.getTenantId() == null || b.getTenantId() == 0) {
            throw validation("tenant_id is required");
        }
        b.setName(b.getName() == null ? "" : b.getName().trim());
        if (b.getName().isEmpty()) {
            throw validation("name is required");
        }
        b.setProvider(b.getProvider() == null ? "" : b.getProvider().trim().toLowerCase());
        if (!allowList.isAllowed(b.getProvider())) {
            throw validation("storage provider \"" + b.getProvider() + "\" is not allowed");
        }
        if (!allowList.isSupported(b.getProvider())) {
            throw validation("unsupported storage provider: " + b.getProvider());
        }
        if (b.getStatus() == null || b.getStatus().isEmpty()) {
            b.setStatus(STATUS_ACTIVE);
        }
        if (!STATUS_ACTIVE.equals(b.getStatus()) && !STATUS_DISABLED.equals(b.getStatus())) {
            throw validation("status must be active or disabled");
        }
        validateForProvider(configOf(b), b.getProvider());
    }

    /** required 检查取固定序（golden 全部用单缺失字段钉住，顺序不进契约） */
    public void validateForProvider(StorageConfig c, String provider) {
        String prefix = c.pathPrefix == null ? "" : c.pathPrefix.replace("\\", "/").trim();
        String cleanPrefix = clean(prefix);
        if (prefix.startsWith("/") || cleanPrefix.equals("..") || cleanPrefix.startsWith("../")) {
            throw validation("path_prefix must be a relative path without parent traversal");
        }
        StorageProviders.forValidation(provider).validate().accept(c);
    }

    /** SSRF（code 1000 + details=原文） */
    public void validateEndpoint(StorageBackend b) {
        StorageConfig c = configOf(b);
        StorageProviders.EndpointPolicy policy =
                StorageProviders.forValidation(b.getProvider()).endpointPolicy().apply(c);
        if (policy.skipSsrf()) {
            return;
        }
        String endpoint = c.endpoint == null ? "" : c.endpoint.trim();
        if (!endpoint.contains("://")) {
            endpoint = policy.scheme() + endpoint;
        }
        try {
            ssrfGuard.validateURLForSSRF(endpoint);
        } catch (SsrfGuard.SsrfException e) {
            throw new BizException(AppError.badRequest("storage endpoint failed SSRF validation")
                    .withDetails(e.getMessage()));
        }
    }

    // ── 连通性测试（local 全对齐，远端以拨号替代 SDK） ────────────────────

    public void test(StorageBackend b) {
        validate(b);
        validateEndpoint(b);
        StorageConfig c = configOf(b);
        StorageProviders.forValidation(b.getProvider()).connectivity().test(
                c, new StorageProviders.TestEnv(localStorageBaseDir, b.getProvider(), this::dialEndpoint));
    }

    /** 创建：校验 → endpoint → 连通性测试 → 时间戳 → 落库（唯一冲突 → 409） */
    public void create(StorageBackend b) {
        // 落库前默认值：id 为空生成 UUID、source 缺省 user、status 缺省 active
        if (b.getId() == null || b.getId().isEmpty()) {
            b.setId(java.util.UUID.randomUUID().toString());
        }
        if (b.getSource() == null || b.getSource().isEmpty()) {
            b.setSource(SOURCE_USER);
        }
        validate(b);
        validateEndpoint(b);
        try {
            test(b);
        } catch (BizException e) {
            throw new BizException(AppError.badRequest("storage connection test failed")
                    .withDetails(sanitizeConnectivity(e.appError().message())));
        } catch (ConnectorFailure e) {
            throw new BizException(AppError.badRequest("storage connection test failed")
                    .withDetails(sanitizeConnectivity(e.getMessage())));
        }
        b.setCreatedAt(OffsetDateTime.now());
        b.setUpdatedAt(OffsetDateTime.now());
        // 唯一性：PG 靠部分唯一索引（deleted_at IS NULL）抛错 → 409；H2 无该索引 →
        // 先查再插（单实例语义等价；PG 侧保留插入后 catch 兜底竞态窗口）
        if (repo.nameExists(b.getTenantId(), b.getName())) {
            throw BizException.conflict("a storage backend with this name already exists");
        }
        try {
            repo.create(b, serializeConfig(b));
        } catch (RuntimeException e) {
            if (repo.nameExists(b.getTenantId(), b.getName())) {
                throw BizException.conflict("a storage backend with this name already exists");
            }
            throw e;
        }
    }

    /** 更新：守卫顺序 get → env → merge → immutable → disable-guard → 校验 → 连通性测试 → 落库 */
    public void update(StorageBackend incoming) {
        StorageBackend existing = repo.getByID(tenant(incoming), incoming.getId()).orElse(null);
        if (existing == null) {
            throw BizException.notFound("storage backend not found");
        }
        if (SOURCE_ENV.equals(existing.getSource())) {
            throw BizException.badRequest("environment storage backend is read-only");
        }
        incoming.setProvider(existing.getProvider());
        StorageConfig incomingConfig = configOf(incoming);
        StorageConfig existingConfig = configOf(existing);
        // MergeSecrets（PreserveIfRedacted）：incoming 为**空串或 *** 占位**都保留存量
        if (incomingConfig.accessKeyId == null || incomingConfig.accessKeyId.isEmpty()
                || REDACTED.equals(incomingConfig.accessKeyId)) {
            incomingConfig.accessKeyId = existingConfig.accessKeyId;
        }
        if (incomingConfig.secretAccessKey == null || incomingConfig.secretAccessKey.isEmpty()
                || REDACTED.equals(incomingConfig.secretAccessKey)) {
            incomingConfig.secretAccessKey = existingConfig.secretAccessKey;
        }
        incoming.setConfig(jsonNodeOf(incomingConfig));
        // LocationKey：provider 固定为 existing.provider（两边比较用同一 provider）
        if (!locationKey(incomingConfig, existing.getProvider())
                .equals(locationKey(existingConfig, existing.getProvider()))) {
            throw BizException.badRequest(
                    "endpoint, region, bucket and path prefix are immutable; use storage migration instead");
        }
        if (incoming.getStatus() == null || incoming.getStatus().isEmpty()) {
            incoming.setStatus(existing.getStatus());
        }
        long tenantId = tenant(incoming);
        if (STATUS_DISABLED.equals(incoming.getStatus()) && !STATUS_DISABLED.equals(existing.getStatus())) {
            int references = repo.countDefaultReference(tenantId, incoming.getId());
            if (references == 0) {
                references = repo.countBoundKnowledgeBases(tenantId, incoming.getId());
            }
            if (references == 0) {
                references = repo.countActiveResources(tenantId, incoming.getId());
            }
            if (references > 0) {
                throw BizException.badRequest("a default or bound storage backend cannot be disabled");
            }
        }
        validate(incoming);
        validateEndpoint(incoming);
        try {
            test(incoming);
        } catch (BizException e) {
            throw new BizException(AppError.badRequest("storage connection test failed")
                    .withDetails(sanitizeConnectivity(e.appError().message())));
        } catch (ConnectorFailure e) {
            throw new BizException(AppError.badRequest("storage connection test failed")
                    .withDetails(sanitizeConnectivity(e.getMessage())));
        }
        incoming.setUpdatedAt(OffsetDateTime.now());
        repo.update(tenantId, incoming.getId(), incoming.getName(), serializeConfig(incoming),
                incoming.getStatus(), incoming.getUpdatedAt());
    }

    /** 删除：事务内 get → env → default → KB → 资源 → legacy → 软删 */
    public void delete(long tenantId, String id) {
        tx.executeWithoutResult(status -> {
            StorageBackend backend = repo.getByID(tenantId, id).orElse(null);
            if (backend == null) {
                throw BizException.notFound("storage backend not found");
            }
            if (SOURCE_ENV.equals(backend.getSource())) {
                throw BizException.badRequest("environment storage backend is read-only");
            }
            if (repo.countDefaultReference(tenantId, id) > 0) {
                throw BizException.badRequest("default storage backend cannot be deleted");
            }
            int kbCount = repo.countBoundKnowledgeBases(tenantId, id);
            if (kbCount > 0) {
                throw BizException.badRequest("storage backend still has " + kbCount
                        + " knowledge base(s) bound to it");
            }
            int resourceCount = repo.countActiveResources(tenantId, id);
            if (resourceCount > 0) {
                throw BizException.badRequest("storage backend still has " + resourceCount
                        + " active resource(s)");
            }
            if (backend.isLegacyAlias()) {
                throw BizException.badRequest(
                        "legacy storage backend cannot be deleted while old file paths may reference it");
            }
            repo.delete(tenantId, id);
        });
    }

    /** 设默认：事务内 get → 仅 active → 更新租户默认 */
    public void setDefault(long tenantId, String id) {
        tx.executeWithoutResult(status -> {
            StorageBackend backend = repo.getByID(tenantId, id).orElse(null);
            if (backend == null) {
                throw BizException.notFound("storage backend not found");
            }
            if (!STATUS_ACTIVE.equals(backend.getStatus())) {
                throw BizException.badRequest("only an active storage backend can be the default");
            }
            repo.setTenantDefaultBackendId(tenantId, id);
        });
    }

    // ── 辅助 ───────────────────────────────────────────────────────────

    /** mode 的 minio remote 缺省语义 */
    public static String locationKey(StorageConfig c, String provider) {
        String mode = c.mode == null ? "" : c.mode.trim();
        if ("minio".equals(provider) && mode.isEmpty()) {
            mode = "remote";
        }
        return String.join("|",
                provider,
                mode,
                trim(c.endpoint),
                trim(c.region),
                trim(c.bucketName),
                stripSlashes(trim(c.pathPrefix)));
    }

    /** config 读取：JsonNode → typed（凭据解密统一走 {@link StorageConfigCodec}——唯一读写口） */
    public StorageConfig configOf(StorageBackend b) {
        return codec.decode(b.getConfig());
    }

    /** config 写出：typed → 凭据加密 → JSON 字符串（统一走 {@link StorageConfigCodec}——唯一读写口） */
    public String serializeConfig(StorageBackend b) {
        return codec.encode(b.getConfig());
    }

    /** 非空密钥 → "***"（掩码只作用于响应） */
    public com.fasterxml.jackson.databind.JsonNode maskedConfig(StorageBackend b) {
        try {
            StorageConfig c = configOf(b);
            if (c.accessKeyId != null && !c.accessKeyId.isEmpty()) {
                c.accessKeyId = REDACTED;
            }
            if (c.secretAccessKey != null && !c.secretAccessKey.isEmpty()) {
                c.secretAccessKey = REDACTED;
            }
            return MAPPER.valueToTree(c);
        } catch (Exception e) {
            throw new IllegalStateException("mask storage backend config failed", e);
        }
    }

    /** 驱动错误 → 中文运维提示 */
    public static String sanitizeConnectivity(String msg) {
        if (msg == null) {
            return "";
        }
        if (msg.contains("Endpoint url cannot have fully qualified paths")) {
            return "Endpoint 地址格式错误：请去除 http:// 或 https:// 前缀，只填写域名或 IP 地址和端口（例如：minio.example.com:9000）";
        }
        if (msg.contains("no such host")) {
            return "DNS 解析失败，请检查地址是否正确";
        }
        if (msg.contains("connection refused")) {
            return "连接被拒绝，请确认服务已启动且端口正确";
        }
        if (msg.contains("no route to host")) {
            return "无法路由到目标地址，请检查网络配置";
        }
        if (msg.contains("i/o timeout") || msg.contains("deadline exceeded") || msg.contains("context deadline")) {
            return "连接超时，请检查网络或服务状态";
        }
        if (msg.contains("403") || msg.contains("AccessDenied") || msg.contains("access denied")) {
            return "认证失败，请检查访问凭证是否正确";
        }
        if (msg.contains("certificate") || msg.contains("tls") || msg.contains("x509")) {
            return "TLS/SSL 证书错误，请检查 SSL 配置";
        }
        if (msg.contains("404") || msg.contains("NoSuchBucket")) {
            return "Bucket 不存在，请检查名称和 Region";
        }
        return "连接失败，请检查配置参数是否正确";
    }

    /** AppError 取 Message，其余清洗（controller 用） */
    public String testErrorMessage(RuntimeException e) {
        if (e instanceof BizException biz) {
            return biz.appError().message();
        }
        if (e instanceof ConnectorFailure cf) {
            return sanitizeConnectivity(cf.getMessage());
        }
        return sanitizeConnectivity(e.getMessage());
    }

    // ── 私有 ──

    private void dialEndpoint(String endpoint, int fallbackPort, String provider) {
        String host;
        int port = fallbackPort;
        try {
            String normalized = endpoint == null ? "" : endpoint.trim();
            if (!normalized.contains("://")) {
                normalized = "https://" + normalized;
            }
            URI uri = URI.create(normalized);
            host = uri.getHost();
            if (uri.getPort() > 0) {
                port = uri.getPort();
            }
        } catch (RuntimeException e) {
            host = null;
        }
        if (host == null || host.isEmpty()) {
            throw new ConnectorFailure("dial " + provider + ": connection refused");
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
        } catch (IOException e) {
            throw new ConnectorFailure("dial " + provider + ": connection refused");
        }
    }

    private static long tenant(StorageBackend b) {
        return b.getTenantId() == null ? 0 : b.getTenantId();
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String stripSlashes(String s) {
        String out = s;
        while (out.startsWith("/")) {
            out = out.substring(1);
        }
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    /** 路径规范化（近似语义）：{@code ..} 与 {@code a/../..} 均收敛为 {@code ..} */
    private static String clean(String path) {
        if (path.isEmpty()) {
            return ".";
        }
        boolean rooted = path.startsWith("/");
        String[] parts = path.split("/");
        java.util.Deque<String> stack = new java.util.ArrayDeque<>();
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                if (!stack.isEmpty() && !stack.peek().equals("..")) {
                    stack.pop();
                } else if (!rooted) {
                    stack.push("..");
                }
            } else {
                stack.push(part);
            }
        }
        StringBuilder sb = new StringBuilder();
        if (rooted) {
            sb.append('/');
        }
        for (java.util.Iterator<String> it = stack.descendingIterator(); it.hasNext();) {
            sb.append(it.next());
            if (it.hasNext()) {
                sb.append('/');
            }
        }
        String out = sb.toString();
        return out.isEmpty() ? (rooted ? "/" : ".") : out;
    }

    private com.fasterxml.jackson.databind.JsonNode jsonNodeOf(StorageConfig c) {
        return MAPPER.valueToTree(c);
    }

    private static BizException validation(String message) {
        return new BizException(AppError.validation(message));
    }

}
