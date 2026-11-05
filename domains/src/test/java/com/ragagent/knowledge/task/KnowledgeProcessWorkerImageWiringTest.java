package com.ragagent.knowledge.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ragagent.contract.model.ModelCatalogPort;
import com.ragagent.contract.model.ModelRuntime;
import com.ragagent.contract.retrieval.RetrieveGraphRepository;
import com.ragagent.contract.retrieval.VectorIndexStore;
import com.ragagent.knowledge.client.DocReaderClient;
import com.ragagent.knowledge.config.KnowledgeOcrProperties;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.domain.KnowledgeBaseIndexingStrategy;
import com.ragagent.knowledge.domain.span.SpanTracker;
import com.ragagent.knowledge.index.ChunkVectorIndexer;
import com.ragagent.knowledge.index.KnowledgeVectorWrites;
import com.ragagent.knowledge.repository.ChunkRepository;
import com.ragagent.knowledge.repository.IngestStateRepository;
import com.ragagent.knowledge.repository.KnowledgeBaseRepository;
import com.ragagent.knowledge.repository.KnowledgeRepository;
import com.ragagent.knowledge.service.image.ExtractedImageStore;
import com.ragagent.knowledge.storage.TenantFileStorage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 回流守卫：解析出的图片必须经 {@link ExtractedImageStore} 改写后再进分块正文。
 *
 * <p><b>探针（可证伪）</b>：把 worker 的 {@code extractMarkdown} 改回
 * {@code parsed.markdown()}，本类必须转红。</p>
 */
class KnowledgeProcessWorkerImageWiringTest {

    private static final String KNOWLEDGE_ID = "kid-img";
    private static final String KB_ID = "kb1";
    private static final String RAW_MARKDOWN = "![x](images/x.jpg)";
    private static final String REWRITTEN = "![x](local://1/exports/x.jpg)";

    private final KnowledgeRepository knowledgeRepository = mock(KnowledgeRepository.class);
    private final IngestStateRepository ingestStateRepository = mock(IngestStateRepository.class);
    private final KnowledgeBaseRepository kbRepository = mock(KnowledgeBaseRepository.class);
    private final ChunkRepository chunkRepository = mock(ChunkRepository.class);
    private final TenantFileStorage fileStorage = mock(TenantFileStorage.class);
    private final ExtractedImageStore extractedImageStore = mock(ExtractedImageStore.class);
    private final DocReaderClient docReader = mock(DocReaderClient.class);
    private final List<Chunk> inserted = new ArrayList<>();

    @SuppressWarnings("unchecked")
    private final KnowledgeProcessWorker worker = KnowledgeProcessWorkerFixture.worker(
            knowledgeRepository,
            ingestStateRepository,
            kbRepository,
            chunkRepository,
            fileStorage,
            extractedImageStore,
            docReader,
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

    @Test
    void extractedImagesAreRewrittenBeforeChunking() throws Exception {
        Knowledge k = new Knowledge();
        k.setId(KNOWLEDGE_ID);
        k.setTenantId(1L);
        k.setKnowledgeBaseId(KB_ID);
        k.setType("file");
        k.setTitle("a.pdf");
        k.setFileName("a.pdf");
        k.setFileType("pdf");
        k.setFilePath("resource://1/kid-img/a.pdf");
        k.setParseStatus(Knowledge.PARSE_PROCESSING);
        when(ingestStateRepository.casPendingToProcessing(eq(KNOWLEDGE_ID), anyLong(), any())).thenReturn(1);
        when(knowledgeRepository.findByIdAny(KNOWLEDGE_ID)).thenReturn(k);

        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(KB_ID);
        kb.setTenantId(1L);
        // 索引全关：跳过嵌入（本用例只钉"图片改写先于切分"）
        kb.setIndexingStrategy(new KnowledgeBaseIndexingStrategy());
        when(kbRepository.findByIdAny(KB_ID)).thenReturn(kb);

        when(fileStorage.read(eq(1L), anyString())).thenReturn("pdf-bytes".getBytes());
        DocReaderClient.ImageRef ref = new DocReaderClient.ImageRef(
                "x.jpg", "images/x.jpg", "image/jpeg", new byte[] {1, 2, 3});
        when(docReader.read(any(), anyString(), anyString(), anyString(), any()))
                .thenReturn(new DocReaderClient.ParseResult(RAW_MARKDOWN, 1, List.of(ref)));
        when(extractedImageStore.storeAndRewrite(any(), anyList(), eq(RAW_MARKDOWN)))
                .thenReturn(REWRITTEN);
        doAnswer(invocation -> {
            inserted.add(invocation.getArgument(0));
            return null;
        }).when(chunkRepository).insert(any());

        worker.process(KNOWLEDGE_ID, null, 1L);

        verify(extractedImageStore).storeAndRewrite(eq(k), anyList(), eq(RAW_MARKDOWN));
        assertThat(inserted).isNotEmpty();
        assertThat(inserted.get(0).getContent()).contains("local://1/exports/x.jpg");
    }
}
