package com.ragagent.wiki.repository.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.platform.task.RedisRetryingTaskQueue;
import com.ragagent.platform.task.RetryPolicy;
import com.ragagent.platform.web.JsonMappers;
import com.ragagent.wiki.domain.ingest.WikiIngestSlotReleaser;
import com.ragagent.wiki.domain.ingest.WikiIngestTask;
import com.ragagent.wiki.repository.TaskDeadLetterRepository;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@link WikiIngestTaskQueue} 的 <b>Redis</b> 实现：跨实例共享的任务表。
 * {@code wiki.redis-enabled=true} 时由 {@code WikiRedisWiring} 装配
 * （{@code @Primary} 覆盖 {@link InProcessWikiIngestTaskQueue}）。
 *
 * <p>队列循环、租约/续期、重试预算、超时中断、TaskID 去重与死信回调都交给
 * {@link RedisRetryingTaskQueue}；本类只剩四件事：投递语义（TaskID 跨实例合并）、
 * 载荷编解码、重试/死信的 wiki 特例（锁冲突 15 秒、slot 释放），以及任务级 trace 的
 * 执行入口。</p>
 *
 * <h2>保真的语义（与 {@link WikiIngestTaskQueue} 的契约一致）</h2>
 * <ul>
 *   <li><b>延迟投递</b>：ready 的 score 即"最早可执行时刻"；</li>
 *   <li><b>TaskID 合并（跨实例）</b>：SETNX 全局唯一——finalize 的"窗口内合并成一次"
 *       在多副本下仍然成立（进程内实现会退化成每副本一次）；终态主动删键，
 *       实例崩溃时由 TTL（单次超时 + 30 分钟，下限 1 小时）兜底释放；</li>
 *   <li><b>重试</b>：失败按 {@link WikiIngestTaskRunner#retryDelaySeconds} 重排回 ready；
 *       耗尽后归档死信并释放 slot（逻辑与进程内实现共用 {@link WikiIngestTaskRunner}）；</li>
 *   <li><b>超时</b>：到点中断执行线程（基座的 Future 看门狗）；</li>
 *   <li><b>崩溃恢复</b>：执行中的任务是带租约的（3 分钟，每 1 分钟续期）；
 *       取任务的实例崩溃后，租约过期即被其它实例回收重投——至少一次语义，
 *       重复执行由 {@code task_pending_ops} 的行级认领兜住（空转不重复处理文档）。</li>
 * </ul>
 */
public class RedisWikiIngestTaskQueue implements WikiIngestTaskQueue {

    private static final Logger log = LoggerFactory.getLogger(RedisWikiIngestTaskQueue.class);

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    /** 执行中集合（测试手工构造在飞成员时用；ready/proc 由基座按前缀生成）。 */
    static final String KEY_PROC = "wiki:task:proc";

    /** TaskID 去重键前缀。 */
    static final String ID_PREFIX = "wiki:task:id:";

    private final RedisRetryingTaskQueue queue;

    private final WikiIngestTaskRunner runner;

    public RedisWikiIngestTaskQueue(StringRedisTemplate template,
            ObjectProvider<WikiIngestTaskHandler> handlerProvider,
            ObjectProvider<TaskDeadLetterRepository> deadLetterProvider,
            ObjectProvider<WikiIngestSlotReleaser> slotReleaserProvider) {
        this.runner = new WikiIngestTaskRunner(handlerProvider, deadLetterProvider, slotReleaserProvider);
        this.queue = new RedisRetryingTaskQueue(template, "wiki:task", "wiki:ingest",
                new RetryPolicy(0), this::run, new WikiFailurePolicy());
    }

    @Override
    public void setRetryDelayOverrideSeconds(Long seconds) {
        queue.setRetryDelayOverrideSeconds(seconds);
    }

    @Override
    public boolean enqueue(WikiIngestTask task) {
        long timeoutMillis = task.timeout() == null ? 0 : task.timeout().toMillis();
        long delayMillis = task.processIn() == null
                ? 0 : Math.max(0, task.processIn().toMillis());
        // 投递失败要抛（契约）；TaskID 冲突返回 false 表示已合并
        RedisRetryingTaskQueue.SubmitOutcome outcome = queue.submit(
                toJson(QueuedTask.of(task, 1)), delayMillis,
                RedisRetryingTaskQueue.TaskSpec.idempotent(
                        task.maxRetry(), timeoutMillis,
                        task.hasTaskId() ? task.taskId() : null, ID_PREFIX,
                        taskIdTtlSeconds(task)));
        return outcome == RedisRetryingTaskQueue.SubmitOutcome.ENQUEUED;
    }

    /** 供测试/关停使用：停止调度并释放资源。 */
    public void shutdown() {
        queue.shutdown();
    }

    @Override
    public void stop() {
        shutdown();
    }

    private void run(String body, boolean finalAttempt) {
        WikiIngestTask task = parseTask(body);
        if (task == null) {
            return; // 损坏载荷：基座按成功收尾（重试无意义）
        }
        Throwable failure = runner.dispatchOnce(task);
        if (failure != null) {
            throw new RedisRetryingTaskQueue.TaskFailure(failure);
        }
    }

    /** wiki 特例：锁冲突固定 15 秒；耗尽后死信归档 + 释放 slot。 */
    private final class WikiFailurePolicy implements RedisRetryingTaskQueue.FailurePolicy {

        @Override
        public Long retryDelaySeconds(int attempt, Throwable failure) {
            return WikiIngestTaskRunner.retryDelaySeconds(attempt, unwrap(failure));
        }

        @Override
        public void onDeadLetter(String body, int attempt, Throwable failure) {
            WikiIngestTask task = parseTask(body);
            if (task != null) {
                runner.archive(task, unwrap(failure), attempt);
            }
        }
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof RedisRetryingTaskQueue.TaskFailure && failure.getCause() != null
                ? failure.getCause() : failure;
    }

    private static WikiIngestTask parseTask(String body) {
        try {
            return fromJson(body).toTask();
        } catch (RuntimeException e) {
            log.warn("wiki task queue: dropping corrupt task member", e);
            return null;
        }
    }

    /**
     * TaskID 去重键的 TTL：终态会主动删键；TTL 是实例崩溃时的兜底释放——
     * 否则该 TaskID 的合并会被一个幽灵任务永久占住。
     * 取"单次超时 + 30 分钟"且不低于 1 小时。
     */
    static long taskIdTtlSeconds(WikiIngestTask task) {
        long timeoutSeconds = task.timeout() == null ? 0 : task.timeout().toSeconds();
        return Math.max(3600, timeoutSeconds + 1800);
    }

    /** 供测试手工构造"执行中"成员（模拟另一实例取走后崩溃）。 */
    static String memberOf(WikiIngestTask task, int attempt) {
        return toJson(new RedisRetryingTaskQueue.QueueMember(
                toJson(QueuedTask.of(task, attempt)), attempt, task.maxRetry(),
                task.timeout() == null ? null : task.timeout().toMillis(),
                task.hasTaskId() ? task.taskId() : null, ID_PREFIX));
    }

    private static String toJson(QueuedTask queued) {
        try {
            return MAPPER.writeValueAsString(queued);
        } catch (Exception e) {
            throw new IllegalArgumentException("wiki task queue: marshal task: " + e.getMessage(), e);
        }
    }

    private static String toJson(RedisRetryingTaskQueue.QueueMember member) {
        try {
            return MAPPER.writeValueAsString(member);
        } catch (Exception e) {
            throw new IllegalArgumentException("wiki task queue: marshal member: " + e.getMessage(), e);
        }
    }

    private static QueuedTask fromJson(String member) {
        try {
            return MAPPER.readValue(member, QueuedTask.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("wiki task queue: unmarshal member: " + e.getMessage(), e);
        }
    }

    /**
     * 任务体（队列成员的 {@code body}）：任务本体扁平化为原始字段
     * （避免 JSON 时间类型模块依赖，键名与字节形变受控）+ 当前尝试次数。
     */
    record QueuedTask(String type, String payload, long processInMillis, int maxRetry,
                      long timeoutMillis, String taskId, int attempt) {

        static QueuedTask of(WikiIngestTask task, int attempt) {
            return new QueuedTask(task.type(), task.payload(),
                    task.processIn() == null ? 0 : task.processIn().toMillis(),
                    task.maxRetry(),
                    task.timeout() == null ? 0 : task.timeout().toMillis(),
                    task.taskId(), attempt);
        }

        WikiIngestTask toTask() {
            return new WikiIngestTask(type, payload, Duration.ofMillis(processInMillis),
                    maxRetry, Duration.ofMillis(timeoutMillis), taskId);
        }
    }
}
