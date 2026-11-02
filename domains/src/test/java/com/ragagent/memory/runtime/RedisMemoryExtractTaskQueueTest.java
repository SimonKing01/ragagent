package com.ragagent.memory.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.memory.domain.MemoryExtractHandler;
import com.ragagent.memory.domain.MemoryExtractPayload;
import com.ragagent.memory.service.MemoryExtractionService;
import com.ragagent.platform.stream.EmbeddedRedis;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@link RedisMemoryExtractTaskQueue} 的真 Redis 语义：执行与终态清理、
 * 延迟投递、重试到预算耗尽后放弃、崩溃租约回收重投、投递失败上抛。
 *
 * <p>没有 redis-server（且未配 {@code REDIS_TEST_ADDR}）时整体跳过——见 {@link EmbeddedRedis}。</p>
 */
class RedisMemoryExtractTaskQueueTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EmbeddedRedis redis;
    private RedisMemoryExtractTaskQueue queue;

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
    private static ObjectProvider<MemoryExtractHandler> handlerProvider(
            MemoryExtractionService handler) {
        ObjectProvider<MemoryExtractHandler> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(handler);
        return p;
    }

    private RedisMemoryExtractTaskQueue newQueue(MemoryExtractionService handler) {
        return new RedisMemoryExtractTaskQueue(redis.template(), handlerProvider(handler));
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
    void runsTaskAndCompletes() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        MemoryExtractionService handler = mock(MemoryExtractionService.class);
        doAnswer(inv -> {
            calls.incrementAndGet();
            return null;
        }).when(handler).handle(any());

        queue = newQueue(handler);
        queue.enqueue(MemoryExtractPayload.empty(), null);
        await(() -> calls.get() == 1);
        await(() -> redis.template().opsForZSet().size("memory:task:extract:ready") == 0);
        assertThat(redis.template().opsForZSet().size("memory:task:extract:proc")).isZero();
    }

    @Test
    void delayedTaskWaitsForItsTime() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        MemoryExtractionService handler = mock(MemoryExtractionService.class);
        doAnswer(inv -> {
            calls.incrementAndGet();
            return null;
        }).when(handler).handle(any());

        queue = newQueue(handler);
        queue.enqueue(MemoryExtractPayload.empty(), Duration.ofSeconds(2));
        Thread.sleep(800);   // 未到期：不得执行
        assertThat(calls.get()).isZero();
        await(() -> calls.get() == 1);
    }

    @Test
    void retriesThenGivesUpAfterMaxAttempts() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        MemoryExtractionService handler = mock(MemoryExtractionService.class);
        doAnswer(inv -> {
            calls.incrementAndGet();
            throw new RuntimeException("boom");
        }).when(handler).handle(any());

        queue = newQueue(handler);
        queue.setRetryDelayOverrideSeconds(0L);
        queue.enqueue(MemoryExtractPayload.empty(), null);
        // MAX_RETRY=2 → 共 3 次尝试后放弃
        await(() -> calls.get() == 3);
        Thread.sleep(600);
        assertThat(calls.get()).as("预算耗尽后不得再尝试").isEqualTo(3);
        await(() -> redis.template().opsForZSet().size("memory:task:extract:proc") == 0);
    }

    @Test
    void staleProcessingIsReclaimedAndRerun() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        MemoryExtractionService handler = mock(MemoryExtractionService.class);
        doAnswer(inv -> {
            calls.incrementAndGet();
            return null;
        }).when(handler).handle(any());

        queue = newQueue(handler);
        // 手工模拟"另一个实例取走后崩溃"：proc 里一个租约已过期的成员
        String member = MAPPER.writeValueAsString(new RedisMemoryExtractTaskQueue.QueueMember(
                MemoryExtractPayload.empty().toJson(), 1));
        redis.template().opsForZSet().add("memory:task:extract:proc", member,
                System.currentTimeMillis() - 1000);

        await(() -> calls.get() == 1);
        await(() -> redis.template().opsForZSet().size("memory:task:extract:proc") == 0);
    }

    @Test
    void submitFailurePropagates() {
        StringRedisTemplate broken = mock(StringRedisTemplate.class);
        when(broken.opsForZSet()).thenThrow(new RuntimeException("boom"));
        MemoryExtractionService handler = mock(MemoryExtractionService.class);
        RedisMemoryExtractTaskQueue brokenQueue =
                new RedisMemoryExtractTaskQueue(broken, handlerProvider(handler));
        try {
            assertThatThrownBy(() -> brokenQueue.enqueue(MemoryExtractPayload.empty(), null))
                    .as("投递失败必须上抛，让调用方释放在途槽位")
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            brokenQueue.shutdown();
        }
    }
}
