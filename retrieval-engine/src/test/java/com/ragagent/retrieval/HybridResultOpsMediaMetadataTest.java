package com.ragagent.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.ragagent.contract.knowledge.ChunkFacts;
import com.ragagent.contract.knowledge.KnowledgeDocumentFacts;
import com.ragagent.contract.retrieval.SearchResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 检索结果装配：{@code knowledge.metadata.mediaRefs} 是嵌套对象，必须序列化成字符串带给模型。
 *
 * <p>改前「只要有一个非字符串键就整包丢弃」，媒体引用永远到不了回答上下文。</p>
 */
class HybridResultOpsMediaMetadataTest {

    private static ChunkFacts chunk() {
        return new ChunkFacts("c1", "k1", "kb1", "正文", "text", "", true, 0, 0, 10, 1,
                "", "", "", null, null, "");
    }

    private static KnowledgeDocumentFacts doc(Object metadata) {
        return new KnowledgeDocumentFacts("k1", "教学视频.mp4", metadata, "教学视频.mp4",
                "file", "web", "", "kb1", 1L, "mp4", "p");
    }

    @Test
    void nestedMediaRefsSurvivesAsJsonString() {
        Map<String, Object> metadata = Map.of("mediaRefs", Map.of(
                "fileType", "mp4",
                "posterRef", "local://1/exports/c.jpg",
                "mediaRef", "local://1/exports/v.mp4"));

        SearchResult r = HybridResultOps.buildSearchResult(chunk(), doc(metadata), 0.9, 0, "");

        assertThat(r.getMetadata()).containsKey("mediaRefs");
        assertThat(r.getMetadata().get("mediaRefs")).contains("local://1/exports/v.mp4");
    }

    @Test
    void nullMetadataStaysEmptyMap() {
        SearchResult r = HybridResultOps.buildSearchResult(chunk(), doc(null), 0.5, 1, "");

        assertThat(r.getMetadata()).isEmpty();
    }
}
