package com.ragagent.model.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.contract.error.AppError;
import com.ragagent.contract.error.BizException;
import com.ragagent.contract.model.ModelConfigDraft;
import com.ragagent.contract.model.ModelConfigValidator;
import com.ragagent.contract.model.ModelFacts;
import com.ragagent.contract.model.ModelFactsPort;
import com.ragagent.contract.model.ModelParameters;
import com.ragagent.contract.tenant.TenantConfigLookup;
import com.ragagent.model.domain.Model;
import com.ragagent.model.dto.CreateModelRequest;
import com.ragagent.model.dto.ModelCredentialsResponse;
import com.ragagent.model.dto.ModelDebugTarget;
import com.ragagent.model.dto.ModelDetailResponse;
import com.ragagent.model.dto.UpdateModelRequest;
import com.ragagent.model.repository.ModelRepository;
import com.ragagent.model.repository.ModelUsageRepository;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.platform.web.JsonMappers;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 模型配置服务（配置 CRUD + credentials 子资源 + 删除守卫；运行时工厂见 ModelRuntime）。
 *
 * 语义要点（golden 已锁定）：
 * - CreateModel：source=remote → active；其他 → downloading（本地 ollama 拉取不轮转，
 *   保持 downloading，记录为实现边界）
 * - GetModelByID：downloading → 500 "model is currently downloading"；download_failed → 500
 * - UpdateModel：内置模型仅系统管理员可改（403），系统管理员修改时 managed_by 清空
 * - 凭证永不经 PUT /models/:id 正文（controller 层快照保留）
 * - DeleteModel：内置 400；被 KB/agent/长期记忆引用 → 400 code=2300 + usage details
 */
@Service
public class ModelService implements ModelFactsPort  {

    /**
     * 跨域只读端口的实现（{@link ModelFactsPort}）：只回填调用侧需要的字段。
     *
     * <p>空值归一为 {@code ""}（与消费方原先的 {@code p == null ? "" : p.getBaseUrl()} 一致；
     * parameters 为 null 时也不会 NPE）。</p>
     */
    @Override
    public ModelFacts findFacts(String modelId) {
        Model m = getModelById(modelId);
        if (m == null) {
            return null;
        }
        var p = m.getParameters();
        return new ModelFacts(m.getId(), m.getName() == null ? "" : m.getName(),
                p == null || p.getBaseUrl() == null ? "" : p.getBaseUrl(),
                p == null || p.getApiKey() == null ? "" : p.getApiKey());
    }


    private static final Logger log = LoggerFactory.getLogger(ModelService.class);
    private static final ObjectMapper MAPPER = JsonMappers.lenient();
    private static final long USAGE_LIST_LIMIT = 50;

    public static final String SOURCE_REMOTE = "remote";
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_DOWNLOADING = "downloading";
    public static final String STATUS_DOWNLOAD_FAILED = "download_failed";

    private final ModelRepository modelRepo;
    private final ModelUsageRepository usageRepo;
    private final TenantConfigLookup tenantConfigLookup;
    private final ModelConfigValidator modelConfigValidator;

    public ModelService(ModelRepository modelRepo, ModelUsageRepository usageRepo,
            TenantConfigLookup tenantConfigLookup, ModelConfigValidator modelConfigValidator) {
        this.modelRepo = modelRepo;
        this.usageRepo = usageRepo;
        this.tenantConfigLookup = tenantConfigLookup;
        this.modelConfigValidator = modelConfigValidator;
    }

    private static long tenantId() {
        Long tid = TenantContext.currentTenantId();
        return tid == null ? 0 : tid;
    }

    /**
     * 保存前结构校验：厂商字段规则（key / model name / base URL），不联网。
     * provider 为空或未知（本地 Ollama、自定义端点）时没有厂商规则。
     */
    private void validateConfigStructure(Model model) {
        ModelParameters p = model.getParameters();
        modelConfigValidator.validate(new ModelConfigDraft(
                p == null ? "" : p.getProvider(),
                p == null ? "" : p.getBaseUrl(),
                p == null ? "" : p.getApiKey(),
                model.getName() == null ? "" : model.getName()));
    }

