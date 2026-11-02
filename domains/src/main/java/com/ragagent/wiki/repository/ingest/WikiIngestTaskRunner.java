package com.ragagent.wiki.repository.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.platform.tracing.langfuse.LangfuseTaskScope;
import com.ragagent.platform.web.JsonMappers;
import com.ragagent.wiki.domain.TaskDeadLetter;
import com.ragagent.wiki.domain.ingest.WikiIngestConstants;
import com.ragagent.wiki.domain.ingest.WikiIngestPayload;
import com.ragagent.wiki.domain.ingest.WikiIngestSlotReleaser;
import com.ragagent.wiki.domain.ingest.WikiIngestTask;
import com.ragagent.wiki.repository.TaskDeadLetterRepository;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * wiki 任务的<b>执行与归档公共逻辑</b>（{@link InProcessWikiIngestTaskQueue} 与
 * {@link RedisWikiIngestTaskQueue} 共用）。
 *
 * <p>本类只承载"传输无关"的部分：载荷解析、handler 分派（含 Langfuse 任务 span）、
 * 死信归档、任务级终态失败后的槽位收尾、重试延迟公式。
 * 把失败的任务"放回哪里重试"（进程内 scheduler vs Redis ZSET）留给调用方——
 * 那是两种传输的本质差异，也正是这里不抽象的部分。</p>
 */
final class WikiIngestTaskRunner {

    private static final Logger log = LoggerFactory.getLogger(WikiIngestTaskRunner.class);

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    /**
     * 锁冲突（{@code ErrWikiIngestConcurrent}）的重试延迟——短到用户无感，
     * 又足以让刚被遗弃的锁过期。
     */
    static final Duration CONCURRENT_CONFLICT_RETRY_DELAY = Duration.ofSeconds(15);

    private final ObjectProvider<WikiIngestTaskHandler> handlerProvider;
    private final ObjectProvider<TaskDeadLetterRepository> deadLetterProvider;
    private final ObjectProvider<WikiIngestSlotReleaser> slotReleaserProvider;

    WikiIngestTaskRunner(ObjectProvider<WikiIngestTaskHandler> handlerProvider,
            ObjectProvider<TaskDeadLetterRepository> deadLetterProvider,
            ObjectProvider<WikiIngestSlotReleaser> slotReleaserProvider) {
        this.handlerProvider = handlerProvider;
        this.deadLetterProvider = deadLetterProvider;
        this.slotReleaserProvider = slotReleaserProvider;
    }

