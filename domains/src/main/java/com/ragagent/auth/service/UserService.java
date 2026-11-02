package com.ragagent.auth.service;

import com.ragagent.auth.domain.AuthToken;
import com.ragagent.auth.domain.TenantMember;
import com.ragagent.auth.domain.User;
import com.ragagent.auth.dto.LoginRequest;
import com.ragagent.auth.dto.Membership;
import com.ragagent.auth.dto.TenantRowView;
import com.ragagent.auth.repository.AuthTokenRepository;
import com.ragagent.auth.repository.UserRepository;
import com.ragagent.contract.auth.UserPreferences;
import com.ragagent.contract.settings.SystemSettingGateway;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.platform.security.UserNameLookup;
import io.jsonwebtoken.Claims;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 用户 service。
 *
 * 方法面：
 * - login：失败不抛异常，编码在 LoginResult.success/message
 * - validateToken：JWT 校验 + DB 撤销检查
 * - buildLoginMemberships：登录响应的空间成员组装
 * - resolveLoginTenantID / homeOrFirstMembershipTenant / resolveFirstMembershipTenant
 *   / clearStaleHomeTenant / clearLastActiveTenantPreference：登录空间解析
 * - generateTokensForTenant：签发 + 落 auth_tokens（错误忽略）
 *
 * bcrypt：BCryptPasswordEncoder 默认 cost=10。
 */
@Service
public class UserService implements UserNameLookup {

    static final Logger log = LoggerFactory.getLogger(UserService.class);
    private static final String TOKEN_TYPE_ACCESS = "access_token";
    static final String TOKEN_TYPE_REFRESH = "refresh_token";

    private final UserRepository userRepo;
    final AuthTokenRepository authTokenRepo;
    final TenantService tenantService;
    final TenantMemberService memberService;
    private final UserSessionOps sessionOps;
    final JwtService jwtService;
    private final SystemSettingGateway settingService;
    private final UserRegistrationService registrationService;
    private final LoginTenantResolver loginTenantResolver;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public UserService(UserRepository userRepo,
                       AuthTokenRepository authTokenRepo,
                       TenantService tenantService,
                       TenantMemberService memberService,
                       JwtService jwtService,
                       SystemSettingGateway settingService,
                       UserRegistrationService registrationService) {
        this.userRepo = userRepo;
        this.authTokenRepo = authTokenRepo;
        this.tenantService = tenantService;
        this.memberService = memberService;
        this.jwtService = jwtService;
        this.settingService = settingService;
        this.registrationService = registrationService;
        this.loginTenantResolver = new LoginTenantResolver(userRepo, tenantService, memberService);
        this.sessionOps = new UserSessionOps(this);
    }

    // ── 登录 ─────────────────────────────────────────────────────────────

    /**
     * 登录。失败路径返回 success=false 的 LoginResult
     * （不抛异常），由 controller 决定 401。
     */
    public LoginResult login(LoginRequest req) {
        User user = getUserByEmail(req.email());
        if (user == null) {
            log.warn("Login failed: no account for email");
            return LoginResult.failure("Invalid email or password");
        }
        if (!user.isIsActive()) {
            log.warn("User account is disabled");
            return LoginResult.failure("Account is disabled");
        }
        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            log.warn("Password verification failed");
            return LoginResult.failure("Invalid email or password");
        }

        long resolvedTenantId = resolveLoginTenantId(user);
        String accessToken;
        String refreshToken;
        try {
            String[] tokens = generateTokensForTenant(user, resolvedTenantId);
            accessToken = tokens[0];
            refreshToken = tokens[1];
        } catch (RuntimeException e) {
            log.error("Failed to generate tokens", e);
            return LoginResult.failure("Login failed");
        }

        // resolvedTenantID == 0 是合法的 tenantless 身份，不算失败
        TenantRowView tenant = null;
        if (resolvedTenantId > 0) {
            tenant = tenantService.getTenantById(resolvedTenantId);
            if (tenant == null) {
                log.warn("Failed to get tenant info");
            }
        }

