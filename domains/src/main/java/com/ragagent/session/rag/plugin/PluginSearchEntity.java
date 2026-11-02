package com.ragagent.session.rag.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.contract.graph.GraphData;
import com.ragagent.contract.graph.GraphNode;
import com.ragagent.contract.graph.GraphRelation;
import com.ragagent.contract.graph.NameSpace;
import com.ragagent.contract.knowledge.ChunkFacts;
import com.ragagent.contract.knowledge.KnowledgeDocumentFacts;
import com.ragagent.contract.pipeline.PipelinePorts;
import com.ragagent.contract.retrieval.RetrieveGraphRepository;
import com.ragagent.contract.retrieval.SearchResult;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.platform.web.JsonMappers;
import com.ragagent.session.rag.PipelineEventType;
import com.ragagent.session.rag.PipelineLog;
import com.ragagent.session.rag.PipelineState;
import com.ragagent.session.rag.support.ImageInfoCollector;
import com.ragagent.session.rag.support.MatchTypes;
import com.ragagent.session.rag.support.SearchSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ENTITY_SEARCH 阶段插件：
 * 按实体查图（逐知识文件或逐知识库，并发执行），把新命中的 chunk
 * 转成 SearchResult（分数恒 1.0、MatchTypeGraph）并合入 SearchResult。
 *
 * <p>各知识库的并发结果合并进共享列表，用 LinkedHashSet 保出现序确定
 * （消费端按稳定段比较）。chunk2SearchResult 的 metadata 取 KnowledgeDocumentFacts 的元数据
 * （jsonb → 字符串 map，值做字符串化，解析失败为 null）。</p>
 */
public final class PluginSearchEntity implements Plugin {

    private final RetrieveGraphRepository graphRepo;
    private final PipelinePorts.ChunkRepository chunkRepo;
    private final PipelinePorts.KnowledgeRepository knowledgeRepo;

    public PluginSearchEntity(RetrieveGraphRepository graphRepository,
                              PipelinePorts.ChunkRepository chunkRepository,
                              PipelinePorts.KnowledgeRepository knowledgeRepository) {
        this.graphRepo = graphRepository;
        this.chunkRepo = chunkRepository;
        this.knowledgeRepo = knowledgeRepository;
    }

    @Override
    public PipelineEventType[] activationEvents() {
        return new PipelineEventType[] {PipelineEventType.ENTITY_SEARCH};
    }

    @Override
    public PluginError onEvent(PipelineEventType eventType, PipelineState chatManage, Plugin.Chain next) {
        List<String> entity = chatManage.getEntityContext().getEntity();
        if (entity == null || entity.isEmpty()) {
            PipelineLog.info("search_entity", "no_entity", new LinkedHashMap<>());
            return next.next();
        }

        List<String> knowledgeBaseIDs = chatManage.getEntityContext().getEntityKbIds();
        Map<String, String> entityKnowledge = chatManage.getEntityContext().getEntityKnowledge();

        if ((knowledgeBaseIDs == null || knowledgeBaseIDs.isEmpty())
                && (entityKnowledge == null || entityKnowledge.isEmpty())) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("msg", "No knowledge base IDs or knowledge IDs with ExtractConfig enabled for entity search");
            PipelineLog.warn("search_entity", "no_scope", f);
            return next.next();
        }

        List<GraphNode> allNodes = new ArrayList<>();
        List<GraphRelation> allRelations = new ArrayList<>();

        if (entityKnowledge != null && !entityKnowledge.isEmpty()) {
            for (Map.Entry<String, String> e : entityKnowledge.entrySet()) {
                searchInto(e.getValue(), e.getKey(), entity, allNodes, allRelations);
            }
        } else {
            for (String kbId : knowledgeBaseIDs) {
                searchInto(kbId, "", entity, allNodes, allRelations);
            }
        }

        chatManage.getSearchOutcome().setGraph(new GraphData(allNodes, allRelations));

