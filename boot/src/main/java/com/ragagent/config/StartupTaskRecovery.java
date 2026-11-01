package com.ragagent.config;

import com.ragagent.knowledge.recovery.KnowledgeStartupRecovery;
import com.ragagent.knowledge.service.management.KnowledgeRecycleService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动恢复。
 *
 * <p>应用重启后把卡在处理态的任务复位为失败，让前端不再显示永远转圈的行：</p>
 * <ol>
 * <li><b>知识解析</b>（仅未装配知识 Redis 面时）：parse_status ∈
 * {pending, processing, finalizing, deleting} 的行 → failed +
 * "Task interrupted due to application restart" + pending_subtasks_count=0。
 * 分布式实现另有「finalizing 且唯一未决子槽是持久化 wiki op」的 NOT-EXISTS 排除
 * （分布式队列持久化、重启后能收尾）——单机形态下该理由不成立，
 * 故 Lite 路径一并复位（详见 {@link KnowledgeStartupRecovery#resetStuck(boolean)}）；复位
 * 成功后按行取消孤儿 span（LatestAttempt + CancelAllOpenSpans，errorCode=SERVER_RESTART）。</li>
 * <li><b>摘要生成</b>（仅 Lite）：summary_status ∈ {pending, processing} → failed。</li>
 * <li><b>数据源同步日志</b>（两种模式都跑）：status=running → failed +
 * "Sync interrupted due to application restart" + finished_at=now；分布式模式
 * 加 30 分钟陈旧窗——持久化队列里可能还有
 * 未开 span 的积压任务，只有明显陈旧的才敢判死。</li>
 * </ol>
 *
 * <p><b>知识/摘要行的跳过判据 = {@code knowledge.redis-enabled}</b>（与
 * {@code KnowledgeRedisWiring} 同一开关）。不用 {@code REDIS_ADDR} 非空判断：
 * 只把 Redis 用于 IM/embed 等其它面的部署会被误判成分布式，卡住的知识行**永不复位**
 * （前端永远转圈，HousekeepingService 只判 failed 不重投）。同理数据源同步日志按
 * {@code datasource.redis-enabled} 判断——三个开关各管各的任务基础设施。</p>
 *
 * <p>判死仍需谨慎：知识 Redis 面装配时另一副本可能仍在处理同一知识，启动钩子无法区分
 * 孤儿与积压——那是 HousekeepingService 的职责（检查 span 活动与真队列）。</p>
 *
 * <p>失败只 WARN 不阻塞启动。此处写下的
 * "Task interrupted due to application restart" 文案即 {@code KnowledgeService}
 * 重启文案映射的消费对象——此前该映射只读不写、错误码永不可产生。</p>
 */
@Component
public class StartupTaskRecovery {

    private static final Logger log = LoggerFactory.getLogger(StartupTaskRecovery.class);

    /** 陈旧窗：30 分钟。 */
    static final long STALE_WINDOW_MINUTES = 30;

    /** 单次启动最多重试多少条删除清理（避免故障积压拖慢启动）。 */
    static final int CLEANUP_RETRY_LIMIT = 200;

    /** 知识/摘要行复位时的 error_message 文案。 */
    public static final String RESTART_INTERRUPTED_MESSAGE =
            KnowledgeStartupRecovery.RESTART_INTERRUPTED_MESSAGE;

    /** 数据源同步日志复位时的 error_message 文案。 */
    public static final String SYNC_INTERRUPTED_MESSAGE =
            "Sync interrupted due to application restart";

    private final JdbcTemplate jdbc;
    private final KnowledgeStartupRecovery knowledgeRecovery;
    private final KnowledgeRecycleService recycleService;
    /** 知识 Redis 面是否装配（= {@code KnowledgeRedisWiring} 的同一开关）。 */
    private final boolean knowledgeRedisEnabled;
    /** 数据源任务队列是否装配 Redis 版（= {@code DataSourceTaskQueueWiring} 的同一开关）。 */
    private final boolean datasourceRedisEnabled;

    public StartupTaskRecovery(JdbcTemplate jdbc,
            KnowledgeStartupRecovery knowledgeRecovery,
            KnowledgeRecycleService recycleService,
            @Value("${knowledge.redis-enabled:false}") boolean knowledgeRedisEnabled,
            @Value("${datasource.redis-enabled:false}") boolean datasourceRedisEnabled) {
        this.jdbc = jdbc;
        this.knowledgeRecovery = knowledgeRecovery;
        this.recycleService = recycleService;
        this.knowledgeRedisEnabled = knowledgeRedisEnabled;
        this.datasourceRedisEnabled = datasourceRedisEnabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void resetPendingTasks() {
        resetPendingTasks(knowledgeRedisEnabled, datasourceRedisEnabled);
    }

    /** 测试便捷重载：两面同值（生产路径走 {@link #resetPendingTasks()} 的注入开关）。 */
    void resetPendingTasks(boolean distributed) {
        resetPendingTasks(distributed, distributed);
    }

    /**
 * 三种复位各自按**自己守护的任务基础设施**决定是否跳过（口径必须与装配一致）。
 *
 * @param knowledgeDistributed 知识 Redis 面已装配（{@code knowledge.redis-enabled}）——
 * 此时另一副本可能仍在处理同一知识，启动钩子不敢判死
 * @param syncDistributed 数据源同步队列已装配 Redis 版（{@code datasource.redis-enabled}）
 */
    void resetPendingTasks(boolean knowledgeDistributed, boolean syncDistributed) {
        try {
            knowledgeRecovery.resetStuck(knowledgeDistributed);
        } catch (RuntimeException e) {
            log.warn("resetPendingTasks: reset stuck knowledge failed", e);
        }
        try {
            resetStuckSyncLogs(syncDistributed);
        } catch (RuntimeException e) {
            log.warn("resetPendingTasks: reset pending data source sync tasks failed", e);
        }
        try {
            recycleService.retryFailedCleanups(CLEANUP_RETRY_LIMIT);
        } catch (RuntimeException e) {
            log.warn("resetPendingTasks: retry failed knowledge cleanups failed", e);
        }
    }


    private void resetStuckSyncLogs(boolean distributed) {
        Object cutoff = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(STALE_WINDOW_MINUTES);
        int reset = distributed
                ? jdbc.update("UPDATE sync_logs SET status = 'failed', error_message = ?, "
                        + "finished_at = ? WHERE status = 'running' AND started_at < ?",
                        SYNC_INTERRUPTED_MESSAGE, OffsetDateTime.now(ZoneOffset.UTC), cutoff)
                : jdbc.update("UPDATE sync_logs SET status = 'failed', error_message = ?, "
                        + "finished_at = ? WHERE status = 'running'",
                        SYNC_INTERRUPTED_MESSAGE, OffsetDateTime.now(ZoneOffset.UTC));
        if (reset > 0) {
            log.info("Reset {} stuck data source sync tasks to failed state (distributed={})",
                    reset, distributed);
        }
    }
}
