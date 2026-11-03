package com.ragagent.session.service;


import com.ragagent.contract.event.Event;
import com.ragagent.contract.event.EventBusInterface;
import com.ragagent.contract.event.payload.AgentFinalAnswerData;
import com.ragagent.contract.knowledge.KnowledgeRowView;
import com.ragagent.contract.model.ChatMessage;
import com.ragagent.contract.model.ChatOptions;
import com.ragagent.contract.model.ChatResponse;
import com.ragagent.contract.model.ChatStream;
import com.ragagent.contract.model.LlmChatClient;
import com.ragagent.contract.model.ResponseType;
import com.ragagent.contract.model.StreamResponse;
import com.ragagent.modelcontext.Registry;
import com.ragagent.platform.access.TenantContextSnapshot;
import com.ragagent.platform.access.TenantScope;
import com.ragagent.platform.event.EventIds;
import com.ragagent.platform.event.EventType;
import com.ragagent.platform.prompt.AgentPromptPlaceholders;
import com.ragagent.platform.prompt.PromptComposer;
import com.ragagent.session.dto.AgentReferencesData;
import com.ragagent.session.rag.ChatManage;
import com.ragagent.session.rag.PipelineCommon;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 知识问答降级协作者:固定文案兜底与模型兜底的消息准备与响应。
 *
 * <p>持有 {@link SessionKnowledgeQaService} 回引以访问其依赖与共享 helpers;本类不得独立实例化。</p>
 */
final class SessionQaFallback {

    private static final Logger log = LoggerFactory.getLogger(SessionQaFallback.class);

    private final SessionKnowledgeQaService service;

    SessionQaFallback(SessionKnowledgeQaService service) {
        this.service = service;
    }

    void handleFallbackResponse(ChatManage chatManage) {
        if ("model".equals(chatManage.getFallbackStrategy())) {
            handleModelFallback(chatManage);
        } else {
            handleFixedFallback(chatManage);
        }
    }

    void handleFixedFallback(ChatManage chatManage) {
        String fallbackContent = chatManage.getFallbackResponse();
        ChatResponse response = new ChatResponse();
        response.setContent(fallbackContent);
        chatManage.getAnswer().setChatResponse(response);
        emitFallbackAnswer(chatManage, fallbackContent);
    }

    void handleModelFallback(ChatManage chatManage) {
        if (chatManage.getFallbackPrompt().isEmpty()) {
            log.warn("Fallback strategy is 'model' but FallbackPrompt is empty, falling back to fixed response");
            handleFixedFallback(chatManage);
            return;
        }
        String promptContent = renderFallbackPrompt(chatManage);
        EventBusInterface eventBus = chatManage.getRuntime().getEventBus();
        if (eventBus == null) {
            log.info("EventBus not available for streaming fallback, falling back to fixed response");
            handleFixedFallback(chatManage);
            return;
        }
        LlmChatClient chatModel;
        try {
            chatModel = service.pipelineModelService.getChatModel(chatManage.getChatModelId());
        } catch (RuntimeException e) {
            log.error("Failed to get chat model for fallback, falling back to fixed response", e);
            handleFixedFallback(chatManage);
            return;
        }
        ChatOptions opt = new ChatOptions();
        opt.setTemperature(chatManage.getSummaryConfig().getTemperature());
        opt.setMaxCompletionTokens(chatManage.getSummaryConfig().getMaxCompletionTokens());
        opt.setThinking(Boolean.FALSE);
        var prepared = prepareFallbackMessages(chatManage, promptContent);
        ChatStream responseQueue;
        try {
            responseQueue = chatModel.chatStream(prepared.messages(), opt);
        } catch (RuntimeException e) {
            log.error("Failed to start streaming fallback response, falling back to fixed response", e);
            handleFixedFallback(chatManage);
            return;
        }
        if (responseQueue == null) {
            log.error("Chat stream returned nil channel, falling back to fixed response");
            handleFixedFallback(chatManage);
            return;
        }
        // 纪律 #1（A5）：消费线程跑在独立虚拟线程上——本线程 capture、消费线程 replay/clear，
        // 否则索引/追问的 owner 推导成 ""（SessionNotFound）。
        TenantContextSnapshot identity = TenantContextSnapshot.capture();
        Thread.ofVirtual().start(() -> TenantScope.run(identity,
                () -> consumeFallbackStream(chatManage, responseQueue, prepared.registry())));
    }

    record FallbackPrepared(List<ChatMessage> messages, Registry registry) {}

