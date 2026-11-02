package com.ragagent.session.service;

import com.ragagent.agentmanagement.service.config.AgentConfigJson;
import com.ragagent.contract.agent.AgentBudgets;
import com.ragagent.contract.agent.AgentEngineFactory;
import com.ragagent.contract.agent.AgentEngineHandle;
import com.ragagent.contract.agent.AgentExecutionException;
import com.ragagent.contract.event.Event;
import com.ragagent.contract.event.EventBusInterface;
import com.ragagent.contract.event.payload.ErrorData;
import com.ragagent.contract.knowledge.KnowledgeCatalogPort;
import com.ragagent.contract.model.ChatMessage;
import com.ragagent.contract.model.LlmChatClient;
import com.ragagent.contract.model.ModelCatalogPort;
import com.ragagent.contract.model.ModelRuntime;
import com.ragagent.contract.model.Reranker;
import com.ragagent.contract.storage.ResourceCatalogPort;
import com.ragagent.memory.api.MemoryAccess;
import com.ragagent.platform.event.EventType;
import com.ragagent.platform.prompt.MessageAttachmentsPrompt;
import com.ragagent.session.conversation.MessageService;
import com.ragagent.session.dto.MemoryRecalledData;
import com.ragagent.session.dto.PipelineUsedMemoryView;
import com.ragagent.session.runtime.SessionModels;
import com.ragagent.session.support.PipelineViews;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * agent 问答 service 面。
 *
 * <p>装配边界：
 * MCP 目录随 {@code registerMcpTools} 接线；
 * sandbox/browser/skills 的生产接线在 dev 部署（无 docker、无 browser 集成）走
 * 未启用分支——工具注册的硬门控逐条保留；
 * 检索工具族（knowledge_search 等）在检索执行面缺失时注册同样无产出，
 * 因此 dev 路径注册的核心是 thinking/todo_write/工具白名单可达集。</p>
 */
@Service
public class SessionAgentQaService {

    private static final Logger log = LoggerFactory.getLogger(SessionAgentQaService.class);

    /** agent 引擎的最大迭代次数上限。 */
    static final int MAX_ITERATIONS = 100;

    static final int AGENT_HISTORY_FETCH_MULTIPLIER = 2;
    static final int AGENT_HISTORY_FETCH_MIN = 20;

    private final ModelCatalogPort modelCatalog;
    private final MemoryAccess memoryService;
    private final SessionKnowledgeQaService knowledgeQa;

    /** 历史/消息装配簇。 */
    private final AgentHistoryAssembler historyAssembler;

    /** 配置装配簇。 */
    private final AgentConfigAssembler configAssembler;

    /** 引擎/工具装配簇。 */
    private final AgentEngineAssembler engineAssembler;


    /** 模型运行时（chat/rerank 客户端的构造归口）。 */
    private final ModelRuntime modelRuntime;
    /** agent 解析（im 面请求只带 agentId：入口按需补 agentRow）。 */
    private final AgentResolver agentResolver;

    public SessionAgentQaService(MessageService messageService,
            ModelCatalogPort modelCatalog,
            MemoryAccess memoryService,
            SessionKnowledgeQaService knowledgeQa,
            AgentToolBackends toolBackends,
            ResourceCatalogPort resourceCatalog,
            javax.sql.DataSource dataSource,
            ArtifactCollectorWiring artifactCollectorWiring,
            KnowledgeCatalogPort knowledgeService,
            ModelRuntime modelRuntime,
            AgentEngineFactory engineFactory,
            VlmDescriberWiring vlmDescriberWiring,
            AgentResolver agentResolver) {
        this.agentResolver = agentResolver;
        this.modelRuntime = modelRuntime;
        this.modelCatalog = modelCatalog;
        this.memoryService = memoryService;
        this.knowledgeQa = knowledgeQa;
        this.historyAssembler = new AgentHistoryAssembler(messageService);
        this.configAssembler = new AgentConfigAssembler(knowledgeQa);
        this.engineAssembler =
                new AgentEngineAssembler(
                        memoryService,
                        knowledgeQa,
                        toolBackends,
                        artifactCollectorWiring,
                        knowledgeService,
                        engineFactory,
                        resourceCatalog,
                        dataSource,
                        vlmDescriberWiring);
    }

    // AgentQA

