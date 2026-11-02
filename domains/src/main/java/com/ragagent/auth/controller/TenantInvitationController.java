package com.ragagent.auth.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.auth.dto.TenantInvitationResponse;
import com.ragagent.auth.dto.TenantRowView;
import com.ragagent.auth.service.TenantInvitationReadService;
import com.ragagent.auth.service.TenantInvitationService;
import com.ragagent.auth.service.TenantRbacException;
import com.ragagent.auth.service.TenantService;
import com.ragagent.auth.service.UserService;
import com.ragagent.contract.error.AppError;
import com.ragagent.contract.error.BizException;
import com.ragagent.contract.error.ErrorCode;
import com.ragagent.contract.tenant.TenantRole;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.platform.web.ApiResponse;
import com.ragagent.platform.web.ApiResult;
import com.ragagent.platform.web.RequestFields;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 邀请相关的 9 条路由：租户侧 GET/POST /tenants/{id}/invitations、
 * DELETE /tenants/{id}/invitations/{invId}、POST /tenants/{id}/invite-links；
 * 收件箱 GET /me/invitations、GET /me/invitations/pending-count、
 * POST /me/invitations/{invId}/accept、POST /me/invitations/{invId}/decline、
 * POST /me/invitations/accept-by-token。
 *
 * <p><b>角色语义</b>：租户侧读 Viewer+、写 Owner+（RbacInterceptor）；/me 收件箱
 * 五条<b>无角色门</b>（只挂 Auth），"只有被邀请人能操作"由 service 层把守。
 * 注意 accept-by-token 也是**已登录**路由（与 /auth/register-by-invite 的公开+限流不同）。</p>
 *
 * <p><b>两层 404 刻意不同</b>：revoke 先 GetByID 再校验 inv.TenantID == URL :id——
 * 跨租户渲染成同款 "invitation not found"（不泄漏存在性）。</p>
 */
@RestController
@ApiResult
@Tag(name = "Auth")
public class TenantInvitationController {

    private final TenantInvitationService invitationService;
    private final UserService userService;
    private final TenantService tenantService;
    private final TenantInvitationReadService readService;

    public TenantInvitationController(TenantInvitationService invitationService,
                                      UserService userService,
                                      TenantService tenantService,
                                      TenantInvitationReadService readService) {
        this.invitationService = invitationService;
        this.userService = userService;
        this.tenantService = tenantService;
        this.readService = readService;
    }

    // ── 租户侧 ─────────────────────────────────────────────────────────────

    /** GET /tenants/{id}/invitations（Viewer+；Owner 及以上附 invite_url） */
    @GetMapping("/api/v1/tenants/{id}/invitations")
    public Map<String, Object> listTenantInvitations(@PathVariable String id, HttpServletRequest request) {
        long tenantId = TenantMemberController.parseTenantId(id);
        boolean includeTerminal = "true".equalsIgnoreCase(
                TenantMemberController.trimToEmpty(request.getParameter("include_terminal")));
        int[] pp = TenantMemberController.parseListPagination(request);

        var page =
                invitationService.listTenantInvitationsPage(tenantId, includeTerminal, pp[0], pp[1]);
        var usersById = readService.hydrateUsers(page.items());
        // Share-link URL 内嵌注册 token——只有 Owner 及以上可以重取；其他角色看元数据
        boolean showShareLinks = currentRoleHasOwner();
        List<TenantInvitationResponse> resp = new ArrayList<>(page.items().size());
        for (var inv : page.items()) {
            // 租户视图不 hydrate 租户名（调用方已知道租户）——传 null
            resp.add(showShareLinks
                    ? readService.projectInvitationWithLink(inv, usersById, null)
                    : readService.projectInvitation(inv, usersById, null));
        }
        // 响应键按字母序：invitations < page < pageSize < total
        Map<String, Object> data = new LinkedHashMap<>();
        // 键按字母序：invitations < page < pageSize < total
        data.put("invitations", resp);
        data.put("page", pp[0]);
        data.put("pageSize", pp[1]);
        data.put("total", page.total());
        return data;
    }

