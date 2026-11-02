package com.ragagent.auth.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.auth.dto.TenantMemberResponse;
import com.ragagent.auth.service.TenantMemberReadService;
import com.ragagent.auth.service.TenantMemberService;
import com.ragagent.auth.service.TenantRbacException;
import com.ragagent.contract.error.AppError;
import com.ragagent.contract.error.BizException;
import com.ragagent.contract.tenant.TenantRole;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.platform.web.ApiResponse;
import com.ragagent.platform.web.ApiResult;
import com.ragagent.platform.web.JsonBodyErrors;
import com.ragagent.platform.web.JsonMappers;
import com.ragagent.platform.web.RequestFields;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 租户成员管理的 5 条路由：
 * GET/POST /tenants/{id}/members、PUT/DELETE /tenants/{id}/members/{userId}、
 * POST /tenants/{id}/leave。
 *
 * <p>角色门禁与 PathTenantMatch 在 RbacInterceptor，controller 不复查角色。
 * 本类还承载成员/邀请两组共用的绑定与分页辅助
 * （parseTenantId / parseListPagination / addMemberAndRespond）。</p>
 *
 * <p><b>错误映射不共享</b>：各端点的哨兵→HTTP 映射各自一份——"找不到成员"的文案在
 * leave 里是 "you are not a member of this workspace"、别处是 "membership not
 * found"；直加/邀请的哨兵映射也不一致。controller 的 catch 逐端点维护，
 * 不做统一映射。</p>
 */
@RestController
@ApiResult
@Tag(name = "Auth")
public class TenantMemberController {

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    /** "system-<tenantID>" 合成用户（API-Key 主体）不入 invited_by */
    private static final String SYNTHETIC_PREFIX = "system-";

    private final TenantMemberService memberService;
    private final TenantMemberReadService readService;

    public TenantMemberController(TenantMemberService memberService,
                                  TenantMemberReadService readService) {
        this.memberService = memberService;
        this.readService = readService;
    }

    // ── 路由 ───────────────────────────────────────────────────────────────

    /** GET /tenants/{id}/members（Viewer+，分页 + q 筛选） */
    @GetMapping("/api/v1/tenants/{id}/members")
    public Map<String, Object> listMembers(@PathVariable String id, HttpServletRequest request) {
        long tenantId = parseTenantId(id);
        String q = trimToEmpty(request.getParameter("q"));
        int[] pp = parseListPagination(request);

        return readService.listMembers(tenantId, q, pp[0], pp[1]);
    }

    /** POST /tenants/{id}/members（Owner+，直加路径；201 + TenantMemberResponse） */
    @PostMapping("/api/v1/tenants/{id}/members")
    public org.springframework.http.ResponseEntity<TenantMemberResponse> addMember(
            @PathVariable String id, HttpServletRequest request) {
        long tenantId = parseTenantId(id);
        JsonNode body = bindJson(rawBody(request));
        requireFields(body, "email", "role");
        String email = body.path("email").asText();
        if (!isValidEmailFormat(email)) {
            throw new BizException(AppError.validation("invalid request body")
                    .withDetails(RequestFields.message("Email", "email")));
        }
        TenantRole role = TenantRole.fromString(body.path("role").asText());
        if (!role.isValid()) {
            // defence-in-depth：binding required 过了但角色值非法
            throw new BizException(AppError.validation("role must be one of owner/admin/contributor/viewer"));
        }

        String invitedBy = invitedByForCaller(TenantContext.currentUserId());
        TenantMemberReadService.PreparedResponse r = readService.addByEmail(
                trimToEmpty(email), tenantId, role, invitedBy);
        return org.springframework.http.ResponseEntity.status(r.status()).body(r.body());
    }

