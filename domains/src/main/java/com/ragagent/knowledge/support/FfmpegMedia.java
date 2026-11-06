package com.ragagent.knowledge.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * ffmpeg / ffprobe 的最小封装：只做音视频入库需要的三件事——读时长与音轨、
 * 抽音轨成 mp3、抽一帧当封面。
 *
 * <p>不引第三方库：都是进程调用，失败时抛 {@link MediaToolException}，
 * 由上层决定「整个文档失败」还是「降级成无转写内容」。</p>
 */
public class FfmpegMedia {

    /** 单条命令的超时：抽音轨对大文件也够用，超时视为失败。 */
    private static final int COMMAND_TIMEOUT_SECONDS = 120;

    private final String ffmpeg;
    private final String ffprobe;

    /** 配置注入：镜像里通常是 PATH 上的 ffmpeg/ffprobe，dev 可指向自定义路径。 */
    public FfmpegMedia(String ffmpeg, String ffprobe) {
        this.ffmpeg = ffmpeg == null || ffmpeg.isBlank() ? "ffmpeg" : ffmpeg.trim();
        this.ffprobe = ffprobe == null || ffprobe.isBlank() ? "ffprobe" : ffprobe.trim();
    }

    /** 探测结果：时长（秒）与是否含音轨。 */
    public record Probe(double durationSeconds, boolean hasAudio) {
    }

    /** 音视频工具调用失败。 */
    public static class MediaToolException extends RuntimeException {
        public MediaToolException(String message) {
            super(message);
        }
    }

    /** 把上传字节落到临时文件（调用方负责删除）。 */
    public static Path writeTemp(byte[] data, String ext) throws IOException {
        Path file = Files.createTempFile("media-", ext == null || ext.isEmpty() ? ".bin" : "." + ext);
        Files.write(file, data);
        return file;
    }

    /** 读时长与音轨；任一命令失败都抛异常（调用方按「无法解析」处理）。 */
    public Probe probe(Path file) {
        String duration = run(ffprobe, List.of("-v", "error", "-show_entries",
                "format=duration", "-of", "default=nw=1:nk=1", file.toString()), false).trim();
        double seconds;
        try {
            seconds = duration.isEmpty() ? 0d : Double.parseDouble(duration.split("\\s")[0]);
        } catch (NumberFormatException e) {
            throw new MediaToolException("ffprobe returned invalid duration: " + duration);
        }
        // 无音轨时 ffprobe 正常退出但**没有输出**，这里允许空结果
        String audio = run(ffprobe, List.of("-v", "error", "-select_streams", "a",
                "-show_entries", "stream=codec_type", "-of", "csv=p=0", file.toString()), false);
        return new Probe(seconds, audio.contains("audio"));
    }

    /** 抽音轨成 16k 单声道 mp3（ASR 端点的通用输入）。 */
    public byte[] extractAudioMp3(Path file) {
        return runBytes(ffmpeg, List.of("-v", "error", "-y", "-i", file.toString(),
                "-vn", "-ac", "1", "-ar", "16000", "-b:a", "32k", "-f", "mp3", "pipe:1"));
    }

    /** 抽一帧作封面；{@code atSeconds} 超出时长时退回第 0 秒。 */
    public byte[] extractCoverJpeg(Path file, double atSeconds) {
        try {
            byte[] frame = runBytes(ffmpeg, List.of("-v", "error", "-y", "-ss",
                    String.valueOf(Math.max(0d, atSeconds)), "-i", file.toString(),
                    "-frames:v", "1", "-f", "image2", "-vcodec", "mjpeg", "pipe:1"));
            if (frame.length > 0) {
                return frame;
            }
        } catch (MediaToolException ignored) {
            // 落到第 0 秒再试一次
        }
        return runBytes(ffmpeg, List.of("-v", "error", "-y", "-i", file.toString(),
                "-frames:v", "1", "-f", "image2", "-vcodec", "mjpeg", "pipe:1"));
    }

    private String run(String command, List<String> args, boolean requireOutput) {
        return new String(runBytes(command, args, requireOutput), StandardCharsets.UTF_8);
    }

    private byte[] runBytes(String command, List<String> args) {
        return runBytes(command, args, true);
    }

    private byte[] runBytes(String command, List<String> args, boolean requireOutput) {
        List<String> cmd = new ArrayList<>(args.size() + 1);
        cmd.add(command);
        cmd.addAll(args);
        Process process;
        try {
            process = new ProcessBuilder(cmd).redirectErrorStream(false).start();
        } catch (IOException e) {
            throw new MediaToolException(command + " 不可用: " + e.getMessage());
        }
        try {
            byte[] stdout = process.getInputStream().readAllBytes();
            byte[] stderr = process.getErrorStream().readAllBytes();
            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new MediaToolException(command + " 超时（>" + COMMAND_TIMEOUT_SECONDS + "s）");
            }
            if (process.exitValue() != 0 || (requireOutput && stdout.length == 0)) {
                String err = new String(stderr, StandardCharsets.UTF_8).trim();
                throw new MediaToolException(command + " 执行失败: "
                        + (err.isEmpty() ? "exit " + process.exitValue() : err));
            }
            return stdout;
        } catch (IOException e) {
            throw new MediaToolException(command + " 读取输出失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MediaToolException(command + " 被中断");
        }
    }
}
