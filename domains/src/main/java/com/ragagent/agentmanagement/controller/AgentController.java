package com.ragagent.agentmanagement.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.agentmanagement.dto.AgentResponses;
import com.ragagent.agentmanagement.dto.AgentView;
import com.ragagent.agentmanagement.service.AgentSuggestedQuestions.TagScope;
import com.ragagent.agentmanagement.service.CustomAgentService;
import com.ragagent.agentmanagement.service.builtin.BuiltinAgentRegistry;
import com.ragagent.agentmanagement.service.config.AgentPlaceholders;
import com.ragagent.agentmanagement.service.config.AgentTypePresets;
import com.ragagent.contract.apikey.APIKeyScopeContext;
import com.ragagent.contract.apikey.TenantAPIKeyScope;
import com.ragagent.contract.error.AppError;
import com.ragagent.contract.error.BizException;
import com.ragagent.platform.web.ApiResponse;
import com.ragagent.platform.web.ApiResult;
import com.ragagent.platform.web.JsonBodyErrors;
import com.ragagent.platform.web.JsonMappers;
import com.ragagent.platform.web.RequestFields;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * agents CRUD 家族路由。
 *
 * <p>守卫层次：写路由在 controller 内做归属校验（行存在且非 Admin+ 且非创建者 →
 * 403 纯字符串；行不存在 → 放行给 handler 出 404）；读/列表 Viewer 下限由
 * RbacInterceptor 承担。</p>
 *
 * <p>响应外壳：本类标注 {@link ApiResult} ⇒ 成功体统一 {@code {code,message,data}}
 * （由 {@code ApiResultAdvice} 施加，方法只返回载荷）；错误体同形（{@code GlobalExceptionHandler}
 * 按请求打标分派）。约定见 {@code docs/api/README.md}。</p>
 */
@RestController
@ApiResult
@Tag(name = "Agent Management")
public class AgentController {

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    private final CustomAgentService service;
    private final AgentPlaceholders placeholders;
    private final AgentTypePresets typePresets;

    public AgentController(CustomAgentService service, AgentPlaceholders placeholders,
            AgentTypePresets typePresets) {
        this.service = service;
        this.placeholders = placeholders;
        this.typePresets = typePresets;
    }

    // ── 请求体（仅 name 必填）──
    private record AgentRequest(String name, String description, String avatar, JsonNode config) {}

    // ── GET /agents ──
    @GetMapping("/api/v1/agents")
    public ResponseEntity<AgentResponses.AgentListPayload> list(
            @RequestParam(value = "creator", required = false) String creator,
            HttpServletRequest req) {
        String locale = BuiltinAgentRegistry.localeFromRequest(req.getHeader("Accept-Language"));
        var result = service.listAgents(creator == null ? "" : creator.toLowerCase().trim(), locale);
        List<AgentView> rows = new ArrayList<>();
        for (var r : result.agents()) {
            rows.add(AgentResponses.agent(r));
        }
        // 载荷形状不变（{agents, disabledOwnAgentIds}）；外壳由 @ApiResult 统一施加
        return ResponseEntity.ok(AgentResponses.listEnvelope(rows, result.disabledOwnIds()));
    }

    // ── GET /agents/placeholders ──
    @GetMapping("/api/v1/agents/placeholders")
    public ResponseEntity<Object> placeholders() {
        return ResponseEntity.ok(placeholders.data());
    }

    // ── GET /agents/type-presets ──
    @GetMapping("/api/v1/agents/type-presets")
    public ResponseEntity<Object> typePresets(HttpServletRequest req) {
        String locale = BuiltinAgentRegistry.localeFromRequest(req.getHeader("Accept-Language"));
        return ResponseEntity.ok(typePresets.list(locale));
    }

    // ── POST /agents ──
    @PostMapping("/api/v1/agents")
    public ResponseEntity<AgentView> create(
            @RequestBody(required = false) String rawBody) {
        AgentRequest parsed = bindAgentRequest(rawBody, true);
        ObjectNode cfg = configNode(parsed);
        authorizeKnowledgeScope(cfg);
        var result = service.createAgent(parsed.name(), parsed.description(), parsed.avatar(), cfg);
        return ResponseEntity.status(201).body(AgentResponses.agent(result));
    }