    /**
     * AgentQA 单轮：解析 agent → 模型面 → 历史 → 建引擎 → 记忆/转向接缝 → 查询组装 → 执行。
     *
     * <p>七步各自独立成方法：单看本方法能读出整条链路顺序，细节按需下钻。</p>
     */
    public TurnLifecycle.State agentQA(QaSupport.QaRequest req, EventBusInterface eventBus) {
        agentResolver.resolveInto(req);
        String sessionId = req.session.getId();
        if (req.agentConfig == null) {
            log.warn("Custom agent not provided for session: {}", sessionId);
            throw new RuntimeException("custom agent configuration is required for agent QA");
        }

        long agentTenantId = knowledgeQa.resolveRetrievalTenantId(req);
        log.info("Start agent-based question answering, session ID: {}, agent tenant ID: {}, query: {}",
                sessionId, agentTenantId, req.query);

        // 补默认值（config 树在 parseQARequest 已跑一遍，这里再钉一次）
        AgentConfigJson.ensureDefaults(req.agentConfig);
        QaAgentConfig agentConfig = configAssembler.buildAgentConfig(req, agentTenantId);

        AgentModels models = applyModelConfig(req, agentConfig);
        List<ChatMessage> llmContext = loadLlmContext(agentConfig, sessionId);
        AgentEngineHandle engine = engineAssembler.createAgentEngine(agentConfig,
                models.chatModel(), models.rerankModel(), eventBus, sessionId, req.assistantMessageId);
        attachMemory(engine, req, sessionId, eventBus);
        attachSteerAndCancel(engine, req);

        AgentQuery query = buildAgentQuery(req, models.supportsVision());
        return executeAgent(engine, req, sessionId, query, llmContext, eventBus)
                ? TurnLifecycle.State.DONE : TurnLifecycle.State.FAILED;
    }

    /** 单轮 agent 的模型面：chat 模型 + 可选 rerank + 是否支持视觉。 */
    record AgentModels(LlmChatClient chatModel, Reranker rerankModel, boolean supportsVision) {
    }

    /** 组装后的 agent 查询：文本（含图片描述/引用/附件）+ 图片 URL 列表。 */
    record AgentQuery(String text, List<String> imageUrls) {
    }

    /** VLM 覆盖、chat 模型解析、上下文窗口、按需 rerank。 */
    private AgentModels applyModelConfig(QaSupport.QaRequest req, QaAgentConfig agentConfig) {
        String vlm = req.agentConfig.path("vlmModelId").asText("");
        if (!vlm.isEmpty()) {
            agentConfig.setVlmModelId(vlm);
        }

        String effectiveModelId = knowledgeQa.resolveChatModelId(req, agentConfig.getKnowledgeBases(),
                agentConfig.getKnowledgeIds());
        if (effectiveModelId.isEmpty()) {
            throw new RuntimeException("summary model (model_id) is not configured in custom agent settings");
        }
        LlmChatClient summaryModel = chatModel(effectiveModelId);

        boolean supportsVision = false;
        int modelContextWindow = 0;
        try {
            var info = modelCatalog.findModel(effectiveModelId);
            if (info != null && info.parameters() != null) {
                supportsVision = info.parameters().isSupportsVision();
                modelContextWindow = info.parameters().getContextWindow();
            }
        } catch (RuntimeException e) {
            // 取不到模型参数 → 零值，不阻断本轮
        }
        agentConfig.setChatModelSupportsVision(supportsVision);
        // 上下文 token 上限：显式设置 > 模型声明 > 缺省
        agentConfig.setMaxContextTokens(agentConfig.getMaxContextTokens() > 0
                ? agentConfig.getMaxContextTokens()
                : (modelContextWindow > 0 ? modelContextWindow
                        : AgentBudgets.DEFAULT_MAX_CONTEXT_TOKENS));
        log.info("Agent context window: {} tokens (model {} declares {})",
                agentConfig.getMaxContextTokens(), effectiveModelId, modelContextWindow);

        Reranker rerankModel = null;
        if (AgentConfigAssembler.agentRequiresRerankModel(req.agentConfig)) {
            String rerankModelId = req.agentConfig.path("rerankModelId").asText("");
            if (rerankModelId.isEmpty()) {
                throw new RuntimeException(
                        "rerank model is not configured: please set rerank_model_id on the agent");
            }
            rerankModel = rerankModel(rerankModelId);
        } else {
            log.info("knowledge_search is unavailable for the effective agent scope, "
                    + "skipping rerank model initialization");
        }
        return new AgentModels(summaryModel, rerankModel, supportsVision);
    }

    /** 多轮历史；装载失败退化为空上下文（不阻断本轮）。 */
    private List<ChatMessage> loadLlmContext(QaAgentConfig agentConfig, String sessionId) {
        if (!agentConfig.isMultiTurnEnabled()) {
            log.info("Multi-turn disabled for this agent, running without history");
            return new ArrayList<>();
        }
        int historyTurns = agentConfig.getHistoryTurns() <= 0 ? 5 : agentConfig.getHistoryTurns();
        try {
            List<ChatMessage> ctx = historyAssembler.loadAgentHistory(sessionId, historyTurns);
            log.info("Loaded {} history messages from DB (turns={})", ctx.size(), historyTurns);
            return ctx;
        } catch (RuntimeException e) {
            log.warn("Failed to load agent history from DB, continuing without history", e);
            return new ArrayList<>();
        }
    }

