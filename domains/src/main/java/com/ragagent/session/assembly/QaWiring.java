package com.ragagent.session.assembly;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.agenttools.datasource.DataAnalysisSessionFactoryAdapter;
import com.ragagent.contract.knowledge.KnowledgeBaseRowView;
import com.ragagent.contract.knowledge.KnowledgeBaseView;
import com.ragagent.contract.knowledge.KnowledgeCatalogPort;
import com.ragagent.contract.knowledge.KnowledgeDocumentFacts;
import com.ragagent.contract.knowledge.projection.KnowledgeBaseViews;
import com.ragagent.contract.knowledge.projection.KnowledgeProjectionAdapter;
import com.ragagent.contract.model.EntityExtractionPort;
import com.ragagent.contract.model.LlmChatClient;
import com.ragagent.contract.model.ModelCatalogPort;
import com.ragagent.contract.model.ModelCatalogPort.ModelRowView;
import com.ragagent.contract.model.ModelRuntime;
import com.ragagent.contract.model.Reranker;
import com.ragagent.contract.pipeline.PipelinePorts;
import com.ragagent.contract.retrieval.HybridSearchPort;
import com.ragagent.contract.retrieval.RetrieveGraphRepository;
import com.ragagent.contract.retrieval.SearchParams;
import com.ragagent.contract.retrieval.SearchResult;
import com.ragagent.contract.tenant.TenantConfigLookup;
import com.ragagent.contract.tenant.WebSearchConfig;
import com.ragagent.contract.websearch.PipelinePageFetcher;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.platform.prompt.PipelineConfig;
import com.ragagent.platform.prompt.PromptSource;
import com.ragagent.platform.settings.ConversationProperties;
import com.ragagent.platform.web.JsonMappers;
import com.ragagent.session.conversation.MessageService;
import com.ragagent.session.dto.PipelineMessageImageView;
import com.ragagent.session.dto.PipelineMessageView;
import com.ragagent.session.rag.EventManager;
import com.ragagent.session.rag.PipelineMessageStore;
import com.ragagent.session.rag.plugin.PluginChatCompletion;
import com.ragagent.session.rag.plugin.PluginChatCompletionStream;
import com.ragagent.session.rag.plugin.PluginDataAnalysis;
import com.ragagent.session.rag.plugin.PluginExtractEntity;
import com.ragagent.session.rag.plugin.PluginFilterTopK;
import com.ragagent.session.rag.plugin.PluginIntoChatMessage;
import com.ragagent.session.rag.plugin.PluginLoadHistory;
import com.ragagent.session.rag.plugin.PluginMemoryAffinity;
import com.ragagent.session.rag.plugin.PluginMemoryRecall;
import com.ragagent.session.rag.plugin.PluginMerge;
import com.ragagent.session.rag.plugin.PluginQueryUnderstand;
import com.ragagent.session.rag.plugin.PluginRerank;
import com.ragagent.session.rag.plugin.PluginSearch;
import com.ragagent.session.rag.plugin.PluginSearchEntity;
import com.ragagent.session.rag.plugin.PluginSearchParallel;
import com.ragagent.session.rag.plugin.PluginWebFetch;
import com.ragagent.session.rag.plugin.PluginWikiBoost;
import com.ragagent.session.runtime.SessionModels;
import com.ragagent.session.support.PipelineViews;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * chat 管线 + QA 面装配（docs/known-issues/05-wave-4.md 的 11 seam 清单）。
 *
 * <p>全部 adapter 是<b>纯新增</b>文件里的静态/内部类，不改既有 service。未装配的
 * seam 在插件侧按缺省值处理（Java 传 null 等价）。</p>
 *
 * <h2>实现边界</h2>
 * <ul>
 *   <li><b>HybridSearch 执行面</b>：adapter 委托
 *       {@code HybridSearchPort}（pgvector + ParadeDB BM25 + RRF 融合 +
 *       FAQ 迭代/负例过滤 + 富化装配）。
 *       外部向量店（ES/milvus/…）绑定仍按 2201 unavailable 同形拒绝（provider 批）。</li>
 *   <li><b>RetrieveGraphRepository（已接线）</b>：注入 {@code Neo4jGraphConfig} 提供的
 *       {@code Neo4jGraphRepository}——NEO4J_ENABLE 未启用时其 driver 为 null，检索返回
 *       null（driver 为 null 的分支；ExtractEntity/SearchEntity 同样有 neo4jEnabled 闸门）。</li>
 *   <li><b>WebSearchStateService / WebSearchProviderRepository</b>：存而不读，
 *       传 null。</li>
 * </ul>
 */
@Configuration
public class QaWiring {