    /** PUT /tenants/{id}/members/{userId}（Owner+，改角色；ErrLastOwner → 409） */
    @PutMapping("/api/v1/tenants/{id}/members/{userId}")
    public ApiResponse<Void> updateMemberRole(@PathVariable String id,
                                                @PathVariable("userId") String userId,
                                                HttpServletRequest request) {
        long tenantId = parseTenantId(id);
        String targetUserId = trimToEmpty(userId);
        if (targetUserId.isEmpty()) {
            throw new BizException(AppError.validation("user_id is required"));
        }
        JsonNode body = bindJson(rawBody(request));
        requireFields(body, "role");
        TenantRole role = TenantRole.fromString(body.path("role").asText());
        if (!role.isValid()) {
            throw new BizException(AppError.validation("role must be one of owner/admin/contributor/viewer"));
        }

        try {
            memberService.updateRole(targetUserId, tenantId, role);
        } catch (TenantRbacException e) {
            switch (e.kind()) {
                case MEMBERSHIP_NOT_FOUND -> throw new BizException(AppError.notFound("membership not found"));
                case LAST_OWNER -> throw e.asConflict();
                case INVALID_TENANT_ROLE -> throw e.asValidationError();
                case API_KEY_CANNOT_ASSIGN_OWNER -> throw e.asForbidden();
                default -> {
                    log(e);
                    throw new BizException(AppError.internal("failed to update member role")
                            .withDetails(e.getMessage()));
                }
            }
        }
        return ApiResponse.ok();   // 204 退役（空体与「外壳恒存在」冲突）
    }

    /** DELETE /tenants/{id}/members/{userId}（Owner+，软删；ErrLastOwner → 409） */
    @DeleteMapping("/api/v1/tenants/{id}/members/{userId}")
    public ApiResponse<Void> removeMember(@PathVariable String id,
                                            @PathVariable("userId") String userId) {
        long tenantId = parseTenantId(id);
        String targetUserId = trimToEmpty(userId);
        if (targetUserId.isEmpty()) {
            throw new BizException(AppError.validation("user_id is required"));
        }
        try {
            memberService.removeMember(targetUserId, tenantId);
        } catch (TenantRbacException e) {
            switch (e.kind()) {
                case MEMBERSHIP_NOT_FOUND -> throw new BizException(AppError.notFound("membership not found"));
                case LAST_OWNER -> throw e.asConflict();
                default -> {
                    log(e);
                    throw new BizException(AppError.internal("failed to remove member")
                            .withDetails(e.getMessage()));
                }
            }
        }
        return ApiResponse.ok();   // 204 退役（空体与「外壳恒存在」冲突）
    }

    /** POST /tenants/{id}/leave（Viewer+，自助退出；ErrMembershipNotFound → 404 专属文案） */
    @PostMapping("/api/v1/tenants/{id}/leave")
    public ApiResponse<Void> leaveTenant(@PathVariable String id) {
        long tenantId = parseTenantId(id);
        String caller = requireCaller();
        try {
            memberService.removeMember(caller, tenantId);
        } catch (TenantRbacException e) {
            switch (e.kind()) {
                case MEMBERSHIP_NOT_FOUND ->
                        throw new BizException(AppError.notFound("you are not a member of this workspace"));
                case LAST_OWNER -> throw e.asConflict();
                default -> {
                    log(e);
                    throw new BizException(AppError.internal("failed to leave workspace")
                            .withDetails(e.getMessage()));
                }
            }
        }
        return ApiResponse.ok();   // 204 退役（空体与「外壳恒存在」冲突）
    }

    // ── 共享辅助（成员/邀请两组共用） ──────────────────────────────────────

    /** 路径租户 ID 解析（通常已被 PathTenantMatch 拦在前面，保留同文案） */
    static long parseTenantId(String raw) {
        String s = trimToEmpty(raw);
        if (s.isEmpty()) {
            throw new BizException(AppError.validation("workspace id is required"));
        }
        try {
            long v = Long.parseLong(s);
            if (v <= 0) {
                throw new NumberFormatException();
            }
            return v;
        } catch (NumberFormatException e) {
            throw new BizException(AppError.validation("workspace id must be a positive integer"));
        }
    }

    /** 路径邀请 ID 解析。 */
    static long parseInvitationId(String raw) {
        String s = trimToEmpty(raw);
        if (s.isEmpty()) {
            throw new BizException(AppError.validation("invitation id is required"));
        }
        try {
            long v = Long.parseLong(s);
            if (v <= 0) {
                throw new NumberFormatException();
            }
            return v;
        } catch (NumberFormatException e) {
            throw new BizException(AppError.validation("invitation id must be a positive integer"));
        }
    }

