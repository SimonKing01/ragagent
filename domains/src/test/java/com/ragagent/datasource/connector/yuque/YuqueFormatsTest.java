package com.ragagent.datasource.connector.yuque;

import static org.assertj.core.api.Assertions.assertThat;

import com.ragagent.platform.web.ZeroTimeSerializer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 语雀纯函数的对等测试（{@code baseURL} / {@code buildDocURL} /
 * {@code parseContentUpdatedAt} / {@code sanitizeFileName} / {@code redactToken}）。
 */
class YuqueFormatsTest {

    // ── GetBaseURL ───────────────────────────────────────────────────────

    /** baseURL 归一的期望值全表。 */
    @ParameterizedTest
    @CsvSource({
            "'',                        https://www.yuque.com",
            "'   ',                     https://www.yuque.com",
            "https://x.yuque.com/,      https://x.yuque.com",
            "company.yuque.com,         https://company.yuque.com",
            "http://x.yuque.com//,      http://x.yuque.com",
            "https://www.yuque.com,     https://www.yuque.com",
    })
    void getBaseUrlMatchesGo(String in, String want) {
        YuqueConfig cfg = new YuqueConfig();
        cfg.setBaseUrl(in);
        assertThat(cfg.baseURL()).isEqualTo(want);
    }

    /**
     * {@code baseURL()} 是**方法**：不得成为 JSON 属性——键集＝字段（key 就是 {@code baseUrl}）。
     */
    @Test
    void getBaseUrlIsNotAJsonProperty() throws Exception {
        YuqueConfig cfg = new YuqueConfig();
        cfg.setApiToken("tok");
        cfg.setBaseUrl("https://company.yuque.com");
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(cfg);
        // 键名＝字段名 ⇒ 逐字节钉住；baseURL() 不产生额外属性（原来它被误当 camel 键的"多余物"）
        assertThat(json).isEqualTo("{\"apiToken\":\"tok\",\"baseUrl\":\"https://company.yuque.com\"}");
        assertThat(json).doesNotContain("baseURL");
    }

    // ── buildDocURL ──────────────────────────────────────────────────────

    /** namespace 为空时只回基地址。 */
    @ParameterizedTest
    @CsvSource({
            "https://www.yuque.com, alice/demo, hello,   https://www.yuque.com/alice/demo/hello",
            "'https://x.yuque.com', team-a/ab,  slug-1,  https://x.yuque.com/team-a/ab/slug-1",
    })
    void buildDocUrlMatchesGo(String base, String namespace, String slug, String want) {
        assertThat(YuqueFormats.buildDocURL(base, namespace, slug)).isEqualTo(want);
    }

    @Test
    void buildDocUrlFallsBackToBaseWhenNamespaceEmpty() {
        assertThat(YuqueFormats.buildDocURL("https://www.yuque.com", "", "hello"))
                .isEqualTo("https://www.yuque.com");
    }

    // ── parseContentUpdatedAt ────────────────────────────────────────────

    /** 解析失败/空一律回**零值时间**（不是 null、不抛错）。 */
    @Test
    void parseContentUpdatedAtMatchesGo() {
        assertThat(ZeroTimeSerializer.isZeroValue(YuqueFormats.parseContentUpdatedAt(""))).isTrue();
        assertThat(ZeroTimeSerializer.isZeroValue(YuqueFormats.parseContentUpdatedAt(null))).isTrue();
        assertThat(ZeroTimeSerializer.isZeroValue(YuqueFormats.parseContentUpdatedAt("not-a-time"))).isTrue();
        // "只有日期"的串不算合法时间戳
        assertThat(ZeroTimeSerializer.isZeroValue(YuqueFormats.parseContentUpdatedAt("2026-04-20"))).isTrue();

        assertThat(YuqueFormats.parseContentUpdatedAt("2026-04-20T10:00:00Z").toInstant())
                .isEqualTo(Instant.parse("2026-04-20T10:00:00Z"));

        OffsetDateTime withOffset = YuqueFormats.parseContentUpdatedAt("2026-04-20T10:00:00+08:00");
        assertThat(withOffset.toInstant()).isEqualTo(Instant.parse("2026-04-20T02:00:00Z"));
        assertThat(withOffset.getOffset()).isEqualTo(ZoneOffset.ofHours(8));

        assertThat(YuqueFormats.parseContentUpdatedAt("2026-04-20T10:00:00.123Z").toInstant())
                .isEqualTo(Instant.parse("2026-04-20T10:00:00.123Z"));
    }

    // ── sanitizeFileName ─────────────────────────────────────────────────

    @Test
    void sanitizeFileNameMatchesGo() {
        assertThat(YuqueFormats.sanitizeFileName("")).isEqualTo("untitled");
        assertThat(YuqueFormats.sanitizeFileName(null)).isEqualTo("untitled");
        assertThat(YuqueFormats.sanitizeFileName("Hello")).isEqualTo("Hello");
        assertThat(YuqueFormats.sanitizeFileName("a/b\\c:d*e?f\"g<h>i|j"))
                .isEqualTo("a_b_c_d_e_f_g_h_i_j");
    }

    /**
     * 长中文标题按字节截断必须落在码点边界上，否则下游的
     * UTF-8 校验会以"文件名包含非法字符"拒绝。
     */
    @Test
    void sanitizeFileNameTruncatesAtRuneBoundary() {
        String longName = "测试".repeat(100); // 600 字节
        String got = YuqueFormats.sanitizeFileName(longName);

        assertThat(got.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(200);
        assertThat(got.getBytes(StandardCharsets.UTF_8).length).isEqualTo(198);
        assertThat(got).isNotEmpty();
        assertThat(longName).startsWith(got);
    }

    // ── redactToken ──────────────────────────────────────────────────────

    /** 令牌脱敏规则。 */
    @ParameterizedTest
    @CsvSource({
            "short,            ***",
            "abcdef1234567890, abcdef...7890",
            "abcdefghijkl,     abcdef...ijkl",
    })
    void redactTokenMatchesGo(String in, String want) {
        assertThat(YuqueClient.redactToken(in)).isEqualTo(want);
    }
}
