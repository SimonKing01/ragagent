package com.ragagent.wiki.repository.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.platform.stream.EmbeddedRedis;
import com.ragagent.wiki.domain.TaskDeadLetter;
import com.ragagent.wiki.domain.ingest.WikiIngestPayload;
import com.ragagent.wiki.domain.ingest.WikiIngestSlotReleaser;
import com.ragagent.wiki.domain.ingest.WikiIngestTask;
import com.ragagent.wiki.repository.TaskDeadLetterRepository;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@link RedisWikiIngestTaskQueue} 的真 Redis 语义：执行与 TaskID 释放、
 * 跨实例 TaskID 合并、延迟投递、失败重试、重试耗尽死信、崩溃租约回收。
 *
 * <p>没有 redis-server（且未配 {@code REDIS_TEST_ADDR}）时整体跳过——见 {@link EmbeddedRedis}。</p>
 */
class RedisWikiIngestTaskQueueTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EmbeddedRedis redis;
    private RedisWikiIngestTaskQueue queue;

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
    private static ObjectProvider<WikiIngestTaskHandler> handlerProvider(WikiIngestTaskHandler h) {
        ObjectProvider<WikiIngestTaskHandler> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(h);
        return p;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<TaskDeadLetterRepository> deadLetterProvider(
            TaskDeadLetterRepository r) {
        ObjectProvider<TaskDeadLetterRepository> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(r);
        return p;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<WikiIngestSlotReleaser> ingestServiceProvider() {
        ObjectProvider<WikiIngestSlotReleaser> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(null);
        return p;
    }

    private RedisWikiIngestTaskQueue newQueue(WikiIngestTaskHandler handler,
            TaskDeadLetterRepository deadLetters) {
        return new RedisWikiIngestTaskQueue(redis.template(),
                handlerProvider(handler), deadLetterProvider(deadLetters), ingestServiceProvider());
    }

    private static String payloadJson() throws Exception {
        return MAPPER.writeValueAsString(WikiIngestPayload.of("kb-1"));
    }

    private static WikiIngestTask ingestTask(String taskId, Duration processIn, int maxRetry)
            throws Exception {
        return new WikiIngestTask(WikiIngestTask.TYPE_WIKI_INGEST, payloadJson(),
                processIn, maxRetry, Duration.ofSeconds(60), taskId);
    }

    /** 轮询等待条件成立（dispatcher 以 500ms 轮询 + 执行是异步的）。 */
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
    void runsTaskAndReleasesTaskIdOnSuccess() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        WikiIngestTaskHandler handler = mock(WikiIngestTaskHandler.class);
        doAnswer(inv -> {
            calls.incrementAndGet();
            return null;
        }).when(handler).processWikiIngest(any());

        queue = newQueue(handler, null);
        assertThat(queue.enqueue(ingestTask("t-1", Duration.ZERO, 0))).isTrue();
        await(() -> calls.get() == 1);
        // 终态清理：TaskID 释放 → 同 id 可再次入队
        await(() -> !redis.template().hasKey(RedisWikiIngestTaskQueue.ID_PREFIX + "t-1"));
        assertThat(queue.enqueue(ingestTask("t-1", Duration.ZERO, 0))).isTrue();
        await(() -> calls.get() == 2);
    }

    @Test
    void taskIdCoalescesAcrossInstances() throws Exception {
        WikiIngestTaskHandler handler = mock(WikiIngestTaskHandler.class);
        queue = newQueue(handler, null);
        RedisWikiIngestTaskQueue other = newQueue(handler, null);
        try {
            assertThat(queue.enqueue(ingestTask("t-9", Duration.ofSeconds(30), 0))).isTrue();
            assertThat(other.enqueue(ingestTask("t-9", Duration.ofSeconds(30), 0)))
                    .as("跨实例 TaskID 合并（进程内实现做不到）").isFalse();
        } finally {
            other.shutdown();
        }
    }

    @Test
    void delayedTaskWaitsForItsTime() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        WikiIngestTaskHandler handler = mock(WikiIngestTaskHandler.class);
        doAnswer(inv -> {
            calls.incrementAndGet();
            return null;
        }).when(handler).processWikiIngest(any());

        queue = newQueue(handler, null);
        queue.enqueue(ingestTask("t-2", Duration.ofSeconds(2), 0));
        Thread.sleep(800);   // 未到期：不得执行
        assertThat(calls.get()).isZero();
        await(() -> calls.get() == 1);   // 到期后执行
    }

    @Test
    void failedTaskIsRetriedWithIncrementedAttempt() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        WikiIngestTaskHandler handler = mock(WikiIngestTaskHandler.class);
        doAnswer(inv -> {
            if (calls.incrementAndGet() == 1) {
                throw new RuntimeException("boom");
            }
            return null;
        }).when(handler).processWikiIngest(any());

        queue = newQueue(handler, null);
        queue.setRetryDelayOverrideSeconds(0L);
        queue.enqueue(ingestTask("t-3", Duration.ZERO, 2));
        await(() -> calls.get() == 2);   // 第一次失败 → 立即重试 → 成功
    }

    @Test
    void exhaustedRetriesArchiveToDeadLetters() throws Exception {
        WikiIngestTaskHandler handler = mock(WikiIngestTaskHandler.class);
        doThrow(new RuntimeException("boom")).when(handler).processWikiIngest(any());
        TaskDeadLetterRepository repo = mock(TaskDeadLetterRepository.class);
        AtomicReference<TaskDeadLetter> archived = new AtomicReference<>();
        doAnswer(inv -> {
            archived.set(inv.getArgument(0));
            return null;
        }).when(repo).insert(any());

        queue = newQueue(handler, repo);
        queue.setRetryDelayOverrideSeconds(0L);
        queue.enqueue(ingestTask("t-4", Duration.ZERO, 1));
        await(() -> archived.get() != null);
        assertThat(archived.get().getTaskType()).isEqualTo(WikiIngestTask.TYPE_WIKI_INGEST);
        assertThat(archived.get().getScopeId()).isEqualTo("kb-1");
        assertThat(archived.get().getFailCount()).as("maxRetry=1 → 共两次尝试").isEqualTo(2);
    }

    @Test
    void staleProcessingIsReclaimedAndRerun() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        WikiIngestTaskHandler handler = mock(WikiIngestTaskHandler.class);
        doAnswer(inv -> {
            calls.incrementAndGet();
            return null;
        }).when(handler).processWikiIngest(any());

        queue = newQueue(handler, null);
        // 手工模拟"另一个实例取走后崩溃"：proc 里一个租约已过期的成员
        String member = RedisWikiIngestTaskQueue.memberOf(
                ingestTask("t-5", Duration.ZERO, 0), 1);
        redis.template().opsForZSet().add(RedisWikiIngestTaskQueue.KEY_PROC, member,
                System.currentTimeMillis() - 1000);

        await(() -> calls.get() == 1);   // 回收 → 重投 → 执行
        await(() -> redis.template().opsForZSet().size(RedisWikiIngestTaskQueue.KEY_PROC) == 0);
    }
}
