package com.ragagent.knowledge.service;


import com.ragagent.knowledge.config.HousekeepingProperties;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.support.KnowledgeTaskRunner;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 知识处理链路的兜底清扫：周期扫描「卡在处理态」的知识行并判死为 failed，另扫
 * 摘要停滞行。worker 重试预算、多模态收口回调等防线漏掉的情形（handler 中途被杀、
 * 长调用超上限、计数器置位但子任务全部旁路失败）由它接住——没有这道网，
 * 一行就会永久停在 processing，用户侧只见一个永远转的圈。
 *
 * <h2>清扫 A：知识行（pending / processing / finalizing）</h2>
 * <p><b>两级判定</b>：{@code knowledges.updated_at} 只在 parse_status 迁移时推进，
 * 长阶段（大 PDF 解析、分块嵌入）可以跑一小时且状态不变，只看 updated_at 会误杀
 * 正在跑的活；因此与 {@code knowledge_processing_spans} 的最新心跳做<b>或</b>组合
 * （SpanTracker 每次 Begin/End/Fail/Skip 都推进 span 行，真在推进的流水线即使父行
 * 「冻结」也总有新鲜心跳）。心跳查询按「存在 {@code updated_at > cutoff} 的 span 行
 * 即活」的语义写、不做聚合——SQLite 对聚合返回值不做类型转换。完全没有 span 的
 * 知识行回落到单纯 updated_at 判定。查库失败一律失败安全：心跳查不出按「无心跳」、
 * 持久表查不出推迟本轮全部候选。</p>
 * <p><b>第二道闸：队列仍有活则不算孤儿</b>——心跳过期但富化子任务只是排在忙碌队列
 * 后面时，杀掉就是误报。两级探测：① 持久表 {@code task_pending_ops}（wiki ingest
 * 按知识 id 去重，立即判定且永远生效，不依赖 inspector）；② inspector 查瞬时队列
 * （出错按「仍是卡死」处理）。</p>
 * <h2>清扫 B：摘要状态</h2>
 * <p>摘要是解析后阶段，阈值更短（单个 LLM 调用即可界定）且没有 span 心跳可查：
 * {@code summary_status=processing} 且 updated_at 早于阈值 → failed。</p>
 * <h2>周期与开关</h2>
 * <p>5 分钟 ticker（应用就绪后启动，幂等；容器关闭停止接收）；卡死阈值 =
 * max(1 小时下限, 文档处理超时) + 10 分钟缓冲。{@code RAGAGENT_HOUSEKEEPING_ENABLED}
 * 缺省开启，显式 {@code false}/{@code 0}/{@code off}/{@code no} 才关停。
 * inspector 是注入缝：Lite 部署无任务队列，生产装配传 {@code null}
 * （只关闭这一项检查，持久表那道闸不受影响）。</p>
 */
@Component
public class HousekeepingService {

    private static final Logger log = LoggerFactory.getLogger(HousekeepingService.class);

    static final Duration SWEEP_INTERVAL = Duration.ofMinutes(5);

    /** 摘要停滞判定阈值。 */
    static final Duration SUMMARY_STALE_THRESHOLD = Duration.ofHours(1);

    /** 处理行判死阈值下限（防误杀长阶段）。 */
    static final Duration STALE_FLOOR = Duration.ofHours(1);

    static final Duration STALE_BUFFER = Duration.ofMinutes(10);

    static final Duration DEFAULT_DOCUMENT_PROCESS_TIMEOUT = Duration.ofHours(2);

    static final String ENABLED_ENV = "RAGAGENT_HOUSEKEEPING_ENABLED";
    static final String DOCUMENT_PROCESS_TIMEOUT_ENV = "RAGAGENT_DOCUMENT_PROCESS_TIMEOUT";

    static final List<String> CANDIDATE_STATUSES = List.of(
            Knowledge.PARSE_PENDING, Knowledge.PARSE_PROCESSING, Knowledge.PARSE_FINALIZING);

    static final String SUMMARY_STATUS_PROCESSING = "processing";
    static final String SUMMARY_STATUS_FAILED = "failed";

