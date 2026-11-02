package com.ragagent.system.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.auth.api.CreateUserResult;
import com.ragagent.auth.apikey.service.ApiKeyAdminAdapter;
import com.ragagent.contract.apikey.APIKeyCapability;
import com.ragagent.contract.apikey.APIKeyScopeType;
import com.ragagent.contract.apikey.TenantAPIKeyCreateResponse;
import com.ragagent.contract.apikey.TenantAPIKeyResponse;
import com.ragagent.contract.audit.AuditAction;
import com.ragagent.contract.audit.AuditOutcome;
import com.ragagent.contract.audit.AuditWritePort;
import com.ragagent.contract.auth.AdminUserView;
import com.ragagent.contract.error.AppError;
import com.ragagent.contract.error.BizException;
import com.ragagent.contract.pagination.PageResult;
import com.ragagent.contract.tenant.TenantStorageQuotaPort;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.platform.web.ApiResponse;
import com.ragagent.platform.web.ApiResult;
import com.ragagent.platform.web.JsonMappers;
import com.ragagent.platform.web.NonNullBody;
import com.ragagent.platform.web.RejectEmptyBody;
import com.ragagent.system.dto.SystemDtos;
import com.ragagent.system.dto.SystemSettingResponse;
import com.ragagent.system.service.SystemAdminUserService;
import com.ragagent.system.service.SystemSettingService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/v1/system/admin 组：用户管理 / 平台 API Key / 系统设置 / 运行时队列 / 配额批量应用。
 *
 * <p>整组 SystemAdmin 守卫；审计埋点（promote/revoke/reset/create/api-key/quota）全部落
 * tenant_id=0 的平台行（best-effort）。</p>
 *
 * <p><b>响应形态</b>：裸资源对象（无 {@code data}/{@code success} 包装）；
 * 动作成功（重置密码 / 删除密钥 / 重置设置）→ 204；错误一律 AppError 信封
 * （请求校验用显式 message，如 {@code userId: 不能为空}）。</p>
 *
 * <p><b>runtime/queues 是 Lite 形态</b>（进程内队列）：
 * available=false + queues=[]；mutate/purge → 503 "Task queue is unavailable"。</p>
 */
@RestController
@ApiResult
@RequestMapping("/api/v1/system/admin")
@Tag(name = "System")
public class SystemAdminController {

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    private final SystemAdminUserService users;
    private final SystemSettingService settings;
    private final ApiKeyAdminAdapter apiKeyAdmin;
    private final AuditWritePort auditWriter;
    private final TenantStorageQuotaPort tenantStorageQuotaPort;

    public SystemAdminController(SystemAdminUserService users,
                                 SystemSettingService settings,
                                 ApiKeyAdminAdapter apiKeyAdmin,
                                 AuditWritePort auditWriter,
                                 TenantStorageQuotaPort tenantStorageQuotaPort) {
        this.tenantStorageQuotaPort = tenantStorageQuotaPort;
        this.users = users;
        this.settings = settings;
        this.apiKeyAdmin = apiKeyAdmin;
        this.auditWriter = auditWriter;
    }

    // ──：系统管理员升降级 ──────────────────────────────────────────────

    /** promote 请求（userId 与 email 二选一，userId 优先）。 */
    public record PromoteRequest(String userId, String email) {
    }

    @PostMapping("/promote")
    public ResponseEntity<SystemDtos.UserInfoResponse> promote(
            @Valid @RejectEmptyBody @NonNullBody @RequestBody(required = false) PromoteRequest req) {
        String uid = req.userId() == null ? "" : req.userId().trim();
        String mail = req.email() == null ? "" : req.email().trim();
        if (uid.isEmpty() && mail.isEmpty()) {
            throw new BizException(AppError.badRequest("Either userId or email is required"));
        }
        AdminUserView user = uid.isEmpty() ? users.getUserByEmail(mail) : users.getUserById(uid);
        if (user == null) {
            throw new BizException(AppError.notFound("User not found"));
        }
        if (user.isIsSystemAdmin()) {
            users.emitAdminAudit(AuditAction.SYSTEM_ADMIN_PROMOTED, user, Map.of(
                    "target_email", user.getEmail(),
                    "target_username", user.getUsername(),
                    "idempotent", true));
            return ResponseEntity.ok(SystemDtos.UserInfoResponse.from(user));
        }
        AdminUserView promoted = users.promote(user);
        users.emitAdminAudit(AuditAction.SYSTEM_ADMIN_PROMOTED, promoted, Map.of(
                "target_email", promoted.getEmail(),
                "target_username", promoted.getUsername(),
                "idempotent", false));
        return ResponseEntity.ok(SystemDtos.UserInfoResponse.from(promoted));
    }