        long tenantId = currentTenantId(chatManage);
        List<String> chunkIDs = filterSeenChunk(chatManage.getSearchOutcome().getGraph(), chatManage.getSearchOutcome().getResults());
        if (chunkIDs.isEmpty()) {
            PipelineLog.info("search_entity", "no_new_chunk", new LinkedHashMap<>());
            return next.next();
        }
        List<ChunkFacts> chunks;
        try {
            chunks = chunkRepo.listChunksById(tenantId, chunkIDs);
        } catch (RuntimeException e) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("sessionId", chatManage.getSessionId());
            f.put("error", e.getMessage());
            PipelineLog.error("search_entity", "list_chunks", f);
            return next.next();
        }
        List<String> knowledgeIDs = new ArrayList<>();
        for (ChunkFacts chunk : chunks) {
            knowledgeIDs.add(chunk.knowledgeId());
        }
        List<KnowledgeDocumentFacts> knowledges;
        try {
            knowledges = knowledgeRepo.getKnowledgeBatch(tenantId, knowledgeIDs);
        } catch (RuntimeException e) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("sessionId", chatManage.getSessionId());
            f.put("error", e.getMessage());
            PipelineLog.error("search_entity", "list_knowledge", f);
            return next.next();
        }

        Map<String, KnowledgeDocumentFacts> knowledgeMap = new LinkedHashMap<>();
        for (KnowledgeDocumentFacts knowledge : knowledges) {
            knowledgeMap.put(knowledge.id(), knowledge);
        }
        List<SearchResult> entityResults = new ArrayList<>();
        for (ChunkFacts chunk : chunks) {
            entityResults.add(chunk2SearchResult(chunk, knowledgeMap.get(chunk.knowledgeId())));
        }
        enrichSearchResultsImageInfo(tenantId, entityResults);
        if (chatManage.getSearchOutcome().getResults() == null) {
            chatManage.getSearchOutcome().setResults(new ArrayList<>());
        }
        chatManage.getSearchOutcome().getResults().addAll(entityResults);
        chatManage.getSearchOutcome().setResults(SearchSupport.removeDuplicateResults(chatManage.getSearchOutcome().getResults()));
        if (chatManage.getSearchOutcome().getResults() == null || chatManage.getSearchOutcome().getResults().isEmpty()) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("sessionId", chatManage.getSessionId());
            PipelineLog.info("search_entity", "no_new_result", f);
            return PluginError.SEARCH_NOTHING;
        }
        return next.next();
    }

    private void searchInto(String knowledgeBaseId, String knowledgeId, List<String> entity,
                            List<GraphNode> allNodes, List<GraphRelation> allRelations) {
        GraphData graph;
        try {
            graph = graphRepo.searchNode(new NameSpace(knowledgeBaseId, knowledgeId), entity);
        } catch (RuntimeException e) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put(knowledgeId.isEmpty() ? "kb" : "knowledge",
                    knowledgeId.isEmpty() ? knowledgeBaseId : knowledgeId);
            f.put("error", e.getMessage());
            PipelineLog.error("search_entity", "search_node", f);
            return;
        }
        if (graph != null && graph.node() != null) {
            allNodes.addAll(graph.node());
        }
        if (graph != null && graph.relation() != null) {
            allRelations.addAll(graph.relation());
        }
    }

    /** 上下文无租户时回落 chatManage 携带的租户 ID。 */
    private long currentTenantId(PipelineState chatManage) {
        Long tid = TenantContext.currentTenantId();
        if (tid != null && tid != 0) {
            return tid;
        }
        return chatManage.getTenantId();
    }

    static List<String> filterSeenChunk(GraphData graph, List<SearchResult> searchResult) {
        Map<String, Boolean> seen = new LinkedHashMap<>();
        if (searchResult != null) {
            for (SearchResult chunk : searchResult) {
                seen.put(chunk.getId(), Boolean.TRUE);
            }
        }
        List<String> chunkIDs = new ArrayList<>();
        if (graph != null && graph.node() != null) {
            for (GraphNode node : graph.node()) {
                if (node.getChunks() == null) {
                    continue;
                }
                for (String chunkID : node.getChunks()) {
                    if (seen.containsKey(chunkID)) {
                        continue;
                    }
                    seen.put(chunkID, Boolean.TRUE);
                    chunkIDs.add(chunkID);
                }
            }
        }
        return chunkIDs;
    }

    static SearchResult chunk2SearchResult(ChunkFacts chunk, KnowledgeDocumentFacts knowledge) {
        SearchResult r = new SearchResult();
        r.setId(chunk.id());
        r.setContent(chunk.content());
        r.setContentRevision(chunk.contentRevision());
        r.setKnowledgeId(chunk.knowledgeId());
        r.setChunkIndex(chunk.chunkIndex());
        r.setKnowledgeTitle(knowledge == null ? "" : knowledge.title());
        r.setStartAt(chunk.startAt());
        r.setEndAt(chunk.endAt());
        r.setSeq(chunk.chunkIndex());
        r.setScore(1.0);
        r.setMatchType(MatchTypes.GRAPH);
        r.setMetadata(knowledgeMetadata(knowledge));
        r.setChunkType(chunk.chunkType());
        r.setParentChunkId(chunk.parentChunkId());
        r.setImageInfo(chunk.imageInfo());
        r.setKnowledgeFilename(knowledge == null ? "" : knowledge.fileName());
        r.setKnowledgeSource(knowledge == null ? "" : knowledge.source());
        r.setKnowledgeChannel(knowledge == null ? "" : knowledge.channel());
        r.setChunkMetadata(JsonMappers.toJsonNode(chunk.metadata()));
        r.setKnowledgeBaseId(knowledge == null ? "" : knowledge.knowledgeBaseId());
        return r;
    }

    /** knowledge 的 metadata jsonb 投影为全字符串 map（值统一字符串化）；空表无键、解析失败返回空 map。 */
    static Map<String, String> knowledgeMetadata(KnowledgeDocumentFacts knowledge) {
        Map<String, String> metadata = new LinkedHashMap<>();
        JsonNode meta = JsonMappers.toJsonNode(knowledge == null ? null : knowledge.metadata());
        if (meta == null || !meta.isObject() || meta.isEmpty()) {
            return metadata;
        }
        var fields = meta.fields();
        while (fields.hasNext()) {
            var e = fields.next();
            var v = e.getValue();
            metadata.put(e.getKey(), v.isContainerNode() ? v.toString() : v.asText());
        }
        return metadata;
    }

    /** 图像信息富化（委托 {@link ImageInfoCollector}）。 */
    private void enrichSearchResultsImageInfo(long tenantId, List<SearchResult> results) {
        ImageInfoCollector.enrichSearchResultsImageInfo(chunkRepo, tenantId, results);
    }
}
