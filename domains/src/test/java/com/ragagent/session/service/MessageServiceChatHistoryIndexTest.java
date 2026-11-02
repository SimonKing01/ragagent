package com.ragagent.session.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.auth.service.TenantService;
import com.ragagent.contract.knowledge.KnowledgeCatalogPort;
import com.ragagent.contract.knowledge.KnowledgeRowView;
import com.ragagent.contract.model.ModelRuntime;
import com.ragagent.contract.retrieval.HybridSearchPort;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.session.conversation.MessageService;
import com.ragagent.session.repository.MessageRepository;
import com.ragagent.session.repository.MessageSuggestionRepository;
import com.ragagent.session.repository.SessionRepository;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 聊天历史 KB 索引的验收：
 * think 剥离、Q/A 全空短路、配置三要素不全跳过、passage 文案逐字、knowledge_id
 * 回写（无 session_id 条件）、失败只警告不抛。
 */
class MessageServiceChatHistoryIndexTest {

    private static final long TENANT = 10002L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MessageRepository messageRepository;
    private TenantService tenantService;
    private KnowledgeCatalogPort knowledgeCatalog;
    private MessageService service;

    @BeforeEach
    void setUp() {
        messageRepository = mock(MessageRepository.class);
        tenantService = mock(TenantService.class);
        knowledgeCatalog = mock(KnowledgeCatalogPort.class);
        service = new MessageService(
                mock(SessionRepository.class),
                messageRepository,
                mock(MessageSuggestionRepository.class),
                knowledgeCatalog,
                tenantService,
                mock(HybridSearchPort.class),
                mock(ModelRuntime.class));
        TenantContext.set(TENANT, null, null, false, "u-1", false);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void tenantWith(ObjectNode chatHistory) {
        // 自 A③ 起租户配置经 L1 端口读（TenantConfigLookup），不再取实体
        when(tenantService.chatHistoryConfig(TENANT)).thenReturn(chatHistory);
    }

    private static ObjectNode configured(String embeddingModelId, String kbId) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("enabled", true);
        n.put("embeddingModelId", embeddingModelId);
        n.put("knowledgeBaseId", kbId);
        return n;
    }

    private static KnowledgeRowView knowledge(String id) {
        KnowledgeRowView k = new KnowledgeRowView();
        k.setId(id);
        return k;
    }

    @Test
    void indexesPairWithThinkStrippedAndLinksMessage() {
        tenantWith(configured("emb-1", "kb-1"));
        when(knowledgeCatalog.createFromPassageSync(eq("kb-1"), anyList(), eq("")))
                .thenReturn(knowledge("k-1"));

        service.indexMessageToKb("问题", "先想一下<think>中间推理</think>最终答案", "m-1", "s-1");

        // passage 文案：剥 think 后去除首尾空白，userQuery 保持原样
        verify(knowledgeCatalog).createFromPassageSync("kb-1",
                List.of("[Session: s-1]\nQ: 问题\nA: 先想一下最终答案"), "");
        verify(messageRepository).updateKnowledgeId("m-1", "k-1");
    }

    @Test
    void blankPairIsSkippedBeforeAnyLookup() {
        tenantWith(configured("emb-1", "kb-1"));

        service.indexMessageToKb("   ", "<think>只剥到这里</think>", "m-1", "s-1");

        verifyNoInteractions(knowledgeCatalog);
        verifyNoInteractions(messageRepository);
    }

    @Test
    void unconfiguredTenantSkipsIndexing() {
        // enabled 但缺 embedding 模型 → 三要素不全，视为未配置
        ObjectNode n = MAPPER.createObjectNode();
        n.put("enabled", true);
        n.put("embeddingModelId", "");
        n.put("knowledgeBaseId", "kb-1");
        tenantWith(n);

        service.indexMessageToKb("q", "a", "m-1", "s-1");

        verifyNoInteractions(knowledgeCatalog);
    }

    @Test
    void creationFailureOnlyWarns() {
        tenantWith(configured("emb-1", "kb-1"));
        when(knowledgeCatalog.createFromPassageSync(anyString(), anyList(), anyString()))
                .thenThrow(new RuntimeException("段落 1 包含非法内容"));

        assertThatCode(() -> service.indexMessageToKb("q", "a", "m-1", "s-1"))
                .doesNotThrowAnyException();
        verify(messageRepository, never()).updateKnowledgeId(anyString(), anyString());
    }

    @Test
    void knowledgeIdWritebackFailureOnlyWarns() {
        tenantWith(configured("emb-1", "kb-1"));
        when(knowledgeCatalog.createFromPassageSync(anyString(), anyList(), anyString()))
                .thenReturn(knowledge("k-1"));
        doThrow(new RuntimeException("db down"))
                .when(messageRepository).updateKnowledgeId("m-1", "k-1");

        assertThatCode(() -> service.indexMessageToKb("q", "a", "m-1", "s-1"))
                .doesNotThrowAnyException();
    }
}