    /** revoke 请求。 */
    public record RevokeRequest(@NotBlank(message = "userId: 不能为空") String userId) {
    }

    @PostMapping("/revoke")
    public ResponseEntity<SystemDtos.UserInfoResponse> revoke(
            @Valid @RejectEmptyBody @NonNullBody @RequestBody(required = false) RevokeRequest req) {
        String callerId = TenantContext.currentUserId() == null ? "" : TenantContext.currentUserId();
        AdminUserView user = users.revoke(req.userId(), callerId);
        if (!user.isIsSystemAdmin()) {
            // ErrUserNotSystemAdmin → 幂等 200（changed=false 审计）
            users.emitAdminAudit(AuditAction.SYSTEM_ADMIN_REVOKED, user, Map.of(
                    "target_email", user.getEmail(),
                    "target_username", user.getUsername(),
                    "changed", false));
            return ResponseEntity.ok(SystemDtos.UserInfoResponse.from(user));
        }
        users.emitAdminAudit(AuditAction.SYSTEM_ADMIN_REVOKED, user, Map.of(
                "target_email", user.getEmail(),
                "target_username", user.getUsername(),
                "changed", true));
        return ResponseEntity.ok(SystemDtos.UserInfoResponse.from(user));
    }

    @GetMapping("/list")
    public ResponseEntity<SystemDtos.SystemAdminListResponse> listAdmins(
            @RequestParam(name = "offset", required = false) String offset,
            @RequestParam(name = "limit", required = false) String limit) {
        // best-effort 分页解析：非法值回落默认（不 400）
        int off = 0;
        int lim = 50;
        if (offset != null && !offset.isEmpty()) {
            try {
                int n = Integer.parseInt(offset);
                if (n >= 0) {
                    off = n;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        if (limit != null && !limit.isEmpty()) {
            try {
                int n = Integer.parseInt(limit);
                if (n > 0) {
                    lim = n;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        if (lim > 200) {
            lim = 200;
        }
        PageResult<AdminUserView> page = users.listSystemAdmins(off, lim);
        List<SystemDtos.UserInfoResponse> infos = new ArrayList<>();
        for (AdminUserView u : page.items()) {
            infos.add(SystemDtos.UserInfoResponse.from(u));
        }
        return ResponseEntity.ok(new SystemDtos.SystemAdminListResponse(page.total(), infos));
    }

    // ── 用户管理 ──────────────────────────────────────────────────────────

    /** 密码重置请求。 */
    public record ResetPasswordRequest(
            @NotBlank(message = "email: 不能为空") String email,
            @NotBlank(message = "newPassword: 不能为空") String newPassword) {
    }

    @PostMapping("/users/reset-password")
    public ApiResponse<Void> resetPassword(
            @Valid @RejectEmptyBody @NonNullBody @RequestBody(required = false)
                    ResetPasswordRequest req) {
        String email = req.email().trim();
        if (!SystemAdminUserService.isValidEmail(email)) {
            throw new BizException(AppError.badRequest("Invalid password reset request"));
        }
        String policyError = users.validatePasswordPolicy(req.newPassword(), users.complexPasswordEnabled());
        if (policyError != null) {
            throw new BizException(AppError.badRequest(policyError));
        }
        AdminUserView user = users.getUserByEmail(email);
        if (user == null) {
            throw new BizException(AppError.notFound("User not found"));
        }
        String callerId = TenantContext.currentUserId() == null ? "" : TenantContext.currentUserId();
        if (callerId.equals(user.getId())) {
            throw new BizException(AppError.badRequest("Cannot reset your own password here"));
        }
        users.adminResetPassword(user, req.newPassword());
        users.emitAdminAudit(AuditAction.SYSTEM_USER_PASSWORD_RESET, user, Map.of(
                "target_email", user.getEmail(),
                "target_username", user.getUsername(),
                "sessions_revoked", true));
        return ApiResponse.ok();   // 204 退役（空体与「外壳恒存在」冲突）
    }

    /** 创建用户请求（username 2-50 字符；password 缺省/为 null 时服务端生成）。 */
    public record CreateUserRequest(
            @NotBlank(message = "username: 不能为空") String username,
            @NotBlank(message = "email: 不能为空") String email,
            String password) {
    }

    @PostMapping("/users/create")
    public ResponseEntity<SystemDtos.CreateUserResponse> createUser(
            @Valid @RejectEmptyBody @NonNullBody @RequestBody(required = false) CreateUserRequest req) {
        if (!SystemAdminUserService.isValidEmail(req.email())
                || req.username().length() < 2 || req.username().length() > 50) {
            throw new BizException(AppError.badRequest("Invalid user creation request"));
        }
        boolean passwordPresent = req.password() != null;
        CreateUserResult result = users.adminCreateUser(
                req.username(), req.email(), req.password(), passwordPresent,
                users.resolveDefaultTenantMode());
        return switch (result) {
            case CreateUserResult.Idempotent idem -> {
                users.emitAdminAudit(AuditAction.SYSTEM_USER_CREATED, idem.user(), Map.of(
                        "target_email", idem.user().getEmail(),
                        "target_username", idem.user().getUsername(),
                        "password_generated", false,
                        "idempotent", true));
                yield ResponseEntity.ok(new SystemDtos.CreateUserResponse(
                        SystemDtos.UserInfoResponse.from(idem.user()), null));
            }
            case CreateUserResult.Created created -> {
                users.emitAdminAudit(AuditAction.SYSTEM_USER_CREATED, created.user(), Map.of(
                        "target_email", created.user().getEmail(),
                        "target_username", created.user().getUsername(),
                        "password_generated", !created.generatedPassword().isEmpty(),
                        "idempotent", false));
                yield ResponseEntity.status(HttpStatus.CREATED).body(new SystemDtos.CreateUserResponse(
                        SystemDtos.UserInfoResponse.from(created.user()),
                        created.generatedPassword().isEmpty() ? null : created.generatedPassword()));
            }
        };
    }

    // ── 平台 API Key ──────────────────────────────────────────────────────

    /** 平台 API Key 创建请求（expiresAtUnix 是 epoch 秒）。 */
    public record PlatformAPIKeyCreateRequest(
            @NotBlank(message = "name: 不能为空") String name,
            List<String> capabilities,
            Long expiresAtUnix) {
    }

    @GetMapping("/api-keys")
    public ResponseEntity<List<TenantAPIKeyResponse>> listPlatformKeys() {
        return ResponseEntity.ok(apiKeyAdmin.listPlatformMasked());   // 脱敏在 port 实现侧 ✓
    }

    @PostMapping("/api-keys")
    public ResponseEntity<TenantAPIKeyCreateResponse> createPlatformKey(
            @Valid @RejectEmptyBody @NonNullBody @RequestBody(required = false)
                    PlatformAPIKeyCreateRequest req) {
        List<String> capabilities = req.capabilities() == null ? List.of() : req.capabilities();
        List<String> normalized = APIKeyCapability.normalizeAll(capabilities);
        if (normalized.isEmpty() || normalized.size() != capabilities.size()) {
            throw validation("valid capabilities are required");
        }
        java.time.OffsetDateTime expiresAt = null;
        if (req.expiresAtUnix() != null) {
            expiresAt = java.time.Instant.ofEpochSecond(req.expiresAtUnix())
                    .atOffset(java.time.ZoneOffset.UTC);
            if (!expiresAt.toInstant().isAfter(java.time.Instant.now())) {
                throw validation("expiresAtUnix must be in the future");
            }
        }
        TenantAPIKeyCreateResponse created = apiKeyAdmin.createPlatformMasked(req.name().trim(), normalized, expiresAt);
        emitAPIKeyAudit(AuditAction.SYSTEM_API_KEY_CREATED, created.id(), created.capabilities());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);   // 脱敏/投影在 port 实现侧
    }

    @DeleteMapping("/api-keys/{keyId}")
    public ApiResponse<Void> deletePlatformKey(@PathVariable("keyId") String keyId) {
        long id;
        try {
            id = Long.parseLong(keyId);
        } catch (NumberFormatException e) {
            id = 0;
        }
        if (id == 0) {
            throw new BizException(AppError.badRequest("Invalid API key ID"));
        }
        try {
            apiKeyAdmin.revokePlatform(id);
        } catch (RuntimeException e) {
            throw new BizException(AppError.notFound("Platform API key not found"));
        }
        emitAPIKeyAudit(AuditAction.SYSTEM_API_KEY_REVOKED, id, List.of());
        return ApiResponse.ok();   // 204 退役（空体与「外壳恒存在」冲突）
    }


    /** API-Key 审计（details: scopeType/capabilities；target_type=api_key）。 */
    private void emitAPIKeyAudit(String action, long keyId, List<String> capabilities) {
        var details = new LinkedHashMap<String, Object>();
        details.put("scopeType", APIKeyScopeType.PLATFORM);
        details.put("capabilities", capabilities);
        auditWriter.logBestEffort(new AuditWritePort.AuditRecord(
                0L,
                TenantContext.currentUserId() == null ? "" : TenantContext.currentUserId(),
                SystemAdminUserService.systemAuditActorRole(),
                action,
                "api_key",
                String.valueOf(keyId),
                "",
                "",
                "",
                AuditOutcome.SUCCESS,
                MAPPER.valueToTree(details).toString()));
    }

    /** validation 工厂：code 1010 信封；message 取传入原文、details 为 null。 */
    private static BizException validation(String message) {
        return new BizException(AppError.validation(message));
    }

    // ──：系统设置 ──────────────────────────────────────────────────────

    @GetMapping("/settings")
    public ResponseEntity<List<SystemSettingResponse>> listSettings() {
        return ResponseEntity.ok(settings.list().stream()
                .map(SystemSettingResponse::from).toList());
    }

    @GetMapping("/settings/{key}")
    public ResponseEntity<SystemSettingResponse> getSetting(
            @PathVariable("key") String key) {
        try {
            return ResponseEntity.ok(SystemSettingResponse.from(settings.get(key)));
        } catch (IllegalArgumentException e) {
            throw new BizException(AppError.badRequest(e.getMessage()));
        }
    }

    /** 设置更新请求（value 必填）。 */
    public record UpdateSettingRequest(@NotNull(message = "value: 不能为空") Object value) {
    }

    @PutMapping("/settings/{key}")
    public ResponseEntity<SystemSettingResponse> updateSetting(
            @PathVariable("key") String key,
            @Valid @RejectEmptyBody @NonNullBody @RequestBody(required = false)
                    UpdateSettingRequest req) {
        try {
            return ResponseEntity.ok(SystemSettingResponse.from(
                    settings.update(key, MAPPER.valueToTree(req.value()))));
        } catch (IllegalArgumentException e) {
            throw new BizException(AppError.badRequest(e.getMessage()));
        }
    }

    @DeleteMapping("/settings/{key}")
    public ApiResponse<Void> resetSetting(@PathVariable("key") String key) {
        try {
            settings.reset(key);
        } catch (IllegalArgumentException e) {
            throw new BizException(AppError.badRequest(e.getMessage()));
        }
        return ApiResponse.ok();   // 204 退役（空体与「外壳恒存在」冲突）
    }

    // ── 运行时队列（Lite） ────────────────────────────────────────────────

    /** 运行时队列名（isKnownRuntimeQueue 的判定集）。 */
    private static final List<String> KNOWN_QUEUES = List.of(
            "default", "chat_attachment", "postprocess", "summary", "multimodal",
            "graph", "question", "memory", "sync", "low", "wiki");

    @GetMapping("/runtime/queues")
    public ResponseEntity<SystemDtos.RuntimeQueuesResponse> runtimeQueues() {
        // 每池 concurrency = setting/env/默认 的正数折叠
        int core = positive("asynq.core_concurrency", "RAGAGENT_ASYNQ_CORE_CONCURRENCY", 8);
        int postProcess = positive("asynq.postprocess_concurrency",
                "RAGAGENT_ASYNQ_POSTPROCESS_CONCURRENCY", 2);
        int enrichment = positive("asynq.enrichment_concurrency",
                "RAGAGENT_ASYNQ_ENRICHMENT_CONCURRENCY", 12);
        int maintenance = positive("asynq.maintenance_concurrency",
                "RAGAGENT_ASYNQ_MAINTENANCE_CONCURRENCY", 4);
        int shared = positive("asynq.shared_concurrency", "RAGAGENT_ASYNQ_SHARED_CONCURRENCY", 6);
        int wiki = positive("asynq.wiki_concurrency", "RAGAGENT_WIKI_ASYNQ_CONCURRENCY", 8);
        int upstreamTotal = core + postProcess + enrichment + maintenance + shared;
        // queue_count = 各池的 QueueDefinitions 条目数 / 共享池 = SharedWeight>0 的条目数
        List<SystemDtos.RuntimeWorkerPool> pools = List.of(
                new SystemDtos.RuntimeWorkerPool("core", core, 2, 0, 0, 0, 0),
                new SystemDtos.RuntimeWorkerPool("postprocess", postProcess, 1, 0, 0, 0, 0),
                new SystemDtos.RuntimeWorkerPool("enrichment", enrichment, 5, 0, 0, 0, 0),
                new SystemDtos.RuntimeWorkerPool("maintenance", maintenance, 2, 0, 0, 0, 0),
                new SystemDtos.RuntimeWorkerPool("shared", shared, 7, 0, 0, 0, 0),
                new SystemDtos.RuntimeWorkerPool("wiki", wiki, 1, 0, 0, 0, 0));
        // Lite：无上游队列统计 → available=false + queues=[]；
        // 本地限流器 RuntimeStats 恒可用（available=true）且进程内无已获取信号量 → []
        return ResponseEntity.ok(new SystemDtos.RuntimeQueuesResponse(
                false, upstreamTotal, upstreamTotal, wiki, pools,
                List.of(), true, List.of(), java.time.Instant.now().getEpochSecond()));
    }

    /** 正数折叠（<1 → fallback）。 */
    private int positive(String key, String env, int fallback) {
        long v = settings.getInt(key, env, fallback);
        return v < 1 ? fallback : (int) v;
    }

    @GetMapping("/runtime/queues/{queue}/tasks")
    public ResponseEntity<SystemDtos.RuntimeTasksResponse> listRuntimeTasks(
            @PathVariable("queue") String queue,
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "pageSize", required = false) String pageSize) {
        if (!KNOWN_QUEUES.contains(queue)) {
            throw new BizException(AppError.badRequest("Unknown task queue"));
        }
        if (state == null || !List.of("pending", "active", "scheduled", "retry", "archived", "completed")
                .contains(state)) {
            throw new BizException(AppError.badRequest("Unknown task state"));
        }
        // 分页大小：默认 20；<1 → 20；>100 → 100；非法 → 20
        int size;
        try {
            size = pageSize == null ? 20 : Integer.parseInt(pageSize);
        } catch (NumberFormatException e) {
            size = 20;
        }
        if (size < 1) {
            size = 20;
        }
        if (size > 100) {
            size = 100;
        }
        // Lite：noopTaskInspector 不实现 RuntimeTaskInspector → available=false 空页
        return ResponseEntity.ok(new SystemDtos.RuntimeTasksResponse(false, List.of(), size, false, null));
    }

    @PostMapping("/runtime/queues/{queue}/tasks/{taskId}/actions/{action}")
    public ResponseEntity<Void> mutateRuntimeTask(@PathVariable("queue") String queue,
                                               @PathVariable("taskId") String taskId,
                                               @PathVariable("action") String action) {
        if (!KNOWN_QUEUES.contains(queue) || taskId.isEmpty()) {
            throw new BizException(AppError.badRequest("Invalid queue or task ID"));
        }
        // Lite：进程内队列不支持任务变更 → 503
        throw new BizException(AppError.serviceUnavailable("Task queue is unavailable"));
    }

    @DeleteMapping("/runtime/queues/{queue}/archived")
    public ResponseEntity<Void> purgeArchived(@PathVariable("queue") String queue) {
        if (!KNOWN_QUEUES.contains(queue)) {
            throw new BizException(AppError.badRequest("Invalid queue"));
        }
        throw new BizException(AppError.serviceUnavailable("Task queue is unavailable"));
    }

    // ── 配额批量应用 ──────────────────────────────────────────────────────

    /** 配额批量应用结果（affected = 被重置的租户数）。 */
    public record StorageQuotaApplyResponse(int affected, long quotaBytes, long quotaGb) {
    }

    @PostMapping("/tenants/apply-default-storage-quota")
    public ResponseEntity<StorageQuotaApplyResponse> applyDefaultStorageQuota() {
        long gb = settings.getInt("tenant.default_storage_quota_gb",
                "RAGAGENT_TENANT_DEFAULT_STORAGE_QUOTA_GB", 10);
        if (gb <= 0) {
            gb = 10;
        }
        long quotaBytes = gb * 1024 * 1024 * 1024;
        // 全表写走具名 Mapper 方法（FullTableWriteGuard 登记例外），不再匿名 update(null, wrapper)
        int affected = tenantStorageQuotaPort.applyDefaultStorageQuota(quotaBytes);
        var details = new LinkedHashMap<String, Object>();
        details.put("quotaBytes", quotaBytes);
        details.put("quotaGb", gb);
        details.put("affected", affected);
        auditWriter.logBestEffort(new AuditWritePort.AuditRecord(
                0L,
                TenantContext.currentUserId() == null ? "" : TenantContext.currentUserId(),
                SystemAdminUserService.systemAuditActorRole(),
                AuditAction.SYSTEM_SETTING_CHANGED,
                "tenant_storage_quota",
                "all",
                "",
                "",
                "",
                AuditOutcome.SUCCESS,
                MAPPER.valueToTree(details).toString()));
        return ResponseEntity.ok(new StorageQuotaApplyResponse(affected, quotaBytes, gb));
    }
}
