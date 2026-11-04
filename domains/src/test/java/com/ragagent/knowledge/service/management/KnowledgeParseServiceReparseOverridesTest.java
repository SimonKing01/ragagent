package com.ragagent.knowledge.service.management;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.contract.knowledge.KnowledgeIngest;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.domain.span.SpanTracker;
import com.ragagent.knowledge.repository.DocumentParser;
import com.ragagent.knowledge.service.KnowledgeAccessHelper;
import com.ragagent.platform.web.JsonMappers;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 重解析时弹窗里的覆盖必须替换上一次导入落下的 metadata.processOverrides。 */
class KnowledgeParseServiceReparseOverridesTest {

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    private final KnowledgeAccessHelper access = mock(KnowledgeAccessHelper.class);
    private final KnowledgeFolderService folderService = mock(KnowledgeFolderService.class);
    private final KnowledgeFileService fileService = mock(KnowledgeFileService.class);
    private final DocumentParser documentParser = mock(DocumentParser.class);
    private final SpanTracker spanTracker = mock(SpanTracker.class);
    private final KnowledgeIngest knowledgeIngest = mock(KnowledgeIngest.class);

    private final KnowledgeParseService service = new KnowledgeParseService(
            access, folderService, fileService, documentParser, spanTracker, knowledgeIngest);

    private Knowledge existingWithOverrides() throws Exception {
        Knowledge k = new Knowledge();
        k.setId("k1");
        k.setKnowledgeBaseId("kb1");
        k.setParseAttempt(1L);
        k.setMetadata(MAPPER.readTree(
                "{\"processOverrides\":{\"questionGenerationConfig\":{\"enabled\":false}}}"));
        return k;
    }

    @Test
    void reparseReplacesPreviousOverrides() throws Exception {
        Knowledge existing = existingWithOverrides();
        when(folderService.loadKnowledgeWrite("k1")).thenReturn(existing);
        when(access.requireKb("kb1")).thenReturn(new KnowledgeBase());

        JsonNode processConfig = MAPPER.readTree("{\"questionGenerationConfig\":{\"enabled\":true}}");
        service.reparseKnowledge("k1", processConfig);

        ArgumentCaptor<JsonNode> metadata = ArgumentCaptor.forClass(JsonNode.class);
        verify(fileService).updateKnowledgeRow(eq(existing), metadata.capture());
        assertThat(metadata.getValue()
                .path("processOverrides").path("questionGenerationConfig").path("enabled").asBoolean())
                .isTrue();
        verify(knowledgeIngest).enqueueProcessing("k1");
    }

    @Test
    void reparseWithoutConfigKeepsExistingOverrides() throws Exception {
        Knowledge existing = existingWithOverrides();
        when(folderService.loadKnowledgeWrite("k1")).thenReturn(existing);
        when(access.requireKb("kb1")).thenReturn(new KnowledgeBase());

        service.reparseKnowledge("k1", null);

        ArgumentCaptor<JsonNode> metadata = ArgumentCaptor.forClass(JsonNode.class);
        verify(fileService).updateKnowledgeRow(eq(existing), metadata.capture());
        assertThat(metadata.getValue()
                .path("processOverrides").path("questionGenerationConfig").path("enabled").asBoolean())
                .isFalse();
    }

    @Test
    void reparseResetsParseState() throws Exception {
        Knowledge existing = existingWithOverrides();
        existing.setParseStatus(Knowledge.PARSE_COMPLETED);
        existing.setEnableStatus("enabled");
        when(folderService.loadKnowledgeWrite("k1")).thenReturn(existing);
        when(access.requireKb("kb1")).thenReturn(new KnowledgeBase());

        service.reparseKnowledge("k1", null);

        assertThat(existing.getParseStatus()).isEqualTo(Knowledge.PARSE_PENDING);
        assertThat(existing.getParseAttempt()).isEqualTo(2L);
        assertThat(existing.getEnableStatus()).isEqualTo("disabled");
        verify(fileService).updateKnowledgeRow(eq(existing), any());
    }
}
