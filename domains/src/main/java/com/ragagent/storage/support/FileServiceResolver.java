package com.ragagent.storage.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.contract.storage.support.FileUrlPort;
import com.ragagent.contract.storage.support.Resolver;
import com.ragagent.contract.storage.support.StorageBackendResolver;
import com.ragagent.contract.tenant.StorageEngineConfig;
import com.ragagent.contract.tenant.TenantConfigLookup.TenantStorageView;
import com.ragagent.platform.storage.fileserve.StoragePaths;
import com.ragagent.platform.storage.support.Rewriter;
import com.ragagent.platform.web.JsonMappers;
import com.ragagent.storage.provider.FileService;
import com.ragagent.storage.provider.FileServiceFactory;
import com.ragagent.storage.provider.StorageProviders;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 按存储 provider 解析并缓存 FileUrlPort。
 *
 * <p>缓存的作用域是**单个请求或单条出站消息**，这样一条有很多图的长回答不会为每条引用
 * 重建一次 SDK 客户端。</p>
 *
 * <p><b>非并发安全</b>；由 {@link Rewriter} 单线程驱动（Rewriter 的锁横跨整次 resolve）。</p>
 *
 * <h2>provider 级文件服务</h2>
 * <p>按 provider + 租户存储配置经 {@code FileServiceFactory}
 * （八个 provider 全落地）造各自的 SDK 客户端，按兜底顺序
 * （真实服务 → local → {@code defaultSvc}）取用，于是配了云后端的租户引用能被换成
 * 真 HTTP 预签名 URL；未配置/解析失败时仍按 handle 原样保留。</p>
 */
public class FileServiceResolver implements Resolver {

    private static final Logger log = LoggerFactory.getLogger(FileServiceResolver.class);

    /** {@code resource://} 手柄的固定长度。 */
    private static final int RESOURCE_HANDLE_LENGTH = 22;
    private static final String RESOURCE_SCHEME = "resource://";
    private static final String STORAGE_BACKEND_SCHEME = "storage://";


    /**
     * 本地存储的磁盘根——启动期快照（{@code LOCAL_STORAGE_BASE_DIR}），
     * 缺省见 {@link StoragePaths#localStorageBaseDir()}。
     */
    public static String localStorageBaseDir() {
        return StoragePaths.localStorageBaseDir();
    }

    private final TenantStorageView tenant;
    private final FileUrlPort defaultSvc;
    private final StorageBackendResolver storageResolver;
    private final Map<String, FileUrlPort> cache = new HashMap<>();

    /**
     * <p>{@code defaultSvc} 是进程级 FileUrlPort，用于 {@code resource://} 手柄，
     * 以及租户存储配置缺失时的兜底。</p>
     */
    public FileServiceResolver(TenantStorageView tenant, FileUrlPort defaultSvc, StorageBackendResolver storageResolver) {
        this.tenant = tenant;
        this.defaultSvc = defaultSvc;
        this.storageResolver = storageResolver;
    }

    @Override
    public FileUrlPort resolveFileService(String filePath) {
        if (parseResourcePath(filePath)) {
            return defaultSvc;
        }
        String backendId = parseStorageBackendId(filePath);

        String provider = parseProviderScheme(filePath);
        if (provider.isEmpty()) {
            JsonNode config = JsonMappers.toJsonNode(tenant == null ? null : tenant.storageEngineConfig());
            if (config != null && config.isObject()) {
                JsonNode defaultProvider = config.get("defaultProvider");
                if (defaultProvider != null && defaultProvider.isTextual()) {
                    provider = defaultProvider.asText().trim().toLowerCase(Locale.ROOT);
                }
            }
            if (provider.isEmpty()) {
                return null;
            }
        }

        String cacheKey = backendId + ":" + provider;
        FileUrlPort cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        if (storageResolver != null && tenant != null) {
            try {
                StorageBackendResolver.Resolved resolved = storageResolver.resolveFileService(
                        tenant.tenantId(), backendId, provider, localStorageBaseDir());
                if (resolved != null && resolved.fileService() != null) {
                    cache.put(cacheKey, resolved.fileService());
                    return resolved.fileService();
                }
            } catch (RuntimeException e) {
                log.warn("resolve storage backend failed: backend_id={} provider={}", backendId, provider, e);
            }
        }
        FileUrlPort service = buildFileServiceForProvider(provider, defaultSvc);
        if (service != null) {
            cache.put(cacheKey, service);
        }
        return service;
    }

