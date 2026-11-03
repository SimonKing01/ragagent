package com.ragagent.websearch.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.contract.apikey.APIKeyScopeContext;
import com.ragagent.contract.apikey.TenantAPIKeyScope;
import com.ragagent.contract.error.BizException;
import com.ragagent.contract.tenant.TenantRole;
import com.ragagent.contract.websearch.WebSearchResult;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.platform.web.ApiResponse;
import com.ragagent.platform.web.ApiResult;
import com.ragagent.platform.web.JsonBodyErrors;
import com.ragagent.platform.web.JsonMappers;
import com.ragagent.platform.web.RequestFields;
import com.ragagent.websearch.config.WebSearchProviderParams;
import com.ragagent.websearch.dto.WebSearchProviderResponse;
import com.ragagent.websearch.dto.WebSearchProviderTestTarget;
import com.ragagent.websearch.dto.WebSearchProviderTypes;
import com.ragagent.websearch.provider.EmptyTestResults;
import com.ragagent.websearch.provider.WebSearchProvider;
import com.ragagent.websearch.provider.WebSearchProviderRegistry;
import com.ragagent.websearch.service.WebSearchProviderService;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * web 搜索 provider 管理面（10 条路由；角色门：读 Viewer+ / 写 Admin+）。
 *
 * <p>错误形态分层（逐端点固定形态，不做统一映射）：</p>
 * <ul>
 *   <li>租户缺失 → 401 纯字符串 {@code {"success":false,"error":"unauthorized: workspace context missing"}}
 *       （键字母序 error &lt; success）；</li>
 *   <li>绑定失败 → 400 AppError 信封 code 1000（EOF / validator 原文，多字段按 struct 序
 *       {@code \n} 连接）；</li>
 *   <li>getOwned 404 → **纯字符串** {@code {"error":"web search provider not found","success":false}}；</li>
 *   <li>service 业务失败（create/update/delete-credentials）→ **500** 信封 code 1007 + 原文；</li>
 *   <li>test 端点的测试失败 → **200** 纯字符串 {@code {"error": err.Error(), "success": false}}
 *       （doTestSearch 产的是普通 error，无 AppError 双前缀）。</li>
 * </ul>
 */
@RestController
@ApiResult
@RequestMapping("/api/v1/web-search-providers")
@Tag(name = "Web Search")
public class WebSearchProviderController {

    private static final Logger log = LoggerFactory.getLogger(WebSearchProviderController.class);
    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    private final WebSearchProviderService service;
    private final WebSearchProviderRegistry registry;

    public WebSearchProviderController(WebSearchProviderService service,
                                       WebSearchProviderRegistry registry) {
        this.service = service;
        this.registry = registry;
    }

    // ── /types（Viewer+）：静态元数据 ──────────────────────────────────

    @GetMapping("/types")
    public ResponseEntity<?> listProviderTypes() {
        return ResponseEntity.ok(WebSearchProviderTypes.all());
    }

    // ── POST /test（Admin+）：原始凭据连通性 ───────────────────────────

    /** 请求体：provider required；parameters 可选 */
    public record TestProviderRequest(
            String provider,
            WebSearchProviderParams parameters) {}

    @PostMapping("/test")
    public ResponseEntity<?> testProviderRaw(@RequestBody(required = false) String rawBody) {
        TestProviderRequest req = bind(rawBody, TestProviderRequest.class);
        if (req.provider() == null || req.provider().isEmpty()) {
            throw validatorError("Provider");
        }
        doTestSearch(req.provider(), req.parameters());
        return ResponseEntity.ok(connectedBody());
    }

    // ── CRUD ───────────────────────────────────────────────────────────

