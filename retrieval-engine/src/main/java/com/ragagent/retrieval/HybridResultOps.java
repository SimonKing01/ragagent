package com.ragagent.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.contract.knowledge.ChunkFacts;
import com.ragagent.contract.knowledge.KnowledgeDocumentFacts;
import com.ragagent.contract.retrieval.SearchResult;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.platform.web.JsonMappers;
import com.ragagent.retrieval.engine.EngineTypes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * HybridSearch 的结果装配簇：命中 → SearchResult（chunk 元数据补全 + FAQ 问题回填）。
 */
final class HybridResultOps {


    private final HybridSearchService service;

    HybridResultOps(HybridSearchService service) {
        this.service = service;
    }

    // ── 结果装配 ─────────────────────────────────────────────────────────

    List<SearchResult> processSearchResults(
            List<EngineTypes.IndexWithScore> chunks, boolean skipEnrichment) {
        if (chunks.isEmpty()) {
            return null;
        }
        Long tenantId = TenantContext.currentTenantId();

        Set<String> knowledgeIds = new LinkedHashSet<>();
        List<String> chunkIds = new ArrayList<>();
        Map<String, Double> scores = new HashMap<>();
        Map<String, Integer> matchTypes = new HashMap<>();
        Map<String, String> matchedContents = new HashMap<>();
        for (EngineTypes.IndexWithScore c : chunks) {
            knowledgeIds.add(c.knowledgeId);
            chunkIds.add(c.chunkId);
            scores.put(c.chunkId, c.score);
            matchTypes.put(c.chunkId, c.matchType);
            matchedContents.put(c.chunkId, c.content);
        }

        List<KnowledgeDocumentFacts> knowledgeList = service.documentGateway.findAccessibleDocuments(
                tenantId == null ? 0 : tenantId, new ArrayList<>(knowledgeIds));
        Map<String, KnowledgeDocumentFacts> knowledgeMap = new HashMap<>();
        for (KnowledgeDocumentFacts k : knowledgeList) {
            knowledgeMap.put(k.id(), k);
        }

        List<ChunkFacts> allChunks = tenantId == null ? List.of()
                : service.chunkGateway.findChunks(tenantId, chunkIds);
        Map<String, ChunkFacts> chunkMap = new HashMap<>();
        for (ChunkFacts c : allChunks) {
            chunkMap.put(c.id(), c);
        }

        // 上下文扩展：父块 / 相邻块 / 关系块——**取回后必须输出**（此前只读不输出，
        // 请求里 skipContextEnrichment=false 也拿不到任何上下文块，那些读取全是白读）。
        // 扩展块登记到来源命中名下，装配时紧随其后。
        Map<String, List<String>> extrasBySource = new LinkedHashMap<>();
        Map<String, Integer> extraMatchTypes = new HashMap<>();
        if (!skipEnrichment) {
            Set<String> processed = new HashSet<>();
            for (ChunkFacts c : allChunks) {
                processed.add(c.id());
            }
            Set<String> extraKnowledgeIds = new LinkedHashSet<>();
            for (ChunkFacts c : allChunks) {
                List<String> extras = new ArrayList<>();
                if (!c.parentChunkId().isEmpty() && processed.add(c.parentChunkId())) {
                    extras.add(c.parentChunkId());
                    extraMatchTypes.put(c.parentChunkId(), HybridSearchService.MATCH_PARENT_CHUNK);
                }
                for (String rel : relatedChunkIds(c, processed)) {
                    extras.add(rel);
                    extraMatchTypes.put(rel, HybridSearchService.MATCH_RELATION_CHUNK);
                }
                if ("text".equals(c.chunkType())) {
                    if (!c.nextChunkId().isEmpty() && processed.add(c.nextChunkId())) {
                        extras.add(c.nextChunkId());
                        extraMatchTypes.put(c.nextChunkId(), HybridSearchService.MATCH_NEAR_BY_CHUNK);
                    }
                    if (!c.preChunkId().isEmpty() && processed.add(c.preChunkId())) {
                        extras.add(c.preChunkId());
                        extraMatchTypes.put(c.preChunkId(), HybridSearchService.MATCH_NEAR_BY_CHUNK);
                    }
                }
                if (extras.isEmpty()) {
                    continue;
                }
                extrasBySource.put(c.id(), extras);
                for (String aid : extras) {
                    ChunkFacts extra = fetchChunk(tenantId, aid);
                    if (extra == null) {
                        continue;
                    }
                    chunkMap.put(extra.id(), extra);
                    // 扩展块继承来源命中的分数（同源上下文；父块原有语义推广到全部扩展块）。
                    scores.put(aid, scores.getOrDefault(c.id(), 0.0));
                    if (!knowledgeMap.containsKey(extra.knowledgeId())) {
                        extraKnowledgeIds.add(extra.knowledgeId());
                    }
                }
            }
            // 关系块可能跨文档：其文档同样要过可访问性闸门（与主命中同一道）。
            if (!extraKnowledgeIds.isEmpty()) {
                for (KnowledgeDocumentFacts k : service.documentGateway.findAccessibleDocuments(
                        tenantId, new ArrayList<>(extraKnowledgeIds))) {
                    knowledgeMap.put(k.id(), k);
                }
            }
        }

        // 装配：按输入顺序；每个命中后紧随其上下文扩展块。
        List<SearchResult> out = new ArrayList<>();
        Set<String> added = new HashSet<>();
        for (EngineTypes.IndexWithScore input : chunks) {
            ChunkFacts chunk = chunkMap.get(input.chunkId);
            if (chunk == null || !isSearchableChunk(chunk) || !added.add(chunk.id())) {
                continue;
            }
            KnowledgeDocumentFacts knowledge = knowledgeMap.get(chunk.knowledgeId());
            if (knowledge == null) {
                continue;
            }
            out.add(buildSearchResult(chunk, knowledge,
                    scores.getOrDefault(chunk.id(), 0.0),
                    matchTypes.getOrDefault(chunk.id(), HybridSearchService.MATCH_EMBEDDING),
                    matchedContents.getOrDefault(chunk.id(), "")));
            for (String aid : extrasBySource.getOrDefault(chunk.id(), List.of())) {
                ChunkFacts extra = chunkMap.get(aid);
                if (extra == null || !isSearchableChunk(extra) || !added.add(extra.id())) {
                    continue;
                }
                KnowledgeDocumentFacts extraKnowledge = knowledgeMap.get(extra.knowledgeId());
                if (extraKnowledge == null) {
                    continue;
                }
                out.add(buildSearchResult(extra, extraKnowledge, scores.getOrDefault(aid, 0.0),
                        extraMatchTypes.getOrDefault(aid, HybridSearchService.MATCH_EMBEDDING), ""));
            }
        }
        return out;
    }


