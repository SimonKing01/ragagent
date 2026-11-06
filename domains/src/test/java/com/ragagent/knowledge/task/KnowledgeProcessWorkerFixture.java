package com.ragagent.knowledge.task;

import com.ragagent.contract.model.ModelCatalogPort;
import com.ragagent.contract.model.ModelRuntime;
import com.ragagent.contract.retrieval.RetrieveGraphRepository;
import com.ragagent.contract.retrieval.VectorIndexStore;
import com.ragagent.knowledge.client.DocReaderClient;
import com.ragagent.knowledge.config.KnowledgeOcrProperties;
import com.ragagent.knowledge.domain.span.SpanTracker;
import com.ragagent.knowledge.index.ChunkVectorIndexer;
import com.ragagent.knowledge.index.KnowledgeVectorWrites;
import com.ragagent.knowledge.repository.ChunkRepository;
import com.ragagent.knowledge.repository.IngestStateRepository;
import com.ragagent.knowledge.repository.KnowledgeBaseRepository;
import com.ragagent.knowledge.repository.KnowledgeRepository;
import com.ragagent.knowledge.service.image.ExtractedImageStore;
import com.ragagent.knowledge.storage.TenantFileStorage;
import com.ragagent.wiki.service.DefaultWikiKnowledgeFinalizer;
import com.ragagent.wiki.service.ingest.WikiIngestService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 测试装配：按生产 wiring 的顺序造阶段集合，避免各测试重复堆 20 个参数。
 */
final class KnowledgeProcessWorkerFixture {

    private KnowledgeProcessWorkerFixture() {
    }

    static KnowledgeProcessWorker worker(KnowledgeRepository knowledgeRepository,
            IngestStateRepository ingestStateRepository,
            KnowledgeBaseRepository kbRepository,
            ChunkRepository chunkRepository,
            TenantFileStorage fileStorage,
            ExtractedImageStore extractedImageStore,
            DocReaderClient docReader,
            KnowledgeOcrProperties ocrProperties,
            VectorIndexStore vectorStore,
            ModelCatalogPort modelCatalog,
            KnowledgeVectorWrites vectorWrites,
            ChunkVectorIndexer chunkVectorIndexer,
            ModelRuntime modelRuntime,
            ApplicationEventPublisher eventPublisher,
            SpanTracker spanTracker,
            RetrieveGraphRepository graphRepository,
            ObjectProvider<WikiIngestService> wikiIngestService,
            ObjectProvider<DefaultWikiKnowledgeFinalizer> wikiFinalizer,
            ObjectProvider<ChunkExtractTaskQueue> chunkQueue,
            ObjectProvider<QuestionGenerationTaskQueue> questionQueue) {
        KnowledgeTextExtractor textExtractor = new KnowledgeTextExtractor(
                fileStorage, extractedImageStore, docReader, kbRepository,
                new KnowledgeImageOcr(fileStorage, ocrProperties, spanTracker), null, spanTracker);
        KnowledgeIngestPipeline pipeline = new KnowledgeIngestPipeline(
                textExtractor,
                new KnowledgeChunkWriter(chunkRepository, spanTracker),
                new KnowledgeIndexStage(chunkRepository, extractedImageStore, vectorStore,
                        modelCatalog, vectorWrites, chunkVectorIndexer, modelRuntime,
                        graphRepository, spanTracker),
                new KnowledgeFinalizingStage(knowledgeRepository, ingestStateRepository,
                        kbRepository, chunkRepository, eventPublisher, spanTracker,
                        wikiIngestService, wikiFinalizer, chunkQueue, questionQueue));
        return new KnowledgeProcessWorker(knowledgeRepository, ingestStateRepository,
                kbRepository, spanTracker, pipeline);
    }
}