    static final String WIKI_TASK_TYPE = "wiki:ingest";
    static final String WIKI_TASK_SCOPE = "knowledge_base";
    static final String WIKI_OP_INGEST = "ingest";

    private final JdbcTemplate jdbc;
    /** 动态 IN 与条件拼装用；由 {@link #jdbc} 派生，构造器签名不变。 */
    private final NamedParameterJdbcTemplate namedJdbc;
    private final KnowledgeQueueInspector inspector;
    private final Duration documentProcessTimeout;
    private final boolean enabled;

    private final Object lock = new Object();
    private volatile boolean started;
    private volatile boolean stopping;

    /**
     * <b>Lite 无 任务队列 ⇒ 生产装配传 {@code null}</b>（只关闭这一项检查，持久表那道闸不受影响）。
     */
    public interface KnowledgeQueueInspector {
        boolean hasQueuedTasksForKnowledge(String knowledgeId);
    }

    /** 后台任务执行器（统一命名与关停）。 */
    private final KnowledgeTaskRunner taskExecutor;

    @Autowired
    public HousekeepingService(JdbcTemplate jdbc, KnowledgeTaskRunner taskExecutor,
                               HousekeepingProperties properties) {
        this(jdbc, null,
                documentProcessTimeoutFromEnv(properties.documentProcessTimeout()),
                housekeepingEnabledFromEnv(properties.enabledRaw()), taskExecutor);
    }

    /** 测试口：显式给阈值与开关/注入 inspector。 */
    HousekeepingService(JdbcTemplate jdbc, KnowledgeQueueInspector inspector,
                        Duration documentProcessTimeout, boolean enabled,
                        KnowledgeTaskRunner taskExecutor) {
        this.jdbc = jdbc;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
        this.inspector = inspector;
        this.documentProcessTimeout = documentProcessTimeout;
        this.enabled = enabled;
        this.taskExecutor = taskExecutor;
    }

    /**
     * 注册 5 分钟周期并启动后台循环。幂等——重复调用是 no-op（装配方无需协调顺序）。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        synchronized (lock) {
            if (started) {
                return;
            }
            if (!enabled) {
                log.info("[Housekeeping] disabled via {}=false", ENABLED_ENV);
                return;
            }
            started = true;
            stopping = false;
        }
        taskExecutor.submit("knowledge-housekeeping", this::sweepLoop);
        log.info("[Housekeeping] started with 5-minute sweep");
    }

    /** 停止周期循环（当前这轮清扫跑完即退出）。 */
    @PreDestroy
    public void stop() {
        synchronized (lock) {
            if (!started) {
                return;
            }
            stopping = true;
            started = false;
        }
    }

    private void sweepLoop() {
        while (!stopping) {
            try {
                Thread.sleep(SWEEP_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (stopping) {
                return;
            }
            try {
                runSweep();
            } catch (RuntimeException e) {
                log.warn("[Housekeeping] sweep failed", e);
            }
        }
    }

    public void runSweep() {
        Duration threshold = staleThreshold();
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC).minus(threshold);

        List<String> candidates = listStuckKnowledgeCandidates(cutoff);
        List<String> stuck = filterByLastSpanActivity(candidates, cutoff);
        int spanSkipped = candidates.size() - stuck.size();
        int[] queueSkipped = new int[1];
        stuck = filterOutQueued(stuck, queueSkipped);

        if (!stuck.isEmpty()) {
            recoverStuckKnowledge(stuck, threshold);
        }
        if (spanSkipped > 0) {
            log.info("[Housekeeping] {} candidate(s) skipped — span heartbeat within threshold",
                    spanSkipped);
        }
        if (queueSkipped[0] > 0) {
            log.info("[Housekeeping] {} candidate(s) skipped — tasks still queued "
                    + "(backpressure, not stuck)", queueSkipped[0]);
        }

        sweepSummary();
    }

    /**
     * 卡死行复位：{@code parse_status=failed、error_message=停滞说明、
     * pending_subtasks_count=0}（行仍处于可复位状态且未软删才生效）。
     */
    private void recoverStuckKnowledge(List<String> stuck, Duration threshold) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("failed", Knowledge.PARSE_FAILED)
                .addValue("errorMessage", "task stuck in processing > " + threshold
                        + ", recovered by housekeeping")
                .addValue("zero", 0)
                .addValue("ids", stuck)
                .addValue("statuses", CANDIDATE_STATUSES);
        try {
            int rows = namedJdbc.update("UPDATE knowledges SET parse_status = :failed,"
                            + " error_message = :errorMessage, pending_subtasks_count = :zero"
                            + " WHERE id IN (:ids) AND parse_status IN (:statuses)"
                            + " AND deleted_at IS NULL",
                    params);
            if (rows > 0) {
                log.info("[Housekeeping] recovered {} stuck knowledge rows (threshold={})",
                        rows, threshold);
            }
        } catch (RuntimeException e) {
            log.warn("[Housekeeping] knowledge sweep update failed", e);
        }
    }

