package com.ragagent.modelruntime.adapter.provider.vlm;

import com.ragagent.contract.model.VlmConfig;
import com.ragagent.llm.ollama.OllamaChatRequest;
import com.ragagent.llm.ollama.OllamaMessage;
import com.ragagent.llm.ollama.OllamaService;
import com.ragagent.platform.web.JsonMappers;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * VLM（视觉语言模型）Predict 客户端。
 *
 * <p>OpenAI 兼容 chat.completions：multipart 内容（text prompt 先行 + 每张图
 * base64 data-URI，detail=auto）、max_tokens=5000、temperature 缺省 0.1
 * （extra_config.temperature 覆盖）、reasoning/GPT5 模型请求整形
 * （max_tokens→max_completion_tokens，采样参数清零）、错误族
 * （no choices / 空 content + finish_reason=length 的截断语义）。
 * <b>ollama interface</b>：经既有 {@code OllamaService} 走
 * {@code POST /api/chat}（images 为原始字节，Jackson 序列化成 base64），
 * stream=false、temperature=0.1，取响应的
 * {@code message.content}。</p>
 */
public final class VlmClient {

    private static final int DEFAULT_MAX_TOKS = 5000;
    private static final double DEFAULT_TEMP = 0.1;

    private VlmClient() {
    }

    /** HTTP 非 2xx（带状态码与响应体原文，供调用方组装错误文案）。 */
    public static final class HttpStatusException extends RuntimeException {

        private final int status;
        private final String body;

        public HttpStatusException(int status, String body) {
            super("status " + status + ": " + body);
            this.status = status;
            this.body = body;
        }

        public int status() {
            return status;
        }

        public String body() {
            return body;
        }
    }

    /** Predict 失败（受检异常）。 */
    public static final class VlmException extends Exception {
        public VlmException(String message) {
            super(message);
        }
    }

    /**
     * OpenAI 兼容预测入口。
     *
     * @param transport POST {base}/chat/completions 的出站通道（注入以便测试与
     *                  provider 接线）
     * @return choices[0].message.content
     */
    public static String predict(VlmConfig config, Transport transport, byte[][] imgBytesList,
            String prompt) throws VlmException {
        if (config != null && config.isOllama()) {
            return predictOllama(OllamaService.getOllamaService(), config,
                    imgBytesList, prompt);
        }
        // 请求体构建（chat.completions 标准字段）
        List<Object> parts = new ArrayList<>();
        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("type", "text");
        textPart.put("text", prompt);
        parts.add(textPart);
        for (byte[] img : imgBytesList) {
            if (img != null && img.length > 0) {
                String mime = detectImageMime(img);
                String dataUri = "data:" + mime + ";base64,"
                        + Base64.getEncoder().encodeToString(img);
                Map<String, Object> imageUrl = new LinkedHashMap<>();
                imageUrl.put("url", dataUri);
                imageUrl.put("detail", "auto");
                Map<String, Object> imgPart = new LinkedHashMap<>();
                imgPart.put("type", "image_url");
                imgPart.put("image_url", imageUrl);
                parts.add(imgPart);
            }
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", parts);

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("model", config.modelName());
        req.put("messages", List.of(message));
        req.put("max_tokens", DEFAULT_MAX_TOKS);
        req.put("temperature", config.temperature());
        shapeReasoningVlmRequest(config.modelName(), req);

        String url = config.baseUrl() == null ? "" : config.baseUrl().replaceAll("/+$", "")
                + "/chat/completions";
        String respBody;
        try {
            respBody = transport.post(url, config.apiKey(), req);
        } catch (Exception e) {
            throw new VlmException("OpenAI VLM request: " + e.getMessage());
        }

        com.fasterxml.jackson.databind.JsonNode root;
        try {
            root = JsonMappers.lenient().readTree(respBody);
        } catch (Exception e) {
            throw new VlmException("OpenAI VLM request: " + e.getMessage());
        }
        var choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new VlmException("OpenAI VLM returned no choices");
        }
        var choice = choices.get(0);
        String content = choice.path("message").path("content").asText("");
        if (content.strip().isEmpty()
                && "length".equals(choice.path("finish_reason").asText(""))) {
            throw new VlmException("OpenAI VLM returned no content: completion truncated at "
                    + DEFAULT_MAX_TOKS + " tokens (finish_reason=length)");
        }
        return content;
    }

