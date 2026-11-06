package com.ragagent.session.service;

import com.ragagent.agenttools.web.WebFetchTool;
import com.ragagent.agenttools.web.WebSearchTool;
import com.ragagent.agenttools.wiki.WikiRouteResolver;
import com.ragagent.agenttools.wiki.WikiScope;
import com.ragagent.contract.agent.AgentEngineFactory;
import com.ragagent.contract.agent.AgentEngineHandle;
import com.ragagent.contract.agent.AgentEngineRequest;
import com.ragagent.contract.agent.AgentRunSpec;
import com.ragagent.contract.agent.ImageDescriberFunc;
import com.ragagent.contract.agent.KnowledgeBaseInfo;
import com.ragagent.contract.agent.RecentDocInfo;
import com.ragagent.contract.agent.SelectedDocumentInfo;
import com.ragagent.contract.event.EventBusInterface;
import com.ragagent.contract.knowledge.FaqEntryPageView;
import com.ragagent.contract.knowledge.FaqEntryView;
import com.ragagent.contract.knowledge.KnowledgeBaseRowView;
import com.ragagent.contract.knowledge.KnowledgeCatalogPort;
import com.ragagent.contract.knowledge.KnowledgeRowView;
import com.ragagent.contract.model.LlmChatClient;
import com.ragagent.contract.model.Reranker;
import com.ragagent.contract.retrieval.SearchTarget;
import com.ragagent.contract.storage.ResourceCatalogPort;
import com.ragagent.contract.tool.AgentTool;
import com.ragagent.contract.tool.ToolDefinitions;
import com.ragagent.memory.api.MemoryAccess;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.session.support.SessionOwnerIds;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code SessionAgentQaService} 的**引擎/工具装配簇**（c 刀 3）：创建 agent 引擎（LLM/记忆/审批门/
 * 工件收集器接线），把 MCP 工具、知识库/文档信息、网页文件、内建工具注册进 {@code ToolRegistry}，
 * 以及知识库检索能力的探测与提示词段落拼装。
 *
 * <p>边界按调用点定：{@code chatModel}/{@code rerankModel}（{@code agentQA} 解析后作参数传入）与
 * {@code parseHostSkillDirs}（构造器在用）不随本簇走（口径）。</p>
 */
final class AgentEngineAssembler {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(AgentEngineAssembler.class);

    private final MemoryAccess memoryService;
    private final SessionKnowledgeQaService knowledgeQa;
    private final AgentToolBackends toolBackends;
    private final ArtifactCollectorWiring artifactCollectorWiring;
    private final KnowledgeCatalogPort knowledgeService;
    private final AgentEngineFactory engineFactory;
    private final ResourceCatalogPort resourceCatalog;
    private final javax.sql.DataSource dataSource;
    private final VlmDescriberWiring vlmDescriberWiring;
    AgentEngineAssembler(MemoryAccess memoryService, SessionKnowledgeQaService knowledgeQa, AgentToolBackends toolBackends, ArtifactCollectorWiring artifactCollectorWiring, KnowledgeCatalogPort knowledgeService, AgentEngineFactory engineFactory, ResourceCatalogPort resourceCatalog, javax.sql.DataSource dataSource, VlmDescriberWiring vlmDescriberWiring) {
        this.memoryService = memoryService;
        this.knowledgeQa = knowledgeQa;
        this.toolBackends = toolBackends;
        this.artifactCollectorWiring = artifactCollectorWiring;
        this.knowledgeService = knowledgeService;
        this.engineFactory = engineFactory;
        this.resourceCatalog = resourceCatalog;
        this.dataSource = dataSource;
        this.vlmDescriberWiring = vlmDescriberWiring;
    }

