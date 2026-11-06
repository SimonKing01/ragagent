package com.ragagent.session.service;

import static com.ragagent.session.service.QaSupport.TagScope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.contract.agent.AgentLookup;
import com.ragagent.contract.error.BizException;
import com.ragagent.contract.event.EventBusInterface;
import com.ragagent.contract.knowledge.KnowledgeBaseRowView;
import com.ragagent.contract.knowledge.KnowledgeCatalogPort;
import com.ragagent.contract.model.ModelCatalogPort;
import com.ragagent.contract.model.ModelCatalogPort.ModelRowView;
import com.ragagent.contract.pipeline.PipelinePorts;
import com.ragagent.contract.retrieval.SearchResult;
import com.ragagent.contract.retrieval.SearchTarget;
import com.ragagent.contract.tenant.TenantConfigLookup;
import com.ragagent.contract.websearch.WebSearchProviderPort;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.platform.prompt.MessageAttachmentsPrompt;
import com.ragagent.platform.settings.ConversationProperties;
import com.ragagent.platform.web.JsonMappers;
import com.ragagent.session.conversation.SessionLookupScope;
import com.ragagent.session.conversation.SessionService;
import com.ragagent.session.rag.ChatManage;
import com.ragagent.session.rag.EventManager;
import com.ragagent.session.rag.PipelineBuilder;
import com.ragagent.session.rag.PipelineEventType;
import com.ragagent.session.rag.PipelineLog;
import com.ragagent.session.rag.PipelineProgress;
import com.ragagent.session.rag.PipelineProgress.StageProgress;
import com.ragagent.session.rag.SummaryConfig;
import com.ragagent.session.rag.plugin.PluginError;
import com.ragagent.session.runtime.SessionTrace;
import com.ragagent.session.support.PipelineViews;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 知识问答 service 面（chat_pipeline 的调用方）。
 *
 * <p>检索由管线插件执行（{@code PluginSearch} → {@code PipelinePorts} 装配的检索面）；
 * web_fetch/网页抓取在 RAG 路径可用。Langfuse span 为 no-op seam，
 * span 生命周期调用点保留。</p>
 *
 * <p>KnowledgeQA/KnowledgeQAByEvent/SearchKnowledge
 * 三条入口流是单一状态机,解析与降级已拆至 SessionQaResolution/SessionQaFallback。</p>

 */
@Service
public class SessionKnowledgeQaService {

    private static final Logger log = LoggerFactory.getLogger(SessionKnowledgeQaService.class);

    final EventManager eventManager;
    final ConversationProperties cfg;
    final ModelCatalogPort modelCatalog;
    final KnowledgeCatalogPort knowledgeCatalog;
    final PipelinePorts.ModelService pipelineModelService;
    final TenantConfigLookup tenantService;
    final WebSearchProviderPort webSearchProviders;
    /** agent 解析（im 面请求只带 agentId：入口按需补 agentRow）。 */
    final AgentResolver agentResolver;

    /** 解析/降级协作者(构造期装配)。 */
    final SessionQaResolution resolution;
    final SessionQaFallback fallback;
    /** WebSearch/WebFetch 有效参数解析（自本类外提）。 */
    final QaWebSearchParams webSearchParams;

    public SessionKnowledgeQaService(EventManager eventManager,
            ConversationProperties cfg,
            ModelCatalogPort modelCatalog,
            KnowledgeCatalogPort knowledgeCatalog,
            PipelinePorts.ModelService pipelineModelService,
            TenantConfigLookup tenantService,
            WebSearchProviderPort webSearchProviders,
            AgentResolver agentResolver) {
        this.eventManager = eventManager;
        this.cfg = cfg;
        this.modelCatalog = modelCatalog;
        this.knowledgeCatalog = knowledgeCatalog;
        this.pipelineModelService = pipelineModelService;
        this.tenantService = tenantService;
        this.webSearchProviders = webSearchProviders;
        this.agentResolver = agentResolver;
        this.resolution = new SessionQaResolution(this);
        this.fallback = new SessionQaFallback(this);
        this.webSearchParams = new QaWebSearchParams(this);
    }

