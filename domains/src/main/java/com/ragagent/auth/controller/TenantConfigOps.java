package com.ragagent.auth.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.auth.domain.TenantConfigRedaction;
import com.ragagent.auth.dto.TenantRowView;
import com.ragagent.contract.apikey.APIKeyCapability;
import com.ragagent.contract.apikey.APIKeyScopeContext;
import com.ragagent.contract.apikey.TenantAPIKeyScope;
import com.ragagent.contract.error.AppError;
import com.ragagent.contract.error.BizException;
import com.ragagent.contract.memory.MemoryConfig;
import com.ragagent.contract.tenant.ChatHistoryConfig;
import com.ragagent.contract.tenant.ParserEngineConfig;
import com.ragagent.contract.tenant.RetrievalConfig;
import com.ragagent.contract.tenant.StorageEngineConfig;
import com.ragagent.contract.tenant.TenantRole;
import com.ragagent.contract.tenant.WebSearchConfig;
import com.ragagent.contract.wiki.WikiLanguageSupport;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.platform.prompt.PromptTemplateCatalog;
import com.ragagent.platform.web.JsonMappers;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * KV 配置分发协作者（自 {@link TenantCatalogController} 拆出）：6 个 DB-backed key 的
 * get（put 仅 5 个可写——storage-engine-config 的写入口已关闭，指向 /api/v1/storage-backends）、
 * 敏感 key 的 admin 门、jsonb 强类型解析与各配置段校验。
 * 持门面回引取 tenantService/ssrfGuard/storageAllowList/knowledgeProvisioner。
 */
final class TenantConfigOps {

    private final TenantCatalogController service;

    TenantConfigOps(TenantCatalogController service) {
        this.service = service;
    }

    // ── KV 分发器 ──────────────────────────────────────────────────────────

    public Object getTenantKV(@PathVariable String key,
                              jakarta.servlet.http.HttpServletRequest request) {
        requireIntegrationSecretsIfSensitive(key);
        return switch (key) {
            case "web-search-config" -> {
                WebSearchConfig ws = getWebSearch();
                yield ws == null
                        ? com.fasterxml.jackson.databind.node.NullNode.getInstance()
                        : ws;
            }
            // config.yaml 模板 + Accept-Language 本地化
            // （locale 解析：env → Accept-Language 首 tag → zh-CN）
            case "prompt-templates" ->
                    PromptTemplateCatalog.toJson(
                            PromptTemplateCatalog.load(),
                            WikiLanguageSupport
                                    .localeFromRequest(request.getHeader("Accept-Language")));
            case "parser-engine-config" -> getParserEngine();
            case "storage-engine-config" -> getStorageEngine();
            case "chat-history-config" -> getChatHistory();
            case "retrieval-config" -> getRetrieval();
            case "memory-config" -> getMemory();
            default -> throw new BizException(AppError.badRequest("unsupported key"));
        };
    }

    public Object updateTenantKV(@PathVariable String key,
                                              @RequestBody(required = false) String rawBody) {
        requireIntegrationSecretsIfSensitive(key);
        return switch (key) {
            case "web-search-config" -> putWebSearch(rawBody);
            case "parser-engine-config" -> putParserEngine(rawBody);
            case "storage-engine-config" -> throw new BizException(AppError.ofHttpStatus(405,
                    "storage config is managed by /api/v1/storage-backends"));
            case "chat-history-config" -> putChatHistory(rawBody);
            case "retrieval-config" -> putRetrieval(rawBody);
            case "memory-config" -> putMemory(rawBody);
            default -> throw new BizException(AppError.badRequest("unsupported key"));
        };
    }

    /**
     * 敏感 key 门：角色 ≥ admin，或 API Key 具备
     * full-access / manage_tenant_settings。
     */
    private void requireIntegrationSecretsIfSensitive(String key) {
        if (!"web-search-config".equals(key)
                && !"parser-engine-config".equals(key)
                && !"storage-engine-config".equals(key)) {
            return;
        }
        if (canViewIntegrationSecrets()) {
            return;
        }
        throw new BizException(AppError.forbidden("integration configuration requires admin access"));
    }

    private boolean canViewIntegrationSecrets() {
        TenantRole role = TenantRole.fromString(TenantContext.currentRole());
        if (role != null && role.hasPermission(TenantRole.ADMIN)) {
            return true;
        }
        TenantAPIKeyScope scope = APIKeyScopeContext.current();
        if (scope == null) {
            return false;
        }
        return scope.fullAccess() || scope.hasCapability(APIKeyCapability.MANAGE_TENANT_SETTINGS);
    }