    /** 创建请求：name/provider required（校验失败按字段声明序用 \n 拼接） */
    @PostMapping
    public ResponseEntity<?> createProvider(@RequestBody(required = false) String rawBody) {
        long tenantId = requireTenant();
        JsonNode body = parseBody(rawBody);
        String name = textOrNull(body, "name");
        String provider = textOrNull(body, "provider");
        List<String> missing = new java.util.ArrayList<>();
        if (name == null) {
            missing.add("Name");
        }
        if (provider == null) {
            missing.add("Provider");
        }
        if (!missing.isEmpty()) {
            throw validatorError(missing.toArray(new String[0]));
        }
        WebSearchProviderResponse created;
        try {
            created = service.createResponse(tenantId, sanitize(name), provider,
                    sanitize(textOrEmpty(body, "description")), paramsOf(body),
                    boolOrFalse(body, "isDefault"), canViewIntegrationSecrets());
        } catch (RuntimeException e) {
            log.warn("Failed to create web search provider", e);
            // 这里兜的主要是 provider type / 参数校验（文案对调用方有用，如 "invalid provider type: bogus"），
            // 与「内部故障不可外泄」不同批处理：先把入参校验从 internal 里分出来，才能换固定文案。
            throw BizException.internal(e.getMessage());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    public ResponseEntity<?> listProviders() {
        long tenantId = requireTenant();
        return ResponseEntity.ok(service.listResponses(tenantId, canViewIntegrationSecrets()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getProvider(@PathVariable("id") String id) {
        long tenantId = requireTenant();
        WebSearchProviderResponse provider = service.getResponse(
                tenantId, id, canViewIntegrationSecrets());
        if (provider == null) {
            throw notFoundPure("web search provider not found");
        }
        return ResponseEntity.ok(provider);
    }

    /** 更新请求：无 required 字段；merge 规则见下 */
    public record UpdateProviderRequest(
            String name,
            String description,
            WebSearchProviderParams parameters,
            Boolean isDefault) {}

    @PutMapping("/{id}")
    public ResponseEntity<?> updateProvider(@PathVariable("id") String id,
            @RequestBody(required = false) String rawBody) {
        long tenantId = requireTenant();
        // ownership 检查先于 body 反序列化（未知 id + 坏 body 都是 404）
        if (service.getResponse(tenantId, id, false) == null) {
            throw notFoundPure("web search provider not found");
        }
        UpdateProviderRequest req = bind(rawBody, UpdateProviderRequest.class);
        String name = req.name() == null ? null : sanitize(req.name());
        String description = req.description() == null ? null : sanitize(req.description());
        try {
            WebSearchProviderResponse updated = service.updateResponse(tenantId, id, name,
                    description, req.parameters(), req.isDefault(), canViewIntegrationSecrets());
            if (updated != null) {
                return ResponseEntity.ok(updated);
            }
        } catch (RuntimeException e) {
            log.warn("Failed to update web search provider {}", id, e);
            throw BizException.internal("failed to update web search provider");
        }
        return ResponseEntity.ok(connectedBody());
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteProvider(@PathVariable("id") String id) {
        long tenantId = requireTenant();
        if (service.getResponse(tenantId, id, false) == null) {
            throw notFoundPure("web search provider not found");
        }
        try {
            service.delete(tenantId, id);
        } catch (RuntimeException e) {
            log.warn("Failed to delete web search provider {}", id, e);
            throw BizException.internal("failed to delete web search provider");
        }
        return ApiResponse.ok();   // 204 退役（空体与「外壳恒存在」冲突）
    }

    // ── POST /{id}/test（Admin+）：已存 provider 连通性 ─────────────────

    @PostMapping("/{id}/test")
    public ResponseEntity<?> testProviderById(@PathVariable("id") String id) {
        long tenantId = requireTenant();
        WebSearchProviderTestTarget target = service.getTestTarget(tenantId, id);
        if (target == null) {
            throw notFoundPure("web search provider not found");
        }
        doTestSearch(target.providerType(), target.parameters());
        return ResponseEntity.ok(connectedBody());
    }

    // ── 内部辅助 ───────────────────────────────────────────────────────

    /**
     * 连通性测试：创建 provider → {@code search("test", 1, false)} → 空结果 →
     * EmptyTestResultsError 文案；三支失败均以 200 纯字符串输出（{@link TestFailure}）。
     * 真实出站受本部署 SSRF 白名单约束（白名单外目标在触网前
     * 即拒，错误原文经 TestFailure 200 输出）。
     */
    private void doTestSearch(String providerType, WebSearchProviderParams params) {
        log.info("[WebSearch][Test] testing provider type={}", providerType);
        WebSearchProvider provider;
        try {
            provider = registry.createProvider(providerType, params);
        } catch (RuntimeException e) {
            log.warn("[WebSearch][Test] failed to create provider", e);
            throw new TestFailure("failed to create provider: " + messageOf(e));
        }
        List<WebSearchResult> results;
        try {
            results = provider.search("test", 1, false);
        } catch (RuntimeException e) {
            log.warn("[WebSearch][Test] search failed", e);
            throw new TestFailure(messageOf(e));
        }
        if (results == null || results.isEmpty()) {
            String message = EmptyTestResults.emptyTestResultsError(providerType, provider).getMessage();
            log.warn("[WebSearch][Test] {}", message);
            throw new TestFailure(message);
        }
        log.info("[WebSearch][Test] succeeded: type={}, results={}", providerType, results.size());
    }

    private static String messageOf(RuntimeException e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    /** test 端点的失败形态：200 纯字符串（不走全局异常的信封） */
    public static class TestFailure extends RuntimeException {
        public TestFailure(String message) {
            super(message);
        }
    }

    /** getOwned 的 404 是纯字符串形态（不走全局错误信封） */
    public static class PureNotFound extends RuntimeException {
        public PureNotFound(String message) {
            super(message);
        }
    }

    private static PureNotFound notFoundPure(String message) {
        return new PureNotFound(message);
    }

    private static long requireTenant() {
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null || tenantId == 0) {
            throw new TenantMissing();
        }
        return tenantId;
    }

    /** 租户缺失：401 纯字符串（非全局错误信封） */
    public static class TenantMissing extends RuntimeException {
    }

    /** 可见凭据：Admin+，或持有全量/管理租户设置能力的 API key */
    static boolean canViewIntegrationSecrets() {
        if (TenantRole.fromString(TenantContext.currentRole()).hasPermission(TenantRole.ADMIN)) {
            return true;
        }
        if (!APIKeyScopeContext.present()) {
            return false;
        }
        TenantAPIKeyScope scope = APIKeyScopeContext.current();
        if (scope == null) {
            return false;
        }
        return scope.fullAccess() || scope.hasCapability("manage_tenant_settings");
    }

    static WebSearchProviderParams paramsOf(JsonNode body) {
        JsonNode node = body.get("parameters");
        if (node == null || node.isNull()) {
            return new WebSearchProviderParams();
        }
        try {
            return MAPPER.treeToValue(node, WebSearchProviderParams.class);
        } catch (Exception e) {
            throw BizException.badRequest(e.getMessage());
        }
    }

    static String textOrNull(JsonNode body, String field) {
        JsonNode n = body.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }

    static String textOrEmpty(JsonNode body, String field) {
        String v = textOrNull(body, field);
        return v == null ? "" : v;
    }

    static boolean boolOrFalse(JsonNode body, String field) {
        JsonNode n = body.get(field);
        return n != null && n.asBoolean(false);
    }

    /** 日志安全脱敏（换行/制表符→空格、去控制字符）——结果同时是**存储值** */
    static String sanitize(String input) {
        if (input == null || input.isEmpty()) {
            return input == null ? "" : "";
        }
        String s = input.replace("\n", " ").replace("\r", " ").replace("\t", " ");
        StringBuilder b = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> {
            if (cp >= 32) {
                b.appendCodePoint(cp);
            }
        });
        return b.toString();
    }

    static JsonNode parseBody(String rawBody) {
        if (rawBody == null || rawBody.isEmpty()) {
            throw BizException.badRequest(JsonBodyErrors.EMPTY_BODY);
        }
        try {
            return MAPPER.readTree(rawBody);
        } catch (Exception e) {
            throw BizException.badRequest(e.getMessage());
        }
    }

    /** 校验错误形态：多失败字段按字段声明序用 \n 连接（message 内） */
    static BizException validatorError(String... fields) {
        StringBuilder sb = new StringBuilder();
        for (String field : fields) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(RequestFields.message(field, "required"));
        }
        return BizException.badRequest(sb.toString());
    }

    /** 绑定 JSON：EOF / 解析器原文 → 400 code 1000 */
    static <T> T bind(String rawBody, Class<T> type) {
        if (rawBody == null || rawBody.isEmpty()) {
            throw BizException.badRequest(JsonBodyErrors.EMPTY_BODY);
        }
        try {
            return MAPPER.readValue(rawBody, type);
        } catch (Exception e) {
            throw BizException.badRequest(e.getMessage());
        }
    }



    /** 连通性测试成功体：{connected:true}（与失败体 {connected:false,error} 同形对称）。 */
    private static Map<String, Object> connectedBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("connected", true);
        return body;
    }

    /** 纯字符串错误体：{"error": msg}（不进全局信封） */
    public static Map<String, Object> errorEnvelope(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return body;
    }

    // ── 本控制器私有的错误形态（不进全局信封） ──

    @ExceptionHandler(PureNotFound.class)
    public ResponseEntity<?> handlePureNotFound(PureNotFound e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorEnvelope(e.getMessage()));
    }

    @ExceptionHandler(TenantMissing.class)
    public ResponseEntity<?> handleTenantMissing() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(errorEnvelope("unauthorized: workspace context missing"));
    }

    @ExceptionHandler(TestFailure.class)
    public ResponseEntity<?> handleTestFailure(TestFailure e) {
        return ResponseEntity.ok(errorEnvelope(e.getMessage()));
    }
}