    // ── GET /agents/:id ──
    @GetMapping("/api/v1/agents/{id}")
    public ResponseEntity<AgentView> get(@PathVariable("id") String id,
            HttpServletRequest req) {
        requireNonEmpty(id);
        String locale = BuiltinAgentRegistry.localeFromRequest(req.getHeader("Accept-Language"));
        var result = service.getAgentById(id, locale);
        return ResponseEntity.ok(AgentResponses.agent(result));
    }

    // ── PUT /agents/:id ──
    @PutMapping("/api/v1/agents/{id}")
    public ResponseEntity<AgentView> update(@PathVariable("id") String id,
            @RequestBody(required = false) String rawBody, HttpServletRequest req) {
        requireNonEmpty(id);
        AgentRequest parsed = bindAgentRequest(rawBody, false);
        service.requireOwnedOrAdmin(id);
        ObjectNode cfg = configNode(parsed);
        authorizeKnowledgeScope(cfg);
        String locale = BuiltinAgentRegistry.localeFromRequest(req.getHeader("Accept-Language"));
        var result = service.updateAgent(id, parsed.name(), parsed.description(), parsed.avatar(),
                cfg, locale);
        return ResponseEntity.ok(AgentResponses.agent(result));
    }

    // ── DELETE /agents/:id ──
    @DeleteMapping("/api/v1/agents/{id}")
    public ApiResponse<Void> delete(@PathVariable("id") String id, HttpServletRequest req) {
        requireNonEmpty(id);
        service.requireOwnedOrAdmin(id);
        service.deleteAgent(id);
        // 成功也带文案：由外壳的 message 承担（200 + {code:0,message:"Agent deleted successfully",data:null}）
        return ApiResponse.ok(null, "Agent deleted successfully");
    }

    // ── POST /agents/:id/copy ──
    @PostMapping("/api/v1/agents/{id}/copy")
    public ResponseEntity<AgentView> copy(@PathVariable("id") String id,
            HttpServletRequest req) {
        requireNonEmpty(id);
        String locale = BuiltinAgentRegistry.localeFromRequest(req.getHeader("Accept-Language"));
        // 先 getAgentByID（404 路径），再对 source config 做 API-Key scope 校验
        var source = service.getAgentById(id, locale);
        authorizeKnowledgeScope(source.config());
        var result = service.copyAgent(id, locale);
        return ResponseEntity.status(201).body(AgentResponses.agent(result));
    }

    // ── GET /agents/:id/suggested-questions ──
    @GetMapping("/api/v1/agents/{id}/suggested-questions")
    public ResponseEntity<Object> suggestedQuestions(@PathVariable("id") String id,
            @RequestParam(value = "knowledgeBaseIds", required = false) String kbIdsStr,
            @RequestParam(value = "knowledgeIds", required = false) String knowledgeIdsStr,
            @RequestParam(value = "tagScopes", required = false) String tagScopesStr,
            @RequestParam(value = "limit", required = false) String limitStr,
            HttpServletRequest req) {
        requireNonEmpty(id);
        List<String> kbIds = splitCsv(kbIdsStr);
        List<String> knowledgeIds = splitCsv(knowledgeIdsStr);
        List<TagScope> tagScopes = new ArrayList<>();
        if (tagScopesStr != null && !tagScopesStr.trim().isEmpty()) {
            try {
                JsonNode arr = MAPPER.readTree(tagScopesStr);
                if (arr.isArray()) {
                    for (JsonNode e : arr) {
                        List<String> tagIds = new ArrayList<>();
                        JsonNode ids = e.get("tagIds");
                        if (ids != null && ids.isArray()) {
                            for (JsonNode t : ids) {
                                tagIds.add(t.asText(""));
                            }
                        }
                        tagScopes.add(new TagScope(e.path("knowledgeBaseId").asText(""), tagIds));
                    }
                } else {
                    throw new IllegalArgumentException("not an array");
                }
            } catch (Exception ex) {
                throw new BizException(AppError.badRequest("tagScopes must be valid JSON"));
            }
        }
        int limit = 0;
        if (limitStr != null && !limitStr.isEmpty()) {
            try {
                int parsed = Integer.parseInt(limitStr.trim());
                if (parsed > 0) {
                    limit = parsed;
                }
            } catch (NumberFormatException ignored) {
                // 解析失败 → limit 保持 0（"unspecified"）
            }
        }
        String locale = BuiltinAgentRegistry.localeFromRequest(req.getHeader("Accept-Language"));
        var questions = service.getSuggestedQuestions(id, kbIds, knowledgeIds, tagScopes, limit,
                locale);
        return ResponseEntity.ok(questions);
    }

