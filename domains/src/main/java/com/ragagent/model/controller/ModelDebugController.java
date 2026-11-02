package com.ragagent.model.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.contract.error.AppError;
import com.ragagent.contract.error.BizException;
import com.ragagent.contract.model.AsrTranscriber;
import com.ragagent.contract.model.ChatConfig;
import com.ragagent.contract.model.ChatMessage;
import com.ragagent.contract.model.ChatOptions;
import com.ragagent.contract.model.ChatStream;
import com.ragagent.contract.model.Embedder;
import com.ragagent.contract.model.LlmChatClient;
import com.ragagent.contract.model.ModelCatalogPort.ModelRowView;
import com.ragagent.contract.model.ModelParameters;
import com.ragagent.contract.model.ModelRuntime;
import com.ragagent.contract.model.RankResult;
import com.ragagent.contract.model.Reranker;
import com.ragagent.contract.model.ResponseType;
import com.ragagent.contract.model.StreamResponse;
import com.ragagent.contract.model.VlmPredictor;
import com.ragagent.model.dto.ModelDebugAsrResponse;
import com.ragagent.model.dto.ModelDebugChatResponse;
import com.ragagent.model.dto.ModelDebugResult;
import com.ragagent.model.dto.ModelDebugTarget;
import com.ragagent.model.service.ModelService;
import com.ragagent.model.service.ModelService.ModelNotFoundException;
import com.ragagent.platform.security.LogSanitizer;
import com.ragagent.platform.storage.config.UploadLimits;
import com.ragagent.platform.web.ApiResult;
import com.ragagent.platform.web.JsonMappers;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 模型调试端点（POST /api/v1/models/{id}/debug）。
 *
 * <p>语义要点：</p>
 * <ul>
 *   <li>multipart 表单：input / options(JSON) / documents(JSON 数组) / file；</li>
 *   <li>五类模型分支：KnowledgeQA(流式 chat) / Embedding / Rerank / VLLM / ASR；</li>
 *   <li>运行时错误<b>不是</b> HTTP 错误——落在结果对象里（{@code ok=false} +
 *       {@code error} 原文），HTTP 恒 200；参数校验错误才是 400 错误信封；</li>
 *   <li>请求预览只含非密字段：extraConfig 过 {@code redactedDebugConfig}，
 *       customHeaders 只露头名。</li>
 * </ul>
 */
@RestController
@ApiResult
@RequestMapping("/api/v1/models")
@Tag(name = "Model")
public class ModelDebugController {


    /** 调试请求体字节上限。 */
    private static final int MAX_INPUT_BYTES = 64 * 1024;
    /** documents 上限。 */
    private static final int MAX_DOCUMENTS = 100;

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    private final ModelService modelService;
    private final ModelRuntime runtimeFactory;
    private final AsrTranscriber asrTranscriber;

    public ModelDebugController(ModelService modelService, ModelRuntime runtimeFactory,
                                AsrTranscriber asrTranscriber) {
        this.modelService = modelService;
        this.runtimeFactory = runtimeFactory;
        this.asrTranscriber = asrTranscriber;
    }

