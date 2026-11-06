package com.ragagent.platform.tracing.decorators;

import com.ragagent.contract.model.ChatMessage;
import com.ragagent.contract.model.ChatOptions;
import com.ragagent.contract.model.ChatResponse;
import com.ragagent.contract.model.ChatStream;
import com.ragagent.contract.model.ChatTool;
import com.ragagent.contract.model.LlmChatClient;
import com.ragagent.contract.model.ResponseType;
import com.ragagent.contract.model.StreamResponse;
import com.ragagent.contract.model.TokenUsage;
import com.ragagent.contract.model.ToolCall;
import com.ragagent.platform.stream.QueuedChatStream;
import com.ragagent.platform.tracing.langfuse.Generation;
import com.ragagent.platform.tracing.langfuse.LangfuseManager;
import com.ragagent.platform.tracing.langfuse.LangfuseTokenUsage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * chat 客户端的 langfuse 装饰器：
 * 每次 Chat/ChatStream 发一条 generation 观测（消息输入、模型参数、输出、用量）。
 * 由 {@code ModelRuntime.getChatModel} 在管理器启用时装配。
 * 未启用时 {@link #wrap} 原样返回，零成本。
 *
 * <p><b>实现边界</b>：调用点未携带 call_purpose / prompt_prefix_fingerprint
 * 的元数据载体，两键恒为空串（真实取值不产出）。</p>
 */
public final class LangfuseChatClient implements LlmChatClient {

    private static final String PURPOSE = "";
    private static final String PREFIX_FINGERPRINT = "";

    private final LlmChatClient inner;

    LangfuseChatClient(LlmChatClient inner) {
        this.inner = inner;
    }

    /** 未启用/空客户端原样返回。 */
    public static LlmChatClient wrap(LlmChatClient client) {
        if (client == null || !LangfuseManager.get().enabled()) {
            return client;
        }
        return new LangfuseChatClient(client);
    }

    @Override
    public ChatResponse chat(List<ChatMessage> messages, ChatOptions options) {
        LangfuseManager manager = LangfuseManager.get();
        if (!manager.enabled()) {
            return inner.chat(messages, options);
        }
        Generation gen = manager.startGeneration(new LangfuseManager.GenerationOptions(
                "chat.completion", inner.getModelName(), buildMessages(messages),
                buildChatMetadata(inner.getModelId(), false, options),
                buildModelParams(options)));

        ChatResponse response = null;
        String err = null;
        try {
            response = inner.chat(messages, options);
            return response;
        } catch (RuntimeException e) {
            err = e.getMessage() == null ? e.toString() : e.getMessage();
            throw e;
        } finally {
            Object output = null;
            LangfuseTokenUsage usage = null;
            if (response != null) {
                usage = LangfusePayloads.convertUsage(response.getUsage());
                output = buildGenerationOutput(response.getContent(), response.getReasoningContent(),
                        response.getFinishReason(), response.getToolCalls());
            }
            gen.finish(output, usage, err);
        }
    }

    @Override
    public ChatStream chatStream(List<ChatMessage> messages, ChatOptions options) {
        LangfuseManager manager = LangfuseManager.get();
        if (!manager.enabled()) {
            return inner.chatStream(messages, options);
        }
        Generation gen = manager.startGeneration(new LangfuseManager.GenerationOptions(
                "chat.completion.stream", inner.getModelName(), buildMessages(messages),
                buildChatMetadata(inner.getModelId(), true, options),
                buildModelParams(options)));

        ChatStream innerQueue;
        try {
            innerQueue = inner.chatStream(messages, options);
        } catch (RuntimeException e) {
            gen.finish(null, null, e.getMessage() == null ? e.toString() : e.getMessage());
            throw e;
        }
        if (innerQueue == null) {
            gen.finish(null, null, null);
            return null;
        }

        // 转发线程：累积 content/reasoning/usage/
        // tool_calls/finish_reason，首 token 到达时调 markCompletionStart，流结束收 generation。
        // 句柄可关闭——关闭即中断转发线程（generation 在它的 finally 收尾）并向上游
        // 传播关闭；重新投终态帧解阻塞，消费者不必再靠"无界队列里的堆积"感知放弃。
        AtomicReference<Thread> forwarderRef = new AtomicReference<>();
        QueuedChatStream wrapped = new QueuedChatStream(() -> {
            Thread forwarder = forwarderRef.get();
            if (forwarder != null) {
                forwarder.interrupt();
            }
            innerQueue.close();
        });
        forwarderRef.set(Thread.ofVirtual().name("langfuse-chat-stream").start(() -> {
            StringBuilder content = new StringBuilder();
            StringBuilder reasoning = new StringBuilder();
            TokenUsage usage = null;
            List<ToolCall> toolCalls = null;
            String finishReason = "";
            boolean firstToken = false;
            boolean terminalSeen = false;
            try {
                while (true) {
                    if (wrapped.isClosed()) {
                        break; // 消费者已关流：generation 交给 finally 收
                    }
                    StreamResponse resp;
                    try {
                        // 终态之后仍可能有尾随分片（用量常在独立 chunk 里到达），
                        // 短宽限窗口排空后再收 generation。
                        resp = terminalSeen
                                ? innerQueue.poll(500, TimeUnit.MILLISECONDS)
                                : innerQueue.take();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    if (resp == null) {
                        break;
                    }
                    ResponseType type = resp.getResponseType();
                    String chunk = resp.getContent() == null ? "" : resp.getContent();
                    if ((type == ResponseType.THINKING || type == ResponseType.ANSWER)
                            && !chunk.isEmpty()) {
                        if (!firstToken) {
                            gen.markCompletionStart();
                            firstToken = true;
                        }
                        if (type == ResponseType.THINKING) {
                            reasoning.append(chunk);
                        } else {
                            content.append(chunk);
                        }
                    }
                    if (resp.getUsage() != null) {
                        usage = resp.getUsage();
                    }
                    if (resp.getToolCalls() != null && !resp.getToolCalls().isEmpty()) {
                        // 快照：下游可能就地改写 parameters
                        toolCalls = List.copyOf(resp.getToolCalls());
                    }
                    if (resp.getFinishReason() != null && !resp.getFinishReason().isEmpty()) {
                        finishReason = resp.getFinishReason();
                    }
                    try {
                        wrapped.put(resp);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    // thinking 的 done=true 只是"思考结束"标记，其后流仍开着（答案分片随后来）
                    // ——只有非 thinking 的终态才算收尾，提前 break 会把答案与用量丢掉、
                    // 并让消费方永久阻塞。
                    if (resp.isDone() && type != ResponseType.THINKING) {
                        terminalSeen = true;
                    }
                }
            } finally {
                gen.finish(buildGenerationOutput(content.toString(), reasoning.toString(),
                        finishReason, toolCalls), LangfusePayloads.convertUsage(usage), null);
            }
        }));
        return wrapped;
    }

    @Override
    public String getModelName() {
        return inner.getModelName();
    }

    @Override
    public String getModelId() {
        return inner.getModelId();
    }

    // ── 载荷构造 ──

    /** 构造 messages 载荷。 */
    static List<Map<String, Object>> buildMessages(List<ChatMessage> messages) {
        List<Map<String, Object>> out = new ArrayList<>(messages.size());
        for (ChatMessage m : messages) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("role", m.getRole());
            if (m.getContent() != null && !m.getContent().isEmpty()) {
                entry.put("content", m.getContent());
            }
            if (m.getMultiContent() != null && !m.getMultiContent().isEmpty()) {
                entry.put("content", m.getMultiContent());
            }
            if (m.getName() != null && !m.getName().isEmpty()) {
                entry.put("name", m.getName());
            }
            if (m.getToolCallId() != null && !m.getToolCallId().isEmpty()) {
                entry.put("tool_call_id", m.getToolCallId());
            }
            if (m.getToolCalls() != null && !m.getToolCalls().isEmpty()) {
                entry.put("tool_calls", m.getToolCalls());
            }
            if (m.getReasoningContent() != null && !m.getReasoningContent().isEmpty()) {
                entry.put("reasoning_content", m.getReasoningContent());
            }
            out.add(entry);
        }
        return out;
    }

    /** chat 观测的 metadata 载荷（含 MCP 目录截断）。 */
    static Map<String, Object> buildChatMetadata(String modelId, boolean streaming,
                                                 ChatOptions options) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("model_id", modelId == null ? "" : modelId);
        meta.put("streaming", streaming);
        meta.put("has_tools", options != null && options.getTools() != null
                && !options.getTools().isEmpty());
        meta.put("call_purpose", PURPOSE);
        meta.put("prompt_prefix_fingerprint", PREFIX_FINGERPRINT);
        if (options == null || options.getTools() == null || options.getTools().isEmpty()) {
            return meta;
        }
        List<String> names = new ArrayList<>(options.getTools().size());
        for (ChatTool tool : options.getTools()) {
            String name = tool.getFunction() == null ? "" : tool.getFunction().getName();
            names.add(name);
            if ("discover_mcp_tools".equals(name) && tool.getFunction() != null
                    && tool.getFunction().getDescription() != null
                    && !tool.getFunction().getDescription().isEmpty()) {
                meta.put("mcp_catalog", LangfusePayloads.truncateChat(
                        tool.getFunction().getDescription(), LangfusePayloads.MCP_CATALOG_RUNES));
            }
        }
        meta.put("tool_names", names);
        return meta;
    }

    /** generation 观测的 output 载荷（tool_calls 键恒在，值可能 null）。 */
    static Map<String, Object> buildGenerationOutput(String content, String reasoningContent,
                                                     String finishReason, List<ToolCall> toolCalls) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("content", content == null ? "" : content);
        output.put("tool_calls", toolCalls);
        output.put("finish_reason", finishReason == null ? "" : finishReason);
        if (reasoningContent != null && !reasoningContent.isEmpty()) {
            output.put("reasoning_content", reasoningContent);
        }
        return output;
    }

    /** 模型参数载荷：只带非零参数；空 → null（属性省略）。 */
    static Map<String, Object> buildModelParams(ChatOptions options) {
        if (options == null) {
            return null;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        if (options.getTemperature() != 0) {
            params.put("temperature", options.getTemperature());
        }
        if (options.getTopP() != 0) {
            params.put("top_p", options.getTopP());
        }
        if (options.completionBudget() > 0) {
            params.put("max_completion_tokens", options.completionBudget());
        }
        if (options.getFrequencyPenalty() != 0) {
            params.put("frequency_penalty", options.getFrequencyPenalty());
        }
        if (options.getPresencePenalty() != 0) {
            params.put("presence_penalty", options.getPresencePenalty());
        }
        if (options.getSeed() != 0) {
            params.put("seed", options.getSeed());
        }
        if (options.getToolChoice() != null && !options.getToolChoice().isEmpty()) {
            params.put("tool_choice", options.getToolChoice());
        }
        // thinking 三态：true/false 记录"要求了什么"；null（调用点没设）时键省略——
        // 出站是否注入 enable_thinking 还取决于模型的 thinking_control 策略，
        // 故此处只反映请求侧意图，但足以在 trace 里一眼看出"漏传"。
        if (options.getThinking() != null) {
            params.put("thinking", options.getThinking());
        }
        return params.isEmpty() ? null : params;
    }
}
