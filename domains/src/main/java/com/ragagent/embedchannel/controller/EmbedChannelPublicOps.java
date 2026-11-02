package com.ragagent.embedchannel.controller;

import com.ragagent.contract.knowledge.ChunkRowView;
import com.ragagent.contract.session.EmbedSessionOps;
import com.ragagent.embedchannel.domain.EmbedChannelEntity;
import com.ragagent.embedchannel.service.EmbedChannelService;
import com.ragagent.embedchannel.support.EmbedError;
import com.ragagent.embedchannel.support.EmbedTokens;
import com.ragagent.platform.security.LogSanitizer;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * embed 公开面协作者（自 {@link EmbedChannelController} 拆出的公开段）：
 * session token 兑换、公开配置、
 * 推荐问题、chunk 读取与访客会话创建。EmbedAuthFilter 已在过滤器层跑完。
 * 持门面回引（ctrl）取 service/sessionService/sessionRepository；渠道解析与
 * 响应组构件经门面类名调用。
 */
final class EmbedChannelPublicOps {

    /** 历史会话列表上限。 */
    private static final int MAX_HISTORY_SESSIONS = 50;

    private final EmbedChannelController ctrl;

    EmbedChannelPublicOps(EmbedChannelController ctrl) {
        this.ctrl = ctrl;
    }

    // ── 公开面（EmbedAuthFilter 已跑） ──

    /** 只有 publish token 能换 session token。 */
    /** 换取访客会话令牌：{expiresIn, sessionToken}。 */
    public record ExchangeResponse(int expiresIn, String sessionToken) {
    }

    public ResponseEntity<ExchangeResponse> exchange(@PathVariable("channelId") String channelId) {
        EmbedChannelEntity ch = EmbedChannelRows.channel(EmbedChannelController.request0());
        String auth = EmbedChannelController.trim(EmbedChannelController.request0().getHeader("Authorization"));
        boolean publishToken = auth.startsWith("Embed ")
                && !EmbedTokens.isSessionToken(auth.substring("Embed ".length()));
        if (!publishToken) {
            return EmbedChannelController.plainError(403, "publish token required");
        }
        EmbedChannelService.IssueResult result;
        try {
            result = ctrl.service.issueSessionToken(ch.getId());
        } catch (EmbedError e) {
            if (e.kind == EmbedError.Kind.SESSION_UNAVAILABLE) {
                return EmbedChannelController.plainError(503, "session tokens unavailable");
            }
            return EmbedChannelController.plainError(500, "failed to issue session token");
        }
        return ResponseEntity.ok(new ExchangeResponse(result.expiresIn(), result.token()));
    }

    /** 公开配置视图。 */
    public ResponseEntity<com.fasterxml.jackson.databind.node.ObjectNode> config(@PathVariable("channelId") String channelId) {
        EmbedChannelEntity ch = EmbedChannelRows.channel(EmbedChannelController.request0());
        return ResponseEntity.ok(ctrl.service.publicConfig(ch));
    }

    /** 公开建议问题（开关关闭或失败时返回空列表）。 */
    /** 推荐问题：{questions:[...]}。 */
    public record SuggestedQuestionsResponse(List<Object> questions) {
    }

    public ResponseEntity<SuggestedQuestionsResponse> suggestedQuestions(
            @PathVariable("channelId") String channelId,
            @RequestParam(name = "limit", required = false) String limit) {
        EmbedChannelEntity ch = EmbedChannelRows.channel(EmbedChannelController.request0());
        if (!ch.isShowSuggestedQuestions()) {
            return ResponseEntity.ok(new SuggestedQuestionsResponse(List.of()));
        }
        int limitInt = 0;
        if (limit != null && !limit.isEmpty()) {
            try {
                int n = Integer.parseInt(limit);
                if (n > 0) {
                    limitInt = Math.min(n, 12);
                }
            } catch (NumberFormatException ignored) {
                // 解析失败按"未指定"处理
            }
        }
        java.util.List<Object> questions;
        try {
            questions = ctrl.service.suggestedQuestions(ch, limitInt);
        } catch (RuntimeException e) {
            return EmbedChannelController.plainError(500, "failed to load suggested questions");
        }
        return ResponseEntity.ok(new SuggestedQuestionsResponse(questions == null ? List.of() : questions));
    }

    /** 公开分块读取（白名单校验 + 404/403 分支）。 */
    public ResponseEntity<?> chunk(@PathVariable("chunkId") String chunkId) {
        EmbedChannelEntity ch = EmbedChannelRows.channel(EmbedChannelController.request0());
        String cid = LogSanitizer.sanitize(chunkId);
        if (cid.isEmpty()) {
            return EmbedChannelController.plainError(400, "chunk_id is required");
        }
        try {
            ChunkRowView chunk = ctrl.service.embedChunk(ch, cid);
            return ResponseEntity.ok(chunk);
        } catch (EmbedChannelService.ChunkNotFoundError e) {
            return EmbedChannelController.plainError(404, "chunk not found");
        } catch (EmbedChannelService.ChunkForbiddenError e) {
            return EmbedChannelController.plainError(403, "chunk not accessible");
        }
    }

