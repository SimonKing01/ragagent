package com.ragagent.im.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * IM 出站媒体处理：视频/音频一律剥除，图片按渠道能力降级。
 *
 * <p>平台差异很大（企微/Slack/Telegram 纯文本，飞书卡片要换 image_key），
 * 统一在出站整形里做确定性处理，避免把 {@code ![]()} / {@code [play video]()}
 * 原样丢给用户。</p>
 */
class ImFormatMediaTest {

    @Test
    void stripsVideoAndAudioLinks() {
        String md = "视频在这里：\n\n[play video](https://cdn.example.com/r/abc)\n\n"
                + "音频：[play audio](https://cdn.example.com/r/def)\n\n要点如下。";

        String out = ImFormat.stripAudioVideoLinks(md);

        assertThat(out).doesNotContain("play video").doesNotContain("play audio");
        assertThat(out).contains("视频在这里").contains("要点如下");
    }

    @Test
    void stripsMediaLinksByExtensionEvenWithOtherLabel() {
        String md = "看这个 [点我](https://cdn.example.com/a/b.mp4) 和 [文档](https://x/y.pdf)";

        String out = ImFormat.stripAudioVideoLinks(md);

        assertThat(out).doesNotContain(".mp4");
        assertThat(out).contains("[文档](https://x/y.pdf)");
    }

    @Test
    void stripsVideoTags() {
        String md = "<p><video controls src=\"https://x/a.mp4\"></video></p>后文";

        String out = ImFormat.stripAudioVideoLinks(md);

        assertThat(out).doesNotContain("<video");
        assertThat(out).contains("后文");
    }

    @Test
    void degradesHttpImagesToLinks() {
        String md = "![poster](https://cdn.example.com/r/abc)";

        assertThat(ImFormat.degradeMarkdownImages(md)).isEqualTo("[poster](https://cdn.example.com/r/abc)");
    }

    @Test
    void dropsNonHttpImageTargetsKeepingAltText() {
        // 未配 APP_EXTERNAL_URL 时 Rewriter 保持 local://，此时链接不可点，只留 alt
        String md = "![poster](local://1/exports/cover-1.jpg)";

        assertThat(ImFormat.degradeMarkdownImages(md)).isEqualTo("poster");
    }

    @Test
    void stripsNonHttpImageTargets() {
        // 飞书卡片把 ![](…) 当 image_key 解析，local:// 会让整条更新失败（200570）
        String md = "![封面](local://1/exports/cover-1.jpg)\n\n![远程](https://cdn.example.com/a.jpg)";

        String out = ImFormat.stripNonHttpImages(md);

        assertThat(out).doesNotContain("local://");
        assertThat(out).contains("封面");
        assertThat(out).contains("![远程](https://cdn.example.com/a.jpg)");
    }

    @Test
    void leavesPlainTextAlone() {
        String text = "没有媒体的普通回答。";

        assertThat(ImFormat.degradeMarkdownImages(text)).isEqualTo(text);
        assertThat(ImFormat.stripAudioVideoLinks(text)).isEqualTo(text);
    }
}
