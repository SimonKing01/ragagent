package com.ragagent.auth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.dto.TenantRowView;
import com.ragagent.auth.repository.TenantLookup;
import com.ragagent.auth.repository.TenantMemberRepository;
import com.ragagent.auth.repository.TenantRepository;
import com.ragagent.contract.error.AppError;
import com.ragagent.contract.error.BizException;
import com.ragagent.contract.pagination.PageResult;
import com.ragagent.contract.storage.StorageBackendProvisioner;
import com.ragagent.contract.tenant.APIPrincipalConfig;
import com.ragagent.contract.tenant.StorageEngineConfig;
import com.ragagent.contract.tenant.TenantConfigLookup;
import com.ragagent.platform.persistence.LikeEscapes;
import com.ragagent.platform.web.JsonMappers;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 租户 service。
 *
 * 落库行为：软删除过滤（WHERE deleted_at IS NULL）。
 * retriever_engines 归一化：历史裸数组格式 [{...}] 包装成 {"engines":[...]}，
 * 响应恒为包装格式 → 读取后归一化。
 */
@Service
public class TenantService implements TenantConfigLookup, TenantLookup {

    private static final Logger log = LoggerFactory.getLogger(TenantService.class);
    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    private final TenantRepository tenantAdmin;
    private final TenantMemberRepository memberRepo;
    private final StorageBackendProvisioner storageProvisioner;

    public TenantService(TenantRepository tenantAdmin,
                         TenantMemberRepository memberRepo,
                         StorageBackendProvisioner storageProvisioner) {
        this.tenantAdmin = tenantAdmin;
        this.memberRepo = memberRepo;
        this.storageProvisioner = storageProvisioner;
    }

    /** 按 id 取**实体**（域内/端口实现用；不存在返回 null）。 */
    Tenant tenantRow(long id) {
        Tenant t = tenantAdmin.byId(id);
        if (t != null) {
            normalizeRetrieverEngines(t);
            normalizeContextConfig(t);
        }
        return t;
    }

    /** 按 id 查（视图口径；不存在返回 null）——租户管理面消费用。 */
    public TenantRowView getTenantById(long id) {
        return toView(tenantRow(id));
    }

    // ── TenantConfigLookup（检索/模型域的只读端口实现）────────────────────

    @Override
    public Object retrieverEngines(long tenantId) {
        Tenant t = tenantRow(tenantId);
        return t == null ? null : t.getRetrieverEngines();
    }

    @Override
    public Object retrievalConfig(long tenantId) {
        Tenant t = tenantRow(tenantId);
        return t == null ? null : t.getRetrievalConfig();
    }

    @Override
    public Object memoryConfig(long tenantId) {
        Tenant t = tenantRow(tenantId);
        return t == null ? null : t.getMemoryConfig();
    }

    @Override
    public TenantRowView tenantById(long tenantId) {
        return getTenantById(tenantId);
    }

    @Override
    public boolean exists(long tenantId) {
        return tenantRow(tenantId) != null;
    }

    @Override
    public APIPrincipalConfig apiPrincipalConfig(long tenantId) {
        Tenant t = tenantRow(tenantId);
        return t == null ? null : t.getApiPrincipalConfig();
    }

    @Override
    public Object webSearchConfig(long tenantId) {
        return configOf(tenantId, Tenant::getWebSearchConfig);
    }

    @Override
    public Object chatHistoryConfig(long tenantId) {
        return configOf(tenantId, Tenant::getChatHistoryConfig);
    }

    @Override
    public Object parserEngineConfig(long tenantId) {
        return configOf(tenantId, Tenant::getParserEngineConfig);
    }

    /** 三段配置的统一取法：租户不存在或值为 null 时返回 null（与端口 javadoc 的语义一致）。 */
    private JsonNode configOf(long tenantId, java.util.function.Function<Tenant, JsonNode> getter) {
        Tenant t = tenantRow(tenantId);
        return t == null ? null : getter.apply(t);
    }

    @Override
    public TenantStorageView storageView(long tenantId) {
        Tenant t = tenantRow(tenantId);
        if (t == null) {
            return null;
        }
        return new TenantStorageView(
                t.getId() == null ? tenantId : t.getId(), t.getDefaultStorageBackendId(), t.getStorageEngineConfig());
    }