    /** 组装 fallback 消息（prepare + build 两步）。 */
    FallbackPrepared prepareFallbackMessages(ChatManage chatManage, String promptContent) {
        List<ChatMessage> messages = new ArrayList<>();
        if (!promptContent.trim().isEmpty()) {
            ChatMessage system = new ChatMessage();
            system.setRole("system");
            system.setContent(promptContent + PromptComposer.SOURCES_AND_OUTPUT_SUFFIX);
            messages.add(system);
        }
        PipelineCommon.appendHistoryMessages(messages, chatManage.getHistory());
        String query = chatManage.getQuery();
        String rq = chatManage.getQueryPlan().getRewriteQuery() == null ? "" : chatManage.getQueryPlan().getRewriteQuery().trim();
        if (!rq.isEmpty()) {
            query = rq;
        }
        ChatMessage userMsg = new ChatMessage();
        userMsg.setRole("user");
        userMsg.setContent(query);
        if (chatManage.isChatModelSupportsVision() && chatManage.getImages() != null) {
            userMsg.setImages(chatManage.getImages());
        }
        messages.add(userMsg);
        boolean citationsEnabled = chatManage.citationsEnabled();
        Registry registry = new Registry(citationsEnabled);
        if (!messages.isEmpty() && "system".equals(messages.get(0).getRole())) {
            ChatMessage first = messages.get(0);
            first.setContent(PromptComposer.appendProtocol(first.getContent(), registry.protocolPrompt()));
        } else {
            ChatMessage system = new ChatMessage();
            system.setRole("system");
            system.setContent(registry.protocolPrompt().trim());
            messages.add(0, system);
        }
        return new FallbackPrepared(registry.encodeMessages(messages), registry);
    }

    // 兜底流的渲染与发射（自门面外提：这些方法的唯一调用方本来就是本类）

    // fallback

    /** 渲染 fallback prompt。 */
    String renderFallbackPrompt(ChatManage chatManage) {
        String query = chatManage.getQuery();
        String rq = chatManage.getQueryPlan().getRewriteQuery() == null ? "" : chatManage.getQueryPlan().getRewriteQuery().trim();
        if (!rq.isEmpty()) {
            query = rq;
        }
        String kbDocuments = buildKbDocumentListing(chatManage);
        String result = AgentPromptPlaceholders.renderPromptPlaceholders(chatManage.getFallbackPrompt(), Map.of(
                "query", query,
                "language", chatManage.getLanguage(),
                "kb_documents", kbDocuments));
        if (!chatManage.getQueryPlan().getImageDescription().isEmpty() && !chatManage.isChatModelSupportsVision()) {
            result += "\n\n[用户上传图片内容]\n" + chatManage.getQueryPlan().getImageDescription();
        }
        if (!chatManage.getQueryPlan().getQuotedContext().isEmpty()) {
            result += "\n\n" + chatManage.getQueryPlan().getQuotedContext();
        }
        return result;
    }

    /** 组装 KB 文档清单。 */
    private String buildKbDocumentListing(ChatManage chatManage) {
        Set<String> kbIds = new LinkedHashSet<>();
        if (chatManage.getSearchTargets() != null) {
            for (var t : chatManage.getSearchTargets()) {
                kbIds.add(t.knowledgeBaseId());
            }
        }
        kbIds.addAll(chatManage.getKnowledgeBaseIds());
        if (kbIds.isEmpty()) {
            return "";
        }
        final int maxDocuments = 50;
        StringBuilder b = new StringBuilder();
        int total = 0;
        for (String kbId : kbIds) {
            if (total >= maxDocuments) {
                break;
            }
            List<KnowledgeRowView> knowledges;
            try {
                knowledges = service.knowledgeCatalog.listKnowledge(kbId, 1, 10000, null, null, null, null, false).items();
            } catch (RuntimeException e) {
                log.warn("buildKBDocumentListing: failed to list knowledge for KB {}", kbId, e);
                continue;
            }
            for (KnowledgeRowView k : knowledges) {
                if (total >= maxDocuments) {
                    break;
                }
                if (!"enabled".equals(k.getEnableStatus())) {
                    continue;
                }
                String title = k.getTitle();
                if (title == null || title.isEmpty()) {
                    title = k.getFileName();
                }
                if (title == null || title.isEmpty()) {
                    continue;
                }
                b.append("- ").append(title);
                if (k.getFileType() != null && !k.getFileType().isEmpty()) {
                    b.append(" (").append(k.getFileType()).append(")");
                }
                if (k.getDescription() != null && !k.getDescription().isEmpty()) {
                    String desc = k.getDescription();
                    if (desc.codePointCount(0, desc.length()) > 100) {
                        desc = SessionKnowledgeQaService.substringByCodePoints(desc, 100) + "...";
                    }
                    b.append(": ").append(desc);
                }
                b.append("\n");
                total++;
            }
        }
        if (b.length() == 0) {
            return "";
        }
        if (total >= maxDocuments) {
            b.append(String.format("... (showing first %d documents)%n", maxDocuments));
        }
        return b.toString();
    }

