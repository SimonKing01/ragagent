package com.ragagent.agenttools.datasource;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.contract.pipeline.PipelinePorts;
import com.ragagent.contract.tool.ToolResult;
import com.ragagent.platform.web.JsonMappers;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link PipelinePorts.DataAnalysisSessionFactory} 的 agent 侧实现（由 {@code chatpipeline} 搬入）。
 *
 * <p>原先这个适配器住在 L2 管线里 ⇒ 让 L2 直接依赖 {@code agent.tools.data.DataAnalysisTool}
 * 及其嵌套 record。现在：管线只认自己端口里的 {@code KnowledgeData}/{@code TableSchema}，
 * 转换（工具 record → 端口 record）发生在本适配器（L3 侧）。</p>
 *
 * <p>放本包内是为了触达包私有的 {@code DataAnalysisSessionBridge}（装载/执行/清理三步）。</p>
 */
public final class DataAnalysisSessionFactoryAdapter implements PipelinePorts.DataAnalysisSessionFactory {

    /** 每会话一个工具实例。 */
    @Override
    public PipelinePorts.DataAnalysisSession create(String sessionId) {
        DataAnalysisTool tool = new DataAnalysisTool(null, null, null, sessionId);
        return new SessionImpl(tool);
    }

    private static final class SessionImpl implements PipelinePorts.DataAnalysisSession {

        private final DataAnalysisTool tool;

        SessionImpl(DataAnalysisTool tool) {
            this.tool = tool;
        }

        @Override
        public PipelinePorts.TableSchema loadFromKnowledge(PipelinePorts.KnowledgeData knowledge) {
            DataAnalysisTool.TableSchema schema = DataAnalysisSessionBridge.loadFromKnowledge(tool,
                    new DataAnalysisTool.KnowledgeData(knowledge.id(), knowledge.knowledgeBaseId(),
                            knowledge.tenantId(), knowledge.fileType(), knowledge.filePath()));
            if (schema == null) {
                return null;
            }
            List<PipelinePorts.ColumnInfo> cols = new ArrayList<>(schema.columns().size());
            for (DataAnalysisTool.ColumnInfo c : schema.columns()) {
                cols.add(new PipelinePorts.ColumnInfo(c.name(), c.type(), c.nullable()));
            }
            return new PipelinePorts.TableSchema(schema.tableName(), cols, schema.rowCount());
        }

        @Override
        public ToolResult execute(Object argsArg) {
            // 契约面用不透明 JSON：还原后再走桥（调用方恒传 JsonNode）
            JsonNode args = argsArg instanceof JsonNode node ? node : JsonMappers.toJsonNode(argsArg);
            return DataAnalysisSessionBridge.execute(tool, args);
        }

        @Override
        public void cleanup() {
            DataAnalysisSessionBridge.cleanup(tool);
        }
    }
}
