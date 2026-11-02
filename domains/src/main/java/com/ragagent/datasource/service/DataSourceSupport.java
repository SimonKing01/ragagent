package com.ragagent.datasource.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.contract.knowledge.KnowledgeBaseRowView;
import com.ragagent.contract.knowledge.KnowledgeRowView;
import com.ragagent.datasource.domain.DataSource;
import com.ragagent.datasource.domain.DataSourceConfig;
import com.ragagent.datasource.domain.DataSourceException;
import com.ragagent.datasource.domain.SyncLog;
import com.ragagent.datasource.domain.TaskInitiator;
import com.ragagent.datasource.domain.connector.Connector;
import com.ragagent.datasource.domain.connector.ConnectorException;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.platform.web.JsonMappers;
import com.ragagent.platform.web.ZeroTimeSerializer;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 数据源支撑件：配置的校验/解析/深比较、知识库归属校验、身份与"零值时间"等判据、
 * 自动标签解析，以及尽力而为的落库与知识库活动记录。
 *
 * <p>持有 {@link DataSourceService} 回引以访问其依赖与常量；本类不得独立实例化。</p>
 */
final class DataSourceSupport {

    private static final Logger log = LoggerFactory.getLogger(DataSourceSupport.class);

    private final DataSourceService service;

    DataSourceSupport(DataSourceService service) {
        this.service = service;
    }

    /**
     * 找/建本次数据源的自动标签。
     *
     * <p><b>标签失败不致命</b>：同步照常进行、条目只是没有标签。</p>
     */
    List<String> resolveAutoTagIds(DataSource ds) {
        List<String> autoTagIds = new ArrayList<>();
        try {
            String tagId = service.autoTagProvider.findOrCreateTagId(ds.getKnowledgeBaseId(), ds.getName());
            if (tagId != null) {
                autoTagIds.add(tagId);
                log.info("[datasource] using auto-tag \"{}\" (id={}) for data source sync",
                        ds.getName(), tagId);
            }
        } catch (RuntimeException e) {
            log.warn("[datasource] failed to find/create auto-tag \"{}\" (proceeding without tag)", ds.getName(), e);
        }
        return autoTagIds;
    }

    /**
     * 解析配置后交给连接器真连一次。
     *
     * <p>解析失败一律折叠成 {@code invalid configuration}
     * （{@value #DataSourceService.ERR_INVALID_CONFIG}）——把 JSON 解析器的原文漏给用户是没有意义的。</p>
     */
    void validateDataSourceConfig(DataSource ds) {
        Connector connector = service.connectorRegistry.get(ds.getType());
        DataSourceConfig config = parseConfigOrInvalid(ds);
        // ⚠️ 这里**允许** config 为 null 并原样传给连接器：
        // "空 config"（解析结果为 null）是把 null 递下去的，各连接器自己拒绝。
        // 把 null 提前折叠成 InvalidConfig 会改变**哪个**错误被暴露出来。
        connector.validate(config);
    }

    /**
     * 解析配置；<b>只有解析抛错</b>才折叠成 {@code invalid configuration}；空 config 回 {@code null}
     * 并继续往下传（调用方自己决定怎么处理 null）。
     */
    static DataSourceConfig parseConfigOrInvalid(DataSource ds) {
        try {
            return ds.parseConfig();
        } catch (RuntimeException e) {
            throw new ConnectorException.InvalidConfig();
        }
    }

    /**
     * 知识库归属校验：
     * 找不到 → {@code knowledge base not found}；租户不符 → <b>同一个</b>错误
     * （不泄漏"这个 id 确实存在，只是不属于你"）。
     */
    KnowledgeBaseRowView requireOwnedKnowledgeBase(String kbId, Long tenantId) {
        KnowledgeBaseRowView kb = service.knowledge.findKnowledgeBase(kbId);
        if (kb == null) {
            throw new DataSourceException(DataSourceService.ERR_KNOWLEDGE_BASE_NOT_FOUND);
        }
        if (!Objects.equals(kb.getTenantId(), tenantId)) {
            throw new DataSourceException(DataSourceService.ERR_KNOWLEDGE_BASE_NOT_FOUND);
        }
        return kb;
    }

