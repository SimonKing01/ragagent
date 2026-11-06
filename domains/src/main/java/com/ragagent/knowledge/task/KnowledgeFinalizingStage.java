package com.ragagent.knowledge.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.ExtractChunkPayload;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.domain.KnowledgeProcessingSpan;
import com.ragagent.knowledge.domain.QuestionBatchPayload;
import com.ragagent.knowledge.domain.span.SpanTracker;
import com.ragagent.knowledge.repository.ChunkRepository;
import com.ragagent.knowledge.repository.IngestStateRepository;
import com.ragagent.knowledge.repository.KnowledgeBaseRepository;
import com.ragagent.knowledge.repository.KnowledgeRepository;
import com.ragagent.knowledge.support.GraphChunkSelector;
import com.ragagent.knowledge.support.KnowledgeProcessedEvent;
import com.ragagent.knowledge.support.ProcessOverridesResolver;
import com.ragagent.knowledge.support.QuestionBatchPlanner;
import com.ragagent.knowledge.support.QuestionGenerationPolicy;
import com.ragagent.platform.access.TracingContext;
import com.ragagent.platform.tracing.langfuse.LangfuseTracing;
import com.ragagent.wiki.service.DefaultWikiKnowledgeFinalizer;
import com.ragagent.wiki.service.ingest.WikiIngestService;
import com.ragagent.wiki.service.ingest.support.WikiEnqueueResult;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 入库收尾阶段：富化计划、finalizing 晋升、摘要/问题/图谱/wiki fan-out、槽位释放与终态收口。
 *
 * <p>从 {@link KnowledgeProcessWorker} 抽出，保持原有判定与计数语义；worker 只负责
 * “索引完成 → 交给本阶段”。</p>
 */