    ChunkFacts fetchChunk(Long tenantId, String id) {
        try {
            List<ChunkFacts> rows = service.chunkGateway.findChunks(tenantId, List.of(id));
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    /** 判定 chunk 可否进入检索结果：须启用、索引状态非 processing/failed、类型在支持列表内。 */
    static boolean isSearchableChunk(ChunkFacts chunk) {
        if (chunk == null || !chunk.enabled()) {
            return false;
        }
        String status = chunk.indexStatus();
        if ("processing".equals(status) || "failed".equals(status)) {
            return false;
        }
        return List.of("text", "summary", "table_column", "table_summary",
                "faq", "image_ocr", "image_caption").contains(chunk.chunkType());
    }

    /** 由 chunk 与其知识文档装配一条 SearchResult。 */
    static SearchResult buildSearchResult(ChunkFacts chunk, KnowledgeDocumentFacts knowledge,
            double score, int matchType, String matchedContent) {
        SearchResult r = new SearchResult();
        r.setId(chunk.id());
        r.setContent(chunk.content());
        r.setContentRevision(chunk.contentRevision());
        r.setKnowledgeId(chunk.knowledgeId());
        r.setChunkIndex(chunk.chunkIndex());
        r.setKnowledgeTitle(knowledge.title());
        r.setStartAt(chunk.startAt());
        r.setEndAt(chunk.endAt());
        r.setSeq(chunk.chunkIndex());
        r.setScore(score);
        r.setMatchType(matchType);
        // metadata 装配：null → 空 map（序列化为 "{}"）；仅当所有值都是字符串才产出
        // map，出现非字符串值或结构不可解析 → null。
        JsonNode meta = JsonMappers.toJsonNode(knowledge.metadata());
        if (meta == null || meta.isNull()) {
            r.setMetadata(new LinkedHashMap<>());
        } else if (meta.isObject()) {
            Map<String, String> m = new LinkedHashMap<>();
            boolean allStrings = true;
            for (var it = meta.fields(); it.hasNext(); ) {
                var e = it.next();
                if (e.getValue() == null || !e.getValue().isTextual()) {
                    allStrings = false;
                    break;
                }
                m.put(e.getKey(), e.getValue().asText());
            }
            r.setMetadata(allStrings ? m : null);
        } else {
            r.setMetadata(null);
        }
        r.setChunkType(chunk.chunkType());
        r.setParentChunkId(chunk.parentChunkId());
        r.setKnowledgeFilename(knowledge.fileName());
        r.setKnowledgeSource(knowledge.source());
        r.setKnowledgeChannel(knowledge.channel());
        r.setKnowledgeDescription(knowledge.description());
        r.setKnowledgeBaseId(knowledge.knowledgeBaseId());
        r.setMatchedContent(matchedContent);
        return r;
    }


    static List<String> relatedChunkIds(ChunkFacts chunk, Set<String> processed) {
        List<String> related = new ArrayList<>();
        JsonNode rel = JsonMappers.toJsonNode(chunk.relationChunks());
        if (rel != null && rel.isArray()) {
            for (JsonNode n : rel) {
                String id = n.asText("");
                if (!id.isEmpty() && !processed.contains(id)) {
                    related.add(id);
                    processed.add(id);
                }
            }
        }
        return related;
    }
}
