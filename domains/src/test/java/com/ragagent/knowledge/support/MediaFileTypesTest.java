package com.ragagent.knowledge.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 音视频扩展名判定：决定走 DocReader 还是本地抽音轨 + ASR。 */
class MediaFileTypesTest {

    @Test
    void videoExtensionsAreRecognised() {
        assertThat(MediaFileTypes.isVideo("mp4")).isTrue();
        assertThat(MediaFileTypes.isVideo("MOV")).isTrue();
        assertThat(MediaFileTypes.isVideo("webm")).isTrue();
        assertThat(MediaFileTypes.isVideo("pdf")).isFalse();
    }

    @Test
    void audioExtensionsAreRecognised() {
        assertThat(MediaFileTypes.isAudio("mp3")).isTrue();
        assertThat(MediaFileTypes.isAudio("M4A")).isTrue();
        assertThat(MediaFileTypes.isAudio("mp4")).isFalse();
    }

    @Test
    void mediaCoversBothAndToleratesNull() {
        assertThat(MediaFileTypes.isMedia("mp4")).isTrue();
        assertThat(MediaFileTypes.isMedia("wav")).isTrue();
        assertThat(MediaFileTypes.isMedia("docx")).isFalse();
        assertThat(MediaFileTypes.isMedia(null)).isFalse();
    }
}
