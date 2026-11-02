package com.ragagent.datasource.task;


import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.datasource.domain.DataSourceSyncHandler;
import com.ragagent.datasource.domain.DataSourceSyncPayload;
import com.ragagent.datasource.domain.DataSourceSyncTaskQueue;
import com.ragagent.datasource.domain.TaskInitiator;
import com.ragagent.platform.stream.EmbeddedRedis;
import com.ragagent.platform.task.RedisRetryingTaskQueue;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@link RedisDataSourceSyncTaskQueue} 的真 Redis 语义：执行与 TaskID 释放、
 * 跨实例 TaskID 去重（多副本同分钟触发的第二层闸门）、重试到预算耗尽后放弃、
 * 超时中断、崩溃租约回收重投。
 *
 * <p>没有 redis-server（且未配 {@code REDIS_TEST_ADDR}）时整体跳过——见 {@link EmbeddedRedis}。</p>
 */
class RedisDataSourceSyncTaskQueueTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EmbeddedRedis redis;
    private RedisDataSourceSyncTaskQueue queue;

    @BeforeEach
    void setUp() {
        redis = EmbeddedRedis.tryStart();
        assumeTrue(redis != null, "redis-server 不可用，跳过");
        redis.flushAll();
    }

    @AfterEach
    void tearDown() {
        if (queue != null) {
            queue.shutdown();
        }
        if (redis != null) {
            redis.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<DataSourceSyncHandler> handlerProvider(DataSourceSyncHandler h) {
        ObjectProvider<DataSourceSyncHandler> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(h);
        return p;
    }

    private RedisDataSourceSyncTaskQueue newQueue(DataSourceSyncHandler handler) {
        return new RedisDataSourceSyncTaskQueue(redis.template(), handlerProvider(handler));
    }

    private static DataSourceSyncPayload syncPayload() {
        return new DataSourceSyncPayload(TaskInitiator.empty(), "manual", "ds-1", 1L,
                "log-1", false, 0);
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("condition not met within 10s");
    }

    @Test
    void enqueueRunsAndReleasesTaskId() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        DataSourceSyncHandler handler = mock(DataSourceSyncHandler.class);
        doAnswer(inv -> {
            calls.incrementAndGet();
            return null;
        }).when(handler).handle(any());

        queue = newQueue(handler);
        String taskId = "dssync:ds-1:202610061200";
        assertThat(queue.enqueue(syncPayload(), taskId, 0, Duration.ofMinutes(1)))
                .isEqualTo(DataSourceSyncTaskQueue.Outcome.ENQUEUED);
        await(() -> calls.get() == 1);
        await(() -> redis.template().opsForZSet().size("datasource:sync:proc") == 0);
        // 终态释放 TaskID：同 id 可再次入队
        await(() -> queue.enqueue(syncPayload(), taskId, 0, Duration.ofMinutes(1))
                == DataSourceSyncTaskQueue.Outcome.ENQUEUED);
    }

    @Test
    void taskIdConflictAcrossInstances() {
        DataSourceSyncHandler handler = mock(DataSourceSyncHandler.class);
        queue = newQueue(handler);
        RedisDataSourceSyncTaskQueue other = newQueue(handler);
        try {
            assertThat(queue.enqueue(syncPayload(), "dssync:ds-1:202610061200", 0, null))
                    .isEqualTo(DataSourceSyncTaskQueue.Outcome.ENQUEUED);
            assertThat(other.enqueue(syncPayload(), "dssync:ds-1:202610061200", 0, null))
                    .as("跨实例 TaskID 去重（cron 同分钟一起触发时只有第一个赢）")
                    .isEqualTo(DataSourceSyncTaskQueue.Outcome.TASK_ID_CONFLICT);
        } finally {
            other.shutdown();
        }
    }

    @Test
    void retriesThenGivesUpAndReleasesTaskId() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        DataSourceSyncHandler handler = mock(DataSourceSyncHandler.class);
        doAnswer(inv -> {
            calls.incrementAndGet();
            throw new RuntimeException("boom");
        }).when(handler).handle(any());

        queue = newQueue(handler);
        queue.setRetryDelayOverrideSeconds(0L);
        String taskId = "dssync:ds-1:202610061201";
        queue.enqueue(syncPayload(), taskId, 1, Duration.ofMinutes(1));
        // maxRetry=1 → 共 2 次尝试后放弃
        await(() -> calls.get() == 2);
        Thread.sleep(600);
        assertThat(calls.get()).as("预算耗尽后不得再尝试").isEqualTo(2);
        await(() -> redis.template().opsForZSet().size("datasource:sync:proc") == 0);
        // 放弃即终态：释放 TaskID
        await(() -> queue.enqueue(syncPayload(), taskId, 1, Duration.ofMinutes(1))
                == DataSourceSyncTaskQueue.Outcome.ENQUEUED);
    }

    @Test
    void timeoutInterruptsAttempt() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean interrupted = new AtomicBoolean();
        DataSourceSyncHandler handler = mock(DataSourceSyncHandler.class);
        doAnswer(inv -> {
            calls.incrementAndGet();
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            return null;
        }).when(handler).handle(any());

        queue = newQueue(handler);
        queue.enqueue(syncPayload(), "dssync:ds-1:202610061202", 0, Duration.ofMillis(300));
        await(() -> interrupted.get());   // 超时看门狗必须中断执行线程
        await(() -> redis.template().opsForZSet().size("datasource:sync:proc") == 0);
    }

    @Test
    void staleProcessingIsReclaimedAndRerun() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        DataSourceSyncHandler handler = mock(DataSourceSyncHandler.class);
        doAnswer(inv -> {
            calls.incrementAndGet();
            return null;
        }).when(handler).handle(any());

        queue = newQueue(handler);
        // 手工模拟"另一个实例取走后崩溃"：proc 里一个租约已过期的成员
        String member = MAPPER.writeValueAsString(new RedisRetryingTaskQueue.QueueMember(
                syncPayload().toJson(), 1, 0, 60_000L, null, null));
        redis.template().opsForZSet().add("datasource:sync:proc", member,
                System.currentTimeMillis() - 1000);

        await(() -> calls.get() == 1);
        await(() -> redis.template().opsForZSet().size("datasource:sync:proc") == 0);
    }
}