    private static final Logger log = LoggerFactory.getLogger(QaWiring.class);

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    public QaWiring() {
    }

    // ── conversation 配置 ────────────
    // ConversationProperties 自 起由 @ConfigurationPropertiesScan 注册
    // （yml conversation.* 真正绑定进实例），模板回填在其 @PostConstruct 完成；
    // 此处的手工 @Bean 会让绑定面变成死键，已删除。

    // ── 11 seam 的生产 adapter ────────────────────────────────────────────────

    /** {@link PipelinePorts.ModelService}：getChatModel/getRerankModel 的运行时工厂。 */
    @Bean
    public PipelinePorts.ModelService qaPipelineModelService(
            ModelCatalogPort modelCatalog,
            ModelRuntime modelRuntime) {
        return new PipelinePorts.ModelService() {
            @Override
            public LlmChatClient getChatModel(String modelId) {
                ModelRowView model = modelCatalog.findModel(modelId);
                if (model == null) {
                    return null;
                }
                // 取数口径留在此处（findModel 的状态闸门）；构造走 model-runtime 工厂面
                // langfuse generation 装饰（未启用时原样返回）
                return SessionModels.chat(modelRuntime.chatClientFor(model));
            }

            @Override
            public Reranker getRerankModel(String modelId) {
                ModelRowView model = modelCatalog.findModel(modelId);
                if (model == null) {
                    return null;
                }
                // langfuse generation 装饰（未启用时原样返回）
                return SessionModels.rerank(modelRuntime.rerankerFor(model));
            }
        };
    }

    /**
     * chat_pipeline 所需租户信息的子集：
     * PluginSearch 的租户 web 配置读取——TenantContext 实时值 + 租户行合并。
     */
    @Bean
    public PipelinePorts.TenantService qaPipelineTenantService(
            TenantConfigLookup tenantService) {
        return new PipelinePorts.TenantService() {
            @Override
            public WebSearchConfig currentWebSearchConfig() {
                Long tid = TenantContext.currentTenantId();
                if (tid == null) {
                    return null;
                }
                try {
                    JsonNode webSearch = JsonMappers.toJsonNode(tenantService.webSearchConfig(tid));
                    if (webSearch == null || webSearch.isNull()) {
                        return null;
                    }
                    return JsonMappers.lenient().treeToValue(webSearch, WebSearchConfig.class);
                } catch (RuntimeException | JsonProcessingException e) {
                    return null;
                }
            }
        };
    }

    /** {@link PipelinePorts.KnowledgeBaseService} 的 chat_pipeline 子集。 */
    @Bean
    public PipelinePorts.KnowledgeBaseService qaPipelineKnowledgeBaseService(
            KnowledgeCatalogPort kbService, HybridSearchPort hybridSearchService) {
        return new PipelinePorts.KnowledgeBaseService() {
            @Override
            public KnowledgeBaseView getKnowledgeBaseByIdUnscoped(String id) {
                // 语义保持：仍走 getAllTenantById（含 ensureDefaults 的"索引策略零值 →
                // vector+keyword 默认"回填），投影在回填**之后**取值。
                KnowledgeBaseRowView kb = kbService.getAllTenantById(id);
                if (kb == null) {
                    // 插件取 KB 元数据失败 → BizError(1003) → 管线 500 信封
                    throw new PipelinePorts.PipelinePortException(
                            "error code: 1003, error message: knowledge base not found");
                }
                return KnowledgeBaseViews.view(kb);
            }

            @Override
            public List<KnowledgeBaseView> getKnowledgeBasesByIdsUnscoped(List<String> ids) {
                return ids.stream().map(kbService::getAllTenantById)
                        .filter(kb -> kb != null).map(KnowledgeBaseViews::view)
                        .collect(java.util.stream.Collectors.toList());
            }

            @Override
            public List<SearchResult> hybridSearch(String knowledgeBaseId, SearchParams params) {
                // KB 元数据缺失时保持既定的 1003 错误（A/B 场景 kse-unknown-kb 依赖）。
                if (kbService.getAllTenantById(knowledgeBaseId) == null) {
                    throw new PipelinePorts.PipelinePortException(
                            "error code: 1003, error message: knowledge base not found");
                }
                // 入参是值拷贝（归一化不回传调用方）——显式浅拷贝。
                SearchParams local = new SearchParams();
                local.setQueryText(params.getQueryText());
                local.setQueryEmbedding(params.getQueryEmbedding());
                local.setVectorThreshold(params.getVectorThreshold());
                local.setKeywordThreshold(params.getKeywordThreshold());
                local.setMatchCount(params.getMatchCount());
                local.setDisableKeywordsMatch(params.isDisableKeywordsMatch());
                local.setDisableVectorMatch(params.isDisableVectorMatch());
                local.setSkipContextEnrichment(params.isSkipContextEnrichment());
                local.setKnowledgeIds(params.getKnowledgeIds());
                local.setTagIds(params.getTagIds());
                local.setKnowledgeBaseIds(params.getKnowledgeBaseIds());
                return hybridSearchService.hybridSearch(knowledgeBaseId, local);
            }

            @Override
            public float[] getQueryEmbedding(String kbId, String queryText) {
                return hybridSearchService.getQueryEmbedding(kbId, queryText);
            }

            @Override
            public Map<String, String> resolveEmbeddingModelKeys(List<String> kbIds) {
                return hybridSearchService.resolveEmbeddingModelKeys(kbIds);
            }
        };
    }

