package com.ragagent.knowledge.recovery;

import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.repository.KnowledgeSpanRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 知识侧的启动恢复：卡在 pending/processing/finalizing/deleting 的解析行复位为 failed，
 * 并取消孤儿 span；summary 的 pending/processing 同样复位。
 *
 * <p>SQL 与状态机语义属于 knowledge 域，boot 只负责启动触发与编排——不要把这两条
 * UPDATE 再搬回 boot。</p>
 */
@Component
public class KnowledgeStartupRecovery {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeStartupRecovery.class);

    /** 重启中断时写入 {@code error_message} 的文案。 */
    public static final String RESTART_INTERRUPTED_MESSAGE =
            "Task interrupted due to application restart";

    private final JdbcTemplate jdbc;
    private final KnowledgeSpanRepository spanRepository;

    public KnowledgeStartupRecovery(JdbcTemplate jdbc, KnowledgeSpanRepository spanRepository) {
        this.jdbc = jdbc;
        this.spanRepository = spanRepository;
    }

    /** 知识面复位（解析态 + summary 态），两者各自容错、互不影响。 */
    public void resetStuck(boolean distributed) {
        try {
            resetStuckKnowledge(distributed);
        } catch (RuntimeException e) {
            log.warn("resetStuck: reset stuck knowledge failed", e);
        }
        try {
            resetStuckSummary(distributed);
        } catch (RuntimeException e) {
            log.warn("resetStuck: reset pending summary tasks failed", e);
        }
    }

    /**
     * 分布式查询条件：可复位状态 + wiki 独槽排除（NOT-EXISTS 子查询）。
     *
     * <p>Lite（{@code distributed=false}）<b>不排除</b> wiki 独槽行：分布式侧的排除理由是
     * 「wiki ingest 独立落库，重启后能收尾」，而单机形态下队列在进程内、随重启消失，
     * <b>没有任何组件会重建触发器</b>（重启后该 wiki op 会一直躺着，文档永远停在
     * finalizing、卡片一直显示「优化中」）。复位为失败让用户可手动重试（重试即重新触发 ingest）。</p>
     */
    private List<String> listStuckKnowledgeIds(boolean distributed) {
        if (!distributed) {
            return jdbc.queryForList("SELECT id FROM knowledges WHERE parse_status IN (?, ?, ?, ?)",
                    String.class,
                    Knowledge.PARSE_PENDING, Knowledge.PARSE_PROCESSING,
                    Knowledge.PARSE_FINALIZING, Knowledge.PARSE_DELETING);
        }
        return jdbc.queryForList("SELECT id FROM knowledges WHERE parse_status IN (?, ?, ?, ?) "
                + "AND NOT (parse_status = ? AND pending_subtasks_count = 1 AND EXISTS ("
                + "SELECT 1 FROM task_pending_ops "
                + "WHERE task_pending_ops.task_type = ? "
                + "AND task_pending_ops.scope = ? "
                + "AND task_pending_ops.dedup_key = knowledges.id "
                + "AND task_pending_ops.op = ?))",
                String.class,
                Knowledge.PARSE_PENDING, Knowledge.PARSE_PROCESSING,
                Knowledge.PARSE_FINALIZING, Knowledge.PARSE_DELETING,
                Knowledge.PARSE_FINALIZING,
                "wiki:ingest", "knowledge_base", "ingest");
    }

    private void resetStuckKnowledge(boolean distributed) {
        if (distributed) {
            return;
        }
        List<String> stuckIds = listStuckKnowledgeIds(false);
        if (stuckIds.isEmpty()) {
            return;
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("failed", Knowledge.PARSE_FAILED)
                .addValue("errorMessage", RESTART_INTERRUPTED_MESSAGE)
                .addValue("ids", stuckIds);
        int reset = new NamedParameterJdbcTemplate(jdbc).update(
                "UPDATE knowledges SET parse_status = :failed, error_message = :errorMessage,"
                        + " pending_subtasks_count = 0 WHERE id IN (:ids)", params);
        if (reset <= 0) {
            return;
        }
        log.info("Reset {} stuck knowledge parsing tasks to failed state (distributed=false)", reset);
        // 行复位为终态后才取消孤儿 span（防止 UI 在后续手动重试时出现重复的
        // running 子 span；重读成功复位的行，SELECT/UPDATE 缝隙里状态变化的行不误取消）
        List<String> resetIds = new NamedParameterJdbcTemplate(jdbc).queryForList(
                "SELECT id FROM knowledges WHERE id IN (:ids) "
                        + "AND parse_status = :failed AND error_message = :errorMessage",
                params, String.class);
        for (String knowledgeId : resetIds) {
            int attempt = spanRepository.latestAttempt(knowledgeId);
            if (attempt <= 0) {
                continue;
            }
            long cancelled = spanRepository.cancelAllOpenSpans(knowledgeId, attempt,
                    "SERVER_RESTART", RESTART_INTERRUPTED_MESSAGE);
            if (cancelled > 0) {
                log.info("resetStuck: cancelled {} open span(s) for knowledge {} attempt {}",
                        cancelled, knowledgeId, attempt);
            }
        }
    }

    private void resetStuckSummary(boolean distributed) {
        if (distributed) {
            return;
        }
        int reset = jdbc.update("UPDATE knowledges SET summary_status = ? "
                + "WHERE summary_status IN (?, ?)", "failed", "pending", "processing");
        if (reset > 0) {
            log.info("Reset {} stuck summary generation tasks to failed state (distributed=false)",
                    reset);
        }
    }
}