    /** 批量按 id 查（map 形态，供 memberships 组装） */
    public Map<Long, TenantRowView> getTenantsByIds(Collection<Long> ids) {
        Map<Long, TenantRowView> out = new HashMap<>();
        if (ids == null || ids.isEmpty()) {
            return out;
        }
        for (Tenant t : tenantAdmin.byIds(ids)) {
            normalizeRetrieverEngines(t);
            normalizeContextConfig(t);
            out.put(t.getId(), toView(t));
        }
        return out;
    }

    /** 全量列表，created_at DESC，软删除过滤 */
    public List<TenantRowView> listAllTenants() {
        List<Tenant> tenants = tenantAdmin.listAll();
        for (Tenant t : tenants) {
            normalizeRetrieverEngines(t);
            normalizeContextConfig(t);
        }
        return toViews(tenants);
    }

    /**
     * 租户搜索：
     * tenant_id>0 且 keyword 非空 → (id=? OR name LIKE OR description LIKE)；
     * 仅 tenant_id → id=?；仅 keyword → name/description LIKE。
     * LIKE 参数先过 {@code LikeEscapes.escape}（\ % _ 反斜杠转义，PG LIKE 默认 ESCAPE 就是反斜杠）。
     * 先 Count 再 Offset/Limit，恒 created_at DESC。
     */

    public PageResult<TenantRowView> searchTenants(String keyword, long tenantId, int page, int pageSize) {
        String escaped = LikeEscapes.escape(keyword == null ? "" : keyword);
        long total = tenantAdmin.count(escaped, tenantId);
        List<Tenant> tenants = tenantAdmin.search(escaped, tenantId, page, pageSize);
        for (Tenant t : tenants) {
            normalizeRetrieverEngines(t);
            normalizeContextConfig(t);
        }
        return PageResult.of(toViews(tenants), total);
    }

