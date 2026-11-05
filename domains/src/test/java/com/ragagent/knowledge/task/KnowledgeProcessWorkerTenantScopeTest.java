package com.ragagent.knowledge.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ragagent.contract.model.ModelCatalogPort;
import com.ragagent.contract.model.ModelRuntime;
import com.ragagent.contract.retrieval.RetrieveGraphRepository;
import com.ragagent.contract.retrieval.VectorIndexStore;
import com.ragagent.knowledge.client.DocReaderClient;
import com.ragagent.knowledge.config.KnowledgeOcrProperties;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.span.SpanTracker;
import com.ragagent.knowledge.index.ChunkVectorIndexer;
import com.ragagent.knowledge.index.KnowledgeVectorWrites;
import com.ragagent.knowledge.repository.ChunkRepository;
import com.ragagent.knowledge.repository.IngestStateRepository;
import com.ragagent.knowledge.repository.KnowledgeBaseRepository;
import com.ragagent.knowledge.repository.KnowledgeRepository;
import com.ragagent.knowledge.service.image.ExtractedImageStore;
import com.ragagent.knowledge.storage.TenantFileStorage;
import com.ragagent.platform.access.TenantContext;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 回归：worker 管线必须在<b>知识行租户作用域</b>内执行。
 *
 * <p><b>现场</b>：上传文档后 {@code parse_status=failed}、{@code error_message} 为
 * {@code error code: 1003, error message: Model not found}，span 表显示 docreader / chunking
 * 都 done、**embedding 阶段失败**。根因：向量化
 * （{@code ChunkVectorIndexer.resolveEmbeddingModel} → {@code ModelService.getModelById}）与
 * 富化都按<b>租户可见性</b>过滤（{@code tenant_id = 当前租户 OR is_builtin}），而 worker
 * 虚拟线程没有 HTTP Filter 链填 {@link TenantContext} ⇒ 空租户（按 0 查）看不到
 * <b>用户自建模型</b>（{@code is_builtin = false}）⇒ 1003。</p>
 *
 * <p><b>探针（可证伪）</b>：把 {@code KnowledgeProcessWorker.process} 里的
 * {@code TenantScopes.enterCaptured(...)} 去掉，本类第一条用例必须转红。</p>
 */
class KnowledgeProcessWorkerTenantScopeTest {

    private final KnowledgeRepository knowledgeRepository = mock(KnowledgeRepository.class);
    private final IngestStateRepository ingestStateRepository = mock(IngestStateRepository.class);
    @SuppressWarnings("unchecked")

    private final KnowledgeProcessWorker worker = KnowledgeProcessWorkerFixture.worker(
            knowledgeRepository,
            ingestStateRepository,
            mock(KnowledgeBaseRepository.class),
            mock(ChunkRepository.class),
            mock(TenantFileStorage.class),
            mock(ExtractedImageStore.class),
            mock(DocReaderClient.class),
            new KnowledgeOcrProperties("", 30, 60, 1, null),
            mock(VectorIndexStore.class),
            mock(ModelCatalogPort.class),
            mock(KnowledgeVectorWrites.class),
            mock(ChunkVectorIndexer.class),
            mock(ModelRuntime.class),
            mock(ApplicationEventPublisher.class),
            mock(SpanTracker.class),
            mock(RetrieveGraphRepository.class),
            mock(ObjectProvider.class),
            mock(ObjectProvider.class),
            mock(ObjectProvider.class),
            mock(ObjectProvider.class));

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("提交线程无租户上下文时，管线仍运行在捕获到的知识租户作用域内（返回后恢复）")
    void pipelineRunsInsideCapturedTenantScope() {
        TenantContext.clear();                       // 模拟 worker 虚拟线程：没有 Filter 链填过
        Knowledge row1 = new Knowledge();
        row1.setId("kid-1");
        row1.setTenantId(11L);
        when(knowledgeRepository.findByIdAny("kid-1")).thenReturn(row1);
        AtomicReference<Long> tenantSeenInPipeline = new AtomicReference<>();
        when(ingestStateRepository.casPendingToProcessing(anyString(), anyLong(), any()))
                .thenAnswer(invocation -> {
                    tenantSeenInPipeline.set(TenantContext.currentTenantId());
                    return 0;                        // 静默退出：只需证明进管线时租户已在
                });

        worker.process("kid-1", null, 11L);

        assertThat(tenantSeenInPipeline.get()).isEqualTo(11L);
        assertThat(TenantContext.currentTenantId()).isNull();   // 调用线程原会话必须被恢复
    }

    @Test
    @DisplayName("后台入队（提交线程没带租户）时回落到知识行所属租户")
    void fallsBackToKnowledgeRowTenant() {
        TenantContext.clear();
        Knowledge row = new Knowledge();
        row.setTenantId(7L);
        when(knowledgeRepository.findByIdAny("kid-2")).thenReturn(row);
        AtomicReference<Long> tenantSeenInPipeline = new AtomicReference<>();
        when(ingestStateRepository.casPendingToProcessing(anyString(), anyLong(), any()))
                .thenAnswer(invocation -> {
                    tenantSeenInPipeline.set(TenantContext.currentTenantId());
                    return 0;
                });

        worker.process("kid-2", null, null);

        assertThat(tenantSeenInPipeline.get()).isEqualTo(7L);
    }
}
