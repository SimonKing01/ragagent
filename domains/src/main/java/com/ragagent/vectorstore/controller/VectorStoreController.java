package com.ragagent.vectorstore.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.contract.error.AppError;
import com.ragagent.contract.error.BizException;
import com.ragagent.contract.vectorstore.ConnectionConfig;
import com.ragagent.contract.vectorstore.IndexConfig;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.platform.web.ApiResponse;
import com.ragagent.platform.web.ApiResult;
import com.ragagent.platform.web.JsonBodyErrors;
import com.ragagent.platform.web.JsonMappers;
import com.ragagent.platform.web.RequestFields;
import com.ragagent.vectorstore.config.EnvVectorStores;
import com.ragagent.vectorstore.dto.VectorStoreTypes;
import com.ragagent.vectorstore.service.VectorStoreConfigService;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 向量库管理端（8 条路由；读 Viewer+ / 写与测试 Admin+）。
 *
 * <p>env store（__env_* 前缀）是**路径判定先于存在性**：PUT/DELETE 对任何 __env_*
 * id 一律 400 readonly（即使该 id 不存在）；GET /test 则先查 env 表 → 404。
 * 本部署 RETRIEVE_DRIVER 未配置 → env 列表恒空（部署状态，golden 钉住）。</p>
 *
 * <p><b>响应形态（统一外壳 {@code {code,message,data}}，见 docs/api/README.md）</b>：
 * 成功体一律外壳（列表/对象进 {@code data}；创建返 201，删除返 200 + 外壳，不用 204）；
 * 错误体同形（{@code {code,message,data}}），**HTTP 状态码随语义**（404/400/401…）。</p>
 *
 * <p>错误一律由 {@code BizException(AppError…)} 交给全局处理器，与其他域同形；
 * 服务端错误返回真实 HTTP 状态（4xx），前端按 reject 分支处理。</p>
 */
@RestController
@ApiResult
@RequestMapping("/api/v1/vector-stores")
@Tag(name = "Vector Store")
public class VectorStoreController {

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    private final VectorStoreConfigService service;

    public VectorStoreController(VectorStoreConfigService service) {
        this.service = service;
    }

    // ── 请求 DTO ─────────────────────────────────────────────────────────

    public record CreateStoreRequest(
            String name,
            String engineType,
            ConnectionConfig connectionConfig,
            IndexConfig indexConfig) {}

    public record UpdateStoreRequest(
            String name) {}

    public record TestStoreRequest(
            String engineType,
            ConnectionConfig connectionConfig) {}

    // ── 端点 ───────────────────────────────────────────────────────────

    @GetMapping("/types")
    public ResponseEntity<?> listStoreTypes() {
        return ResponseEntity.ok(VectorStoreTypes.all());
    }

    @PostMapping("/test")
    public ResponseEntity<?> testStoreRaw(@RequestBody(required = false) String rawBody) {
        requireTenant(); // 调用即鉴权（未认证会抛 TenantMissing）；返回值本方法不用
        TestStoreRequest req = bind(rawBody, TestStoreRequest.class);
        if (req.engineType() == null || req.engineType().isEmpty()) {
            throw validator("EngineType");
        }
        // ConnectionConfig 的必填校验不在这里做（嵌套结构校验行为不触发），
        // 直接落到引擎必填校验。
        // 错误由 BizException 携带码与状态，不在控制器内包装
        String version = service.testRawConnection(req.engineType(), req.connectionConfig());
        return ResponseEntity.ok(versionBody(version));
    }