    @PostMapping("/{id}/debug")
    public ResponseEntity<ModelDebugResult> debugModel(@PathVariable("id") String id,
                                        @RequestParam(value = "input", required = false) String input,
                                        @RequestParam(value = "options", required = false) String optionsRaw,
                                        @RequestParam(value = "documents", required = false) String documentsRaw,
                                        @RequestParam(value = "file", required = false) MultipartFile file) {
        long startedNanos = System.nanoTime();
        id = LogSanitizer.sanitize(id);
        if (id == null || id.isEmpty()) {
            throw new BizException(AppError.badRequest("Model ID cannot be empty"));
        }

        ModelDebugTarget model;
        try {
            model = modelService.getDebugTarget(id);
        } catch (ModelNotFoundException e) {
            throw new BizException(AppError.notFound("Model not found"));
        }

        // ── 表单字段校验 ──
        if (input == null) {
            input = "";
        }
        if (input.getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_BYTES) {
            throw new BizException(AppError.badRequest("input is too long"));
        }
        DebugOptions opts = parseOptions(optionsRaw);

        List<String> documents = null;
        if (documentsRaw != null && !documentsRaw.trim().isEmpty()) {
            documents = parseDocuments(documentsRaw);
            if (documents.size() > MAX_DOCUMENTS) {
                throw new BizException(AppError.badRequest("documents cannot exceed 100 items"));
            }
        }

        // ── 可选文件 ──
        byte[] fileBytes = null;
        String fileName = "";
        long fileSize = 0;
        if (file != null && fileName(file) != null && !fileName(file).isEmpty()) {
            fileName = fileName(file);
            fileSize = file.getSize();
            long maxBytes = UploadLimits.maxFileSizeBytes();
            long maxMb = UploadLimits.maxFileSizeMb();
            if (fileSize > maxBytes) {
                throw new BizException(AppError.badRequest("file cannot exceed " + maxMb + " MB"));
            }
            try {
                fileBytes = file.getBytes();
            } catch (Exception e) {
                throw new BizException(AppError.badRequest("failed to read uploaded file"));
            }
            if (fileBytes.length > maxBytes) {
                throw new BizException(AppError.badRequest("file cannot exceed " + maxMb + " MB"));
            }
            fileSize = fileBytes.length;
        }

        Map<String, Object> requestPreview =
                requestPreview(model, input, documents, opts, fileName, fileSize);
        Map<String, Object> observations = new TreeMap<>(); // 按键排序输出

        return switch (model.type() == null ? "" : model.type()) {
            case "KnowledgeQA" -> debugChat(model, input, opts, startedNanos, requestPreview, observations);
            case "Embedding" -> debugEmbedding(model, input, startedNanos, requestPreview, observations);
            case "Rerank" -> debugRerank(model, input, documents, startedNanos, requestPreview, observations);
            case "VLLM" -> debugVlm(model, input, fileBytes, startedNanos, requestPreview, observations);
            case "ASR" -> debugAsr(model, fileBytes, fileName, startedNanos, requestPreview, observations);
            default -> throw new BizException(AppError.badRequest("unsupported model type"));
        };
    }

    // ── KnowledgeQA ──────────────────────────────────────────────────────

