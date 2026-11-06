package com.ragagent.auth.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ragagent.auth.domain.AuthToken;
import com.ragagent.auth.domain.User;
import com.ragagent.auth.dto.TenantRowView;
import com.ragagent.auth.mapper.AuthTokenMapper;
import com.ragagent.auth.mapper.UserMapper;
import com.ragagent.auth.service.PasswordPolicy;
import com.ragagent.auth.service.TenantMemberService;
import com.ragagent.auth.service.TenantService;
import com.ragagent.contract.auth.AdminUserView;
import com.ragagent.contract.error.AppError;
import com.ragagent.contract.error.BizException;
import com.ragagent.contract.pagination.PageResult;
import com.ragagent.platform.persistence.PageRequests;
import com.ragagent.platform.security.LogSanitizer;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 系统管理员 用户管理（注册 / 建管理员 / 重置密码 / 列管理员 / 撤管理员）。
 *
 * <p>异常语义逐分支固定（错误消息是契约）：</p>
 * <ul>
 * <li>promote：user_id/email 双空 → 400 "Either user_id or email is required"；
 * 查无 → 404 "User not found"；已是管理员 → 幂等 200（审计 idempotent=true）。</li>
 * <li>revoke：自撤 → 400 "Cannot revoke your own system admin privileges"；
 * 最后一名 → 400 "Cannot revoke the last remaining system administrator"；
 * 查无 → 404；非管理员 → 幂等 200（changed=false）。</li>
 * <li>create：身份重复且完全一致 → 200 幂等返回既有行；仅部分冲突 → 409。</li>
 * </ul>
 *
 * <p><b>实现边界（Lite）</b>：撤管理员无 SELECT ... FOR UPDATE 事务，
 * 用顺序读-判-写（SystemAdmin 面无并发竞争窗）。
 * 密码生成用 SecureRandom + base64url，输出长度可能与早先实现不同——
 * 生成密码只出现一次且契约测试掩码。</p>
 */
@Component
public class AdminUserAdapter {

    private static final Logger log = LoggerFactory.getLogger(AdminUserAdapter.class);

    /** 密码策略错误原文（400 响应体）；实现收拢到 {@link PasswordPolicy}。 */
    public static final String ERR_PASSWORD_POLICY = PasswordPolicy.ERR_PASSWORD_POLICY;
    public static final String ERR_COMPLEX_PASSWORD_POLICY = PasswordPolicy.ERR_COMPLEX_PASSWORD_POLICY;

    static final String PASSWORD_SPECIAL_CHARS = "!@#$%^&*()_+-=[]{}|;:,.<>?";

    private final UserMapper userMapper;
    private final AuthTokenMapper authTokenMapper;
    private final TenantService tenantService;
    private final TenantMemberService memberService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AdminUserAdapter(UserMapper userMapper,
                            AuthTokenMapper authTokenMapper,
                            TenantService tenantService,
                            TenantMemberService memberService) {
        this.userMapper = userMapper;
        this.authTokenMapper = authTokenMapper;
        this.tenantService = tenantService;
        this.memberService = memberService;
    }

    // ── 密码策略 ──────────────────────────────────────────────────────────


    /** @return null = 通过；否则 = 错误消息（契约原文）。实现委托 PasswordPolicy.validate。 */
    public String validatePasswordPolicy(String password, boolean complexEnabled) {
        return PasswordPolicy.validate(password, complexEnabled);
    }

    /** 生成直到过策略（复杂 → 16 位复杂密码）。 */
    public String generatePolicyCompliantPassword(boolean complexEnabled) {
        java.security.SecureRandom random = new java.security.SecureRandom();
        while (true) {
            String password;
            if (complexEnabled) {
                StringBuilder sb = new StringBuilder();
                String all = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
                        + PASSWORD_SPECIAL_CHARS;
                for (int i = 0; i < 16; i++) {
                    sb.append(all.charAt(random.nextInt(all.length())));
                }
                password = sb.toString();
            } else {
                byte[] bytes = new byte[24];
                random.nextBytes(bytes);
                password = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            }
            if (validatePasswordPolicy(password, complexEnabled) == null) {
                return password;
            }
        }
    }

    // ── promote / revoke / list ───────────────────────────────────────────

    public AdminUserView byId(String id) {
        return toView(byIdEntity(id));
    }

    User byIdEntity(String id) {
        return id == null || id.isEmpty() ? null
                : userMapper.selectOne(new LambdaQueryWrapper<User>()
                        .eq(User::getId, id).isNull(User::getDeletedAt).last("LIMIT 1"));
    }

    public AdminUserView byEmail(String email) {
        return toView(byEmailEntity(email));
    }