    @PostMapping
    public ResponseEntity<?> createStore(@RequestBody(required = false) String rawBody) {
        long tenantId = requireTenant();
        JsonNode body = parseOrValidator(rawBody);
        String name = textOrNull(body, "name");
        String engineType = textOrNull(body, "engineType");
        List<String> missing = new ArrayList<>();
        if (name == null) {
            missing.add("Name");
        }
        if (engineType == null) {
            missing.add("EngineType");
        }
        if (!missing.isEmpty()) {
            throw validator(missing.toArray(new String[0]));
        }
        var created = service.createResponse(tenantId, name, engineType,
                configOf(body, "connectionConfig"), indexOf(body, "indexConfig"));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** env stores 在前、DB stores 在后（合并顺序固定） */
    @GetMapping
    public ResponseEntity<?> listStores() {
        long tenantId = requireTenant();
        return ResponseEntity.ok(service.listResponses(tenantId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getStore(@PathVariable("id") String id) {
        long tenantId = requireTenant();
        Object response = service.getResponse(tenantId, id);
        if (response == null) {
            throw notFoundPure();
        }
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateStore(@PathVariable("id") String id,
            @RequestBody(required = false) String rawBody) {
        long tenantId = requireTenant();
        if (EnvVectorStores.isEnvStoreId(id)) {
            throw readonlyEnv();
        }
        if (service.getResponse(tenantId, id) == null) {
            throw notFoundPure();
        }
        UpdateStoreRequest req = bind(rawBody, UpdateStoreRequest.class);
        if (req.name() == null || req.name().isEmpty()) {
            throw validator("Name");
        }
        Object result = service.updateNameResponse(tenantId, id, req.name());
        if (result == null) {
            throw notFoundPure();
        }
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteStore(@PathVariable("id") String id) {
        long tenantId = requireTenant();
        if (EnvVectorStores.isEnvStoreId(id)) {
            throw readonlyEnv();
        }
        if (service.getResponse(tenantId, id) == null) {
            throw notFoundPure();
        }
        service.delete(tenantId, id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/test")
    public ResponseEntity<?> testStoreByID(@PathVariable("id") String id) {
        long tenantId = requireTenant();
        String version = service.testResponse(tenantId, id);
        if (version == null) {
            throw notFoundPure();
        }
        return ResponseEntity.ok(versionBody(version));
    }

    // ── 内部 ───────────────────────────────────────────────────────────

    private static ConnectionConfig configOf(JsonNode body, String field) {
        JsonNode n = body.get(field);
        if (n == null || n.isNull()) {
            return new ConnectionConfig();
        }
        try {
            return MAPPER.treeToValue(n, ConnectionConfig.class);
        } catch (Exception e) {
            throw BizException.badRequest(e.getMessage());
        }
    }

    private static IndexConfig indexOf(JsonNode body, String field) {
        JsonNode n = body.get(field);
        if (n == null || n.isNull()) {
            return new IndexConfig();
        }
        try {
            return MAPPER.treeToValue(n, IndexConfig.class);
        } catch (Exception e) {
            throw BizException.badRequest(e.getMessage());
        }
    }

    private static String textOrNull(JsonNode body, String field) {
        JsonNode n = body.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }

    private static JsonNode parseOrValidator(String rawBody) {
        if (rawBody == null || rawBody.isEmpty()) {
            throw BizException.badRequest(JsonBodyErrors.EMPTY_BODY);
        }
        try {
            return MAPPER.readTree(rawBody);
        } catch (Exception e) {
            throw BizException.badRequest(e.getMessage());
        }
    }

    private static long requireTenant() {
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null || tenantId == 0) {
            throw BizException.unauthorized("unauthorized: workspace context missing");
        }
        return tenantId;
    }

    /** env store 只读 → 400（原为私有 ReadonlyEnvStore + 纯字符串体）。 */
    private static BizException readonlyEnv() {
        return BizException.badRequest("environment-configured vector stores cannot be modified via API");
    }

    private static BizException validator(String... fields) {
        StringBuilder sb = new StringBuilder();
        for (String field : fields) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(RequestFields.message(field, "required"));
        }
        return BizException.badRequest(sb.toString());
    }

    private static <T> T bind(String rawBody, Class<T> type) {
        if (rawBody == null || rawBody.isEmpty()) {
            throw BizException.badRequest(JsonBodyErrors.EMPTY_BODY);
        }
        try {
            return MAPPER.readValue(rawBody, type);
        } catch (Exception e) {
            throw BizException.badRequest(e.getMessage());
        }
    }

    /** 404 → BizException(AppError.notFound)（原为私有 PureNotFound + 纯字符串体）。 */
    private static BizException notFoundPure() {
        return new BizException(AppError.notFound("vector store not found"));
    }

    /** 连通性测试成功体：{version}（探测不到为空串照写）。 */
    private static Map<String, Object> versionBody(String version) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", version == null ? "" : version);
        return body;
    }


}
