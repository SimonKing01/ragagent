package com.ragagent.initialization.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.contract.apikey.APIKeyScopeContext;
import com.ragagent.contract.error.AppError;
import com.ragagent.contract.error.BizException;
import com.ragagent.contract.knowledge.KnowledgeBaseRowView;
import com.ragagent.contract.knowledge.KnowledgeCatalogPort;
import com.ragagent.contract.knowledge.config.AsrConfigView;
import com.ragagent.contract.knowledge.config.ChunkingConfigView;
import com.ragagent.contract.knowledge.config.IndexingStrategyView;
import com.ragagent.contract.knowledge.config.VlmConfigView;
import com.ragagent.contract.model.ModelCatalogPort;
import com.ragagent.contract.model.ModelView;
import com.ragagent.contract.storage.StorageBackendPort;
import com.ragagent.contract.storage.StorageBackendView;
import com.ragagent.contract.tenant.TenantRole;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.platform.web.JsonBodies;
import com.ragagent.platform.web.JsonMappers;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

/**
 * initialization config 端点用例：KB 配置读写与请求绑定。
 *
 * <p>模型资源的创建/修改不在此处——只走模型主资源（Admin+、凭据脱敏）。</p>
 */
@Service
public final class InitializationConfigService {

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    private final KnowledgeCatalogPort knowledgeCatalog;
    private final ModelCatalogPort modelCatalog;
    private final StorageBackendPort storageBackendPort;

    public InitializationConfigService(KnowledgeCatalogPort knowledgeCatalog,
            ModelCatalogPort modelCatalog,
            StorageBackendPort storageBackendPort) {
        this.knowledgeCatalog = knowledgeCatalog;
        this.modelCatalog = modelCatalog;
        this.storageBackendPort = storageBackendPort;
    }

    // ── GET /initialization/config/:kbId ──

    public Map<String, Object> getConfig(String kbId) {
        knowledgeCatalog.requireKbAccess(kbId);
        KnowledgeBaseRowView kb = knowledgeCatalog.getAllTenantById(kbId);
        if (kb == null) {
            throw new BizException(AppError.notFound("知识库不存在"));
        }
        List<ModelView> models = new ArrayList<>();
        for (String id : List.of(JsonBodies.orEmpty(kb.getEmbeddingModelId()), JsonBodies.orEmpty(kb.getSummaryModelId()),
                JsonBodies.orEmpty(kb.getVlmConfig().modelId()))) {
            if (id.isEmpty()) {
                continue;
            }
            try {
                ModelView m = modelCatalog.getModelRow(id);
                if (m != null) {
                    models.add(m);
                }
            } catch (Exception ignored) {
                // 单个模型查询失败 → 跳过，继续处理其余模型
            }
        }
        return configResponse(models, kb, hasFiles(kbId));
    }

    // ── PUT /initialization/config/:kbId ──