    // ── seam 委托:实现随 Resolution 协作者(外部消费面不变) ──

    public static boolean isAgentMode(ObjectNode c) {
        return SessionQaResolution.isAgentMode(c);
    }

    public long resolveRetrievalTenantId(QaSupport.QaRequest req) {
        return resolution.resolveRetrievalTenantId(req);
    }

    public String resolveChatModelId(QaSupport.QaRequest req, List<String> knowledgeBaseIds,
            List<String> knowledgeIds) {
        return resolution.resolveChatModelId(req, knowledgeBaseIds, knowledgeIds);
    }

    public SessionQaResolution.MentionScope restrictMentionsToAgentScope(
            AgentLookup.AgentView agent, ObjectNode agentCfg,
            long sessionTenantId, List<String> kbIds, List<String> knowledgeIds) {
        return resolution.restrictMentionsToAgentScope(agent, agentCfg, sessionTenantId, kbIds, knowledgeIds);
    }

    public KnowledgeResolution resolveKnowledgeBases(QaSupport.QaRequest req) {
        return resolution.resolveKnowledgeBases(req);
    }

    public List<SearchTargetView> buildSearchTargets(long tenantId, List<String> knowledgeBaseIds,
            List<String> knowledgeIds, List<TagScope> tagScopes) {
        return resolution.buildSearchTargets(tenantId, knowledgeBaseIds, knowledgeIds, tagScopes);
    }

    public KnowledgeBaseRowView findKnowledgeBase(String kbId) {
        return resolution.findKnowledgeBase(kbId);
    }

    // KnowledgeQA 主流程