    /** 上下文无租户 → 400 */
    private TenantRowView requireContextTenant() {
        Long tenantId = TenantContext.currentTenantId();
        TenantRowView tenant = tenantId == null || tenantId == 0 ? null : service.tenantService.getTenantById(tenantId);
        if (tenant == null) {
            throw new BizException(AppError.badRequest("Workspace is empty"));
        }
        return tenant;
    }

    /**
     * Jsonb 列 → 强类型：SQL NULL → null；
     * jsonb 'null' → 零值对象（非 NULL 列先分配再反序列化，
     * "null" 落在零值对象上）；对象 → 按字段绑定。
     */
    private static <T> T parseConfig(JsonNode node, Class<T> type) {
        if (node == null) {
            return null;
        }
        try {
            if (node.isNull()) {
                return type.getDeclaredConstructor().newInstance();
            }
            return TenantBindSupport.MAPPER.treeToValue(node, type);
        } catch (Exception e) {
            throw new IllegalStateException("failed to decode tenant config jsonb: " + e.getMessage(), e);
        }
    }

    /** PUT 失败分支统一形态：BizException(AppError) 透传，其余 → 500 + details */
    private RuntimeException updateFailed(String message, RuntimeException e) {
        if (e instanceof BizException be) {
            return be;
        }
        return new BizException(AppError.internal(message).withDetails(e.getMessage()));
    }

    // ── web-search-config ──────────────────────────────────────────────────

    private WebSearchConfig getWebSearch() {
        TenantRowView tenant = requireContextTenant();
        return TenantConfigRedaction.webSearchForResponse(
                parseConfig(JsonMappers.toJsonNode(tenant.getWebSearchConfig()), WebSearchConfig.class));
    }

    private Object putWebSearch(String rawBody) {
        WebSearchConfig cfg = TenantBindSupport.bindBody(rawBody, WebSearchConfig.class, "Invalid request data");
        if (cfg == null) {
            cfg = new WebSearchConfig();
        }
        TenantRowView tenant = requireContextTenant();
        WebSearchConfig merged = TenantConfigRedaction.mergeWebSearch(
                cfg, parseConfig(JsonMappers.toJsonNode(tenant.getWebSearchConfig()), WebSearchConfig.class));
        if (merged.getMaxResults() < 1 || merged.getMaxResults() > 50) {
            throw new BizException(AppError.badRequest("max_results must be between 1 and 50"));
        }
        tenant.setWebSearchConfig(TenantBindSupport.MAPPER.valueToTree(merged));
        try {
            service.tenantService.updateTenant(tenant);
        } catch (RuntimeException e) {
            throw updateFailed("Failed to update workspace web search config", e);
        }
        return TenantConfigRedaction.webSearchForResponse(merged);
    }

    // ── parser-engine-config ───────────────────────────────────────────────

    private ParserEngineConfig getParserEngine() {
        TenantRowView tenant = requireContextTenant();
        ParserEngineConfig data = TenantConfigRedaction.parserEngineForResponse(
                parseConfig(JsonMappers.toJsonNode(tenant.getParserEngineConfig()), ParserEngineConfig.class));
        return data == null ? new ParserEngineConfig() : data;
    }

    private Object putParserEngine(String rawBody) {
        ParserEngineConfig cfg = TenantBindSupport.bindBody(rawBody, ParserEngineConfig.class, "Invalid request data");
        if (cfg == null) {
            cfg = new ParserEngineConfig();
        }
        TenantRowView tenant = requireContextTenant();
        ParserEngineConfig merged = TenantConfigRedaction.mergeParserEngine(
                cfg, parseConfig(JsonMappers.toJsonNode(tenant.getParserEngineConfig()), ParserEngineConfig.class));
        validateParserEngineOutboundUrls(merged);
        tenant.setParserEngineConfig(TenantBindSupport.MAPPER.valueToTree(merged));
        try {
            service.tenantService.updateTenant(tenant);
        } catch (RuntimeException e) {
            throw updateFailed("Failed to update workspace parser engine config", e);
        }
        return TenantConfigRedaction.parserEngineForResponse(merged);
    }

    /** 四处 URL 过 SSRF */
    private void validateParserEngineOutboundUrls(ParserEngineConfig cfg) {
        if (cfg == null) {
            return;
        }
        if (!cfg.getMineruEndpoint().trim().isEmpty()) {
            ssrfCheck("mineru_endpoint", cfg.getMineruEndpoint().trim());
        }
        if (!cfg.getMineruVlmServerUrl().trim().isEmpty()) {
            ssrfCheck("mineru_vlm_server_url", cfg.getMineruVlmServerUrl().trim());
        }
        if (!cfg.getOdlHybridUrl().trim().isEmpty()) {
            ssrfCheck("odl_hybrid_url", cfg.getOdlHybridUrl().trim());
        }
        if (!cfg.getPaddleOcrVlEndpoint().trim().isEmpty()) {
            ssrfCheck("paddleocr_vl_endpoint", cfg.getPaddleOcrVlEndpoint().trim());
        }
    }

