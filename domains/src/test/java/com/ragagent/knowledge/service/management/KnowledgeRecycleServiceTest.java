package com.ragagent.knowledge.service.management;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.index.KnowledgeIndexCleaner;
import com.ragagent.knowledge.repository.ChunkRepository;
import com.ragagent.knowledge.repository.KnowledgeRepository;
import com.ragagent.knowledge.service.KnowledgeAccessHelper;
import com.ragagent.knowledge.service.image.ExtractedImageStore;
import com.ragagent.knowledge.storage.TenantFileStorage;
import com.ragagent.platform.access.TenantContext;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 删除回收用例的验收：
 * 库内失败不碰物理资源、单条/批量共用清单、单项清理失败不阻塞其余项、已删行 no-op。
 */
class KnowledgeRecycleServiceTest {

    private static final long TENANT = 10002L;
    private static final String KB = "kb-1";

    private KnowledgeRepository knowledgeRepository;
    private ChunkRepository chunkRepository;
    private TenantFileStorage fileStorage;
    private ExtractedImageStore extractedImageStore;
    private KnowledgeIndexCleaner indexCleaner;
    private KnowledgeAccessHelper access;
    private KnowledgeRecycleService service;

    @BeforeEach
    void setUp() {
        knowledgeRepository = mock(KnowledgeRepository.class);
        chunkRepository = mock(ChunkRepository.class);
        fileStorage = mock(TenantFileStorage.class);
        extractedImageStore = mock(ExtractedImageStore.class);
        indexCleaner = mock(KnowledgeIndexCleaner.class);
        access = mock(KnowledgeAccessHelper.class);
        service = new KnowledgeRecycleService(knowledgeRepository, chunkRepository, fileStorage,
                extractedImageStore, indexCleaner, access);
        TenantContext.set(TENANT, null, null, false, "u-1", false);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void recycleOne_softDeletesRowsThenCleansEveryResource() {
        Knowledge k = knowledge("k-1");
        when(access.getKnowledge("k-1")).thenReturn(k);
        when(access.findKb(KB)).thenReturn(kb());

        String taskId = service.recycleOne("k-1");

        assertThat(taskId).isNotBlank();
        verify(knowledgeRepository).softDelete(eq("k-1"), any(OffsetDateTime.class));
        verify(chunkRepository).softDeleteByKnowledgeId(eq("k-1"), any(OffsetDateTime.class));
        verify(fileStorage).delete(TENANT, "k-1", k.getFilePath());
        verify(extractedImageStore).releaseFor(TENANT, "k-1");
        verify(indexCleaner).cleanup(eq(k), any(KnowledgeBase.class));
    }

    @Test
    void recycleOne_whenSecondDbWriteFails_neverTouchesPhysicalResources() {
        Knowledge k = knowledge("k-1");
        when(access.getKnowledge("k-1")).thenReturn(k);
        when(access.findKb(KB)).thenReturn(kb());
        doThrow(new IllegalStateException("db write failed"))
                .when(chunkRepository).softDeleteByKnowledgeId(eq("k-1"), any(OffsetDateTime.class));

        assertThatThrownBy(() -> service.recycleOne("k-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("db write failed");

        verifyNoInteractions(fileStorage, extractedImageStore, indexCleaner);
    }

    @Test
    void recycleBatch_recyclesOnlyLiveRows() {
        Knowledge live = knowledge("k-live");
        Knowledge alreadyDeleted = knowledge("k-gone");
        when(access.findKb(KB)).thenReturn(kb());
        when(knowledgeRepository.findById(TENANT, "k-live")).thenReturn(live);
        when(knowledgeRepository.findById(TENANT, "k-gone")).thenReturn(alreadyDeleted);
        when(knowledgeRepository.softDeleteIfActive(eq("k-live"), any(OffsetDateTime.class))).thenReturn(1);
        when(knowledgeRepository.softDeleteIfActive(eq("k-gone"), any(OffsetDateTime.class))).thenReturn(0);

        service.recycleBatch(KB, List.of("k-live", "k-gone"));

        verify(fileStorage).delete(TENANT, "k-live", live.getFilePath());
        verify(fileStorage, never()).delete(eq(TENANT), eq("k-gone"), anyString());
        verify(indexCleaner).cleanup(eq(live), any(KnowledgeBase.class));
        verify(indexCleaner, never()).cleanup(eq(alreadyDeleted), any(KnowledgeBase.class));
    }

    @Test
    void recycleBatch_skipsUnknownIds() {
        when(access.findKb(KB)).thenReturn(kb());
        when(knowledgeRepository.findById(TENANT, "missing")).thenReturn(null);

        service.recycleBatch(KB, List.of("missing"));

        verify(knowledgeRepository, never()).softDeleteIfActive(anyString(), any(OffsetDateTime.class));
        verifyNoInteractions(fileStorage, extractedImageStore, indexCleaner);
    }

    @Test
    void cleanup_keepsGoingWhenOneResourceFails() {
        Knowledge k = knowledge("k-1");
        when(access.getKnowledge("k-1")).thenReturn(k);
        when(access.findKb(KB)).thenReturn(kb());
        doThrow(new IllegalStateException("disk full"))
                .when(fileStorage).delete(TENANT, "k-1", k.getFilePath());

        assertThatCode(() -> service.recycleOne("k-1")).doesNotThrowAnyException();

        verify(extractedImageStore).releaseFor(TENANT, "k-1");
        verify(indexCleaner).cleanup(eq(k), any(KnowledgeBase.class));
    }

    private static Knowledge knowledge(String id) {
        Knowledge k = new Knowledge();
        k.setId(id);
        k.setTenantId(TENANT);
        k.setKnowledgeBaseId(KB);
        k.setFilePath("local://" + TENANT + "/knowledge/" + id + "/doc.md");
        return k;
    }

    private static KnowledgeBase kb() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(KB);
        kb.setTenantId(TENANT);
        kb.setType("document");
        return kb;
    }
}
