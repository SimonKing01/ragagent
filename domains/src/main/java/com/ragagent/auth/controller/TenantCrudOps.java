package com.ragagent.auth.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ragagent.auth.dto.TenantResponse;
import com.ragagent.auth.dto.TenantRowView;
import com.ragagent.contract.error.AppError;
import com.ragagent.contract.error.BizException;
import com.ragagent.contract.tenant.TenantRole;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.platform.web.JsonBodyErrors;
import com.ragagent.platform.web.ToolJson;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * tenants CRUD 协作者（自 {@link TenantCatalogController} 拆出）：单空间列表、读取/更新/删除与
 * updateTenantRequest 的手写绑定（legacy 绑定错误文案）。
 * 持门面回引取 tenantService。
 */
final class TenantCrudOps {

    private final TenantCatalogController service;

    TenantCrudOps(TenantCatalogController service) {
        this.service = service;
    }

    // ── tenants CRUD 4 条 ───────────────────────────────────────────────────

    /**
     * GET /tenants：返回**调用者活动空间**的单元素
     * 列表（不是全量目录——全量在 /tenants/all）。上下文无租户 → 401
     * "Authentication required"。本路由**无角色门**（只有全局 Auth）。
     */
    public Map<String, Object> listTenants() {
        Long tenantId = TenantContext.currentTenantId();
        TenantRowView tenant = tenantId == null || tenantId == 0 ? null : service.tenantService.getTenantById(tenantId);
        if (tenant == null) {
            throw new BizException(AppError.unauthorized("Authentication required"));
        }
        List<TenantResponse> items = new ArrayList<>();
        items.add(TenantResponse.from(tenant, contextRoleHasAdmin()));
        return Map.of("items", items);
    }

    /**
     * GET /tenants/{id}。URL :id 的合法性由
     * PathTenantMatch 在中间件层先行校验/拒绝（handler 里的 "Invalid workspace ID"
     * 400 是不可达死代码）；租户缺失 → 500 "Failed to retrieve workspace"
     * details "record not found"（仓储层错误不是 AppError）。
     */
    public TenantResponse getTenant(@PathVariable("id") String id) {
        TenantRowView tenant = loadTenantOr500(Long.parseLong(id.trim()), "Failed to retrieve workspace");
        return TenantResponse.from(tenant, contextRoleHasAdmin());
    }

    /**
     * PUT /tenants/{id}：白名单只开 name/description
     *（可空类型区分"未携带"与"显式空串"）。绑定失败 400 "Invalid request data"+details；
     * name trim 后空 → 400 "name cannot be blank"；其余复用 kv 分发器的 500 形态。
     */
    public TenantResponse updateTenant(@PathVariable("id") String id,
                                            @RequestBody(required = false) String rawBody) {
        UpdateTenantRequest req = bindUpdateTenantRequest(rawBody);
        TenantRowView existing = loadTenantOr500(Long.parseLong(id.trim()), "Failed to load workspace");
        // 注意：绑定（400 语义层）先于租户加载

        if (req.name != null) {
            String trimmed = TenantBindSupport.trimGo(req.name);
            if (trimmed.isEmpty()) {
                throw new BizException(AppError.validation("name cannot be blank"));
            }
            existing.setName(trimmed);
        }
        if (req.description != null) {
            existing.setDescription(TenantBindSupport.trimGo(req.description));
        }

        try {
            service.tenantService.updateTenant(existing);
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BizException(AppError.internal("Failed to update workspace")
                    .withDetails(e.getMessage()));
        }
        return TenantResponse.from(existing, contextRoleHasAdmin());
    }

    /** 更新请求载体：name min=1,max=128；description max=512（按码点计）。 */
    static final class UpdateTenantRequest {
        @JsonProperty("name")
        String name;
        @JsonProperty("description")
        String description;
    }

    /**
     * 绑定 + validator（按码点计长；失败字段按声明序 join("\n")）。
     * 类型错给 legacy 绑定错误原文（具名 struct →
     * "updateTenantRequest.name of type string"——golden w5a-tenant-put-badjson 钉住）。
     */
    private UpdateTenantRequest bindUpdateTenantRequest(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            throw TenantBindSupport.invalidParams("Invalid request data", JsonBodyErrors.EMPTY_BODY);
        }
        com.fasterxml.jackson.databind.JsonNode root;
        try {
            root = TenantBindSupport.MAPPER.readTree(rawBody);
        } catch (Exception e) {
            throw TenantBindSupport.invalidParams("Invalid request data",
                    e.getMessage());
        }
        if (root == null || !root.isObject()) {
            if (root == null || root.isNull()) {
                return new UpdateTenantRequest();
            }
            throw TenantBindSupport.invalidParams("Invalid request data",
                    ToolJson.expectedObjectMessage(root));
        }
        String typeError = TenantBindSupport.stringFieldTypeError(root, "name");
        if (typeError == null) {
            typeError = TenantBindSupport.stringFieldTypeError(root, "description");
        }
        if (typeError != null) {
            throw TenantBindSupport.invalidParams("Invalid request data", typeError);
        }
        UpdateTenantRequest req = new UpdateTenantRequest();
        req.name = root.get("name") == null || root.get("name").isNull() ? null : root.get("name").asText();
        req.description = root.get("description") == null || root.get("description").isNull()
                ? null : root.get("description").asText();
        List<String> errors = new ArrayList<>();
        if (req.name != null) {
            int len = req.name.codePointCount(0, req.name.length());
            if (len < 1) {
                errors.add(TenantBindSupport.bindingError("Name", "min"));
            } else if (len > 128) {
                errors.add(TenantBindSupport.bindingError("Name", "max"));
            }
        }
        if (req.description != null && req.description.codePointCount(0, req.description.length()) > 512) {
            errors.add(TenantBindSupport.bindingError("Description", "max"));
        }
        if (!errors.isEmpty()) {
            throw TenantBindSupport.invalidParams("Invalid request data", String.join("\n", errors));
        }
        return req;
    }


    /**
     * DELETE /tenants/{id}：repo 层软删成员+租户、
     * 删不存在的 id 同样成功 → 204。
     */
    public org.springframework.http.ResponseEntity<Void> deleteTenant(@PathVariable("id") String id) {
        try {
            service.tenantService.deleteTenant(Long.parseLong(id.trim()));
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BizException(AppError.internal("Failed to delete workspace")
                    .withDetails(e.getMessage()));
        }
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    /** GET/PUT 共用：租户缺失 → 500 + details "record not found"（仓储错误原文透传）。 */
    private TenantRowView loadTenantOr500(long id, String message) {
        TenantRowView tenant = service.tenantService.getTenantById(id);
        if (tenant == null) {
            throw new BizException(AppError.internal(message).withDetails("record not found"));
        }
        return tenant;
    }

    /** 秘密字段是否输出 = 当前上下文角色 ≥ admin。 */
    private static boolean contextRoleHasAdmin() {
        return TenantRole.fromString(TenantContext.currentRole()).hasPermission(TenantRole.ADMIN);
    }
}