    public void knowledgeQA(QaSupport.QaRequest req, EventBusInterface eventBus) {
        agentResolver.resolveInto(req);
        String sessionId = req.session.getId();
        log.info("Knowledge base question answering parameters, session ID: {}, query: {}, webSearchEnabled: {}",
                sessionId, req.query, req.webSearchEnabled);

        // qa.setup span 包住请求装配段（KB/模型解析、检索目标构建、
        // agent 覆盖应用）——补上 trace 开始到首个阶段观测之间的可见空档
        SessionTrace.SpanHandle setupSpan =
                SessionTrace.start(
                                "qa.setup", null,
                                java.util.Map.of("sessionId", sessionId == null ? "" : sessionId));

        // Resolve knowledge bases using shared helper
        KnowledgeResolution kb = resolution.resolveKnowledgeBases(req);

        // Resolve chat model ID using shared helper
        String chatModelId = resolution.resolveChatModelId(req, kb.kbIds, kb.knowledgeIds);

        // Initialize ChatManage defaults from config.yaml
        SummaryConfig summaryConfig = ChatManageFactory.summaryConfig(cfg);
        String fallbackStrategy = cfg.getFallbackStrategy();
        if (fallbackStrategy == null || fallbackStrategy.isEmpty()) {
            fallbackStrategy = "fixed";
            log.info("Fallback strategy not set, using default: {}", fallbackStrategy);
        }

        // Resolve chat model vision capability and VLM model ID for image routing
        boolean chatModelSupportsVision = false;
        String vlmModelId = "";
        if (!chatModelId.isEmpty()) {
            try {
                ModelRowView chatModelInfo = modelCatalog.findModel(chatModelId);
                if (chatModelInfo != null) {
                    chatModelSupportsVision = chatModelInfo.parameters() != null && chatModelInfo.parameters().isSupportsVision();
                }
            } catch (RuntimeException e) {
                // 获取失败 → 保持 false
            }
        }
        if (req.agentConfig != null) {
            vlmModelId = req.agentConfig.path("vlmModelId").asText("");
        }

        // Resolve retrieval tenant scope using shared helper
        long retrievalTenantId = resolution.resolveRetrievalTenantId(req);

        // Build unified search targets（computed once）
        List<SearchTargetView> searchTargets;
        try {
            searchTargets = resolution.buildSearchTargets(retrievalTenantId, kb.kbIds, kb.knowledgeIds, req.tagScopes);
        } catch (RuntimeException e) {
            throw new RuntimeException("build search targets: " + e.getMessage(), e);
        }

        log.info("Creating chat manage object, knowledge base IDs: {}, knowledge IDs: {}, chat model ID: {}, search targets: {}",
                kb.kbIds, kb.knowledgeIds, chatModelId, searchTargets.size());

        ChatManage chatManage = ChatManageFactory.create(req, sessionId, retrievalTenantId, eventBus);
        ChatManageFactory.applyRetrieval(chatManage, kb, searchTargets, cfg);
        ChatManageFactory.applyModel(chatManage, chatModelId, summaryConfig,
                chatModelSupportsVision, vlmModelId);
        ChatManageFactory.applyGeneration(chatManage, cfg, fallbackStrategy);
        ChatManageFactory.applyWeb(chatManage, req, retrievalTenantId, webSearchParams);

        // Apply custom agent overrides
        resolution.applyAgentOverridesToChatManage(req, chatManage);
        chatManage.freeze();

        // Pipeline 选择
        boolean hasKb = hasKnowledgeRetrievalScope(searchTargets, kb.kbIds, kb.knowledgeIds);
        boolean needsRag = hasKb || req.webSearchEnabled;
        boolean hasHistory = chatManage.getMaxRounds() > 0;

        List<PipelineEventType> pipeline;
        if (!needsRag) {
            // Pure chat — no retrieval needed.
            String userContent = req.query;
            if (!req.imageDescription.isEmpty() && !chatModelSupportsVision) {
                userContent += "\n\n[用户上传图片内容]\n" + req.imageDescription;
            }
            if (!req.quotedContext.isEmpty()) {
                userContent += "\n\n" + req.quotedContext;
            }
            if (!req.attachments.isEmpty()) {
                userContent += MessageAttachmentsPrompt.build(
                        PipelineViews.ofAttachments(req.attachments));
            }
            chatManage.getAnswer().setUserContent(userContent);

            pipeline = PipelineBuilder.builder()
                    .addIf(hasHistory, PipelineEventType.LOAD_HISTORY)
                    .add(PipelineEventType.MEMORY_RECALL)
                    .add(PipelineEventType.CHAT_COMPLETION_STREAM)
                    .build();
        } else {
            // RAG — dynamically assembled.
            pipeline = PipelineBuilder.builder()
                    .addIf(hasHistory, PipelineEventType.LOAD_HISTORY)
                    .add(PipelineEventType.MEMORY_RECALL)
                    .add(PipelineEventType.QUERY_UNDERSTAND)
                    .add(PipelineEventType.CHUNK_SEARCH_PARALLEL)
                    .add(PipelineEventType.CHUNK_RERANK)
                    .addIf(req.webSearchEnabled, PipelineEventType.WEB_FETCH)
                    .add(PipelineEventType.CHUNK_MERGE)
                    .add(PipelineEventType.FILTER_TOP_K)
                    .addIf(chatManage.isDataAnalysisEnabled(), PipelineEventType.DATA_ANALYSIS)
                    .add(PipelineEventType.INTO_CHAT_MESSAGE)
                    .add(PipelineEventType.CHAT_COMPLETION_STREAM)
                    .build();
        }

        log.info("Assembled pipeline ({} stages), hasKB={}, webSearch={}, history={}",
                pipeline.size(), hasKb, req.webSearchEnabled, hasHistory);

        // 进入 QA 事件处理前打上「按会话属主租户查」标记——管线内的会话/
        // 消息查询由此走**租户范围**（共享 agent 场景下当前主体不是属主，带 user 范围会查不到）。
        // 清理在请求收尾处（KnowledgeQaController 的 TenantContext.clear() 旁）。
        SessionLookupScope.mark();

        // setup span 收尾（stages / KB 列表 / 检索目标数）
        java.util.Map<String, Object> setupOutput = new java.util.LinkedHashMap<>();
        setupOutput.put("stages", pipeline.size());
        setupOutput.put("knowledge_base_ids", kb.kbIds);
        setupOutput.put("search_targets", searchTargets.size());
        setupSpan.finish(setupOutput, null, null);

        // 会话租户设定与 sessionId 传播由 TenantContext 承担；
        // 取消探针是线程级的：STOP 置 streamCtx.cancelled 后，各阶段循环能读到并退出
        CANCEL_PROBE.set(req.cancellationProbe);
        try {
            knowledgeQAByEvent(chatManage, pipeline);
        } finally {
            CANCEL_PROBE.remove();
        }
        log.info("Knowledge base question answering initiated");
    }

