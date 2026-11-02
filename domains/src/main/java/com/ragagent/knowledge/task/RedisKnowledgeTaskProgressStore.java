package com.ragagent.knowledge.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.knowledge.dto.doc.KnowledgeMoveProgress;
import com.ragagent.knowledge.dto.kb.KBCloneProgress;
import com.ragagent.knowledge.support.KnowledgeTaskProgressStore;
import com.ragagent.platform.web.JsonMappers;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@link KnowledgeTaskProgressStore} 的 <b>Redis</b> 实现（跨实例进度可见）。
 *
 * <p>{@code knowledge.redis-enabled=true} 时由 {@code KnowledgeRedisWiring}
 * 装配（{@code @Primary} 覆盖 {@link InProcessKnowledgeTaskProgressStore}）。
 * 键级 TTL（24 小时，与进程内实现读路径的 TTL 同值）= 内存版的"惰性过期"语义；
 * {@code save*Initial} 用 {@code SET ... NX} 精确对应 {@code putIfAbsent}。</p>
 *
 * <p><b>故障策略</b>：worker 每步的写（{@code save*}）尽力而为（吞 + warn）——
 * 进度写失败不得打断 move/clone 流程；读（{@code get*}）抛——查询失败报 500
 * 而不是静默当成"任务不存在"（404 误导）。</p>
 */
public class RedisKnowledgeTaskProgressStore implements KnowledgeTaskProgressStore {

    private static final Logger log = LoggerFactory.getLogger(RedisKnowledgeTaskProgressStore.class);

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    static final String MOVE_PREFIX = "knowledge:task:progress:move:";
    static final String CLONE_PREFIX = "knowledge:task:progress:clone:";

    /** 与进程内实现的读路径 TTL 同值。 */
    static final long TTL_SECONDS = 24 * 3600L;

    private final StringRedisTemplate template;

    public RedisKnowledgeTaskProgressStore(StringRedisTemplate template) {
        this.template = template;
    }

    // ── move ──────────────────────────────────────────────────────────

    @Override
    public void saveMoveInitial(KnowledgeMoveProgress progress) {
        try {
            // NX = putIfAbsent 语义：只在键不存在时落初始进度
            template.opsForValue().setIfAbsent(MOVE_PREFIX + progress.taskId(),
                    toJson(progress), Duration.ofSeconds(TTL_SECONDS));
        } catch (RuntimeException e) {
            log.warn("[KnowledgeProgressRedis] save move initial failed for {}", progress.taskId(), e);
        }
    }

    @Override
    public void saveMove(KnowledgeMoveProgress progress) {
        try {
            template.opsForValue().set(MOVE_PREFIX + progress.taskId(),
                    toJson(progress), Duration.ofSeconds(TTL_SECONDS));
        } catch (RuntimeException e) {
            // 进度写失败不得打断 move 流程
            log.warn("[KnowledgeProgressRedis] save move failed for {}", progress.taskId(), e);
        }
    }

    @Override
    public KnowledgeMoveProgress getMove(String taskId) {
        return read(MOVE_PREFIX + taskId, KnowledgeMoveProgress.class);
    }

    // ── clone ─────────────────────────────────────────────────────────

    @Override
    public void saveCloneInitial(KBCloneProgress progress) {
        try {
            template.opsForValue().setIfAbsent(CLONE_PREFIX + progress.taskId(),
                    toJson(progress), Duration.ofSeconds(TTL_SECONDS));
        } catch (RuntimeException e) {
            log.warn("[KnowledgeProgressRedis] save clone initial failed for {}", progress.taskId(), e);
        }
    }

    @Override
    public void saveClone(KBCloneProgress progress) {
        try {
            template.opsForValue().set(CLONE_PREFIX + progress.taskId(),
                    toJson(progress), Duration.ofSeconds(TTL_SECONDS));
        } catch (RuntimeException e) {
            log.warn("[KnowledgeProgressRedis] save clone failed for {}", progress.taskId(), e);
        }
    }

    @Override
    public KBCloneProgress getClone(String taskId) {
        return read(CLONE_PREFIX + taskId, KBCloneProgress.class);
    }

    /** 过期（键被 TTL 回收）→ null；解析失败抛（损坏要暴露，不静默 404）。 */
    private <T> T read(String key, Class<T> type) {
        String json = template.opsForValue().get(key);
        if (json == null) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("knowledge task progress unmarshal failed: " + e.getMessage(), e);
        }
    }

    private static String toJson(Object progress) {
        try {
            return MAPPER.writeValueAsString(progress);
        } catch (Exception e) {
            throw new IllegalStateException("knowledge task progress marshal failed: " + e.getMessage(), e);
        }
    }
}
