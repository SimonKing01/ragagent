package com.ragagent.knowledge.task;

import com.ragagent.contract.model.AsrTranscriber;
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
import com.ragagent.knowledge.support.FfmpegMedia;
import com.ragagent.knowledge.support.MediaPipelineDeps;
import com.ragagent.wiki.service.DefaultWikiKnowledgeFinalizer;
import com.ragagent.wiki.service.ingest.WikiIngestService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 入库主链路的阶段装配：把 20 个依赖收在装配层，Worker 只接收阶段集合。
 */
@Configuration
public class KnowledgeProcessWiring {

    @Bean
    public KnowledgeIngestPipeline knowledgeIngestPipeline(
            KnowledgeRepository knowledgeRepository,
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
            ModelRuntime modelRuntimeAssembler,
            ApplicationEventPublisher eventPublisher,
            SpanTracker spanTracker,
            RetrieveGraphRepository graphRepository,
            ObjectProvider<WikiIngestService> wikiIngestService,
            ObjectProvider<DefaultWikiKnowledgeFinalizer> wikiKnowledgeFinalizer,
            ObjectProvider<ChunkExtractTaskQueue> chunkExtractQueue,
            ObjectProvider<QuestionGenerationTaskQueue> questionGenerationQueue,
            AsrTranscriber asrTranscriber,
            @Value("${knowledge.media.max-duration-seconds:600}") int mediaMaxDurationSeconds,
            @Value("${knowledge.media.ffmpeg-path:ffmpeg}") String ffmpegPath,
            @Value("${knowledge.media.ffprobe-path:ffprobe}") String ffprobePath) {
        KnowledgeTextExtractor textExtractor = new KnowledgeTextExtractor(
                fileStorage, extractedImageStore, docReader, kbRepository,
                new KnowledgeImageOcr(fileStorage, ocrProperties, spanTracker),
                new KnowledgeMediaTranscriber(extractedImageStore,
                        new MediaPipelineDeps(modelCatalog, modelRuntimeAssembler, asrTranscriber,
                                kbRepository, knowledgeRepository,
                                new FfmpegMedia(ffmpegPath, ffprobePath), mediaMaxDurationSeconds)),
                spanTracker);
        KnowledgeChunkWriter chunkWriter = new KnowledgeChunkWriter(chunkRepository, spanTracker);
        KnowledgeIndexStage indexStage = new KnowledgeIndexStage(
                chunkRepository, extractedImageStore, vectorStore, modelCatalog, vectorWrites,
                chunkVectorIndexer, modelRuntimeAssembler, graphRepository, spanTracker);
        KnowledgeFinalizingStage finalizingStage = new KnowledgeFinalizingStage(
                knowledgeRepository, ingestStateRepository, kbRepository, chunkRepository,
                eventPublisher, spanTracker,
                wikiIngestService, wikiKnowledgeFinalizer, chunkExtractQueue, questionGenerationQueue);
        return new KnowledgeIngestPipeline(textExtractor, chunkWriter, indexStage, finalizingStage);
    }
}