    AgentEngineHandle createAgentEngine(QaAgentConfig config, LlmChatClient chatModel,
            Reranker rerankModel, EventBusInterface eventBus, String sessionId, String assistantMessageId) {
        log.info("Creating agent engine with custom EventBusInterface");

        // 1. Validate config（ValidateConfig）
        if (config.getMaxIterations() < 0) {
            config.setMaxIterations(AgentRunSpec.UNLIMITED_MAX_ITERATIONS);
        } else if (config.getMaxIterations() == 0) {
            config.setMaxIterations(5);
        } else if (config.getMaxIterations() > SessionAgentQaService.MAX_ITERATIONS) {
            throw new RuntimeException(
                    "invalid agent config: max iterations too high: " + config.getMaxIterations() + " (max " + SessionAgentQaService.MAX_ITERATIONS + ")");
        }
        if (chatModel == null) {
            throw new RuntimeException("chat model is nil after initialization");
        }

        // 2. 外部工具（域侧构建；注册表/内置工具/MCP/技能装配由 agent-engine 的工厂完成）
        ToolPlan plan = planTools(config);
        List<AgentTool> tools = new ArrayList<>(buildTools(config, plan, rerankModel, sessionId));
        wireWebPageFiles(tools, config, sessionId, assistantMessageId);

        // 3. Resolve KB / selected doc metadata（resolveKBAndDocInfos；失败回落 IDs-only）
        List<KnowledgeBaseInfo> kbInfos = getKnowledgeBaseInfos(config);
        List<SelectedDocumentInfo> selectedDocs = getSelectedDocumentInfos(config);

        // 4. System prompt template
        String systemPromptTemplate = "";
        if (config.useCustomSystemPrompt() || !config.getSystemPrompt().isEmpty()) {
            // 自定义模板即终选模板
            systemPromptTemplate = config.getSystemPrompt();
        }

        // 5. 工具图片 VLM 描述器（取到 VLM 模型则注入；失败只记警告继续）
        ImageDescriberFunc imageDescriber = null;
        if (!config.getVlmModelId().isEmpty()) {
            try {
                imageDescriber = vlmDescriberWiring.create(config.getVlmModelId());
                log.info("VLM image describer set for tool result analysis (model: {})",
                        config.getVlmModelId());
            } catch (RuntimeException e) {
                log.warn("Failed to load VLM model {} for tool image fallback", config.getVlmModelId(), e);
            }
        }

        // 6. 交给 agent-engine 的工厂装配，返回契约句柄
        AgentEngineRequest request = new AgentEngineRequest(config, sessionId, assistantMessageId,
                kbInfos, selectedDocs, systemPromptTemplate, plan.allowedToolNames(), config.isSkillsEnabled(),
                config.getAllowedSkills(), TenantContext.currentTenantId(), config.getMcpSelectionMode(),
                config.getMcpServices(), config.getMcpAuthWaitTimeout(),
                config.getPinnedMcpServiceIds(), config.getPinnedSkillNames(),
                imageDescriber, tools);
        return engineFactory.create(request, chatModel, eventBus);
    }

