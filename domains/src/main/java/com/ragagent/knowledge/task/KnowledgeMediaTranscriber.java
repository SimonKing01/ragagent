package com.ragagent.knowledge.task;

import com.ragagent.contract.model.AsrTranscriber;
import com.ragagent.contract.model.ModelCatalogPort;
import com.ragagent.contract.model.ModelCatalogPort.ModelRowView;
import com.ragagent.contract.model.ModelRuntime;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.domain.KnowledgeBaseAsrConfig;
import com.ragagent.knowledge.repository.KnowledgeBaseRepository;
import com.ragagent.knowledge.service.image.ExtractedImageStore;
import com.ragagent.knowledge.support.FfmpegMedia;
import com.ragagent.knowledge.support.MediaFileTypes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 音视频入库：抽音轨 → ASR 转写 → 带媒体信息与时间戳的正文。
 *
 * <p>DocReader 不支持视频（会直接回 {@code Unsupported file type: mp4}），音频能力也不可控，
 * 因此这两类在 {@link KnowledgeTextExtractor} 走独立分支，正文由本类生成，后续分块/向量化
 * 与普通文档完全一致。</p>
 *
 * <p><b>媒体文件与封面都落 exports 并登记绑定</b>（与解析抽取图同一套流程）：这样正文里的
 * {@code local://} 引用能通过知识库文件代理取到，前端可像图片一样渲染封面和播放器。</p>
 *
 * <p><b>无音轨视频</b>不判失败：仍然入库，语音文本处写明原因。</p>
 */
public class KnowledgeMediaTranscriber {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeMediaTranscriber.class);

    private static final String KIND_IMAGE = "image";
    private static final String KIND_FILE = "file";

    private final ExtractedImageStore imageStore;
    private final ModelCatalogPort modelCatalog;
    private final ModelRuntime modelRuntime;
    private final AsrTranscriber asrTranscriber;
    private final KnowledgeBaseRepository kbRepository;
    private final FfmpegMedia media;
    private final int maxDurationSeconds;

    public KnowledgeMediaTranscriber(ExtractedImageStore imageStore,
            ModelCatalogPort modelCatalog,
            ModelRuntime modelRuntime,
            AsrTranscriber asrTranscriber,
            KnowledgeBaseRepository kbRepository,
            FfmpegMedia media,
            int maxDurationSeconds) {
        this.imageStore = imageStore;
        this.modelCatalog = modelCatalog;
        this.modelRuntime = modelRuntime;
        this.asrTranscriber = asrTranscriber;
        this.kbRepository = kbRepository;
        this.media = media;
        this.maxDurationSeconds = maxDurationSeconds;
    }

    /** 该文档是否由本类处理（音视频）。 */
    public boolean supports(Knowledge k) {
        return k != null && MediaFileTypes.isMedia(k.getFileType());
    }

    /**
     * 生成正文 markdown。超时长、无 ASR 模型属**硬失败**（抛出后由流水线记为解析失败），
     * 无音轨视频则降级为「媒体信息 + 说明」。
     */
    public String render(Knowledge k, byte[] content) throws Exception {
        Path mediaFile = FfmpegMedia.writeTemp(content, k.getFileType());
        try {
            FfmpegMedia.Probe probe = media.probe(mediaFile);
            if (probe.durationSeconds() > maxDurationSeconds) {
                throw new IllegalStateException("音视频时长超过上限（"
                        + (int) probe.durationSeconds() + "s > " + maxDurationSeconds + "s）");
            }
            String title = displayTitle(k);
            boolean video = MediaFileTypes.isVideo(k.getFileType());
            String coverRef = video ? storeCover(k, mediaFile) : "";
            String mediaRef = storeMedia(k, content);
            String transcript = probe.hasAudio()
                    ? transcribe(k, mediaFile)
                    : (video
                            ? "This video has no audio track; no transcript is available."
                            : "This audio file has no recognizable speech track.");
            return buildMarkdown(title, k.getFileName(), video, coverRef, mediaRef,
                    transcript, probe.durationSeconds());
        } finally {
            try {
                Files.deleteIfExists(mediaFile);
            } catch (Exception ignored) {
                // 临时文件清理失败不影响解析结果
            }
        }
    }

    // ── 正文拼装 ────────────────────────────────────────────────────────

    /**
     * 正文结构（媒体信息都放在开头，保证**首个分块**就能看到封面/地址；后续分块只有转写文本）。
     */
    static String buildMarkdown(String title, String fileName, boolean video, String coverRef,
            String mediaRef, String transcript, double durationSeconds) {
        // 正文一律英文标签：产品面向多语言租户，模型复述时不会把中文标签带进英/日/韩回答
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title).append("\n\n");
        if (!mediaRef.isEmpty()) {
            // 封面不再单独占一行：作为播放链接的 title 一起下发（前端拿去当 poster）
            sb.append("[play ").append(video ? "video" : "audio").append("](")
                    .append(linkTarget(mediaRef));
            if (!coverRef.isEmpty()) {
                sb.append(" \"poster=").append(coverRef).append('"');
            }
            sb.append(")\n\n");
        }
        sb.append("- file: ").append(fileName == null || fileName.isBlank() ? title : fileName)
                .append('\n');
        sb.append("- duration: ").append(timestamp(durationSeconds)).append('\n');
        if (!coverRef.isEmpty()) {
            sb.append("- poster: ").append(coverRef).append(" (internal ref)\n");
        }
        if (!mediaRef.isEmpty()) {
            sb.append("- media: ").append(mediaRef).append(" (internal ref)\n");
        }
        sb.append("\n## transcript\n\n").append(transcript).append('\n');
        return sb.toString();
    }

    /** markdown 链接目标：含空格/括号时用尖括号包裹，否则解析不出链接。 */
    static String linkTarget(String ref) {
        if (ref == null || ref.isEmpty()) {
            return "";
        }
        return ref.matches(".*[\\s()<>].*") ? "<" + ref + ">" : ref;
    }

    /** 转写结果按 segment 输出 `[mm:ss] 文本`，便于回答里定位到具体位置。 */
    private String transcribe(Knowledge k, Path mediaFile) {
        String modelId = resolveAsrModelId(k);
        if (modelId.isEmpty()) {
            throw new IllegalStateException("未配置语音识别（ASR）模型，无法转写音视频");
        }
        byte[] audio = this.media.extractAudioMp3(mediaFile);
        ModelRowView model = modelRuntime.getAsrModel(modelId);
        var params = model.parameters();
        AsrTranscriber.AsrConfig config = new AsrTranscriber.AsrConfig(
                params == null ? "" : params.getBaseUrl(),
                model.name(),
                params == null ? "" : params.getApiKey(),
                model.id(),
                resolveLanguage(k),
                params == null ? null : params.getCustomHeaders());
        AsrTranscriber.TranscriptionResult result =
                asrTranscriber.transcribe(config, audio, "audio.mp3");
        return formatSegments(result);
    }

    static String formatSegments(AsrTranscriber.TranscriptionResult result) {
        if (result == null) {
            return "";
        }
        List<AsrTranscriber.TranscriptionResult.Segment> segments = result.segments();
        if (segments == null || segments.isEmpty()) {
            return result.text() == null ? "" : result.text().trim();
        }
        StringBuilder sb = new StringBuilder();
        for (AsrTranscriber.TranscriptionResult.Segment segment : segments) {
            String text = segment.text() == null ? "" : segment.text().trim();
            if (text.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append('[').append(timestamp(segment.start())).append("] ").append(text);
        }
        return sb.toString();
    }

    /** 秒 → mm:ss（超过 1 小时补 hh:mm:ss）。 */
    static String timestamp(double seconds) {
        long total = Math.max(0L, Math.round(seconds));
        long hours = total / 3600;
        long minutes = (total % 3600) / 60;
        long secs = total % 60;
        return hours > 0
                ? String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, secs)
                : String.format(Locale.ROOT, "%02d:%02d", minutes, secs);
    }

    // ── 媒体落盘 ────────────────────────────────────────────────────────

    /** 封面取**首帧**（0s），与用户预期一致。 */
    private String storeCover(Knowledge k, Path mediaFile) {
        try {
            byte[] cover = media.extractCoverJpeg(mediaFile, 0d);
            if (cover.length == 0) {
                return "";
            }
            String ref = imageStore.storeFor(k, cover, "cover-" + k.getId() + ".jpg",
                    "image/jpeg", KIND_IMAGE);
            if (ref == null || ref.isEmpty()) {
                log.warn("store video cover returned empty ref for knowledge {}", k.getId());
            }
            return ref == null ? "" : ref;
        } catch (RuntimeException e) {
            log.warn("extract video cover failed for knowledge {}", k.getId(), e);
            return "";
        }
    }

    /** 视频/音频本体也复制到 exports 并登记，正文才能用受保护引用播放。 */
    private String storeMedia(Knowledge k, byte[] content) {
        try {
            String mime = ContentTypes.mimeOf(k.getFileType());
            String fileName = safeName(k);
            String ref = imageStore.storeFor(k, content, fileName, mime, KIND_FILE);
            if (ref == null || ref.isEmpty()) {
                log.warn("store media returned empty ref for knowledge {}", k.getId());
            }
            return ref == null ? "" : ref;
        } catch (RuntimeException e) {
            log.warn("store media failed for knowledge {}", k.getId(), e);
            return "";
        }
    }

    /**
     * 落 exports 用的文件名：**不能带空格与括号**。
     *
     * <p>正文里它是 markdown 链接目标的最后一段，只要含空格，CommonMark 就不会把它解析成
     * 链接（`[▶ 播放视频](local://…/aion i60….mp4)` 会原样显示成文本）——用户点不到、也
     * 渲染不出播放器。这里把「非字母/数字/中文/点/下划线/横线」一律折叠成 "-"。</p>
     */
    static String safeName(Knowledge k) {
        String name = k.getFileName() == null ? "" : k.getFileName().trim();
        if (name.isEmpty()) {
            name = k.getId() + (k.getFileType() == null ? "" : "." + k.getFileType());
        }
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            ext = name.substring(dot);
            name = name.substring(0, dot);
            if (!ext.matches("\\.[A-Za-z0-9]{1,6}")) {
                name = name + ext;
                ext = "";
            }
        }
        String cleaned = name.replaceAll("[^\\p{L}\\p{N}._-]+", "-").replaceAll("-{2,}", "-");
        cleaned = cleaned.replaceAll("^-+|-+$", "");
        return (cleaned.isEmpty() ? k.getId() : cleaned) + ext;
    }

    /** 扩展名 → mime（只覆盖音视频这几类，其余按 octet-stream 兜底）。 */
    static final class ContentTypes {
        private ContentTypes() {
        }

        static String mimeOf(String fileType) {
            String ext = fileType == null ? "" : fileType.toLowerCase(Locale.ROOT);
            return switch (ext) {
                case "mp4", "m4v" -> "video/mp4";
                case "mov" -> "video/quicktime";
                case "webm" -> "video/webm";
                case "mkv" -> "video/x-matroska";
                case "avi" -> "video/x-msvideo";
                case "mp3" -> "audio/mpeg";
                case "wav" -> "audio/wav";
                case "m4a" -> "audio/mp4";
                case "flac" -> "audio/flac";
                case "ogg" -> "audio/ogg";
                case "aac" -> "audio/aac";
                default -> "application/octet-stream";
            };
        }
    }

    private String displayTitle(Knowledge k) {
        String title = k.getTitle() == null ? "" : k.getTitle().trim();
        if (!title.isEmpty()) {
            return title;
        }
        String fileName = k.getFileName() == null ? "" : k.getFileName().trim();
        return fileName.isEmpty() ? "未命名音视频" : fileName;
    }

    /** KB 的 ASR 配置优先；未开启或没选模型时退回租户默认 ASR 模型。 */
    private String resolveAsrModelId(Knowledge k) {
        String kbId = k.getKnowledgeBaseId();
        if (kbId != null && !kbId.isBlank()) {
            try {
                KnowledgeBase kb = kbRepository.findByIdOnly(kbId);
                KnowledgeBaseAsrConfig asr = kb == null ? null : kb.getAsrConfig();
                if (asr != null && asr.isEnabled() && asr.getModelId() != null
                        && !asr.getModelId().isBlank()) {
                    return asr.getModelId();
                }
            } catch (RuntimeException e) {
                log.warn("resolve kb asr config failed for {}", kbId, e);
            }
        }
        if (modelCatalog == null) {
            return "";
        }
        try {
            for (ModelRowView model : modelCatalog.listModels()) {
                if (model != null && "ASR".equalsIgnoreCase(model.type())) {
                    return model.id();
                }
            }
        } catch (RuntimeException e) {
            log.warn("list models for default ASR failed", e);
        }
        return "";
    }

    private String resolveLanguage(Knowledge k) {
        String kbId = k.getKnowledgeBaseId();
        if (kbId == null || kbId.isBlank()) {
            return "";
        }
        try {
            KnowledgeBase kb = kbRepository.findByIdOnly(kbId);
            KnowledgeBaseAsrConfig asr = kb == null ? null : kb.getAsrConfig();
            return asr == null || asr.getLanguage() == null ? "" : asr.getLanguage();
        } catch (RuntimeException e) {
            return "";
        }
    }
}
