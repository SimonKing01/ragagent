package com.ragagent.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.contract.knowledge.ChunkFacts;
import com.ragagent.contract.knowledge.ChunkSearchGateway;
import com.ragagent.contract.knowledge.KnowledgeBaseSearchGateway;
import com.ragagent.contract.knowledge.KnowledgeDocumentFacts;
import com.ragagent.contract.knowledge.KnowledgeDocumentGateway;
import com.ragagent.contract.model.EmbeddingPort;
import com.ragagent.contract.model.ModelFactsPort;
import com.ragagent.contract.retrieval.SearchResult;
import com.ragagent.contract.retrieval.TenantStoreOwnership;
import com.ragagent.contract.tenant.TenantConfigLookup;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.platform.config.RetrievalDriverProperties;
import com.ragagent.platform.web.JsonMappers;
import com.ragagent.retrieval.engine.EngineTypes;
import com.ragagent.retrieval.engine.RetrieveEngineRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * K9：**上下文扩展块必须输出**（或压根别读）。
 *
 * <p>缺陷现场：`HybridResultOps` 收集了父块 / 相邻块 / 关系块并逐条 `fetchChunk` 取回，
 * 但装配循环只遍历原命中 ⇒ 这些读取全是白读，请求里 `skipContextEnrichment=false`
 * （= 明确要求扩展）也拿不到任何上下文块。</p>
 */
class HybridResultOpsContextExpansionTest {

    private static final long TENANT = 10002L;