    User byEmailEntity(String email) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getEmail, email).isNull(User::getDeletedAt).last("LIMIT 1"));
    }

    /** 真实写分支（幂等分支由 controller 处理）。 */
    public AdminUserView promote(AdminUserView user) {
        User entity = toEntity(user);
        entity.setIsSystemAdmin(true);
        entity.setUpdatedAt(OffsetDateTime.now());
        userMapper.updateById(entity);
        copyInto(user, entity);
        return toView(entity);
    }

    /**
     * 撤管理员（无事务，顺序读-判-写）：
     * @return user 行；self/last-admin/not-found 抛 AppError 信封（400/404）。
     */
    public AdminUserView revoke(String userId, String callerId) {
        if (userId.equals(callerId)) {
            throw new BizException(AppError.badRequest(
                    "Cannot revoke your own system admin privileges"));
        }
        User user = byIdEntity(userId);
        if (user == null) {
            throw new BizException(AppError.notFound("User not found"));
        }
        if (!user.isIsSystemAdmin()) {
            return toView(user); // idempotent 分支（handler 落 changed=false 审计）
        }
        Long total = countSystemAdmins();
        if (total == null || total <= 1) {
            throw new BizException(AppError.badRequest(
                    "Cannot revoke the last remaining system administrator"));
        }
        user.setIsSystemAdmin(false);
        userMapper.updateById(user);
        return toView(user);
    }

    public PageResult<AdminUserView> listSystemAdmins(int offset, int limit) {
        Long total = countSystemAdmins();
        // offset/limit 语义的 API：atOffset 表达任意偏移，LIMIT/OFFSET 交给分页插件
        List<User> users = userMapper.selectList(PageRequests.atOffset(offset, limit),
                new LambdaQueryWrapper<User>()
                        .eq(User::isIsSystemAdmin, true)
                        .isNull(User::getDeletedAt)
                        .orderByDesc(User::getCreatedAt)
                        .orderByAsc(User::getId));
        List<AdminUserView> views = new ArrayList<>(users.size());
        for (User u : users) {
            views.add(toView(u));
        }
        return PageResult.of(views, total == null ? 0 : total);
    }

    private Long countSystemAdmins() {
        return userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::isIsSystemAdmin, true)
                .isNull(User::getDeletedAt));
    }

    // ── reset-password ────────────────────────────────────────────────────

    /** 换 hash + 吊销全部会话。 */
    public void resetPassword(String userId, String newPassword) {
        User user = byIdEntity(userId);
        if (user == null) {
            return;
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setUpdatedAt(OffsetDateTime.now());
        userMapper.updateById(user);
        revokeTokensByUserId(userId);
    }

    private void revokeTokensByUserId(String userId) {
        try {
            authTokenMapper.update(null, new LambdaUpdateWrapper<AuthToken>()
                    .eq(AuthToken::getUserId, userId)
                    .set(AuthToken::isIsRevoked, true));
        } catch (RuntimeException e) {
            log.warn("Failed to revoke tokens for user {}", userId, e);
        }
    }

    // ── create-user ──────────────────────────────────────────────────────

    public CreateUserResult createUser(String rawUsername, String rawEmail,
                                                String password, boolean passwordPresent,
                                                String provisioning, boolean complexEnabled) {
        String username = LogSanitizer.sanitize(rawUsername == null ? "" : rawUsername.trim());
        String email = LogSanitizer.sanitize(rawEmail == null ? "" : rawEmail.trim());
        String effectivePassword = password;
        boolean generated = false;
        if (!passwordPresent) {
            effectivePassword = generatePolicyCompliantPassword(complexEnabled);
            generated = true;
        }
        String policyError = validatePasswordPolicy(effectivePassword, complexEnabled);
        if (policyError != null) {
            throw new BizException(AppError.badRequest(policyError));
        }

        User existing;
        try {
            existing = register(username, email, effectivePassword, provisioning);
        } catch (DuplicateIdentityException dup) {
            User row = AuthUserErrors.EMAIL_EXISTS.equals(dup.message)
                    ? byEmailEntity(email)
                    : byUsernameEntity(username);
            if (row != null && username.equals(row.getUsername()) && email.equals(row.getEmail())) {
                return new CreateUserResult.Idempotent(toView(row));
            }
            throw new BizException(AppError.conflict(AuthUserErrors.IDENTITY_CONFLICT));
        }
        return generated ? new CreateUserResult.Created(toView(existing), effectivePassword)
                : new CreateUserResult.Created(toView(existing), "");
    }

    /** 部分身份冲突（email 与 username 各自命中不同行）的载体。 */
    static final class DuplicateIdentityException extends RuntimeException {
        final String message;

        public DuplicateIdentityException(String message) {
            super(message);
            this.message = message;
        }
    }

    public AdminUserView byUsername(String username) {
        return toView(byUsernameEntity(username));
    }

    User byUsernameEntity(String username) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username).isNull(User::getDeletedAt).last("LIMIT 1"));
    }

    /** create_personal 租户：建空间 + Owner 成员；失败回滚。 */
    private User register(String username, String email, String hashedPassword, String provisioning) {
        if (byEmailEntity(email) != null) {
            throw new DuplicateIdentityException(AuthUserErrors.EMAIL_EXISTS);
        }
        if (byUsernameEntity(username) != null) {
            throw new DuplicateIdentityException(AuthUserErrors.USERNAME_EXISTS);
        }
        TenantRowView createdTenant = null;
        if ("create_personal".equals(provisioning)) {
            createdTenant = new TenantRowView();
            createdTenant.setName(LogSanitizer.sanitize(username) + "'s Workspace");
            createdTenant.setDescription("Default workspace");
            createdTenant.setStatus("active");
            tenantService.createTenant(createdTenant);
        }
        User user = new User();
        user.setId(UUID.randomUUID().toString());
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(hashedPassword));
        user.setIsActive(true);
        user.setCreatedAt(OffsetDateTime.now());
        user.setUpdatedAt(OffsetDateTime.now());
        if (createdTenant != null) {
            user.setTenantId(createdTenant.getId());
        }
        try {
            userMapper.insert(user);
        } catch (RuntimeException e) {
            if (createdTenant != null) {
                try {
                    tenantService.deleteTenant(createdTenant.getId());
                } catch (RuntimeException rollbackErr) {
                    log.warn("Failed to roll back tenant {} after user creation failure", createdTenant.getId(), rollbackErr);
                }
            }
            throw new IllegalStateException("failed to create user");
        }
        if (createdTenant != null) {
            try {
                memberService.addMember(user.getId(), createdTenant.getId(), "owner", "");
            } catch (RuntimeException e) {
                log.warn("Failed to create owner membership for user {} tenant {}", user.getId(), createdTenant.getId(), e);
                userMapper.deleteById(user.getId());
                tenantService.deleteTenant(createdTenant.getId());
                throw new IllegalStateException("failed to finalise workspace ownership");
            }
        }
        return user;
    }

    /** 实体 → 视图（逐字段搬运，去 passwordHash；null 透传）。 */
    static AdminUserView toView(User u) {
        if (u == null) {
            return null;
        }
        AdminUserView v = new AdminUserView();
        v.setId(u.getId());
        v.setUsername(u.getUsername());
        v.setEmail(u.getEmail());
        v.setAvatar(u.getAvatar());
        v.setTenantId(u.getTenantId());
        v.setIsActive(u.isIsActive());
        v.setCanAccessAllTenants(u.isCanAccessAllTenants());
        v.setIsSystemAdmin(u.isIsSystemAdmin());
        v.setPreferences(u.getPreferences());
        v.setCreatedAt(u.getCreatedAt());
        v.setUpdatedAt(u.getUpdatedAt());
        v.setDeletedAt(u.getDeletedAt());
        return v;
    }

    /** 视图 → 实体（写面用；经实体 setter；null 透传）。 */
    static User toEntity(AdminUserView v) {
        if (v == null) {
            return null;
        }
        User u = new User();
        u.setId(v.getId());
        u.setUsername(v.getUsername());
        u.setEmail(v.getEmail());
        u.setAvatar(v.getAvatar());
        u.setTenantId(v.getTenantId());
        u.setIsActive(v.isIsActive());
        u.setCanAccessAllTenants(v.isCanAccessAllTenants());
        u.setIsSystemAdmin(v.isIsSystemAdmin());
        u.setPreferences(v.getPreferences());
        u.setCreatedAt(v.getCreatedAt());
        u.setUpdatedAt(v.getUpdatedAt());
        u.setDeletedAt(v.getDeletedAt());
        return u;
    }

    /** 落库后回填传入视图（保持"同对象被填充"语义，调用方依赖；null 安全）。 */
    static void copyInto(AdminUserView v, User u) {
        if (v == null || u == null) {
            return;
        }
        AdminUserView fresh = toView(u);
        v.setId(fresh.getId());
        v.setUsername(fresh.getUsername());
        v.setEmail(fresh.getEmail());
        v.setAvatar(fresh.getAvatar());
        v.setTenantId(fresh.getTenantId());
        v.setIsActive(fresh.isIsActive());
        v.setCanAccessAllTenants(fresh.isCanAccessAllTenants());
        v.setIsSystemAdmin(fresh.isIsSystemAdmin());
        v.setPreferences(fresh.getPreferences());
        v.setCreatedAt(fresh.getCreatedAt());
        v.setUpdatedAt(fresh.getUpdatedAt());
        v.setDeletedAt(fresh.getDeletedAt());
    }
}