    /** 长期记忆召回：注入 prompt + 发 {@code EVENT_MEMORY_RECALLED}（失败不阻断）。 */
    private void attachMemory(AgentEngineHandle engine, QaSupport.QaRequest req,
            String sessionId, EventBusInterface eventBus) {
        if (memoryService == null || !req.agentConfig.path("memoryEnabled").asBoolean(false)) {
            return;
        }
        var recall = memoryService.recall(req.query);
        if (recall == null || recall.prompt() == null || recall.prompt().isEmpty()) {
            return;
        }
        engine.setMemoryPrompt(recall.prompt());
        List<PipelineUsedMemoryView> used = new ArrayList<>();
        if (recall.items() != null) {
            for (var item : recall.items()) {
                // 与 chatpipeline 的投影一致：kind 缺省空串
                used.add(new PipelineUsedMemoryView(item.getId(), null, item.getContent()));
            }
        }
        Event evt = new Event();
        evt.setType(EventType.EVENT_MEMORY_RECALLED);
        evt.setSessionId(sessionId);
        evt.setData(new MemoryRecalledData(used));
        try {
            eventBus.emit(evt);
        } catch (RuntimeException e) {
            log.warn("Failed to emit memory recalled event", e);
        }
        log.info("Injected {} long-term memories into agent context", used.size());
    }

    /** steer 交接与用户停止的取消源（两者都可空）。 */
    private void attachSteerAndCancel(AgentEngineHandle engine, QaSupport.QaRequest req) {
        if (req.steerSink != null) {
            engine.setSteerSink(req.steerSink);
        }
        if (req.cancellationProbe != null) {
            engine.setCancellationSource(req.cancellationProbe);
        }
    }

    /** 查询组装：视觉直传图片，否则把 VLM 描述拼进文本；再补引用与附件。 */
    private AgentQuery buildAgentQuery(QaSupport.QaRequest req, boolean supportsVision) {
        String text = req.query;
        List<String> imageUrls = new ArrayList<>();
        if (supportsVision && req.imageUrls != null && !req.imageUrls.isEmpty()) {
            imageUrls = req.imageUrls;
            log.info("Agent model supports vision, passing {} image(s) directly", imageUrls.size());
        } else if (!req.imageDescription.isEmpty()) {
            text = req.query + "\n\n[用户上传图片内容]\n" + req.imageDescription;
            log.info("Agent model does not support vision, appending image description ({} chars)",
                    req.imageDescription.length());
        }
        if (!req.quotedContext.isEmpty()) {
            text += "\n\n" + req.quotedContext;
        }
        if (!req.attachments.isEmpty()) {
            text += MessageAttachmentsPrompt.build(PipelineViews.ofAttachments(req.attachments));
            log.info("Appended {} attachment(s) to agent query", req.attachments.size());
        }
        return new AgentQuery(text, imageUrls);
    }

    /**
     * 执行并处理两类失败。
     *
     * <p><b>纪律</b>：{@link AgentExecutionException} 是引擎已上报的失败——这里只记带栈日志；
     * 再补一份 error 事件会让客户端看到两次报错。其余 {@link RuntimeException} 属引擎入口自身
     * 失败（建 span / 组装 prompt / 建工具集），没有对应 error 事件，需本层补发。</p>
     */
    boolean executeAgent(AgentEngineHandle engine, QaSupport.QaRequest req, String sessionId,
            AgentQuery query, List<ChatMessage> llmContext, EventBusInterface eventBus) {
        try {
            engine.execute(sessionId, req.assistantMessageId, query.text(), llmContext, query.imageUrls());
            return true;
        } catch (AgentExecutionException e) {
            // 引擎已上报过 error 事件：这里不重复发，只把失败结果回传给调用方判终态
            log.error("Agent execution failed", e);
            return false;
        } catch (RuntimeException e) {
            log.error("Agent execution failed before engine error channel", e);
            Event evt = new Event();
            evt.setType(EventType.EVENT_ERROR);
            evt.setSessionId(sessionId);
            ErrorData errData = new ErrorData();
            errData.setError(e.getMessage());
            errData.setStage("agent_execution");
            errData.setSessionId(sessionId);
            evt.setData(errData);
            eventBus.emit(evt);
            return false;
        }
    }

    private LlmChatClient chatModel(String modelId) {
        var model = modelCatalog.findModel(modelId);
        if (model == null) {
            return null;
        }
        // 取数口径留在此处（findModel 的状态闸门）；构造走 model-runtime 的工厂面
        // （并发闸门装饰在那边装配， 的 NPE 教训随之固化）。
        // langfuse generation 装饰（未启用时原样返回）
        return SessionModels.chat(modelRuntime.chatClientFor(model));
    }

    private Reranker rerankModel(String modelId) {
        // langfuse generation 装饰（未启用时原样返回）
        return SessionModels.rerank(modelRuntime.rerankerFor(modelCatalog.findModel(modelId)));
    }

}
