package com.ragagent.session.rag.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.contract.graph.EntityExtractionResult;
import com.ragagent.contract.graph.GraphNode;
import com.ragagent.contract.knowledge.KnowledgeBaseView;
import com.ragagent.contract.knowledge.KnowledgeDocumentFacts;
import com.ragagent.contract.model.EntityExtractionPort;
import com.ragagent.contract.model.LlmChatClient;
import com.ragagent.contract.pipeline.PipelinePorts;
import com.ragagent.platform.prompt.PipelineConfig;
import com.ragagent.platform.web.JsonMappers;
import com.ragagent.session.rag.PipelineEventType;
import com.ragagent.session.rag.PipelineState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * QUERY_UNDERSTAND 附加插件：
 * 图谱抽取（NEO4J_ENABLE=true 才生效）——按 ExtractConfig 命中的知识库跑实体抽取，
 * 抽出的实体名挂到 chatManage.Entity（ENTITY_SEARCH 阶段消费）。
 *
 * <p>实体集合用 LinkedHashMap 保出现序（EntityKBIDs/EntityKnowledge 顺序确定，
 * 消费端 search_entity 不依赖顺序）。模板经 PipelineConfig 传入，例子仅取
 * Description/Examples。</p>
 */
public final class PluginExtractEntity implements Plugin {

    private final PipelinePorts.ModelService modelService;
    private final PipelineConfig.PromptTemplateStructured template;
    private final PipelinePorts.KnowledgeBaseRepository knowledgeBaseRepo;
    private final PipelinePorts.KnowledgeService knowledgeService;
    private final boolean neo4jEnabled;
    private final EntityExtractionPort entityExtraction;

    public PluginExtractEntity(PipelinePorts.ModelService modelService,
                               PipelineConfig.PromptTemplateStructured template,
                               PipelinePorts.KnowledgeBaseRepository knowledgeBaseRepo,
                               PipelinePorts.KnowledgeService knowledgeService,
                               boolean neo4jEnabled,
                               EntityExtractionPort entityExtraction) {
        this.modelService = modelService;
        this.template = template;
        this.knowledgeBaseRepo = knowledgeBaseRepo;
        this.knowledgeService = knowledgeService;
        // 环境开关 NEO4J_ENABLE 在装配期解析传入
        this.neo4jEnabled = neo4jEnabled;
        this.entityExtraction = entityExtraction;
    }

    @Override
    public PipelineEventType[] activationEvents() {
        return new PipelineEventType[] {PipelineEventType.QUERY_UNDERSTAND};
    }

    @Override
    public PluginError onEvent(PipelineEventType eventType, PipelineState chatManage, Plugin.Chain next) {
        if (!neo4jEnabled) {
            return next.next();
        }

        String query = chatManage.getQuery();

        LlmChatClient model;
        try {
            model = modelService.getChatModel(chatManage.getChatModelId());
        } catch (RuntimeException e) {
            return next.next();
        }

        // 收集全部 KB ID（含共享 KB 文档）
        Map<String, Boolean> kbIdSet = new LinkedHashMap<>();
        for (String id : chatManage.getKnowledgeBaseIds()) {
            kbIdSet.put(id, Boolean.TRUE);
        }

        Map<String, String> knowledgeToKBMap = new LinkedHashMap<>();
        if (chatManage.getKnowledgeIds() != null && !chatManage.getKnowledgeIds().isEmpty()) {
            List<KnowledgeDocumentFacts> knowledges;
            try {
                knowledges = knowledgeService.getKnowledgeBatchWithSharedAccess(
                        chatManage.getTenantId(), chatManage.getKnowledgeIds());
            } catch (RuntimeException e) {
                return next.next();
            }
            for (KnowledgeDocumentFacts k : knowledges) {
                kbIdSet.put(k.knowledgeBaseId(), Boolean.TRUE);
                knowledgeToKBMap.put(k.id(), k.knowledgeBaseId());
            }
        }

        List<KnowledgeBaseView> kbs;
        try {
            kbs = knowledgeBaseRepo.getKnowledgeBaseByIds(new ArrayList<>(kbIdSet.keySet()));
        } catch (RuntimeException e) {
            return next.next();
        }

        Map<String, Boolean> enabledKBSet = new LinkedHashMap<>();
        for (KnowledgeBaseView kb : kbs) {
            if (extractEnabled(kb)) {
                enabledKBSet.put(kb.getId(), Boolean.TRUE);
            }
        }
        if (enabledKBSet.isEmpty()) {
            return next.next();
        }

        chatManage.getEntityContext().setEntityKbIds(new ArrayList<>(enabledKBSet.keySet()));

        Map<String, String> entityKnowledge = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : knowledgeToKBMap.entrySet()) {
            if (enabledKBSet.containsKey(e.getValue())) {
                entityKnowledge.put(e.getKey(), e.getValue());
            }
        }
        chatManage.getEntityContext().setEntityKnowledge(entityKnowledge);

        PipelineConfig.PromptTemplateStructured tpl = new PipelineConfig.PromptTemplateStructured();
        tpl.setDescription(template == null ? "" : template.getDescription());
        tpl.setExamples(template == null ? null : template.getExamples());
        EntityExtractionResult graph;
        try {
            graph = entityExtraction.extract(model, tpl, query);
        } catch (RuntimeException e) {
            return next.next();
        }
        List<String> nodes = new ArrayList<>();
        for (GraphNode node : graph.nodes()) {
            nodes.add(node.getName());
        }
        chatManage.getEntityContext().setEntity(nodes);
        return next.next();
    }

    /** extract_config jsonb 的 enabled 开关（缺省关）。 */
    private static boolean extractEnabled(KnowledgeBaseView kb) {
        JsonNode cfg = JsonMappers.toJsonNode(kb.getExtractConfig());
        return cfg != null && !cfg.isNull()
                && cfg.has("enabled") && cfg.path("enabled").asBoolean(false);
    }
}
