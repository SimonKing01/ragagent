package com.ragagent.session.rag.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import com.ragagent.contract.retrieval.SearchResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 文档头里的 {@code <media>} 段：只有媒体类型才输出，且按知识条目去重。 */
class PluginIntoChatMessageMediaTest {

    private static SearchResult result(String knowledgeId, String title, String mediaRefs) {
        SearchResult r = new SearchResult();
        r.setId("chunk-" + knowledgeId);
        r.setContent("正文");
        r.setKnowledgeId(knowledgeId);
        r.setKnowledgeTitle(title);
        if (mediaRefs != null) {
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("mediaRefs", mediaRefs);
            r.setMetadata(metadata);
        }
        return r;
    }

    @Test
    void videoDocumentCarriesMediaBlockWithPosterAndPlay() {
        String header = PluginIntoChatMessage.buildDocumentHeader(List.of(result("k1", "教学视频.mp4",
                "{\"fileType\":\"mp4\",\"posterRef\":\"local://1/exports/c.jpg\","
                        + "\"mediaRef\":\"local://1/exports/v.mp4\",\"durationSeconds\":36}")));

        assertThat(header).contains("<media type=\"video\">");
        assertThat(header).contains("<poster>local://1/exports/c.jpg</poster>");
        assertThat(header).contains("<play>local://1/exports/v.mp4</play>");
    }

    @Test
    void imageDocumentCarriesPosterOnly() {
        String header = PluginIntoChatMessage.buildDocumentHeader(List.of(result("k2", "卖点图.png",
                "{\"fileType\":\"png\",\"posterRef\":\"local://1/exports/a.png\"}")));

        assertThat(header).contains("<media type=\"image\">");
        assertThat(header).contains("<poster>local://1/exports/a.png</poster>");
        assertThat(header).doesNotContain("<play>");
    }

    @Test
    void plainDocumentHasNoMediaBlock() {
        String header = PluginIntoChatMessage.buildDocumentHeader(List.of(result("k3", "手册.pdf", null)));

        assertThat(header).contains("<title>手册.pdf</title>");
        assertThat(header).doesNotContain("<media");
    }

    @Test
    void mediaBlockAppearsOncePerDocument() {
        String mediaRefs = "{\"fileType\":\"mp4\",\"mediaRef\":\"local://1/exports/v.mp4\"}";
        String header = PluginIntoChatMessage.buildDocumentHeader(List.of(
                result("k4", "视频.mp4", mediaRefs),
                result("k4", "视频.mp4", mediaRefs)));

        assertThat(header.split("<media ", -1).length - 1).isEqualTo(1);
    }

    @Test
    void malformedMediaRefsIsIgnored() {
        String header = PluginIntoChatMessage.buildDocumentHeader(List.of(result("k5", "视频.mp4", "not json")));

        assertThat(header).doesNotContain("<media");
    }
}