    /**
     * 清扫 A 的候选集：可复位状态 + 行 updated_at 已过 cutoff。
     */
    private List<String> listStuckKnowledgeCandidates(OffsetDateTime cutoff) {
        try {
            return jdbc.queryForList("SELECT id FROM knowledges WHERE parse_status IN (?, ?, ?) "
                            + "AND updated_at < ? AND deleted_at IS NULL",
                    String.class,
                    Knowledge.PARSE_PENDING, Knowledge.PARSE_PROCESSING,
                    Knowledge.PARSE_FINALIZING, cutoff);
        } catch (RuntimeException e) {
            log.warn("[Housekeeping] knowledge candidate query failed", e);
            return List.of();
        }
    }

    /**
     * 心跳过滤——候选里**最近 span 行早于 cutoff（或压根没有 span）**的子集即真卡死。
     * 查库失败<b>失败安全</b>——按"全都没有心跳"
     * 处理（全部候选都当卡死），宁可多回收也绝不漏回收。
     */
    private List<String> filterByLastSpanActivity(List<String> candidates, OffsetDateTime cutoff) {
        if (candidates.isEmpty()) {
            return candidates;
        }
        Set<String> alive;
        try {
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("ids", candidates)
                    .addValue("cutoff", cutoff);
            alive = new LinkedHashSet<>(namedJdbc.queryForList(
                    "SELECT DISTINCT knowledge_id FROM knowledge_processing_spans "
                            + "WHERE knowledge_id IN (:ids) AND updated_at > :cutoff",
                    params, String.class));
        } catch (RuntimeException e) {
            log.warn("[Housekeeping] span heartbeat query failed (will fail safe and recover all candidates)", e);
            return candidates;
        }
        List<String> out = new ArrayList<>(candidates.size());
        for (String id : candidates) {
            if (!alive.contains(id)) {
                out.add(id); // 无新鲜心跳 → 仍按卡死处理
            }
        }
        return out;
    }

    /**
     * 第二道闸——把"队列里还有活"的候选剔掉（积压 ≠ 孤儿）。
     * 先查持久表（错误 ⇒ 推迟<b>全部</b>候选），
     * 再按 inspector 查瞬时队列（错误 ⇒ 当作仍卡死）。
     * @param skippedHolder 单元素输出：被剔掉的候选数
     */
    private List<String> filterOutQueued(List<String> candidates, int[] skippedHolder) {
        if (candidates.isEmpty()) {
            return candidates;
        }
        Set<String> durable;
        try {
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("taskType", WIKI_TASK_TYPE)
                    .addValue("scope", WIKI_TASK_SCOPE)
                    .addValue("op", WIKI_OP_INGEST)
                    .addValue("dedupKeys", candidates);
            durable = new LinkedHashSet<>(namedJdbc.queryForList(
                    "SELECT DISTINCT dedup_key FROM task_pending_ops "
                            + "WHERE task_type = :taskType AND scope = :scope AND op = :op"
                            + " AND dedup_key IN (:dedupKeys)",
                    params, String.class));
        } catch (RuntimeException e) {
            log.warn("[Housekeeping] durable queue probe failed (deferring {} candidate(s))", candidates.size(), e);
            skippedHolder[0] = candidates.size();
            return List.of();
        }

        List<String> out = new ArrayList<>(candidates.size());
        int skipped = 0;
        for (String id : candidates) {
            if (durable.contains(id)) {
                skipped++;
                continue;
            }
            if (inspector == null) {
                out.add(id);
                continue;
            }
            boolean queued;
            try {
                queued = inspector.hasQueuedTasksForKnowledge(id);
            } catch (RuntimeException e) {
                log.warn("[Housekeeping] queue probe failed for {} (will fail safe and treat as stuck)", id, e);
                out.add(id);
                continue;
            }
            if (queued) {
                skipped++;
                continue;
            }
            out.add(id);
        }
        skippedHolder[0] = skipped;
        return out;
    }

