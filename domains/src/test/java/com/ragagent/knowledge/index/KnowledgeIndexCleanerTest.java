package com.ragagent.knowledge.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ragagent.contract.model.Embedder;
import com.ragagent.contract.model.ModelRuntime;
import com.ragagent.contract.retrieval.IndexEngine;
import com.ragagent.contract.retrieval.RetrieveGraphRepository;
import com.ragagent.contract.retrieval.VectorIndexStore;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 索引回收的验收：绑店走引擎口（维度 + 类型）、未绑走 pg 直连、索引未启用不动、
 * 外部失败只回报失败项数（不抛）。
 */
class KnowledgeIndexCleanerTest {

    private static final long TENANT = 10002L;
    private static final String KB = "kb-1";

    private VectorIndexStore vectorStore;
    private KnowledgeVectorWrites vectorWrites;
    private ModelRuntime modelRuntime;
    private RetrieveGraphRepository graphRepository;
    private KnowledgeIndexCleaner cleaner;

    @BeforeEach
    void setUp() {
        vectorStore = mock(VectorIndexStore.class);
        vectorWrites = mock(KnowledgeVectorWrites.class);
        modelRuntime = mock(ModelRuntime.class);
        graphRepository = mock(RetrieveGraphRepository.class);
        cleaner = new KnowledgeIndexCleaner(vectorStore, vectorWrites, modelRuntime, graphRepository);
    }

    @Test
    void cleanup_boundEngine_deletesWithModelDimensions() throws Exception {
        IndexEngine engine = mock(IndexEngine.class);
        Embedder embedder = mock(Embedder.class);
        when(embedder.getDimensions()).thenReturn(768);
        when(vectorWrites.boundEngine(any(KnowledgeBase.class), any())).thenReturn(engine);
        when(modelRuntime.getEmbeddingModel("emb-1")).thenReturn(embedder);

        int failed = cleaner.cleanup(knowledge("k-1"), kb("emb-1"));

        assertThat(failed).isZero();
        verify(engine).deleteByKnowledgeIdList(List.of("k-1"), 768, "document");
        verifyNoInteractions(vectorStore);
    }

    @Test
    void cleanup_engineFailure_reportsOneWithoutThrowing() throws Exception {
        IndexEngine engine = mock(IndexEngine.class);
        Embedder embedder = mock(Embedder.class);
        when(embedder.getDimensions()).thenReturn(768);
        when(vectorWrites.boundEngine(any(KnowledgeBase.class), any())).thenReturn(engine);
        when(modelRuntime.getEmbeddingModel("emb-1")).thenReturn(embedder);
        doThrow(new Exception("engine down"))
                .when(engine).deleteByKnowledgeIdList(anyList(), anyInt(), anyString());

        int failed = cleaner.cleanup(knowledge("k-1"), kb("emb-1"));

        assertThat(failed).isEqualTo(1);
    }

    @Test
    void cleanup_unboundKnowledgeBase_deletesVectorsThroughPg() {
        when(vectorWrites.boundEngine(any(KnowledgeBase.class), any())).thenReturn(null);

        int failed = cleaner.cleanup(knowledge("k-1"), kb("emb-1"));

        assertThat(failed).isZero();
        verify(vectorStore).deleteByKnowledgeId(List.of("k-1"));
    }

    @Test
    void cleanup_noEmbeddingModel_skipsVectorStore() {
        when(vectorWrites.boundEngine(any(KnowledgeBase.class), any())).thenReturn(null);
        Knowledge k = knowledge("k-1");
        k.setEmbeddingModelId("");

        int failed = cleaner.cleanup(k, kb(""));

        assertThat(failed).isZero();
        verifyNoInteractions(vectorStore);
    }

    @Test
    void cleanup_graphFailure_reportsOneWithoutThrowing() {
        when(vectorWrites.boundEngine(any(KnowledgeBase.class), any())).thenReturn(null);
        Knowledge k = knowledge("k-1");
        k.setEmbeddingModelId("");
        doThrow(new IllegalStateException("neo4j down"))
                .when(graphRepository).delGraph(anyList());

        int failed = cleaner.cleanup(k, kb(""));

        assertThat(failed).isEqualTo(1);
    }

    private static Knowledge knowledge(String id) {
        Knowledge k = new Knowledge();
        k.setId(id);
        k.setTenantId(TENANT);
        k.setKnowledgeBaseId(KB);
        k.setEmbeddingModelId("emb-1");
        return k;
    }

    private static KnowledgeBase kb(String embeddingModelId) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(KB);
        kb.setTenantId(TENANT);
        kb.setType("document");
        kb.setEmbeddingModelId(embeddingModelId);
        return kb;
    }
}