    public Map<String, Object> updateConfig(String kbId,
            String rawBody) {
        InitializationRequests.KBModelConfigRequest req = InitializationRequests.bindKBModelConfigRequest(rawBody);
        knowledgeCatalog.requireKbAccess(kbId);
        requireOwned(kbId);
        KnowledgeBaseRowView kb = knowledgeCatalog.getAllTenantById(kbId);
        if (kb == null) {
            throw new BizException(AppError.notFound("知识库不存在"));
        }
        if (req.name() != null && !req.name().isBlank()) {
            kb.setName(req.name().trim());
        }
        if (req.description() != null) {
            kb.setDescription(req.description());
        }

        // Embedding 变更 + 已有文件 → 400（先于模型校验）
        if (!kb.getEmbeddingModelId().isEmpty() && !req.embeddingModelId().isEmpty()
                && !kb.getEmbeddingModelId().equals(req.embeddingModelId())
                && hasFiles(kbId)) {
            throw new BizException(AppError.badRequest("知识库中已有文件，无法修改Embedding模型"));
        }
        requireModel(req.llmModelId(), "LLM模型不存在");
        if (!req.embeddingModelId().isEmpty()) {
            requireModel(req.embeddingModelId(), "Embedding模型不存在");
        }

        kb.setSummaryModelId(req.llmModelId());
        if (!req.embeddingModelId().isEmpty()) {
            kb.setEmbeddingModelId(req.embeddingModelId());
        }

        // VLM / ASR 重置后再按请求回填（收集为契约视图，末尾统一回写）
        boolean vlmEnabled = false;
        String vlmModelId = "";
        JsonNode vlmReq = req.vlmConfig();
        if (vlmReq != null && req.multimodalEnabled() && !vlmReq.path("modelId").asText("").isEmpty()) {
            String candidate = vlmReq.path("modelId").asText("");
            try {
                if (modelCatalog.findModel(candidate) != null) {
                    vlmEnabled = vlmReq.path("enabled").asBoolean(false);
                    vlmModelId = candidate;
                }
            } catch (Exception ignored) {
                // 查询失败 → 视为模型不存在，VLM 保持禁用
            }
        }
        if (!vlmEnabled) {
            vlmModelId = "";
        }
        boolean asrEnabled = false;
        String asrModelId = "";
        String asrLanguage = "";
        JsonNode asrReq = req.asrConfig();
        if (asrReq != null && asrReq.path("enabled").asBoolean(false)
                && !asrReq.path("modelId").asText("").isEmpty()) {
            try {
                if (modelCatalog.findModel(asrReq.path("modelId").asText()) != null) {
                    asrEnabled = true;
                    asrModelId = asrReq.path("modelId").asText();
                    asrLanguage = asrReq.path("language").asText("");
                }
            } catch (Exception ignored) {
                // 查询失败 → 忽略，ASR 保持未启用
            }
        }
        kb.setAsrConfig(new AsrConfigView(asrEnabled, asrModelId, asrLanguage));

        // 文档分块（未提供的键保持现值；未生效标注按最终值重算）
        ChunkingConfigView curChunking = kb.getChunkingConfig();
        int chunkSize = req.chunkSize() > 0 ? req.chunkSize() : curChunking.chunkSize();
        int chunkOverlap = req.chunkOverlap() >= 0 ? req.chunkOverlap() : curChunking.chunkOverlap();
        List<String> separators = req.separators() != null && !req.separators().isEmpty()
                ? req.separators() : curChunking.separators();
        List<ChunkingConfigView.ParserEngineRuleView> parserRules = req.parserEngineRules();
        boolean enableParentChild = req.enableParentChild();
        int parentChunkSize = req.parentChunkSize() != null && req.parentChunkSize() > 0
                ? req.parentChunkSize() : curChunking.parentChunkSize();
        int childChunkSize = req.childChunkSize() != null && req.childChunkSize() > 0
                ? req.childChunkSize() : curChunking.childChunkSize();
        String strategy = req.strategy() != null ? req.strategy() : curChunking.strategy();
        int tokenLimit = req.tokenLimit() != null ? req.tokenLimit() : curChunking.tokenLimit();
        List<String> languages = req.languages() != null ? req.languages() : curChunking.languages();
        String tableMetadata = req.tableMetadataInstructions() != null
                ? req.tableMetadataInstructions().trim() : curChunking.tableMetadataInstructions();
        kb.setChunkingConfig(new ChunkingConfigView(chunkSize, chunkOverlap, separators, parserRules,
                enableParentChild, parentChunkSize, childChunkSize, strategy, tokenLimit, languages,
                tableMetadata, ChunkingConfigView.inactiveKeys(enableParentChild, parentChunkSize,
                        childChunkSize, tableMetadata)));

        if (!req.multimodalEnabled()) {
            vlmModelId = "";
        }
        String vlmDescLang = null;
        String vlmCustomInstr = null;
        if (vlmReq != null) {
            vlmDescLang = vlmReq.path("descriptionLanguage").asText("").trim();
            vlmCustomInstr = vlmReq.path("customInstructions").asText("").trim();
        }
        kb.setVlmConfig(new VlmConfigView(vlmEnabled, vlmModelId, vlmDescLang, vlmCustomInstr,
                "", "", ""));

        applyStorageAndBasic(kb, req);

        // 知识图谱
        if (req.nodeExtractEnabled()) {
            ObjectNode extract = MAPPER.createObjectNode();
            extract.put("enabled", true);
            extract.put("text", req.nodeExtractText());
            extract.set("tags", MAPPER.valueToTree(req.nodeExtractTags()));
            extract.set("nodes", MAPPER.valueToTree(req.nodeExtractNodes()));
            extract.set("relations", MAPPER.valueToTree(req.nodeExtractRelations()));
            extract.put("customInstructions", req.nodeExtractCustomInstructions().trim());
            kb.setExtractConfig(extract);
        } else if (kb.getExtractConfig() != null) {
            ((ObjectNode) kb.getExtractConfig()).put("enabled", false);
        } else {
            ObjectNode extract = MAPPER.createObjectNode();
            extract.put("enabled", false);
            kb.setExtractConfig(extract);
        }

        // 问题生成
        ObjectNode qg = MAPPER.createObjectNode();
        if (req.questionGenerationEnabled()) {
            int count = req.questionGenerationCount();
            if (count <= 0) {
                count = 3;
            }
            if (count > 10) {
                count = 10;
            }
            qg.put("enabled", true);
            qg.put("questionCount", count);
            qg.put("customInstructions", req.questionGenerationInstructions().trim());
        } else {
            qg.put("enabled", false);
            qg.put("customInstructions", req.questionGenerationInstructions().trim());
        }
        kb.setQuestionGenerationConfig(qg);

        saveKb(kb);
        return java.util.Collections.singletonMap("message", "配置更新成功");
    }

