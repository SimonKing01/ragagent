package com.ragagent.knowledge.service.management;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.contract.apikey.TenantAPIKeyScope;
import com.ragagent.contract.error.AppError;
import com.ragagent.contract.error.BizException;
import com.ragagent.contract.error.ErrorCode;
import com.ragagent.contract.knowledge.KnowledgeBaseFacts;
import com.ragagent.contract.knowledge.KnowledgeBaseGateway;
import com.ragagent.contract.knowledge.KnowledgeBaseProvisioner;
import com.ragagent.contract.knowledge.KnowledgeBaseSearchFacts;
import com.ragagent.contract.knowledge.KnowledgeBaseSearchGateway;
import com.ragagent.contract.retrieval.IndexEngineResolver;
import com.ragagent.contract.retrieval.RetrieveEngineException;
import com.ragagent.contract.storage.StorageBackendPort;
import com.ragagent.contract.storage.StorageBackendView;
import com.ragagent.contract.storage.StorageEnvLookup;
import com.ragagent.contract.tenant.TenantConfigLookup;
import com.ragagent.contract.tenant.TenantRole;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.domain.KnowledgeBaseIndexingStrategy;
import com.ragagent.knowledge.domain.UserKbPin;
import com.ragagent.knowledge.dto.KnowledgeBaseAccess;
import com.ragagent.knowledge.repository.ChunkRepository;
import com.ragagent.knowledge.repository.KnowledgeBaseRepository;
import com.ragagent.knowledge.repository.KnowledgeRepository;
import com.ragagent.knowledge.repository.UserKbPinRepository;
import com.ragagent.knowledge.support.KnowledgeBaseConfigCodec;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.platform.config.RetrievalDriverProperties;
import com.ragagent.platform.security.LogSanitizer;
import com.ragagent.platform.security.UserNameLookup;
import com.ragagent.platform.web.JsonMappers;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 覆盖 CRUD + pin + move-targets + 计数回填 + clone/copy + clear-contents + 共享访问；
 * 本类保留门面与创建/更新主路径。
 * 默认值链：
 *  ensureDefaults → applyTenantDefaultStorageProvider → applyAndValidateStorageBackend
 */