    /** POST /tenants/{id}/invitations（Owner+；auto-accept 开启时直加成员） */
    @PostMapping("/api/v1/tenants/{id}/invitations")
    public ResponseEntity<?> createInvitation(@PathVariable String id,
                                                                HttpServletRequest request) {
        long tenantId = TenantMemberController.parseTenantId(id);
        JsonNode body = TenantMemberController.bindJson(TenantMemberController.rawBody(request));
        TenantMemberController.requireFields(body, "email", "role");
        String email = body.path("email").asText();
        if (!TenantMemberController.isValidEmailFormat(email)) {
            throw new BizException(AppError.validation("invalid request body")
                    .withDetails(RequestFields.message("Email", "email")));
        }
        TenantRole role = TenantRole.fromString(body.path("role").asText());
        if (!role.isValid()) {
            throw new BizException(AppError.validation("role must be one of owner/admin/contributor/viewer"));
        }
        String message = body.path("message").isTextual() ? body.path("message").asText() : "";

        var user = userService.getUserByEmail(TenantMemberController.trimToEmpty(email));
        if (user == null) {
            throw new BizException(AppError.notFound(
                    "user with this email is not registered; ask them to sign up first"));
        }
        String invitedBy = TenantMemberController.invitedByForCaller(TenantContext.currentUserId());

        // auto-accept 开关：跳过 pending 邀请、直接把已注册用户加成成员（含对账+采纳默认空间）
        if (TenantInvitationService.autoAcceptInvitationEnabled()) {
            var r =
                    readService.autoAcceptInvitationAndRespond(user, tenantId, role, invitedBy);
            return ResponseEntity.status(r.status()).contentType(MediaType.APPLICATION_JSON).body(r.body());
        }

        try {
            return ResponseEntity.status(201).body(
                    readService.createInvitationResponse(
                            tenantId, user, role, invitedBy, message));
        } catch (TenantRbacException e) {
            switch (e.kind()) {
                case INVALID_TENANT_ROLE -> throw e.asValidationError();
                case API_KEY_CANNOT_ASSIGN_OWNER -> throw e.asForbidden();
                case PENDING_INVITATION_EXISTS -> throw e.asConflict();
                case ALREADY_MEMBER -> throw e.asConflict();
                default -> throw new BizException(AppError.internal("failed to create invitation")
                        .withDetails(e.getMessage()));
            }
        }
    }

    /** DELETE /tenants/{id}/invitations/{invId}（Owner+，撤销 pending） */
    @DeleteMapping("/api/v1/tenants/{id}/invitations/{invId}")
    public ApiResponse<Void> revokeInvitation(@PathVariable String id,
            @PathVariable("invId") String invId) {
        long tenantId = TenantMemberController.parseTenantId(id);
        long invIdNum = TenantMemberController.parseInvitationId(invId);

        // 跨租户校验邀请行的 tenant_id——不匹配渲染成同款 404（不泄漏存在性）
        var inv = invitationService.getById(invIdNum);
        if (inv == null) {
            throw new BizException(AppError.notFound("invitation not found"));
        }
        if (inv.getTenantId() != tenantId) {
            throw new BizException(AppError.notFound("invitation not found"));
        }
        try {
            invitationService.revoke(invIdNum);
        } catch (TenantRbacException e) {
            switch (e.kind()) {
                case INVITATION_NOT_FOUND -> throw e.asNotFound();
                case INVITATION_NOT_PENDING -> throw e.asConflict();
                default -> throw new BizException(AppError.internal("failed to revoke invitation")
                        .withDetails(e.getMessage()));
            }
        }
        return ApiResponse.ok();   // 204 退役（空体与「外壳恒存在」冲突）
    }

    /** POST /tenants/{id}/invite-links（Owner+，多用途共享链接；201 + 带 invite_url） */
    @PostMapping("/api/v1/tenants/{id}/invite-links")
    public ResponseEntity<TenantInvitationResponse> createInviteLink(@PathVariable String id,
                                                                HttpServletRequest request) {
        long tenantId = TenantMemberController.parseTenantId(id);
        JsonNode body = TenantMemberController.bindJson(TenantMemberController.rawBody(request));
        TenantMemberController.requireFields(body, "role");
        TenantRole role = TenantRole.fromString(body.path("role").asText());
        if (!role.isValid()) {
            throw new BizException(AppError.validation("role must be one of owner/admin/contributor/viewer"));
        }
        String message = body.path("message").isTextual() ? body.path("message").asText() : "";
        String invitedBy = TenantMemberController.invitedByForCaller(TenantContext.currentUserId());

        var result = readService.createShareLinkResponse(
                tenantId, role, invitedBy, message);
        return ResponseEntity.status(201).body(result);
    }

    // ── 收件箱（/me/invitations*，登录即可、无角色门） ────────────────────────

    /** GET /me/invitations（默认仅 pending；include_terminal=true 带终止态） */
    @GetMapping("/api/v1/me/invitations")
    public Map<String, Object> listMyInvitations(HttpServletRequest request) {
        String caller = TenantMemberController.requireCaller();
        boolean includeTerminal = "true".equalsIgnoreCase(
                TenantMemberController.trimToEmpty(request.getParameter("include_terminal")));
        var rows = invitationService.listByInvitee(caller, includeTerminal);
        var usersById = readService.hydrateUsers(rows);
        Map<Long, TenantRowView> tenantsById = readService.hydrateTenants(rows);
        List<TenantInvitationResponse> resp = new ArrayList<>(rows.size());
        for (var inv : rows) {
            resp.add(readService.projectInvitation(inv, usersById, tenantsById));
        }
        // 键按字母序：invitations < total（没有 page/pageSize 键）
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("invitations", resp);
        data.put("total", resp.size());
        return data;
    }