        List<Membership> memberships = buildLoginMemberships(user, tenant);
        return LoginResult.success(user, tenant, memberships, accessToken, refreshToken);
    }

    // ── Token 校验（AuthFilter 通道 2 入口） ──────────────────────────────

    /**
     * 校验访问令牌（JWT 校验 + 撤销检查）。
     *
     * @return 校验通过的用户与 JWT tenant_id claim
     * @throws TokenValidationException 任一校验失败（消息 = 锁定原文）
     */
    public ValidatedToken validateToken(String tokenString) {
        Claims claims = jwtService.parseSigned(tokenString);

        Object userIdClaim = claims.get("user_id");
        if (!(userIdClaim instanceof String userId)) {
            throw new TokenValidationException("invalid user ID in token");
        }
        if (JwtService.isRefreshTokenClaims(claims)) {
            throw new TokenValidationException("refresh token cannot be used as access token");
        }
        if (JwtService.isSandboxTerminalTicketClaims(claims)) {
            throw new TokenValidationException("terminal ticket cannot be used as access token");
        }

        AuthToken tokenRecord = authTokenRepo.findByToken(tokenString);
        if (tokenRecord == null || tokenRecord.isIsRevoked()) {
            throw new TokenValidationException("token is revoked");
        }
        if (TOKEN_TYPE_REFRESH.equals(tokenRecord.getTokenType())) {
            throw new TokenValidationException("refresh token cannot be used as access token");
        }

        User user = getUserById(userId);
        if (user == null) {
            // 用户记录不存在 → 同样抛 "record not found"
            throw new TokenValidationException("record not found");
        }

        long homeTenantId = user.getTenantId() == null ? 0 : user.getTenantId();
        long activeTenantId = JwtService.tenantIdFromClaims(claims, homeTenantId);
        return new ValidatedToken(user, activeTenantId);
    }

    // ── 查询 ─────────────────────────────────────────────────────────────

    /** 软删除过滤；找不到返回 null */
    public User getUserByEmail(String email) {
        return userRepo.findByEmail(email);
    }

    /**
     * 取租户内 created_at
     * 最早的用户（软删除过滤）；找不到返回 null。API Key 认证的
     * {@code attachAPIKeyAuthContext} 用它取租户首位用户身份，查不到时
     * 走合成用户兜底 {@code system-<tenantId>}。
     */
    public User getUserByTenantIdFirst(long tenantId) {
        return userRepo.findByTenantIdFirst(tenantId);
    }

    /** 软删除过滤；找不到返回 null */
    public User getUserById(String id) {
        return userRepo.findById(id);
    }

    /**
     * 批量按 id 查（map 形态，供成员/邀请列表 hydrate）。
     * 软删除过滤 → isNull(deleted_at)。
     */
    public java.util.Map<String, User> getUsersByIds(java.util.Collection<String> ids) {
        java.util.Map<String, User> out = new java.util.HashMap<>();
        if (ids == null || ids.isEmpty()) {
            return out;
        }
        for (User u : userRepo.listByIds(ids)) {
            out.put(u.getId(), u);
        }
        return out;
    }

    /** 整行写回。调用方负责设置 updatedAt（写回不会自动刷新该列）。 */
    public void updateUser(User user) {
        userRepo.updateById(user);
    }

    /**
     * tenant_id 置空写回：必须显式写 SQL NULL——⚠️ 直接写 0 会炸 FK fk_users_tenant。
     * register-by-invite 的 accept 失败修复路径专用。
     */
    public void restoreTenantless(User user) {
        user.setTenantId(null);
        user.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        userRepo.restoreTenantless(user.getId(), user.getUpdatedAt());
    }

    /** register-by-invite 修复失败时的半建号清理。 */
    public void deleteUser(String id) {
        userRepo.deleteById(id);
    }

    // ── memberships 组装 ──────────────────────────────────────────────────

    /**
     * 错误不传播：membership 查不到 → 空数组。
     * 仅 status=active 且 tenant 行存在（名字非空白）的行进入响应。
     */
    public List<Membership> buildLoginMemberships(User user, TenantRowView activeTenant) {
        if (user == null) {
            return new ArrayList<>();
        }
        List<TenantMember> rows = memberService.listByUser(user.getId());
        if (rows.isEmpty()) {
            return new ArrayList<>();
        }
        List<Long> needsLookup = new ArrayList<>();
        for (TenantMember m : rows) {
            if (m == null || !TenantMemberService.STATUS_ACTIVE.equals(m.getStatus())) {
                continue;
            }
            if (activeTenant != null && m.getTenantId().equals(activeTenant.getId())) {
                continue;
            }
            needsLookup.add(m.getTenantId());
        }
        java.util.Map<Long, TenantRowView> tenantById = tenantService.getTenantsByIds(needsLookup);

        List<Membership> out = new ArrayList<>(rows.size());
        for (TenantMember m : rows) {
            if (m == null || !TenantMemberService.STATUS_ACTIVE.equals(m.getStatus())) {
                continue;
            }
            String name = "";
            if (activeTenant != null && m.getTenantId().equals(activeTenant.getId())) {
                name = activeTenant.getName();
            } else {
                TenantRowView t = tenantById.get(m.getTenantId());
                if (t != null) {
                    name = t.getName();
                }
            }
            // tenant 行已删除/名字空白 → 丢弃该 membership
            if (name == null || name.trim().isEmpty()) {
                continue;
            }
            out.add(new Membership(m.getTenantId(), name, m.getRole()));
        }
        return out;
    }

    // ── 登录空间解析（preference → home → 首个 membership） ────────────────

    /** 解析登录目标空间；状态机与失效自愈见 {@link LoginTenantResolver}。 */
    long resolveLoginTenantId(User user) {
        return loginTenantResolver.resolve(user);
    }

    // ── Token 签发 ────────────────────────────────────────────────────────

    /**
     * 签发 access(24h)/refresh(7d) 并各插一条 auth_tokens（插入错误忽略，不使调用失败）。
     */
    String[] generateTokensForTenant(User user, long activeTenantId) {
        String accessToken = jwtService.generateAccessToken(user, activeTenantId);
        String refreshToken = jwtService.generateRefreshToken(user);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        insertToken(user.getId(), accessToken, TOKEN_TYPE_ACCESS, now.plusHours(24));
        insertToken(user.getId(), refreshToken, TOKEN_TYPE_REFRESH, now.plusDays(7));
        return new String[]{accessToken, refreshToken};
    }

    private void insertToken(String userId, String tokenValue, String tokenType, OffsetDateTime expiresAt) {
        try {
            AuthToken record = new AuthToken();
            record.setId(UUID.randomUUID().toString());
            record.setUserId(userId);
            record.setToken(tokenValue);
            record.setTokenType(tokenType);
            record.setExpiresAt(expiresAt);
            authTokenRepo.insert(record);
        } catch (RuntimeException e) {
            // 落库失败不使登录失败
            log.warn("Failed to persist {} (user={})", tokenType, userId, e);
        }
    }

    // ── 注册 ─────────────────────────────────────────────────────────────

    /** provisioning 模式取值（服务端控制，不从请求 JSON 读）。 */
    public static final String PROVISIONING_CREATE_PERSONAL = "create_personal";
    public static final String PROVISIONING_TENANTLESS = "tenantless";

    /** 注册失败：message = 服务端错误原文（controller 包成 400 BadRequest）。 */
    public static final class RegistrationException extends RuntimeException {
        public RegistrationException(String message) {
            super(message);
        }
    }

    /** 注册用例；事务在 {@link UserRegistrationService}。 */
    public User register(String username, String email, String password, String provisioning) {
        return registrationService.register(username, email, password, provisioning);
    }

    /** 软删除过滤；找不到返回 null */
    public User getUserByUsername(String username) {
        return userRepo.findByUsername(username);
    }

    /** 从请求上下文取当前用户（AuthFilter 已鉴权）。 */
    public User getCurrentUser() {
        String userId = TenantContext.currentUserId();
        if (userId == null || userId.isEmpty()) {
            return null;
        }
        return getUserById(userId);
    }

    // ── 偏好 ─────────────────────────────────────────────────────────────

    /**
     * PATCH 语义合并（null = 未携带，保持原值）。
     * last_active_tenant_id=0 → 清偏好。
     */
    public UserPreferences updateUserPreferences(String userId, UserPreferences patch) {
        User user = getUserById(userId);
        if (user == null) {
            throw new PreferencesException("record not found");
        }
        UserPreferences merged = user.getPreferences() != null ? user.getPreferences() : new UserPreferences();
        if (patch.getLastActiveTenantId() != null) {
            // 0 = 「忘掉我的偏好」哨兵；其余正值直接存（成员关系下次登录时校验）
            merged.setLastActiveTenantId(
                    patch.getLastActiveTenantId() == 0 ? null : patch.getLastActiveTenantId());
        }
        user.setPreferences(merged);
        user.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        userRepo.updateById(user);
        return merged;
    }

    /** 偏好更新失败：message = 服务端错误原文（controller 包成 400 BadRequest "Failed to update preferences"）。 */
    public static final class PreferencesException extends RuntimeException {
        public PreferencesException(String message) {
            super(message);
        }
    }

    // ── 修改密码 ──────────────────────────────────────────────────────────

    /** change-password 的失败分类（controller 按此分派 HTTP 状态与文案）。 */
    public enum ChangePasswordFailure {NONE, INVALID_OLD, SAME_AS_OLD, POLICY, OTHER}

    public static final class ChangePasswordException extends RuntimeException {
        private final ChangePasswordFailure kind;

        public ChangePasswordException(ChangePasswordFailure kind, String message) {
            super(message);
            this.kind = kind;
        }

        public ChangePasswordException(ChangePasswordFailure kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }

        public ChangePasswordFailure kind() {
            return kind;
        }
    }

    /**
     * 先验旧密码（错误凭据不被策略错误掩盖）→ 新旧相同 →
     * 策略 → 落库 → 吊销全部会话。成功改密会清掉 OIDC 自动 provisioning 的
     * oidc_only_login 标记。
     */
    public void changePassword(String userId, String oldPassword, String newPassword) {
        User user = getUserById(userId);
        if (user == null) {
            throw new ChangePasswordException(ChangePasswordFailure.OTHER, "record not found");
        }
        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new ChangePasswordException(ChangePasswordFailure.INVALID_OLD, "invalid old password");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new ChangePasswordException(ChangePasswordFailure.SAME_AS_OLD,
                    "new password must differ from current password");
        }
        String policyError = PasswordPolicy.validate(newPassword, complexPasswordEnabled());
        if (policyError != null) {
            throw new ChangePasswordException(ChangePasswordFailure.POLICY, policyError);
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        if (user.getPreferences() != null
                && Boolean.TRUE.equals(user.getPreferences().getOidcOnlyLogin())) {
            user.getPreferences().setOidcOnlyLogin(false);
        }
        try {
            userRepo.updateById(user);
        } catch (RuntimeException e) {
            throw new ChangePasswordException(ChangePasswordFailure.OTHER, e.getMessage(), e);
        }
        // 吊销全部会话：被偷的 token 不能活过密码轮换
        revokeTokensByUserId(userId);
    }

    /** 吊销该用户全部令牌：is_revoked=true，同时刷 updated_at。 */
    public void revokeTokensByUserId(String userId) {
        authTokenRepo.revokeByUserId(userId);
    }

    /** 按 token 串查一条（缺失返回 null）。 */
    AuthToken findTokenByString(String token) {
        return authTokenRepo.findByToken(token);
    }

    /** 整行写回并吊销（刷新 updated_at）。 */
    void revokeTokenRecord(AuthToken record) {
        authTokenRepo.revoke(record);
    }

    // ── logout / refresh / switch-tenant（实现外提至 {@link UserSessionOps}） ────

    /** 解出 user_id 后吊销该用户全部会话（失败通道见本类 LogoutException）。 */
    public void logout(String tokenString) {
        sessionOps.logout(tokenString);
    }

    /** 校验并吊销旧 refresh，按 last-active 偏好签发新令牌对。 */
    public String[] refreshToken(String refreshTokenString) {
        return sessionOps.refreshToken(refreshTokenString);
    }

    /** 校验成员关系 → 记偏好 → 签发 → 尽力吊销旧 refresh。 */
    public LoginResult switchTenant(User user, long targetTenantId, String currentRefreshToken) {
        return sessionOps.switchTenant(user, targetTenantId, currentRefreshToken);
    }

    /** logout 的失败通道（controller 包成 500 "Logout failed"）。 */
    public static final class LogoutException extends RuntimeException {
        public LogoutException(String message) {
            super(message);
        }
    }
    /** refresh 的失败通道（controller 包成 401 "Token refresh failed"）。 */
    public static final class RefreshTokenException extends RuntimeException {
        public RefreshTokenException(String message) {
            super(message);
        }
    }
    /** switch-tenant 的失败通道（controller 包成 403 "workspace switch failed"）。 */
    public static final class SwitchTenantException extends RuntimeException {
        public SwitchTenantException(String message) {
            super(message);
        }

        public SwitchTenantException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // ── Token 签发（对外） ─────────────────────────────────────────────────

    /** 按登录空间解析结果签发令牌对。 */
    /** Object 入口：控制器不 import User 时的薄适配。 */
    public String[] generateTokensFor(Object user) {
        return generateTokens((User) user);
    }

    public String[] generateTokens(User user) {
        return generateTokensForTenant(user, resolveLoginTenantId(user));
    }

    // ── 工具 ──────────────────────────────────────────────────────────────

    /** 复杂密码开关的解析顺序：系统设置 > 环境变量 > 默认 false。 */
    public boolean complexPasswordEnabled() {
        return settingService.getBool(
                "auth.complex_password_enabled", "RAGAGENT_AUTH_COMPLEX_PASSWORD_ENABLED", false);
    }

    // ── UserNameLookup（B97b：非 auth 域经端口取名，不再依赖本类）─────

    @Override
    public String usernameOf(String userId) {
        if (userId == null || userId.isEmpty()) {
            return null;
        }
        var user = getUserById(userId);
        return user == null ? null : user.getUsername();
    }
}
