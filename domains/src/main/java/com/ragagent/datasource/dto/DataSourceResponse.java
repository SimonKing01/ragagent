package com.ragagent.datasource.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.datasource.domain.DataSource;
import com.ragagent.datasource.domain.DataSourceConfig;
import com.ragagent.datasource.domain.SyncLog;
import com.ragagent.platform.web.ZeroTimeSerializer;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 数据源管理端点的响应体。
 *
 * <h2>它为什么不是裸实体</h2>
 * <p>实体的 {@code config} 里躺着加密后的连接器凭据。直接把实体丢给序列化器
 * 会把密文（以及历史行里的明文）送到前端，所以每一个管理端点都必须
 * 经本类出参——<b>credentials 按构造被剥离</b>，"配没配"只经
 * {@code credentials.credentials.configured} 这一个布尔暴露。</p>
 *
 * <h2>键序 = 声明序</h2>
 * <pre>
 *   {"id","tenantId","knowledgeBaseId","name","type","config","syncSchedule","syncMode",
 *    "status","conflictStrategy","syncDeletions","lastSyncAt","lastSyncCursor",
 *    "lastSyncResult","errorMessage","syncLogRetentionDays","createdAt","updatedAt",
 *    "totalItemsSynced","latestSyncLog","credentials"}
 *   ← 本类字段全 camel、零注解
 * </pre>
 * <p>按字段声明序输出（不是字母序）——{@code config} 夹在 {@code type} 与
 * {@code syncSchedule} 之间、{@code credentials} 在最后。</p>
 *
 * <h2>为空省略（逐字段）</h2>
 * <ul>
 *   <li>{@code config}：解析失败时为 {@code null} 并<b>省略整个键</b>；</li>
 *   <li>{@code lastSyncCursor} / {@code lastSyncResult}：{@code NON_EMPTY}
 *       （JsonNode 的 {@code null} 与空节点都省略）；</li>
 *   <li>{@code errorMessage}：空串省略（注意这与实体自身
 *       "恒输出空串"的形态**不同**，只有 DTO 这一层省略）；</li>
 *   <li>{@code latestSyncLog}：{@code null} 省略；</li>
 *   <li>{@code credentials}：map + 为空省略 → 空 map 省略。但工厂方法**恒**塞一个
 *       {@code credentials} 键进去，所以线上永远出现；</li>
 *   <li>{@code lastSyncAt} / {@code createdAt} / {@code updatedAt} / {@code totalItemsSynced}
 *       / {@code syncDeletions} / 四个字符串字段：<b>没有</b>为空省略
 *       → 恒输出（null 照输出）。</li>
 * </ul>
 *
 * <h2>持久化语义</h2>
 * <ol>
 *   <li><b>钩子</b>：无（本类不落表）。</li>
 *   <li><b>关联预加载</b>：{@code latest_sync_log} 是"由 service 逐个回填"的
 *       非表字段，不是联表查询。</li>
 *   <li><b>软删除 / 默认排序 / 唯一索引 / 自动时间戳</b>：全无。</li>
 * </ol>
 */
public class DataSourceResponse {

    private String id = "";

    private long tenantId;

    private String knowledgeBaseId = "";

    private String name = "";

    private String type = "";

    private DataSourceConfigDto config;

    private String syncSchedule = "";

    private String syncMode = "";

    private String status = "";

    private String conflictStrategy = "";

    private boolean syncDeletions;

    private OffsetDateTime lastSyncAt;

    private JsonNode lastSyncCursor;

    private JsonNode lastSyncResult;

    private String errorMessage;

    private int syncLogRetentionDays;

    private OffsetDateTime createdAt = ZeroTimeSerializer.ZERO_DATE_TIME;

    private OffsetDateTime updatedAt = ZeroTimeSerializer.ZERO_DATE_TIME;

    private long totalItemsSynced;

    private SyncLog latestSyncLog;

    /**
     * 单个逻辑凭据字段。挂 {@link SortedMapSerializer}：map 键按字母序输出，
     * 而这里只有一个键——排序本身无所谓，但挂上它同时保证 {@code NON_EMPTY} 的语义
     * （自定义序列化器会让 {@code @JsonInclude(NON_EMPTY)} 失效）。
     */
    private Map<String, DatasourceCredentialFieldMetadata> credentials;