    /** GET /me/invitations/pending-count（头像角标轮询的轻量端点） */
    @GetMapping("/api/v1/me/invitations/pending-count")
    public Map<String, Object> countMyPendingInvitations() {
        String caller = TenantMemberController.requireCaller();
        long count = invitationService.countPendingByInvitee(caller);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pendingCount", count);
        return data;
    }

    /** POST /me/invitations/{invId}/accept（接受 + 写成员行 + 首空间采纳） */
    @PostMapping("/api/v1/me/invitations/{invId}/accept")
    public Map<String, Object> acceptMyInvitation(@PathVariable("invId") String invId) {
        String caller = TenantMemberController.requireCaller();
        long invIdNum = TenantMemberController.parseInvitationId(invId);
        try {
            var member = invitationService.accept(invIdNum, caller);
            readService.adoptHomeTenantIfTenantless(caller, member);
            return TenantInvitationReadService.membershipResponse(member, "");
        } catch (TenantRbacException e) {
            throw acceptDeclineError(e, "failed to accept invitation");
        }
    }

    /** POST /me/invitations/{invId}/decline（拒绝；不建成员行） */
    @PostMapping("/api/v1/me/invitations/{invId}/decline")
    public ApiResponse<Void> declineMyInvitation(@PathVariable("invId") String invId) {
        String caller = TenantMemberController.requireCaller();
        long invIdNum = TenantMemberController.parseInvitationId(invId);
        try {
            invitationService.decline(invIdNum, caller);
        } catch (TenantRbacException e) {
            throw acceptDeclineError(e, "failed to decline invitation");
        }
        return ApiResponse.ok();   // 204 退役（空体与「外壳恒存在」冲突）
    }

    /** POST /me/invitations/accept-by-token（已登录用户用共享链接 token 加入；幂等） */
    @PostMapping("/api/v1/me/invitations/accept-by-token")
    public Map<String, Object> acceptMyInvitationByToken(HttpServletRequest request) {
        String caller = TenantMemberController.requireCaller();
        JsonNode body = TenantMemberController.bindJson(TenantMemberController.rawBody(request));
        requireToken(body);
        String token = TenantMemberController.trimToEmpty(body.path("token").asText());
        if (token.isEmpty()) {
            // 纯空白过了 binding（required 对 string 是非零值），由 trim 后的空检查拦下
            throw new BizException(AppError.validation("token is required"));
        }

        try {
            var member = invitationService.acceptByToken(token, caller);
            readService.adoptHomeTenantIfTenantless(caller, member);
            String tenantName = "";
            TenantRowView tenant = tenantService.getTenantById(member.getTenantId());
            if (tenant != null) {
                tenantName = tenant.getName();
            }
            return TenantInvitationReadService.membershipResponse(member, tenantName);
        } catch (TenantRbacException e) {
            if (e.kind() == TenantRbacException.Kind.INVITATION_TOKEN_INVALID) {
                throw new BizException(new AppError(
                        ErrorCode.NOT_FOUND.value(),
                        "invitation link is invalid or has been revoked", null, 410));
            }
            throw new BizException(AppError.internal("failed to accept invitation")
                    .withDetails(e.getMessage()));
        }
    }

    // ── 共享投影 / 辅助 ─────────────────────────────────────────────────────

    /** Accept/Decline 的哨兵→HTTP 映射（Accept 与 Decline 两处同形）。 */
    private static BizException acceptDeclineError(TenantRbacException e, String internalMessage) {
        switch (e.kind()) {
            case INVITATION_NOT_FOUND:
                return new BizException(AppError.notFound("invitation not found"));
            case INVITATION_FORBIDDEN:
                return e.asForbidden();
            case INVITATION_NOT_PENDING:
            case INVITATION_EXPIRED:
                return e.asConflict();
            default:
                return new BizException(AppError.internal(internalMessage).withDetails(e.getMessage()));
        }
    }

    /** showShareLinks：调用方角色 ≥ Owner */
    private boolean currentRoleHasOwner() {
        return TenantRole.fromString(TenantContext.currentRole()).hasPermission(TenantRole.OWNER);
    }

    /** token required（validator 原文进 details） */
    private static void requireToken(JsonNode body) {
        JsonNode n = body.get("token");
        boolean missing = n == null || n.isNull() || (n.isTextual() && n.asText().isEmpty());
        if (missing) {
            throw new BizException(AppError.validation("token is required")
                    .withDetails(RequestFields.message("Token", "required")));
        }
    }

}
