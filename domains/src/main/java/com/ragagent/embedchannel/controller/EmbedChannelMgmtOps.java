package com.ragagent.embedchannel.controller;

import com.ragagent.embedchannel.domain.EmbedChannelEntity;
import com.ragagent.embedchannel.service.EmbedChannelService;
import com.ragagent.embedchannel.service.EmbedChannelService.UpdateCommand;
import com.ragagent.embedchannel.support.EmbedError;
import com.ragagent.platform.security.LogSanitizer;
import com.ragagent.platform.web.ApiResponse;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * embed 渠道管理面协作者（自 {@link EmbedChannelController} 拆出的管理段）：
 * create/list/get/update/delete、
 * rotate-token/preview-session/stats。错误经 {@code writeMgmtError} 统一形态。
 * 持门面回引（ctrl）取 EmbedChannelService 与 sessionRepository；绑定与响应
 * 组构件经门面类名调用。
 */
final class EmbedChannelMgmtOps {

    private final EmbedChannelController ctrl;

    EmbedChannelMgmtOps(EmbedChannelController ctrl) {
        this.ctrl = ctrl;
    }

    // ── 管理面 ──

    /** 创建渠道：201 + 裸对象（键字母序）。 */
    public ResponseEntity<EmbedChannelRows.EmbedChannelRow> create(@PathVariable("id") String agentId,
                                                      @RequestBody(required = false) String rawBody) {
        EmbedChannelController.EmbedChannelRequest req = EmbedChannelController.bind(rawBody);
        try {
            ctrl.service.validateAllowedOrigins(EmbedChannelController.stringList(req.allowedOrigins()));
            if (req.launcherIcon() != null) {
                EmbedChannelService.validateLauncherIcon(req.launcherIcon().trim());
            }
        } catch (EmbedError e) {
            throw EmbedChannelController.writeMgmtError(e);
        }
        EmbedChannelEntity input = new EmbedChannelEntity();
        input.setName(EmbedChannelController.orEmpty(req.name()));
        input.setEnabled(Boolean.TRUE.equals(req.enabled()));
        input.setAllowedOrigins(EmbedChannelController.allowedOriginsColumn(req.allowedOrigins()));
        input.setWelcomeMessage(EmbedChannelController.orEmpty(req.welcomeMessage()));
        input.setRateLimitPerMinute(req.rateLimitPerMinute() == null ? 0 : req.rateLimitPerMinute());
        input.setRateLimitPerDay(req.rateLimitPerDay() == null ? 0 : req.rateLimitPerDay());
        input.setPrimaryColor(EmbedChannelController.orEmpty(req.primaryColor()));
        input.setPageTitle(EmbedChannelController.orEmpty(req.pageTitle()));
        input.setHeaderTitleMode(EmbedChannelController.orEmpty(req.headerTitleMode()));
        input.setShowSuggestedQuestions(!Boolean.FALSE.equals(req.showSuggestedQuestions()));
        input.setShowThinking(Boolean.TRUE.equals(req.showThinking()));
        input.setWidgetPosition(EmbedChannelController.orEmpty(req.widgetPosition()));
        input.setAllowWebSearch(Boolean.TRUE.equals(req.allowWebSearch()));
        input.setAllowFileUpload(Boolean.TRUE.equals(req.allowFileUpload()));
        input.setDefaultLocale(EmbedChannelService.normalizeDefaultLocale(EmbedChannelController.orEmpty(req.defaultLocale())));
        input.setLauncherIcon(EmbedChannelController.orEmpty(req.launcherIcon()));
        try {
            EmbedChannelEntity ch = ctrl.service.create(EmbedChannelController.currentTenant(), LogSanitizer.sanitize(agentId), input);
            // 201 + 裸对象
            return ResponseEntity.status(201).body(EmbedChannelRows.row(ch, true));
        } catch (EmbedError e) {
            throw EmbedChannelController.writeMgmtError(e);
        }
    }

    /** 渠道列表（按 agent；列表行不含 publish token）。 */
    public ResponseEntity<List<EmbedChannelRows.EmbedChannelRow>> listByAgent(@PathVariable("id") String agentId) {
        try {
            List<EmbedChannelEntity> rows =
                    ctrl.service.listByAgent(EmbedChannelController.currentTenant(), LogSanitizer.sanitize(agentId));
            return ResponseEntity.ok(EmbedChannelRows.rows(rows));
        } catch (EmbedError e) {
            throw EmbedChannelController.writeMgmtError(e);
        }
    }

    /** 全租户渠道列表（跨 agent，publish token 永不出现在列表里）。 */
    public ResponseEntity<List<EmbedChannelRows.EmbedChannelRow>> listAll() {
        try {
            return ResponseEntity.ok(EmbedChannelRows.rows(ctrl.service.listByTenant(EmbedChannelController.currentTenant())));
        } catch (EmbedError e) {
            throw EmbedChannelController.writeMgmtError(e);
        }
    }

    /** 管理详情（**含** publish token）。 */
    public ResponseEntity<EmbedChannelRows.EmbedChannelRow> get(@PathVariable("channelId") String channelId) {
        try {
            EmbedChannelEntity ch = ctrl.service.getOwnedChannel(EmbedChannelController.currentTenant(), EmbedChannelController.trim(channelId));
            return ResponseEntity.ok(EmbedChannelRows.row(ch, true));
        } catch (EmbedError e) {
            throw EmbedChannelController.writeMgmtError(e);
        }
    }

