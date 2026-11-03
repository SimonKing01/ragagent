package com.ragagent.vectorstore.service;

import com.ragagent.contract.error.AppError;
import com.ragagent.contract.error.BizException;
import com.ragagent.contract.vectorstore.ConnectionConfig;
import com.ragagent.contract.vectorstore.IndexConfig;
import com.ragagent.contract.vectorstore.VectorStoreAddressField;
import com.ragagent.contract.vectorstore.VectorStoreEngineCatalogPort;
import com.ragagent.contract.vectorstore.VectorStoreEngineRules;
import com.ragagent.contract.vectorstore.VectorStoreRequiredField;
import com.ragagent.platform.config.RetrievalDriverProperties;
import com.ragagent.platform.security.SsrfGuard;
import com.ragagent.vectorstore.config.EnvVectorStores;
import com.ragagent.vectorstore.domain.VectorStore;
import com.ragagent.vectorstore.domain.VectorStoreEngines;
import com.ragagent.vectorstore.dto.VectorStoreResponse;
import com.ragagent.vectorstore.repository.VectorStoreRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 向量库配置服务（CRUD + 连通性探测 + 健康检查）。命名带 Config 以区别
 * retrieval.engine.VectorStoreService（检索引擎的 embeddings 索引写面）。
 *
 * <p>校验顺序固定：Validate → validateConnectionConfig → validateConnectionAddrSSRF →
 * ValidateIndexConfig → OpenSearch HNSW → DB 去重 → env 去重 → TestConnection（失败=
 * "connection test failed: ..."）→ 落库 → 注册（进程内注册表当前 no-op）。</p>
 *
 * <p><b>TestConnection 的引擎覆盖</b>：规则与探测体都在 retrieval-engine 的
 * {@code VectorStoreEngineCatalog}（各引擎的差异与"错误文案逐字一致"的约定随迁，
 * 见那边的类说明）。本类只剩执行规则（必填 + 地址 SSRF）与对外错误包装。</p>
 */
@Service
public class VectorStoreConfigService {

