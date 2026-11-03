package com.ragagent.storage.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.contract.error.BizException;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.platform.storage.config.StorageAllowList;
import com.ragagent.platform.web.ApiResponse;
import com.ragagent.platform.web.ApiResult;
import com.ragagent.platform.web.JsonBodyErrors;
import com.ragagent.platform.web.JsonMappers;
import com.ragagent.platform.web.RequestFields;
import com.ragagent.storage.dto.StorageConfig;
import com.ragagent.storage.service.StorageBackendReadService;
import com.ragagent.storage.service.StorageBackendService;
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
 * 存储后端管理面：9 条路由；读 Viewer+ / 写与测试 Admin+。
 *
 * <p>**全 AppError 信封**（与 wsp/vs 的纯字符串 404 刻意不同）：404 = code 1003。
 * PUT **先绑 body 再进 service**（与 wsp 的 ownership-first 相反——
 * 未知 id + 坏 body 落 400 EOF）。TestRaw/TestByID 的连通失败是 **200** +
 * {@code {"success":false,"error":清洗后文案}}（storageTestErrorMessage：AppError 取
 * message、其余经 SanitizeStorageConnectivityError 映射）；校验/SSRF 失败走信封
 * （1000/1010）。storage handler **不校验租户缺失**（tenantId=0 时 list 为空、
 * get 404）。</p>
 */
@RestController
@ApiResult
@RequestMapping("/api/v1/storage-backends")
@Tag(name = "Storage")
public class StorageBackendController {

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    private final StorageBackendService service;
    private final StorageBackendReadService readService;
    private final StorageAllowList allowList;

    public StorageBackendController(StorageBackendService service,
                                    StorageBackendReadService readService,
                                    StorageAllowList allowList) {
        this.service = service;
        this.readService = readService;
        this.allowList = allowList;
    }

    /** 请求体：name/provider required */
    public record StorageBackendRequest(
            String name,
            String provider,
            StorageConfig config,
            String status) {}

    @GetMapping("/types")
    public ResponseEntity<?> types() {
        return ResponseEntity.ok(allowList.allowedList());
    }

    @PostMapping("/test")
    public ResponseEntity<?> testRaw(@RequestBody(required = false) String rawBody) {
        long tenantId = tenantId();
        StorageBackendRequest req = bind(rawBody);
        return ResponseEntity.ok(readService.testRawResponse(
                tenantId, req.name(), req.provider(), req.config(), req.status()));
    }

    /** 创建后端：201 + 裸资源。 */
    @PostMapping
    public ResponseEntity<?> create(@RequestBody(required = false) String rawBody) {
        long tenantId = tenantId();
        StorageBackendRequest req = bind(rawBody);
        return ResponseEntity.status(HttpStatus.CREATED).body(readService.createResponse(
                tenantId, req.name(), req.provider(), req.config(), req.status()));
    }

    /** 列表 = {items, defaultStorageBackendId}（游标外的附加字段形态）。 */
    @GetMapping
    public ResponseEntity<?> list() {
        long tenantId = tenantId();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", readService.listResponses(tenantId));
        body.put("defaultStorageBackendId",
                tenantId == 0 ? null : service.tenantDefaultBackendId(tenantId));
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable("id") String id) {
        long tenantId = tenantId();
        Object backend = readService.getResponse(tenantId, id);
        if (backend == null) {
            throw BizException.notFound("storage backend not found");
        }
        return ResponseEntity.ok(backend);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable("id") String id,
            @RequestBody(required = false) String rawBody) {
        long tenantId = tenantId();
        // 先绑定请求体再进 service（未知 id + 坏 body → 400，非 404）
        StorageBackendRequest req = bind(rawBody);
        Object updated = readService.updateResponse(
                tenantId, id, req.name(), req.provider(), req.config(), req.status());
        if (updated == null) {
            throw BizException.notFound("storage backend not found");
        }
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable("id") String id) {
        service.delete(tenantId(), id);
        return ApiResponse.ok();   // 204 退役（空体与「外壳恒存在」冲突）
    }

    @PutMapping("/{id}/default")
    public ApiResponse<Void> setDefault(@PathVariable("id") String id) {
        service.setDefault(tenantId(), id);
        return ApiResponse.ok();   // 204 退役（空体与「外壳恒存在」冲突）
    }

    @PostMapping("/{id}/test")
    public ResponseEntity<?> testById(@PathVariable("id") String id) {
        long tenantId = tenantId();
        Map<String, Object> result = readService.testByIdResponse(tenantId, id);
        if (result == null) {
            throw BizException.notFound("storage backend not found");
        }
        return ResponseEntity.ok(result);
    }

    // ── 内部 ───────────────────────────────────────────────────────────

    private static long tenantId() {
        Long tenantId = TenantContext.currentTenantId();
        return tenantId == null ? 0 : tenantId;
    }

    private static StorageBackendRequest bind(String rawBody) {
        if (rawBody == null || rawBody.isEmpty()) {
            throw BizException.badRequest(JsonBodyErrors.EMPTY_BODY);
        }
        StorageBackendRequest req;
        try {
            req = MAPPER.readValue(rawBody, StorageBackendRequest.class);
        } catch (Exception e) {
            throw BizException.badRequest(e.getMessage());
        }
        List<String> missing = new ArrayList<>();
        if (req.name() == null || req.name().isEmpty()) {
            missing.add("Name");
        }
        if (req.provider() == null || req.provider().isEmpty()) {
            missing.add("Provider");
        }
        if (!missing.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String field : missing) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(RequestFields.message(field, "required"));
            }
            throw BizException.badRequest(sb.toString());
        }
        return req;
    }

}
