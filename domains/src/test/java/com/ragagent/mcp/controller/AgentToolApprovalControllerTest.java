package com.ragagent.mcp.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.contract.error.BizException;
import com.ragagent.mcp.approval.Decision;
import com.ragagent.mcp.approval.ToolApprovalException;
import com.ragagent.mcp.approval.ToolApprovalGate;
import com.ragagent.mcp.dto.ResolveToolApprovalRequest;
import com.ragagent.platform.access.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@code ResolveToolApproval} 的行为契约。
 *
 * <p>重点钉住四哨兵 → HTTP 映射表与原文案，以及 {@code modified_args}
 * 的"非 null JSON 对象"前置校验（字面量 "null" 只有 4 字节，非空长度检查挡不住，
 * 会让下游工具拿到 null 参数表、静默丢掉原始参数）。</p>
 */
class AgentToolApprovalControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ToolApprovalGate gate;
    private AgentToolApprovalController controller;

    @BeforeEach
    void setUp() {
        gate = mock(ToolApprovalGate.class);
        controller = new AgentToolApprovalController(new com.ragagent.mcp.service.AgentToolApprovalService(gate));
        // 处理器从租户上下文取 tenant/principal/角色，先种好上下文
        TenantContext.set(7L, TenantContext.webUserPrincipal("user-1"), "admin", false, "user-1", false);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static ResolveToolApprovalRequest approve(String modifiedArgsJson) throws Exception {
        return new ResolveToolApprovalRequest("approve",
                modifiedArgsJson == null ? null : JSON.readTree(modifiedArgsJson), "why");
    }

    // ── 哨兵 → 404/400 映射表 ────────────────────────────────────────────

    @Test
    void pendingNotFoundMapsTo404() throws Exception {
        doThrow(ToolApprovalException.pendingNotFound()).when(gate).resolve(any(Long.class), any(), any(), any());

        BizException e = assertThrows(BizException.class,
                () -> controller.resolveToolApproval("p1", approve(null)));

        assertEquals(404, e.appError().httpCode());
        assertEquals("pending approval not found or already completed", e.appError().message());
    }

    @Test
    void alreadyResolvedMapsTo400() throws Exception {
        doThrow(ToolApprovalException.alreadyResolved()).when(gate).resolve(any(Long.class), any(), any(), any());

        BizException e = assertThrows(BizException.class,
                () -> controller.resolveToolApproval("p1", approve(null)));

        assertEquals(400, e.appError().httpCode());
        assertEquals("pending approval already resolved (timeout / cancel raced your action)",
                e.appError().message());
    }

    @Test
    void tenantMismatchMapsTo400WorkspaceMismatch() throws Exception {
        doThrow(ToolApprovalException.tenantMismatch()).when(gate).resolve(any(Long.class), any(), any(), any());

        BizException e = assertThrows(BizException.class,
                () -> controller.resolveToolApproval("p1", approve(null)));

        assertEquals(400, e.appError().httpCode());
        assertEquals("workspace mismatch", e.appError().message());
    }

    @Test
    void userMismatchMapsTo400OwnerOnly() throws Exception {
        doThrow(ToolApprovalException.userMismatch()).when(gate).resolve(any(Long.class), any(), any(), any());

        BizException e = assertThrows(BizException.class,
                () -> controller.resolveToolApproval("p1", approve(null)));

        assertEquals(400, e.appError().httpCode());
        assertEquals("user mismatch: only the session owner may resolve this approval",
                e.appError().message());
    }

    @Test
    void internalApprovalErrorMapsTo500() throws Exception {
        doThrow(ToolApprovalException.internal("boom")).when(gate).resolve(any(Long.class), any(), any(), any());

        BizException e = assertThrows(BizException.class,
                () -> controller.resolveToolApproval("p1", approve(null)));

        assertEquals(500, e.appError().httpCode());
    }

    // ── 前置校验 ─────────────────────────────────────────────────────────

    @Test
    void rejectsInvalidDecision() throws Exception {
        BizException e = assertThrows(BizException.class, () -> controller.resolveToolApproval("p1",
                new ResolveToolApprovalRequest("maybe", null, "")));

        assertEquals("decision must be approve or reject", e.appError().message());
        verify(gate, never()).resolve(any(Long.class), any(), any(), any());
    }

    /**
     * 显式 JSON {@code null} 是**跳过**（不是 400）：字面量 "null" 被排除在
     * 解析之外，modifiedArgs 保持 null。
     * 真正的 400 只留给"能解析但解析出来不是对象"的载荷。
     */
    @Test
    void nullModifiedArgsIsIgnoredNotRejected() throws Exception {
        controller.resolveToolApproval("p1", approve("null"));

        ArgumentCaptor<Decision> captor = ArgumentCaptor.forClass(Decision.class);
        verify(gate).resolve(eq(7L), eq("web_user:user-1"), eq("p1"), captor.capture());
        assertEquals(true, captor.getValue().approved());
        assertEquals(null, captor.getValue().modifiedArgs());
    }

    @Test
    void rejectsNonObjectModifiedArgs() throws Exception {
        BizException e = assertThrows(BizException.class,
                () -> controller.resolveToolApproval("p1", approve("[1,2]")));

        assertEquals("modified_args must be a non-null JSON object", e.appError().message());
    }

    @Test
    void requiresAuthenticatedUser() throws Exception {
        TenantContext.set(7L, null, "admin", false, null, false);

        BizException e = assertThrows(BizException.class,
                () -> controller.resolveToolApproval("p1", approve(null)));

        assertEquals(401, e.appError().httpCode());
        assertEquals("authenticated user required to resolve tool approval", e.appError().message());
        verify(gate, never()).resolve(any(Long.class), any(), any(), any());
    }

    @Test
    void rejectsEmptyWorkspace() throws Exception {
        TenantContext.set(0L, null, "admin", false, "user-1", false);

        BizException e = assertThrows(BizException.class,
                () -> controller.resolveToolApproval("p1", approve(null)));

        assertEquals("Workspace ID cannot be empty", e.appError().message());
    }

    @Test
    void gateNotConfiguredReturns500() {
        AgentToolApprovalController unconfigured = new AgentToolApprovalController(new com.ragagent.mcp.service.AgentToolApprovalService((com.ragagent.mcp.approval.ToolApprovalGate) null));

        BizException e = assertThrows(BizException.class, () -> unconfigured.resolveToolApproval("p1",
                new ResolveToolApprovalRequest("approve", null, "")));

        assertEquals("Tool approval gate is not configured", e.appError().message());
    }

    // ── 决策构造 ─────────────────────────────────────────────────────────

    @Test
    void approvePassesReasonAndModifiedArgs() throws Exception {
        controller.resolveToolApproval("p1", approve("{\"path\":\"/tmp/x\"}"));

        ArgumentCaptor<Decision> captor = ArgumentCaptor.forClass(Decision.class);
        verify(gate).resolve(eq(7L), eq("web_user:user-1"), eq("p1"), captor.capture());
        Decision d = captor.getValue();
        assertEquals(true, d.approved());
        assertEquals("why", d.reason(), "approve 也必须带上 reason（Go 的 dec := Decision{Reason: ...}）");
        assertEquals(JSON.readTree("{\"path\":\"/tmp/x\"}"), JSON.readTree(d.modifiedArgs()));
    }

    @Test
    void approveWithoutModifiedArgsLeavesThemUnset() throws Exception {
        controller.resolveToolApproval("p1", approve(null));

        ArgumentCaptor<Decision> captor = ArgumentCaptor.forClass(Decision.class);
        verify(gate).resolve(eq(7L), eq("web_user:user-1"), eq("p1"), captor.capture());
        assertEquals(null, captor.getValue().modifiedArgs(),
                "缺失的 modified_args 不得变成 \"null\" 字符串（下游会当成参数表）");
    }

    @Test
    void rejectPassesReasonAndNoModifiedArgs() throws Exception {
        controller.resolveToolApproval("p1",
                new ResolveToolApprovalRequest("reject", null, "not allowed"));

        ArgumentCaptor<Decision> captor = ArgumentCaptor.forClass(Decision.class);
        verify(gate).resolve(eq(7L), eq("web_user:user-1"), eq("p1"), captor.capture());
        Decision d = captor.getValue();
        assertEquals(false, d.approved());
        assertEquals("not allowed", d.reason());
    }

    /** 空对象 {} 是合法的"改用空参数表"（非 null 即通过） */
    @Test
    void acceptsEmptyObjectModifiedArgs() throws Exception {
        controller.resolveToolApproval("p1", approve("{}"));

        ArgumentCaptor<Decision> captor = ArgumentCaptor.forClass(Decision.class);
        verify(gate).resolve(eq(7L), eq("web_user:user-1"), eq("p1"), captor.capture());
        assertEquals("{}", captor.getValue().modifiedArgs());
    }
}
