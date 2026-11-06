package com.ragagent.knowledge.support;

import java.util.Locale;
import java.util.Set;

/**
 * 音视频扩展名口径：知识库入库时这两类不走 DocReader，而是走
 * {@link com.ragagent.knowledge.task.KnowledgeMediaTranscriber}（抽音轨 → ASR 转写）。
 */
public final class MediaFileTypes {

    /** 视频：抽音轨转写；无音轨也要能入库（封面 + 标题 + 播放地址）。 */
    private static final Set<String> VIDEO = Set.of("mp4", "mov", "m4v", "webm", "mkv", "avi");

    /** 音频：直接送 ASR。 */
    private static final Set<String> AUDIO = Set.of("mp3", "wav", "m4a", "flac", "ogg", "aac");

    private MediaFileTypes() {
    }

    public static boolean isVideo(String fileType) {
        return fileType != null && VIDEO.contains(normalize(fileType));
    }

    public static boolean isAudio(String fileType) {
        return fileType != null && AUDIO.contains(normalize(fileType));
    }

    public static boolean isMedia(String fileType) {
        return isVideo(fileType) || isAudio(fileType);
    }

    private static String normalize(String fileType) {
        return fileType.trim().toLowerCase(Locale.ROOT);
    }
}