    /** 消费 fallback 流。 */
    void consumeFallbackStream(ChatManage chatManage,
            ChatStream responseChan, Registry modelContext) {
        String fallbackId = EventIds.generateEventId("fallback");
        EventBusInterface eventBus = chatManage.getRuntime().getEventBus();
        StringBuilder finalContent = new StringBuilder();
        boolean streamCompleted = false;
        var decoder = modelContext.streamDecoder();
        // 生产者异常/中断时不投终态元素（RemoteApiChat 的 catch-return 路径）——
        // 无上限的 poll 空转每次回退泄漏一个自旋虚拟线程。连续空读超时即收束
        // （主流路径 takeQuietly 120s 同款兜底思想）。
        int emptyPolls = 0;

        while (true) {
            StreamResponse response;
            try {
                response = responseChan.poll(1, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (response == null) {
                if (++emptyPolls >= 120) {
                    log.warn("fallback stream produced no terminal frame after {}s, giving up",
                            emptyPolls);
                    break;
                }
                continue; // Java 无 channel 关闭：done 收束约定由生产者保证
            }
            emptyPolls = 0;
            if (response.getResponseType() == ResponseType.ANSWER) {
                String content = decoder.feed(response.getContent());
                if (response.isDone()) {
                    content += decoder.flush();
                }
                finalContent.append(content);
                Event evt = new Event();
                evt.setId(fallbackId);
                evt.setType(EventType.EVENT_AGENT_FINAL_ANSWER);
                evt.setSessionId(chatManage.getSessionId());
                AgentFinalAnswerData data = new AgentFinalAnswerData(content, response.isDone(), true);
                evt.setData(data);
                try {
                    eventBus.emit(evt);
                } catch (RuntimeException e) {
                    log.error("Failed to emit fallback answer chunk event", e);
                }
                if (response.isDone()) {
                    ChatResponse cr = new ChatResponse();
                    cr.setContent(finalContent.toString());
                    chatManage.getAnswer().setChatResponse(cr);
                    streamCompleted = true;
                    log.info("Fallback streaming response completed");
                    break;
                }
            }
        }
        if (!streamCompleted) {
            // A3：提前收束（中断 / 120s 空转）⇒ 关流，终止上游 provider 调用与 permit。
            responseChan.close();
            log.warn("Fallback stream closed without completion, emitting final event with fixed response");
            emitFallbackAnswer(chatManage, chatManage.getFallbackResponse());
        }
    }

    /** 发送 knowledge_references 事件。 */
    static void emitKnowledgeReferencesEvent(ChatManage chatManage) {
        if (chatManage == null || chatManage.getRuntime().getEventBus() == null
                || chatManage.getMerged().getResults() == null || chatManage.getMerged().getResults().isEmpty()) {
            return;
        }
        log.info("Emitting references event with {} results (pre-answer)", chatManage.getMerged().getResults().size());
        Event evt = new Event();
        evt.setId(EventIds.generateEventId("references"));
        evt.setType(EventType.EVENT_AGENT_REFERENCES);
        evt.setSessionId(chatManage.getSessionId());
        evt.setData(new AgentReferencesData(chatManage.getMerged().getResults(), 0));
        try {
            chatManage.getRuntime().getEventBus().emit(evt);
        } catch (RuntimeException e) {
            log.error("Failed to emit references event", e);
        }
    }

    /** 发送 fallback 答案。 */
    void emitFallbackAnswer(ChatManage chatManage, String content) {
        EventBusInterface eventBus = chatManage.getRuntime().getEventBus();
        if (eventBus == null) {
            return;
        }
        if (!chatManage.citationsEnabled()) {
            Registry registry = new Registry(false);
            content = registry.decodeOutputText(content);
        }
        String fallbackId = EventIds.generateEventId("fallback");
        Event evt = new Event();
        evt.setId(fallbackId);
        evt.setType(EventType.EVENT_AGENT_FINAL_ANSWER);
        evt.setSessionId(chatManage.getSessionId());
        evt.setData(new AgentFinalAnswerData(content, true, true));
        try {
            eventBus.emit(evt);
            log.info("Fallback answer event emitted successfully");
        } catch (RuntimeException e) {
            log.error("Failed to emit fallback answer event", e);
        }
    }
}
