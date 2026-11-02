package com.ragagent.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.contract.error.BizException;
import com.ragagent.contract.knowledge.ChunkFacts;
import com.ragagent.contract.knowledge.KnowledgeBaseSearchFacts;
import com.ragagent.contract.retrieval.SearchParams;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.platform.web.JsonMappers;
import com.ragagent.retrieval.HybridSearchService.RetrievalConfigView;
import com.ragagent.retrieval.HybridSearchService.StoreGroup;
import com.ragagent.retrieval.engine.EngineTypes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HybridSearch 的融合与 FAQ 后处理簇：RRF 融合/按分去重、FAQ 迭代取回（只涨 TopK，
 * 引擎分组复用）、负向问题过滤。融合作业为纯静态，FAQ 侧经由门面扇出与 chunk 网关。
 */
final class HybridFusionOps {

    private static final Logger log = LoggerFactory.getLogger(HybridFusionOps.class);

    private final HybridSearchService service;

    HybridFusionOps(HybridSearchService service) {
        this.service = service;
    }

    // ── 融合 ─────────────────────────────────────────────────────────────

    static List<EngineTypes.IndexWithScore> fuseOrDeduplicate(
            List<List<EngineTypes.IndexWithScore>> vectorStores,
            List<List<EngineTypes.IndexWithScore>> keywordStores, RetrievalConfigView rc) {
        if (keywordStores.isEmpty()) {
            return deduplicateByScore(vectorStores);
        }
        if (vectorStores.isEmpty()) {
            return deduplicateByScore(keywordStores);
        }
        return fuseWithRRF(vectorStores, keywordStores, rc);
    }


    static final Comparator<EngineTypes.IndexWithScore> SCORE_DESC = (a, b) -> {
        int c = Double.compare(b.score, a.score);
        return c;
    };

    /**
 * 同分定序：分数相同再按 chunkId 升序。
 *
 * <p>RRF 分数大量并列（不同 store 的同一名次同权），若只按分排序，并列项的顺序就由
 * 插入顺序决定 ⇒ 交换 store / KB 顺序结果会漂移（验收：交换顺序结果稳定）。</p>
 */
    static final Comparator<EngineTypes.IndexWithScore> SCORE_THEN_ID =
            SCORE_DESC.thenComparing(h -> h.chunkId);


    /** 单一来源（另一侧为空）：按分降序；同分保持来源顺序——同一来源内即来源名次。 */
    static List<EngineTypes.IndexWithScore> deduplicateByScore(
            List<List<EngineTypes.IndexWithScore>> stores) {
        Map<String, EngineTypes.IndexWithScore> chunkInfoMap = new LinkedHashMap<>();
        for (List<EngineTypes.IndexWithScore> store : stores) {
            for (EngineTypes.IndexWithScore r : store) {
                EngineTypes.IndexWithScore existing = chunkInfoMap.get(r.chunkId);
                if (existing == null || r.score > existing.score) {
                    chunkInfoMap.put(r.chunkId, r);
                }
            }
        }
        List<EngineTypes.IndexWithScore> deduped = new ArrayList<>(chunkInfoMap.values());
        deduped.sort(SCORE_DESC);
        return deduped;
    }

