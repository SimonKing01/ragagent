package com.ragagent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.ragagent.knowledge.recovery.KnowledgeStartupRecovery;
import com.ragagent.knowledge.service.management.KnowledgeRecycleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * K6：三种启动复位**各按自己守护的任务基础设施**判据（不再用 {@code REDIS_ADDR} 统一代理）。
 *
 * <p>知识侧 SQL/状态机已下沉到 {@link KnowledgeStartupRecovery}；boot 只验证
 * “按开关把知识面委托出去”与“同步日志面独立复位”。</p>
 */
class StartupTaskRecoverySplitTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final KnowledgeStartupRecovery knowledgeRecovery = mock(KnowledgeStartupRecovery.class);
    private final KnowledgeRecycleService recycleService = mock(KnowledgeRecycleService.class);

    @Test
    @DisplayName("K6：知识面按 knowledge.redis-enabled 委托；同步日志面独立复位")
    void criteriaArePerSurface() {
        StartupTaskRecovery recovery = new StartupTaskRecovery(jdbc, knowledgeRecovery, recycleService, true, false);

        recovery.resetPendingTasks();

        verify(knowledgeRecovery).resetStuck(true);
        verify(recycleService).retryFailedCleanups(StartupTaskRecovery.CLEANUP_RETRY_LIMIT);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).update(sql.capture(), any(Object[].class));
        assertThat(sql.getAllValues()).as("同步日志的复位必须是 UPDATE sync_logs")
                .anyMatch(s -> s.contains("sync_logs"));
    }

    @Test
    @DisplayName("K6：知识面未装配 Redis 时仍交给知识域复位")
    void knowledgeResetsWhenRedisSurfaceAbsent() {
        StartupTaskRecovery recovery = new StartupTaskRecovery(jdbc, knowledgeRecovery, recycleService, false, false);

        recovery.resetPendingTasks();

        verify(knowledgeRecovery).resetStuck(false);
    }
}
