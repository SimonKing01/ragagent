package com.ragagent.contract.pipeline;

import com.ragagent.contract.knowledge.ChunkFacts;
import com.ragagent.contract.knowledge.KnowledgeBaseView;
import com.ragagent.contract.knowledge.KnowledgeDocumentFacts;
import com.ragagent.contract.memory.MemoryRecall;
import com.ragagent.contract.memory.MemoryRetrievalContext;
import com.ragagent.contract.model.LlmChatClient;
import com.ragagent.contract.model.Reranker;
import com.ragagent.contract.retrieval.SearchParams;
import com.ragagent.contract.retrieval.SearchResult;
import com.ragagent.contract.tenant.WebSearchConfig;
import com.ragagent.contract.tool.ToolResult;
import com.ragagent.contract.websearch.WebSearchResult;
import java.util.List;
import java.util.Map;

/**
 * chat 管线消费面的窄 seam 接口集合，只收管线实际调用的方法子集。
 *
 * <h2>装配要求</h2>
 * <p>每个接口需要一个 adapter（纯新增文件，不改既有 service）把现有 Java service
 * 适配进来：</p>
 * <ul>
 *   <li>{@link ModelService} → KnowledgeService/model 侧的模型工厂（getChatModel/getRerankModel）。</li>
 *   <li>{@link KnowledgeBaseService} → knowledge 域 HybridSearch 执行面（向量/关键词执行）；
 *       getQueryEmbedding / resolveEmbeddingModelKeys /
 *       getKnowledgeBase(s)ById(s)Only。</li>
 *   <li>{@link ChunkRepository} / {@link KnowledgeRepository} / {@link KnowledgeBaseRepository}
 *       → knowledge.mapper.ChunkRepository / KnowledgeService / KnowledgeBaseMapper。</li>
 *   <li>{@link MessageService} → session.service.MessageService（getImage/RenderedContent 更新
 *       方法需在该类补方法或 adapter 内直写 mapper）。</li>
 *   <li>{@link MemoryService} → memory.service.MemoryService（recall/retrievalContextFor/
 *       documentAffinity 签名已对齐）。</li>
 *   <li>websearch 面 → 域侧适配器转成执行面配置（见 {@code session/QaWiring}）。</li>
 *   <li>{@link com.ragagent.contract.retrieval.RetrieveGraphRepository} → 图检索仓储（neo4j/图库面）。</li>
 *   <li>{@link TenantService} / {@link SessionService} / {@link WebSearchStateService} /
 *       {@link WebSearchProviderRepository}：占位接口——管线只判空、从不调用其方法。</li>
 * </ul>
 *
 * <p>错误通道：失败一律抛 {@link PipelinePortException}，成功走返回值。</p>
 */
/**
 * 管线所需世界的**端口束**（契约化：原 {@code chatpipeline.PipelinePorts}）。
 *
 * <p>管线的消费方（session 的装配、以及 knowledge/memory/websearch/datasource 的 api 适配器）
 * 只认这一束端口，不认管线本体；载荷一律用契约形状，{@code DataAnalysisSession.execute}
 * 的 JSON 参数按契惯例为**不透明 JSON**（{@code Object}，实现侧按其 JSON 库解释）。</p>
 */
public final class PipelinePorts {

    private PipelinePorts() {}

    /** seam 调用失败通道。 */
    public static final class PipelinePortException extends RuntimeException {
        public PipelinePortException(String message) { super(message); }
        public PipelinePortException(String message, Throwable cause) { super(message, cause); }
    }

    /** 模型面：chat 管线所需的方法子集。 */
    public interface ModelService {
        LlmChatClient getChatModel(String modelId);
        Reranker getRerankModel(String modelId);
    }

    /** 知识库面：chat 管线所需的方法子集。 */
    public interface KnowledgeBaseService {
        /** 按 ID 直取（无租户过滤）。 */
        KnowledgeBaseView getKnowledgeBaseByIdUnscoped(String id);

        /** 批量直取（无租户过滤；缺失 ID 跳过）。 */
        List<KnowledgeBaseView> getKnowledgeBasesByIdsUnscoped(List<String> ids);

