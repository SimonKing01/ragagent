package com.ragagent.knowledge.index;

import com.ragagent.contract.graph.NameSpace;
import com.ragagent.contract.model.Embedder;
import com.ragagent.contract.model.ModelRuntime;
import com.ragagent.contract.retrieval.IndexEngine;
import com.ragagent.contract.retrieval.RetrieveGraphRepository;
import com.ragagent.contract.retrieval.VectorIndexStore;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 知识删除时的索引回收：向量（绑定 store 走引擎口，未绑定走 pg 直连）+ 图谱命名空间。
 *
 * <p>与 {@code KnowledgeIndexStage} 的预清理同源，差别在失败语义：删除路径的库内变更
 * 已提交，外部引擎故障不能把它拖回去，因此只告警并回报失败项数，由回收用例决定是否
 * 需要重试（可靠重试由删除清理任务承接）。</p>
 */
@Component
public class KnowledgeIndexCleaner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexCleaner.class);

    private final VectorIndexStore vectorStore;
    private final KnowledgeVectorWrites vectorWrites;
    private final ModelRuntime modelRuntime;
    private final RetrieveGraphRepository graphRepository;

    public KnowledgeIndexCleaner(VectorIndexStore vectorStore,
                                 KnowledgeVectorWrites vectorWrites,
                                 ModelRuntime modelRuntime,
                                 RetrieveGraphRepository graphRepository) {
        this.vectorStore = vectorStore;
        this.vectorWrites = vectorWrites;
        this.modelRuntime = modelRuntime;
        this.graphRepository = graphRepository;
    }

    /**
     * 清理一条知识的全部索引，返回失败项数（0 = 全部成功）。
     *
     * @param kb 所属 KB；可为 {@code null}（KB 已删时退化为 pg 直连或跳过）
     */
    public int cleanup(Knowledge k, KnowledgeBase kb) {
        if (k == null) {
            return 0;
        }
        int failed = deleteVectors(k, kb) ? 0 : 1;
        return deleteGraph(k) ? failed : failed + 1;
    }

    /** 向量：绑定店优先（引擎口），否则 pg 直连；索引未启用 → 无动作。 */
    private boolean deleteVectors(Knowledge k, KnowledgeBase kb) {
        IndexEngine bound;
        try {
            bound = vectorWrites.boundEngine(kb, k.getTenantId());
        } catch (RuntimeException e) {
            log.warn("Vector cleanup incomplete: bound index engine unresolved, kb={} knowledge={}",
                    kb == null ? "" : kb.getId(), k.getId(), e);
            return false;
        }
        if (bound != null) {
            try {
                Embedder embedder = modelRuntime.getEmbeddingModel(kb.getEmbeddingModelId());
                bound.deleteByKnowledgeIdList(List.of(k.getId()), embedder.getDimensions(), kb.getType());
                return true;
            } catch (Exception e) {
                log.warn("Failed to delete vectors via bound engine, kb={} knowledge={}",
                        kb.getId(), k.getId(), e);
                return false;
            }
        }
        String embeddingModelId = resolveEmbeddingModelId(k, kb);
        if (embeddingModelId == null) {
            return true;
        }
        try {
            vectorStore.deleteByKnowledgeId(List.of(k.getId()));
            return true;
        } catch (RuntimeException e) {
            log.warn("Failed to delete pg vectors, knowledge={}", k.getId(), e);
            return false;
        }
    }

    /**
     * 与 {@code KnowledgeIndexStage#resolveEmbeddingModelId} 同口径，但**不抛**：
     * 未启用向量/关键词、或模型未配置 → null（无索引可清）。
     */
    private static String resolveEmbeddingModelId(Knowledge k, KnowledgeBase kb) {
        if (kb != null) {
            var strategy = kb.getIndexingStrategy();
            if (strategy != null && !strategy.isVectorEnabled() && !strategy.isKeywordEnabled()) {
                return null;
            }
        }
        String modelId = k.getEmbeddingModelId();
        if (modelId == null || modelId.isEmpty()) {
            modelId = kb == null ? null : kb.getEmbeddingModelId();
        }
        return modelId == null || modelId.isEmpty() ? null : modelId;
    }

    private boolean deleteGraph(Knowledge k) {
        try {
            graphRepository.delGraph(List.of(new NameSpace(k.getKnowledgeBaseId(), k.getId())));
            return true;
        } catch (RuntimeException e) {
            log.warn("Failed to delete graph namespace, kb={} knowledge={}",
                    k.getKnowledgeBaseId(), k.getId(), e);
            return false;
        }
    }
}
