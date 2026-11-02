package com.ragagent.contract.model;

import java.util.List;
import java.util.Map;

/**
 * ASR（语音识别）调用端口（契约面）。
 *
 * <p><b>为什么在契约面</b>：消费方横跨初始化（asr/check）、模型调试、会话（临时文档转写）
 * ——都是按模型配置调 provider 的能力，实现（OpenAI 兼容 /v1/audio/transcriptions 薄实现）
 * 收在 model-runtime，消费方只认本接口与下面几个形状。</p>
 *
 * <p><b>实现侧线上行为（错误文案等已被 golden 钉死，不可改字）</b>：构造期 SSRF 校验、
 * multipart POST {baseURL}/audio/transcriptions、非 2xx 取 {@code error.message}、
 * 200 响应 {text, segments}。详见实现类 javadoc。</p>
 */
public interface AsrTranscriber {

    /** ASR 调用配置（本端点只用这些字段）。 */
    record AsrConfig(String baseUrl, String modelName, String apiKey, String modelId,
                     String language, Map<String, String> customHeaders) {}

    /** 转写结果（text + segments；segments 为空时省略）。 */
    record TranscriptionResult(String text, List<Segment> segments) {
        public record Segment(double start, double end, String text) {}
    }

    /** 构造失败异常（message 已含 "base URL SSRF check failed" 等前缀原文，文案钉死）。 */
    class AsrCreateException extends RuntimeException {
        public AsrCreateException(String message) { super(message); }

        public AsrCreateException(String message, Throwable cause) { super(message, cause); }
    }

    /** 调用失败异常（message 按 "ASR transcription request failed: ..." 包裹，文案钉死）。 */
    class AsrTranscribeException extends RuntimeException {
        public AsrTranscribeException(String message) { super(message); }

        public AsrTranscribeException(String message, Throwable cause) { super(message, cause); }
    }

    /**
     * 转写入口。构造失败抛 {@link AsrCreateException}，
     * 调用失败抛 {@link AsrTranscribeException}，成功返回结果。
     */
    TranscriptionResult transcribe(AsrConfig config, byte[] audioBytes, String fileName);
}