    /**
     * 按 provider 造文件服务：
     *
     * <ol>
     *   <li>按 provider + 租户存储配置造真实服务（{@code FileServiceFactory}，
     *       八个 provider 全落地：local + S3 协议族 + oss/cos/tos）——成功即用，
     *       于是引用能被换成真 HTTP URL（s3 族/三家云的预签名 URL）；</li>
     *   <li>provider == {@code "local"}：直接用 {@code defaultSvc}（调用方传入的
     *       进程级服务；externalURL 为空时给出 {@code local://…} 非 http →
     *       handle 原样保留）；</li>
     *   <li>其余失败 → 回落 {@code defaultSvc}（可能为 null → 引用原样保留）。</li>
     * </ol>
     */
    private FileUrlPort buildFileServiceForProvider(String provider, FileUrlPort defaultSvc) {
        try {
            JsonNode sec = JsonMappers.toJsonNode(tenant == null ? null : tenant.storageEngineConfig());
            StorageEngineConfig typed = sec == null ? new StorageEngineConfig()
                    : CONFIG_MAPPER.convertValue(sec, StorageEngineConfig.class);
            FileServiceFactory.Created created = FileServiceFactory.fromStorageConfig(
                    provider, typed, localStorageBaseDir());
            if (created != null && created.service() != null) {
                return new ProviderUrlFileService(created.service());
            }
        } catch (RuntimeException e) {
            log.warn("build file service for provider failed: provider={}", provider, e);
        }
        return defaultSvc;
    }

    /** storageurl 窄口（只有 GetFileURL）到 A3 provider 服务的适配。 */
    private record ProviderUrlFileService(FileService inner)
            implements FileUrlPort {

        @Override
        public String getFileURL(String filePath) {
            return inner.getFileURL(filePath);
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper CONFIG_MAPPER =
            JsonMappers.lenient();
    /**
     * {@code resource://} 后必须恰好 22 个
     * {@code [A-Za-z0-9_-]} 字符。
     */
    private static boolean parseResourcePath(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        if (!trimmed.startsWith(RESOURCE_SCHEME)) {
            return false;
        }
        String handle = trimmed.substring(RESOURCE_SCHEME.length());
        if (handle.length() != RESOURCE_HANDLE_LENGTH) {
            return false;
        }
        for (int i = 0; i < handle.length(); i++) {
            if (!isResourceHandleChar(handle.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isResourceHandleChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_' || c == '-';
    }

    /**
     * 解析 {@code storage://<id>/<providerPath>}，
     * 只取 id（providerPath 在本包用不到）。
     */
    private static String parseStorageBackendId(String path) {
        if (path == null || !path.startsWith(STORAGE_BACKEND_SCHEME)) {
            return "";
        }
        String rest = path.substring(STORAGE_BACKEND_SCHEME.length());
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash == rest.length() - 1) {
            return "";
        }
        return rest.substring(0, slash);
    }

    /**
     * 先剥掉 {@code storage://<id>/} 前缀，
     * 再按已知 provider 列表前缀匹配。列表**顺序有语义**。
     */
    private static String parseProviderScheme(String filePath) {
        String candidate = filePath == null ? "" : filePath;
        if (candidate.startsWith(STORAGE_BACKEND_SCHEME)) {
            String rest = candidate.substring(STORAGE_BACKEND_SCHEME.length());
            int slash = rest.indexOf('/');
            if (slash > 0 && slash < rest.length() - 1) {
                candidate = rest.substring(slash + 1);
            }
        }
        for (String provider : StorageProviders.schemeKeys()) {
            if (candidate.startsWith(provider + "://")) {
                return provider;
            }
        }
        return "";
    }
}
