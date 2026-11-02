package com.ragagent.websearch.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.contract.error.BizException;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.platform.web.ApiResponse;
import com.ragagent.platform.web.ApiResult;
import com.ragagent.platform.web.JsonBodyErrors;
import com.ragagent.platform.web.JsonMappers;
import com.ragagent.websearch.service.WebSearchProviderService;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * web 搜索 provider 凭据面（两路由 Admin+）。
 *
 * <p>**全 AppError 信封**（与主 controller 的纯字符串 404 刻意不同）：
 * 租户缺失 → 400 "Workspace ID cannot be empty"（不是 401！）；api_key=null 的查询
 * 语义下查不到 provider → 404 code 1003；未知 field → 400（先于 provider 存在性）；
 * 写/清时 provider 缺失 → **500**（"failed to update credentials: ..." /
 * "failed to clear credential: ..."——service 的普通 error 被包成 internal，非 404）。</p>
 */
@RestController
@ApiResult
@RequestMapping("/api/v1/web-search-providers")
@Tag(name = "Web Search")
public class WebSearchProviderCredentialsController {

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    private final WebSearchProviderService service;

    public WebSearchProviderCredentialsController(WebSearchProviderService service) {
        this.service = service;
    }

    /** PUT 请求：apiKey 为可空——null = 查询状态语义 */
    @PutMapping("/{id}/credentials")
    public ResponseEntity<?> put(@PathVariable("id") String id,
            @RequestBody(required = false) String rawBody) {
        long tenantId = tenantId();
        String apiKey = parseApiKey(rawBody);
        if (apiKey == null) {
            Boolean configured = service.credentialConfigured(tenantId, id);
            if (configured == null) {
                throw BizException.notFound("web search provider not found");
            }
            return ResponseEntity.ok(fieldsBody(configured));
        }
        boolean configured;
        try {
            configured = service.updateCredentials(tenantId, id, apiKey);
        } catch (RuntimeException e) {
            throw BizException.internal("failed to update credentials: " + e.getMessage());
        }
        return ResponseEntity.ok(fieldsBody(configured));
    }

    @DeleteMapping("/{id}/credentials/{field}")
    public ApiResponse<Void> deleteField(@PathVariable("id") String id,
            @PathVariable("field") String field) {
        long tenantId = tenantId();
        if (!"apiKey".equals(field)) {
            throw BizException.badRequest("unknown credential field: " + field);
        }
        try {
            service.clearCredential(tenantId, id, field);
        } catch (RuntimeException e) {
            throw BizException.internal("failed to clear credential: " + e.getMessage());
        }
        return ApiResponse.ok();   // 204 退役（HttpStatus.NO_CONTENT 形态）
    }

    // ── 内部 ───────────────────────────────────────────────────────────

    /** 租户缺失（含 0）→ 400 "Workspace ID cannot be empty"（非 401） */
    private static long tenantId() {
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null || tenantId == 0) {
            throw BizException.badRequest("Workspace ID cannot be empty");
        }
        return tenantId;
    }

    /** 解析 api_key：键缺失/null → null（查询语义）；空 body → 400 EOF；坏 JSON → 400 解析器原文 */
    private static String parseApiKey(String rawBody) {
        if (rawBody == null || rawBody.isEmpty()) {
            throw BizException.badRequest(JsonBodyErrors.EMPTY_BODY);
        }
        JsonNode body;
        try {
            body = MAPPER.readTree(rawBody);
        } catch (Exception e) {
            throw BizException.badRequest(e.getMessage());
        }
        JsonNode key = body.get("apiKey");
        return key == null || key.isNull() ? null : key.asText();
    }

    /** 裸对象 {fields:{apiKey:{configured}}}（凭据状态形态）。 */
    private static Map<String, Object> fieldsBody(boolean configured) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("configured", configured);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("apiKey", field);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fields", fields);
        return body;
    }
}