    /**
 * RRF 融合：vW/(k+vRank) + kW/(k+kRank)，rank 1-indexed，**每个 store 列表各自算名次**。
 *
 * <p>：rank 是"店内名次"——各 store 各自保序。此前调用方把同类型的多个 store 命中先
 * {@code addAll} 成一个大列表，rank 退化成"拼接位置"，第二个 store 的本地第一名会被
 * 系统性降级，且结果随 store 顺序漂移。</p>
 */
    static List<EngineTypes.IndexWithScore> fuseWithRRF(
            List<List<EngineTypes.IndexWithScore>> vectorStores,
            List<List<EngineTypes.IndexWithScore>> keywordStores,
            RetrievalConfigView rc) {
        int rrfK = rc.effectiveRrfK();
        double vectorWeight = rc.effectiveVectorWeight();
        double keywordWeight = rc.effectiveKeywordWeight();

        Map<String, Integer> vectorRanks = ranksByStore(vectorStores);
        Map<String, Integer> keywordRanks = ranksByStore(keywordStores);

        Map<String, EngineTypes.IndexWithScore> chunkInfoMap = new LinkedHashMap<>();
        for (List<EngineTypes.IndexWithScore> store : vectorStores) {
            for (EngineTypes.IndexWithScore r : store) {
                EngineTypes.IndexWithScore existing = chunkInfoMap.get(r.chunkId);
                if (existing == null || r.score > existing.score) {
                    chunkInfoMap.put(r.chunkId, r);
                }
            }
        }
        for (List<EngineTypes.IndexWithScore> store : keywordStores) {
            for (EngineTypes.IndexWithScore r : store) {
                chunkInfoMap.putIfAbsent(r.chunkId, r);
            }
        }

        List<EngineTypes.IndexWithScore> result = new ArrayList<>(chunkInfoMap.size());
        for (Map.Entry<String, EngineTypes.IndexWithScore> e : chunkInfoMap.entrySet()) {
            EngineTypes.IndexWithScore info = e.getValue();
            double rrfScore = 0.0;
            Integer vRank = vectorRanks.get(e.getKey());
            if (vRank != null) {
                rrfScore += vectorWeight / (double) (rrfK + vRank);
            }
            Integer kRank = keywordRanks.get(e.getKey());
            if (kRank != null) {
                rrfScore += keywordWeight / (double) (rrfK + kRank);
            }
            info.score = rrfScore;
            result.add(info);
        }
        result.sort(SCORE_THEN_ID);
        return result;
    }

    /** 每店名次（1-indexed）：同一 chunk 命中多个 store 时取**最好**名次。 */
    private static Map<String, Integer> ranksByStore(
            List<List<EngineTypes.IndexWithScore>> stores) {
        Map<String, Integer> ranks = new HashMap<>();
        for (List<EngineTypes.IndexWithScore> store : stores) {
            for (int i = 0; i < store.size(); i++) {
                ranks.merge(store.get(i).chunkId, i + 1, Math::min);
            }
        }
        return ranks;
    }

    // ── FAQ 后处理（按 storeGroups 扇出） ─────────────────────────────────

    List<EngineTypes.IndexWithScore> applyFaqPostProcessing(
            KnowledgeBaseSearchFacts primary, List<EngineTypes.IndexWithScore> chunks,
            List<EngineTypes.IndexWithScore> vectorResults, List<StoreGroup> groups,
            SearchParams params, int matchCount) {
        if (!"faq".equals(primary.type())) {
            return chunks;
        }
        if (needsIterativeRetrieval(params, matchCount, chunks, vectorResults)) {
            log.info("Not enough unique chunks, using iterative retrieval for FAQ");
            return iterativeRetrieveWithDeduplication(groups, params.getMatchCount(),
                    params.getQueryText());
        }
        return filterByNegativeQuestions(chunks, params.getQueryText());
    }


    static boolean needsIterativeRetrieval(SearchParams params, int matchCount,
            List<EngineTypes.IndexWithScore> chunks,
            List<EngineTypes.IndexWithScore> vectorResults) {
        // 触发条件：唯一 chunk 不足 且 首轮向量结果打满 over-retrieval 池。
        return chunks.size() < params.getMatchCount() && vectorResults.size() == matchCount;
    }