    private void sweepSummary() {
        OffsetDateTime summaryCutoff = OffsetDateTime.now(ZoneOffset.UTC)
                .minus(SUMMARY_STALE_THRESHOLD);
        int rows;
        try {
            rows = jdbc.update("UPDATE knowledges SET summary_status = ? "
                            + "WHERE summary_status = ? AND updated_at < ? AND deleted_at IS NULL",
                    SUMMARY_STATUS_FAILED, SUMMARY_STATUS_PROCESSING, summaryCutoff);
        } catch (RuntimeException e) {
            log.warn("[Housekeeping] summary sweep failed", e);
            return;
        }
        if (rows > 0) {
            log.info("[Housekeeping] recovered {} stuck summary rows", rows);
        }
    }

    /**
     * processing 行可以静置多久才被判孤儿。下限 1 小时
     * （真慢的大 PDF 不能在飞行中被杀），上限随运维配置的 DocumentProcessTimeout 再加
     * 10 分钟缓冲吸收调度抖动。
     */
    Duration staleThreshold() {
        Duration base = STALE_FLOOR;
        if (documentProcessTimeout != null && documentProcessTimeout.compareTo(base) > 0) {
            base = documentProcessTimeout;
        }
        return base.plus(STALE_BUFFER);
    }

    boolean enabled() {
        return enabled;
    }

    /**
     * 缺省、非正数或解析失败回落 {@code DefaultDocumentProcessTimeout}（2h）。
     */
    static Duration documentProcessTimeoutFromEnv(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return DEFAULT_DOCUMENT_PROCESS_TIMEOUT;
        }
        Duration parsed = parseGoDuration(raw.trim());
        return parsed == null || parsed.isZero() || parsed.isNegative()
                ? DEFAULT_DOCUMENT_PROCESS_TIMEOUT : parsed;
    }

    private static Duration parseGoDuration(String s) {
        if (s.isEmpty()) {
            return null;
        }
        Duration total = Duration.ZERO;
        int i = 0;
        while (i < s.length()) {
            int numStart = i;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) {
                i++;
            }
            if (i == numStart) {
                return null;
            }
            String digits = s.substring(numStart, i);
            int unitStart = i;
            while (i < s.length() && !Character.isDigit(s.charAt(i)) && s.charAt(i) != '.') {
                i++;
            }
            if (i == unitStart) {
                return null; // 缺单位
            }
            String unit = s.substring(unitStart, i);
            long scale = switch (unit) {
                case "ns" -> 1L;
                case "us", "\u00b5s", "\u03bcs" -> 1_000L;
                case "ms" -> 1_000_000L;
                case "s" -> 1_000_000_000L;
                case "m" -> 60L * 1_000_000_000L;
                case "h" -> 3_600L * 1_000_000_000L;
                default -> -1L;
            };
            if (scale < 0) {
                return null; // 未知单位
            }
            try {
                total = total.plusNanos((long) (Double.parseDouble(digits) * scale));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return total;
    }

    /** 缺省开启，只有显式 0/false/off/no 才关。 */
    static boolean housekeepingEnabledFromEnv(String raw) {
        String v = raw == null ? "" : raw.trim();
        if (v.isEmpty()) {
            return true;
        }
        switch (v.toLowerCase(Locale.ROOT)) {
            case "0", "false", "off", "no" -> {
                return false;
            }
            default -> {
                return true;
            }
        }
    }

}
