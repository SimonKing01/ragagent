package com.ragagent.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.auth.dto.AuthConfigResponse;
import com.ragagent.auth.dto.AuthLoginResponse;
import com.ragagent.auth.dto.ChangePasswordRequest;
import com.ragagent.auth.dto.CurrentUserResponse;
import com.ragagent.auth.dto.InvitationLookupRequest;
import com.ragagent.auth.dto.InvitationLookupResponse;
import com.ragagent.auth.dto.InvitationLookupView;
import com.ragagent.auth.dto.LoginRequest;
import com.ragagent.auth.dto.Membership;
import com.ragagent.auth.dto.OidcAuthUrlResponse;
import com.ragagent.auth.dto.OidcConfigResponse;
import com.ragagent.auth.dto.RegisterByInviteRequest;
import com.ragagent.auth.dto.RegisterRequest;
import com.ragagent.auth.dto.TenantResponse;
import com.ragagent.auth.dto.TenantRowView;
import com.ragagent.auth.dto.TokenPairResponse;
import com.ragagent.auth.dto.UpdatePreferencesRequest;
import com.ragagent.auth.dto.UserCapabilities;
import com.ragagent.auth.dto.UserInfo;
import com.ragagent.auth.service.AuthAccountService;
import com.ragagent.auth.service.LoginResult;
import com.ragagent.auth.service.OidcConfig;
import com.ragagent.auth.service.OidcService;
import com.ragagent.auth.service.OidcStateCodec;
import com.ragagent.auth.service.PasswordPolicy;
import com.ragagent.auth.service.TenantInvitationReadService;
import com.ragagent.auth.service.TenantService;
import com.ragagent.auth.service.TokenValidationException;
import com.ragagent.auth.service.UserService;
import com.ragagent.auth.service.ValidatedToken;
import com.ragagent.contract.auth.UserPreferences;
import com.ragagent.contract.error.AppError;
import com.ragagent.contract.error.BizException;
import com.ragagent.contract.settings.SystemSettingGateway;
import com.ragagent.contract.tenant.TenantRole;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.platform.config.TenantProperties;
import com.ragagent.platform.security.LogSanitizer;
import com.ragagent.platform.settings.SystemSettingRegistry;
import com.ragagent.platform.text.UnicodeText;
import com.ragagent.platform.web.ApiResponse;
import com.ragagent.platform.web.ApiResult;
import com.ragagent.platform.web.JsonMappers;
import com.ragagent.platform.web.NonNullBody;
import com.ragagent.platform.web.RejectEmptyBody;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.security.SecureRandom;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证端点（登录 / 注册 / 会话 / OIDC）。
 *
 * 端点清单：
 * - POST /login             登录
 * - POST /register          invite_only 门 → binding → 消毒 →
 *                           空值 → 密码策略 → Register（建四表）→ 201
 * - POST /auto-setup        非 lite 部署恒 403
 * - POST /register-by-invite /invitations/lookup
 * - GET  /config            注册模式 + 复杂密码开关（无鉴权，供前端）
 * - GET  /validate          其 400 分支在部署态被 AuthFilter 短路
 * - GET  /me                嵌套 map 每层字母序
 * - PUT  /me/preferences    PATCH 语义合并
 * - POST /change-password   错误分派到三种 details 令牌
 * - GET  /oidc/{config,url,start,callback}：
 *   302 的字节行为（body=`<a href="<html 转义 Location>">Found</a>.\n\n`，
 *   Content-Type: text/html; charset=utf-8）；enabled 后的网络步整体推迟
 *
 * map 响应：键一律按字母序输出 → LinkedHashMap 按字母序构造。
 */