    /** LIKE 转义：\ → \\、% → \%、_ → \_（顺序敏感，先转义反斜杠） */
    /**
     * 更新租户：先做存储桶唯一性校验
     * （KV storage PUT / api-principal PUT 共用这条 service 路径），
     * 再显式刷 updated_at 写回。updateById 跳过 null 字段——
     * 对"从 DB 读出再写回"
     * 的用法等价（值未变的列重写同值）。
     */
    public TenantRowView updateTenant(TenantRowView view) {
        validateStorageBucketUniqueness(view);
        java.time.OffsetDateTime now = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC);
        view.setUpdatedAt(now);
        Tenant tenant = toEntity(view);
        tenantAdmin.updateById(tenant);
        copyInto(view, tenant);
        return toView(tenant);
    }

    /**
     * 存储桶唯一性校验：
     * minio/cos/tos/s3/oss 五族的 bucketName 跨租户唯一；仅当本租户**改动**
     * 了某族桶名（与库存旧值不同）且该桶名被别的租户占用时拒绝。
     * 旧租户行读取失败/不存在按"无旧值"处理（selectById 返回 null）。
     */
    private void validateStorageBucketUniqueness(TenantRowView tenant) {
        JsonNode cfg = JsonMappers.toJsonNode(tenant.getStorageEngineConfig());
        if (cfg == null || cfg.isNull()) {
            return;
        }
        Map<String, String> oldBuckets = Map.of();
        if (tenant.getId() != null && tenant.getId() != 0) {
            Tenant old = tenantAdmin.byIdIncludingDeleted(tenant.getId());
            if (old != null) {
                oldBuckets = bucketNames(old.getStorageEngineConfig());
            }
        }
        Map<String, String> newBuckets = bucketNames(cfg);
        // 汇总其他租户的占用（软删除行不参与）
        Map<String, java.util.Set<String>> usedByOthers = new HashMap<>();
        for (Tenant t : tenantAdmin.listLive()) {
            if (t.getId() != null && t.getId().equals(tenant.getId())) {
                continue;
            }
            bucketNames(t.getStorageEngineConfig()).forEach((p, b) ->
                    usedByOthers.computeIfAbsent(p, k -> new java.util.HashSet<>()).add(b));
        }
        for (Map.Entry<String, String> e : newBuckets.entrySet()) {
            String oldB = oldBuckets.get(e.getKey());
            if (!e.getValue().equals(oldB)) {
                java.util.Set<String> used = usedByOthers.get(e.getKey());
                if (used != null && used.contains(e.getValue())) {
                    throw new BizException(
                            AppError.badRequest(
                                    "存储桶名称「" + e.getValue() + "」已被其他空间使用，为保证数据隔离，请使用其他名称"));
                }
            }
        }
    }

    /** 取 minio/cos/tos/s3/oss 五族的非空 bucketName */
    private static Map<String, String> bucketNames(JsonNode cfg) {
        if (cfg == null || cfg.isNull() || !cfg.isObject()) {
            return Map.of();
        }
        Map<String, String> out = new HashMap<>();
        for (String provider : new String[]{"minio", "cos", "tos", "s3", "oss"}) {
            JsonNode node = cfg.get(provider);
            if (node == null || !node.isObject()) {
                continue;
            }
            JsonNode bucket = node.get("bucketName");
            if (bucket != null && bucket.isTextual() && !bucket.asText().isEmpty()) {
                out.put(provider, bucket.asText());
            }
        }
        return out;
    }

    /**
     * 创建租户：status=active + created_at/updated_at 显式赋值
     * + retriever_engines null → 空数组 + id 回填 + 默认存储后端创建。
     * 存储配额等列落 DB 默认值（10GiB，迁移 000000）。
     * validateStorageBucketUniqueness 对自助路径是 no-op（StorageEngineConfig 恒 null），
     * 超管全字段路径可能携带 → 在 insert 前跑。
     */
    public TenantRowView createTenant(TenantRowView view) {
        Tenant tenant = toEntity(view);
        if (tenant.getName() == null || tenant.getName().isEmpty()) {
            throw new IllegalArgumentException("workspace name cannot be empty");
        }
        tenant.setStatus("active");
        // 真表 business 列 NOT NULL 且无默认（空串兜底）
        if (tenant.getBusiness() == null) {
            tenant.setBusiness("");
        }
        // storage_used 零值兜底：恒 0 落库并回显（响应实体即在内存对象）
        if (tenant.getStorageUsed() == null) {
            tenant.setStorageUsed(0L);
        }
        // retriever_engines null → 空数组
        if (tenant.getRetrieverEngines() == null) {
            ObjectNode engines = MAPPER.createObjectNode();
            engines.putArray("engines");
            tenant.setRetrieverEngines(engines);
        }
        // context_config null → 落库 jsonb 'null' 字面量（既定注册路径行为），
        // 不是 SQL NULL
        if (tenant.getContextConfig() == null) {
            tenant.setContextConfig(MAPPER.nullNode());
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        tenant.setCreatedAt(now);
        tenant.setUpdatedAt(now);
        validateStorageBucketUniqueness(view);
        tenantAdmin.insert(tenant);
        try {
            createDefaultStorageBackend(tenant);
        } catch (RuntimeException e) {
            // 默认后端创建失败 → 删租户回滚，错误上抛
            // （此时还没有关联行，回滚是安全的）
            try {
                tenantAdmin.deleteById(tenant.getId());
            } catch (RuntimeException rollbackErr) {
                log.warn("createTenant rollback: failed to delete tenant {}", tenant.getId(), rollbackErr);
            }
            throw e;
        }
        copyInto(view, tenant);
        return toView(tenant);
    }

    /**
     * 默认存储后端编排：**只留本域事务编排**——
     * 建后端（委派 {@link StorageBackendProvisioner}，env→实体/config 的规则已在存储域）→
     * 回写 default_storage_backend_id → 回写失败则删后端行再上抛。
     * 注册路径 storageEngineConfig 恒 null → 走 env 兜底分支。
     */
    private void createDefaultStorageBackend(Tenant tenant) {
        String backendId = storageProvisioner.provisionForTenant(tenant.getId());
        tenant.setDefaultStorageBackendId(backendId);
        // 回写 updated_at（第二次写，晚于 created_at）
        tenant.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        try {
            tenantAdmin.updateById(tenant);
        } catch (RuntimeException e) {
            try {
                storageProvisioner.deleteForTenant(tenant.getId(), backendId);
            } catch (RuntimeException cleanupErr) {
                log.warn("createDefaultStorageBackend cleanup: failed to delete backend {}", backendId, cleanupErr);
            }
            throw e;
        }
    }

    /**
     * 启动迁移：把存量 KV {@code storage-engine-config} 落成一行 legacy 后端并回写默认 id。
     *
     * <p>KV 写接口已停用；没有这一步，只有 KV 配置的租户会在重启后失去 provider 兜底。
     * 幂等：已有默认后端 id 的租户直接跳过。单租户失败只记日志，不阻断启动。</p>
     */
    public int migrateLegacyStorageBackends() {
        int migrated = 0;
        for (Tenant tenant : tenantAdmin.listLive()) {
            if (tenant.getDefaultStorageBackendId() != null
                    && !tenant.getDefaultStorageBackendId().isBlank()) {
                continue;
            }
            JsonNode raw = tenant.getStorageEngineConfig();
            if (raw == null || raw.isNull() || raw.isEmpty()) {
                continue;
            }
            StorageEngineConfig config;
            try {
                config = MAPPER.treeToValue(raw, StorageEngineConfig.class);
            } catch (Exception e) {
                log.warn("legacy storage migration: parse failed for tenant {}", tenant.getId(), e);
                continue;
            }
            if (config == null) {
                continue;
            }
            String backendId = null;
            try {
                backendId = storageProvisioner.provisionFromLegacy(tenant.getId(), config);
                if (backendId == null) {
                    continue;
                }
                tenant.setDefaultStorageBackendId(backendId);
                tenant.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
                tenantAdmin.updateById(tenant);
                migrated++;
            } catch (RuntimeException e) {
                log.warn("legacy storage migration failed for tenant {}", tenant.getId(), e);
                if (backendId != null) {
                    try {
                        storageProvisioner.deleteForTenant(tenant.getId(), backendId);
                    } catch (RuntimeException cleanupErr) {
                        log.warn("legacy storage migration cleanup failed for backend {}", backendId, cleanupErr);
                    }
                }
            }
        }
        return migrated;
    }


    /**
     * 删除租户（register 失败回滚等路径共用）：先软删
     * tenant_members（tenant_id=?）再软删 tenants（id=?）——
     * 显式写 deleted_at（不用 @TableLogic）。删不存在的 id 不报错（幂等成功）。
     */
    public void deleteTenant(long id) {
        java.time.OffsetDateTime now = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC);
        memberRepo.softDeleteByTenant(id, now);
        tenantAdmin.softDeleteById(id, now);
    }

    /**
     * retriever_engines 归一化：NULL/裸数组 → 包装格式；
     * 响应恒输出包装格式。
     * NULL → {"engines":null}；裸数组 → {"engines":[...]}。
     */
    private static void normalizeRetrieverEngines(Tenant t) {
        JsonNode engines = t.getRetrieverEngines();
        ObjectNode wrapped = MAPPER.createObjectNode();
        if (engines == null) {
            wrapped.putNull("engines");
        } else if (engines.isArray()) {
            wrapped.set("engines", engines);
        } else {
            return;
        }
        t.setRetrieverEngines(wrapped);
    }

    /**
     * context_config 归一化（读取路径与响应输出共用）。
     * 注册路径恒写 jsonb 'null'，
     * 读回时 "null" 落在已分配的零值对象上 → 响应恒输出零值对象。
     * 对象形态则只保留 4 个已知键并按既定声明序重排（反序列化丢未知键，
     * 且 jsonb 存储序 ≠ 声明序——实测 PG 10002 行的键序与输出序不同）。
     */
    private static void normalizeContextConfig(Tenant t) {
        JsonNode cfg = t.getContextConfig();
        ObjectNode out = MAPPER.createObjectNode();
        out.put("maxTokens", intOrZero(cfg, "maxTokens"));
        out.put("compressionStrategy", textOrEmpty(cfg, "compressionStrategy"));
        out.put("recentMessageCount", intOrZero(cfg, "recentMessageCount"));
        out.put("summarizeThreshold", intOrZero(cfg, "summarizeThreshold"));
        t.setContextConfig(out);
    }

    private static int intOrZero(JsonNode cfg, String key) {
        JsonNode v = cfg == null ? null : cfg.get(key);
        return v != null && v.isNumber() ? v.asInt(0) : 0;
    }

    private static String textOrEmpty(JsonNode cfg, String key) {
        JsonNode v = cfg == null ? null : cfg.get(key);
        return v != null && v.isTextual() ? v.asText("") : "";
    }

    /** 实体 → 视图（逐字段搬运；null 透传）。 */
    public static TenantRowView toView(Tenant t) {
        if (t == null) {
            return null;
        }
        TenantRowView v = new TenantRowView();
        v.setId(t.getId());
        v.setName(t.getName());
        v.setDescription(t.getDescription());
        v.setStatus(t.getStatus());
        v.setRetrieverEngines(JsonMappers.toJavaValue(t.getRetrieverEngines()));
        v.setBusiness(t.getBusiness());
        v.setStorageQuota(t.getStorageQuota());
        v.setStorageUsed(t.getStorageUsed());
        v.setContextConfig(JsonMappers.toJavaValue(t.getContextConfig()));
        v.setWebSearchConfig(JsonMappers.toJavaValue(t.getWebSearchConfig()));
        v.setParserEngineConfig(JsonMappers.toJavaValue(t.getParserEngineConfig()));
        v.setCredentials(JsonMappers.toJavaValue(t.getCredentials()));
        v.setStorageEngineConfig(JsonMappers.toJavaValue(t.getStorageEngineConfig()));
        v.setDefaultStorageBackendId(t.getDefaultStorageBackendId());
        v.setChatHistoryConfig(JsonMappers.toJavaValue(t.getChatHistoryConfig()));
        v.setRetrievalConfig(JsonMappers.toJavaValue(t.getRetrievalConfig()));
        v.setMemoryConfig(JsonMappers.toJavaValue(t.getMemoryConfig()));
        v.setApiPrincipalConfigValue(t.getApiPrincipalConfig());
        v.setCreatedAt(t.getCreatedAt());
        v.setUpdatedAt(t.getUpdatedAt());
        v.setDeletedAt(t.getDeletedAt());
        return v;
    }

    /** 批量（null 安全）。 */
    public static List<TenantRowView> toViews(List<Tenant> rows) {
        if (rows == null) {
            return List.of();
        }
        List<TenantRowView> out = new java.util.ArrayList<>(rows.size());
        for (Tenant t : rows) {
            out.add(toView(t));
        }
        return out;
    }

    /** 视图 → 实体（写面用；经实体 setter；null 透传）。 */
    public static Tenant toEntity(TenantRowView v) {
        if (v == null) {
            return null;
        }
        Tenant t = new Tenant();
        t.setId(v.getId());
        t.setName(v.getName());
        t.setDescription(v.getDescription());
        t.setStatus(v.getStatus());
        t.setRetrieverEngines(JsonMappers.toJsonNode(v.getRetrieverEngines()));
        t.setBusiness(v.getBusiness());
        t.setStorageQuota(v.getStorageQuota());
        t.setStorageUsed(v.getStorageUsed());
        t.setContextConfig(JsonMappers.toJsonNode(v.getContextConfig()));
        t.setWebSearchConfig(JsonMappers.toJsonNode(v.getWebSearchConfig()));
        t.setParserEngineConfig(JsonMappers.toJsonNode(v.getParserEngineConfig()));
        t.setCredentials(JsonMappers.toJsonNode(v.getCredentials()));
        t.setStorageEngineConfig(JsonMappers.toJsonNode(v.getStorageEngineConfig()));
        t.setDefaultStorageBackendId(v.getDefaultStorageBackendId());
        t.setChatHistoryConfig(JsonMappers.toJsonNode(v.getChatHistoryConfig()));
        t.setRetrievalConfig(JsonMappers.toJsonNode(v.getRetrievalConfig()));
        t.setMemoryConfig(JsonMappers.toJsonNode(v.getMemoryConfig()));
        t.setApiPrincipalConfig(v.apiPrincipalConfigValue());
        t.setCreatedAt(v.getCreatedAt());
        t.setUpdatedAt(v.getUpdatedAt());
        t.setDeletedAt(v.getDeletedAt());
        return t;
    }

    /** 落库后回填传入视图（保持"同对象被填充"的旧语义；null 安全）。 */
    public static void copyInto(TenantRowView v, Tenant t) {
        if (v == null || t == null) {
            return;
        }
        TenantRowView fresh = toView(t);
        v.setId(fresh.getId());
        v.setName(fresh.getName());
        v.setDescription(fresh.getDescription());
        v.setStatus(fresh.getStatus());
        v.setRetrieverEngines(fresh.getRetrieverEngines());
        v.setBusiness(fresh.getBusiness());
        v.setStorageQuota(fresh.getStorageQuota());
        v.setStorageUsed(fresh.getStorageUsed());
        v.setContextConfig(fresh.getContextConfig());
        v.setWebSearchConfig(fresh.getWebSearchConfig());
        v.setParserEngineConfig(fresh.getParserEngineConfig());
        v.setCredentials(fresh.getCredentials());
        v.setStorageEngineConfig(fresh.getStorageEngineConfig());
        v.setDefaultStorageBackendId(fresh.getDefaultStorageBackendId());
        v.setChatHistoryConfig(fresh.getChatHistoryConfig());
        v.setRetrievalConfig(fresh.getRetrievalConfig());
        v.setMemoryConfig(fresh.getMemoryConfig());
        v.setApiPrincipalConfigValue(fresh.apiPrincipalConfigValue());
        v.setCreatedAt(fresh.getCreatedAt());
        v.setUpdatedAt(fresh.getUpdatedAt());
        v.setDeletedAt(fresh.getDeletedAt());
    }
}