    /** {@link PipelinePorts.KnowledgeService} 的 chat_pipeline 子集。 */
    @Bean
    public PipelinePorts.KnowledgeService qaPipelineKnowledgeService(KnowledgeCatalogPort knowledgeService) {
        return new PipelinePorts.KnowledgeService() {
            @Override
            public KnowledgeDocumentFacts getKnowledgeById(String id) {
                // 知识查询带租户过滤（从 TenantContext 取）
                return KnowledgeProjectionAdapter.factsOf(
                        knowledgeService.getKnowledgeInTenant(TenantContext.currentTenantId(), id));
            }

            @Override
            public List<KnowledgeDocumentFacts> getKnowledgeBatch(long tenantId, List<String> ids) {
                return KnowledgeProjectionAdapter.factsOf(knowledgeService.getKnowledgeBatch(tenantId, ids));
            }

            @Override
            public List<KnowledgeDocumentFacts> getKnowledgeBatchWithSharedAccess(
                    long tenantId, List<String> ids) {
                return KnowledgeProjectionAdapter.factsOf(
                        knowledgeService.getKnowledgeBatchWithSharedAccess(tenantId, ids));
            }
        };
    }

    /** {@link PipelinePorts.ChunkRepository} 的实现在 knowledge 域适配器，此处只按类型注入。 */

    /** {@link PipelinePorts.KnowledgeRepository}（getKnowledgeBatch）。 */
    @Bean
    public PipelinePorts.KnowledgeRepository qaPipelineKnowledgeRepository(KnowledgeCatalogPort knowledgeService) {
        return new PipelinePorts.KnowledgeRepository() {
            @Override
            public List<KnowledgeDocumentFacts> getKnowledgeBatch(long tenantId, List<String> ids) {
                return KnowledgeProjectionAdapter.factsOf(knowledgeService.getKnowledgeBatch(tenantId, ids));
            }
        };
    }

    /** {@link PipelinePorts.KnowledgeBaseRepository}（getKnowledgeBaseByIds）。 */
    @Bean
    public PipelinePorts.KnowledgeBaseRepository qaPipelineKnowledgeBaseRepository(
            KnowledgeCatalogPort kbService) {
        return new PipelinePorts.KnowledgeBaseRepository() {
            @Override
            public List<KnowledgeBaseView> getKnowledgeBaseByIds(List<String> ids) {
                return ids.stream().map(kbService::getAllTenantById)
                        .filter(kb -> kb != null).map(KnowledgeBaseViews::view)
                        .collect(java.util.stream.Collectors.toList());
            }
        };
    }

    /** {@link PipelineMessageStore} 的 chat_pipeline 子集。 */
    @Bean
    public PipelineMessageStore qaPipelineMessageService(MessageService messageService) {
        return new PipelineMessageStore() {
            @Override
            public PipelineMessageView getMessage(String sessionId, String messageId) {
                // 端口两端只认 common 载荷：出域（实体 → 载荷）在这层收敛
                return PipelineViews.ofMessage(messageService.getMessage(sessionId, messageId));
            }

            @Override
            public List<PipelineMessageView> getRecentMessagesBySession(String sessionId, int limit) {
                return PipelineViews.ofMessages(messageService.getRecentMessages(sessionId, limit));
            }

            @Override
            public void updateMessageImages(String sessionId, String messageId,
                                            List<PipelineMessageImageView> images) {
                messageService.updateMessageImages(sessionId, messageId,
                        PipelineViews.toImages(images));
            }

            @Override
            public void updateMessageRenderedContent(String sessionId, String messageId, String renderedContent) {
                messageService.updateMessageRenderedContent(sessionId, messageId, renderedContent);
            }
        };
    }

    /** {@link PipelinePorts.MemoryService} 的实现在 memory 域适配器，此处只按类型注入。 */