    /** RFC-3339 纳秒精度 + 服务器本地时区（同 ZeroTimeSerializer 逻辑）。 */

    static Map<String, String> toStringMap(JsonNode n) {
        if (n == null || !n.isObject()) {
            return null;
        }
        Map<String, String> out = new TreeMap<>();
        for (var e : n.properties()) {
            out.put(e.getKey(), e.getValue().isNull() ? null : e.getValue().asText(""));
        }
        return out;
    }






    // ── 守卫与校验 ──

    private void requireOwned(String kbId) {
        KnowledgeBaseRowView kb = knowledgeCatalog.getAllTenantById(kbId);
        if (kb == null) {
            throw new BizException(AppError.notFound("knowledge base not found"));
        }
        String role = TenantContext.currentRole();
        boolean admin = TenantRole.fromString(role == null ? "" : role)
                .hasPermission(TenantRole.ADMIN);
        String uid = TenantContext.currentUserId() == null ? "" : TenantContext.currentUserId();
        String creator = kb.getCreatorId() == null ? "" : kb.getCreatorId();
        if (!admin && (creator.isEmpty() || !creator.equals(uid))) {
            throw BizException.forbidden("must own the resource or have the required role");
        }
    }


    /** 存储后端绑定 + 基本信息/索引策略（一次命令的其余权威字段）。 */
    private void applyStorageAndBasic(KnowledgeBaseRowView kb,
            InitializationRequests.KBModelConfigRequest req) {
        // 存储后端：storageBackendId 是权威字段；storageProvider 仅兼容投影。
        // 空值 = 保留当前绑定（不静默清空）；显式 id 必须属于本租户且 active。
        Long tid = TenantContext.currentTenantId();
        long tenantId = tid == null ? 0L : tid;
        String backendId = req.storageBackendId() == null ? "" : req.storageBackendId().trim();
        if (!backendId.isEmpty()) {
            StorageBackendView backend = storageBackendPort.findById(tenantId, backendId);
            if (backend == null || !"active".equals(backend.getStatus())) {
                throw new BizException(AppError.badRequest("storage backend is unavailable"));
            }
            kb.setStorageBackendId(backend.getId());
            kb.setStorageProvider(backend.getProvider());
        } else if (req.storageProvider() != null && !req.storageProvider().trim().isEmpty()) {
            String provider = req.storageProvider().trim().toLowerCase(java.util.Locale.ROOT);
            // 兼容旧客户端的白名单校验（STORAGE_ALLOW_LIST 语义）
            List<String> supported = List.of("local", "minio", "cos", "tos", "s3", "oss", "ks3", "obs");
            if (!supported.contains(provider)) {
                throw new BizException(AppError.badRequest(
                        "Storage provider is not allowed by STORAGE_ALLOW_LIST"));
            }
            StorageBackendView backend = storageBackendPort.findActiveLegacyAliasByProvider(tenantId, provider);
            if (backend != null) {
                kb.setStorageBackendId(backend.getId());
                kb.setStorageProvider(backend.getProvider());
            } else {
                kb.setStorageProvider(provider);
            }
        }

        // 基本信息/索引策略合并进本命令：一次请求提交全部权威字段，避免两次调用部分成功
        if (req.faqConfig() != null) {
            kb.setFaqConfig(req.faqConfig());
        }
        if (req.wikiConfig() != null) {
            kb.setWikiConfig(req.wikiConfig());
        }
        if (req.autoTagConfig() != null) {
            kb.setAutoTagConfig(req.autoTagConfig());
        }
        if (req.indexingStrategy() != null) {
            JsonNode is = req.indexingStrategy();
            kb.setIndexingStrategy(new IndexingStrategyView(
                    is.path("vectorEnabled").asBoolean(true),
                    is.path("keywordEnabled").asBoolean(true),
                    is.path("wikiEnabled").asBoolean(false),
                    is.path("graphEnabled").asBoolean(false)));
        }
    }