    // ── handler 私有段 ──

    private static void requireNonEmpty(String id) {
        if (id == null || id.isEmpty()) {
            throw new BizException(AppError.badRequest("Agent ID cannot be empty"));
        }
    }

    /**
     * 请求体绑定：name 必填（Create）→ 固定校验文案；
     * JSON 语法错误 → 标准 Jackson 消息。两个请求体都把它拼进
     * "Invalid request parameters" 的 details。
     */
    private static AgentRequest bindAgentRequest(String rawBody, boolean requireName) {
        if (rawBody == null || rawBody.isEmpty()) {
            throw invalidParams(JsonBodyErrors.EMPTY_BODY);
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(rawBody);
        } catch (Exception e) {
            throw invalidParams(e.getMessage());
        }
        String name = node == null || node.get("name") == null || node.get("name").isNull()
                ? "" : node.get("name").asText("");
        if (requireName && name.isEmpty()) {
            throw invalidParams(RequestFields.message("Name", "required"));
        }
        String description = node != null && node.get("description") != null
                && !node.get("description").isNull() ? node.get("description").asText("") : "";
        String avatar = node != null && node.get("avatar") != null
                && !node.get("avatar").isNull() ? node.get("avatar").asText("") : "";
        JsonNode config = node != null && node.get("config") != null && node.get("config").isObject()
                ? node.get("config") : MAPPER.createObjectNode();
        return new AgentRequest(name, description, avatar, config);
    }

    private static BizException invalidParams(String details) {
        return new BizException(AppError.badRequest("Invalid request parameters").withDetails(details));
    }

    private static ObjectNode configNode(AgentRequest parsed) {
        JsonNode cfg = parsed.config();
        return cfg == null || cfg.isNull() || !cfg.isObject()
                ? MAPPER.createObjectNode() : (ObjectNode) cfg;
    }

    /** API-Key 受限 scope 的 KB 白名单校验。 */
    private static void authorizeKnowledgeScope(ObjectNode cfg) {
        TenantAPIKeyScope scope = APIKeyScopeContext.current();
        if (scope == null || !scope.isKnowledgeBaseRestricted()) {
            return;
        }
        String mode = cfg.path("kbSelectionMode").asText("").toLowerCase().trim();
        switch (mode) {
            case "none" -> {
            }
            case "all" -> throw new BizException(AppError.forbidden(
                    "API key scope does not allow agents that use all knowledge bases"));
            case "selected" -> TenantAPIKeyScope.authorizeKnowledgeBases(strings(cfg, "knowledgeBases"));
            default -> {
                List<String> kbs = strings(cfg, "knowledgeBases");
                if (!kbs.isEmpty()) {
                    TenantAPIKeyScope.authorizeKnowledgeBases(kbs);
                }
            }
        }
    }


    /** 归属校验在 service（{@link CustomAgentService#requireOwnedOrAdmin}）。 */

    private static List<String> strings(ObjectNode cfg, String field) {
        List<String> out = new ArrayList<>();
        JsonNode n = cfg.get(field);
        if (n != null && n.isArray()) {
            for (JsonNode e : n) {
                out.add(e.asText(""));
            }
        }
        return out;
    }

    private static List<String> splitCsv(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) {
            return out;
        }
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }
}