    private void ssrfCheck(String field, String url) {
        try {
            service.ssrfGuard.validateURLForSSRF(url);
        } catch (RuntimeException e) {
            throw new BizException(AppError.validation(
                    field + " failed SSRF validation: " + e.getMessage()));
        }
    }

    // ── storage-engine-config ──────────────────────────────────────────────

    /** 只读兼容一版：写入口已迁到 {@code /api/v1/storage-backends}，本键仅回显存量值。 */
    private StorageEngineConfig getStorageEngine() {
        TenantRowView tenant = requireContextTenant();
        StorageEngineConfig data = TenantConfigRedaction.storageEngineForResponse(
                parseConfig(JsonMappers.toJsonNode(tenant.getStorageEngineConfig()), StorageEngineConfig.class));
        return data == null ? new StorageEngineConfig() : data;
    }

    // ── chat-history-config ────────────────────────────────────────────────

    private ChatHistoryConfig getChatHistory() {
        TenantRowView tenant = requireContextTenant();
        ChatHistoryConfig data = parseConfig(JsonMappers.toJsonNode(tenant.getChatHistoryConfig()), ChatHistoryConfig.class);
        return data == null ? new ChatHistoryConfig() : data;
    }

    private Object putChatHistory(String rawBody) {
        ChatHistoryConfig req = TenantBindSupport.bindBody(rawBody, ChatHistoryConfig.class, "Invalid request data");
        if (req == null) {
            req = new ChatHistoryConfig();
        }
        TenantRowView tenant = requireContextTenant();
        ChatHistoryConfig existing = parseConfig(JsonMappers.toJsonNode(tenant.getChatHistoryConfig()), ChatHistoryConfig.class);

        // 重建对象（knowledgeBaseId 不受客户端控制），
        // 嵌入模型未变时沿用存量 KB
        ChatHistoryConfig cfg = new ChatHistoryConfig();
        cfg.setEnabled(req.isEnabled());
        cfg.setEmbeddingModelId(req.getEmbeddingModelId());
        if (existing != null && !existing.getKnowledgeBaseId().isEmpty()
                && existing.getEmbeddingModelId().equals(req.getEmbeddingModelId())) {
            cfg.setKnowledgeBaseId(existing.getKnowledgeBaseId());
        }

        // enabled + 有模型 + 无 KB → 自动建隐藏 KB
        if (cfg.isEnabled() && !cfg.getEmbeddingModelId().isEmpty()
                && cfg.getKnowledgeBaseId().isEmpty()) {
            // 实体语义（名字/类型/临时标记/描述）归知识域，本域只传模型 id、只消费 id
            String kbId;
            try {
                kbId = service.knowledgeProvisioner.provisionChatHistoryKnowledgeBase(cfg.getEmbeddingModelId());
            } catch (RuntimeException e) {
                throw new BizException(AppError.internal("Failed to create chat history knowledge base")
                        .withDetails(e.getMessage()));
            }
            cfg.setKnowledgeBaseId(kbId);
        }

        tenant.setChatHistoryConfig(TenantBindSupport.MAPPER.valueToTree(cfg));
        try {
            service.tenantService.updateTenant(tenant);
        } catch (RuntimeException e) {
            throw updateFailed("Failed to update chat history config", e);
        }
        return cfg;
    }

    // ── retrieval-config ───────────────────────────────────────────────────

    private RetrievalConfig getRetrieval() {
        TenantRowView tenant = requireContextTenant();
        RetrievalConfig data = parseConfig(JsonMappers.toJsonNode(tenant.getRetrievalConfig()), RetrievalConfig.class);
        return data == null ? new RetrievalConfig() : data;
    }