    /** 工厂：null 入参回 null。 */
    public static DataSourceResponse from(DataSource ds) {
        if (ds == null) {
            return null;
        }
        DataSourceResponse out = new DataSourceResponse();
        DataSourceConfigDto cfgDto = null;
        boolean configured = false;

        DataSourceConfig parsed = ds.parseConfig();
        if (parsed != null) {
            cfgDto = new DataSourceConfigDto();
            cfgDto.setType(parsed.getType());
            cfgDto.setResourceIds(parsed.getResourceIds());
            cfgDto.setSettings(parsed.getSettings());
            configured = parsed.hasConfiguredCredentials(ds.getType());
        }

        out.id = ds.getId();
        out.tenantId = ds.getTenantId() == null ? 0L : ds.getTenantId();
        out.knowledgeBaseId = ds.getKnowledgeBaseId();
        out.name = ds.getName();
        out.type = ds.getType();
        out.config = cfgDto;
        out.syncSchedule = ds.getSyncSchedule();
        out.syncMode = ds.getSyncMode();
        out.status = ds.getStatus();
        out.conflictStrategy = ds.getConflictStrategy();
        out.syncDeletions = ds.isSyncDeletions();
        out.lastSyncAt = ds.getLastSyncAt();
        out.lastSyncCursor = ds.getLastSyncCursor();
        out.lastSyncResult = ds.getLastSyncResult();
        // errorMessage 空串**省略键**（与实体自身的恒输出不同）
        out.errorMessage = ds.getErrorMessage();
        out.syncLogRetentionDays = ds.getSyncLogRetentionDays();
        out.createdAt = ds.getCreatedAt();
        out.updatedAt = ds.getUpdatedAt();
        out.totalItemsSynced = ds.getTotalItemsSynced() == null ? 0L : ds.getTotalItemsSynced();
        out.latestSyncLog = ds.getLatestSyncLog();
        out.credentials = DatasourceCredentialsResponse.credentials(configured).fields();
        return out;
    }

    public String getId() { return id; }
    public void setId(String v) { id = v == null ? "" : v; }

    public long getTenantId() { return tenantId; }
    public void setTenantId(long v) { tenantId = v; }

    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(String v) { knowledgeBaseId = v == null ? "" : v; }

    public String getName() { return name; }
    public void setName(String v) { name = v == null ? "" : v; }

    public String getType() { return type; }
    public void setType(String v) { type = v == null ? "" : v; }

    public DataSourceConfigDto getConfig() { return config; }
    public void setConfig(DataSourceConfigDto v) { config = v; }

    public String getSyncSchedule() { return syncSchedule; }
    public void setSyncSchedule(String v) { syncSchedule = v == null ? "" : v; }

    public String getSyncMode() { return syncMode; }
    public void setSyncMode(String v) { syncMode = v == null ? "" : v; }

    public String getStatus() { return status; }
    public void setStatus(String v) { status = v == null ? "" : v; }

    public String getConflictStrategy() { return conflictStrategy; }
    public void setConflictStrategy(String v) { conflictStrategy = v == null ? "" : v; }

    public boolean isSyncDeletions() { return syncDeletions; }
    public void setSyncDeletions(boolean v) { syncDeletions = v; }

    public OffsetDateTime getLastSyncAt() { return lastSyncAt; }
    public void setLastSyncAt(OffsetDateTime v) { lastSyncAt = v; }

    public JsonNode getLastSyncCursor() { return lastSyncCursor; }
    public void setLastSyncCursor(JsonNode v) { lastSyncCursor = v; }

    public JsonNode getLastSyncResult() { return lastSyncResult; }
    public void setLastSyncResult(JsonNode v) { lastSyncResult = v; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String v) { errorMessage = v; }

    public int getSyncLogRetentionDays() { return syncLogRetentionDays; }
    public void setSyncLogRetentionDays(int v) { syncLogRetentionDays = v; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { createdAt = v; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { updatedAt = v; }

    public long getTotalItemsSynced() { return totalItemsSynced; }
    public void setTotalItemsSynced(long v) { totalItemsSynced = v; }

    public SyncLog getLatestSyncLog() { return latestSyncLog; }
    public void setLatestSyncLog(SyncLog v) { latestSyncLog = v; }

    public Map<String, DatasourceCredentialFieldMetadata> getCredentials() { return credentials; }
    public void setCredentials(Map<String, DatasourceCredentialFieldMetadata> v) { credentials = v; }
}