    /**
     * 分页参数：缺省 page=1 / size=20；**给了就必须合法**——
     * page 非正整数或非数字都是 400 "page must be a positive integer"，
     * size 出 [1,100] 是 400 "page_size must be between 1 and 100"（三段 if，不是 clamp）。
     */
    static int[] parseListPagination(HttpServletRequest request) {
        int page = 1;
        int pageSize = 20;
        String s = trimToEmpty(request.getParameter("page"));
        if (!s.isEmpty()) {
            try {
                int p = Integer.parseInt(s);
                if (p < 1) {
                    throw new NumberFormatException();
                }
                page = p;
            } catch (NumberFormatException e) {
                throw new BizException(AppError.validation("page must be a positive integer"));
            }
        }
        s = trimToEmpty(request.getParameter("page_size"));
        if (!s.isEmpty()) {
            try {
                int ps = Integer.parseInt(s);
                if (ps < 1 || ps > 100) {
                    throw new NumberFormatException();
                }
                pageSize = ps;
            } catch (NumberFormatException e) {
                throw new BizException(AppError.validation("page_size must be between 1 and 100"));
            }
        }
        return new int[] {page, pageSize};
    }

    static String trimToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    /** 合成用户判定：system-<id> 前缀（严格长于前缀） */
    static boolean isSyntheticUserId(String id) {
        if (id == null || id.length() <= SYNTHETIC_PREFIX.length()) {
            return false;
        }
        return id.startsWith(SYNTHETIC_PREFIX);
    }

    /** invitedBy 归一：合成主体（API-Key）→ null */
    static String invitedByForCaller(String caller) {
        if (caller == null || caller.isEmpty() || isSyntheticUserId(caller)) {
            return null;
        }
        return caller;
    }

    /**
     * 绑定 JSON body：解析失败 → 400 validation("invalid request body") +
     * details=legacy 解析器原文（标准 Jackson 消息；EOF 与字面量扫描逐字节一致）。
     */
    static JsonNode bindJson(String rawBody) {
        if (rawBody == null || rawBody.isEmpty()) {
            throw new BizException(AppError.validation("invalid request body")
                    .withDetails(JsonBodyErrors.EMPTY_BODY));
        }
        try {
            return MAPPER.readTree(rawBody);
        } catch (Exception e) {
            throw new BizException(AppError.validation("invalid request body")
                    .withDetails(e.getMessage()));
        }
    }

    /**
     * required 校验：string 是"非零值"（空串失败、
     * 纯空白通过）。多失败字段按声明序 \n 连接进 details。
     */
    static void requireFields(JsonNode body, String... jsonFields) {
        List<String> lines = new ArrayList<>();
        for (String field : jsonFields) {
            JsonNode n = body.get(field);
            boolean missing = n == null || n.isNull() || (n.isTextual() && n.asText().isEmpty());
            if (missing) {
                String cap = Character.toUpperCase(field.charAt(0)) + field.substring(1);
                lines.add(RequestFields.message(cap, "required"));
            }
        }
        if (!lines.isEmpty()) {
            throw new BizException(AppError.validation("invalid request body")
                    .withDetails(String.join("\n", lines)));
        }
    }

    /**
     * email 校验（简化子集：非空本地部分 + 恰一个 @ + 非空域名——
     * 完整 RFC 正则接受的边角更宽；golden 只钉 "notanemail" 拒绝这一形态）。
     */
    static boolean isValidEmailFormat(String email) {
        int at = email.indexOf('@');
        return at > 0 && at == email.lastIndexOf('@') && at < email.length() - 1;
    }


    static String requireCaller() {
        String caller = TenantContext.currentUserId();
        if (caller == null || caller.isEmpty()) {
            throw new BizException(AppError.unauthorized("caller user id missing from context"));
        }
        return caller;
    }

    static String rawBody(HttpServletRequest request) {
        if (request instanceof org.springframework.web.util.ContentCachingRequestWrapper cached) {
            byte[] buf = cached.getContentAsByteArray();
            return new String(buf, java.nio.charset.StandardCharsets.UTF_8);
        }
        try {
            return new String(request.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return "";
        }
    }

    private static void log(TenantRbacException e) {
        org.slf4j.LoggerFactory.getLogger(TenantMemberController.class)
                .error("tenant member op failed: kind={} msg={}", e.kind(), e.getMessage());
    }
}