    /** 创建访客会话：201 {id, sig}。访客 id（可选）随会话落库，供历史会话列表。 */
    /** 新建访客会话：{id, sig}。 */
    public record SessionCreatedResponse(String id, String sig) {
    }

    public ResponseEntity<SessionCreatedResponse> createSession(
            @PathVariable("channelId") String channelId) {
        EmbedChannelEntity ch = EmbedChannelRows.channel(EmbedChannelController.request0());
        long tenantId = EmbedChannelController.currentTenant();
        String visitor = EmbedChannelController.trim(
                EmbedChannelController.request0().getHeader("X-Embed-Visitor"));
        if (!visitor.isEmpty() && !EmbedChannelController.validVisitor(visitor)) {
            return EmbedChannelController.plainError(400, "invalid embed visitor id");
        }
        String createdId;
        try {
            createdId = ctrl.embedSessionOps.createVisitorSession(tenantId, ch.getId(),
                    EmbedChannelService.embedSessionDescription(ch.getId()), visitor);
        } catch (RuntimeException e) {
            return EmbedChannelController.plainError(500, "failed to create session");
        }
        return ResponseEntity.status(201)
                .body(new SessionCreatedResponse(createdId, EmbedTokens.signHandle(ch, createdId)));
    }

    /**
     * 删除本访客在本渠道下的一个历史会话（软删）。
     *
     * <p>只允许删本渠道 marker + 本访客名下的会话；不属于该访客时返回 404，
     * 不泄露会话是否存在。删除后该会话不再出现在历史列表里。</p>
     */
    /** 删除访客会话：{deleted:true}。 */
    public record SessionDeletedResponse(boolean deleted) {
    }

    public ResponseEntity<SessionDeletedResponse> deleteSession(
            @PathVariable("channelId") String channelId,
            @PathVariable("sessionId") String sessionId) {
        EmbedChannelEntity ch = EmbedChannelRows.channel(EmbedChannelController.request0());
        long tenantId = EmbedChannelController.currentTenant();
        String visitor = EmbedChannelController.trim(
                EmbedChannelController.request0().getHeader("X-Embed-Visitor"));
        if (!EmbedChannelController.validVisitor(visitor)) {
            return EmbedChannelController.plainError(400, "invalid embed visitor id");
        }
        boolean deleted;
        try {
            deleted = ctrl.embedSessionOps.deleteVisitorSession(tenantId,
                    EmbedChannelService.embedSessionDescription(ch.getId()), visitor, sessionId);
        } catch (RuntimeException e) {
            return EmbedChannelController.plainError(500, "failed to delete session");
        }
        if (!deleted) {
            return EmbedChannelController.plainError(404, "session not found");
        }
        return ResponseEntity.ok(new SessionDeletedResponse(true));
    }

    /**
     * 访客历史会话列表：{@code {sessions:[{id, title, updatedAt, sig}]}}。
     *
     * <p>只含本渠道 + 本访客、**说过话**的会话（空会话不进列表）；按最后活动倒序，
     * 上限 {@value #MAX_HISTORY_SESSIONS} 条。逐条附会话句柄签名——读消息时必须出示，
     * 而前端只在创建会话时领过一次。</p>
     */
    /** 访客历史会话条目。 */
    public record SessionItem(String id, String title, OffsetDateTime updatedAt, String sig) {
    }

    /** 访客历史会话列表：{sessions:[...]}。 */
    public record SessionsResponse(List<SessionItem> sessions) {
    }

    public ResponseEntity<SessionsResponse> listSessions(
            @PathVariable("channelId") String channelId) {
        EmbedChannelEntity ch = EmbedChannelRows.channel(EmbedChannelController.request0());
        long tenantId = EmbedChannelController.currentTenant();
        String visitor = EmbedChannelController.trim(
                EmbedChannelController.request0().getHeader("X-Embed-Visitor"));
        if (!EmbedChannelController.validVisitor(visitor)) {
            return EmbedChannelController.plainError(400, "invalid embed visitor id");
        }
        List<EmbedSessionOps.VisitorSessionRow> rows;
        try {
            rows = ctrl.embedSessionOps.visitorSessions(tenantId,
                    EmbedChannelService.embedSessionDescription(ch.getId()), visitor,
                    MAX_HISTORY_SESSIONS);
        } catch (RuntimeException e) {
            return EmbedChannelController.plainError(500, "failed to load sessions");
        }
        List<SessionItem> sessions = new ArrayList<>(rows.size());
        for (EmbedSessionOps.VisitorSessionRow row : rows) {
            sessions.add(new SessionItem(row.id(), row.title() == null ? "" : row.title(),
                    row.updatedAt(), EmbedTokens.signHandle(ch, row.id())));
        }
        return ResponseEntity.ok(new SessionsResponse(sessions));
    }
}
