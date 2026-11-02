package com.ragagent.platform.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.platform.web.JsonMappers;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 重试队列：把 {@link RedisTaskQueueCore} 的租约/续期/重投与
 * {@link RetryPolicy} 的重试预算拼成一个可复用的执行循环。
 *
 * <p>成员 JSON：{@code {"body","attempt","maxRetry","timeoutMillis","idempotencyKey","idempotencyPrefix"}}。
 * 后四个可缺省：缺省时 {@code maxRetry} 回落到 policy、无超时、无幂等键。
 * 多实例语义由 core 保证（至少一次）。</p>
 *
 * <p>幂等键（可选）：提交前 {@code SETNX} 占位，TTL 覆盖全部重试的总时长上界；
 * 终态（成功/放弃/载荷坏）释放。重复提交返回 {@link SubmitOutcome#DUPLICATE}，
 * 不抛异常——调用方据此区分"已在跑"与"投递失败"。</p>
 */
public final class RedisRetryingTaskQueue implements TaskQueue {

    private static final Logger log = LoggerFactory.getLogger(RedisRetryingTaskQueue.class);
    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    @FunctionalInterface
    public interface Handler {
        void handle(String body, boolean finalAttempt);
    }

    /** 把 handler 抛出的任意失败归一为运行时异常，供 {@link FailurePolicy} 解包原始原因。 */
    public static class TaskFailure extends RuntimeException {
        public TaskFailure(Throwable cause) {
            super(cause == null ? "task failed" : cause.toString(), cause);
        }
    }

    /** 失败策略：重试延迟与终态死信回调；{@code null} = policy 公式 + 无死信。 */
    public interface FailurePolicy {
        /** 返回 {@code null} = 用 policy 默认退避；返回 ≥0 = 指定秒数（仍受测试覆盖值钳制）。 */
        Long retryDelaySeconds(int attempt, Throwable failure);

        /** 重试耗尽（终态失败）；默认无操作。 */
        default void onDeadLetter(String body, int attempt, Throwable failure) {
        }
    }

    /** 每次提交的参数；缺省字段走队列自身的 policy/无超时/无幂等。 */
    public record TaskSpec(Integer maxRetry, Long timeoutMillis, String idempotencyKey,
                           String idempotencyPrefix, Long idempotencyTtlSeconds) {

        public static TaskSpec of(Integer maxRetry) {
            return new TaskSpec(maxRetry, null, null, null, null);
        }

        public static TaskSpec idempotent(Integer maxRetry, Long timeoutMillis, String key,
                                          String prefix, Long ttlSeconds) {
            return new TaskSpec(maxRetry, timeoutMillis, key, prefix, ttlSeconds);
        }
    }

    /** 提交结果。 */
    public enum SubmitOutcome { ENQUEUED, DUPLICATE }

    private final RedisTaskQueueCore core;
    private final StringRedisTemplate template;
    private final String taskName;
    private final RetryPolicy policy;
    private final Handler handler;
    private final FailurePolicy failurePolicy;
    private final ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService scheduler;

    /** 测试钩子：覆盖重试延迟（秒）；{@code null} = 用 policy 公式。 */
    private final RetryDelayOverride retryDelay = new RetryDelayOverride();

    public RedisRetryingTaskQueue(StringRedisTemplate template, String keyPrefix, String taskName,
            RetryPolicy policy, Handler handler) {
        this(template, keyPrefix, taskName, policy, handler, null);
    }

    public RedisRetryingTaskQueue(StringRedisTemplate template, String keyPrefix, String taskName,
            RetryPolicy policy, Handler handler, FailurePolicy failurePolicy) {
        this.core = new RedisTaskQueueCore(template, keyPrefix);
        this.template = template;
        this.taskName = taskName;
        this.policy = policy;
        this.handler = handler;
        this.failurePolicy = failurePolicy;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, taskName.replace(' ', '-') + "-renew");
            thread.setDaemon(true);
            return thread;
        });
        this.core.start(this::accept);
    }

    @Override
    public String name() {
        return taskName;
    }

    public void submit(String body) {
        submit(body, 0L);
    }

    public void submit(String body, long delayMillis) {
        submit(body, delayMillis, TaskSpec.of(null));
    }

    public SubmitOutcome submit(String body, long delayMillis, TaskSpec spec) {
        TaskSpec effective = spec == null ? TaskSpec.of(null) : spec;
        if (effective.idempotencyKey() != null && !effective.idempotencyKey().isEmpty()) {
            Boolean reserved;
            try {
                reserved = template.opsForValue().setIfAbsent(
                        effective.idempotencyPrefix() + effective.idempotencyKey(), "1",
                        Duration.ofSeconds(effective.idempotencyTtlSeconds() != null
                                ? effective.idempotencyTtlSeconds()
                                : idempotencyTtlSeconds(
                                        effective.maxRetry(), effective.timeoutMillis())));
            } catch (RuntimeException e) {
                throw new IllegalStateException("task queue reserve failed: " + e.getMessage(), e);
            }
            if (!Boolean.TRUE.equals(reserved)) {
                return SubmitOutcome.DUPLICATE;
            }
        }
        long readyAt = System.currentTimeMillis() + Math.max(delayMillis, 0L);
        try {
            core.submit(toJson(new QueueMember(body, 1, effective.maxRetry(),
                    effective.timeoutMillis(), effective.idempotencyKey(),
                    effective.idempotencyPrefix())), readyAt);
        } catch (RuntimeException e) {
            releaseIdempotency(effective.idempotencyPrefix(), effective.idempotencyKey());
            throw e;
        }
        return SubmitOutcome.ENQUEUED;
    }

    /** 供测试覆盖重试延迟（秒）。 */
    public void setRetryDelayOverrideSeconds(Long seconds) {
        retryDelay.set(seconds);
    }

    /** 停掉调度、执行与续期资源（幂等）。 */
    public void shutdown() {
        core.shutdown();
        scheduler.shutdownNow();
        worker.shutdownNow();
    }

    @Override
    public void stop() {
        shutdown();
    }

    private void accept(String member) {
        QueueMember queued;
        try {
            queued = MAPPER.readValue(member, QueueMember.class);
        } catch (Exception e) {
            log.warn("{} task: dropping corrupt member", taskName, e);
            core.complete(member);
            return;
        }
        worker.execute(() -> run(member, queued));
    }

    private void run(String member, QueueMember queued) {
        ScheduledFuture<?> renew = scheduler.scheduleAtFixedRate(
                () -> core.renew(member),
                core.renewIntervalMillis(),
                core.renewIntervalMillis(),
                TimeUnit.MILLISECONDS);
        RuntimeException failure = null;
        boolean interrupted = false;
        try {
            if (queued.timeoutMillis() != null && queued.timeoutMillis() > 0) {
                Future<?> future = worker.submit(
                        () -> handler.handle(queued.body(), isFinalAttempt(queued)));
                try {
                    future.get(queued.timeoutMillis(), TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    future.cancel(true);
                    failure = new IllegalStateException(
                            "task timed out after " + queued.timeoutMillis() + "ms");
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    failure = cause instanceof RuntimeException re
                            ? re : new IllegalStateException(cause);
                } catch (InterruptedException e) {
                    future.cancel(true);
                    Thread.currentThread().interrupt();
                    interrupted = true;
                }
            } else {
                handler.handle(queued.body(), isFinalAttempt(queued));
            }
        } catch (RuntimeException e) {
            failure = e;
        } finally {
            renew.cancel(false);
        }

        if (interrupted) {
            return; // 留在 proc，等租约回收（进程下线语义）
        }
        if (failure == null) {
            complete(member, queued);
            return;
        }
        if (isFinalAttempt(queued)) {
            log.warn("{} task gave up after {} attempts", taskName, queued.attempt(), failure);
            if (failurePolicy != null) {
                try {
                    failurePolicy.onDeadLetter(queued.body(), queued.attempt(), failure);
                } catch (RuntimeException e) {
                    log.warn("{} task: dead-letter callback failed", taskName, e);
                }
            }
            complete(member, queued);
            return;
        }
        long delaySeconds = delaySeconds(queued.attempt(), failure);
        log.warn("{} task failed (attempt {}/{}), retrying in {}s",
                taskName, queued.attempt(), maxRetry(queued) + 1, delaySeconds, failure);
        core.requeue(member, toJson(new QueueMember(queued.body(), queued.attempt() + 1,
                        queued.maxRetry(), queued.timeoutMillis(), queued.idempotencyKey(),
                        queued.idempotencyPrefix())),
                System.currentTimeMillis() + RedisTaskQueueCore.secondsToMillis(delaySeconds));
    }

    private void complete(String member, QueueMember queued) {
        core.complete(member);
        releaseIdempotency(queued.idempotencyPrefix(), queued.idempotencyKey());
    }

    private boolean isFinalAttempt(QueueMember queued) {
        return queued.attempt() > maxRetry(queued);
    }

    private int maxRetry(QueueMember queued) {
        return queued.maxRetry() != null ? queued.maxRetry() : policy.maxRetry();
    }

    private long delaySeconds(int attempt, Throwable failure) {
        Long custom = failurePolicy == null
                ? null : failurePolicy.retryDelaySeconds(attempt, failure);
        long fallback = custom != null ? Math.max(custom, 0) : policy.delaySeconds(attempt);
        return retryDelay.resolve(fallback);
    }

    private void releaseIdempotency(String prefix, String key) {
        if (prefix == null || key == null || key.isEmpty()) {
            return;
        }
        try {
            template.delete(prefix + key);
        } catch (RuntimeException e) {
            log.warn("{} task: release idempotency key failed (TTL will reap)", taskName, e);
        }
    }

    /**
     * 幂等键 TTL：终态会主动删键；TTL 是实例崩溃时的兜底释放。
     * 覆盖全部重试的总时长上界：{@code (maxRetry+1) * 单次超时 + 30 分钟}，下限 1 小时。
     */
    public static long idempotencyTtlSeconds(Integer maxRetry, Long timeoutMillis) {
        long timeoutSeconds = timeoutMillis == null ? 0 : Math.max(0, timeoutMillis / 1000);
        int retries = maxRetry == null ? 0 : Math.max(0, maxRetry);
        return Math.max(3600, (retries + 1) * timeoutSeconds + 1800);
    }

    private static String toJson(QueueMember member) {
        try {
            return MAPPER.writeValueAsString(member);
        } catch (Exception e) {
            throw new IllegalArgumentException("task queue: marshal: " + e.getMessage(), e);
        }
    }

    /** 队列成员：任务体 + 当前尝试次数 + 提交参数（缺省字段为 null）。 */
    public record QueueMember(String body, int attempt, Integer maxRetry, Long timeoutMillis,
                              String idempotencyKey, String idempotencyPrefix) {
    }
}
