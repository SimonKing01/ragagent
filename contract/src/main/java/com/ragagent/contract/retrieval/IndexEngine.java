package com.ragagent.contract.retrieval;

import com.ragagent.contract.model.Embedder;
import java.util.List;
import java.util.Map;

/**
 * 索引写面端口：一个**已绑定到某个 store** 的检索引擎句柄（业务域 → 检索引擎）。
 *
 * <p> 契约化：业务域（知识索引/迁移/克隆/FAQ/worker 删除链）原先直注
 * {@code retrieval-engine} 的 {@code CompositeRetrieveEngine} 实现类，且组装件是内部形状
 * {@code EngineTypes.IndexInfo}；本端口只收"域侧真调用"的方法，载体换成领域级
 * {@link IndexDocument}。句柄由 {@link IndexEngineResolver} 按租户 + store 解析
 * （未绑定的 KB 由调用方自行跳过，保持各自的 pg 直连路径）。</p>
 *
 * <p>失败语义沿用实现：解析类失败抛 {@link RetrieveEngineException}；写路径异常按
 * {@code Exception} 原样冒泡。</p>
 */
public interface IndexEngine {

    /** 索引一条（自算向量）。 */
    void index(Embedder embedder, IndexDocument document) throws Exception;

    /** 批量索引（自算向量）。 */
    void batchIndex(Embedder embedder, List<IndexDocument> documents) throws Exception;

    /** 按 chunk id 批量删。 */
    void deleteByChunkIdList(List<String> chunkIds, int dimension, String knowledgeType) throws Exception;

    /** 按源 id 批量删（生成问题行用 {@code chunkId-qId} 源 id）。 */
    void deleteBySourceIdList(List<String> sourceIds, int dimension, String knowledgeType) throws Exception;

    /** 按知识 id 批量删。 */
    void deleteByKnowledgeIdList(List<String> knowledgeIds, int dimension, String knowledgeType) throws Exception;

    /** 克隆：源 KB 的索引复制到目标 KB（含 chunk id 重映射）。 */
    void copyIndices(String sourceKnowledgeBaseId, Map<String, String> sourceToTargetKbIdMap,
                     Map<String, String> sourceToTargetChunkIdMap, String targetKnowledgeBaseId,
                     int dimension, String knowledgeType) throws Exception;

    /** 移动：预检 + 扇出（不支持迁移的引擎直接报错）。 */
    void moveKnowledgeIndices(String sourceKnowledgeBaseId, String targetKnowledgeBaseId,
                              String knowledgeId, List<String> chunkIds, int dimension,
                              String knowledgeType) throws Exception;

    /** 批量改启用状态。 */
    void batchUpdateChunkEnabledStatus(Map<String, Boolean> chunkStatusMap) throws Exception;

    /** 批量改标签。 */
    void batchUpdateChunkTagId(Map<String, String> chunkTagMap) throws Exception;

    /** 估算这些条目的存储体量。 */
    long estimateStorageSize(Embedder embedder, List<IndexDocument> documents);
}