    private static final int MAX_SHARDS = 64;
    private static final int MAX_REPLICAS = 10;
    private static final Pattern INDEX_NAME_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_-]{0,127}$");

    private final VectorStoreRepository repo;
    private final SsrfGuard ssrfGuard;

    /** 引擎目录（规则与探测体在 retrieval-engine，本类只做校验 + 委托 + 包装）。 */
    private final VectorStoreEngineCatalogPort engineCatalog;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    /** 构造期派生一次的 env stores */
    private final EnvVectorStores.EnvLookup envLookup;

    /** RETRIEVE_DRIVER（属性绑定，不读裸 env）。 */
    private final RetrievalDriverProperties driverProperties;

    private final List<VectorStore> envStores;

    public VectorStoreConfigService(VectorStoreRepository repo, SsrfGuard ssrfGuard,
            VectorStoreEngineCatalogPort engineCatalog,
            JdbcTemplate jdbc, TransactionTemplate tx,
            RetrievalDriverProperties driverProperties, EnvVectorStores.EnvLookup envLookup) {
        this.repo = repo;
        this.ssrfGuard = ssrfGuard;
        this.engineCatalog = engineCatalog;
        this.jdbc = jdbc;
        this.tx = tx;
        this.envLookup = envLookup;
        this.driverProperties = driverProperties;
        this.envStores = EnvVectorStores.build(driverProperties.driver(), envLookup);
    }

    public List<VectorStore> envStores() {
        return envStores;
    }

    /**
     * 按 id 取进程级（{@code __env_*}）向量库；不存在返回 null。
     *
     * <p>env 族查找面由 {@link EnvVectorStores.EnvLookup} bean 提供（调用方
     * 不再自传 {@code System::getenv}）。</p>
     */
    public VectorStore findEnvStore(String id) {
        return EnvVectorStores.find(driverProperties.driver(), envLookup, id);
    }

    public VectorStore getById(long tenantId, String id) {
        return repo.getById(tenantId, id);
    }

    public List<VectorStore> list(long tenantId) {
        return repo.list(tenantId);
    }

    // ── 控制面读模型（控制器不再 import VectorStore） ────────────────────

    public List<VectorStoreResponse> listResponses(long tenantId) {
        List<VectorStoreResponse> all = new ArrayList<>();
        for (VectorStore env : envStores()) {
            all.add(VectorStoreResponse.of(env, "env", true));
        }
        for (VectorStore store : list(tenantId)) {
            all.add(VectorStoreResponse.of(store, "user", false));
        }
        return all;
    }

    /** {@code null} = 不存在。 */
    public VectorStoreResponse getResponse(long tenantId,
            String id) {
        if (EnvVectorStores.isEnvStoreId(id)) {
            VectorStore env = findEnvStore(id);
            return env == null ? null
                    : VectorStoreResponse.of(env, "env", true);
        }
        VectorStore store = getById(tenantId, id);
        return store == null ? null
                : VectorStoreResponse.of(store, "user", false);
    }

    public VectorStoreResponse createResponse(long tenantId,
            String name, String engineType, ConnectionConfig connection,
            IndexConfig index) {
        VectorStore store = new VectorStore();
        store.setId(UUID.randomUUID().toString());
        store.setTenantId(tenantId);
        store.setName(name);
        store.setEngineType(engineType);
        store.setConnectionConfig(connection);
        store.setIndexConfig(index);
        create(store);
        return VectorStoreResponse.of(store, "user", false);
    }

    public VectorStoreResponse updateNameResponse(long tenantId,
            String id, String name) {
        VectorStore updated = new VectorStore();
        updated.setId(id);
        updated.setTenantId(tenantId);
        updated.setName(name);
        updateName(updated);
        VectorStore result = getById(tenantId, id);
        return result == null ? null
                : VectorStoreResponse.of(result, "user", false);
    }

    /** 探测连通性；{@code null} = 不存在，空串 = 探测成功但无版本号。 */
    public String testResponse(long tenantId, String id) {
        if (EnvVectorStores.isEnvStoreId(id)) {
            VectorStore env = findEnvStore(id);
            return env == null ? null
                    : testConnection(env.getEngineType(), env.getConnectionConfig());
        }
        VectorStore store = getById(tenantId, id);
        if (store == null) {
            return null;
        }
        String version = testConnection(store.getEngineType(), store.getConnectionConfig());
        if (!version.isEmpty() && !version.equals(store.getConnectionConfig().version)) {
            try {
                store.getConnectionConfig().version = version;
                saveDetectedVersion(store);
            } catch (RuntimeException ignored) {
                // 仅记 WARN 后继续（不影响响应）
            }
        }
        return version;
    }

    /** 在副本上写 connection_config.version */
    public void saveDetectedVersion(VectorStore store) {
        repo.updateConnectionConfig(store);
    }

    /** 创建向量库（步骤注释为固定序）。 */
    public void create(VectorStore store) {
        // 1. 基础校验
        store.validate();
        // 2. 引擎专属连接必填
        validateConnectionConfig(store.getEngineType(), store.getConnectionConfig());
        // 2.1 SSRF（先于一切网络 I/O）
        validateConnectionAddrSSRF(store.getEngineType(), store.getConnectionConfig());
        // 2.5 IndexConfig 边界/名字
        validateIndexConfig(store.getIndexConfig());
        // 2.6 OpenSearch HNSW（仅 create）
        if ("opensearch".equals(store.getEngineType())) {
            validateOpenSearchIndexConfig(store.getIndexConfig());
        }
        // 3. DB 去重（应用层比较——JSONB 抽取语法因库而异）
        String endpoint = store.getConnectionConfig().getEndpoint();
        String indexName = store.getIndexConfig().getIndexNameOrDefault(store.getEngineType());
        for (VectorStore s : repo.list(store.getTenantId())) {
            if (s.getEngineType().equals(store.getEngineType())
                    && s.getConnectionConfig().getEndpoint().equals(endpoint)
                    && s.getIndexConfig().getIndexNameOrDefault(store.getEngineType()).equals(indexName)) {
                throw BizException.conflict("a vector store with the same endpoint and index already exists");
            }
        }
        // 4. env 去重
        for (VectorStore envStore : envStores) {
            if (envStore.getEngineType().equals(store.getEngineType())
                    && envStore.getConnectionConfig().getEndpoint().equals(endpoint)
                    && envStore.getIndexConfig().getIndexNameOrDefault(store.getEngineType()).equals(indexName)) {
                throw BizException.conflict(
                        "a vector store with the same endpoint and index is already configured via environment variables");
            }
        }
        // 5. 连接测试（版本探测）：err → NewBadRequestError("connection test failed: %s. Ensure ...")
        String version;
        try {
            version = testConnection(store.getEngineType(), store.getConnectionConfig());
        } catch (BizException e) {
            throw BizException.badRequest("connection test failed: " + e.getMessage()
                    + ". Ensure the server is reachable before saving.");
        }
        if (!version.isEmpty()) {
            store.getConnectionConfig().version = version;
        }
        // 6. 落库
        repo.create(store, java.time.OffsetDateTime.now());
        // 7. 进程内注册表（引擎工厂未实现，当前 no-op）
    }

    public void updateName(VectorStore store) {
        if (store.getTenantId() == null || store.getTenantId() == 0) {
            throw validation("tenant_id is required");
        }
        if (store.getName() == null || store.getName().isEmpty()) {
            throw validation("name is required");
        }
        repo.updateName(store, java.time.OffsetDateTime.now());
    }

    /** 事务内行锁（PG FOR UPDATE，H2 无锁提示）+ 绑定计数 → 软删 */
    public void delete(long tenantId, String id) {
        Integer deleted = tx.execute(status -> {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM vector_stores WHERE id = ? AND tenant_id = ? AND deleted_at IS NULL",
                    Integer.class, id, tenantId);
            if (count == null || count == 0) {
                throw BizException.notFound("vector store not found");
            }
            Integer bound = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM knowledge_bases WHERE tenant_id = ? AND vector_store_id = ? "
                            + "AND deleted_at IS NULL",
                    Integer.class, tenantId, id);
            if (bound != null && bound > 0) {
                throw BizException.badRequest("vector store still has " + bound
                        + " knowledge base(s) bound to it; unbind or delete them before removing the store");
            }
            jdbc.update("UPDATE vector_stores SET deleted_at = NOW() "
                    + "WHERE id = ? AND tenant_id = ? AND deleted_at IS NULL", id, tenantId);
            return 1;
        });
        if (deleted == null || deleted == 0) {
            throw BizException.notFound("vector store not found");
        }
        // 进程内注册表未实现，当前 no-op
    }

    /** 白名单 → 必填 → SSRF → TestConnection。失败抛 AppError */
    public String testRawConnection(String engineType, ConnectionConfig config) {
        if (!VectorStoreEngines.isValidEngineType(engineType)) {
            throw validation("connection test is not supported for engine type: " + engineType);
        }
        validateConnectionConfig(engineType, config);
        validateConnectionAddrSSRF(engineType, config);
        return testConnection(engineType, config);
    }

    /**
     * 连通性测试：成功返回探测版本（可空），失败抛 AppError（各分支 code 1000，
     * handler 以**双前缀**形态输出——golden vs-test-byid-connrefused 钉住）。
     *
     * <p>探测体（9 个分支 + 各厂商驱动探针）在 {@code retrieval-engine}，
     * 经 {@link VectorStoreEngineCatalogPort} 调用；本方法保留为对外同名入口（行为与文案不变）。</p>
     */
    public String testConnection(String engineType, ConnectionConfig config) {
        return engineCatalog.probe(engineType, config);
    }

    // ── 校验（文案逐字对照） ────────────────────────────────────────────

    /** 连接配置必填校验（规则由引擎目录提供；未登记引擎放行，白名单在 validate() 已拦）。 */
    public void validateConnectionConfig(String engineType, ConnectionConfig config) {
        ConnectionConfig c = config == null ? new ConnectionConfig() : config;
        VectorStoreEngineRules rules = engineCatalog.rulesFor(engineType);
        if (rules == null) {
            return;
        }
        for (VectorStoreRequiredField field : rules.requiredFields()) {
            if (!field.satisfied().test(c)) {
                throw validation(field.message());
            }
        }
    }

    /** 逐地址字段的 SSRF 校验（白名单优先），未登记引擎 fail-closed。 */
    public void validateConnectionAddrSSRF(String engineType, ConnectionConfig config) {
        ConnectionConfig c = config == null ? new ConnectionConfig() : config;
        VectorStoreEngineRules rules = engineCatalog.rulesFor(engineType);
        if (rules == null) {
            throw validation("SSRF validation is not configured for engine type: " + engineType);
        }
        for (VectorStoreAddressField field : rules.addressFields()) {
            checkAddr(field.value().apply(c));
        }
    }

    private void checkAddr(String addr) {
        if (empty(addr)) {
            return;
        }
        try {
            ssrfGuard.validateURLForSSRF(addr);
        } catch (SsrfGuard.SsrfException e) {
            throw validation(ssrfGuard.formatSSRFError("vector store address", addr, e));
        }
    }

    /** 索引配置校验 */
    public void validateIndexConfig(IndexConfig ic) {
        IndexConfig c = ic == null ? new IndexConfig() : ic;
        if (notEmpty(c.indexName) && !INDEX_NAME_PATTERN.matcher(c.indexName).matches()) {
            throw validation("index_name must start with a letter and contain only alphanumeric, "
                    + "underscore, or hyphen characters (max 128)");
        }
        if (notEmpty(c.collectionPrefix) && !INDEX_NAME_PATTERN.matcher(c.collectionPrefix).matches()) {
            throw validation("collection_prefix must start with a letter and contain only alphanumeric, "
                    + "underscore, or hyphen characters (max 128)");
        }
        if (notEmpty(c.collectionName) && !INDEX_NAME_PATTERN.matcher(c.collectionName).matches()) {
            throw validation("collection_name must start with a letter and contain only alphanumeric, "
                    + "underscore, or hyphen characters (max 128)");
        }
        checkRange("number_of_shards", c.numberOfShards, MAX_SHARDS);
        checkRange("number_of_replicas", c.numberOfReplicas, MAX_REPLICAS);
        checkRange("shard_number", c.shardNumber, MAX_SHARDS);
        checkRange("replication_factor", c.replicationFactor, MAX_REPLICAS);
        checkRange("shards_num", c.shardsNum, MAX_SHARDS);
        checkRange("replica_number", c.replicaNumber, MAX_REPLICAS);
        checkRange("desired_shard_count", c.desiredShardCount, MAX_SHARDS);
        checkRange("buckets_num", c.bucketsNum, MAX_SHARDS);
        checkRange("replication_num", c.replicationNum, MAX_REPLICAS);
    }

    private static void checkRange(String name, int value, int max) {
        if (value < 0 || value > max) {
            throw validation(name + " must be between 0 and " + max);
        }
    }

    /** OpenSearch 索引配置校验（HNSW 边界） */
    public void validateOpenSearchIndexConfig(IndexConfig ic) {
        IndexConfig c = ic == null ? new IndexConfig() : ic;
        if (c.hnswM != 0 && (c.hnswM < 2 || c.hnswM > 100)) {
            throw validation("hnsw_m must be between 2 and 100");
        }
        if (c.hnswEfConstruction != 0 && (c.hnswEfConstruction < 2 || c.hnswEfConstruction > 4096)) {
            throw validation("hnsw_ef_construction must be between 2 and 4096");
        }
        if (c.hnswEfSearch != 0 && (c.hnswEfSearch < 1 || c.hnswEfSearch > 10000)) {
            throw validation("hnsw_ef_search must be between 1 and 10000");
        }
        if (notEmpty(c.knnEngine) && !c.knnEngine.equals("lucene") && !c.knnEngine.equals("faiss")) {
            // 文案含真实引号（错误契约的一部分）
            throw validation("knn_engine must be \"lucene\" or \"faiss\"");
        }
    }

    // ── 小工具 ─────────────────────────────────────────────────────────

    private static boolean empty(String s) {
        return s == null || s.isEmpty();
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    private static BizException validation(String message) {
        return new BizException(AppError.validation(message));
    }
}