@RestController
@ApiResult
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    /**
     * 登录/注册通用的 email 正则（与常见 validator 的 email 判定一致），
     * 保证 binding 错误判定口径统一。
     */
    static final Pattern GIN_EMAIL = Pattern.compile(
            "^(?:[a-zA-Z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[a-zA-Z0-9!#$%&'*+/=?^_`{|}~-]+)*"
                    + "|\"(?:[\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f\\x21\\x23-\\x5b\\x5d-\\x7f]"
                    + "|\\\\[\\x01-\\x09\\x0b\\x0c\\x0e-\\x7f])*\")"
                    + "@(?:(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?\\.)+"
                    + "[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?"
                    + "|\\[(?:(?:(2(5[0-5]|[0-4][0-9])|1[0-9][0-9]|[1-9]?[0-9]))\\.){3}"
                    + "(?:(2(5[0-5]|[0-4][0-9])|1[0-9][0-9]|[1-9]?[0-9])"
                    + "|[a-zA-Z0-9-]*[a-zA-Z0-9]:(?:[\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f\\x21-\\x5a\\x53-\\x7f]"
                    + "|\\\\[\\x01-\\x09\\x0b\\x0c\\x0e-\\x7f])+)\\])$");

    static final ObjectMapper MAPPER = JsonMappers.lenient();

    final UserService userService;
    final TenantService tenantService;
    private final TenantInvitationReadService invitationReadService;
    private final AuthAccountService accountService;
    private final SystemSettingGateway settingService;
    private final TenantProperties tenantProperties;
    final OidcConfig oidcConfig;
    final OidcService oidcService;
    final OidcStateCodec oidcStateCodec;

    final AuthOidcOps oidcOps;
    final AuthSessionOps sessionOps;
    final AuthBindingSupport bindingSupport;

    /** 部署形态常量（构建期注入，默认 "standard"）。 */
    @Value("${weknora.system.edition:standard}")
    private String edition;

    /** 注册模式配置（缺省 self_serve）。 */
    @Value("${weknora.auth.registration-mode:}")
    private String configuredRegistrationMode;

    public AuthController(UserService userService,
                          TenantService tenantService,
                          TenantInvitationReadService invitationReadService,
                          AuthAccountService accountService,
                          SystemSettingGateway settingService,
                          TenantProperties tenantProperties,
                          OidcConfig oidcConfig,
                          OidcService oidcService,
                          OidcStateCodec oidcStateCodec) {
        this.userService = userService;
        this.tenantService = tenantService;
        this.invitationReadService = invitationReadService;
        this.accountService = accountService;
        this.settingService = settingService;
        this.tenantProperties = tenantProperties;
        this.oidcConfig = oidcConfig;
        this.oidcService = oidcService;
        this.oidcStateCodec = oidcStateCodec;
        this.oidcOps = new AuthOidcOps(this);
        this.sessionOps = new AuthSessionOps(this);
        this.bindingSupport = new AuthBindingSupport();
    }

    @PostMapping("/login")
    public ResponseEntity<AuthLoginResponse> login(
            @Valid @RejectEmptyBody @NonNullBody @RequestBody(required = false) LoginRequest req) {
        log.info("Start user login");

        // 空值由 @NotBlank 拦截；格式与长度沿用 gin binding 语义（正则 + 按码点计数，逐条收集）
        List<String> bindingErrors = new ArrayList<>();
        if (!GIN_EMAIL.matcher(req.email()).matches()) {
            bindingErrors.add(bindingError("Email", "email"));
        }
        if (req.password().codePointCount(0, req.password().length()) < 6) {
            bindingErrors.add(bindingError("Password", "min"));
        }
        if (!bindingErrors.isEmpty()) {
            throw AuthBindingSupport.invalidParams("Invalid login parameters",
                    String.join("\n", bindingErrors));
        }

        LoginResult result = userService.login(req);
        if (!result.success()) {
            log.warn("Login failed: {}", result.message());
            throw new BizException(AppError.unauthorized(result.message()));
        }
        log.info("User logged in successfully, email: {}", result.user().getEmail());
        return ResponseEntity.ok(toResponse(result));
    }

    // ── POST /register ─────────────────────────────────────────────────────

    @PostMapping("/register")
    public ResponseEntity<Object> register(
            @Valid @RejectEmptyBody @NonNullBody @RequestBody(required = false) RegisterRequest req) {
        // 1) invite_only 门（DB system_settings > cfg > self_serve）
        if ("invite_only".equals(resolveRegistrationMode())) {
            throw new BizException(AppError.forbidden("Registration is invite-only"));
        }
        // 2) 格式与长度沿用 gin binding 语义（正则 + 按码点计数，逐条收集）
        List<String> bindingErrors = new ArrayList<>();
        int usernameLen = req.username().codePointCount(0, req.username().length());
        if (usernameLen < 2) {
            bindingErrors.add(bindingError("Username", "min"));
        } else if (usernameLen > 50) {
            bindingErrors.add(bindingError("Username", "max"));
        }
        if (!GIN_EMAIL.matcher(req.email()).matches()) {
            bindingErrors.add(bindingError("Email", "email"));
        }
        if (req.password().codePointCount(0, req.password().length()) < 6) {
            bindingErrors.add(bindingError("Password", "min"));
        }
        if (!bindingErrors.isEmpty()) {
            throw AuthBindingSupport.invalidParams("Invalid registration parameters",
                    String.join("\n", bindingErrors));
        }
        // 3) 消毒（密码刻意不消毒：SanitizeForLog 会改写控制字符，导致注册成功却登录不上）
        String username = LogSanitizer.sanitize(req.username());
        String email = LogSanitizer.sanitize(req.email());
        // 4) 密码策略（运行时可调：DB system_settings 优先）
        String policyError = PasswordPolicy.validate(req.password(), userService.complexPasswordEnabled());
        if (policyError != null) {
            throw new BizException(AppError.validation(policyError));
        }
        // 5) 注册（租户供应模式服务端决定，不从请求读）
        try {
            var user = userService.register(
                    username, email, req.password(), resolveDefaultTenantMode());
            // 返回投影而非实体：passwordHash 不再依赖 @JsonIgnore 兜底，
            // 形状与 /auth/me 一致（UserInfo）
            return ResponseEntity.status(201)
                    .body(UserInfo.from(user, user.isCanAccessAllTenants()));
        } catch (UserService.RegistrationException e) {
            throw new BizException(AppError.badRequest(e.getMessage()));
        }
    }

    // ── POST /auto-setup ───────────────────────────────────────────────────

    @PostMapping("/auto-setup")
    public ResponseEntity<AuthLoginResponse> autoSetup() {
        if (!"lite".equals(edition)) {
            throw new BizException(AppError.forbidden("auto-setup is only available in lite edition"));
        }
        final String defaultEmail = "admin@weknora.local";
        var user = userService.getUserByEmail(defaultEmail);
        if (user == null) {
            byte[] randomBytes = new byte[24];
            new SecureRandom().nextBytes(randomBytes);
            String randomPassword = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
            String randomUsername = "user_" + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(java.util.Arrays.copyOf(randomBytes, 6));
            try {
                userService.register(randomUsername, defaultEmail, randomPassword, "");
            } catch (UserService.RegistrationException e) {
                throw new BizException(AppError.internal("auto-setup failed").withDetails(e.getMessage()));
            }
            user = userService.getUserByEmail(defaultEmail);
            if (user == null) {
                throw new BizException(
                        AppError.internal("auto-setup failed: user not found after registration"));
            }
        }
        String[] tokens = generateTokensOr500(user, "auto-setup failed");
        TenantRowView tenant = user.getTenantId() != null && user.getTenantId() > 0
                ? tenantService.getTenantById(user.getTenantId()) : null;
        List<Membership> memberships = List.of(new Membership(
                user.getTenantId() == null ? 0L : user.getTenantId(),
                tenantNameOrEmpty(tenant), TenantRole.OWNER.value()));
        return ResponseEntity.ok(
                buildAuthLoginResponse(user, tenant, memberships, tokens[0], tokens[1]));
    }

    // ── POST /register-by-invite + /invitations/lookup ─────────────────────
    // （均不受 invite_only 门控——token 即授权）

    @PostMapping("/invitations/lookup")
    public ResponseEntity<InvitationLookupResponse> lookupInvitation(
            @Valid @RejectEmptyBody @NonNullBody @RequestBody(required = false)
                    InvitationLookupRequest req) {
        String token = UnicodeText.trim(req.token());
        if (token.isEmpty()) {
            throw new BizException(AppError.validation("token is required"));
        }
        var inv = lookupInvitationOr410(token);

        TenantRowView tenant = null;
        try {
            tenant = tenantService.getTenantById(inv.tenantId());
        } catch (RuntimeException e) {
            log.warn("invitations/lookup: tenant {} lookup failed", inv.tenantId(), e);
        }
        InvitationLookupResponse resp = new InvitationLookupResponse(
                inv.tenantId(),
                tenant != null ? tenant.getName() : null,
                inv.role(),
                // 对照 .UTC().Format("2006-01-02T15:04:05Z07:00")：UTC、无小数秒、Z 结尾
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                        .format(inv.expiresAt().withOffsetSameInstant(ZoneOffset.UTC)));
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/register-by-invite")
    public ResponseEntity<AuthLoginResponse> registerByInvite(
            @Valid @RejectEmptyBody @NonNullBody @RequestBody(required = false)
                    RegisterByInviteRequest req) {
        List<String> bindingErrors = new ArrayList<>();
        if (!GIN_EMAIL.matcher(req.email()).matches()) {
            bindingErrors.add(bindingError("Email", "email"));
        }
        if (req.password().codePointCount(0, req.password().length()) < 6) {
            bindingErrors.add(bindingError("Password", "min"));
        }
        if (!bindingErrors.isEmpty()) {
            throw AuthBindingSupport.invalidParams("Invalid registration parameters",
                    String.join("\n", bindingErrors));
        }
        String token = UnicodeText.trim(req.token());
        String email = UnicodeText.trim(req.email()).toLowerCase(Locale.ROOT);
        String username = UnicodeText.trim(req.username());
        if (token.isEmpty() || email.isEmpty() || username.isEmpty() || isBlank(req.password())) {
            throw new BizException(
                    AppError.validation("token, email, username and password are required"));
        }

        var inv = lookupInvitationOr410(token);

        // 已有账号 → 409（分享链接流程是「新建账号并加入」，不是「老号加入」）
        if (userService.getUserByEmail(email) != null) {
            throw new BizException(AppError.conflict(
                    "this email already has an account; please log in to join the workspace"));
        }
        String policyError = PasswordPolicy.validate(req.password(), userService.complexPasswordEnabled());
        if (policyError != null) {
            throw new BizException(AppError.validation(policyError));
        }

        var completedUser = accountService.completeInviteRegistration(
                registerUser(username, email, req.password()), inv.tenantId(), token);

        String[] tokens = generateTokensOr500(completedUser, "token generation failed");
        TenantRowView tenant = null;
        try {
            tenant = tenantService.getTenantById(inv.tenantId());
        } catch (RuntimeException ignored) {
            // tenantNameOrEmpty 容忍 null，取不到也不让请求失败
        }
        List<Membership> memberships = List.of(
                new Membership(inv.tenantId(), tenantNameOrEmpty(tenant), inv.role()));
        return ResponseEntity.status(201).body(
                buildAuthLoginResponse(completedUser, tenant, memberships, tokens[0], tokens[1]));
    }

    // ── GET /config（无鉴权公共读） ─────────────────────────────────────────

    @GetMapping("/config")
    public ResponseEntity<AuthConfigResponse> getAuthConfig() {
        return ResponseEntity.ok(new AuthConfigResponse(
                userService.complexPasswordEnabled(), resolveRegistrationMode(), edition));
    }

    // ── GET /validate ──────────────────────────────────────────────────────
    // 注意：部署态下无/坏 Authorization 头都先被 AuthFilter 以 401 纯文本拒绝，
    // 这里的 400/401 分支在过滤器之后不可达，仅为协议完整性保留。

    @GetMapping("/validate")
    public ResponseEntity<UserInfo> validateToken(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (authHeader == null || authHeader.isEmpty()) {
            throw new BizException(AppError.validation("Authorization header is required"));
        }
        String[] tokenParts = authHeader.split(" ", -1); // 全部空格都切
        if (tokenParts.length != 2 || !"Bearer".equals(tokenParts[0])) {
            throw new BizException(AppError.validation("Invalid Authorization header format"));
        }
        ValidatedToken vt;
        try {
            vt = userService.validateToken(tokenParts[1]);
        } catch (TokenValidationException e) {
            throw new BizException(AppError.unauthorized("Token validation failed")
                    .withDetails(e.getMessage()));
        }
        return ResponseEntity.ok(UserInfo.from(vt.user(), vt.user().isCanAccessAllTenants()));
    }

    // ── GET /me ────────────────────────────────────────────────────────────

    @GetMapping("/me")
    public ResponseEntity<CurrentUserResponse> getCurrentUser() {
        var user = userService.getCurrentUser();
        if (user == null) {
            throw new BizException(AppError.unauthorized("Failed to get user information")
                    .withDetails("user not found in context"));
        }
        // 取**活动**空间（AuthFilter 按 X-Tenant-ID/JWT claim 解析的），不是用户的主空间
        Long ctxTenant = TenantContext.currentTenantId();
        long activeTenantId = ctxTenant == null ? 0 : ctxTenant;
        if (activeTenantId == 0) {
            activeTenantId = user.getTenantId() == null ? 0 : user.getTenantId();
        }
        TenantRowView tenant = null;
        if (activeTenantId > 0) {
            try {
                tenant = tenantService.getTenantById(activeTenantId);
            } catch (RuntimeException e) {
                // 租户信息取不到不让请求失败
                log.warn("Failed to get tenant info for user {}, tenant ID {}", user.getEmail(), activeTenantId, e);
            }
        }
        UserInfo userInfo = UserInfo.from(user, user.isCanAccessAllTenants());
        List<Membership> memberships = userService.buildLoginMemberships(user, tenant);
        boolean canCreateTenant = user.isCanAccessAllTenants()
                || resolveTenantSelfServiceCreationEnabled();
        boolean autoAcceptInvitation = settingService.getBool("tenant.auto_accept_invitation",
                "RAGAGENT_TENANT_AUTO_ACCEPT_INVITATION", false);

        // preferenceDefaults：browser_search_instructions 随浏览器连接裁撤后已无条目，
        // 保留空 map 以维持响应形状。
        return ResponseEntity.ok(new CurrentUserResponse(
                new UserCapabilities(autoAcceptInvitation, canCreateTenant),
                memberships,
                new LinkedHashMap<>(),
                tenant == null ? null : TenantResponse.from(tenant, contextRoleHasAdmin()),
                tenant == null,
                userInfo));
    }

    // ── PUT /me/preferences ────────────────────────────────────────────────

    @PutMapping("/me/preferences")
    public ResponseEntity<UserPreferences> updateMyPreferences(
            @Valid @RejectEmptyBody @NonNullBody @RequestBody(required = false)
                    UpdatePreferencesRequest req) {
        String userId = currentUserIdOr401();
        UserPreferences patch = new UserPreferences();
        patch.setLastActiveTenantId(req.lastActiveTenantId());
        UserPreferences prefs;
        try {
            prefs = userService.updateUserPreferences(userId, patch);
        } catch (UserService.PreferencesException e) {
            throw new BizException(AppError.badRequest("Failed to update preferences")
                    .withDetails(e.getMessage()));
        }
        return ResponseEntity.ok(prefs);
    }

    // ── POST /change-password ──────────────────────────────────────────────

    @PostMapping("/change-password")
    public ApiResponse<Void> changePassword(
            @Valid @RejectEmptyBody @NonNullBody @RequestBody(required = false)
                    ChangePasswordRequest req) {
        String userId = currentUserIdOr401();
        try {
            userService.changePassword(userId, req.oldPassword(), req.newPassword());
        } catch (UserService.ChangePasswordException e) {
            switch (e.kind()) {
                case POLICY -> throw new BizException(AppError.validation("Password policy violation")
                        .withDetails(PasswordPolicy.DETAIL_PASSWORD_POLICY));
                case INVALID_OLD -> throw new BizException(
                        AppError.badRequest("Current password is incorrect")
                                .withDetails(PasswordPolicy.DETAIL_INVALID_OLD_PASSWORD));
                case SAME_AS_OLD -> throw new BizException(
                        AppError.validation("New password must differ from current password")
                                .withDetails(PasswordPolicy.DETAIL_SAME_PASSWORD));
                default -> throw new BizException(AppError.badRequest("Password change failed")
                        .withDetails(e.getMessage()));
            }
        }
        return ApiResponse.ok();   // 204 退役（空体与「外壳恒存在」冲突）
    }


    @GetMapping("/oidc/config")
    public ResponseEntity<OidcConfigResponse> getOidcConfig() {
        return oidcOps.getOidcConfig();
    }

    @GetMapping("/oidc/url")
    public ResponseEntity<OidcAuthUrlResponse> getOidcAuthorizationUrl(
            @RequestParam(value = "redirect_uri", required = false) String redirectUri,
            HttpServletRequest request, HttpServletResponse response) {
        return oidcOps.getOidcAuthorizationUrl(redirectUri, request, response);
    }

    @GetMapping("/oidc/start")
    public ResponseEntity<String> oidcStart(HttpServletRequest request, HttpServletResponse response) {
        return oidcOps.oidcStart(request, response);
    }

    @GetMapping("/oidc/callback")
    public ResponseEntity<String> oidcRedirectCallback(
            @RequestParam(value = "error", required = false) String providerError,
            @RequestParam(value = "error_description", required = false) String errorDescription,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "code", required = false) String code,
            HttpServletRequest request, HttpServletResponse response) {
        return oidcOps.oidcRedirectCallback(providerError, errorDescription, state, code, request, response);
    }

    // ── 策略解析 ──────────────────────────────────────────────────────────

    /** 注册模式解析：DB system_settings > cfg > self_serve */
    private String resolveRegistrationMode() {
        String def = configuredRegistrationMode == null || configuredRegistrationMode.isBlank()
                ? SystemSettingRegistry.AUTH_REGISTRATION_MODE_DEFAULT
                : configuredRegistrationMode.trim();
        return settingService.getString("auth.registration_mode", "", def);
    }

    /** 默认租户模式解析：DB > ENV > cfg（cfg 兜底 create_personal） */
    String resolveDefaultTenantMode() {
        String mode = settingService.getString("auth.default_tenant_mode",
                "RAGAGENT_AUTH_DEFAULT_TENANT_MODE", "create_personal");
        return "tenantless".equals(mode)
                ? UserService.PROVISIONING_TENANTLESS : UserService.PROVISIONING_CREATE_PERSONAL;
    }

    /** 自助建租户开关：DB > ENV > cfg(默认 true) */
    private boolean resolveTenantSelfServiceCreationEnabled() {
        return settingService.getBool("tenant.self_service_creation_enabled",
                "RAGAGENT_TENANT_SELF_SERVICE_CREATION_ENABLED",
                tenantProperties.isSelfServiceCreationEnabled());
    }

    // ── 共享辅助 ──────────────────────────────────────────────────────────

    private Object registerUser(String username, String email, String password) {
        try {
            return userService.register(username, email, password,
                    UserService.PROVISIONING_TENANTLESS);
        } catch (UserService.RegistrationException e) {
            throw new BizException(AppError.badRequest(e.getMessage()));
        }
    }

    String currentUserIdOr401() {
        var user = userService.getCurrentUser();
        if (user == null) {
            throw new BizException(AppError.unauthorized("Failed to get user information")
                    .withDetails("user not found in context"));
        }
        return user.getId();
    }

    InvitationLookupView lookupInvitationOr410(String token) {
        return invitationReadService.lookupView(token);
    }

    String[] generateTokensOr500(Object user, String message) {
        try {
            return userService.generateTokensFor(user);
        } catch (RuntimeException e) {
            throw new BizException(AppError.internal(message).withDetails(e.getMessage()));
        }
    }

    /** 租户名取不到时回落空串。 */
    private static String tenantNameOrEmpty(TenantRowView t) {
        return t == null || t.getName() == null ? "" : t.getName();
    }

    /** 当前上下文角色是否有 Admin 权限（决定秘密字段是否输出）。 */
    private boolean contextRoleHasAdmin() {
        String role = TenantContext.currentRole();
        return TenantRole.fromString(role == null ? "" : role).hasPermission(TenantRole.ADMIN);
    }

    /** 登录响应组装规则：active_tenant 按 membership 角色决定秘密字段是否输出 */
    /**
     * POST /auth/logout。AuthFilter 已把它列入 tenant-optional（tenantless 也可登出）。
     * 成功无响应体。
     */
    /**
     * POST /auth/refresh。noAuthAPI 白名单路径
     * （无鉴权）；绑定失败 400 "Invalid refresh token request"+details，
     * service 失败 401 "Token refresh failed"+details。成功体见 AuthLoginResponse。
     */
    /**
     * POST /auth/switch-tenant。tenant-optional
     * （tenantless 主体可调用，切换成功即有空间）。绑定失败 400
     * "Invalid workspace switch request"+details；未认证 401 "not authenticated"；
     * service 失败 403 "workspace switch failed"+details；成功 = 登录响应同形
     * （AuthLoginResponse，message="Workspace switched"）。
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        return sessionOps.logout(authHeader);
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenPairResponse> refreshToken(
            @RequestBody(required = false) String rawBody) {
        return sessionOps.refreshToken(rawBody);
    }

    @PostMapping("/switch-tenant")
    public ResponseEntity<AuthLoginResponse> switchTenant(
            @RequestBody(required = false) String rawBody) {
        return sessionOps.switchTenant(rawBody);
    }
    /** login 用的响应组装。 */
    private AuthLoginResponse toResponse(LoginResult r) {
        return buildAuthLoginResponse(r.user(), r.activeTenant(),
                r.memberships(), r.token(), r.refreshToken());
    }

    /** 取用户在指定空间的成员角色；无成员或角色无效返回空串。 */
    static String membershipRoleForTenant(List<Membership> memberships, long tenantId) {
        if (memberships == null) {
            return "";
        }
        for (Membership m : memberships) {
            if (m != null && m.tenantId() == tenantId && TenantRole.fromString(m.role()).isValid()) {
                return m.role();
            }
        }
        return "";
    }

    static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }

    AuthLoginResponse buildAuthLoginResponse(Object user,
            TenantRowView activeTenant, List<Membership> memberships, String token, String refreshToken) {
        return bindingSupport.buildAuthLoginResponse(user, activeTenant,
                memberships, token, refreshToken);
    }

    String bindingError(String field, String tag) {
        return AuthBindingSupport.bindingError(field, tag);
    }

    BizException invalidParams(String message, String details) {
        return AuthBindingSupport.invalidParams(message, details);
    }


}