    /** {@link PipelinePorts.WebSearch} 的实现在 websearch 域适配器（管线/工具双面），此处只按类型注入。 */

    // ── EventManager（插件注册序） ───────────────

    @Bean
    public PipelineConfig chatPipelineConfig(ConversationProperties conv) {
        PipelineConfig config = new PipelineConfig();
        config.setRewritePromptSystem(conv.getRewritePromptSystem());
        config.setRewritePromptUser(conv.getRewritePromptUser());
        // intent prompts：intent_prompts.yaml 的 id→content。
        // 空表时 query_understand 走缺省意图提示。
        config.setIntentSystemPrompts(loadIntentPrompts());
        // ExtractEntity 的结构化模板：neo4j 未启用时插件在入口直通，模板留空。
        return config;
    }

    private static Map<String, String> loadIntentPrompts() {
        Map<String, String> out = new LinkedHashMap<>();
        try (InputStream in = PromptSource.open(PromptSource.INTENT_PROMPTS)) {
            if (in == null) {
                return out;
            }
            Object raw = new org.yaml.snakeyaml.Yaml().load(in);
            JsonNode root = MAPPER.valueToTree(raw);
            JsonNode list = root.get("templates");
            if (list != null && list.isArray()) {
                for (JsonNode t : list) {
                    String id = t.path("id").asText("");
                    if (!id.isEmpty()) {
                        out.put(id, t.path("content").asText(""));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("load intent prompts failed", e);
        }
        return out;
    }

    /**
     * Bean 注册序（与 chatpipeline 事件管理器的装配顺序一致）。
     * RetrieveGraphRepository 在 neo4j 未启用时由实现返回 null（见类注释的取舍）。
     * DataAnalysisSessionFactory：dev 无分析面，工厂恒返回不可用会话——
     * DATA_ANALYSIS 阶段只在 MergeResult 出现 CSV/Excel 命中时进入。
     */
    @Bean
    public EventManager chatPipelineEventManager(
            PipelinePorts.ModelService modelService,
            PipelinePorts.KnowledgeBaseService knowledgeBaseService,
            PipelinePorts.KnowledgeService knowledgeService,
            PipelinePorts.ChunkRepository chunkRepository,
            PipelinePorts.KnowledgeRepository knowledgeRepository,
            PipelinePorts.KnowledgeBaseRepository knowledgeBaseRepository,
            PipelineMessageStore messageService,
            PipelinePorts.MemoryService memoryService,
            PipelinePorts.WebSearch webSearch,
            PipelinePorts.TenantService tenantService,
            RetrieveGraphRepository retrieveGraphRepository,
            PipelinePageFetcher pipelinePageFetcher,
            EntityExtractionPort entityExtraction,
            PipelineConfig config) {

        EventManager mgr = new EventManager();
        // neo4j 未启用 → extractEntity 关闭（neo4jEnabled=false 直通）
        boolean neo4jEnabled = false;
        var extractEntityTemplate = new PipelineConfig.PromptTemplateStructured();

        // 插件注册顺序即执行链顺序
        PluginSearch searchPlugin = new PluginSearch(
                knowledgeBaseService, webSearch, tenantService);
        mgr.register(searchPlugin);
        mgr.register(new PluginRerank(modelService));
        mgr.register(new PluginWebFetch(pipelinePageFetcher));
        mgr.register(new PluginMerge(chunkRepository));
        mgr.register(new PluginDataAnalysis(modelService, knowledgeService,
                new DataAnalysisSessionFactoryAdapter()));
        mgr.register(new PluginIntoChatMessage(messageService));
        mgr.register(new PluginChatCompletion(modelService));
        mgr.register(new PluginChatCompletionStream(modelService));
        mgr.register(new PluginFilterTopK());
        mgr.register(new PluginQueryUnderstand(modelService, messageService, memoryService, config));
        mgr.register(new PluginLoadHistory(messageService));
        mgr.register(new PluginMemoryRecall(memoryService));
        mgr.register(new PluginExtractEntity(modelService, extractEntityTemplate,
                knowledgeBaseRepository, knowledgeService, neo4jEnabled, entityExtraction));
        PluginSearchEntity searchEntityPlugin = new PluginSearchEntity(
                retrieveGraphRepository, chunkRepository, knowledgeRepository);
        mgr.register(searchEntityPlugin);
        mgr.register(new PluginSearchParallel(searchPlugin, searchEntityPlugin));
        mgr.register(new PluginWikiBoost(knowledgeBaseService));
        mgr.register(new PluginMemoryAffinity(memoryService));
        return mgr;
    }
}