    // KnowledgeQAByEvent

    public void knowledgeQAByEvent(ChatManage chatManage, List<PipelineEventType> eventList) {
        log.info("Start processing knowledge base question answering through events");
        log.info("Knowledge base question answering parameters, session ID: {}, query: {}",
                chatManage.getSessionId(), chatManage.getQuery());

        List<PipelineEventType> methods = new ArrayList<>(eventList);
        log.info("Trigger event list: {}", methods.stream().map(PipelineEventType::key).toList());

        long pipelineStart = System.currentTimeMillis();
        PipelineEventType lastRetrievalStage = PipelineProgress.lastConsolidatedRetrievalStage(eventList, chatManage);
        StageProgress retrievalProgress = null;
        long retrievalStart = 0;
        StageProgress understandProgress = null;
        long understandStart = 0;
        for (PipelineEventType eventType : eventList) {
            long stageStart = System.currentTimeMillis();
            // 阶段 span 包住本阶段；CHAT_COMPLETION_STREAM 跳过——
            // 该阶段的 chat.completion.stream generation 已覆盖完整时长，再套一层
            // 会产出"视觉上超出父节点"的子观测
            SessionTrace.SpanHandle stageSpan = null;
            if (eventType != PipelineEventType.CHAT_COMPLETION_STREAM) {
                stageSpan = SessionTrace.start(
                                "pipeline." + eventType.key(), null,
                                java.util.Map.of("event_type", eventType.key(),
                                        "session_id", chatManage.getSessionId() == null
                                                ? "" : chatManage.getSessionId()));
            }
            if (eventType == PipelineEventType.QUERY_UNDERSTAND
                    && PipelineProgress.shouldEmitQueryUnderstandProgress(chatManage)) {
                understandStart = stageStart;
                understandProgress = PipelineProgress.beginQueryUnderstandProgress(chatManage);
            }
            if (PipelineProgress.isConsolidatedRetrievalStage(eventType, chatManage) && retrievalProgress == null) {
                retrievalStart = stageStart;
                retrievalProgress = PipelineProgress.beginRetrievalProgress(chatManage);
            }
            // Emit references before answer streaming（complete 关流前必达）
            if (eventType == PipelineEventType.CHAT_COMPLETION_STREAM) {
                SessionQaFallback.emitKnowledgeReferencesEvent(chatManage);
            }
            PluginError err = eventManager.trigger(eventType, chatManage);
            if (understandProgress != null && eventType == PipelineEventType.QUERY_UNDERSTAND) {
                PipelineProgress.endQueryUnderstandProgress(chatManage, understandProgress,
                        understandStart, err);
                understandProgress = null;
            }
            if (retrievalProgress != null
                    && PipelineProgress.shouldCloseRetrievalProgress(eventType, lastRetrievalStage, err)) {
                PipelineProgress.endRetrievalProgress(chatManage, retrievalProgress,
                        retrievalStart, err);
                retrievalProgress = null;
            }
            long stageDuration = System.currentTimeMillis() - stageStart;

            // 阶段 span 收尾（输出时长；SEARCH_NOTHING 不算错误）
            if (stageSpan != null) {
                String stageErr = err != null && err != PluginError.SEARCH_NOTHING
                        ? (err.cause() != null ? err.cause().getMessage() : err.description) : null;
                stageSpan.finish(java.util.Map.of("durationMs", stageDuration), null, stageErr);
            }

            // 用户停止：先于"检索无结果"判定
            if (cancelled()) {
                PipelineLog.warn("Pipeline", "stage_cancelled", Map.of(
                        "event", eventType.key(), "duration_ms", stageDuration, "reason", "context canceled"));
                throw new RuntimeException("context canceled");
            }

            if (err == PluginError.SEARCH_NOTHING) {
                PipelineLog.warn("Pipeline", "stage_fallback", Map.of(
                        "event", eventType, "duration_ms", stageDuration,
                        "reason", "search_nothing", "strategy", chatManage.getFallbackStrategy()));
                fallback.handleFallbackResponse(chatManage);
                return;
            }

            if (err != null) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("event", eventType);
                f.put("durationMs", stageDuration);
                f.put("error_type", err.errorType);
                f.put("description", err.description);
                PipelineLog.error("Pipeline", "stage_failed", f);
                throw err.cause() != null ? new RuntimeException(err.cause()) : new RuntimeException(err.description);
            }

            PipelineLog.info("Pipeline", "stage_complete", Map.of(
                    "event", eventType, "duration_ms", stageDuration));
        }

