package com.ragagent.knowledge.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** OCR 配置默认值：全局并发 2、最长边 1600。 */
class KnowledgeOcrPropertiesTest {

    private static KnowledgeOcrProperties props(Integer maxPages, Integer timeout,
            Integer concurrency, Integer maxImageSide) {
        return new KnowledgeOcrProperties("http://ocr:18092", maxPages, timeout, concurrency, maxImageSide);
    }

    @Test
    void defaultsToTwoWayAnd1600Pixels() {
        KnowledgeOcrProperties p = props(null, null, null, null);

        assertThat(p.enabled()).isTrue();
        assertThat(p.maxPagesOrDefault()).isEqualTo(30);
        assertThat(p.timeoutSecondsOrDefault()).isEqualTo(60);
        assertThat(p.concurrencyOrDefault()).isEqualTo(2);
        assertThat(p.maxImageSideOrDefault()).isEqualTo(1600);
    }

    @Test
    void respectsExplicitValues() {
        KnowledgeOcrProperties p = props(10, 30, 2, 2000);

        assertThat(p.maxPagesOrDefault()).isEqualTo(10);
        assertThat(p.timeoutSecondsOrDefault()).isEqualTo(30);
        assertThat(p.concurrencyOrDefault()).isEqualTo(2);
        assertThat(p.maxImageSideOrDefault()).isEqualTo(2000);
    }

    @Test
    void fallsBackWhenValuesAreNonsensical() {
        KnowledgeOcrProperties p = props(0, 0, 0, 100);

        assertThat(p.maxPagesOrDefault()).isEqualTo(30);
        assertThat(p.timeoutSecondsOrDefault()).isEqualTo(60);
        assertThat(p.concurrencyOrDefault()).isEqualTo(2);
        // 太小会把文字缩没：低于 320 一律回落默认
        assertThat(p.maxImageSideOrDefault()).isEqualTo(1600);
    }

    @Test
    void blankEndpointDisablesOcr() {
        assertThat(new KnowledgeOcrProperties("  ", null, null, null, null).enabled()).isFalse();
        assertThat(new KnowledgeOcrProperties(null, null, null, null, null).enabled()).isFalse();
    }

    @Test
    void baseUrlStripsTrailingSlash() {
        assertThat(new KnowledgeOcrProperties("http://ocr:18092///", null, null, null, null).baseUrl())
                .isEqualTo("http://ocr:18092");
    }
}
