package com.ragagent.knowledge.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.ragagent.contract.model.AsrTranscriber;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.platform.web.JsonMappers;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 音视频正文的时间戳与分段拼装（纯函数，不依赖 ffmpeg / ASR 服务）。
 */
class KnowledgeMediaTranscriberTest {

    @Test
    void timestampPadsMinutesAndSeconds() {
        assertThat(KnowledgeMediaTranscriber.timestamp(0)).isEqualTo("00:00");
        assertThat(KnowledgeMediaTranscriber.timestamp(65.4)).isEqualTo("01:05");
        assertThat(KnowledgeMediaTranscriber.timestamp(599)).isEqualTo("09:59");
        assertThat(KnowledgeMediaTranscriber.timestamp(3725)).isEqualTo("01:02:05");
    }

    @Test
    void segmentsBecomeTimestampedLines() {
        var result = new AsrTranscriber.TranscriptionResult("全文", List.of(
                new AsrTranscriber.TranscriptionResult.Segment(0d, 2d, " 第一句 "),
                new AsrTranscriber.TranscriptionResult.Segment(62d, 64d, "第二句"),
                new AsrTranscriber.TranscriptionResult.Segment(66d, 67d, "   ")));

        assertThat(KnowledgeMediaTranscriber.formatSegments(result))
                .isEqualTo("[00:00] 第一句\n[01:02] 第二句");
    }

    @Test
    void perDocumentAsrOverrideWins() throws Exception {
        Knowledge k = new Knowledge();
        k.setMetadata(JsonMappers.lenient().readTree("""
                {"processOverrides":{"asrConfig":{"enabled":true,"modelId":"m-asr","language":"zh"}}}
                """));

        KnowledgeMediaTranscriber.AsrOverride override =
                KnowledgeMediaTranscriber.overrideAsr(k);
        assertThat(override).isNotNull();
        assertThat(override.modelId()).isEqualTo("m-asr");
        assertThat(override.language()).isEqualTo("zh");
    }

    @Test
    void perDocumentAsrOverrideIgnoredWhenDisabledOrEmpty() throws Exception {
        Knowledge disabled = new Knowledge();
        disabled.setMetadata(JsonMappers.lenient()
                .readTree("{\"processOverrides\":{\"asrConfig\":{\"enabled\":false,\"modelId\":\"m\"}}}"));
        assertThat(KnowledgeMediaTranscriber.overrideAsr(disabled)).isNull();

        Knowledge emptyModel = new Knowledge();
        emptyModel.setMetadata(JsonMappers.lenient()
                .readTree("{\"processOverrides\":{\"asrConfig\":{\"enabled\":true,\"modelId\":\"\"}}}"));
        assertThat(KnowledgeMediaTranscriber.overrideAsr(emptyModel)).isNull();

        assertThat(KnowledgeMediaTranscriber.overrideAsr(new Knowledge())).isNull();
    }

    @Test
    void mediaFileNameDropsSpacesAndBrackets() {
        Knowledge k = new Knowledge();
        k.setId("k-1");
        k.setFileName("aion i60蓝牙无钥匙进入教学 (1).mp4");

        String name = KnowledgeMediaTranscriber.safeName(k);

        // 空格与括号会让 markdown 链接解析失败，必须清洗掉；扩展名保留
        assertThat(name).isEqualTo("aion-i60蓝牙无钥匙进入教学-1.mp4");
    }

    @Test
    void linkTargetWrapsUnsafeUrls() {
        assertThat(KnowledgeMediaTranscriber.linkTarget("local://1/exports/a-b.mp4"))
                .isEqualTo("local://1/exports/a-b.mp4");
        assertThat(KnowledgeMediaTranscriber.linkTarget("local://1/exports/a b.mp4"))
                .isEqualTo("<local://1/exports/a b.mp4>");
        assertThat(KnowledgeMediaTranscriber.linkTarget("")).isEmpty();
    }

    @Test
    void markdownCarriesCoverPlayLinkAndTranscript() {
        String md = KnowledgeMediaTranscriber.buildMarkdown(
                "aion i60 教学", "aion i60 教学.mp4", true,
                "local://1/exports/cover-1.jpg", "local://1/exports/aion.mp4",
                "[00:03] 你好世界", 192);

        assertThat(md).startsWith("# aion i60 教学\n");
        // 标签一律英文（多语言租户，模型复述时不带中文标签）
        // 封面不再单独占一行，改挂到播放链接的 title 上（前端拿去当 poster）
        assertThat(md).doesNotContain("![poster]");
        assertThat(md).contains(
                "[play video](local://1/exports/aion.mp4 \"poster=local://1/exports/cover-1.jpg\")");
        assertThat(md).contains("- file: aion i60 教学.mp4");
        assertThat(md).contains("- duration: 03:12");
        assertThat(md).contains("- poster: local://1/exports/cover-1.jpg (internal ref)");
        assertThat(md).contains("- media: local://1/exports/aion.mp4 (internal ref)");
        assertThat(md).contains("## transcript\n\n[00:03] 你好世界");
    }

    @Test
    void markdownWithoutAudioKeepsMediaInfo() {
        String md = KnowledgeMediaTranscriber.buildMarkdown(
                "无声视频", "silent.mp4", true, "", "local://1/exports/silent.mp4",
                "This video has no audio track; no transcript is available.", 5);

        assertThat(md).doesNotContain("![");          // 没有封面时不渲染图片行
        assertThat(md).contains("[play video](local://1/exports/silent.mp4)");
        assertThat(md).contains("no audio track");
    }

    @Test
    void blankSegmentsFallBackToPlainText() {
        var noSegments = new AsrTranscriber.TranscriptionResult(" 整段文本 ", List.of());
        assertThat(KnowledgeMediaTranscriber.formatSegments(noSegments)).isEqualTo("整段文本");

        var nullResult = new AsrTranscriber.TranscriptionResult(null, null);
        assertThat(KnowledgeMediaTranscriber.formatSegments(nullResult)).isEmpty();
        assertThat(KnowledgeMediaTranscriber.formatSegments(null)).isEmpty();
    }
}