    private final Map<String, ChunkFacts> chunkRows = new LinkedHashMap<>();
    private final Map<String, KnowledgeDocumentFacts> docs = new LinkedHashMap<>();
    private HybridResultOps ops;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT, TenantContext.webUserPrincipal("u-1"), "owner", false, "u-1", false);

        KnowledgeBaseSearchGateway kbGateway = mock(KnowledgeBaseSearchGateway.class);
        KnowledgeDocumentGateway documentGateway = mock(KnowledgeDocumentGateway.class);
        ChunkSearchGateway chunkGateway = mock(ChunkSearchGateway.class);
        when(documentGateway.findAccessibleDocuments(anyLong(), anyList()))
                .thenAnswer(inv -> requested(inv.getArgument(1)).stream()
                        .map(docs::get).filter(Objects::nonNull).toList());
        when(chunkGateway.findChunks(anyLong(), anyList()))
                .thenAnswer(inv -> requested(inv.getArgument(1)).stream()
                        .map(chunkRows::get).filter(Objects::nonNull).toList());

        HybridSearchService service = new HybridSearchService(
                kbGateway, documentGateway, chunkGateway,
                mock(ModelFactsPort.class), mock(TenantConfigLookup.class), mock(EmbeddingPort.class),
                mock(RetrieveEngineRegistry.class),
                mock(TenantStoreOwnership.class), new RetrievalDriverProperties(null));
        ops = new HybridResultOps(service);
    }

    @Test
    @DisplayName("父块随命中输出，matchType=父块、分数继承来源命中")
    void parentChunkIsEmitted() {
        doc("doc-1");
        chunkRows.put("c-1", chunk("c-1", "doc-1", "text", "p-1", "", "", null, "completed", true));
        chunkRows.put("p-1", chunk("p-1", "doc-1", "text", "", "", "", null, "completed", true));

        List<SearchResult> out = ops.processSearchResults(List.of(hit("c-1", "doc-1", 0.5)), false);

        assertThat(ids(out)).as("扩展块此前被读回却从不输出").containsExactly("c-1", "p-1");
        assertThat(resultOf(out, "p-1").getMatchType())
                .isEqualTo(HybridSearchService.MATCH_PARENT_CHUNK);
        assertThat(resultOf(out, "p-1").getScore()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("相邻块（pre/next）随命中输出，matchType=相邻块")
    void neighborChunksAreEmitted() {
        doc("doc-1");
        chunkRows.put("c-1",
                chunk("c-1", "doc-1", "text", "", "pre-1", "n-1", null, "completed", true));
        chunkRows.put("n-1", chunk("n-1", "doc-1", "text", "", "", "", null, "completed", true));
        chunkRows.put("pre-1", chunk("pre-1", "doc-1", "text", "", "", "", null, "completed", true));

        List<SearchResult> out = ops.processSearchResults(List.of(hit("c-1", "doc-1", 0.4)), false);

        assertThat(ids(out)).containsExactly("c-1", "n-1", "pre-1");
        assertThat(resultOf(out, "n-1").getMatchType())
                .isEqualTo(HybridSearchService.MATCH_NEAR_BY_CHUNK);
        assertThat(resultOf(out, "pre-1").getMatchType())
                .isEqualTo(HybridSearchService.MATCH_NEAR_BY_CHUNK);
    }

    @Test
    @DisplayName("关系块跨文档也要输出（其文档同样要过可访问性）")
    void relatedChunkFromAnotherDocumentIsEmitted() {
        doc("doc-1");
        doc("doc-2");
        chunkRows.put("c-1", chunk("c-1", "doc-1", "text", "", "", "", relations("r-1"),
                "completed", true));
        chunkRows.put("r-1", chunk("r-1", "doc-2", "text", "", "", "", null, "completed", true));

        List<SearchResult> out = ops.processSearchResults(List.of(hit("c-1", "doc-1", 0.3)), false);

        assertThat(ids(out)).containsExactly("c-1", "r-1");
        assertThat(resultOf(out, "r-1").getMatchType())
                .isEqualTo(HybridSearchService.MATCH_RELATION_CHUNK);
    }

    @Test
    @DisplayName("skipContextEnrichment=true 时只有原命中（既有开关语义）")
    void skipEnrichmentKeepsOnlyPrimaryHits() {
        doc("doc-1");
        chunkRows.put("c-1", chunk("c-1", "doc-1", "text", "p-1", "", "", null, "completed", true));
        chunkRows.put("p-1", chunk("p-1", "doc-1", "text", "", "", "", null, "completed", true));

        List<SearchResult> out = ops.processSearchResults(List.of(hit("c-1", "doc-1", 0.5)), true);

        assertThat(ids(out)).containsExactly("c-1");
    }

    @Test
    @DisplayName("不可检索的扩展块（processing / 停用）不得混进结果")
    void nonSearchableExtraIsFiltered() {
        doc("doc-1");
        chunkRows.put("c-1", chunk("c-1", "doc-1", "text", "p-1", "", "", null, "completed", true));
        chunkRows.put("p-1", chunk("p-1", "doc-1", "text", "", "", "", null, "processing", true));

        List<SearchResult> out = ops.processSearchResults(List.of(hit("c-1", "doc-1", 0.5)), false);

        assertThat(ids(out)).containsExactly("c-1");
    }

    // ── 夹具 ──────────────────────────────────────────────────────────────

    private static EngineTypes.IndexWithScore hit(String chunkId, String knowledgeId,
            double score) {
        EngineTypes.IndexWithScore h = new EngineTypes.IndexWithScore();
        h.chunkId = chunkId;
        h.id = chunkId;
        h.knowledgeId = knowledgeId;
        h.score = score;
        return h;
    }

    private static ChunkFacts chunk(String id, String knowledgeId, String type, String parent,
            String pre, String next, JsonNode relations, String indexStatus, boolean enabled) {
        return new ChunkFacts(id, knowledgeId, "kb-1", "content-" + id, type, indexStatus, enabled,
                0, 0, 1, 1, parent, pre, next, relations, null, null);
    }

    private static JsonNode relations(String... ids) {
        return JsonMappers.lenient().valueToTree(List.of(ids));
    }

    private void doc(String id) {
        docs.put(id, new KnowledgeDocumentFacts(id, "title-" + id, null, id + ".txt", "manual",
                "web", null, "kb-1", TENANT, "txt", "/tmp/" + id));
    }

    private static List<String> requested(Object arg) {
        List<String> out = new ArrayList<>();
        for (Object o : (List<?>) arg) {
            out.add(String.valueOf(o));
        }
        return out;
    }

    private static List<String> ids(List<SearchResult> results) {
        List<String> out = new ArrayList<>();
        for (SearchResult r : results) {
            out.add(r.getId());
        }
        return out;
    }

    private static SearchResult resultOf(List<SearchResult> results, String id) {
        return results.stream().filter(r -> id.equals(r.getId())).findFirst()
                .orElseThrow(() -> new AssertionError("结果里缺少: " + id));
    }
}
