package com.ragagent.knowledge.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.knowledge.domain.ExtractChunkPayload;
import com.ragagent.knowledge.domain.QuestionBatchPayload;
import com.ragagent.knowledge.service.chunk.ChunkExtractService;
import com.ragagent.knowledge.service.enrichment.QuestionGenerationService;
import com.ragagent.platform.stream.EmbeddedRedis;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * knowledge 两个 Redis 队列的语义：执行与终态清理、重试到预算耗尽后放弃、
 * 问题生成带 finalAttempt 标志、共享任务表（另一实例可执行本实例提交的任务）、
 * 崩溃租约回收重投。
 *
 * <p>没有 redis-server（且未配 {@code REDIS_TEST_ADDR}）时整体跳过——见 {@link EmbeddedRedis}。</p>
 */
class RedisKnowledgeTaskQueueTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EmbeddedRedis redis;
    private RedisChunkExtractTaskQueue chunkQueue;
    private RedisQuestionGenerationTaskQueue questionQueue;

    @BeforeEach
    void setUp() {
        redis = EmbeddedRedis.tryStart();
        assumeTrue(redis != null, "redis-server 不可用，跳过");
        redis.flushAll();
    }

    @AfterEach
    void tearDown() {
        if (chunkQueue != null) {
            chunkQueue.shutdown();
        }
        if (questionQueue != null) {
            questionQueue.shutdown();
        }
        if (redis != null) {
            redis.close();
        }
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

    private static ExtractChunkPayload chunkPayload() {
        return new ExtractChunkPayload(1L, "chunk-1", "model-1", "kb-1", 0, 0);
    }

    private static QuestionBatchPayload questionPayload() {
        return new QuestionBatchPayload(1L, "kb-1", "kb-1", 3, "zh",
                0, List.of("chunk-1"), 0, "", "");
    }

    @Test
    void chunkTaskRunsAndCompletes() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ChunkExtractService service = mock(ChunkExtractService.class);
        doAnswer(inv -> {
            calls.incrementAndGet();
            return null;
        }).when(service).handleJson(anyString(), anyBoolean());

        chunkQueue = new RedisChunkExtractTaskQueue(redis.template(), service);
        chunkQueue.enqueue(chunkPayload());
        await(() -> calls.get() == 1);
        // 终态清理：ready / proc 都空
        await(() -> redis.template().opsForZSet()
                .size("knowledge:task:chunk:ready") == 0);
        assertThat(redis.template().opsForZSet().size("knowledge:task:chunk:proc")).isZero();
    }

    @Test
    void chunkTaskRetriesThenGivesUpAfterMaxAttempts() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ChunkExtractService service = mock(ChunkExtractService.class);
        doAnswer(inv -> {
            calls.incrementAndGet();
            throw new RuntimeException("boom");
        }).when(service).handleJson(anyString(), anyBoolean());

        chunkQueue = new RedisChunkExtractTaskQueue(redis.template(), service);
        chunkQueue.setRetryDelayOverrideSeconds(0L);
        chunkQueue.enqueue(chunkPayload());
        // MAX_RETRY=3 → 共 4 次尝试后放弃
        await(() -> calls.get() == 4);
        Thread.sleep(600);
        assertThat(calls.get()).as("预算耗尽后不得再尝试").isEqualTo(4);
        await(() -> redis.template().opsForZSet()
                .size("knowledge:task:chunk:proc") == 0);
    }

    @Test
    void chunkTaskPassesFinalAttemptFlag() throws Exception {
        List<Boolean> finalFlags = new ArrayList<>();
        ChunkExtractService service = mock(ChunkExtractService.class);
        doAnswer(inv -> {
            synchronized (finalFlags) {
                finalFlags.add(inv.getArgument(1));
            }
            throw new RuntimeException("boom");
        }).when(service).handleJson(anyString(), anyBoolean());

        chunkQueue = new RedisChunkExtractTaskQueue(redis.template(), service);
        chunkQueue.setRetryDelayOverrideSeconds(0L);
        chunkQueue.enqueue(chunkPayload());
        await(() -> {
            synchronized (finalFlags) {
                return finalFlags.size() == 4;
            }
        });
        Thread.sleep(600);
        synchronized (finalFlags) {
            assertThat(finalFlags)
                    .as("K5：前 3 次可重试（terminal=false），第 4 次终态（terminal=true）")
                    .containsExactly(false, false, false, true);
        }
    }

    @Test
    void questionTaskPassesFinalAttemptFlag() throws Exception {
        List<Boolean> finalFlags = new ArrayList<>();
        QuestionGenerationService service = mock(QuestionGenerationService.class);
        doAnswer(inv -> {
            synchronized (finalFlags) {
                finalFlags.add(inv.getArgument(1));
            }
            throw new RuntimeException("boom");
        }).when(service).handleJson(anyString(), anyBoolean());

        questionQueue = new RedisQuestionGenerationTaskQueue(redis.template(), service);
        questionQueue.setRetryDelayOverrideSeconds(0L);
        questionQueue.enqueue(questionPayload());
        await(() -> {
            synchronized (finalFlags) {
                return finalFlags.size() == 4;
            }
        });
        synchronized (finalFlags) {
            assertThat(finalFlags).as("第 4 次（最后一次）尝试必须带 finalAttempt=true")
                    .containsExactly(false, false, false, true);
        }
    }

    @Test
    void otherInstanceExecutesSubmittedTask() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ChunkExtractService service = mock(ChunkExtractService.class);
        doAnswer(inv -> {
            calls.incrementAndGet();
            return null;
        }).when(service).handleJson(anyString(), anyBoolean());

        RedisChunkExtractTaskQueue other = new RedisChunkExtractTaskQueue(redis.template(), service);
        chunkQueue = new RedisChunkExtractTaskQueue(redis.template(), service);
        chunkQueue.shutdown();   // 本实例不参与调度：任务只能由 other 执行
        try {
            chunkQueue.enqueue(chunkPayload());
            await(() -> calls.get() == 1);
        } finally {
            other.shutdown();
        }
    }

    @Test
    void staleProcessingIsReclaimedAndRerun() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ChunkExtractService service = mock(ChunkExtractService.class);
        doAnswer(inv -> {
            calls.incrementAndGet();
            return null;
        }).when(service).handleJson(anyString(), anyBoolean());

        chunkQueue = new RedisChunkExtractTaskQueue(redis.template(), service);
        // 手工模拟"另一个实例取走后崩溃"：proc 里一个租约已过期的成员
        String member = MAPPER.writeValueAsString(
                new AbstractRedisKnowledgeTaskQueue.QueueMember(chunkPayload().toJson(), 1));
        redis.template().opsForZSet().add("knowledge:task:chunk:proc", member,
                System.currentTimeMillis() - 1000);

        await(() -> calls.get() == 1);
        await(() -> redis.template().opsForZSet()
                .size("knowledge:task:chunk:proc") == 0);
    }
}