    /**
     * 本地 Ollama 预测：{@code /api/chat}——
     * 单条 user 消息（prompt + 各图原始字节）、{@code stream=false}、
     * {@code options.temperature=0.1}，回调里取最后一次响应的 {@code message.content}。
     * 错误文案：{@code Ollama VLM request: …}。
     */
    static String predictOllama(OllamaService service, VlmConfig config,
            byte[][] imgBytesList, String prompt) throws VlmException {
        List<byte[]> images = new ArrayList<>();
        for (byte[] img : imgBytesList) {
            if (img != null && img.length > 0) {
                images.add(img);
            }
        }
        OllamaMessage message =
                new OllamaMessage("user", prompt);
        message.setImages(images);

        OllamaChatRequest request =
                new OllamaChatRequest();
        request.setModel(config.modelName());
        request.setMessages(new ArrayList<>(List.of(message)));
        request.setStream(false);
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("temperature", DEFAULT_TEMP);
        request.setOptions(options);

        final String[] result = new String[1];
        try {
            // 日志口径：model / numImages / totalImageSize
            service.chat(request, response -> {
                if (response != null && response.getMessage() != null) {
                    result[0] = response.getMessage().getContent();
                }
            });
        } catch (RuntimeException e) {
            throw new VlmException("Ollama VLM request: " + e.getMessage());
        }
        return result[0] == null ? "" : result[0];
    }

    /**
     * 请求整形：OpenAI reasoning /
     * GPT5 家族把 max_tokens 平移到 max_completion_tokens，采样参数清零。
     */
    static void shapeReasoningVlmRequest(String modelName, Map<String, Object> req) {
        if (!isReasoningOrGpt5(modelName)) {
            return;
        }
        Object maxTokens = req.get("max_tokens");
        if (!req.containsKey("max_completion_tokens") && maxTokens instanceof Number n) {
            req.put("max_completion_tokens", n.intValue());
        }
        req.remove("max_tokens");
        req.put("temperature", 0);
    }

    /** OpenAI reasoning / GPT5 家族判定。 */
    static boolean isReasoningOrGpt5(String modelName) {
        String name = modelName == null ? "" : modelName.strip().toLowerCase();
        if (name.isEmpty()) {
            return false;
        }
        if (name.startsWith("gpt-5")) {
            return true;
        }
        for (String prefix : new String[] {"o1", "o3", "o4"}) {
            if (name.equals(prefix) || name.startsWith(prefix + "-")) {
                return true;
            }
        }
        return false;
    }

    /** 图片 MIME 嗅探（非 image/ 前缀时回落 image/png）。 */
    static String detectImageMime(byte[] data) {
        String ct = sniff(data);
        return ct.startsWith("image/") ? ct : "image/png";
    }

    private static String sniff(byte[] b) {
        // 魔数嗅探表的相关子集（JPEG 3B / PNG 8B / GIF 6B /
        // WEBP 12B / XML 前缀）
        if (b.length >= 3 && b[0] == (byte) 0xFF && b[1] == (byte) 0xD8 && b[2] == (byte) 0xFF) {
            return "image/jpeg";
        }
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        if (b.length >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F') {
            return "image/gif";
        }
        if (b.length >= 12 && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        if (b.length >= 5 && b[0] == '<' && b[1] == '?') {
            return "text/xml";
        }
        return "application/octet-stream";
    }

    /** 出站 POST 通道（生产由 HTTP 客户端实现；测试用内存 stub）。 */
    public interface Transport {
        String post(String url, String apiKey, Object jsonBody) throws Exception;

    }
}
