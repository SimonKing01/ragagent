package com.ragagent.embedchannel.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.ragagent.contract.error.BizException;
import com.ragagent.embedchannel.controller.filter.EmbedAuthFilter;
import com.ragagent.embedchannel.domain.EmbedChannelEntity;
import com.ragagent.embedchannel.service.EmbedChannelService;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** Embed 渠道行与请求上下文投影：控制器只做路由，不持有实体。 */
public final class EmbedChannelRows {

    private EmbedChannelRows() {
    }

    /**
     * 渠道行视图（键名＝实体字段名、无 {@code {data,success}} 信封）。
     *
     * <p>唯一的**条件键**是 {@code publishToken}：管理详情/创建/轮换带它、列表行不带——
     * 这是**授权边界**（列表里泄漏 publish token 等于把渠道会话签发权发给任何读列表的人），
     * 不是"有时出现有时消失"的数据条件键，故刻意保留。</p>
     *
     * <p>键序显式钉住：{@code publishToken} 上的 {@code NON_NULL} 会让 Jackson 2 把它提前，
     * 不能依赖声明序（契约金片按文本锁）。</p>
     */
    @JsonPropertyOrder({"agentId", "allowFileUpload", "allowWebSearch", "allowedOrigins", "createdAt",
        "defaultLocale", "enabled", "hasWebhookSecret", "headerTitleMode", "id", "launcherIcon",
        "name", "pageTitle", "primaryColor", "publishToken", "rateLimitPerDay", "rateLimitPerMinute",
        "showSuggestedQuestions", "showThinking", "tenantId", "updatedAt", "webhookUrl",
        "welcomeMessage", "widgetPosition"})
    public record EmbedChannelRow(
            String agentId,
            boolean allowFileUpload,
            boolean allowWebSearch,
            List<String> allowedOrigins,
            OffsetDateTime createdAt,
            String defaultLocale,
            boolean enabled,
            boolean hasWebhookSecret,
            String headerTitleMode,
            String id,
            String launcherIcon,
            String name,
            String pageTitle,
            String primaryColor,
            String publishToken,
            int rateLimitPerDay,
            int rateLimitPerMinute,
            boolean showSuggestedQuestions,
            boolean showThinking,
            Long tenantId,
            OffsetDateTime updatedAt,
            String webhookUrl,
            String welcomeMessage,
            String widgetPosition) {

        /** 空 token 整键省略（null 与空串同义）。 */
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        public String publishToken() {
            return publishToken;
        }
    }

    /** 渠道行视图：publishToken 非空才带键。 */
    public static EmbedChannelRow row(EmbedChannelEntity ch, boolean withPublishToken) {
        return row(ch, ch.getPublishToken() == null ? "" : ch.getPublishToken(), withPublishToken);
    }

    public static EmbedChannelRow row(EmbedChannelEntity ch, String token) {
        return row(ch, token, true);
    }

    public static EmbedChannelRow row(EmbedChannelEntity ch, String token, boolean includeToken) {
        return new EmbedChannelRow(
                ch.getAgentId(),
                ch.isAllowFileUpload(),
                ch.isAllowWebSearch(),
                originsJson(EmbedChannelService.allowedOriginsList(ch)),
                ch.getCreatedAt(),
                ch.getDefaultLocale(),
                ch.isEnabled(),
                ch.getWebhookSecret() != null && !ch.getWebhookSecret().isEmpty(),
                EmbedChannelService.normalizeHeaderTitleMode(ch.getHeaderTitleMode()),
                ch.getId(),
                ch.getLauncherIcon(),
                ch.getName(),
                ch.getPageTitle(),
                ch.getPrimaryColor(),
                includeToken && token != null && !token.isEmpty() ? token : null,
                ch.getRateLimitPerDay(),
                ch.getRateLimitPerMinute(),
                ch.isShowSuggestedQuestions(),
                ch.isShowThinking(),
                ch.getTenantId(),
                ch.getUpdatedAt(),
                ch.getWebhookUrl(),
                ch.getWelcomeMessage(),
                ch.getWidgetPosition());
    }

    /** allowed_origins 列值：空 → JSON null，非空 → 数组（列表行用）。 */
    private static List<String> originsJson(List<String> origins) {
        return origins.isEmpty() ? null : origins;
    }

    /** 列表行（裸数组；不带 publish token）。 */
    public static List<EmbedChannelRow> rows(List<EmbedChannelEntity> list) {
        List<EmbedChannelRow> data = new ArrayList<>();
        for (EmbedChannelEntity ch : list) {
            data.add(row(ch, "", false));
        }
        return data;
    }


    public static EmbedChannelEntity channel(jakarta.servlet.http.HttpServletRequest request) {
        Object ch = request.getAttribute(EmbedAuthFilter.CHANNEL_ATTRIBUTE);
        if (!(ch instanceof EmbedChannelEntity entity)) {
            throw BizException.unauthorized("unauthorized");
        }
        return entity;
    }

    /** 取当前 HTTP 请求（MockMvc/Servlet 通用）。 */
    static jakarta.servlet.http.HttpServletRequest request0() {
        var attrs = org.springframework.web.context.request.RequestContextHolder
                .currentRequestAttributes();
        return ((org.springframework.web.context.request.ServletRequestAttributes) attrs).getRequest();
    }


}