    /**
     * 迭代取回去重：只涨各组的 TopK，
     * 引擎与分组在上游算好复用；类型化失败（2201）上抛，瞬时故障 WARN 后带部分结果退出。
     */
    List<EngineTypes.IndexWithScore> iterativeRetrieveWithDeduplication(
            List<StoreGroup> groups, int matchCount, String queryText) {
        final int maxIterations = 5;
        int currentTopK = Math.min(matchCount * 3, HybridSearchService.MAX_RETRIEVAL_POOL_SIZE);
        Map<String, EngineTypes.IndexWithScore> uniqueChunks = new LinkedHashMap<>();
        Map<String, ChunkFacts> chunkDataCache = new HashMap<>();
        Set<String> filteredOutChunks = new HashSet<>();
        String queryTextLower = queryText == null ? "" : queryText.strip().toLowerCase();
        Long tenantId = TenantContext.currentTenantId();

        for (int i = 0; i < maxIterations; i++) {
            for (StoreGroup grp : groups) {
                grp.topK = currentTopK;
            }
            List<EngineTypes.IndexWithScore> iterationResults = new ArrayList<>();
            try {
                iterationResults = service.retrieveFromStores(groups).stream()
                        .flatMap(rr -> rr.results().stream())
                        .collect(java.util.stream.Collectors.toList());
            } catch (BizException e) {
                // 类型化失败（组超时/绑定失效）如实上抛——不静默截断。
                log.warn("Iterative retrieval surfaced typed failure at iteration {}", i + 1, e);
                throw e;
            } catch (RuntimeException e) {
                log.warn("Iterative retrieval failed at iteration {}", i + 1, e);
                break;
            }
            if (iterationResults.isEmpty()) {
                break;
            }

            List<String> newChunkIds = new ArrayList<>();
            for (EngineTypes.IndexWithScore result : iterationResults) {
                if (!chunkDataCache.containsKey(result.chunkId)
                        && !filteredOutChunks.contains(result.chunkId)) {
                    newChunkIds.add(result.chunkId);
                }
            }
            if (!newChunkIds.isEmpty() && tenantId != null) {
                try {
                    List<ChunkFacts> fresh = service.chunkGateway.findChunks(tenantId, newChunkIds);
                    for (ChunkFacts c : fresh) {
                        chunkDataCache.put(c.id(), c);
                    }
                } catch (Exception e) {
                    log.warn("Failed to fetch chunks at iteration {}", i + 1, e);
                }
            }
            for (EngineTypes.IndexWithScore result : iterationResults) {
                if (filteredOutChunks.contains(result.chunkId)) {
                    continue;
                }
                ChunkFacts chunkData = chunkDataCache.get(result.chunkId);
                if (chunkData != null && "faq".equals(chunkData.chunkType())
                        && matchesNegativeQuestions(queryTextLower, faqNegativeQuestions(chunkData))) {
                    filteredOutChunks.add(result.chunkId);
                    uniqueChunks.remove(result.chunkId);
                    continue;
                }
                EngineTypes.IndexWithScore existing = uniqueChunks.get(result.chunkId);
                if (existing == null || result.score > existing.score) {
                    uniqueChunks.put(result.chunkId, result);
                }
            }
            if (uniqueChunks.size() >= matchCount) {
                break;
            }
            currentTopK = Math.min(currentTopK * 2, HybridSearchService.MAX_RETRIEVAL_POOL_SIZE);
        }
        List<EngineTypes.IndexWithScore> out = new ArrayList<>(uniqueChunks.values());
        out.sort(SCORE_DESC);
        return out;
    }


    List<String> faqNegativeQuestions(ChunkFacts chunk) {
        try {
            List<String> negatives = new ArrayList<>();
            JsonNode node = JsonMappers.toJsonNode(chunk.metadata());
            if (node != null && node.has("negativeQuestions") && node.get("negativeQuestions").isArray()) {
                for (JsonNode n : node.get("negativeQuestions")) {
                    negatives.add(n.asText(""));
                }
            }
            return negatives;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 子串命中即负例。 */
    static boolean matchesNegativeQuestions(String queryTextLower, List<String> negativeQuestions) {
        if (negativeQuestions == null || negativeQuestions.isEmpty()) {
            return false;
        }
        for (String q : negativeQuestions) {
            if (q == null || q.isEmpty()) {
                continue;
            }
            if (queryTextLower.contains(q.toLowerCase())) {
                return true;
            }
        }
        return false;
    }


    List<EngineTypes.IndexWithScore> filterByNegativeQuestions(
            List<EngineTypes.IndexWithScore> chunks, String queryText) {
        if (chunks.isEmpty()) {
            return chunks;
        }
        String queryTextLower = queryText == null ? "" : queryText.strip().toLowerCase();
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null) {
            return chunks;
        }
        List<String> ids = chunks.stream().map(c -> c.chunkId).toList();
        Map<String, ChunkFacts> cache = new HashMap<>();
        try {
            for (ChunkFacts c : service.chunkGateway.findChunks(tenantId, ids)) {
                cache.put(c.id(), c);
            }
        } catch (Exception e) {
            log.warn("Failed to fetch chunks for negative question filtering", e);
            return chunks;
        }
        List<EngineTypes.IndexWithScore> result = new ArrayList<>();
        for (EngineTypes.IndexWithScore hit : chunks) {
            ChunkFacts chunkData = cache.get(hit.chunkId);
            if (chunkData != null && "faq".equals(chunkData.chunkType())
                    && matchesNegativeQuestions(queryTextLower, faqNegativeQuestions(chunkData))) {
                continue;
            }
            result.add(hit);
        }
        return result;
    }
}