    private record KbScopes(List<String> kbIds, Map<String, Long> kbTenantMap) {
    }
    private static KbScopes knowledgeBaseScopesForPrompt(QaAgentConfig config) {
        Map<String, Long> tenantMap = config.getSearchTargets() == null
                ? Map.of() : config.getSearchTargets().getKbTenantMap();
        if (config.getKnowledgeBases() != null && !config.getKnowledgeBases().isEmpty()) {
            return new KbScopes(config.getKnowledgeBases(), tenantMap);
        }
        return new KbScopes(config.getSearchTargets() == null
                ? List.of() : config.getSearchTargets().getAllKnowledgeBaseIds(), tenantMap);
    }
    /**
     * 真实 KB 元数据（名称/描述/类型/文档数/最近文档/
     * capabilities）进 system prompt 与 runtime_context。单库失败回落 ID-only 占位；
     * 临时库（__chat_history__ 等）跳过。
     */
    private List<KnowledgeBaseInfo> getKnowledgeBaseInfos(QaAgentConfig config) {
        KbScopes scopes = knowledgeBaseScopesForPrompt(config);
        if (scopes.kbIds().isEmpty()) {
            return new ArrayList<>();
        }
        List<KnowledgeBaseInfo> kbInfos = new ArrayList<>();
        for (String kbId : scopes.kbIds()) {
            KnowledgeBaseRowView kb;
            try {
                kb = knowledgeQa.findKnowledgeBase(kbId);
            } catch (RuntimeException e) {
                kb = null;
            }
            if (kb == null) {
                log.warn("Failed to get knowledge base {}, using IDs only for prompt", kbId);
                kbInfos.add(new KnowledgeBaseInfo(kbId, kbId, "document", "", 0,
                        List.of(), List.of()));
                continue;
            }
            // 跳过隐藏/系统托管的知识库（__chat_history__ 等）
            if (kb.isIsTemporary()) {
                log.debug("Skipping temporary knowledge base {} ({}) from prompt", kb.getId(), kb.getName());
                continue;
            }
            int docCount = 0;
            List<RecentDocInfo> recentDocs = new ArrayList<>();
            // FAQ 库：条目列表；否则/失败回落通用 knowledge 列表（completed 过滤，top 10）
            if ("faq".equals(kb.getType())) {
                try {
                    FaqEntryPageView page = knowledgeService.listFaqEntries(kbId, 1, 10, null, 0, "", "", "", null);
                    docCount = page.total() > Integer.MAX_VALUE ? Integer.MAX_VALUE
                            : (int) page.total();
                    List<FaqEntryView> entries = page.items();
                    if (entries != null) {
                        for (var entry : entries) {
                            if (recentDocs.size() >= 10) {
                                break;
                            }
                            recentDocs.add(new RecentDocInfo(
                                    entry.chunkId(), entry.knowledgeBaseId(), entry.knowledgeId(),
                                    entry.standardQuestion(), "", "", 0, "faq",
                                    entry.createdAt() == null ? ""
                                            : entry.createdAt().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE),
                                    entry.standardQuestion(), entry.similarQuestions(), entry.answers()));
                        }
                    }
                } catch (RuntimeException e) {
                    log.warn("Failed to list FAQ entries for {}", kbId, e);
                }
            }
            // 非 FAQ 或 FAQ 列表为空/失败 → 回落通用 knowledge 列表
            if (!"faq".equals(kb.getType()) || recentDocs.isEmpty()) {
                try {
                    var page = knowledgeService.listKnowledge(kbId, 1, 10, null, "completed",
                            null, null, false);
                    docCount = (int) Math.max(page.total(), 0);
                    if (page.items() != null) {
                        for (KnowledgeRowView k : page.items()) {
                            if (k == null || recentDocs.size() >= 10) {
                                break;
                            }
                            recentDocs.add(new RecentDocInfo(
                                    "", kb.getId(), nz(k.getId()), nz(k.getTitle()), nz(k.getDescription()),
                                    nz(k.getFileName()), k.getFileSize() == null ? 0 : k.getFileSize(),
                                    nz(k.getFileType()),
                                    k.getCreatedAt() == null ? ""
                                            : k.getCreatedAt().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE),
                                    "", null, null));
                        }
                    }
                } catch (RuntimeException e) {
                    log.warn("Failed to list knowledge for {}", kbId, e);
                }
            }
            String kbType = kb.getType() == null || kb.getType().isEmpty() ? "document" : kb.getType();
            kbInfos.add(new KnowledgeBaseInfo(kb.getId(), nz(kb.getName()), kbType,
                    nz(kb.getDescription()), docCount, kbRetrievalCapabilities(kb), recentDocs));
        }
        return kbInfos;
    }
    /** wiki / chunks（vector 或 keyword 开启）。 */
    private static List<String> kbRetrievalCapabilities(KnowledgeBaseRowView kb) {
        List<String> caps = new ArrayList<>(2);
        if (kb.getIndexingStrategy() != null) {
            if (kb.getIndexingStrategy().wikiEnabled()) {
                caps.add("wiki");
            }
            if (kb.getIndexingStrategy().vectorEnabled() || kb.getIndexingStrategy().keywordEnabled()) {
                caps.add("chunks");
            }
        }
        return caps;
    }
    /** @ 提及文档的元数据（缺失逐条跳过）。 */
    private List<SelectedDocumentInfo> getSelectedDocumentInfos(QaAgentConfig config) {
        List<String> ids = config.getKnowledgeIds();
        if (ids == null || ids.isEmpty()) {
            return new ArrayList<>();
        }
        List<SelectedDocumentInfo> selectedDocs = new ArrayList<>();
        for (String kid : ids) {
            KnowledgeRowView k;
            try {
                k = knowledgeService.getKnowledgeByIdOnly(kid);
            } catch (RuntimeException e) {
                k = null;
            }
            if (k == null) {
                log.debug("Selected knowledge {} not found", kid);
                continue;
            }
            selectedDocs.add(new SelectedDocumentInfo(nz(k.getId()),
                    nz(k.getKnowledgeBaseId()), nz(k.getTitle()), nz(k.getFileName()), nz(k.getFileType())));
        }
        return selectedDocs;
    }
    private static String nz(String v) {
        return v == null ? "" : v;
    }
    private void wireWebPageFiles(List<AgentTool> tools, QaAgentConfig config,
            String sessionId, String assistantMessageId) {
        if (config == null || !config.isWebSearchEnabled()) {
            return;
        }
        WebFetchTool fetch = null;
        WebSearchTool search = null;
        for (AgentTool tool : tools) {
            if (tool instanceof WebFetchTool f) {
                fetch = f;
            } else if (tool instanceof WebSearchTool s) {
                search = s;
            }
        }
        if (fetch == null) {
            return;
        }
        // web_search 是**另一次**工具查找，与 fetch 是两个实例
        if (search != null) {
            search.withPageReader(fetch);
        }
        // handler 已把会话存储钉到 owner 租户（租户取自 TenantContext）
        Long ctxTenant = TenantContext.currentTenantId();
        long tenantId = ctxTenant == null ? 0L : ctxTenant;
        if (tenantId == 0 || sessionId == null || sessionId.isEmpty()
                || assistantMessageId == null || assistantMessageId.isEmpty()) {
            return;
        }
        // 生产存储接缝（web 抓取页快照的存取 + 资源目录绑定）
        AgentWebPages pages = new AgentWebPages(dataSource, resourceCatalog,
                artifactCollectorWiring.webPageStore(), artifactCollectorWiring.webPageBinding(),
                tenantId, SessionOwnerIds.currentSessionOwnerId(),
                sessionId, assistantMessageId);
        fetch.withPageSource(pages);
    }

    /** 允许工具名 + wiki 装配上下文（KB/wiki/沙箱门控裁剪在此完成）。 */
    private record ToolPlan(List<String> allowedToolNames, List<WikiScope> wikiScopes,
                            List<String> wikiKbIds, WikiRouteResolver wikiRoutes) {
    }

    /** 工具注册面；工具集与硬门控逐条保留。 */
    private ToolPlan planTools(QaAgentConfig config) {
        List<String> allowedTools = new ArrayList<>(config.getAllowedTools().isEmpty()
                ? ToolDefinitions.defaultAllowedTools()
                : config.getAllowedTools());

        // Capability detection from SearchTargets
        boolean hasVectorKb = false;
        List<String> detectedWikiKbIds = new ArrayList<>();
        if (config.getSearchTargets() != null) {
            for (var target : config.getSearchTargets().list()) {
                String kbId = target.knowledgeBaseId();
                if (kbId == null || kbId.isEmpty()) {
                    continue;
                }
                try {
                    var kb = knowledgeQa.findKnowledgeBase(kbId);
                    if (kb != null && kb.getIndexingStrategy() != null) {
                        if (kb.getIndexingStrategy().vectorEnabled() || kb.getIndexingStrategy().keywordEnabled()) {
                            hasVectorKb = true;
                        }
                        if (kb.getIndexingStrategy().wikiEnabled()) {
                            detectedWikiKbIds.add(kb.getId());
                        }
                    }
                } catch (RuntimeException ignored) {
                    // 该 KB 不可达时跳过
                }
            }
        }
        // dedup → 由 SearchTargets 解析出带 doc/tag 窄化的 scope → **再用 scope
        // 重建 KB 清单**。hasWikiKb 必须看窄化后的结果：畸形空 target 不会变成整库授权，
        // 因而该 KB 也不该挂 wiki 工具。
        List<WikiScope> wikiScopes = detectedWikiKbIds.isEmpty()
                ? List.of()
                : WikiScope.newWikiScopesFromSearchTargets(config.getSearchTargets(), detectedWikiKbIds);
        List<String> wikiKbIds = new ArrayList<>();
        for (WikiScope scope : wikiScopes) {
            wikiKbIds.add(scope.knowledgeBaseId());
        }
        boolean hasWikiKb = !wikiKbIds.isEmpty();
        // 一个引擎一个 WikiRouteResolver，wiki 十件共享（search 见过的 slug
        // 会偏置 read_page 的查找序）
        WikiRouteResolver wikiRoutes = new WikiRouteResolver();
        boolean hasKnowledge = !config.getKnowledgeBases().isEmpty() || !config.getKnowledgeIds().isEmpty()
                || (config.getSearchTargets() != null
                        && SearchTarget.SearchTargets
                                .hasKnowledgeRetrievalScope(config.getSearchTargets(), List.of(), List.of()));

        // KB 工具过滤
        if (!hasKnowledge) {
            List<String> filtered = new ArrayList<>();
            List<String> kbTools = List.of(
                    ToolDefinitions.TOOL_KNOWLEDGE_SEARCH, ToolDefinitions.TOOL_GREP_CHUNKS,
                    ToolDefinitions.TOOL_LIST_KNOWLEDGE_CHUNKS, ToolDefinitions.TOOL_QUERY_KNOWLEDGE_GRAPH,
                    ToolDefinitions.TOOL_GET_DOCUMENT_INFO, ToolDefinitions.TOOL_DATABASE_QUERY,
                    ToolDefinitions.TOOL_DATA_ANALYSIS, ToolDefinitions.TOOL_DATA_SCHEMA,
                    ToolDefinitions.TOOL_WIKI_READ_PAGE, ToolDefinitions.TOOL_WIKI_SEARCH,
                    ToolDefinitions.TOOL_WIKI_READ_SOURCE_DOC, ToolDefinitions.TOOL_WIKI_FLAG_ISSUE,
                    ToolDefinitions.TOOL_WIKI_WRITE_PAGE, ToolDefinitions.TOOL_WIKI_REPLACE_TEXT,
                    ToolDefinitions.TOOL_WIKI_RENAME_PAGE, ToolDefinitions.TOOL_WIKI_DELETE_PAGE,
                    ToolDefinitions.TOOL_WIKI_READ_ISSUE, ToolDefinitions.TOOL_WIKI_UPDATE_ISSUE);
            List<String> effectiveKbTools = new ArrayList<>(kbTools);
            if (!config.isWebSearchEnabled()) {
                effectiveKbTools.add(ToolDefinitions.TOOL_TODO_WRITE);
            }
            for (String toolName : allowedTools) {
                if (!effectiveKbTools.contains(toolName)) {
                    filtered.add(toolName);
                }
            }
            allowedTools = filtered;
            log.info("Pure Agent Mode: Knowledge base tools filtered out, remaining: {}", allowedTools);
        }

        // Web 工具跟运行时开关
        allowedTools.remove(ToolDefinitions.TOOL_WEB_SEARCH);
        allowedTools.remove(ToolDefinitions.TOOL_WEB_FETCH);
        if (config.isWebSearchEnabled()) {
            allowedTools.add(ToolDefinitions.TOOL_WEB_SEARCH);
            allowedTools.add(ToolDefinitions.TOOL_WEB_FETCH);
        }

        // memory 工具跟开关：先摘，可用才挂回（"关掉"与"没存过"要答得不同）
        allowedTools.remove(ToolDefinitions.TOOL_SEARCH_MEMORY);
        if (memoryService.memoryAvailable()) {
            allowedTools.add(ToolDefinitions.TOOL_SEARCH_MEMORY);
        } else {
            log.info("search_memory not registered: long-term memory is off for this request");
        }

        // 硬安全网
        List<String> ragToolSet = List.of(
                ToolDefinitions.TOOL_KNOWLEDGE_SEARCH, ToolDefinitions.TOOL_GREP_CHUNKS,
                ToolDefinitions.TOOL_LIST_KNOWLEDGE_CHUNKS, ToolDefinitions.TOOL_QUERY_KNOWLEDGE_GRAPH,
                ToolDefinitions.TOOL_GET_DOCUMENT_INFO, ToolDefinitions.TOOL_DATABASE_QUERY);
        List<String> allWikiToolSet = List.of(
                ToolDefinitions.TOOL_WIKI_READ_PAGE, ToolDefinitions.TOOL_WIKI_SEARCH,
                ToolDefinitions.TOOL_WIKI_READ_SOURCE_DOC, ToolDefinitions.TOOL_WIKI_FLAG_ISSUE,
                ToolDefinitions.TOOL_WIKI_WRITE_PAGE, ToolDefinitions.TOOL_WIKI_REPLACE_TEXT,
                ToolDefinitions.TOOL_WIKI_RENAME_PAGE, ToolDefinitions.TOOL_WIKI_DELETE_PAGE,
                ToolDefinitions.TOOL_WIKI_READ_ISSUE, ToolDefinitions.TOOL_WIKI_UPDATE_ISSUE);
        if (!hasWikiKb) {
            allowedTools.removeIf(allWikiToolSet::contains);
        }
        if (!hasVectorKb) {
            allowedTools.removeIf(ragToolSet::contains);
        }

        // Dedup 保序
        allowedTools = new ArrayList<>(new java.util.LinkedHashSet<>(allowedTools));
        return new ToolPlan(allowedTools, wikiScopes, wikiKbIds, wikiRoutes);
    }

    /** 域侧外部工具（内置工具由 agent-engine 的工厂注册）。 */
    private List<AgentTool> buildTools(QaAgentConfig config, ToolPlan plan, Reranker rerankModel,
            String sessionId) {
        List<AgentTool> out = new ArrayList<>();
        String toolOwnerId = SessionOwnerIds.currentSessionOwnerId();
        for (String toolName : plan.allowedToolNames()) {
            AgentTool toolToRegister = null;
            switch (toolName) {
                // 思考/待办是**引擎内置工具**：由 agent-engine 的工厂按同一份允许清单注册
                case ToolDefinitions.TOOL_THINKING, ToolDefinitions.TOOL_TODO_WRITE -> {
                }
                // 检索/会话/记忆/DB 族：seam → 真实服务经 AgentToolBackends
                case ToolDefinitions.TOOL_KNOWLEDGE_SEARCH, ToolDefinitions.TOOL_GREP_CHUNKS,
                        ToolDefinitions.TOOL_LIST_KNOWLEDGE_CHUNKS,
                        ToolDefinitions.TOOL_QUERY_KNOWLEDGE_GRAPH,
                        ToolDefinitions.TOOL_GET_DOCUMENT_INFO,
                        ToolDefinitions.TOOL_SEARCH_CONVERSATIONS,
                        ToolDefinitions.TOOL_SEARCH_MEMORY, ToolDefinitions.TOOL_DATABASE_QUERY,
                        ToolDefinitions.TOOL_DATA_SCHEMA, ToolDefinitions.TOOL_DATA_ANALYSIS ->
                        toolToRegister = toolBackends.createTool(toolName,
                                config.getSearchTargets(), rerankModel, toolOwnerId, sessionId);
                // wiki 族 10 件
                case ToolDefinitions.TOOL_WIKI_READ_PAGE, ToolDefinitions.TOOL_WIKI_SEARCH,
                        ToolDefinitions.TOOL_WIKI_READ_SOURCE_DOC, ToolDefinitions.TOOL_WIKI_FLAG_ISSUE,
                        ToolDefinitions.TOOL_WIKI_WRITE_PAGE, ToolDefinitions.TOOL_WIKI_REPLACE_TEXT,
                        ToolDefinitions.TOOL_WIKI_RENAME_PAGE, ToolDefinitions.TOOL_WIKI_DELETE_PAGE,
                        ToolDefinitions.TOOL_WIKI_READ_ISSUE, ToolDefinitions.TOOL_WIKI_UPDATE_ISSUE ->
                        toolToRegister = toolBackends.createWikiTool(toolName,
                                config.getSearchTargets(), plan.wikiScopes(), plan.wikiKbIds(), plan.wikiRoutes());
                // web 两件
                case ToolDefinitions.TOOL_WEB_SEARCH, ToolDefinitions.TOOL_WEB_FETCH ->
                        toolToRegister = toolBackends.createWebTool(toolName,
                                config.getWebSearchMaxResults(), config.getWebSearchProviderId());
                case ToolDefinitions.TOOL_SHELL_EXEC, ToolDefinitions.TOOL_READ_FILE,
                        ToolDefinitions.LEGACY_TOOL_READ_SKILL, ToolDefinitions.LEGACY_TOOL_EXECUTE_SKILL_SCRIPT,
                        ToolDefinitions.TOOL_LIST_SANDBOX_FILES, ToolDefinitions.LEGACY_TOOL_READ_SANDBOX_FILE,
                        ToolDefinitions.TOOL_WRITE_SANDBOX_FILE, ToolDefinitions.TOOL_EDIT_SANDBOX_FILE -> {
                    // Bound to the resolved sandbox manager in the file/shell registration
                    // steps（同款 continue；dev 无 sandbox → 恒走此分支跳过）
                }
                default -> log.warn("Unknown tool: {}", toolName);
            }
            if (toolToRegister != null) {
                if (!toolToRegister.getName().equals(toolName)) {
                    log.warn("Tool name mismatch: expected {}, got {}", toolName, toolToRegister.getName());
                }
                out.add(toolToRegister);
            }
        }
        log.info("Assembled {} external tool(s)", out.size());
        return out;
    }

}
