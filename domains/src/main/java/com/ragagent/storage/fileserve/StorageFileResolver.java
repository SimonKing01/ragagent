package com.ragagent.storage.fileserve;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.contract.storage.StorageEnvLookup;
import com.ragagent.contract.storage.fileserve.FileContentService;
import com.ragagent.contract.storage.fileserve.WritableFileContentService;
import com.ragagent.contract.tenant.StorageEngineConfig;
import com.ragagent.contract.tenant.TenantConfigLookup.TenantStorageView;
import com.ragagent.platform.security.CryptoService;
import com.ragagent.platform.storage.fileserve.StoragePaths;
import com.ragagent.platform.web.JsonMappers;
import com.ragagent.storage.config.StorageProviderEnv;
import com.ragagent.storage.domain.StorageBackend;
import com.ragagent.storage.provider.FileService;
import com.ragagent.storage.provider.FileServiceFactory;
import com.ragagent.storage.repository.StorageBackendRepository;
import com.ragagent.storage.service.ResourceCatalogService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 运行时存储解析门面：backend 解析、配置投影、provider 工厂与 failover 编排。
 *
 * <p>协作者：backend 选择 / 租户视图补全在 {@link StorageBackendLocator}，实例行 →
 * 引擎配置（改名 + 凭据解密）在 {@link StorageConfigProjector}，环境快照在
 * {@link StorageEnvProvisioner}，{@code resource://} 装饰在
 * {@link ResourceCatalogFileService}。本类保留公开入口与错误通道。</p>
 *
 * <p>云 provider 的 SDK 客户端经 {@link FileServiceFactory} 构造（local + S3 协议族 +
 * 厂商原生 SDK），完备性错误文案由工厂单点产出，会出现在 presigned-preview 的 400 body 里
 * ——那是固定线格式，改文案前先找金片。</p>
 */
@Service
public class StorageFileResolver {

    private static final Logger log = LoggerFactory.getLogger(StorageFileResolver.class);

    private final ResourceCatalogService catalog;
    private final StorageBackendLocator backendResolver;
    private final StorageEnvProvisioner envProvisioner;

    @Autowired
    public StorageFileResolver(StorageBackendRepository backendRepo, ResourceCatalogService catalog,
            List<StorageProviderEnv.ProviderEnvFamily> providerEnvs) {
        this.catalog = catalog;
        Map<String, StorageProviderEnv.ProviderEnvFamily> envs = new LinkedHashMap<>();
        if (providerEnvs != null) {
            for (StorageProviderEnv.ProviderEnvFamily env : providerEnvs) {
                envs.put(env.provider(), env);
            }
        }
        this.backendResolver = new StorageBackendLocator(backendRepo);
        this.envProvisioner = new StorageEnvProvisioner(envs);
    }

    /**
     * 纯单元测试/无装配场景的便捷构造：只带 {@code local} 族——与「未设置 {@code STORAGE_TYPE}」
     * 的 env 缺省（provider=local、无 pathPrefix）同形，故既有测试的解析结果不变。
     */
    public StorageFileResolver(StorageBackendRepository backendRepo, ResourceCatalogService catalog) {
        this(backendRepo, catalog, List.of(new StorageProviderEnv.Local(null)));
    }

    // ── 解析结果 ────────────────────────────────────────────────────────────

    /** (fileSvc, resolvedProvider, ok)——错误经 {@code error} 通道。 */
    public record Resolution(FileContentService service, String resolvedProvider, String error) {

        boolean ok() {
            return error == null;
        }
    }

    // ── storagebackend.ResolveFileService / ResolveBackend ──────────────────

    /**
     * 返回的 Resolution.error 非 null 时 HTTP 面按各自的映射处理。
     */
    public Resolution resolveFileService(TenantStorageView tenant, String backendId, String provider,
            String localBaseDir) {
        if (tenant == null) {
            return new Resolution(null, "", "workspace context missing");
        }
        TenantStorageView hydrated = hydrateTenantStorage(tenant);
        Objects.requireNonNull(hydrated);

        StorageBackendLocator.Result backend = backendResolver.resolve(hydrated, backendId, provider);
        if (backend.error() != null) {
            return new Resolution(null, "", backend.error());
        }
        if (backend.backend() != null) {
            StorageBackend b = backend.backend();
            FactoryResult inner = newFileServiceFromStorageConfig(b.getProvider(),
                    toStorageEngineConfig(b), localBaseDir);
            if (inner.error() != null) {
                return new Resolution(null, inner.provider(), inner.error());
            }
            // backend 在位 → 包 BackendScoped（GetFileURL 的
            // storage://<id>/ 包装与 GetFile 的 mismatch 守卫都来自它），
            // 再包 resource catalog 装饰器。
            return new Resolution(
                    decorate(new BackendScopedFileService(b.getId(), inner.service())),
                    inner.provider(), null);
        }
        JsonNode sec = JsonMappers.toJsonNode(hydrated.storageEngineConfig());
        String provider0 = provider == null ? "" : provider.trim().toLowerCase(java.util.Locale.ROOT);
        if (provider0.isEmpty() && storageEngineDefaultProvider(sec).isEmpty()) {
            StorageBackend env = storageBackendFromEnvironment(hydrated.tenantId());
            if (env != null) {
                provider0 = env.getProvider();
                if (sec == null) {
                    sec = toStorageEngineConfig(env);
                }
            }
        }
        FactoryResult inner = newFileServiceFromStorageConfig(provider0, sec, localBaseDir);
        if (inner.error() != null) {
            return new Resolution(null, inner.provider(), inner.error());
        }
        return new Resolution(decorate(inner.service()), inner.provider(), null);
    }

    /** stub TenantStorageView 从库行补 DefaultStorageBackendId/配置。 */
    private TenantStorageView hydrateTenantStorage(TenantStorageView tenant) {
        return backendResolver.hydrate(tenant);
    }

    private static String storageEngineDefaultProvider(JsonNode sec) {
        return StorageBackendLocator.defaultProvider(sec);
    }

    // ── 配置 → FileService 工厂 ─────────────────────────────────────────────

    public record FactoryResult(FileContentService service, String provider, String error) {
    }

    /**
     * provider 可空 → 租户
     * defaultProvider；完备性检查的错误文案为固定线格式（presigned-preview 400 可见）。
     */
    public FactoryResult newFileServiceFromStorageConfig(String provider, JsonNode sec, String localBaseDir) {
        String p = provider == null ? "" : provider.trim().toLowerCase(java.util.Locale.ROOT);
        if (p.isEmpty() && sec != null) {
            p = storageEngineDefaultProvider(sec);
        }
        if (p.isEmpty()) {
            return new FactoryResult(null, "", "empty provider");
        }
        String baseDir = localBaseDir == null || localBaseDir.isEmpty()
                ? StoragePaths.localStorageBaseDir() : localBaseDir;

        if ("local".equals(p)) {
            String dir = baseDir;
            JsonNode local = sec == null ? null : sec.get("local");
            if (local != null) {
                String pathPrefix = textOrNull(local.get("pathPrefix"));
                if (pathPrefix != null && !pathPrefix.trim().isEmpty()) {
                    String joined = LocalFileContentService.joinPath(dir, pathPrefix.trim());
                    try {
                        dir = LocalFileContentService.safePathUnderBase(dir, joined);
                    } catch (Exception ignored) {
                        // safePathUnderBase 失败 → 忽略，baseDir 原样
                    }
                }
            }
            String externalURL = env("APP_EXTERNAL_URL");
            return new FactoryResult(new LocalFileContentService(dir, externalURL), p, null);
        }
        // 云 provider 的完备性校验与文案由 FileServiceFactory 单点产出；
        // 这里不再逐 provider 重复一套 switch（两套字符串曾各自维护）。
        return providerBacked(p, sec, baseDir);
    }

    /**
     * 配置完备的云 provider → 经 {@code FileServiceFactory} 造真实 SDK 客户端。
     *
     * <p>完备性校验的文案由工厂产出（与上面各分支的字符串一致），因此
     * 这里不需要重复校验；{@code IllegalArgumentException} 就是"配置不完整"的通道。
     * 构造期的凭据/网络失败（真连桶）折成同一 error 通道——调用方按各自映射处理
     * （presigned-preview 折 400）。</p>
     */
    private static FactoryResult providerBacked(String p, JsonNode sec, String baseDir) {
        ProviderResolution pr = buildProviderRaw(p, sec, baseDir);
        if (!pr.ok()) {
            return new FactoryResult(null, pr.provider(), pr.error());
        }
        return new FactoryResult(new ProviderFileContentService(pr.service()), pr.provider(), null);
    }

    // ── 写面（A3-3 尾批）：知识上传/读取用的**原始** provider 服务 ──────────────

    /** provider 服务的原始解析结果（未经 resource catalog 装饰——写面用）。 */
    public record ProviderResolution(FileService service,
                                     String provider, String error) {
        public boolean ok() {
            return error == null && service != null;
        }
    }

    /**
     * 写面解析：按
     * backend 优先 → 环境回归 → 租户 defaultProvider 的顺序解析出**原始** provider
     * 服务（知识上传的 {@code SaveFile} / 读取的 {@code GetFile} 直连它，不经过
     * resource catalog 装饰——装饰层是给 HTTP 流式面用的）。
     *
     * <p>{@code local} 或 provider 为空时 {@code service == null} 且 {@code error == null}：
     * 调用方走 Java 既有的本地契约（{@code resource://…} + {@code LocalStorageService}），
     * 那是 golden 锁定的落盘形态，不能换成 provider 的 {@code local://…}。</p>
     */
    public ProviderResolution resolveProviderService(TenantStorageView tenant, String backendId, String provider,
            String localBaseDir) {
        if (tenant == null) {
            return new ProviderResolution(null, "", "workspace context missing");
        }
        TenantStorageView hydrated = hydrateTenantStorage(tenant);
        StorageBackendLocator.Result backend = backendResolver.resolve(hydrated, backendId, provider);
        if (backend.error() != null) {
            return new ProviderResolution(null, "", backend.error());
        }
        if (backend.backend() != null) {
            return buildProviderRaw(backend.backend().getProvider(),
                    toStorageEngineConfig(backend.backend()), localBaseDir);
        }
        JsonNode sec = JsonMappers.toJsonNode(hydrated.storageEngineConfig());
        String p0 = provider == null ? "" : provider.trim().toLowerCase(java.util.Locale.ROOT);
        if (p0.isEmpty() && storageEngineDefaultProvider(sec).isEmpty()) {
            StorageBackend env = storageBackendFromEnvironment(hydrated.tenantId());
            if (env != null) {
                p0 = env.getProvider();
                if (sec == null) {
                    sec = toStorageEngineConfig(env);
                }
            }
        }
        return buildProviderRaw(p0, sec, localBaseDir);
    }

    /** {@code local}/空 provider → (null, provider, null)；云 → 真客户端或错误文案。 */
    private static ProviderResolution buildProviderRaw(String provider, JsonNode sec, String baseDir) {
        String p = provider == null ? "" : provider.trim().toLowerCase(java.util.Locale.ROOT);
        if (p.isEmpty() && sec != null) {
            p = storageEngineDefaultProvider(sec);
        }
        if (p.isEmpty()) {
            return new ProviderResolution(null, "", "empty provider");
        }
        if ("local".equals(p)) {
            return new ProviderResolution(null, p, null);
        }
        String dir = baseDir == null || baseDir.isEmpty()
                ? StoragePaths.localStorageBaseDir() : baseDir;
        try {
            StorageEngineConfig typed = sec == null
                    ? new StorageEngineConfig()
                    : CONFIG_MAPPER.convertValue(sec,
                            StorageEngineConfig.class);
            FileServiceFactory.Created created =
                    FileServiceFactory.fromStorageConfig(p, typed, dir);
            if (created == null || created.service() == null) {
                return new ProviderResolution(null, p, "unsupported provider \"" + p + "\"");
            }
            return new ProviderResolution(created.service(), p, null);
        } catch (RuntimeException e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            log.warn("build provider file service failed: provider={} err={}", p, msg);
            return new ProviderResolution(null, p, msg);
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper CONFIG_MAPPER =
            JsonMappers.lenient();
    private static String textOrNull(JsonNode n) {
        return n == null || n.isNull() ? null : n.asText();
    }

    /**
     * provider 家族键读取（{@code MINIO_*} / {@code OBS_*} / {@code APP_EXTERNAL_URL} …）——
     * 走存储域统一查找面；未配置 → 空串。
     */
    static String env(String key) {
        String v = StorageEnvLookup.get(key);
        return v == null ? "" : v.trim();
    }

    // ── StorageBackend.ToStorageEngineConfig / FromEnvironment ─────────────

    /** 实例模型 → 单例配置投影；实现见 {@link StorageConfigProjector}。 */
    static JsonNode toStorageEngineConfig(StorageBackend b) {
        return StorageConfigProjector.toStorageEngineConfig(b);
    }

    /** 实例行 → provider 段（凭据必须解密）；实现见 {@link StorageConfigProjector}。 */
    static JsonNode toStorageEngineConfig(StorageBackend b, CryptoService crypto) {
        return StorageConfigProjector.toStorageEngineConfig(b, crypto);
    }

    /**
     * env 快照的 System 只读行。
     *
     * <p>配置不由本类手写 switch 拼装，而是复用**同一个投影**
     * （{@code StorageProviderEnv.*.writeConfig}，落库面 camel 词汇，与
     * {@code DefaultStorageBackendProvisioner} 同源）——本行随后照常经
     * {@code toStorageEngineConfig} 的改名器派生出引擎面。同一份环境变量<b>只有一条
     * 投影路径</b>，键名漂移不再可能。</p>
     */
    StorageBackend storageBackendFromEnvironment(long tenantId) {
        return envProvisioner.fromEnvironment(tenantId);
    }

    // ── 租户级解析失败回落 ──────────────────────────────────────────────────

    /**
     * 租户级解析失败且
     * provider == 全局 STORAGE_TYPE 时回落进程级服务；否则 ok=false（调用方 400）。
     */
    public Resolution resolveTenantFileServiceWithFallback(String logTag, TenantStorageView tenant,
            String backendId, String provider, String absDir, FileContentService globalFileService) {
        Resolution resolution = resolveFileService(tenant, backendId, provider, absDir);
        if (resolution.ok()) {
            return resolution;
        }
        String globalStorageType = StoragePaths.globalStorageType();
        if (provider != null && provider.equals(globalStorageType) && globalFileService != null) {
            log.info("[Router] {} tenant storage config missing or invalid, fallback to global file "
                    + "service: tenant_id={} provider={} err={}", logTag, tenant.tenantId(), provider,
                    resolution.error());
            return new Resolution(new ResourceCatalogFileService(globalFileService, catalog), globalStorageType, null);
        }
        log.warn("[Router] {} resolve file service failed without fallback: tenant_id={} provider={} "
                + "global_storage_type={} err={}", logTag, tenant.tenantId(), provider, globalStorageType,
                resolution.error());
        return new Resolution(null, "", resolution.error());
    }

    // ── resourceCatalogFileService 装饰器 ───────────────────────────────────

    /**
     * 生产装配的进程级默认服务（恒 local 基座）。
     * baseDir 经 Spring 属性注入（env 缺省，测试期可注入——见 FileProxyService）。
     */
    public WritableFileContentService globalFileService(String localBaseDir) {
        return decorate(new LocalFileContentService(localBaseDir, env("APP_EXTERNAL_URL")));
    }

    /** 测试/装饰辅助：给定 inner 的 resource:// 装饰视图（读+写）。 */
    public WritableFileContentService decorate(FileContentService inner) {
        return new ResourceCatalogFileService(inner, catalog);
    }
}