final class KnowledgeFinalizingStage {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeFinalizingStage.class);

    private final KnowledgeRepository knowledgeRepository;
    private final IngestStateRepository ingestStateRepository;
    private final KnowledgeBaseRepository kbRepository;
    private final ChunkRepository chunkRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final SpanTracker spanTracker;
    private final ObjectProvider<WikiIngestService> wikiIngestService;
    private final ObjectProvider<DefaultWikiKnowledgeFinalizer> wikiKnowledgeFinalizer;
    private final ObjectProvider<ChunkExtractTaskQueue> chunkExtractQueue;
    private final ObjectProvider<QuestionGenerationTaskQueue> questionGenerationQueue;

    KnowledgeFinalizingStage(
            KnowledgeRepository knowledgeRepository,
            IngestStateRepository ingestStateRepository,
            KnowledgeBaseRepository kbRepository,
            ChunkRepository chunkRepository,
            ApplicationEventPublisher eventPublisher,
            SpanTracker spanTracker,
            ObjectProvider<WikiIngestService> wikiIngestService,
            ObjectProvider<DefaultWikiKnowledgeFinalizer> wikiKnowledgeFinalizer,
            ObjectProvider<ChunkExtractTaskQueue> chunkExtractQueue,
            ObjectProvider<QuestionGenerationTaskQueue> questionGenerationQueue) {
        this.knowledgeRepository = knowledgeRepository;
        this.ingestStateRepository = ingestStateRepository;
        this.kbRepository = kbRepository;
        this.chunkRepository = chunkRepository;
        this.eventPublisher = eventPublisher;
        this.spanTracker = spanTracker;
        this.wikiIngestService = wikiIngestService;
        this.wikiKnowledgeFinalizer = wikiKnowledgeFinalizer;
        this.chunkExtractQueue = chunkExtractQueue;
        this.questionGenerationQueue = questionGenerationQueue;
    }

    /** 富化 fan-out 的判定结果（三项独立判定：wiki 关、图开也要跑）。 */
    private record FinalizingPlan(boolean wiki, boolean graph, boolean question,
            List<Chunk> graphChunks, List<Chunk> questionChunks, int questionBatchCount) {
    }

    /**
     * 收尾：有富化（wiki/图谱/问题生成任一）→ 索引完成推进启用态、（有摘要模型时）
     * 摘要 fan-out、processing 原子晋升 finalizing（pending_subtasks_count = wiki 槽 +
     * 问题批数 + 图分块数，摘要槽不计入）后入队各子任务；promote 失败 = 行被 cancel/
     * delete 抢走 → 跳过富化不覆盖状态。无富化 → 直接完成/失败收口。
     */
    void finalizeProcessing(Knowledge k, KnowledgeBase kb, String knowledgeId,
            int attempt, long parseAttempt, List<Chunk> chunks) {
        FinalizingPlan plan = planFinalizing(k, kb, chunks);
        if (!plan.wiki() && !plan.graph() && !plan.question()) {
            failOrComplete(knowledgeId, attempt, parseAttempt, null);
            return;
        }
        SpanTracker.SpanHandle postSpan = beginStageSpan(attempt, knowledgeId,
                KnowledgeProcessingSpan.STAGE_POST_PROCESS, null);
        int pendingSubtasks = (plan.wiki() ? 1 : 0) + plan.questionBatchCount()
                + plan.graphChunks().size();
        if (promoteFinalizing(knowledgeId, parseAttempt, pendingSubtasks)) {
            // 先抢到 ownership 再推进启用态：promote 失败说明行已被 cancel/重解析抢走
            markIndexedEnabled(knowledgeId, parseAttempt);
            if (hasSummaryModel(kb)) {
                knowledgeRepository.setSummaryStatus(knowledgeId, "none",
                        OffsetDateTime.now(ZoneOffset.UTC));
                spawnSummaryFanOut(knowledgeId);
            }
            if (plan.wiki()) {
                enqueueWikiIngest(knowledgeId, k);
            }
            if (plan.graph()) {
                enqueueGraphExtracts(knowledgeId, k, kb, plan.graphChunks(), attempt);
            }
            if (plan.question()) {
                enqueueQuestionBatches(knowledgeId, k, kb, plan.questionChunks(), attempt);
            }
        }
        endStageSpan(postSpan, null);
        // root 收口：wiki 交由 finalizing 计数兜底，但本 attempt 的 post-process 已完成
        // ——不收口会让 trace 的「知识处理」永远显示计时中
        if (attempt > 0) {
            spanTracker.finalizeAttempt(knowledgeId, attempt,
                    KnowledgeProcessingSpan.STATUS_DONE, null, "", "");
        }
    }

    private FinalizingPlan planFinalizing(Knowledge k, KnowledgeBase kb, List<Chunk> chunks) {
        // wiki 子任务必须能解析出合成模型才入队：缺模型时 ingest 批次必然失败并反复
        // 重试（MaxRetry 次退避），而该子任务槽一直被占住 → 文档永远停在 finalizing。
        // 模型后来补齐时，KB 侧触发（上传/重解析）会重新入队，此处跳过不丢功能。
        boolean wiki = kb.getIndexingStrategy() != null
                && kb.getIndexingStrategy().isWikiEnabled()
                && !chunks.isEmpty()
                && hasWikiSynthesisModel(kb);
        boolean kbGraphEnabled = kb.getIndexingStrategy() != null
                && kb.getIndexingStrategy().isGraphEnabled();
        // 抽取配置（含逐文档覆盖）也要在此拦一道：否则会白入队一批注定被 worker 跳过的子任务
        JsonNode extractCfg = ProcessOverridesResolver.extractConfig(k, kb.getExtractConfig());
        boolean extractEnabled = extractCfg != null && extractCfg.path("enabled").asBoolean(false);
        List<Chunk> graphChunks = ProcessOverridesResolver.graphEnabled(k, kbGraphEnabled)
                && extractEnabled
                        ? GraphChunkSelector.selectGraphChunks(chunks)
                        : List.of();
        // 逐文档覆盖优先：上传确认弹窗里关掉推荐问题时，不能再用 KB 级开关覆盖它
        boolean questionEnabled = QuestionGenerationPolicy.enabled(k, kb);
        boolean kbNeedsEmbedding = kb.getIndexingStrategy() != null
                && (kb.getIndexingStrategy().isVectorEnabled()
                        || kb.getIndexingStrategy().isKeywordEnabled());
        List<Chunk> questionChunks = questionEnabled && kbNeedsEmbedding
                && !chunks.isEmpty()
                        ? QuestionBatchPlanner.selectQuestionChunks(chunks)
                        : List.of();
        int questionBatchCount = QuestionBatchPlanner.batchCount(questionChunks.size());
        return new FinalizingPlan(wiki, !graphChunks.isEmpty(),
                questionBatchCount > 0, graphChunks, questionChunks, questionBatchCount);
    }

    /** KB 是否配置了摘要模型。 */
    private static boolean hasSummaryModel(KnowledgeBase kb) {
        return kb != null && kb.getSummaryModelId() != null && !kb.getSummaryModelId().isEmpty();
    }

    /**
     * wiki 合成模型是否可解析——与 ingest 侧同一口径：{@code wikiConfig.synthesisModelId}
     * 优先，空则回落 KB 的 {@code summaryModelId}。
     *
     * <p>刻意读 camelCase 键：{@code knowledge_bases.wiki_config} 的 Java 值类型
     * {@code wiki.domain.WikiConfig} 无线名注解，读写都是 Java 字段名。</p>
     */
    private static boolean hasWikiSynthesisModel(KnowledgeBase kb) {
        if (kb == null) {
            return false;
        }
        JsonNode wikiConfig = kb.getWikiConfig();
        if (wikiConfig != null && wikiConfig.isObject()
                && !wikiConfig.path("synthesisModelId").asText("").isEmpty()) {
            return true;
        }
        return hasSummaryModel(kb);
    }

    /**
     * 摘要 fan-out：
     * 仅当有文本 chunk 时派发。条件化派发的背景见 {@code failOrComplete} 注释
     * （Java 进程内 worker 会真实处理，无条件派发会污染契约测试的 HTTP 快照）。
     */
    private void spawnSummaryFanOut(String knowledgeId) {
        long textChunkCount = chunkRepository.countByKnowledgeIdAndChunkType(knowledgeId, "text");
        if (textChunkCount <= 0) {
            return;
        }
        try {
            // 同步领域事件：订阅方 KnowledgeSummaryService#onKnowledgeProcessed
            // 在本线程内执行，异常从这里冒出（由上方 try/catch 兜底）。
            eventPublisher.publishEvent(new KnowledgeProcessedEvent(knowledgeId));
        } catch (RuntimeException e) {
            log.warn("Post-process summary fan-out failed for knowledge {}", knowledgeId, e);
        }
    }

    /**
     * 索引完成的无条件推进：{@code enable_status=enabled}
     * + {@code processed_at}。{@code storage_size} 的 Java 侧计算未接线，保持既有形态。
     */
    private void markIndexedEnabled(String knowledgeId, long parseAttempt) {
        ingestStateRepository.markIndexedEnabledIfOwned(knowledgeId, parseAttempt,
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    /**
     * processing 原子晋升 finalizing 并写入子任务计数的
     * 状态翻转部分：一次条件更新把 {@code processing} 原子翻到 {@code finalizing}，
     * 并置 {@code pending_subtasks_count=1}（wiki 子任务占用的那个槽）。
     * <p>返回 false = 行已不在 processing（cancel/delete 抢走）——调用方必须跳过
     * 富化且不得覆盖状态。</p>
     */
    private boolean promoteFinalizing(String knowledgeId, long parseAttempt, int pendingSubtasks) {
        int promoted = ingestStateRepository.promoteToFinalizing(knowledgeId, parseAttempt, pendingSubtasks,
                OffsetDateTime.now(ZoneOffset.UTC));
        if (promoted > 0) {
            log.info("[KnowledgePostProcess] Knowledge {} entered finalizing ({} subtask(s) pending)",
                    knowledgeId, pendingSubtasks);
        }
        return promoted > 0;
    }

    /**
     * 图抽取 fan-out：逐块入队 {@code chunk:extract}，{@code model_id}
     * worker 侧续接同一棵树。
     * <p>入队失败的槽位<b>立即释放</b>。</p>
     */
    private void enqueueGraphExtracts(String knowledgeId, Knowledge k, KnowledgeBase kb,
            List<Chunk> graphChunks, int attempt) {
        ChunkExtractTaskQueue queue = chunkExtractQueue.getIfAvailable();
        if (queue == null) {
            log.info("[KnowledgePostProcess] chunk extract queue unavailable, releasing {} slot(s) for {}",
                    graphChunks.size(), knowledgeId);
            releaseSlots(knowledgeId, graphChunks.size());
            return;
        }
        TracingContext tracing = LangfuseTracing.inject();
        int index = 0;
        for (Chunk chunk : graphChunks) {
            try {
                queue.enqueue(ExtractChunkPayload.withTracing(k.getTenantId(), chunk.getId(),
                        kb.getSummaryModelId() == null ? "" : kb.getSummaryModelId(),
                        knowledgeId, attempt, index, tracing));
            } catch (RuntimeException e) {
                log.error("[KnowledgePostProcess] Failed to create chunk extract task for {}", chunk.getId(), e);
                releaseSlots(knowledgeId, 1);
            }
            index++;
        }
    }

    /**
     * 问题生成 fan-out：按 {@link QuestionBatchPlanner#BATCH_SIZE} 分批入队
     * {@code question:generation}，载荷只带 chunk id（+ 边界邻块 id），worker 运行时装读内容。
     * <p>入队失败的批<b>立即释放</b>该批占用的槽位。</p>
     */
    private void enqueueQuestionBatches(String knowledgeId, Knowledge k, KnowledgeBase kb,
            List<Chunk> questionChunks, int attempt) {
        List<QuestionBatchPlanner.Batch> batches = QuestionBatchPlanner.planBatches(questionChunks);
        QuestionGenerationTaskQueue queue = questionGenerationQueue.getIfAvailable();
        if (queue == null) {
            log.info("[KnowledgePostProcess] question generation queue unavailable, releasing {} slot(s) for {}",
                    batches.size(), knowledgeId);
            releaseSlots(knowledgeId, batches.size());
            return;
        }
        TracingContext tracing = LangfuseTracing.inject();
        int questionCount = QuestionGenerationPolicy.count(k, kb);
        for (QuestionBatchPlanner.Batch batch : batches) {
            try {
                queue.enqueue(QuestionBatchPayload.withTracing(k.getTenantId(), kb.getId(), knowledgeId,
                        questionCount, "", attempt, batch.chunkIds(), batch.index(),
                        batch.prevChunkId(), batch.nextChunkId(), tracing));
            } catch (RuntimeException e) {
                log.error("[KnowledgePostProcess] Failed to enqueue question batch {} for {}", batch.index(), knowledgeId, e);
                releaseSlots(knowledgeId, 1);
            }
        }
    }

    /** 释放 n 个 finalizing 槽（逐次递减+晋升，幂等到计数归零）。 */
    private void releaseSlots(String knowledgeId, int count) {
        for (int i = 0; i < count; i++) {
            releaseWikiSlot(knowledgeId);
        }
    }

    /**
     * op 落库 + 防抖触发。op 未被接受（如 KB 已删）或入队异常时
     * 避免行搁浅在 finalizing。触发失败只记警告——op 已落库，不从重追加。
     */
    private void enqueueWikiIngest(String knowledgeId, Knowledge k) {
        WikiIngestService service = wikiIngestService.getIfAvailable();
        if (service == null) {
            log.info("[KnowledgePostProcess] Wiki ingest service unavailable, releasing slot for {}",
                    knowledgeId);
            releaseWikiSlot(knowledgeId);
            return;
        }
        try {
            WikiEnqueueResult result = service
                    .enqueueWikiIngest(k.getTenantId(), k.getKnowledgeBaseId(), knowledgeId);
            if (result.accepted()) {
                log.info("[KnowledgePostProcess] Enqueued wiki ingest task for {}", knowledgeId);
                if (result.error() != null) {
                    log.warn("[KnowledgePostProcess] Wiki trigger enqueue failed for {}", knowledgeId, result.error());
                }
            } else {
                log.warn("[KnowledgePostProcess] Wiki pending op not accepted for {}", knowledgeId, result.error());
                releaseWikiSlot(knowledgeId);
            }
        } catch (RuntimeException e) {
            log.warn("[KnowledgePostProcess] Wiki ingest enqueue failed for {}", knowledgeId, e);
            releaseWikiSlot(knowledgeId);
        }
    }

    /**
     * 释放 wiki 槽（finalizer 的"递减+晋升"两步写）。
     * finalizer 缺席时静默——行由 finalizing housekeeping sweep 兜底。
     */
    private void releaseWikiSlot(String knowledgeId) {
        DefaultWikiKnowledgeFinalizer finalizer = wikiKnowledgeFinalizer.getIfAvailable();
        if (finalizer == null) {
            return;
        }
        try {
            finalizer.finalizeWikiSubtask(knowledgeId);
        } catch (RuntimeException e) {
            log.warn("[KnowledgePostProcess] Release wiki slot failed for {}", knowledgeId, e);
        }
    }

    void failOrComplete(String knowledgeId, int attempt, long parseAttempt, String error) {
        if (error == null) {
            SpanTracker.SpanHandle postSpan = beginStageSpan(attempt, knowledgeId,
                    KnowledgeProcessingSpan.STAGE_POST_PROCESS, null);
            // 索引完成即推进启用态（finalizing 前置）：
            // 索引完成 → summary_status=none（post-process fan-out 随后按需改 pending）。
            // 仅当 KB 配了 summary model 时推进——进程内 worker 在契约测试里会真实
            // 处理，无条件推进会让异步副作用污染 HTTP 快照断言；
            // 无 summary model 的 KB 因此不做 none/failed 中间态。
            Knowledge row = knowledgeRepository.findByIdAny(knowledgeId);
            KnowledgeBase rowKb = row == null ? null
                    : kbRepository.findByIdAny(row.getKnowledgeBaseId());
            boolean summaryModelConfigured = hasSummaryModel(rowKb);
            int completedRows = ingestStateRepository.completeIfOwned(knowledgeId, parseAttempt,
                    summaryModelConfigured, OffsetDateTime.now(ZoneOffset.UTC));
            if (completedRows == 0) {
                log.info("[KnowledgeProcess] completion write skipped for {}: row not owned", knowledgeId);
            }
            if (summaryModelConfigured) {
                spawnSummaryFanOut(knowledgeId);
            }
            endStageSpan(postSpan, null);
            if (attempt > 0) {
                spanTracker.finalizeAttempt(knowledgeId, attempt,
                        KnowledgeProcessingSpan.STATUS_DONE, null, "", "");
            }
        } else {
            // CAS：只有仍由本次处理占用的行（processing）才许翻 failed。
            int failedRows = ingestStateRepository.failIfProcessing(knowledgeId, parseAttempt,
                    abbreviate(error), OffsetDateTime.now(ZoneOffset.UTC));
            if (failedRows == 0) {
                log.info("[KnowledgeProcess] failure write skipped for {}: row not processing", knowledgeId);
            }
            // 非阶段失败（模型解析/预清理等）没有 failSpan 收口 → 显式 finalize（幂等）
            if (attempt > 0) {
                spanTracker.finalizeAttempt(knowledgeId, attempt,
                        KnowledgeProcessingSpan.STATUS_FAILED, null, "", abbreviate(error));
            }
        }
    }

    private static String abbreviate(String s) {
        return s.length() > 2000 ? s.substring(0, 2000) : s;
    }

    private SpanTracker.SpanHandle beginStageSpan(int attempt, String knowledgeId,
            String stage, Map<String, Object> input) {
        if (attempt <= 0) {
            return null;
        }
        return spanTracker.beginStage(knowledgeId, attempt, stage, input);
    }

    private void endStageSpan(SpanTracker.SpanHandle span, Map<String, Object> output) {
        if (span != null) {
            spanTracker.endSpan(span, output);
        }
    }
}