    /**
     * 深比较两个配置：折成规范化 JSON 树再比。
     *
     * <p>字段集：{@code type} / {@code credentials} /
     * {@code resourceIds} / {@code settings}。<b>不含</b> {@code multimodal_enabled}
     * ——它在本方法被调用时两侧都还是零值（{@code @JsonIgnore}、从不落库、
     * 只在同步抓取前临时填）。</p>
     */
    static boolean configDeepEquals(DataSourceConfig a, DataSourceConfig b) {
        return Objects.equals(configTree(a), configTree(b));
    }

    static com.fasterxml.jackson.databind.JsonNode configTree(DataSourceConfig cfg) {
        ObjectNode node = DataSourceService.MAPPER.createObjectNode();
        node.put("type", cfg.getType());
        node.set("credentials", DataSourceService.MAPPER.valueToTree(cfg.getCredentials()));
        node.set("resourceIds", DataSourceService.MAPPER.valueToTree(cfg.getResourceIds()));
        node.set("settings", DataSourceService.MAPPER.valueToTree(cfg.getSettings()));
        return node;
    }

    /** KB 的多模态开关：缺失时等价于 false。 */
    static boolean isMultimodalEnabled(KnowledgeBaseRowView kb) {
        try {
            java.lang.reflect.Method m = kb.getClass().getMethod("isMultimodalEnabled");
            Object v = m.invoke(kb);
            return v instanceof Boolean b && b;
        } catch (ReflectiveOperationException e) {
            // KB 没有 VLM 配置入口 → 等价于"没开多模态"（连接器因此不抽图片）
            return false;
        }
    }

    /** 从当前上下文取发起人；合成用户（API-Key 主体）刻意留空。 */
    static TaskInitiator taskInitiatorFromContext() {
        String userId = TenantContext.currentUserId();
        if (userId == null || userId.isEmpty() || isSyntheticUserId(userId)) {
            return TaskInitiator.empty();
        }
        String role = TenantContext.currentRole();
        return new TaskInitiator(userId, role == null ? "" : role);
    }

    /** 合成用户 id 的形态：{@code "system-"} + 全数字。 */
    static boolean isSyntheticUserId(String id) {
        String prefix = "system-";
        if (id == null || id.length() <= prefix.length() || !id.startsWith(prefix)) {
            return false;
        }
        for (int i = prefix.length(); i < id.length(); i++) {
            char c = id.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /** 零值时间判定（本模块只用来判"连接器有没有给时间"）。 */
    static boolean isZeroTime(OffsetDateTime t) {
        return t == null || ZeroTimeSerializer.isZeroValue(t);
    }

    static String readMetadataValue(KnowledgeRowView k, String key) {
        com.fasterxml.jackson.databind.JsonNode md = JsonMappers.toJsonNode(k.getMetadata());
        if (md == null || !md.isObject()) {
            return null;
        }
        com.fasterxml.jackson.databind.JsonNode v = md.get(key);
        return v == null || v.isNull() ? null : v.asText();
    }

    void bestEffortUpdate(DataSource ds) {
        bestEffort(() -> service.dsRepo.update(ds));
    }

    /** 尽力而为地更新同步日志（失败不影响主流程）。 */
    void bestEffortUpdateLog(SyncLog syncLog) {
        bestEffort(() -> service.syncLogRepo.update(syncLog));
    }

    static void bestEffort(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // 写库失败不改变主流程
        }
    }

    /** 构造一个"按字母序"的 details（审计 JSON 的既定键序）。 */
    static Map<String, Object> mapOf(Object... kv) {
        TreeMap<String, Object> m = new TreeMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** 一次同步运行在审计详情里的关联字段。 */
    record ActivityTask(String taskId, String trigger) {
    }

    /**
     * 记录一条知识库活动审计（写入规则见 {@code ActivityAuditRecorder}）。
     *
     * <p>本模块只发"汇总事件"——同步期间的单条变更一律被压掉
     * （{@code suppressed} 参数），由调用点自己保证只发汇总。</p>
     */
    void recordKbActivity(long tenantId, String kbId, String action, String targetType,
                          String targetId, String outcome, Map<String, Object> details,
                          ActivityTask task, boolean suppressed) {
        service.activityAudit.record(
                tenantId,
                "knowledge_base",
                kbId,
                action,
                targetType,
                targetId,
                outcome,
                details,
                task == null ? "" : task.taskId(),
                task == null ? "" : task.trigger(),
                task != null,
                suppressed);
    }

    static String nullToEmpty(String v) {
        return v == null ? "" : v;
    }
}