    private ResponseEntity<ModelDebugResult> debugChat(ModelDebugTarget model, String input, DebugOptions opts,
                                        long startedNanos, Map<String, Object> requestPreview,
                                        Map<String, Object> observations) {
        if (input.trim().isEmpty()) {
            throw new BizException(AppError.badRequest("query cannot be empty"));
        }
        LlmChatClient instance;
        try {
            instance = runtimeFactory.getChatModel(model.id());
        } catch (RuntimeException e) {
            return writeResult(startedNanos, requestPreview, null, e.getMessage(), observations);
        }
        List<ChatMessage> messages = new ArrayList<>(2);
        if (opts.systemPrompt != null && !opts.systemPrompt.trim().isEmpty()) {
            messages.add(new ChatMessage("system", opts.systemPrompt));
        }
        messages.add(new ChatMessage("user", input));
        ChatOptions chatOpts = new ChatOptions();
        if (opts.temperature != null) {
            chatOpts.setTemperature(opts.temperature);
        }
        if (opts.topP != null) {
            chatOpts.setTopP(opts.topP);
        }
        if (opts.maxTokens != null) {
            chatOpts.setMaxTokens(opts.maxTokens);
        }
        chatOpts.setThinking(opts.thinking);

        // 组 chat 配置 + thinking 观测
        ChatConfig chatConfig = runtimeFactory.chatConfigFor(new ModelRowView(
                model.id(), model.name(), model.type(), model.source(), model.status(),
                model.isDefault(), model.parameters()));
        String thinkingControl = runtimeFactory.effectiveThinkingControl(chatConfig);
        observations.put("stream", true);
        observations.put("requestedThinking", opts.thinking != null && opts.thinking);
        observations.put("thinkingControl", thinkingControl);
        observations.put("thinkingParameterSent", opts.thinking != null && !"none".equals(thinkingControl));

        ChatStream stream;
        try {
            stream = instance.chatStream(messages, chatOpts);
        } catch (RuntimeException e) {
            return writeResult(startedNanos, requestPreview, null, e.getMessage(), observations);
        }
        ModelDebugChatResponse resp = new ModelDebugChatResponse();
        String streamError = null;
        try {
            // 流式消费：适配器会在最后一个内容分片（done=true）之后再
            // 补一条带 usage 的终态 done 事件，因此看到 done 不能立刻停——
            // 改成「done 之后短超时排空队列」：终态事件由同一线程紧跟着 put，
            // 2 秒窗口足够覆盖调度交错；poll 超时 = 对 channel close 的模拟。
            boolean sawDone = false;
            while (true) {
                StreamResponse event = sawDone
                        ? stream.poll(2, java.util.concurrent.TimeUnit.SECONDS)
                        : stream.take();
                if (event == null) {
                    break;
                }
                resp.getStreamEvents().add(event);
                ResponseType type = event.getResponseType();
                if (type == ResponseType.THINKING) {
                    resp.setReasoningContent(
                            (resp.getReasoningContent() == null ? "" : resp.getReasoningContent())
                                    + event.getContent());
                } else if (type == ResponseType.ANSWER) {
                    resp.setContent(resp.getContent() + event.getContent());
                    if (event.getToolCalls() != null && !event.getToolCalls().isEmpty()) {
                        resp.setToolCalls(event.getToolCalls());
                    }
                    if (event.getFinishReason() != null && !event.getFinishReason().isEmpty()) {
                        resp.setFinishReason(event.getFinishReason());
                    }
                    if (event.getUsage() != null) {
                        resp.setUsage(event.getUsage());
                    }
                } else if (type == ResponseType.TOOL_CALL) {
                    if (event.getToolCalls() != null && !event.getToolCalls().isEmpty()) {
                        resp.setToolCalls(event.getToolCalls());
                    }
                } else if (type == ResponseType.ERROR) {
                    streamError = event.getContent();
                    break;
                }
                if (event.isDone()) {
                    sawDone = true;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            streamError = "interrupted";
        } finally {
            // A3：排空窗口结束 = 不再消费，关流终止上游（调试端点也要守消费者纪律）。
            stream.close();
        }
        // resp 恒非 null（consume 出错也返回部分结果）
        String reasoning = resp.getReasoningContent() == null ? "" : resp.getReasoningContent();
        observations.put("reasoningReturned", !reasoning.trim().isEmpty());
        observations.put("reasoningCharacters", reasoning.codePointCount(0, reasoning.length()));
        observations.put("answerCharacters", resp.getContent().codePointCount(0, resp.getContent().length()));
        return writeResult(startedNanos, requestPreview, resp, streamError, observations);
    }

    // ── Embedding ────────────────────────────────────────────────────────

    private ResponseEntity<ModelDebugResult> debugEmbedding(ModelDebugTarget model, String input, long startedNanos,
                                             Map<String, Object> requestPreview, Map<String, Object> observations) {
        if (input.trim().isEmpty()) {
            throw new BizException(AppError.badRequest("input cannot be empty"));
        }
        Embedder instance;
        try {
            instance = runtimeFactory.getEmbeddingModel(model.id());
        } catch (RuntimeException e) {
            return writeResult(startedNanos, requestPreview, null, e.getMessage(), observations);
        }
        float[] vector = null;
        String error = null;
        try {
            vector = instance.embed(input);
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        observations.put("dimension", vector == null ? 0 : vector.length);
        return writeResult(startedNanos, requestPreview, vector, error, observations);
    }

    // ── Rerank ───────────────────────────────────────────────────────────

    private ResponseEntity<ModelDebugResult> debugRerank(ModelDebugTarget model, String input, List<String> documents,
                                          long startedNanos, Map<String, Object> requestPreview,
                                          Map<String, Object> observations) {
        if (input.trim().isEmpty() || documents == null || documents.isEmpty()) {
            throw new BizException(AppError.badRequest("query and documents cannot be empty"));
        }
        Reranker instance;
        try {
            instance = runtimeFactory.getRerankModel(model.id());
        } catch (RuntimeException e) {
            return writeResult(startedNanos, requestPreview, null, e.getMessage(), observations);
        }
        List<RankResult> results = null;
        String error = null;
        try {
            results = instance.rerank(input, documents);
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        observations.put("resultCount", results == null ? 0 : results.size());
        return writeResult(startedNanos, requestPreview, results, error, observations);
    }

    // ── VLLM ─────────────────────────────────────────────────────────────

    private ResponseEntity<ModelDebugResult> debugVlm(ModelDebugTarget model, String input, byte[] fileBytes,
                                       long startedNanos, Map<String, Object> requestPreview,
                                       Map<String, Object> observations) {
        if (fileBytes == null || fileBytes.length == 0) {
            throw new BizException(AppError.badRequest("image file is required"));
        }
        ModelRowView vlmModel;
        try {
            vlmModel = runtimeFactory.getVlmModel(model.id());
        } catch (RuntimeException e) {
            return writeResult(startedNanos, requestPreview, null, e.getMessage(), observations);
        }
        // 装配（配置 + 构造期 SSRF 校验 + 观测装饰 + 并发闸门）整段走模型运行时端口
        // ——与 agent 侧 VLM 装配共用同一条链。
        VlmPredictor predictor;
        try {
            predictor = runtimeFactory.vlmPredictorFor(vlmModel);
        } catch (RuntimeException e) {
            return writeResult(startedNanos, requestPreview, null, e.getMessage(), observations);
        }
        String result = null;
        String error = null;
        try {
            result = predictor.predict(fileBytes, input);
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        String answer = result == null ? "" : result;
        observations.put("answerCharacters", answer.codePointCount(0, answer.length()));
        return writeResult(startedNanos, requestPreview, result, error, observations);
    }

    // ── ASR ──────────────────────────────────────────────────────────────

    private ResponseEntity<ModelDebugResult> debugAsr(ModelDebugTarget model, byte[] fileBytes, String fileName,
                                       long startedNanos, Map<String, Object> requestPreview,
                                       Map<String, Object> observations) {
        if (fileBytes == null || fileBytes.length == 0) {
            throw new BizException(AppError.badRequest("audio file is required"));
        }
        try {
            runtimeFactory.getAsrModel(model.id());
        } catch (RuntimeException e) {
            return writeResult(startedNanos, requestPreview, null, e.getMessage(), observations);
        }
        ModelParameters p = model.parameters();
        // language 不从模型来（恒空），customHeaders 透传
        var config = new AsrTranscriber.AsrConfig(
                p == null ? "" : p.getBaseUrl(), model.name(), p == null ? "" : p.getApiKey(),
                model.id(), "", p == null ? null : p.getCustomHeaders());
        AsrTranscriber.TranscriptionResult result = null;
        String error = null;
        try {
            result = asrTranscriber.transcribe(config, fileBytes, fileName);
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        ModelDebugAsrResponse raw = null;
        if (result != null) {
            observations.put("textCharacters",
                    result.text() == null ? 0 : result.text().codePointCount(0, result.text().length()));
            observations.put("segmentCount", result.segments() == null ? 0 : result.segments().size());
            List<ModelDebugAsrResponse.Segment> segments = null;
            if (result.segments() != null) {
                segments = new ArrayList<>(result.segments().size());
                for (var s : result.segments()) {
                    segments.add(new ModelDebugAsrResponse.Segment(s.start(), s.end(), s.text()));
                }
            }
            raw = new ModelDebugAsrResponse(result.text(), segments);
        }
        return writeResult(startedNanos, requestPreview, raw, error, observations);
    }

    // ── 结果整形 ─────────────────────────────────────────────────────────

    /** 结果整形：HTTP 恒 200，裸结果对象（无信封）。 */
    private ResponseEntity<ModelDebugResult> writeResult(long startedNanos, Map<String, Object> request,
                                                         Object rawResponse, String error,
                                                         Map<String, Object> observations) {
        long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000;
        return ResponseEntity.ok(new ModelDebugResult(
                error == null, elapsedMs, error, request, rawResponse, observations));
    }

    /**
     * 请求预览（只含非密字段）：顶层键按字母序输出（确定性）；
     * options 各键仅在显式给出时出现（键序 = 声明序）。
     */
    private Map<String, Object> requestPreview(ModelDebugTarget model, String input, List<String> documents,
                                               DebugOptions opts, String fileName, long fileSize) {
        ModelParameters p = model.parameters();
        Map<String, Object> preview = new TreeMap<>();
        if (p != null && p.getCustomHeaders() != null && !p.getCustomHeaders().isEmpty()) {
            // 只露头名（值可能含密钥），排序输出保证确定性
            preview.put("customHeaderNames", new ArrayList<>(new java.util.TreeSet<>(
                    p.getCustomHeaders().keySet())));
        }
        if (documents != null && !documents.isEmpty()) {
            preview.put("documents", documents);
        }
        if (fileName != null && !fileName.isEmpty()) {
            Map<String, Object> fileInfo = new LinkedHashMap<>();
            fileInfo.put("name", fileName);
            fileInfo.put("size", fileSize);
            preview.put("file", fileInfo);
        }
        preview.put("input", input);
        if (p != null && p.getExtraConfig() != null) {
            preview.put("modelExtraConfig", redactedDebugConfig(p.getExtraConfig()));
        }
        preview.put("modelId", model.id());
        preview.put("modelName", model.name());
        preview.put("modelType", model.type());

        Map<String, Object> options = new LinkedHashMap<>();
        if (opts.systemPrompt != null && !opts.systemPrompt.isEmpty()) {
            options.put("systemPrompt", opts.systemPrompt);
        }
        if (opts.temperature != null) {
            options.put("temperature", opts.temperature);
        }
        if (opts.topP != null) {
            options.put("topP", opts.topP);
        }
        if (opts.maxTokens != null) {
            options.put("maxTokens", opts.maxTokens);
        }
        if (opts.thinking != null) {
            options.put("thinking", opts.thinking);
        }
        preview.put("options", options);

        preview.put("provider", p == null || p.getProvider() == null ? "" : p.getProvider());
        preview.put("source", model.source());
        return preview;
    }

    /** 配置脱敏：空 → null；命中敏感词的值换 [REDACTED]（键序按字母序）。 */
    private static Map<String, String> redactedDebugConfig(Map<String, String> config) {
        if (config == null || config.isEmpty()) {
            return null;
        }
        Map<String, String> out = new TreeMap<>(); // 序列化按键排序
        for (Map.Entry<String, String> e : config.entrySet()) {
            String lower = e.getKey().toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("secret") || lower.contains("token") || lower.contains("password")
                    || lower.contains("api_key") || lower.contains("apikey")
                    || lower.contains("authorization")) {
                out.put(e.getKey(), "[REDACTED]");
            } else {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    // ── options / documents 解析 ──

    /** 调试选项（null 表示"未显式给出"，与显式 false/0 区分）。 */
    private record DebugOptions(String systemPrompt, Double temperature, Double topP,
                                Integer maxTokens, Boolean thinking) {
        static final DebugOptions EMPTY = new DebugOptions("", null, null, null, null);
    }

    /** options JSON 解析：键为 camelCase；null / 空 → 零值；类型/范围错误 → 400。 */
    private static DebugOptions parseOptions(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return DebugOptions.EMPTY;
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new BizException(AppError.badRequest("invalid options: malformed JSON"));
        }
        if (node == null || node.isNull()) {
            // "null" 解析为零值无错误
            return DebugOptions.EMPTY;
        }
        if (!node.isObject()) {
            throw new BizException(AppError.badRequest("invalid options: expected a JSON object"));
        }
        String systemPrompt = "";
        Double temperature = null;
        Double topP = null;
        Integer maxTokens = null;
        Boolean thinking = null;
        JsonNode v;
        if ((v = node.get("systemPrompt")) != null && !v.isNull()) {
            if (!v.isTextual()) {
                throw new BizException(AppError.badRequest(
                        "invalid options: systemPrompt must be a string"));
            }
            systemPrompt = v.asText();
        }
        if ((v = node.get("temperature")) != null && !v.isNull()) {
            if (!v.isNumber()) {
                throw new BizException(AppError.badRequest(
                        "invalid options: temperature must be a number"));
            }
            temperature = v.asDouble();
        }
        if ((v = node.get("topP")) != null && !v.isNull()) {
            if (!v.isNumber()) {
                throw new BizException(AppError.badRequest("invalid options: topP must be a number"));
            }
            topP = v.asDouble();
        }
        if ((v = node.get("maxTokens")) != null && !v.isNull()) {
            if (!v.isIntegralNumber()) {
                throw new BizException(AppError.badRequest(
                        "invalid options: maxTokens must be an integer"));
            }
            maxTokens = v.asInt();
        }
        if ((v = node.get("thinking")) != null && !v.isNull()) {
            if (!v.isBoolean()) {
                throw new BizException(AppError.badRequest(
                        "invalid options: thinking must be a boolean"));
            }
            thinking = v.asBoolean();
        }
        if (maxTokens != null && (maxTokens < 1 || maxTokens > 8192)) {
            throw new BizException(AppError.badRequest("maxTokens must be between 1 and 8192"));
        }
        if (temperature != null && (temperature < 0 || temperature > 2)) {
            throw new BizException(AppError.badRequest("temperature must be between 0 and 2"));
        }
        if (topP != null && (topP <= 0 || topP > 1)) {
            throw new BizException(AppError.badRequest("topP must be greater than 0 and at most 1"));
        }
        return new DebugOptions(systemPrompt, temperature, topP, maxTokens, thinking);
    }

    /** documents 解析：非法 JSON / 非字符串数组 → 固定文案。 */
    private static List<String> parseDocuments(String raw) {
        JsonNode node;
        try {
            node = MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new BizException(AppError.badRequest("documents must be a JSON string array"));
        }
        if (node == null || !node.isArray()) {
            throw new BizException(AppError.badRequest("documents must be a JSON string array"));
        }
        List<String> documents = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw new BizException(AppError.badRequest("documents must be a JSON string array"));
            }
            documents.add(item.asText());
        }
        return documents;
    }

    // ── thinking 观测 ─────────────────────────────────────────────────────


    // ── 小工具 ───────────────────────────────────────────────────────────


    private static String fileName(MultipartFile file) {
        return file == null ? null : file.getOriginalFilename();
    }
}