        Map<String, Object> f = new LinkedHashMap<>();
        f.put("sessionId", chatManage.getSessionId());
        f.put("total_stages", eventList.size());
        f.put("totalDurationMs", System.currentTimeMillis() - pipelineStart);
        PipelineLog.info("Pipeline", "all_stages_complete", f);
    }

    /** 当前执行线程的取消探针（来自 QaRequest；STOP 置位后返回非 null）。 */
    private static final ThreadLocal<java.util.function.Supplier<String>> CANCEL_PROBE = new ThreadLocal<>();

    private static boolean cancelled() {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        java.util.function.Supplier<String> probe = CANCEL_PROBE.get();
        return probe != null && probe.get() != null;
    }

    // SearchKnowledge

    public List<SearchResult> searchKnowledge(List<String> knowledgeBaseIds, List<String> knowledgeIds,
            List<TagScope> tagScopes, String query) {
        log.info("Start knowledge base search without LLM summary");
        Long tenantIdBoxed = TenantContext.currentTenantId();
        if (tenantIdBoxed == null) {
            throw new RuntimeException("workspace ID not found in context");
        }
        long tenantId = tenantIdBoxed;

        List<SearchTargetView> searchTargets;
        try {
            searchTargets = resolution.buildSearchTargets(tenantId, knowledgeBaseIds, knowledgeIds, tagScopes);
        } catch (RuntimeException e) {
            throw new RuntimeException("build search targets: " + e.getMessage(), e);
        }

        if (searchTargets.isEmpty()) {
            log.warn("No search targets available, returning empty results");
            return new ArrayList<>();
        }

        // Create default retrieval parameters — prefer tenant RetrievalConfig
        String userId = SessionService.sessionUserIdForLookup();
        RetrievalConfigView rc = new RetrievalConfigView(null);
        try {
            JsonNode retrieval = JsonMappers.toJsonNode(tenantService.retrievalConfig(tenantId));
            if (retrieval != null) {
                rc = new RetrievalConfigView(retrieval);
            }
        } catch (RuntimeException e) {
            // 获取失败 → 保持 null（由 GetEffective* 兜底）
        }

        ChatManage chatManage = new ChatManage();
        chatManage.setQuery(query);
        chatManage.setUserId(userId);
        // 插件从 ChatManage 取租户（无隐式上下文；与 QA 路径同源
        // retrievalTenantId 的同款赋值）——漏了它 Merge 阶段 faq_enrich/expand 全跳过，
        // knowledge-search 响应的 content 就少了前后文扩块，与既定输出契约不符。
        chatManage.setTenantId(tenantId);
        chatManage.setKnowledgeBaseIds(knowledgeBaseIds);
        chatManage.setKnowledgeIds(knowledgeIds);
        chatManage.setSearchTargets(SearchTargetView.toPipeline(searchTargets));
        chatManage.setMaxRounds(cfg.getMaxRounds());
        chatManage.setEmbeddingTopK(rc.embeddingTopK());
        chatManage.setVectorThreshold(rc.vectorThreshold());
        chatManage.setKeywordThreshold(rc.keywordThreshold());
        chatManage.setRerankTopK(rc.rerankTopK());
        chatManage.setRerankThreshold(rc.rerankThreshold());
        chatManage.getQueryPlan().setRewriteQuery(query);

        // Use rerank model from RetrievalConfig if set, otherwise first available
        if (rc.rerankModelId() != null && !rc.rerankModelId().isEmpty()) {
            chatManage.setRerankModelId(rc.rerankModelId());
        } else {
            try {
                for (ModelRowView model : modelCatalog.listModels()) {
                    if (model == null) {
                        continue;
                    }
                    if ("Rerank".equals(model.type())) {
                        chatManage.setRerankModelId(model.id());
                        break;
                    }
                }
            } catch (RuntimeException e) {
                log.error("Failed to get models", e);
                throw e;
            }
        }

        chatManage.freeze();
        List<PipelineEventType> searchEvents = List.of(
                PipelineEventType.CHUNK_SEARCH,
                PipelineEventType.CHUNK_RERANK,
                PipelineEventType.CHUNK_MERGE,
                PipelineEventType.FILTER_TOP_K);

        log.info("Trigger search event list: {}", searchEvents.stream().map(PipelineEventType::key).toList());

        for (PipelineEventType event : searchEvents) {
            log.info("Starting to trigger search event: {}", event.key());
            // search_knowledge 流的阶段 span（恒开，含 SEARCH_NOTHING）
            SessionTrace.SpanHandle stageSpan =
                    SessionTrace.start(
                                    "pipeline." + event.key(), null,
                                    java.util.Map.of("event_type", event.key(),
                                            "flow", "search_knowledge"));
            PluginError err = eventManager.trigger(event, chatManage);

            // SEARCH_NOTHING 不算错误；其余带底层错误消息收尾
            String stageErr = err != null && err != PluginError.SEARCH_NOTHING
                    ? (err.cause() != null ? err.cause().getMessage() : err.description) : null;
            stageSpan.finish(null, null, stageErr);

            if (err == PluginError.SEARCH_NOTHING) {
                log.warn("Event {} triggered, search result is empty", event);
                return new ArrayList<>();
            }
            if (err != null) {
                log.error("Event triggering failed, event: {}, error type: {}, description: {}, error: {}",
                        event, err.errorType, err.description, err.cause());
                // 管线错误 → 500 内部错误，文案取底层错误消息
                String msg = err.cause() != null ? err.cause().getMessage() : err.description;
                throw BizException.internal(msg);
            }
            log.info("Event {} triggered successfully", event);
        }

        log.info("Knowledge base search completed, found {} results",
                chatManage.getMerged().getResults() == null ? 0 : chatManage.getMerged().getResults().size());
        return chatManage.getMerged().getResults() == null ? new ArrayList<>() : chatManage.getMerged().getResults();
    }

    // 共享 QA helpers

    /**
     * 判定骨架（见 {@link #callerCanReadKb}）：
     * 作用域租户为 0 / 属主租户为 0 ⇒ 否；同租户 ⇒ 是；否则交给共享判定。
     * 抽成静态纯函数以便脱离 Spring 上下文做回归（API-key 作用域与共享判定在调用方装配）。
     */
    static boolean kbReadableByCaller(Long scopeTenantId, long ownerTenantId,
                                      java.util.function.BooleanSupplier sharedPermitsViewer) {
        if (scopeTenantId == null || scopeTenantId == 0 || ownerTenantId == 0) {
            return false;
        }
        if (scopeTenantId == ownerTenantId) {
            return true;
        }
        return sharedPermitsViewer != null && sharedPermitsViewer.getAsBoolean();
    }

    // substringByCodePoints 仍留在门面：modelcontext/ModelOutput 也在用（跨域共享的小工具）
    static String substringByCodePoints(String s, int max) {
        int i = 0;
        int cp = 0;
        while (i < s.length() && cp < max) {
            int c = s.codePointAt(i);
            i += Character.charCount(c);
            cp++;
        }
        return s.substring(0, i);
    }




    // 纯函数族

    public static Map<String, List<String>> mergeTagScopesByKb(List<TagScope> scopes) {
        Map<String, List<String>> byKb = new LinkedHashMap<>();
        Map<String, Set<String>> seen = new LinkedHashMap<>();
        if (scopes == null) {
            return byKb;
        }
        for (TagScope scope : scopes) {
            if (scope.knowledgeBaseId.isEmpty()) {
                continue;
            }
            Set<String> seenTags = seen.computeIfAbsent(scope.knowledgeBaseId, k -> new LinkedHashSet<>());
            for (String tagId : scope.tagIds) {
                if (tagId.isEmpty() || seenTags.contains(tagId)) {
                    continue;
                }
                seenTags.add(tagId);
                byKb.computeIfAbsent(scope.knowledgeBaseId, k -> new ArrayList<>()).add(tagId);
            }
        }
        return byKb;
    }

    public static List<String> uniqueNonEmptyStrings(List<String> values) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        if (values == null) {
            return out;
        }
        for (String value : values) {
            if (value == null || value.isEmpty() || seen.contains(value)) {
                continue;
            }
            seen.add(value);
            out.add(value);
        }
        return out;
    }

    public static List<String> intersectStrings(List<String> left, List<String> right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) {
            return new ArrayList<>();
        }
        Set<String> rightSet = new LinkedHashSet<>(right);
        List<String> out = new ArrayList<>();
        for (String value : left) {
            if (rightSet.contains(value)) {
                out.add(value);
            }
        }
        return out;
    }

    /** 是否有知识检索范围（以 target 视图判）。 */
    public static boolean hasKnowledgeRetrievalScope(List<SearchTargetView> targets,
            List<String> kbIds, List<String> knowledgeIds) {
        if (!kbIds.isEmpty() || !knowledgeIds.isEmpty()) {
            return true;
        }
        for (SearchTargetView t : targets) {
            if (!t.tagIds.isEmpty() || !t.knowledgeIds.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    static List<String> stringListOf(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode n : arr) {
                if (n.isTextual()) {
                    out.add(n.asText());
                }
            }
        }
        return out;
    }

    static long requireTenantId() {
        Long tid = TenantContext.currentTenantId();
        if (tid == null) {
            throw new IllegalStateException("tenant ID not found in context");
        }
        return tid;
    }

    /** 当前语言（dev 缺省 en-US）。 */
    static String currentLanguage() {
        return "en-US";
    }

    // 轻量视图

    /** resolveKnowledgeBases 的二元返回。 */
    public record KnowledgeResolution(List<String> kbIds, List<String> knowledgeIds) {}

    /** SearchTarget 的 service 面视图（管线面经 toPipeline 转换）。 */
    public static final class SearchTargetView {
        public String type = "";
        public String knowledgeBaseId = "";
        public long tenantId;
        public List<String> knowledgeIds = new ArrayList<>();
        public List<String> tagIds = new ArrayList<>();
        public List<String> scopeTagIds = new ArrayList<>();
        public boolean disableRecallThresholds;

        public List<SearchTarget> toPipeline() {
            return toPipelineList();
        }

        public List<SearchTarget> toPipelineList() {
            List<SearchTarget> out = new ArrayList<>();
            out.add(asPipelineTarget());
            return out;
        }

        public SearchTarget asPipelineTarget() {
            return new SearchTarget(type, knowledgeBaseId, tenantId,
                    knowledgeIds, tagIds, scopeTagIds, disableRecallThresholds);
        }

        public static List<SearchTarget> toPipeline(List<SearchTargetView> views) {
            List<SearchTarget> out = new ArrayList<>();
            if (views == null) {
                return out;
            }
            for (SearchTargetView v : views) {
                out.add(v.asPipelineTarget());
            }
            return out;
        }
    }

    /** RetrievalConfig 的读取视图（GetEffective* 兜底）。 */
    private record RetrievalConfigView(JsonNode raw) {
        String rerankModelId() { return raw == null ? null : raw.path("rerankModelId").asText(null); }
        int embeddingTopK() { return effInt("embeddingTopK", 30); }
        double vectorThreshold() { return effDouble("vectorThreshold", 0.2); }
        double keywordThreshold() { return effDouble("keywordThreshold", 0.3); }
        int rerankTopK() { return effInt("rerankTopK", 30); }
        double rerankThreshold() { return effDouble("rerankThreshold", 0.3); }
        private int effInt(String f, int d) { return raw == null || raw.path(f).asInt(0) <= 0 ? d : raw.path(f).asInt(); }
        private double effDouble(String f, double d) {
            return raw == null || raw.path(f).asDouble(-1) < 0 ? d : raw.path(f).asDouble();
        }
    }
}