        List<SearchResult> hybridSearch(String knowledgeBaseId, SearchParams params);

        float[] getQueryEmbedding(String kbId, String queryText);

        /** KB ID → "模型名|endpoint"（解析失败的 KB 缺键）。 */
        Map<String, String> resolveEmbeddingModelKeys(List<String> kbIds);
    }

    /** 知识面：chat 管线所需的方法子集。 */
    public interface KnowledgeService {
        /** 按租户过滤取单条（adapter 从 TenantContext 取租户）。 */
        KnowledgeDocumentFacts getKnowledgeById(String id);

        List<KnowledgeDocumentFacts> getKnowledgeBatch(long tenantId, List<String> ids);

        List<KnowledgeDocumentFacts> getKnowledgeBatchWithSharedAccess(
                long tenantId, List<String> ids);
    }

    /** chunk 面：chat 管线所需的方法子集。 */
    public interface ChunkRepository {
        List<ChunkFacts> listChunksById(long tenantId, List<String> ids);

        /** image_info 聚合用。 */
        List<ChunkFacts> listChunksByParentIds(long tenantId, List<String> parentIds);
    }

    /** 知识批量取仓储。 */
    public interface KnowledgeRepository {
        List<KnowledgeDocumentFacts> getKnowledgeBatch(long tenantId, List<String> ids);
    }

    /** 知识库批量取仓储。 */
    public interface KnowledgeBaseRepository {
        List<KnowledgeBaseView> getKnowledgeBaseByIds(List<String> ids);
    }

    /** 记忆面端口（签名与 memory.service.MemoryService 对齐）。 */
    public interface MemoryService {
        MemoryRecall recall(String query);

        MemoryRetrievalContext retrievalContextFor();

        Map<String, Integer> documentAffinity(List<String> knowledgeIds);
    }

    /** web 搜索执行（providerId + 执行面配置）。 */
    public interface WebSearch {
        List<WebSearchResult> search(String providerId, WebSearchConfig config,
                                      String query);
    }
    // ── 占位接口（管线只判空、从不调用方法） ──

    /** 租户面端口——chat 管线取租户信息的最小子集。 */
    public interface TenantService {

        /**
         * 当前租户的 web 搜索配置（TenantContext 实时读取）；无租户上下文 → null
         * （调用方按空配置走缺省合并分支）。default null 保持未装配 port 时的恒空行为。
         */
        default WebSearchConfig currentWebSearchConfig() {
            return null;
        }
    }

    /**
     * DataAnalysis 插件的工具会话 seam（装载/执行/清理三步）。实现侧可放 agent.tools
     * 包内以触达包私有 loadFromKnowledge，打包 KnowledgeLoader/Materializer/AnalysisDuckDb
     * 三个 seam 与数据库连接句柄。
     */
    public interface DataAnalysisSessionFactory {
        DataAnalysisSession create(String sessionId);
    }

    /**
     * 一次数据装载/执行/清理会话。
     *
     * <p>签名原先直接用 {@code agent.tools.data.DataAnalysisTool} 的嵌套 record ⇒ L2 依赖 L3。
     * 现改收本端口自有的三个 record（形状与工具侧一致），由 agent 侧适配器做转换。</p>
     */
    public interface DataAnalysisSession {
        TableSchema loadFromKnowledge(KnowledgeData knowledge);

        ToolResult execute(Object args);

        void cleanup();
    }

    /** 待分析的知识文件（形状对齐 {@code DataAnalysisTool.KnowledgeData}）。 */
    public record KnowledgeData(String id, String knowledgeBaseId, long tenantId, String fileType,
                                String filePath) {
    }

    /** 表格列（形状对齐 {@code DataAnalysisTool.ColumnInfo}）。 */
    public record ColumnInfo(String name, String type, String nullable) {
    }

    /** 表 schema：表名 + 列 + 行数（形状对齐 {@code DataAnalysisTool.TableSchema}）。 */
    public record TableSchema(String tableName, List<ColumnInfo> columns, long rowCount) {
    }
}