@Service
public class KnowledgeBaseService
        implements KnowledgeBaseGateway, KnowledgeBaseProvisioner, KnowledgeBaseSearchGateway {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);

    private final KnowledgeBaseRepository kbRepository;
    private final KnowledgeRepository knowledgeRepository;
    private final ChunkRepository chunkRepository;
    private final UserKbPinRepository pinRepository;
    private final StorageBackendPort storageBackendPort;
    private final TenantConfigLookup tenantConfigLookup;
    private final UserNameLookup userNameLookup;
    private final String retrieveDriver;
    private final IndexEngineResolver indexEngineResolver;
    /** 抽取图片回收（删库时逐篇清理）。 */
    private final KnowledgeRecycleService recycleService;

    public KnowledgeBaseService(KnowledgeBaseRepository kbRepository,
                                KnowledgeRepository knowledgeRepository,
                                ChunkRepository chunkRepository,
                                UserKbPinRepository pinRepository,
                                StorageBackendPort storageBackendPort,
                                TenantConfigLookup tenantConfigLookup,
                                UserNameLookup userNameLookup,
                                IndexEngineResolver indexEngineResolver,
                                RetrievalDriverProperties driverProperties,
                                KnowledgeRecycleService recycleService) {
        this.kbRepository = kbRepository;
        this.knowledgeRepository = knowledgeRepository;
        this.chunkRepository = chunkRepository;
        this.pinRepository = pinRepository;
        this.storageBackendPort = storageBackendPort;
        this.tenantConfigLookup = tenantConfigLookup;
        this.userNameLookup = userNameLookup;
        this.indexEngineResolver = indexEngineResolver;
        this.recycleService = recycleService;
        String env = driverProperties.driver();
        this.retrieveDriver = env == null || env.isBlank() ? "postgres" : env;
    }

    public String retrieveDriver() {
        return retrieveDriver;
    }

    private static long tenantId() {
        Long tid = TenantContext.currentTenantId();
        return tid == null ? 0 : tid;
    }

    // ── 创建 ─────────────────────────────────────────────────────────────
    public KnowledgeBase createKnowledgeBase(KnowledgeBase kb) {
        if (kb.getId() == null || kb.getId().isEmpty()) {
            kb.setId(UUID.randomUUID().toString());
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        kb.setCreatedAt(now);
        kb.setUpdatedAt(now);
        kb.setTenantId(tenantId());
        String uid = TenantContext.currentUserId();
        // 合成用户（API key）不记录创建者，KB 归租户所有
        if (uid != null && !uid.startsWith("system-")) {
            kb.setCreatorId(uid);
        }
      ensureDefaults(kb);
        // 下面两行是 null → "" 规整：setter 承担归一，读回再写回即完成（非冗余赋值）
        kb.setEmbeddingModelId(kb.getEmbeddingModelId());
        kb.setSummaryModelId(kb.getSummaryModelId());
        applyTenantDefaultStorageProvider(kb);
        applyAndValidateStorageBackend(kb);
        kb.normalizeVectorStoreId();
        // vector_store_id 绑定校验
        if (kb.hasVectorStore()) {
            validateVectorStoreBinding(tenantId(), kb.getVectorStoreId());
        }
        kbRepository.insert(kb);
        log.info("Knowledge base created successfully, ID: {}, name: {}", kb.getId(), kb.getName());
        return kb;
    }

    /**
     * 走
     * {@code retriever.VerifyBinding} 让归属 + 注册表哨兵层级保持单源。服务层负责：
     * ①畸形 UUID 快拒（省一次 DB 往返，也挡 "' OR 1=1 --" 式类型混淆输入）；②哨兵 →
     * 用户可见的 2200/2201 文案（不含 store UUID——UUID 只进结构化日志，经 sanitizer）。
     */
    public void validateVectorStoreBinding(long tenantId, String storeId) {
        String sanitized = LogSanitizer.sanitize(storeId);
        try {
            UUID.fromString(storeId);
        } catch (IllegalArgumentException e) {
            log.warn("[kb.create] vector store id is not a valid UUID: tenant_id={} store_id={}",
                    tenantId, sanitized);
            throw new BizException(new AppError(
                    ErrorCode.VECTOR_STORE_BINDING_INVALID.value(),
                    "vector store not found", null, 400));
        }
        try {
            indexEngineResolver.verifyBinding(tenantId, storeId);
        } catch (RuntimeException err) {
            if (err instanceof RetrieveEngineException re) {
                switch (re.kind()) {
                    case VECTOR_STORE_FORBIDDEN:
                        log.warn("[kb.create] vector store not owned by tenant: tenant_id={} "
                                + "store_id={}", tenantId, sanitized);
                        throw new BizException(new AppError(
                                ErrorCode.VECTOR_STORE_BINDING_INVALID.value(),
                                "vector store not found", null, 400));
                    case VECTOR_STORE_NOT_FOUND:
                    case VECTOR_STORE_UNAVAILABLE:
                        log.info("[kb.create] vector store currently unavailable: tenant_id={} "
                                + "store_id={}", tenantId, sanitized);
                        throw new BizException(new AppError(
                                ErrorCode.VECTOR_STORE_UNAVAILABLE.value(),
                                "vector store is currently unavailable; check its connection "
                                        + "configuration", null, 400));
                    default:
                        break;
                }
            }
            if (RetrieveEngineException.isCancellation(err)) {
                throw err;
            }
            log.error("[kb.create] binding verification failed: tenant_id={} store_id={}", tenantId, sanitized, err);
            throw new BizException(AppError.internal("failed to verify vector store binding"));
        }
    }
    public static void ensureDefaults(KnowledgeBase kb) {
        if (kb.getType().isEmpty()) {
            kb.setType("document");
        }
        if (!"faq".equals(kb.getType())) {
            kb.setFaqConfig(null);
        }
        if (!"document".equals(kb.getType())) {
            kb.setAutoTagConfig(null);
        }
        // IndexingStrategy 零值 → 默认（vector+keyword）
        if (kb.getIndexingStrategy().allIndexingDisabled()) {
            kb.setIndexingStrategy(KnowledgeBaseIndexingStrategy.defaultStrategy());
        }
        // legacy ExtractConfig.Enabled → GraphEnabled 同步
        if (kb.getExtractConfig() != null && kb.getExtractConfig().path("enabled").asBoolean(false)
                && !kb.getIndexingStrategy().isGraphEnabled()) {
            kb.getIndexingStrategy().setGraphEnabled(true);
        }
    }

    /** 租户默认 → allow-list 首个 */
    private void applyTenantDefaultStorageProvider(KnowledgeBase kb) {
        if (!kb.getStorageProvider().isEmpty()) {
            return;
        }
        String provider = "";
        var storageView = tenantConfigLookup.storageView(tenantId());
        JsonNode storageEngine = storageView == null ? null
                : JsonMappers.toJsonNode(storageView.storageEngineConfig());
        if (storageEngine != null && storageEngine.path("defaultProvider").isTextual()) {
            provider = storageEngine.path("defaultProvider").asText().toLowerCase().trim();
        }
        if (provider.isEmpty() || !isStorageAllowed(provider)) {
            provider = firstAllowedStorage();
        }
        if (provider.isEmpty()) {
            return;
        }
        kb.setStorageProvider(provider);
    }

    /** STORAGE_ALLOW_LIST env（默认仅 local） */
    static boolean isStorageAllowed(String provider) {
        String raw = StorageEnvLookup.get("STORAGE_ALLOW_LIST");
        if (raw == null || raw.isBlank()) {
            return "local".equals(provider);
        }
        for (String p : raw.split(",")) {
            if (p.trim().equalsIgnoreCase(provider)) {
                return true;
            }
        }
        return false;
    }

    static String firstAllowedStorage() {
        String raw = StorageEnvLookup.get("STORAGE_ALLOW_LIST");
        if (raw == null || raw.isBlank()) {
            return "local";
        }
        for (String p : raw.split(",")) {
            if (!p.trim().isEmpty()) {
                return p.trim().toLowerCase();
            }
        }
        return "";
    }

    /**
     * * 显式 id → 租户默认 → provider legacy alias；命中则写回 storageBackendId+provider。
     */
    private void applyAndValidateStorageBackend(KnowledgeBase kb) {
        var storageView = tenantConfigLookup.storageView(tenantId());
        if (storageView == null) {
            throw new BizException(AppError.badRequest("workspace context missing"));
        }
        String id = kb.getStorageBackendId() == null ? "" : kb.getStorageBackendId().trim();
        if (id.isEmpty() && storageView.defaultStorageBackendId() != null
                && !storageView.defaultStorageBackendId().trim().isEmpty()) {
            id = storageView.defaultStorageBackendId().trim();
        }
        StorageBackendView backend = null;
        if (!id.isEmpty()) {
            backend = storageBackendPort.findById(tenantId(), id);
            if (backend == null) {
                throw new BizException(AppError.badRequest("storage backend is unavailable"));
            }
        } else {
            String provider = kb.getStorageProvider();
            if (!provider.isEmpty()) {
                backend = storageBackendPort.findActiveLegacyAliasByProvider(tenantId(), provider);
            }
        }
        if (backend == null) {
            return;
        }
        kb.setStorageBackendId(backend.getId());
        kb.setStorageProvider(backend.getProvider());
    }

    // ── 查询 ─────────────────────────────────────────────────────────────

    /** 本租户 + 未删除 */
    public KnowledgeBase getById(long tid, String id) {
        return kbRepository.findById(tid, id);
    }

    /**
     * 跨域命令端口的实现（{@link KnowledgeBaseProvisioner}）：建聊天历史隐藏 KB。
     *
     * <p>归属调整：实体构造原先发生在聊天历史配置端点（auth），现收回本域；
     * 字段与描述逐字保持原样。</p>
     */
    @Override
    public String provisionChatHistoryKnowledgeBase(String embeddingModelId) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName("__chat_history__");
        kb.setType("document");
        kb.setIsTemporary(true);
        kb.setDescription("Auto-managed knowledge base for chat history message indexing");
        kb.setEmbeddingModelId(embeddingModelId);
        return createKnowledgeBase(kb).getId();
    }

    /**
     * 跨域只读端口的实现（{@link KnowledgeBaseGateway}）。
     *
     * <p>只回填归属事实，**不**调用 {@code ensureDefaults}——那是本域内部语义（字段回填），
     * 端口调用方（audit 的归属守卫、auth 的跨租户校验）不需要，保持与它们原先的裸查询等价。</p>
     */
    @Override
    public KnowledgeBaseFacts findFacts(String knowledgeBaseId) {
        KnowledgeBase kb = kbRepository.findByIdOnly(knowledgeBaseId);
        return kb == null ? null : new KnowledgeBaseFacts(kb.getTenantId(), kb.getCreatorId());
    }

    /**
     * 跨域只读端口的实现（{@link KnowledgeBaseSearchGateway}）：检索引擎编排按 id 取
     * KB 的检索配置。
     *
     * <p>走 {@link #getAllTenantById}（含 {@code ensureDefaults} 的字段回填），
     * 保证"索引策略零值 → vector+keyword 默认"的既有检索行为不变。</p>
     */
    @Override
    public KnowledgeBaseSearchFacts findSearchFacts(String knowledgeBaseId) {
        KnowledgeBase kb = getAllTenantById(knowledgeBaseId);
        if (kb == null) {
            return null;
        }
        KnowledgeBaseIndexingStrategy strategy = kb.getIndexingStrategy();
        return new KnowledgeBaseSearchFacts(
                kb.getId(),
                kb.getTenantId(),
                kb.getType() == null ? "" : kb.getType(),
                kb.getEmbeddingModelId() == null ? "" : kb.getEmbeddingModelId(),
                kb.getVectorStoreId(),
                strategy != null && strategy.isVectorEnabled(),
                strategy != null && strategy.isKeywordEnabled());
    }

    /**
     * * move/copy/duplicate 的 handler 链先按 id 找行、再自己判租户——跨租户行"存在"是
     * 403/404 分歧的前提，不能提前按租户收敛。
     */
    /** 多库检索：白名单 + 租户归属校验后返回可用 id 列表。 */
    public List<String> validatedSearchKbIds(String primaryId, List<String> requested) {
        List<String> ids = requested != null && !requested.isEmpty()
                ? new ArrayList<>(requested) : List.of(primaryId);
        TenantAPIKeyScope.authorizeKnowledgeBases(ids);
        List<KnowledgeBase> rows = new ArrayList<>();
        for (String kbId : ids) {
            KnowledgeBase row = getAllTenantById(kbId);
            if (row != null) {
                rows.add(row);
            }
        }
        if (rows.isEmpty()) {
            throw new BizException(AppError.notFound("knowledge base not found"));
        }
        Long caller = TenantContext.currentTenantId();
        for (KnowledgeBase row : rows) {
            if (row.getTenantId() == null || !row.getTenantId().equals(caller)) {
                throw new BizException(AppError.notFound("knowledge base not found"));
            }
        }
        boolean primaryFound = rows.stream().anyMatch(row -> row.getId().equals(primaryId));
        if (!primaryFound) {
            throw new BizException(AppError.notFound("knowledge base not found"));
        }
        return ids;
    }

    public KnowledgeBaseAccess resolveAccess(String kbId) {
        if (kbId == null || kbId.isEmpty()) {
            throw new BizException(AppError.badRequest("Knowledge base ID cannot be empty"));
        }
        TenantAPIKeyScope.authorizeKnowledgeBases(List.of(kbId));
        KnowledgeBase kb = getAllTenantById(kbId);
        if (kb == null) {
            throw new BizException(AppError.notFound("knowledge base not found"));
        }
        Long caller = TenantContext.currentTenantId();
        if (kb.getTenantId() == null || caller == null || !kb.getTenantId().equals(caller)) {
            throw new BizException(AppError.forbidden(
                    "Permission denied to access this knowledge base"));
        }
        return new KnowledgeBaseAccess(kb.getId(), kb.getTenantId(), kb.getCreatorId());
    }

    public void checkOwnership(String kbId) {
        KnowledgeBase kb = getKnowledgeBase(kbId);
        String role = TenantContext.currentRole();
        String uid = TenantContext.currentUserId();
        boolean admin = TenantRole.fromString(role).hasPermission(TenantRole.ADMIN);
        if (!admin && (kb.getCreatorId() == null || kb.getCreatorId().isEmpty()
                || !kb.getCreatorId().equals(uid))) {
            throw BizException.forbidden("must own the resource or have the required role");
        }
    }

    public KnowledgeBase newTarget(String id, long tenantId, String creatorId) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(id);
        kb.setTenantId(tenantId);
        kb.setCreatorId(creatorId);
        return kb;
    }

    public void validateCloneCompatibilityByIds(String sourceId, String targetId) {
        KnowledgeBase source = getAllTenantById(sourceId);
        KnowledgeBase target = getAllTenantById(targetId);
        if (source == null || target == null) {
            throw new BizException(AppError.notFound("knowledge base not found"));
        }
        KnowledgeService.validateCloneCompatibility(source, target);
    }

    public KnowledgeBase getAllTenantById(String id) {
        KnowledgeBase kb = kbRepository.findByIdOnly(id);
        if (kb != null) {
            ensureDefaults(kb);
        }
        return kb;
    }

    /** 带计数回填 */
    public KnowledgeBase getKnowledgeBase(String id) {
        KnowledgeBase kb = getById(tenantId(), id);
        if (kb == null) {
            throw new BizException(AppError.notFound("knowledge base not found"));
        }
        fillCounts(kb);
        fillPin(kb, TenantContext.currentUserId());
        return kb;
    }

    /**
     * tenant 全量 + is_temporary=false + created_at DESC。不回填 pin/creator_name/
     * share_count（该路径不做）；计数只覆盖类型相关字段（document→
     * knowledge_count、faq→chunk_count），processing 状态只算 pending/processing。
     */
    public List<KnowledgeBase> listKnowledgeBasesByTenantId(long tenantId) {
        List<KnowledgeBase> all = kbRepository.listActiveByTenant(tenantId);
        for (KnowledgeBase kb : all) {
            // 对零值策略原样返回（capabilities 全假），见 W5sSharedAgentContractTest.kbListAgentBranch。
            fillCountsForSharedList(kb);
        }
        return all;
    }

    /** 只覆盖类型相关计数字段，另一个保留库表原值。 */
    private void fillCountsForSharedList(KnowledgeBase kb) {
        if ("document".equals(kb.getType())) {
            Long kc = knowledgeRepository.countByKnowledgeBaseId(kb.getId());
            kb.setKnowledgeCount(kc == null ? 0 : kc);
        } else if ("faq".equals(kb.getType())) {
            Long cc = chunkRepository.countByKnowledgeBaseId(kb.getId());
            kb.setChunkCount(cc == null ? 0 : cc);
        }
        Long pc = knowledgeRepository.countByParseStatuses(kb.getId(),
                Knowledge.PARSE_PENDING, Knowledge.PARSE_PROCESSING);
        kb.setProcessingCount(pc == null ? 0 : pc);
        kb.setIsProcessing(pc != null && pc > 0);
    }

    /**
 * 全量（无分页）+ 计数/置顶/创建者名回填。
 *
 * <p><b>必须排除隐藏库</b>（{@code is_temporary = false}）：否则
 * 「聊天历史」自动开通的 {@code __chat_history__} 会泄漏进列表与所有复用本方法的
 * 消费面（IM {@code /kb}、建议问题范围、QA 范围）—— 点检实锤。
 * 隐藏库的正当访问路径是按配置里的 id 直查（MessageSearch），不依赖本列表。</p>
 */
    public List<KnowledgeBase> listKnowledgeBases(String creator) {
        // 按 created_at DESC 排序，最新在前
        List<KnowledgeBase> all = kbRepository.listActiveByTenant(tenantId());
        String uid = TenantContext.currentUserId();
        List<KnowledgeBase> out = new ArrayList<>(all.size());
        for (KnowledgeBase kb : all) {
            // 线上抓回：缺这一步 ⇒ capabilities() 全假 ⇒ quick-answer 能力过滤
            // 丢弃 KB ⇒ /agents/{id}/suggested-questions 无范围时返回空数组）
            ensureDefaults(kb);
            fillCounts(kb);
            fillPin(kb, uid);
            // creator=mine/others 内存过滤：空 CreatorID 的行两边都不出现
            if (creator != null && !creator.isEmpty()) {
                boolean mine = "mine".equals(creator);
                boolean has = !kb.getCreatorId().isEmpty();
                if (mine != has) {
                    continue;
                }
            }
            if (!kb.getCreatorId().isEmpty()) {
                String creatorName = userNameLookup.usernameOf(kb.getCreatorId());
                if (creatorName != null) {
                    kb.setCreatorName(creatorName);
                }
            }
            out.add(kb);
        }
        return out;
    }

    /** knowledge_count/chunk_count/is_processing/processing_count */
    private void fillCounts(KnowledgeBase kb) {
        Long kc = knowledgeRepository.countByKnowledgeBaseId(kb.getId());
        Long cc = chunkRepository.countByKnowledgeBaseId(kb.getId());
        Long pc = knowledgeRepository.countByParseStatuses(kb.getId(),
                Knowledge.PARSE_PENDING, Knowledge.PARSE_PROCESSING, Knowledge.PARSE_FINALIZING);
        kb.setKnowledgeCount(kc == null ? 0 : kc);
        kb.setChunkCount(cc == null ? 0 : cc);
        kb.setProcessingCount(pc == null ? 0 : pc);
        kb.setIsProcessing(pc != null && pc > 0);
        kb.setShareCount(0); // 共享（kb_shares）属组织模块，暂未接入，恒为 0
    }

    private void fillPin(KnowledgeBase kb, String uid) {
        if (uid == null || uid.isEmpty()) {
            return;
        }
        UserKbPin pin = pinRepository.findByUserAndKb(tenantId(), uid, kb.getId());
        if (pin != null) {
            kb.setIsPinned(true);
            kb.setPinnedAt(pin.getPinnedAt());
        }
    }

    // ── 更新 / 删除 / 置顶 ────────────────────────────────────────────────

    public KnowledgeBase updateKnowledgeBase(KnowledgeBase existing,
                                             String name, String description,
                                             JsonNode config) {
        if (name != null && !name.isEmpty()) {
            existing.setName(name);
        }
        if (description != null) {
            existing.setDescription(description);
        }
        if (config != null) {
            applyUpdateConfig(existing, config);
        }
        existing.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        kbRepository.updateById(existing);
        fillCounts(existing);
        fillPin(existing, TenantContext.currentUserId());
        return existing;
    }

    /**
     * 更新请求的 {@code config} 内层 dispatch 键＝Java 字段名（camelCase），与创建请求的
     * {@code CreateKnowledgeBaseRequest} 同名同形；落库面的值类型也按 Java 字段名读写。
     */
    private static void applyUpdateConfig(KnowledgeBase kb, JsonNode config) {
        if (config.hasNonNull("chunkingConfig")) {
            kb.setChunkingConfig(KnowledgeBaseConfigCodec.chunkingFrom(config.get("chunkingConfig")));
        }
        if (config.hasNonNull("imageProcessingConfig")) {
            kb.setImageProcessingConfig(
                    KnowledgeBaseConfigCodec.imageProcessingFrom(config.get("imageProcessingConfig")));
        }
        if (config.hasNonNull("faqConfig")) {
            kb.setFaqConfig(config.get("faqConfig"));
        }
        if (config.hasNonNull("wikiConfig")) {
            kb.setWikiConfig(config.get("wikiConfig"));
        }
        if (config.hasNonNull("autoTagConfig")) {
            kb.setAutoTagConfig(config.get("autoTagConfig"));
        }
        // indexingStrategy：引用语义 null=不变；hasAnyIndexing 为 false → 400
        if (config.has("indexingStrategy") && config.get("indexingStrategy") != null
                && config.get("indexingStrategy").isObject()) {
            KnowledgeBaseIndexingStrategy strategy = KnowledgeBaseConfigCodec.indexingStrategyFrom(config.get("indexingStrategy"));
            if (!strategy.hasAnyIndexing()) {
                throw new BizException(AppError.badRequest("at least one indexing strategy must be enabled"));
            }
            kb.setIndexingStrategy(strategy);
            // wiki/graph 联动
            if (kb.getWikiConfig() == null && strategy.isWikiEnabled()) {
                ObjectMapper m = JsonMappers.lenient();
                kb.setWikiConfig(m.createObjectNode());
            }
            if (kb.getExtractConfig() != null && kb.getExtractConfig().isObject()) {
                ObjectNode ec =
                        (ObjectNode) kb.getExtractConfig();
                ec.put("enabled", strategy.isGraphEnabled());
            }
        }
    }

    public void deleteKnowledgeBase(String id) {
        KnowledgeBase kb = getById(tenantId(), id);
        if (kb == null) {
            throw new BizException(AppError.notFound("knowledge base not found"));
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        // 文档侧交给统一回收用例（软删 + 文件/图片/索引），再做 KB 行本身的软删。
        recycleService.recycleKnowledgeBaseContents(id);
        kbRepository.softDelete(id, now);
        knowledgeRepository.softDeleteByKnowledgeBaseId(id, now);
        chunkRepository.softDeleteByKnowledgeBaseId(id, now);
        pinRepository.deleteByKnowledgeBaseId(id);
        log.info("Knowledge base deleted: {}", id);
    }

    /** per-(user,kb) 幂等切换 */
    public KnowledgeBase togglePin(String id) {
        KnowledgeBase kb = getById(tenantId(), id);
        if (kb == null) {
            throw new BizException(AppError.notFound("knowledge base not found"));
        }
        String uid = TenantContext.currentUserId();
        long tid = tenantId();
        UserKbPin existing = pinRepository.findByUserAndKb(tid, uid, id);
        if (existing != null) {
            pinRepository.deleteByUserAndKb(tid, uid, id);
        } else {
            UserKbPin pin = new UserKbPin();
            pin.setTenantId(tid);
            pin.setUserId(uid);
            pin.setKnowledgeBaseId(id);
            pin.setPinnedAt(OffsetDateTime.now(ZoneOffset.UTC));
            pinRepository.insert(pin);
        }
        fillCounts(kb);
        fillPin(kb, uid);
        return kb;
    }

    /** 同 type + 同 embedding_model_id + 非临时 + 排除自身 */
    public List<KnowledgeBase> listMoveTargets(String id) {
        KnowledgeBase source = getById(tenantId(), id);
        if (source == null) {
            throw new BizException(AppError.notFound("knowledge base not found"));
        }
        return kbRepository.listMoveTargets(tenantId(), source.getType(),
                source.getEmbeddingModelId() == null ? "" : source.getEmbeddingModelId(), id);
    }
}