    // ── CRUD ─────────────────────────────────────────────────────────────

    /** 创建模型（本地源保持 downloading，见类注释）。 */
    public Model createModel(Model model) {
        log.info("Creating model: {}, type: {}, source: {}", model.getName(), model.getType(), model.getSource());
        if (model.getId() == null || model.getId().isEmpty()) {
            model.setId(UUID.randomUUID().toString());
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        model.setCreatedAt(now);
        model.setUpdatedAt(now);
        if (SOURCE_REMOTE.equals(model.getSource())) {
            model.setStatus(STATUS_ACTIVE);
        } else {
            model.setStatus(STATUS_DOWNLOADING);
        }
        validateConfigStructure(model);
        modelRepo.insert(model);
        log.info("Model created successfully: {}", model.getId());
        return model;
    }

    /**
     * 带状态闸门（downloading/download_failed → 500）。
     * @throws ModelNotFoundException 不存在
     * @throws BizException 500（1007）状态异常
     */
    public Model getModelById(String id) {
        if (id == null || id.isEmpty()) {
            throw new BizException(new AppError(1000, "model ID cannot be empty", null, 500));
        }
        Model model = getByIdVisible(tenantId(), id);
        if (model == null) {
            log.debug("Model not found for tenant scope: id={}, tenantId={}", id, tenantId());
            throw new ModelNotFoundException();
        }
        return switch (model.getStatus()) {
            case STATUS_ACTIVE -> model;
            case STATUS_DOWNLOADING ->
                    throw new BizException(new AppError(1007, "model is currently downloading", null, 500));
            case STATUS_DOWNLOAD_FAILED ->
                    throw new BizException(new AppError(1007, "model download failed", null, 500));
            default -> throw new BizException(new AppError(1007, "abnormal model status", null, 500));
        };
    }

    /** WHERE (tenant_id = ? OR is_builtin = true) AND deleted_at IS NULL */
    public Model getByIdVisible(long tenantId, String id) {
        return modelRepo.findVisible(tenantId, id);
    }

    /** 无排序（DB 自然序）。 */
    public List<Model> listModels() {
        return modelRepo.listVisible(tenantId());
    }

    /**
     * 内置模型守卫 + 系统管理员修改时清空 managed_by。
     * 注意 model 须为"读-改-写"后的完整对象（controller 负责字段合并）。
     */
    public Model updateModel(Model model) {
        long tid = tenantId();
        Model existing = getByIdVisible(tid, model.getId());
        if (existing != null && existing.isIsBuiltin()) {
            if (!TenantContext.isSystemAdmin()) {
                log.warn("Non-system-admin attempted to update builtin model: {}", model.getId());
                throw new BizException(AppError.forbidden("only system administrators can update builtin models"));
            }
            // UI 编辑 = 显式运行时覆盖：清 YAML 托管标记，防止启动对账静默覆盖
            model.setTenantId(existing.getTenantId());
            model.setIsBuiltin(true);
            model.setManagedBy("");
        }
        validateConfigStructure(model);
        modelRepo.updateById(model);
        log.info("Model updated successfully: {}", model.getId());
        return model;
    }

    // ── credentials 子资源 ────────────────────────────────────────────────

    /** 仅写入非空且变化的值；内置模型需系统管理员 */
    public Model updateModelCredentials(String id, String apiKey, String appSecret) {
        long tid = tenantId();
        Model existing = getByIdVisible(tid, id);
        if (existing == null) {
            throw new ModelNotFoundException();
        }
        if (existing.isIsBuiltin() && !TenantContext.isSystemAdmin()) {
            throw new BizException(AppError.forbidden(
                    "only system administrators can modify builtin model credentials"));
        }
        boolean changed = false;
        if (apiKey != null && !apiKey.isEmpty() && !apiKey.equals(existing.getParameters().getApiKey())) {
            existing.getParameters().setApiKey(apiKey);
            changed = true;
        }
        if (appSecret != null && !appSecret.isEmpty()
                && !appSecret.equals(existing.getParameters().getAppSecret())) {
            existing.getParameters().setAppSecret(appSecret);
            changed = true;
        }
        if (!changed) {
            return existing;
        }
        if (existing.isIsBuiltin()) {
            existing.setManagedBy("");
        }
        validateConfigStructure(existing);
        modelRepo.updateById(existing);
        log.info("Model credentials updated: id={}", id);
        return existing;
    }

    /** 幂等清除单字段 */
    public void clearModelCredential(String id, String field) {
        long tid = tenantId();
        Model existing = getByIdVisible(tid, id);
        if (existing == null) {
            throw new ModelNotFoundException();
        }
        if (existing.isIsBuiltin() && !TenantContext.isSystemAdmin()) {
            throw new BizException(AppError.forbidden(
                    "only system administrators can modify builtin model credentials"));
        }
        boolean changed = switch (field) {
            case "apiKey" -> {
                if (!existing.getParameters().getApiKey().isEmpty()) {
                    existing.getParameters().setApiKey("");
                    yield true;
                }
                yield false;
            }
            case "appSecret" -> {
                if (!existing.getParameters().getAppSecret().isEmpty()) {
                    existing.getParameters().setAppSecret("");
                    yield true;
                }
                yield false;
            }
            default -> throw new BizException(
                    new AppError(1000, "unknown credential field: " + field, null, 500));
        };
        if (!changed) {
            return;
        }
        if (existing.isIsBuiltin()) {
            existing.setManagedBy("");
        }
        modelRepo.updateById(existing);
        log.info("Model credential cleared by user: id={} field={}", id, field);
    }

    // ── 删除守卫 ─────────────────────────────────────────────────────────

    /** 删除模型（内置/被引用见调用方守卫）。 */
    public void deleteModel(String id) {
        long tid = tenantId();
        Model existing = getByIdVisible(tid, id);
        if (existing == null) {
            throw new ModelNotFoundException();
        }
        if (existing.isIsBuiltin()) {
            log.warn("Attempted to delete builtin model: {}", id);
            throw new BizException(AppError.badRequest("builtin models cannot be deleted"));
        }
        JsonNode usage = getModelUsageDetails(tid, id);
        if (inUse(usage)) {
            long kbCount = usage.get("knowledgeBaseTotal").asLong();
            long agentCount = usage.get("agentTotal").asLong();
            boolean memory = usage.get("longTermMemory").get("bindings").size() > 0;
            log.warn("Model {} is in use: kb={} agent={} memory={}", id, kbCount, agentCount, memory);
            throw new BizException(new AppError(2300, formatInUseMessage(kbCount, agentCount, memory), usage, 400));
        }
        // 软删除：UPDATE deleted_at = now
        modelRepo.softDelete(id, tid, OffsetDateTime.now(ZoneOffset.UTC));
        log.info("Model deleted successfully: {}", id);
    }

    private static boolean inUse(JsonNode usage) {
        return usage.get("knowledgeBaseTotal").asLong() > 0
                || usage.get("agentTotal").asLong() > 0
                || usage.get("knowledgeBases").size() > 0
                || usage.get("agents").size() > 0
                || usage.get("longTermMemory").get("bindings").size() > 0;
    }

    /** 组装 2300 错误的 in-use 文案。 */
    static String formatInUseMessage(long kbCount, long agentCount, boolean memory) {
        List<String> parts = new ArrayList<>();
        if (kbCount > 0) {
            parts.add(kbCount + " knowledge base(s)");
        }
        if (agentCount > 0) {
            parts.add(agentCount + " agent(s)");
        }
        if (memory) {
            parts.add("long-term memory");
        }
        return "model is used by " + String.join(" and ", parts)
                + "; reconfigure or remove those references before deleting";
    }

    /**
     * KB/agent 引用 + 空间长期记忆模型绑定。
     * 返回 ObjectNode（字段序固定），作为 2300 错误的 details 原样输出。
     */
    public JsonNode getModelUsageDetails(long tid, String modelId) {
        ObjectNode details = MAPPER.createObjectNode();
        ArrayNode kbs = details.putArray("knowledgeBases");
        ArrayNode agents = details.putArray("agents");
        ObjectNode memory = details.putObject("longTermMemory");
        ArrayNode memoryBindings = memory.putArray("bindings");

        long kbTotal = 0;
        for (Map<String, Object> row : usageRepo.listKnowledgeBaseRows(tid)) {
            List<String> bindings = kbBindings(row, modelId);
            if (bindings.isEmpty()) {
                continue;
            }
            kbTotal++;
            if (kbTotal <= USAGE_LIST_LIMIT) {
                ObjectNode r = kbs.addObject();
                r.put("id", String.valueOf(row.get("id")));
                r.put("name", String.valueOf(row.get("name")));
                ArrayNode b = r.putArray("bindings");
                bindings.forEach(b::add);
            }
        }

        long agentTotal = 0;
        for (Map<String, Object> row : usageRepo.listCustomAgentRows(tid)) {
            List<String> bindings = agentBindings(row, modelId);
            if (bindings.isEmpty()) {
                continue;
            }
            agentTotal++;
            if (agentTotal <= USAGE_LIST_LIMIT) {
                ObjectNode r = agents.addObject();
                r.put("id", String.valueOf(row.get("id")));
                r.put("name", String.valueOf(row.get("name")));
                ArrayNode b = r.putArray("bindings");
                bindings.forEach(b::add);
            }
        }

        JsonNode memoryConfig = JsonMappers.toJsonNode(tenantConfigLookup.memoryConfig(tid));
        if (memoryConfig != null) {
            // 两个记忆模型钉都要查：删任一会让空间指向不存在的模型
            if (modelId.equals(text(memoryConfig.get("embeddingModelId")))) {
                memoryBindings.add("embedding_model");
            }
            if (modelId.equals(text(memoryConfig.get("extractModelId")))) {
                memoryBindings.add("extract_model");
            }
        }

        details.put("knowledgeBaseTotal", kbTotal);
        details.put("agentTotal", agentTotal);
        return details;
    }

    /** KB 引用绑定（绑定序固定） */
    private static List<String> kbBindings(Map<String, Object> row, String modelId) {
        List<String> bindings = new ArrayList<>();
        if (modelId.equals(stringOrNull(row.get("embedding_model_id")))) {
            bindings.add("embedding_model");
        }
        if (modelId.equals(stringOrNull(row.get("summary_model_id")))) {
            bindings.add("summary_model");
        }
        if (modelId.equals(jsonField(row.get("image_processing_config"), "modelId"))) {
            bindings.add("image_processing_model");
        }
        if (modelId.equals(jsonField(row.get("vlm_config"), "modelId"))) {
            bindings.add("vlm_model");
        }
        if (modelId.equals(jsonField(row.get("asr_config"), "modelId"))) {
            bindings.add("asr_model");
        }
        if (modelId.equals(jsonField(row.get("wiki_config"), "synthesisModelId"))) {
            bindings.add("wiki_synthesis_model");
        }
        return bindings;
    }

    /** agent 引用绑定（绑定序固定） */
    private static List<String> agentBindings(Map<String, Object> row, String modelId) {
        List<String> bindings = new ArrayList<>();
        Object configRaw = row.get("config");
        JsonNode config = parseJson(configRaw);
        if (config == null) {
            return bindings;
        }
        // agent 配置树键名 = Java 字段名（camel）——此处按 camel 读取
        if (modelId.equals(text(config.get("modelId")))) {
            bindings.add("chat_model");
        }
        if (modelId.equals(text(config.get("rerankModelId")))) {
            bindings.add("rerank_model");
        }
        if (modelId.equals(text(config.get("vlmModelId")))) {
            bindings.add("vlm_model");
        }
        if (modelId.equals(text(config.get("asrModelId")))) {
            bindings.add("asr_model");
        }
        if (modelId.equals(text(config.get("queryUnderstandModelId")))) {
            bindings.add("query_understand_model");
        }
        JsonNode followUps = config.get("questionSuggestions");
        if (followUps != null && followUps.get("followUps") != null) {
            if (modelId.equals(text(followUps.get("followUps").get("modelId")))) {
                bindings.add("follow_up_model");
            }
        }
        return bindings;
    }

    private static String stringOrNull(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static JsonNode parseJson(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            if (raw instanceof JsonNode node) {
                return node;
            }
            return MAPPER.readTree(String.valueOf(raw));
        } catch (Exception e) {
            return null;
        }
    }

    private static String jsonField(Object raw, String field) {
        JsonNode node = parseJson(raw);
        return node == null ? null : text(node.get(field));
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    // ── 控制面读模型（控制器不再 import Model 实体） ─────────────────────

    public ModelDetailResponse createModelResponse(long tenantId, CreateModelRequest req,
            boolean canViewIntegrationSecrets) {
        Model model = new Model();
        model.setTenantId(tenantId);
        model.setName(req.name());
        model.setDisplayName(req.displayName());
        model.setType(req.type());
        model.setSource(req.source());
        model.setDescription(req.description());
        model.setParameters(req.parameters().toDomain());
        model = createModel(model);
        return ModelDetailResponse.from(model, canViewIntegrationSecrets,
                canManageBuiltin(model));
    }

    public ModelDetailResponse getModelResponse(String id, boolean canViewIntegrationSecrets) {
        Model model = getModelById(id);
        return ModelDetailResponse.from(model, canViewIntegrationSecrets,
                canManageBuiltin(model));
    }

    public List<ModelDetailResponse> listModelResponses(boolean canViewIntegrationSecrets) {
        return listModels().stream()
                .map(model -> ModelDetailResponse.from(model, canViewIntegrationSecrets,
                        canManageBuiltin(model)))
                .toList();
    }

    public ModelDetailResponse updateModelResponse(String id, UpdateModelRequest req,
            boolean canViewIntegrationSecrets) {
        Model model = getModelById(id);
        if (req.name() != null && !req.name().isEmpty()) {
            model.setName(req.name());
        }
        if (req.displayName() != null) {
            model.setDisplayName(req.displayName());
        }
        model.setDescription(req.description());

        var stored = model.getParameters();
        String storedApiKey = stored.getApiKey();
        String storedAppSecret = stored.getAppSecret();
        ModelParameters newParams = req.parameters() != null
                ? req.parameters().toDomain() : new ModelParameters();
        newParams.setApiKey(storedApiKey);
        newParams.setAppSecret(storedAppSecret);
        newParams.setParameterSize(stored.getParameterSize());
        if (newParams.getInterfaceType().isEmpty()) {
            newParams.setInterfaceType(stored.getInterfaceType());
        }
        if (newParams.getAppId().isEmpty()) {
            newParams.setAppId(stored.getAppId());
        }
        if (newParams.getExtraConfig() == null) {
            newParams.setExtraConfig(stored.getExtraConfig());
        }
        model.setParameters(newParams);
        model.setSource(req.source());
        model.setType(req.type());
        model = updateModel(model);
        return ModelDetailResponse.from(model, canViewIntegrationSecrets,
                canManageBuiltin(model));
    }

    public ModelCredentialsResponse credentialsResponse(String id) {
        Model model = getModelById(id);
        return ModelCredentialsResponse.of(
                !model.getParameters().getApiKey().isEmpty(),
                !model.getParameters().getAppSecret().isEmpty());
    }

    public ModelCredentialsResponse updateCredentialsResponse(String id, String apiKey,
            String appSecret) {
        Model model = updateModelCredentials(id, apiKey, appSecret);
        return ModelCredentialsResponse.of(
                !model.getParameters().getApiKey().isEmpty(),
                !model.getParameters().getAppSecret().isEmpty());
    }

    public ModelDebugTarget getDebugTarget(String id) {
        return ModelDebugTarget.from(getModelById(id));
    }

    private static boolean canManageBuiltin(Model model) {
        return model.isIsBuiltin() && TenantContext.isSystemAdmin();
    }

    /** 模型不存在（404 "Model not found"） */
    public static class ModelNotFoundException extends RuntimeException {
    }
}
