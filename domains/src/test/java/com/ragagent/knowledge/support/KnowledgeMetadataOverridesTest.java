package com.ragagent.knowledge.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.platform.web.JsonMappers;
import org.junit.jupiter.api.Test;

/** 逐文档处理覆盖写入 metadata 的口径。 */
class KnowledgeMetadataOverridesTest {

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    @Test
    void writesOverridesAndKeepsExistingKeys() throws Exception {
        JsonNode metadata = MAPPER.readTree("{\"content\":\"手工录入\",\"format\":\"markdown\"}");
        JsonNode overrides = MAPPER.readTree("{\"imageProcessingConfig\":{\"ocrEnabled\":false}}");

        JsonNode merged = KnowledgeMetadataOverrides.merge(metadata, overrides);

        assertThat(merged.path("content").asText()).isEqualTo("手工录入");
        assertThat(merged.path("format").asText()).isEqualTo("markdown");
        assertThat(merged.path("processOverrides").path("imageProcessingConfig").path("ocrEnabled").asBoolean())
                .isFalse();
        // 不改动入参
        assertThat(metadata.has("processOverrides")).isFalse();
    }

    @Test
    void createsMetadataWhenAbsent() throws Exception {
        JsonNode overrides = MAPPER.readTree("{\"imageProcessingConfig\":{\"ocrEnabled\":true}}");

        JsonNode merged = KnowledgeMetadataOverrides.merge(null, overrides);

        assertThat(merged.path("processOverrides").path("imageProcessingConfig").path("ocrEnabled").asBoolean())
                .isTrue();
    }

    @Test
    void replacesPreviousOverrides() throws Exception {
        JsonNode metadata = MAPPER.readTree("{\"processOverrides\":{\"imageProcessingConfig\":{\"ocrEnabled\":true}}}");
        JsonNode next = MAPPER.readTree("{\"imageProcessingConfig\":{\"ocrEnabled\":false}}");

        JsonNode merged = KnowledgeMetadataOverrides.merge(metadata, next);

        assertThat(merged.path("processOverrides").path("imageProcessingConfig").path("ocrEnabled").asBoolean())
                .isFalse();
    }

    @Test
    void ignoresEmptyOrNonObjectOverrides() throws Exception {
        JsonNode metadata = MAPPER.readTree("{\"content\":\"x\"}");

        assertThat(KnowledgeMetadataOverrides.merge(metadata, null)).isSameAs(metadata);
        assertThat(KnowledgeMetadataOverrides.merge(metadata, MAPPER.readTree("{}"))).isSameAs(metadata);
        assertThat(KnowledgeMetadataOverrides.merge(metadata, MAPPER.readTree("\"text\""))).isSameAs(metadata);
    }

    @Test
    void readsQuestionGenerationOverride() throws Exception {
        JsonNode metadata = MAPPER.readTree(
                "{\"processOverrides\":{\"questionGenerationConfig\":{\"enabled\":false,\"questionCount\":5}}}");

        assertThat(KnowledgeMetadataOverrides.questionGenerationEnabled(metadata)).isFalse();
        assertThat(KnowledgeMetadataOverrides.questionCount(metadata)).isEqualTo(5);
    }

    @Test
    void questionGenerationOverrideAbsentMeansFallback() throws Exception {
        assertThat(KnowledgeMetadataOverrides.questionGenerationEnabled(null)).isNull();
        assertThat(KnowledgeMetadataOverrides.questionGenerationEnabled(MAPPER.readTree("{}"))).isNull();
        assertThat(KnowledgeMetadataOverrides.questionCount(MAPPER.readTree("{\"processOverrides\":{}}")))
                .isNull();
    }
}
