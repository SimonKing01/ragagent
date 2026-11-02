package com.ragagent.storage.fileserve;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.contract.tenant.TenantConfigLookup.TenantStorageView;
import com.ragagent.storage.domain.StorageBackend;
import com.ragagent.storage.repository.StorageBackendRepository;

/**
 * 存储 backend 解析：显式 id → legacy alias → 租户默认后端；并补全 stub 租户视图
 * 缺失的 defaultStorageBackendId。
 */
final class StorageBackendLocator {

    private final StorageBackendRepository backendRepo;

    StorageBackendLocator(StorageBackendRepository backendRepo) {
        this.backendRepo = backendRepo;
    }

    record Result(StorageBackend backend, String error) {
    }

    /** backend 解析：legacy alias 与默认后端两级回落。 */
    Result resolve(TenantStorageView tenant, String backendId, String provider) {
        String id = backendId == null ? "" : backendId.trim();
        String p = provider == null ? "" : provider.trim().toLowerCase(java.util.Locale.ROOT);
        if (id.isEmpty() && !p.isEmpty()) {
            StorageBackend alias = backendRepo.findLegacyAlias(tenant.tenantId(), p);
            if (alias != null) {
                return new Result(alias, null);
            }
        }
        if (id.isEmpty()) {
            id = tenant.defaultStorageBackendId() == null ? "" : tenant.defaultStorageBackendId().trim();
        }
        if (!id.isEmpty()) {
            StorageBackend backend = backendRepo.getByID(tenant.tenantId(), id).orElse(null);
            if (backend == null) {
                return new Result(null, "storage backend not found");
            }
            if (!"active".equals(backend.getStatus())) {
                return new Result(null, "storage backend is not active");
            }
            return new Result(backend, null);
        }
        return new Result(null, null);
    }

    /** stub TenantStorageView 从库行补 DefaultStorageBackendId/配置。 */
    TenantStorageView hydrate(TenantStorageView tenant) {
        if (tenant.tenantId() == 0 || backendRepo == null) {
            // 仓储缺位（纯单元测试构造）时跳过补全：本方法只是"从库行补 stub 字段"的优化，
            // 补不了就按调用方给的实体走。
            return tenant;
        }
        if (tenant.defaultStorageBackendId() != null && !tenant.defaultStorageBackendId().trim().isEmpty()) {
            return tenant;
        }
        String storedDefault = backendRepo.tenantDefaultBackendId(tenant.tenantId());
        if (storedDefault != null && !storedDefault.isEmpty()) {
            return new TenantStorageView(tenant.tenantId(), storedDefault, tenant.storageEngineConfig());
        }
        return tenant;
    }

    static String defaultProvider(JsonNode sec) {
        if (sec == null) {
            return "";
        }
        JsonNode dp = sec.get("defaultProvider");
        if (dp == null || !dp.isTextual()) {
            return "";
        }
        return dp.asText().trim().toLowerCase(java.util.Locale.ROOT);
    }
}