    private void saveKb(KnowledgeBaseRowView kb) {
        knowledgeCatalog.updateKnowledgeBase(kb);
    }

    // ── GET config 响应（全 map 字母序） ──

    private Map<String, Object> configResponse(List<ModelView> models, KnowledgeBaseRowView kb,
            boolean hasFiles) {
        Map<String, Object> config = new TreeMap<>();
        config.put("hasFiles", hasFiles);
        config.put("storageBackendId", kb.getStorageBackendId() == null ? "" : kb.getStorageBackendId());
        boolean canView = canViewIntegrationSecrets();

        for (ModelView m : models) {
            String baseUrl = m.getParameters() == null ? "" : m.getParameters().getBaseUrl();
            if (m.isIsBuiltin() || !canView) {
                baseUrl = "";
            }
            boolean hasKey = m.getParameters() != null && !m.getParameters().getApiKey().isEmpty()
                    && !m.isIsBuiltin();
            switch (m.getType()) {
                case "KnowledgeQA" -> config.put("llm", sortedBlock(Map.of(
                        "source", JsonBodies.orEmpty(m.getSource()),
                        "modelName", JsonBodies.orEmpty(m.getName()),
                        "baseUrl", baseUrl,
                        "credentials", Map.of("apiKey", hasKey))));
                case "Embedding" -> config.put("embedding", sortedBlock(Map.of(
                        "source", JsonBodies.orEmpty(m.getSource()),
                        "modelName", JsonBodies.orEmpty(m.getName()),
                        "baseUrl", baseUrl,
                        "dimension", m.getParameters() == null ? 0
                                : m.getParameters().getEmbeddingParameters().getDimension(),
                        "credentials", Map.of("apiKey", hasKey))));
                case "Rerank" -> config.put("rerank", sortedBlock(Map.of(
                        "enabled", true,
                        "modelName", JsonBodies.orEmpty(m.getName()),
                        "baseUrl", baseUrl,
                        "credentials", Map.of("apiKey", hasKey))));
                case "VLLM" -> {
                    Map<String, Object> mm = castMap(config.get("multimodal"));
                    if (mm == null) {
                        mm = new TreeMap<>();
                        mm.put("enabled", true);
                        config.put("multimodal", mm);
                    }
                    mm.put("vlm", sortedBlock(Map.of(
                            "modelName", JsonBodies.orEmpty(m.getName()),
                            "baseUrl", baseUrl,
                            "interfaceType", m.getParameters() == null ? ""
                                    : m.getParameters().getInterfaceType(),
                            "modelId", m.getId(),
                            "credentials", Map.of("apiKey", hasKey))));
                }
                default -> {
                }
            }
        }

        String storageProvider = kb.getStorageProvider();
        boolean hasMultimodal = kb.getVlmConfig().enabled()
                || !kb.getStorageConfig().secretId().isEmpty()
                || !kb.getStorageConfig().bucketName().isEmpty()
                || (!storageProvider.isEmpty() && !"local".equals(storageProvider));
        Map<String, Object> mm = castMap(config.get("multimodal"));
        if (mm == null) {
            mm = new TreeMap<>();
            mm.put("enabled", hasMultimodal);
            config.put("multimodal", mm);
        } else {
            mm.put("enabled", hasMultimodal);
        }
        String descLang = kb.getVlmConfig().descriptionLanguage();
        String customInstr = kb.getVlmConfig().customInstructions();
        if ((descLang != null && !descLang.isEmpty()) || (customInstr != null && !customInstr.isEmpty())) {
            if (descLang != null && !descLang.isEmpty()) {
                mm.put("descriptionLanguage", descLang);
            }
            if (customInstr != null && !customInstr.isEmpty()) {
                mm.put("customInstructions", customInstr);
            }
        }

        if (config.get("rerank") == null) {
            config.put("rerank", sortedBlock(Map.of(
                    "enabled", false,
                    "modelName", "",
                    "baseUrl", "",
                    "credentials", Map.of("apiKey", false))));
        }

        var c = kb.getChunkingConfig();
        Map<String, Object> ds = new TreeMap<>();
        ds.put("chunkSize", c.chunkSize());
        ds.put("chunkOverlap", c.chunkOverlap());
        ds.put("separators", c.separators());
        if (c.strategy() != null && !c.strategy().isEmpty()) {
            ds.put("strategy", c.strategy());
        }
        if (c.tokenLimit() > 0) {
            ds.put("tokenLimit", c.tokenLimit());
        }
        if (c.languages() != null && !c.languages().isEmpty()) {
            ds.put("languages", c.languages());
        }
        if (c.tableMetadataInstructions() != null && !c.tableMetadataInstructions().isEmpty()) {
            ds.put("tableMetadataInstructions", c.tableMetadataInstructions());
        }
        config.put("documentSplitting", ds);

        String effectiveProvider = kb.getStorageProvider();
        if (!kb.getStorageConfig().secretId().isEmpty()
                || (!effectiveProvider.isEmpty() && !"local".equals(effectiveProvider))) {
            Map<String, Object> mm2 = castMap(config.get("multimodal"));
            if (mm2 == null) {
                mm2 = new TreeMap<>();
                mm2.put("enabled", true);
                config.put("multimodal", mm2);
            }
            mm2.put("storageType", effectiveProvider);
            if ("cos".equals(effectiveProvider)) {
                Map<String, Object> cos = new TreeMap<>();
                cos.put("region", kb.getStorageConfig().region());
                cos.put("bucketName", kb.getStorageConfig().bucketName());
                cos.put("appId", kb.getStorageConfig().appId());
                cos.put("pathPrefix", kb.getStorageConfig().pathPrefix());
                cos.put("credentials", Map.of(
                        "secretId", !kb.getStorageConfig().secretId().isEmpty(),
                        "secretKey", !kb.getStorageConfig().secretKey().isEmpty()));
                mm2.put("cos", cos);
            } else if ("minio".equals(effectiveProvider)) {
                mm2.put("minio", sortedBlock(Map.of(
                        "bucketName", kb.getStorageConfig().bucketName(),
                        "pathPrefix", kb.getStorageConfig().pathPrefix())));
            }
        }

        JsonNode extract = JsonMappers.toJsonNode(kb.getExtractConfig());
        if (extract != null) {
            Map<String, Object> ne = new TreeMap<>();
            ne.put("enabled", extract.path("enabled").asBoolean(false));
            ne.put("text", extract.path("text").asText(""));
            ne.put("tags", extract.get("tags"));
            ne.put("nodes", extract.get("nodes"));
            ne.put("relations", extract.get("relations"));
            String ci = extract.path("customInstructions").asText("");
            if (!ci.isEmpty()) {
                ne.put("customInstructions", ci);
            }
            config.put("nodeExtract", ne);
        } else {
            config.put("nodeExtract", sortedBlock(Map.of("enabled", false)));
        }

        JsonNode qg = JsonMappers.toJsonNode(kb.getQuestionGenerationConfig());
        if (qg != null) {
            config.put("questionGeneration", sortedBlock(Map.of(
                    "enabled", qg.path("enabled").asBoolean(false),
                    "questionCount", qg.path("questionCount").asInt(0),
                    "customInstructions", qg.path("customInstructions").asText(""))));
        } else {
            config.put("questionGeneration", sortedBlock(Map.of("enabled", false)));
        }
        return config;
    }

    // ── 小工具 ──

    private boolean hasFiles(String kbId) {
        return knowledgeCatalog.hasFiles(kbId);
    }

    private void requireModel(String id, String message) {
        if (id == null || id.isEmpty()) {
            throw new BizException(AppError.badRequest(message));
        }
        try {
            ModelView m = modelCatalog.getModelRow(id);
            if (m != null) {
                return;
            }
        } catch (Exception ignored) {
            // 落到下方 400
        }
        throw new BizException(AppError.badRequest(message));
    }

    private boolean canViewIntegrationSecrets() {
        String role = TenantContext.currentRole();
        if (TenantRole.fromString(role == null ? "" : role).hasPermission(TenantRole.ADMIN)) {
            return true;
        }
        var scope = APIKeyScopeContext.current();
        return scope != null && (scope.fullAccess() || scope.hasCapability("manage_tenant_settings"));
    }

    /** Map.of 乱序 → TreeMap 重排（响应键为字母序）。 */
    private static Map<String, Object> sortedBlock(Map<String, Object> entries) {
        return new TreeMap<>(entries);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }



}