    private Object putRetrieval(String rawBody) {
        RetrievalConfig cfg = TenantBindSupport.bindBody(rawBody, RetrievalConfig.class, "Invalid request data");
        if (cfg == null) {
            cfg = new RetrievalConfig();
        }
        // 五段范围校验（在租户检查之前）
        if (cfg.getVectorThreshold() < 0 || cfg.getVectorThreshold() > 1) {
            throw new BizException(AppError.badRequest("vectorThreshold must be between 0 and 1"));
        }
        if (cfg.getKeywordThreshold() < 0 || cfg.getKeywordThreshold() > 1) {
            throw new BizException(AppError.badRequest("keywordThreshold must be between 0 and 1"));
        }
        if (cfg.getRerankThreshold() < -10 || cfg.getRerankThreshold() > 10) {
            throw new BizException(AppError.badRequest("rerankThreshold must be between -10 and 10"));
        }
        if (cfg.getEmbeddingTopK() < 0 || cfg.getEmbeddingTopK() > 200) {
            throw new BizException(AppError.badRequest("embeddingTopK must be between 0 and 200"));
        }
        if (cfg.getRerankTopK() < 0 || cfg.getRerankTopK() > 200) {
            throw new BizException(AppError.badRequest("rerankTopK must be between 0 and 200"));
        }
        TenantRowView tenant = requireContextTenant();
        tenant.setRetrievalConfig(TenantBindSupport.MAPPER.valueToTree(cfg));
        try {
            service.tenantService.updateTenant(tenant);
        } catch (RuntimeException e) {
            throw updateFailed("Failed to update retrieval config", e);
        }
        return cfg;
    }

    // ── memory-config（MemoryConfig 本体在 memory 模块） ────────────────────

    private MemoryConfig getMemory() {
        TenantRowView tenant = requireContextTenant();
        MemoryConfig data = parseConfig(JsonMappers.toJsonNode(tenant.getMemoryConfig()), MemoryConfig.class);
        if (data == null) {
            data = new MemoryConfig();
        }
        data.normalize();
        return data;
    }

    private Object putMemory(String rawBody) {
        MemoryConfig cfg = TenantBindSupport.bindBody(rawBody, MemoryConfig.class, "Invalid request data");
        if (cfg == null) {
            cfg = new MemoryConfig();
        }
        // 七段校验（在 Normalize 与租户检查之前）
        String writeMode = cfg.getWriteMode();
        if (!writeMode.isEmpty()
                && !MemoryConfig.WRITE_MODE_EXPLICIT_ONLY.equals(writeMode)
                && !MemoryConfig.WRITE_MODE_AUTO.equals(writeMode)) {
            throw new BizException(AppError.badRequest("writeMode must be explicit_only or auto"));
        }
        if (cfg.getMaxItems() < 0 || cfg.getMaxItems() > MemoryConfig.MAX_ITEMS_CAP) {
            throw new BizException(AppError.badRequest("maxItems must be between 0 and 2000"));
        }
        if (cfg.getExtractDelaySeconds() < 0
                || cfg.getExtractDelaySeconds() > MemoryConfig.MAX_EXTRACT_DELAY_SECONDS) {
            throw new BizException(AppError.badRequest(
                    "extractDelaySeconds must be between 0 and " + MemoryConfig.MAX_EXTRACT_DELAY_SECONDS));
        }
        if (cfg.getExtractMinIntervalSeconds() < 0
                || cfg.getExtractMinIntervalSeconds() > MemoryConfig.MAX_EXTRACT_MIN_INTERVAL_SECONDS) {
            throw new BizException(AppError.badRequest(
                    "extractMinIntervalSeconds must be between 0 and "
                            + MemoryConfig.MAX_EXTRACT_MIN_INTERVAL_SECONDS));
        }
        if (cfg.getEmbeddingModelId().length() > 64) {
            throw new BizException(AppError.badRequest("embeddingModelId is too long"));
        }
        if (cfg.getInterestThreshold() < 0
                || cfg.getInterestThreshold() > MemoryConfig.MAX_MEMORY_INTEREST_THRESHOLD) {
            // 下界文案写死 1（尽管校验允许 0）——刻意保持既有文案
            throw new BizException(AppError.badRequest(
                    "interestThreshold must be between 1 and " + MemoryConfig.MAX_MEMORY_INTEREST_THRESHOLD));
        }
        if (cfg.getExtractInstructions().codePointCount(0, cfg.getExtractInstructions().length())
                > MemoryConfig.MAX_EXTRACT_INSTRUCTIONS_RUNES) {
            throw new BizException(AppError.badRequest(
                    "extractInstructions must be at most " + MemoryConfig.MAX_EXTRACT_INSTRUCTIONS_RUNES
                            + " characters"));
        }
        cfg.normalize();
        TenantRowView tenant = requireContextTenant();
        tenant.setMemoryConfig(TenantBindSupport.MAPPER.valueToTree(cfg));
        try {
            service.tenantService.updateTenant(tenant);
        } catch (RuntimeException e) {
            throw updateFailed("Failed to update memory config", e);
        }
        return cfg;
    }
}
