package com.ragagent.knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 入库图片 OCR 服务配置。
 *
 * <p>{@code endpoint} 为空即关闭（默认关闭，行为与未接入时一致）；{@code maxPages}
 * 限制单篇文档送入 OCR 的图片张数——扫描件每页一张图，不设上限会随页数线性放大耗时。
 * {@code concurrency} 是<b>全局</b>并发（跨文档），{@code maxImageSide} 在送识别前
 * 把大图等比缩小，控制服务端内存峰值。</p>
 */
@ConfigurationProperties(prefix = "knowledge.ocr")
public record KnowledgeOcrProperties(String endpoint, Integer maxPages,
                                     Integer timeoutSeconds, Integer concurrency,
                                     Integer maxImageSide) {

    private static final int DEFAULT_MAX_PAGES = 30;
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;
    /** 全局并发：整个进程同时最多多少张图在送 OCR（跨文档共享）。 */
    private static final int DEFAULT_CONCURRENCY = 2;
    /** 送 OCR 前的最长边上限；超过则等比缩小（降内存、降耗时，文字仍清晰）。 */
    private static final int DEFAULT_MAX_IMAGE_SIDE = 1600;

    /** 是否配置了 OCR 服务地址。 */
    public boolean enabled() {
        return endpoint != null && !endpoint.isBlank();
    }

    /** 服务基地址（去尾部斜杠）。 */
    public String baseUrl() {
        String value = endpoint == null ? "" : endpoint.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    public int maxPagesOrDefault() {
        return maxPages == null || maxPages <= 0 ? DEFAULT_MAX_PAGES : maxPages;
    }

    public int timeoutSecondsOrDefault() {
        return timeoutSeconds == null || timeoutSeconds <= 0
                ? DEFAULT_TIMEOUT_SECONDS : timeoutSeconds;
    }

    public int concurrencyOrDefault() {
        return concurrency == null || concurrency <= 0 ? DEFAULT_CONCURRENCY : concurrency;
    }

    public int maxImageSideOrDefault() {
        return maxImageSide == null || maxImageSide < 320 ? DEFAULT_MAX_IMAGE_SIDE : maxImageSide;
    }
}
