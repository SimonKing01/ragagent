package com.ragagent.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ragagent.audit.domain.AuditLog;
import com.ragagent.audit.dto.AuditLogQuery;
import com.ragagent.audit.repository.AuditLogRepository;
import com.ragagent.audit.service.AuditLogService;
import com.ragagent.contract.audit.AuditAction;
import com.ragagent.contract.audit.AuditOutcome;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 审计服务语义测试。
 *
 * <p>仓储用 Mockito 桩 + 可推进的 {@link FakeClock}：
 * 断言钉住服务语义本身。</p>
 */
class AuditLogServiceTest {

    private static final Instant BASE = Instant.parse("2026-05-14T10:00:00Z");
    private static final ZoneId ZONE = ZoneOffset.UTC;

    /** 可控时间源，测试无需 sleep 即可模拟"一分钟后"。 */
    static final class FakeClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        FakeClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId z) { return new FakeClock(instant, z); }
        @Override public Instant instant() { return instant; }

        /** 推进时钟。 */
        void advance(Duration by) { instant = instant.plus(by); }
    }

    /** 测试夹具：桩仓储 + 夹具时钟 + 服务。 */
    private record Fixture(AuditLogService svc, AuditLogRepository repo, FakeClock clock,
                           List<AuditLog> created, AtomicInteger dedupErrorInjector) {}

    /**
     * 测试夹具：Create 收进内存列表；CountSinceForDedup 从该列表现算；
     * {@code dedupErrorInjector} 非零时让 count 抛错。
     */
    private static Fixture newFixture() {
        FakeClock clock = new FakeClock(BASE, ZONE);
        AuditLogRepository repo = mock(AuditLogRepository.class);
        List<AuditLog> created = new ArrayList<>();
        AtomicInteger dedupFailures = new AtomicInteger(0);

        doAnswer(inv -> {
            created.add(inv.getArgument(0));
            return null;
        }).when(repo).create(any(AuditLog.class));

        when(repo.countSinceForDedup(anyLong(), anyString(), anyString(), anyString(), any()))
                .thenAnswer(inv -> {
                    if (dedupFailures.get() > 0) {
                        throw new IllegalStateException("transient");
                    }
                    long tenantId = inv.getArgument(0);
                    String actor = inv.getArgument(1);
                    String action = inv.getArgument(2);
                    String path = inv.getArgument(3);
                    OffsetDateTime since = inv.getArgument(4);
                    long n = 0;
                    for (AuditLog e : created) {
                        if (e.getTenantId() == tenantId
                                && e.getActorUserId().equals(actor)
                                && e.getAction().equals(action)
                                && e.getRequestPath().equals(path)
                                && !e.getCreatedAt().isBefore(since)) {
                            n++;
                        }
                    }
                    return n;
                });

        return new Fixture(new AuditLogService(repo, clock), repo, clock, created, dedupFailures);
    }

    // ── Log ──────────────────────────────────────────────────────────────

    @Test
    void logFillsCreatedAtAndOutcome() {
        Fixture f = newFixture();
        AuditLog entry = new AuditLog();
        entry.setTenantId(7L);
        // Outcome 与 CreatedAt 都留空
        entry.setAction(AuditAction.MEMBER_ADDED);

        f.svc().log(entry);

        assertThat(f.created()).hasSize(1);
        assertThat(entry.getCreatedAt()).isEqualTo(OffsetDateTime.ofInstant(BASE, ZONE));
        assertThat(entry.getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
    }

    /** schema 要求 action，服务先挡住。 */
    @Test
    void logRejectsEmptyAction() {
        Fixture f = newFixture();
        AuditLog entry = new AuditLog();
        entry.setTenantId(7L);

        assertThatThrownBy(() -> f.svc().log(entry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("action is required");
        assertThat(f.created()).isEmpty();
    }

    /** null entry → 拒绝且不落库。 */
    @Test
    void logRejectsNullEntry() {
        Fixture f = newFixture();
        assertThatThrownBy(() -> f.svc().log(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nil entry");
    }

    /** best-effort 语义：仓储炸了也不影响调用方。 */
    @Test
    void logBestEffortSwallowsRepositoryFailure() {
        Fixture f = newFixture();
        doThrow(new IllegalStateException("db down")).when(f.repo()).create(any(AuditLog.class));

        AuditLog entry = new AuditLog();
        entry.setTenantId(7L);
        entry.setAction(AuditAction.KB_CREATED);

        // 不抛 = 通过（best-effort 入口吞错）
        f.svc().logBestEffort(entry);

        // 但严格入口仍然要抛
        assertThatThrownBy(() -> f.svc().log(entry))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── LogDenied 去重 ────────────────────────────────────────────────────

    /**
     * 窗口内第二次拒绝<b>不</b>落库——去重原语就是"探测型客户端无法以线速刷表"的保证。
     */
    @Test
    void logDeniedDedupesRepeatedRejectsWithinWindow() {
        Fixture f = newFixture();

        f.svc().logDenied(7L, "u-viewer", "viewer", "admin", "/api/v1/tenants/7", "PUT");
        f.svc().logDenied(7L, "u-viewer", "viewer", "admin", "/api/v1/tenants/7", "PUT");

        assertThat(f.created()).hasSize(1);
        AuditLog row = f.created().get(0);
        assertThat(row.getAction()).isEqualTo(AuditAction.ACCESS_DENIED);
        assertThat(row.getOutcome()).isEqualTo(AuditOutcome.DENIED);
        assertThat(row.getRequestPath()).isEqualTo("/api/v1/tenants/7");
        assertThat(row.getRequestMethod()).isEqualTo("PUT");
        assertThat(row.getActorRole()).isEqualTo("viewer");
        assertThat(row.getDetails().get("requiredRole").asText()).isEqualTo("admin");
        // rawPath 与 request_path 相同时**不写**（去重同一资源）
        assertThat(row.getDetails().has("rawPath")).isFalse();
        assertThat(row.getDetails().fieldNames()).toIterable().containsExactly("requiredRole");
    }

    /**
     * 去重是滑动窗口而非一次性锁——窗口空掉后下一次拒绝必须记。
     */
    @Test
    void logDeniedWritesAgainAfterWindowExpires() {
        Fixture f = newFixture();

        f.svc().logDenied(7L, "u-viewer", "viewer", "admin", "/api/v1/tenants/7", "PUT");
        f.clock().advance(AuditLogService.DENY_DEDUP_WINDOW.plusSeconds(1));
        f.svc().logDenied(7L, "u-viewer", "viewer", "admin", "/api/v1/tenants/7", "PUT");

        assertThat(f.created()).hasSize(2);
    }

    /**
     * 两个不同 actor 打同一端点、或同一 actor 打两个不同端点，都必须各自留痕。
     * 去重键是 (tenant, actor, action, path)。
     */
    @Test
    void logDeniedDedupIsPerActorAndPath() {
        Fixture f = newFixture();

        f.svc().logDenied(7L, "u-viewer-a", "viewer", "admin", "/api/v1/tenants/7", "PUT");
        f.svc().logDenied(7L, "u-viewer-b", "viewer", "admin", "/api/v1/tenants/7", "PUT");
        f.svc().logDenied(7L, "u-viewer-a", "viewer", "admin", "/api/v1/agents/abc", "PUT");

        assertThat(f.created()).hasSize(3);
    }

    /**
     * 去重 count 出错（DB 抖动）时<b>绝不能</b>静默跳过审计行——事件响应期间
     * 写一行重复远好过丢一次拒绝记录。
     */
    @Test
    void logDeniedDegradesGracefullyOnDedupLookupError() {
        Fixture f = newFixture();
        f.dedupErrorInjector().set(1);

        f.svc().logDenied(7L, "u-viewer", "viewer", "admin", "/api/v1/tenants/7", "PUT");

        assertThat(f.created()).hasSize(1);
    }

    /**
     * 路由模板与原始 URL 不同时：{@code request_path} 记<b>模板</b>（去重键稳定，
     * 遍历 UUID 无法绕开窗口），原始 URL 进 Details 的 {@code rawPath} 供取证。
     *
     * <p>这是对运行中 dev server 实测确认的契约：
     * {@code {"request_path":"/api/v1/tenants/:id/audit-log",
     * "details":{"rawPath":"/api/v1/tenants/10002/audit-log","requiredRole":"admin"}}}。</p>
     */
    @Test
    void logDeniedUsesRouteTemplateAndKeepsRawPathInDetails() {
        Fixture f = newFixture();

        f.svc().logDenied(10002L, "u-viewer", "viewer", "admin",
                "/api/v1/tenants/{id}/audit-log", "GET", "/api/v1/tenants/10002/audit-log");

        assertThat(f.created()).hasSize(1);
        AuditLog row = f.created().get(0);
        assertThat(row.getRequestPath()).isEqualTo("/api/v1/tenants/{id}/audit-log");
        assertThat(row.getRequestMethod()).isEqualTo("GET");
        assertThat(row.getDetails().get("rawPath").asText())
                .isEqualTo("/api/v1/tenants/10002/audit-log");
        assertThat(row.getDetails().get("requiredRole").asText()).isEqualTo("admin");
        // 键序按字母序：rawPath 在 requiredRole 之前
        assertThat(row.getDetails().fieldNames()).toIterable()
                .containsExactly("rawPath", "requiredRole");
    }

    /** 调用点会传 {@code "system_admin"} 字面量（RequireSystemAdmin 路径，实测所见）。 */
    @Test
    void logDeniedAcceptsSystemAdminLiteralRole() {
        Fixture f = newFixture();

        f.svc().logDenied(10002L, "u-owner", "user", "system_admin",
                "/api/v1/system/admin/audit-log", "GET");

        assertThat(f.created().get(0).getDetails().get("requiredRole").asText())
                .isEqualTo("system_admin");
    }

    // ── List ─────────────────────────────────────────────────────────────

    /** List 纯粹代理到仓储，不在服务层重复校验租户。 */
    @Test
    void listDelegatesToRepository() {
        Fixture f = newFixture();
        List<AuditLog> rows = List.of(new AuditLog());
        when(f.repo().list(eq(7L), any())).thenReturn(rows);

        assertThat(f.svc().list(7L, AuditLogQuery.empty())).isSameAs(rows);
    }
}