    /** 更新渠道：200 + 行视图（不含 publish token）。 */
    public ResponseEntity<EmbedChannelRows.EmbedChannelRow> update(@PathVariable("channelId") String channelId,
                                                      @RequestBody(required = false) String rawBody) {
        EmbedChannelController.EmbedChannelRequest req = EmbedChannelController.bind(rawBody);
        try {
            if (req.allowedOrigins() != null) {
                ctrl.service.validateAllowedOrigins(EmbedChannelController.stringList(req.allowedOrigins()));
            }
            if (req.webhookUrl() != null) {
                EmbedChannelService.validateWebhookUrl(req.webhookUrl());
            }
            if (req.launcherIcon() != null) {
                EmbedChannelService.validateLauncherIcon(req.launcherIcon().trim());
            }
        } catch (EmbedError e) {
            throw EmbedChannelController.writeMgmtError(e);
        }
        UpdateCommand cmd = new UpdateCommand();
        cmd.name = EmbedChannelController.orEmpty(req.name());
        cmd.welcomeMessage = req.welcomeMessage();
        cmd.primaryColor = req.primaryColor();
        cmd.pageTitle = req.pageTitle();
        cmd.headerTitleMode = req.headerTitleMode();
        cmd.widgetPosition = req.widgetPosition();
        cmd.agentId = req.agentId();
        cmd.enabled = req.enabled();
        cmd.showSuggested = req.showSuggestedQuestions();
        cmd.showThinking = req.showThinking();
        cmd.allowWebSearch = req.allowWebSearch();
        cmd.allowFileUpload = req.allowFileUpload();
        cmd.defaultLocale = req.defaultLocale();
        cmd.webhookUrl = req.webhookUrl();
        cmd.webhookSecret = req.webhookSecret();
        cmd.launcherIcon = req.launcherIcon();
        cmd.rateLimitPerMinute = req.rateLimitPerMinute() == null ? 0 : req.rateLimitPerMinute();
        cmd.rateLimitPerDay = req.rateLimitPerDay() == null ? 0 : req.rateLimitPerDay();
        // ⚠️ golden 钉死：allowed_origins 缺键 → 列值整列覆写为 JSON "null"（allowlist 清空）
        cmd.allowedOriginsColumn = req.allowedOrigins() == null
                ? "null" : req.allowedOrigins().toString();
        try {
            EmbedChannelEntity ch = ctrl.service.update(EmbedChannelController.currentTenant(), EmbedChannelController.trim(channelId), cmd);
            return ResponseEntity.ok(EmbedChannelRows.row(ch, false));
        } catch (EmbedError e) {
            throw EmbedChannelController.writeMgmtError(e);
        }
    }

    /** 删除渠道 → **204**（同步完成的删除无响应体，不回 {"success":true}）。 */
    public ApiResponse<Void> delete(@PathVariable("channelId") String channelId) {
        try {
            ctrl.service.delete(EmbedChannelController.currentTenant(), EmbedChannelController.trim(channelId));
        } catch (EmbedError e) {
            throw EmbedChannelController.writeMgmtError(e);
        }
        return ApiResponse.ok();   // 204 退役（空体与「外壳恒存在」冲突）
    }

    /** 轮换发布令牌：200 + 含新 token 的行。 */
    public ResponseEntity<EmbedChannelRows.EmbedChannelRow> rotate(@PathVariable("channelId") String channelId) {
        try {
            var result = ctrl.service.rotateToken(EmbedChannelController.currentTenant(), EmbedChannelController.trim(channelId));
            return ResponseEntity.ok(EmbedChannelRows.row(result.channel(), result.token()));
        } catch (EmbedError e) {
            throw EmbedChannelController.writeMgmtError(e);
        }
    }

    /** 预览会话签发：禁用渠道 → 403 "embed channel is disabled"（专用分支）。 */
    /** 预览会话：{expiresIn, sessionToken}。 */
    public record PreviewResponse(int expiresIn, String sessionToken) {
    }

    public ResponseEntity<PreviewResponse> preview(@PathVariable("channelId") String channelId) {
        EmbedChannelService.IssueResult result;
        try {
            result = ctrl.service.issuePreviewSession(EmbedChannelController.currentTenant(), EmbedChannelController.trim(channelId));
        } catch (EmbedError e) {
            if (e.kind == EmbedError.Kind.CHANNEL_DISABLED) {
                return EmbedChannelController.plainError(403, "embed channel is disabled");
            }
            throw EmbedChannelController.writeMgmtError(e);
        }
        return ResponseEntity.ok(new PreviewResponse(result.expiresIn(), result.token()));
    }

    /** 渠道统计：{sessionCount:N}。 */
    public record StatsResponse(long sessionCount) {
    }

    public ResponseEntity<StatsResponse> stats(@PathVariable("channelId") String channelId) {
        try {
            ctrl.service.getOwnedChannel(EmbedChannelController.currentTenant(), EmbedChannelController.trim(channelId));
        } catch (EmbedError e) {
            throw EmbedChannelController.writeMgmtError(e);
        }
        long total = ctrl.embedSessionOps.countSessions(EmbedChannelController.currentTenant(), EmbedChannelController.trim(channelId));
        return ResponseEntity.ok(new StatsResponse(total));
    }
}
