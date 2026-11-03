package com.ragagent.agent;

import com.fasterxml.jackson.databind.node.NullNode;
import com.ragagent.agent.budget.TokenEstimator;
import com.ragagent.agent.compaction.CompactionSettings;
import com.ragagent.agent.compaction.Compactor;
import com.ragagent.agent.domain.AgentState;
import com.ragagent.agent.prompt.AgentPromptTemplates;
import com.ragagent.agent.prompt.PinnedMCPServiceInfo;
import com.ragagent.agent.prompt.PinnedSkillInfo;
import com.ragagent.agent.skills.Manager;
import com.ragagent.agent.tool.ToolRegistry;
import com.ragagent.contract.agent.AgentBudgets;
import com.ragagent.contract.agent.AgentRunSpec;
import com.ragagent.contract.agent.AgentStep;
import com.ragagent.contract.agent.AgentToolCall;
import com.ragagent.contract.agent.ImageDescriberFunc;
import com.ragagent.contract.agent.KnowledgeBaseInfo;
import com.ragagent.contract.agent.SelectedDocumentInfo;
import com.ragagent.contract.agent.SteerSink;
import com.ragagent.contract.error.BizException;
import com.ragagent.contract.event.Event;
import com.ragagent.contract.event.EventBusInterface;
import com.ragagent.contract.event.payload.ErrorData;
import com.ragagent.contract.model.ChatMessage;
import com.ragagent.contract.model.ChatResponse;
import com.ragagent.contract.model.ChatTool;
import com.ragagent.contract.model.FunctionDef;
import com.ragagent.contract.model.LlmChatClient;
import com.ragagent.contract.model.TokenUsage;
import com.ragagent.contract.model.ToolCall;
import com.ragagent.contract.tool.ToolDefinitions;
import com.ragagent.modelcontext.Registry;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.platform.access.TenantContextSnapshot;
import com.ragagent.platform.event.EventBus;
import com.ragagent.platform.event.EventIds;
import com.ragagent.platform.event.EventType;
import com.ragagent.platform.tracing.langfuse.LangfuseManager;
import com.ragagent.platform.tracing.langfuse.Span;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ReAct agent 引擎。
 *
 * <h2>无状态跨轮</h2>
 * <p>引擎跨轮无状态：会话历史每轮由调用方从 DB
 * 重建后经 {@code llmContext} 传入。引擎不维护自己的缓存、系统提示词存储或跨轮缓冲。</p>
 *
 * <h2>事件即 SSE 上游</h2>
 * <p>所有事件经 {@link EventBusInterface} 发出；
 * emit 顺序就是将来的 SSE 帧序。</p>
 *
 * <h2>语义决策</h2>
 * <ul>
 *   <li><b>失败即异常</b>：成功返回值 / 抛 {@link AgentEngineException}
 *       （message 逐字稳定——它是 error 事件字段原文）。</li>
 *   <li><b>取消探测</b>用
 *       {@link #setCancellationSource(Supplier)}（null=存活，非 null=错误原文）；
 *       租户/主体在引擎线程解析成<b>显式值</b>传入虚拟线程，
 *       不读 ThreadLocal。</li>
 *   <li><b>消息列表/计数器</b>用 {@link MsgRef} / AtomicInteger /
 *       AtomicReference 装箱共享。</li>
 *   <li><b>complete 事件的 usage 键恒输出</b>：用量缺失也输出
 *       {@code "usage":null}；以 {@link NullNode} 编码，消费侧按
 *       {@code usage instanceof TokenUsage} 判别。</li>
 *   <li><b>LLM 瞬态重试</b>的 sleep（1s/2s）保留。</li>
 * </ul>
 *
 * <h2>主循环地图</h2>
 * <p>一轮 = 一个 {@link ReActIteration}，由 {@code execute} → {@code executeLoop} 驱动。每轮时序：</p>
 * <ol>
 *   <li>裁剪 / 压缩上下文（{@code ObservePhase.manageContextWindow} + {@code compaction/}）</li>
 *   <li>注入用户中途插话（{@code SteerIntake.drainSteerMessages}）</li>
 *   <li>调模型（{@code ThinkPhase.callLLMWithRetry}；消息与提示词由 {@code ConversationAssembler} 装配）</li>
 *   <li>判定响应：终答 / 继续 / 中断（{@code ObservePhase.analyzeResponse} → {@code ResponseVerdict}）</li>
 *   <li>执行工具（{@code ActPhase.executeToolCalls}，工具定义在 {@code tools/}）</li>
 *   <li>回灌结果与图片（{@code ObservePhase.appendToolResults} / {@code appendToolImages}）</li>
 * </ol>
 * <p>出口三种：终答、迭代预算用尽（{@code withinIterationBudget} → {@code FinalizePhase.handleMaxIterations} 兜底）、
 * 取消探测命中（{@code pollCancellation}）。想改某一段，见 {@link ThinkPhase} / {@link ActPhase} /
 * {@link ObservePhase} / {@link FinalizePhase} / {@link SteerIntake} 的类头。</p>
 */
public class AgentEngine {

    private static final Logger log = LoggerFactory.getLogger(AgentEngine.class);


    /** agent.execute span 输入里 query 的预览上限。 */
    private static final int LANGFUSE_QUERY_PREVIEW = 2000;

    /** 循环结束注入最多多跑一轮。 */
    static final int MAX_STEER_OVERRUNS = 1;

    // 引擎字段
    /** 本轮运行规格（只读；会话侧 agent 配置的引擎读面）。 */
    final AgentRunSpec config;

    /** 本轮工具注册表（模型函数定义 + MCP 动态刷新/清理）。 */
    final ToolRegistry toolRegistry;

    /** 本轮对话模型客户端（provider 配置与观测装饰已在外层封装）。 */
    final LlmChatClient chatModel;

    /** 事件出口（answer / thinking / tool / error 事件帧）。 */
    final EventBusInterface eventBus;

    /** 绑定知识库详情（提示词用）；测试需要直改 → 包内可见。 */
    List<KnowledgeBaseInfo> knowledgeBasesInfo;

    /** 用户 @ 选中的文档。 */
    List<SelectedDocumentInfo> selectedDocs;

    /** 本轮 @ 指定的 MCP 服务。 */
    List<PinnedMCPServiceInfo> pinnedMCPServices = List.of();

    /** 本轮 @ 指定的技能。 */
    List<PinnedSkillInfo> pinnedSkills = List.of();

    /** 引擎自己的 session id（emitContextCompacted 用它，不是执行入口的入参）。 */
    final String sessionId;

    /** 显式系统提示词模板；空串 = 按有无知识库选默认模板（纯 agent / 渐进 RAG）。 */
    String systemPromptTemplate;

    /** 会话侧注入的记忆片段（拼进系统提示词）；空串 = 无记忆段。 */
    String memoryPrompt = "";

    /** 本轮技能管理器；null = 不挂技能（未启用，或目录读取失败降级）。 */
    Manager skillsManager;

    /** 提示词模板解析配置；null = 默认 base。 */
    AgentPromptTemplates.TemplatesConfig appConfig;

    /** 工具结果图片的 VLM 描述函数（可选）。 */
    ImageDescriberFunc imageDescriber;

    /** token 估算器（预算判断、压缩与摘要共用同一套估算）。 */
    final TokenEstimator tokenEstimator = new TokenEstimator();

    /** 上下文压缩器（超预算时裁剪历史；阈值取本轮 config）。 */
    Compactor compactor;

    /** 最近一次 LLM 调用的用量（usage 基线）。 */
    TokenUsage lastUsage = new TokenUsage();

    /** 最近一次 LLM 调用发送的消息数。 */
    int lastSentMsgCount;

    /** 本轮已用过的一次溢出压缩重试。 */
    boolean overflowRecovered;

    /** 压缩上次"腾不出空间"时的消息数。 */
    int compactionExhaustedAt;

    /** 请求内 model-context 边界。 */
    final Registry modelContext;

    /** 运行中注入通道；null = 禁用。 */
    SteerSink steerSink;

    /** 本轮 steer 注入把预算耗尽的主循环拉回来一次（主循环消费一次即复位）。 */
    boolean allowSteerOverrun;

    /** 已透支的轮数（上限 {@link #MAX_STEER_OVERRUNS}）。 */
    int steerOverruns;

    /** 取消探测；null = 永不取消。 */
    Supplier<String> cancellationSource;

    /** ReAct 各段协作者（构造期装配；只存引擎引用，调用期才解引）。 */
    final ThinkPhase think;
    final ActPhase act;
    final ObservePhase observe;
    final ConversationAssembler prompts;
    final FinalizePhase finalize;
    final SteerIntake steer;
    final ReActIteration iteration;
    final ContextDebugEmitter contextDebug;

    // 构造

    public AgentEngine(AgentRunSpec config, LlmChatClient chatModel, ToolRegistry toolRegistry,
            EventBusInterface eventBus, List<KnowledgeBaseInfo> knowledgeBasesInfo,
            List<SelectedDocumentInfo> selectedDocs, String sessionId,
            String systemPromptTemplate) {
        if (eventBus == null) {
            eventBus = new EventBus();
        }
        this.config = config;
        this.toolRegistry = toolRegistry;
        this.chatModel = chatModel;
        this.eventBus = eventBus;
        this.knowledgeBasesInfo = knowledgeBasesInfo;
        this.selectedDocs = selectedDocs;
        this.sessionId = sessionId;
        this.systemPromptTemplate = systemPromptTemplate == null ? "" : systemPromptTemplate;
        this.modelContext = new Registry(config == null || config.citationsEnabled());

        this.compactor = Compactor.create(chatModel, tokenEstimator,
                new CompactionSettings(true,
                        config == null ? 0 : config.getMaxContextTokens(),
                        contextReserveTokens(),
                        config == null ? 0 : config.getCompactionKeepRecentTokens(),
                        getCompletionTokenBudget()));
        this.think = new ThinkPhase(this);
        this.act = new ActPhase(this);
        this.observe = new ObservePhase(this);
        this.prompts = new ConversationAssembler(this);
        this.finalize = new FinalizePhase(this);
        this.steer = new SteerIntake(this);
        this.iteration = new ReActIteration(this);
        this.contextDebug = new ContextDebugEmitter(this);
    }

    /** 带技能管理器的构造变体。 */
    public static AgentEngine withSkills(AgentConfig config, LlmChatClient chatModel,
            ToolRegistry toolRegistry, EventBusInterface eventBus,
            List<KnowledgeBaseInfo> knowledgeBasesInfo,
            List<SelectedDocumentInfo> selectedDocs, String sessionId,
            String systemPromptTemplate, Manager skillsManager) {
        AgentEngine engine = new AgentEngine(config, chatModel, toolRegistry, eventBus,
                knowledgeBasesInfo, selectedDocs, sessionId, systemPromptTemplate);
        engine.skillsManager = skillsManager;
        return engine;
    }

    /** 本轮 @mention 范围。 */
    public void setPinnedMentions(List<PinnedMCPServiceInfo> mcpServices,
            List<PinnedSkillInfo> skills) {
        this.pinnedMCPServices = mcpServices == null ? List.of() : mcpServices;
        this.pinnedSkills = skills == null ? List.of() : skills;
    }

    /** 空输入不改动系统提示词。 */
    public void setMemoryPrompt(String prompt) {
        this.memoryPrompt = prompt == null ? "" : prompt;
    }

    /** 设置提示词模板解析配置。 */
    public void setAppConfig(AgentPromptTemplates.TemplatesConfig cfg) {
        this.appConfig = cfg;
    }

    public void setImageDescriber(ImageDescriberFunc fn) {
        this.imageDescriber = fn;
    }

    public void setSkillsManager(Manager manager) {
        this.skillsManager = manager;
    }

    public Manager getSkillsManager() {
        return skillsManager;
    }

    /** null（默认）= 完全禁用运行中注入。 */
    public void setSteerSink(SteerSink sink) {
        this.steerSink = sink;
    }

    /**
     * 取消探测 seam：存活返回 null、取消返回错误原文；
     * 调用方在 stop 链路接线它。
     */
    public void setCancellationSource(Supplier<String> source) {
        this.cancellationSource = source;
    }

    String pollCancellation() {
        return cancellationSource == null ? null : cancellationSource.get();
    }

    // ── 包内测试 seam（生产装配走构造器/setter） ──

    ToolRegistry getRegistryForTest() {
        return toolRegistry;
    }

    EventBusInterface getBusForTest() {
        return eventBus;
    }

    void setKnowledgeBasesInfoForTest(List<KnowledgeBaseInfo> v) {
        this.knowledgeBasesInfo = v;
    }

    void setSelectedDocsForTest(List<SelectedDocumentInfo> v) {
        this.selectedDocs = v;
    }

    TokenEstimator tokenEstimatorForTest() {
        return tokenEstimator;
    }

    void setUsageBaselineForTest(TokenUsage usage, int sentCount) {
        this.lastUsage = usage;
        this.lastSentMsgCount = sentCount;
    }

    CompactionSettings compactorSettingsForTest() {
        return compactor == null ? null : compactor.settings();
    }

    // ── 包内测试/跨段 seam 委托（实现随 ReAct 各段协作者） ──

    AgentToolCall runToolCall(ToolCall tc, int i, int iteration, int round,
            String sessionId, String assistantMessageId, TenantContextSnapshot tenant) {
        return act.runToolCall(tc, i, iteration, round, sessionId, assistantMessageId, tenant);
    }

    int drainSteerMessages(AgentState state, MsgRef messagesRef, String sessionId, String messageId) {
        return steer.drainSteerMessages(state, messagesRef, sessionId, messageId);
    }

    WindowOutcome manageContextWindow(List<ChatMessage> messages, int round, int currentTokens) {
        return observe.manageContextWindow(messages, round, currentTokens);
    }

    ChatResponse streamThinkingToEventBus(List<ChatMessage> messages, List<ChatTool> tools,
            int iteration, String sessionId) {
        return think.streamThinkingToEventBus(messages, tools, iteration, sessionId);
    }

    // token 预算与迭代上限

    int estimateCurrentTokens(List<ChatMessage> messages) {
        int baseline = ContextBudget.contextTokensFromUsage(lastUsage);
        if (baseline > 0 && lastSentMsgCount > 0 && lastSentMsgCount <= messages.size()) {
            return baseline + tokenEstimator
                    .estimateMessages(messages.subList(deltaStart(messages), messages.size()));
        }
        return tokenEstimator.estimateMessages(messages);
    }

    /** 第一个尚未被 lastUsage 覆盖的消息下标。 */
    private int deltaStart(List<ChatMessage> messages) {
        int start = lastSentMsgCount;
        if (start < messages.size() && "assistant".equals(messages.get(start).getRole())) {
            start++;
        }
        return start;
    }

    /** 是否还允许一个 ReAct 轮次；负 MaxIterations = 无上限。 */
    boolean withinIterationBudget(int round) {
        if (config == null) {
            return false;
        }
        if (config.unlimitedIterations()) {
            return true;
        }
        return round < config.getMaxIterations();
    }
    String maxIterationsDisplay() {
        if (config != null && config.unlimitedIterations()) {
            return "unlimited";
        }
        return String.valueOf(config == null ? 0 : config.getMaxIterations());
    }

    /** 单轮 ReAct 的补全预算。 */
    int getCompletionTokenBudget() {
        int configured = 0;
        if (config != null) {
            configured = config.getMaxCompletionTokens();
        }
        return AgentBudgets.agentRoundMaxCompletionTokens(configured);
    }

    int contextReserveTokens() {
        return AgentConsts.contextReserveTokens(getCompletionTokenBudget());
    }

    int clampCompletionBudgetToContext(int currentTokens) {
        int budget = getCompletionTokenBudget();
        if (config == null || config.getMaxContextTokens() <= 0) {
            return budget;
        }
        return AgentConsts.clampCompletionBudgetToContext(config.getMaxContextTokens(), currentTokens, budget);
    }

    Duration getLLMStallTimeout() {
        return AgentConsts.llmStallTimeout(config == null ? null : config.getLlmCallTimeout());
    }

    // 执行主入口
    /** 便捷重载（无图片）。 */
    public AgentState execute(String sessionId, String messageId, String query,
            List<ChatMessage> llmContext) {
        return execute(sessionId, messageId, query, llmContext, null);
    }

    /**
 * 执行 agent：带会话历史与流式输出。
 *
 * @param imageURLs 多模态输入图片
 * @throws AgentEngineException 失败时（**error 事件已在抛出前发出**，终态帧随后补；：
 * 引擎是失败上报的唯一所有者——闸门内的非契约异常也统一收编成本类型，调用方
 * 据类型判定"已上报"、不再补发第二份 error）
 */
    public AgentState execute(String sessionId, String messageId, String query,
            List<ChatMessage> llmContext, List<String> imageURLs) {
        // null 列表按空集处理——调用方（如 skill 安装器的 installer run）可以合法传
        // null，入口日志会直接取 size 而 NPE（install E2E 抓回：
        // installer agent failed: Cannot invoke "java.util.List.size()" because
        // "llmContext" is null），这里归一为空列表。
        List<ChatMessage> context = llmContext == null ? List.of() : llmContext;
        log.info("[Agent] Starting execution: session={}, message={}, query_len={}, context_msgs={}, tenantId={}, principal={}, userId={}",
                sessionId, messageId, query.length(), context.size(),
                TenantContext.currentTenantId(),
                TenantContext.currentPrincipal() == null ? "<null>"
                        : TenantContext.currentPrincipal().type(),
                TenantContext.currentUserId());
        try {
            return executeInner(sessionId, messageId, query, context, imageURLs);
        } finally {
            // Ensure tools are cleaned up after execution
            if (toolRegistry != null) {
                toolRegistry.cleanup();
            }
        }
    }

    private AgentState executeInner(String sessionId, String messageId, String query,
            List<ChatMessage> llmContext, List<String> imageURLs) {
        // 顶层 Langfuse span：整轮归到一个节点（no-op 实现零成本）。
        int imgCount = imageURLs == null ? 0 : imageURLs.size();
        List<String> kbIds = new ArrayList<>();
        if (knowledgeBasesInfo != null) {
            for (KnowledgeBaseInfo kb : knowledgeBasesInfo) {
                if (kb != null) {
                    kbIds.add(kb.id());
                }
            }
        }
        Map<String, Object> spanInput = new LinkedHashMap<>();
        spanInput.put("query", truncateRunes(query, LANGFUSE_QUERY_PREVIEW));
        spanInput.put("query_len", query.length());
        spanInput.put("context_msgs", llmContext.size());
        spanInput.put("image_count", imgCount);
        Map<String, Object> spanMeta = new LinkedHashMap<>();
        spanMeta.put("sessionId", sessionId);
        spanMeta.put("messageId", messageId);
        spanMeta.put("max_iterations", config.getMaxIterations());
        spanMeta.put("parallel_tool_calls", config.isParallelToolCalls());
        spanMeta.put("web_search", config.isWebSearchEnabled());
        spanMeta.put("multi_turn", config.isMultiTurnEnabled());
        spanMeta.put("knowledge_base_ids", kbIds);
        spanMeta.put("allowed_tools", config.getAllowedTools());
        Span agentSpan = LangfuseManager.get().startSpan(
                new LangfuseManager.SpanOptions("agent.execute", spanInput, spanMeta));

        // Initialize state
        AgentState state = new AgentState();
        state.setRoundSteps(new ArrayList<>());
        state.setKnowledgeRefs(new ArrayList<>());
        state.setComplete(false);
        state.setCurrentRound(0);

        String systemPrompt = prompts.buildSystemPrompt();
        log.debug("[Agent] SystemPrompt: {} chars", systemPrompt.length());

        List<String> imgs = imageURLs;
        List<ChatMessage> messages = prompts.buildMessagesWithLLMContext(systemPrompt, query, sessionId,
                llmContext, imgs);
        if (toolRegistry != null) {
            toolRegistry.rememberMcpHistory(messages);
            toolRegistry.refreshMcpTools();
        }

        List<ChatTool> tools = buildToolsForLLM();
        String toolListStr = String.join(", ", listToolNames(tools));
        log.info("[Agent] Ready: {} messages, {} tools [{}], mcp_catalog={} chars, {} images",
                messages.size(), tools.size(), toolListStr, mcpCatalogDescriptionLen(tools),
                imgs == null ? 0 : imgs.size());

        // 终态门（A1-AGENT）：成功由 executeLoop 的 finally 收口；失败由下面 catch 补终态——
        // 两者共用这道门 ⇒ 恰好一次。
        AtomicBoolean completionEmitted = new AtomicBoolean(false);
        Instant startTime = Instant.now();
        try {
            executeLoop(state, query, new MsgRef(messages), tools, sessionId, messageId,
                    completionEmitted, startTime);
        } catch (RuntimeException e) {
            log.error("[Agent] Execution failed", e);
            // 顺序契约：终态帧（agentComplete）是 SSE 的退出条件——必须先发错误事件，
            // 否则错误帧落在流关闭之后（客户端只看到"成功收尾"）。
            eventBus.emit(new Event(EventIds.generateEventId("error"), EventType.EVENT_ERROR,
                    sessionId, new ErrorData(e.getMessage(), "", "agent_execution", sessionId,
                            "", null), null, ""));
            finishAgentSpan(agentSpan, state, e.getMessage());
            if (completionEmitted.compareAndSet(false, true)) {
                finalize.emitCompletionEvent(state, sessionId, messageId, startTime, e.getMessage());
            }
            //：失败上报的**唯一所有者**在引擎侧——非契约异常统一收编为
            // AgentEngineException（消息沿用既有 wireText 口径、保留 state 与 cause），
            // 调用方据此判定"已上报"、不再补发第二份 error。
            throw e instanceof AgentEngineException aee
                    ? aee
                    : new AgentEngineException(BizException.wireText(e), state, e);
        }

        log.info("[Agent] Completed: {} rounds, {} steps, complete={}",
                state.getCurrentRound(), state.getRoundSteps().size(), state.isComplete());
        finishAgentSpan(agentSpan, state, null);
        return state;
    }

    /** 成败共用同一份 span 载荷。 */
    private static void finishAgentSpan(Span span, AgentState state, String err) {
        if (span == null) {
            return;
        }
        int totalToolCalls = countTotalToolCalls(state.getRoundSteps());
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("rounds", state.getCurrentRound());
        output.put("steps", state.getRoundSteps().size());
        output.put("toolCalls", totalToolCalls);
        output.put("complete", state.isComplete());
        output.put("final_answer_len", state.getFinalAnswer().length());
        output.put("finalAnswer", truncateRunes(state.getFinalAnswer(), LANGFUSE_QUERY_PREVIEW));
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("rounds", state.getCurrentRound());
        meta.put("steps", state.getRoundSteps().size());
        meta.put("toolCalls", totalToolCalls);
        meta.put("complete", state.isComplete());
        span.finish(output, meta, err);
    }

    /** 按码点截断 + 尾加 "…"。 */
    static String truncateRunes(String s, int n) {
        if (n <= 0 || s.isEmpty()) {
            return s;
        }
        if (s.codePointCount(0, s.length()) <= n) {
            return s;
        }
        return s.substring(0, s.offsetByCodePoints(0, n)) + "…";
    }

    // ReAct 主循环
    /** 消息列表的可变引用代理（模拟引用传参）。 */
    static final class MsgRef {
        List<ChatMessage> items;

        MsgRef(List<ChatMessage> items) {
            this.items = items;
        }
    }

    /** 一个 ReAct 迭代后的循环走向（label 供 langfuse 输出）。 */
    enum IterOutcome {
        NEXT("next"), CONTINUE("continue"), BREAK("break");

        private final String label;

        IterOutcome(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    private void executeLoop(AgentState state, String query, MsgRef messagesRef,
            List<ChatTool> tools, String sessionId, String messageId,
            AtomicBoolean completionEmitted, Instant startTime) {
        log.info("[PIPELINE] stage=Agent action=loop_start max_iterations={}", config.getMaxIterations());

        boolean loopCompleted = false;
        try {
            AtomicInteger emptyRetries = new AtomicInteger();
            AtomicInteger consecutiveSameContent = new AtomicInteger();
            AtomicReference<String> lastResponseContent = new AtomicReference<>("");

            loop:
            while (withinIterationBudget(state.getCurrentRound()) || allowSteerOverrun) {
                allowSteerOverrun = false;
                // 轮首取消检查（请求超时/用户停止）。
                String cancelErr = pollCancellation();
                if (cancelErr != null) {
                    log.warn("[Agent] Context cancelled at round {}: {}", state.getCurrentRound() + 1,
                            cancelErr);
                    int totalTC = countTotalToolCalls(state.getRoundSteps());
                    if (totalTC > 0) {
                        log.info("[Agent] Synthesizing final answer from {} existing tool results", totalTC);
                        finalize.streamFinalAnswerToEventBus(query, state, sessionId, messagesRef.items);
                        state.setComplete(true);
                    }
                    throw new AgentEngineException(cancelErr, state);
                }

                // 上一轮结束后可能产生了新工具定义；只在轮首发布并重建 wire 列表。
                if (toolRegistry != null) {
                    toolRegistry.refreshMcpTools();
                    tools = buildToolsForLLM();
                }

                IterOutcome outcome = iteration.runReActIteration(state, messagesRef, tools,
                        sessionId, messageId, query, emptyRetries, consecutiveSameContent,
                        lastResponseContent);
                switch (outcome) {
                    case CONTINUE -> {
                        continue loop;
                    }
                    case BREAK -> {
                        break loop;
                    }
                    case NEXT -> state.setCurrentRound(state.getCurrentRound() + 1);
                }
            }

            // 循环走完没有最终答案就补一个——上下文被取消（用户停止）时跳过：
            // 兜底调用会在已取消的上下文上失败并把占位文案漏给用户。
            if (!state.isComplete() && pollCancellation() == null) {
                finalize.handleMaxIterations(query, state, sessionId, messagesRef.items);
            }
            loopCompleted = true;
        } finally {
            // 只有循环自己走完才在这里收口；失败路径的终态由 executeInner 的 catch 在
            // **错误事件之后**补（顺序契约：错误帧在前、终态帧在后）。
            if (loopCompleted && completionEmitted.compareAndSet(false, true)) {
                finalize.emitCompletionEvent(state, sessionId, messageId, startTime);
            }
        }
    }

    /** WindowOutcome：manageContextWindow 的 (messages, changed)。 */
    record WindowOutcome(List<ChatMessage> messages, boolean changed) {
    }

    // finish reason 判定（供测试与 ReAct 迭代共用）

    /** 自然停 finish reason。 */
    static boolean isNaturalStopFinishReason(String reason) {
        String r = reason == null ? "" : reason.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (r) {
            case "stop", "end_turn", "stop_sequence" -> true;
            default -> false;
        };
    }

    /** 截断 finish reason。 */
    static boolean isLengthFinishReason(String reason) {
        String r = reason == null ? "" : reason.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (r) {
            case "length", "max_tokens", "max_output_tokens" -> true;
            default -> false;
        };
    }

    /**
     * 渲染本轮用户消息内容（含 runtime_context / must_use 注入块；实现见 {@link ConversationAssembler}）。
     */
    public String renderUserTurnContent(String sessionId, String query) {
        return prompts.renderUserTurnContent(sessionId, query);
    }

    static List<String> listToolNames(List<ChatTool> tools) {
        List<String> names = new ArrayList<>(tools.size());
        for (ChatTool t : tools) {
            names.add(t.getFunction().getName());
        }
        return names;
    }

    static int mcpCatalogDescriptionLen(List<ChatTool> tools) {
        for (ChatTool t : tools) {
            if (ToolDefinitions.TOOL_DISCOVER_MCP_TOOLS.equals(t.getFunction().getName())) {
                return t.getFunction().getDescription().length();
            }
        }
        return 0;
    }

    /** LLM 函数调用用的工具列表。 */
    List<ChatTool> buildToolsForLLM() {
        List<FunctionDef> functionDefs = toolRegistry.getModelFunctionDefinitions();
        List<ChatTool> tools = new ArrayList<>(functionDefs.size());
        for (FunctionDef def : functionDefs) {
            tools.add(new ChatTool(def.getName(), def.getDescription(), def.getParameters()));
        }
        return modelContext.encodeTools(tools);
    }

    static int countTotalToolCalls(List<AgentStep> steps) {
        int total = 0;
        if (steps == null) {
            return 0;
        }
        for (AgentStep step : steps) {
            total += step.getToolCalls() == null ? 0 : step.getToolCalls().size();
        }
        return total;
    }
}
