package com.ragagent.agenttools.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.ragagent.agenttools.knowledge.KnowledgeSearchTool.ResultWithMeta;
import com.ragagent.agenttools.knowledge.KnowledgeSearchTool.SearchResultView;
import com.ragagent.contract.tool.ToolResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 输出格式的特征钉子：拆成具名步骤后，XML 与 data 形状保持不变。 */
class KnowledgeSearchOutputFormatterTest {

    private final KnowledgeSearchTool tool =
            new KnowledgeSearchTool(null, null, null, null, null, null);
    private final KnowledgeSearchOutputFormatter formatter = new KnowledgeSearchOutputFormatter(tool);

    private static ResultWithMeta chunkResult() {
        SearchResultView view = new SearchResultView();
        view.id = "c1";
        view.content = "hello";
        view.knowledgeId = "k1";
        view.knowledgeBaseId = "kb1";
        view.knowledgeTitle = "Doc";
        view.chunkIndex = 3;
        view.score = 0.75;
        view.matchType = 1;
        return new ResultWithMeta(view, "q", "vector", "document");
    }

    @Test
    void rendersMediaBlockFromKnowledgeMetadata() {
        ResultWithMeta base = chunkResult();
        base.sr.metadata = Map.of("mediaRefs",
                "{\"fileType\":\"mp4\",\"posterRef\":\"local://1/exports/c.jpg\","
                        + "\"mediaRef\":\"local://1/exports/v.mp4\"}");

        ToolResult result = formatter.formatOutput(List.of(base), List.of("kb1"), List.of("q"));

        assertThat(result.getOutput())
                .contains("<documents>")
                .contains("<media type=\"video\">")
                .contains("<poster>local://1/exports/c.jpg</poster>")
                .contains("<play>local://1/exports/v.mp4</play>");
    }

    @Test
    void omitsDocumentsBlockWhenNothingToShow() {
        ToolResult result = formatter.formatOutput(List.of(chunkResult()), List.of("kb1"), List.of("q"));

        assertThat(result.getOutput()).doesNotContain("<documents>");
    }

    @Test
    @SuppressWarnings("unchecked")
    void rendersChunkXmlAndData() {
        ToolResult result = formatter.formatOutput(List.of(chunkResult()), List.of("kb1"), List.of("q"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput())
                .startsWith("<searchResults count=\"1\">")
                .contains("<chunk rank=\"1\" chunkId=\"c1\" chunkIndex=\"3\"")
                .contains("<content>hello</content>")
                .contains("</chunk>")
                .contains("<retrievalStatistics>")
                .contains("</searchResults>");
        Map<String, Object> data = result.getData();
        assertThat(data).containsEntry("displayType", "search_results").containsEntry("count", 1);
        List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("results");
        assertThat(results).hasSize(1);
        assertThat(results.get(0))
                .containsEntry("resultIndex", 1)
                .containsEntry("chunkId", "c1")
                .containsEntry("chunkIndex", 3)
                .containsEntry("sourceQuery", "q");
    }

    @Test
    void secondCallMarksChunkAlreadySeen() {
        formatter.formatOutput(List.of(chunkResult()), List.of("kb1"), List.of("q"));
        ToolResult again = formatter.formatOutput(List.of(chunkResult()), List.of("kb1"), List.of("q"));

        assertThat(again.getOutput())
                .contains("already_seen=\"true\"")
                .contains("already returned in a previous knowledge_search call");
    }

    @Test
    void emptyResultsKeepFixedGuidance() {
        ToolResult result = formatter.formatOutput(List.of(), List.of("kb1", "kb2"), List.of("q"));

        assertThat(result.getOutput())
                .contains("No relevant content found in 2 knowledge base(s).")
                .contains("NEVER fabricate or infer answers");
        assertThat(result.getData()).containsEntry("count", 0);
    }
}