    /**
     * 分派一次任务（不含超时看门狗——那属于调用方的执行环境）。
     *
     * @return {@code null} = <b>终态</b>：成功，或 handler 缺失的丢弃
     *         （handler 尚未接线时重试只会刷无信息的 warn；调用方两种都走"释放槽位"）；
     *         非 null = 失败原因，调用方据此走重试/死信。
     */
    Throwable dispatchOnce(WikiIngestTask task) {
        try {
            WikiIngestTaskHandler handler = handlerProvider.getIfAvailable();
            if (handler == null) {
                // 没有处理器 bean 不是可重试的失败：它表示 batch/finalize 尚未接线，
                // 重试 10 次只会刷 10 行无信息的 warn。
                // 但调用方必须<b>释放 TaskID</b>——否则该 KB 的 finalize 合并会被一个
                // 永不存在的任务永久占住（比丢一次任务严重得多）。
                log.warn("wiki task queue: no WikiIngestTaskHandler bean registered, "
                        + "dropping {} task (batch/finalize translation not wired yet)", task.type());
                return null;
            }
            WikiIngestPayload payload = parsePayload(task);
            // 任务侧观测——负载带 traceparent 就续接上游
            // trace，否则以任务类型开独立根；处理体包在 asynq.<type> span 内，收尾记 outcome。
            try (LangfuseTaskScope scope =
                         LangfuseTaskScope.start(
                                 task.type(), payload.tracing(),
                                 java.util.Map.of("knowledge_base_id",
                                         payload.knowledgeBaseId() == null ? "" : payload.knowledgeBaseId()),
                                 LangfuseTaskScope.previewPayload(task.payload()))) {
                if (WikiIngestTask.TYPE_WIKI_FINALIZE.equals(task.type())) {
                    handler.processWikiFinalize(payload);
                } else {
                    handler.processWikiIngest(payload);
                }
            }
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    /**
     * 任务进死信：归档 + 释放该 KB 在途 op 对应文档的 finalizing 槽位（只对 ingest 任务）。
     */
    void archive(WikiIngestTask task, Throwable failure, int attempt) {
        // 先释放槽位再归档：任务级的终态失败意味着该 KB 的 op 没人结算了，
        // 不释放的话对应文档会一直停在「优化中」（op 保留，等下次触发重跑）。
        releaseAbandonedSubtaskSlots(task);
        TaskDeadLetterRepository repo = deadLetterProvider.getIfAvailable();
        if (repo == null) {
            return;
        }
        try {
            WikiIngestPayload payload = parsePayload(task);
            TaskDeadLetter dl = new TaskDeadLetter();
            dl.setTenantId(payload.tenantId());
            dl.setTaskType(task.type());
            dl.setScope(WikiIngestConstants.TASK_SCOPE);
            dl.setScopeId(payload.knowledgeBaseId());
            dl.setRelatedId("");
            dl.setPayload(MAPPER.readTree(task.payload()));
            dl.setLastError(failure == null ? "" : failure.toString());
            dl.setFailCount(attempt);
            repo.insert(dl);
        } catch (Exception e) {
            // 尽力而为：归档失败不得掩盖底层任务错误
            log.warn("wiki task queue: failed to archive {} to dead letters", task.type(), e);
        }
    }

    /** 任务进死信时，释放该 KB 在途 op 对应文档的 finalizing 槽位（只对 ingest 任务）。 */
    private void releaseAbandonedSubtaskSlots(WikiIngestTask task) {
        if (!WikiIngestTask.TYPE_WIKI_INGEST.equals(task.type())) {
            return;
        }
        WikiIngestSlotReleaser service = slotReleaserProvider.getIfAvailable();
        if (service == null) {
            return;   // 未接线（测试/裁剪装配）
        }
        try {
            WikiIngestPayload payload = parsePayload(task);
            service.releaseSlotsForAbandonedTask(payload.knowledgeBaseId());
        } catch (RuntimeException e) {
            // 尽力而为：收尾失败不得掩盖底层任务错误
            log.warn("wiki task queue: release abandoned subtask slots failed", e);
        }
    }

    WikiIngestPayload parsePayload(WikiIngestTask task) {
        try {
            WikiIngestPayload payload = MAPPER.readValue(task.payload(), WikiIngestPayload.class);
            return payload == null ? WikiIngestPayload.of("") : payload;
        } catch (Exception e) {
            // 负载损坏不是可修复的失败：直接抛出，让上层按普通失败路径
            // 走重试/死信，行为与通用队列保持一致
            throw new IllegalArgumentException("wiki task queue: unmarshal payload: " + e.getMessage(), e);
        }
    }

    /**
     * 重试延迟：锁冲突走固定的 15 秒，其余走默认退避公式。
     *
     * <p>为什么锁冲突不能指数退避：新被遗弃的锁 ≤60 秒就过期，15 秒的固定重试
     * 几乎保证下一次尝试成功。没有这个覆盖，崩溃-重启循环会让一个 KB 在
     * 7–10 分钟内无法推进（等孤儿锁过期 <b>并且</b> 等退避计划追上）。</p>
     *
     * <p>默认公式：{@code n^4 + 15 + rand(30) * (n + 1)} 秒。</p>
     */
    static long retryDelaySeconds(int alreadyRetried, Throwable failure) {
        if (failure instanceof WikiIngestConstants.ConcurrentTaskActiveException) {
            return CONCURRENT_CONFLICT_RETRY_DELAY.toSeconds();
        }
        long n = alreadyRetried;
        return (n * n * n * n) + 15 + ThreadLocalRandom.current().nextLong(30) * (n + 1);
    }
}
