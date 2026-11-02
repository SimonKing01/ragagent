package com.ragagent.knowledge.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.knowledge.dto.faq.FaqImportProgress;
import com.ragagent.knowledge.support.FaqImportTaskStore;
import com.ragagent.platform.web.JsonMappers;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * {@link FaqImportTaskStore} 的 <b>Redis</b> 实现（跨实例共享的进度与锁）。
 *
 * <p>{@code knowledge.redis-enabled=true} 时由 {@code KnowledgeRedisWiring}
 * 装配（{@code @Primary} 覆盖 {@link InProcessFaqImportTaskStore}）。</p>
 *
 * <h2>Redis 结构</h2>
 * <ul>
 *   <li>{@code knowledge:faq:import:progress:<taskId>}（STRING = 进度 JSON，TTL 24 小时）；</li>
 *   <li>{@code knowledge:faq:import:running:<kbId>}（HASH = taskId/enqueuedAt/instanceId，
 *       TTL 2 小时）——running 锁跨实例可见，"同一知识库已有导入任务"的拦截在多副本下成立；</li>
 *   <li>{@code knowledge:faq:import:guard:<guardKey>}（STRING NX EX 30 秒）——同标准问创建互斥。</li>
 * </ul>
 *
 * <h2>故障策略（有意，非疏忽）</h2>
 * <ul>
 *   <li><b>running 锁取/读：fail-closed（抛异常）</b>——Redis 故障时宁可拒绝启动导入，
 *       不可让两个副本各跑一次（重复条目是可恢复的数据质量问题，但能避免就避免）；</li>
 *   <li><b>进度写：尽力而为（吞 + warn）</b>——save 在导入流程的多个节点被调用，
 *       让它抛会打断导入本身；进度是体验数据，丢了只是查询不到；</li>
 *   <li><b>进度读：抛</b>——查询失败要报 500（可重试），不能静默当成"任务不存在"（404 误导）；</li>
 *   <li><b>释放类操作（clearRunningInfoIfMatches / releaseCreateGuard）：吞 + warn</b>——
 *       TTL 兜底（running 2h、guard 30s），实例崩溃后自动自愈。</li>
 * </ul>
 */
public class RedisFaqImportTaskStore implements FaqImportTaskStore {

    private static final Logger log = LoggerFactory.getLogger(RedisFaqImportTaskStore.class);

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    static final String PROGRESS_PREFIX = "knowledge:faq:import:progress:";
    static final String RUNNING_PREFIX = "knowledge:faq:import:running:";
    static final String GUARD_PREFIX = "knowledge:faq:import:guard:";

    /** 进度快照 TTL（与 move/clone 进度同值：24 小时足够覆盖用户回看窗口）。 */
    static final long PROGRESS_TTL_SECONDS = 24 * 3600L;

    /**
     * running 锁 TTL：正常完成由 clearRunningInfoIfMatches 主动释放；
     * TTL 是实例崩溃时的兜底（否则该 KB 永远无法再导入）。2 小时覆盖任何正常导入时长。
     */
    static final long RUNNING_TTL_SECONDS = 2 * 3600L;

    /** 创建互斥 TTL：创建是秒级操作；崩溃后 30 秒自愈（比永久占住好）。 */
    static final long GUARD_TTL_SECONDS = 30L;

    /** setRunningInfo：HSET 三字段 + 单独 TTL，原子（否则中间崩溃会留下无 TTL 的永久锁）。 */
    private static final RedisScript<Long> SET_RUNNING = new DefaultRedisScript<>("""
            redis.call('hset', KEYS[1], 'taskId', ARGV[1], 'enqueuedAt', ARGV[2], 'instanceId', ARGV[3])
            redis.call('pexpire', KEYS[1], ARGV[4])
            return 1
            """, Long.class);

    /**
     * clearRunningInfoIfMatches：原子校验（taskId 相等，且 instanceId 一方为空即视为通配——
     * 与进程内实现的匹配条件逐条对应）后才删。
     */
    private static final RedisScript<Long> CLEAR_RUNNING = new DefaultRedisScript<>("""
            local tid = redis.call('hget', KEYS[1], 'taskId')
            if not tid then return 0 end
            local iid = redis.call('hget', KEYS[1], 'instanceId')
            if iid == false then iid = '' end
            if tid == ARGV[1] and (iid == '' or ARGV[2] == '' or iid == ARGV[2]) then
              redis.call('del', KEYS[1])
              return 1
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate template;

    public RedisFaqImportTaskStore(StringRedisTemplate template) {
        this.template = template;
    }

    // ── 进度 ──────────────────────────────────────────────────────────

    @Override
    public FaqImportProgress getProgress(String taskId) {
        String json = template.opsForValue().get(PROGRESS_PREFIX + taskId);
        if (json == null) {
            return null;
        }
        try {
            return MAPPER.readValue(json, FaqImportProgress.class);
        } catch (Exception e) {
            throw new IllegalStateException("faq import progress unmarshal failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void saveProgress(FaqImportProgress p) {
        try {
            template.opsForValue().set(PROGRESS_PREFIX + p.taskId(), toJson(p),
                    Duration.ofSeconds(PROGRESS_TTL_SECONDS));
        } catch (RuntimeException e) {
            // 进度是体验数据：写失败不得打断导入流程
            log.warn("[FaqImportRedis] save progress failed for {}", p.taskId(), e);
        }
    }

    // ── running 锁 ────────────────────────────────────────────────────

    @Override
    public String getRunningTaskId(String kbId) {
        Object taskId = template.opsForHash().get(RUNNING_PREFIX + kbId, "taskId");
        return taskId == null ? "" : taskId.toString();
    }

    @Override
    public void setRunningInfo(String kbId, RunningInfo info) {
        template.execute(SET_RUNNING, List.of(RUNNING_PREFIX + kbId),
                info.taskId(), Long.toString(info.enqueuedAt()), info.instanceId(),
                Long.toString(RUNNING_TTL_SECONDS * 1000L));
    }

    @Override
    public void clearRunningInfoIfMatches(String kbId, String taskId, String instanceId, long enqueuedAt) {
        try {
            template.execute(CLEAR_RUNNING, List.of(RUNNING_PREFIX + kbId), taskId, instanceId);
        } catch (RuntimeException e) {
            // 释放失败靠 TTL 兜底（2 小时自愈）
            log.warn("[FaqImportRedis] clear running info failed for {}", kbId, e);
        }
    }

    // ── 创建互斥 ──────────────────────────────────────────────────────

    @Override
    public boolean acquireCreateGuard(String key) {
        Boolean ok = template.opsForValue().setIfAbsent(GUARD_PREFIX + key, "1",
                Duration.ofSeconds(GUARD_TTL_SECONDS));
        return Boolean.TRUE.equals(ok);
    }

    @Override
    public void releaseCreateGuard(String key) {
        try {
            template.delete(GUARD_PREFIX + key);
        } catch (RuntimeException e) {
            // 释放失败靠 TTL 兜底（30 秒自愈）
            log.warn("[FaqImportRedis] release create guard failed", e);
        }
    }

    private static String toJson(FaqImportProgress p) {
        try {
            return MAPPER.writeValueAsString(p);
        } catch (Exception e) {
            throw new IllegalStateException("faq import progress marshal failed: " + e.getMessage(), e);
        }
    }
}
