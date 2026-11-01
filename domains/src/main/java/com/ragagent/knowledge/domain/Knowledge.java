package com.ragagent.knowledge.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ragagent.contract.knowledge.KnowledgeParseStatus;
import com.ragagent.platform.persistence.PgJsonTypeHandler;
import java.time.OffsetDateTime;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * knowledges 表实体。
 * 仓储行为契约：
 * - 软删除 → 显式 isNull("deleted_at")
 * - 钩子 BeforeCreate：id UUID + custom_metadata 补 '{}'（Java 由 service 显式赋值，语义等价）
 * - metadata jsonb：文件型文档为内部摄取状态（NULL 常见）；手工知识为 ManualKnowledgeMetadata
 * - tags 为 无列映射标签 关联（不回填 → 恒 null，契约样例锁定 "tags":null）
 */
@TableName(value = "knowledges", autoResultMap = true)
public class Knowledge {

    public static final String PARSE_PENDING = KnowledgeParseStatus.PARSE_PENDING;
    public static final String PARSE_PROCESSING = KnowledgeParseStatus.PARSE_PROCESSING;
    public static final String PARSE_FINALIZING = KnowledgeParseStatus.PARSE_FINALIZING;
    public static final String PARSE_COMPLETED = KnowledgeParseStatus.PARSE_COMPLETED;
    public static final String PARSE_FAILED = KnowledgeParseStatus.PARSE_FAILED;
    public static final String PARSE_DELETING = KnowledgeParseStatus.PARSE_DELETING;
    public static final String PARSE_CANCELLED = KnowledgeParseStatus.PARSE_CANCELLED;
    public static final String PARSE_DELETED = KnowledgeParseStatus.PARSE_DELETED;
    public static final String PARSE_DELETE_FAILED = KnowledgeParseStatus.PARSE_DELETE_FAILED;

    @TableId(type = IdType.INPUT)
    private String id;
    private Long tenantId;
    private String knowledgeBaseId;
    /** 无列映射标签 关联回填；不回填 → 恒 null */
    @TableField(exist = false)
    private List<JsonNode> tags;
    private String type;
    private String title;
    private String description = "";
    private String source = "";
    private String channel = "web";
    private String parseStatus = PARSE_PENDING;
    /** 解析代次：每次重解析 +1，状态 CAS 与失败清场按它隔离新旧尝试。 */
    private Long parseAttempt = 0L;
    private int pendingSubtasksCount;
    private String summaryStatus = "none";
    private String enableStatus = "enabled";
    private String embeddingModelId = "";
    private String fileName;
    private String folderPath = "";
    private String fileType;
    private Long fileSize;
    private String fileHash;
    private String filePath;
    private long storageSize;
    /** 恒输出：NULL → "metadata":null */
    @TableField(typeHandler = PgJsonTypeHandler.class)
    private JsonNode metadata;
    @TableField(typeHandler = PgJsonTypeHandler.class)
    private JsonNode customMetadata;
    /** 恒输出：NULL → null */
    @TableField(typeHandler = PgJsonTypeHandler.class)
    private JsonNode lastFaqImportResult;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime processedAt;
    private String errorMessage = "";
    private OffsetDateTime deletedAt;
    /** 无列映射标签 查询回填（跨 KB 列表场景；恒输出，默认 ""） */
    @TableField(exist = false)
    private String knowledgeBaseName = "";

    public String getId() { return id; }
    public void setId(String v) { id = v; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long v) { tenantId = v; }
    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(String v) { knowledgeBaseId = v; }
    public List<JsonNode> getTags() { return tags; }
    public void setTags(List<JsonNode> v) { tags = v; }
    public String getType() { return type; }
    public void setType(String v) { type = v; }
    public String getTitle() { return title; }
    public void setTitle(String v) { title = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { description = v == null ? "" : v; }
    public String getSource() { return source; }
    public void setSource(String v) { source = v == null ? "" : v; }
    public String getChannel() { return channel; }
    public void setChannel(String v) { channel = v == null || v.isEmpty() ? "web" : v; }
    public String getParseStatus() { return parseStatus; }
    public void setParseStatus(String v) { parseStatus = v; }
    public Long getParseAttempt() { return parseAttempt == null ? 0L : parseAttempt; }
    public void setParseAttempt(Long v) { parseAttempt = v == null ? 0L : v; }
    public int getPendingSubtasksCount() { return pendingSubtasksCount; }
    public void setPendingSubtasksCount(int v) { pendingSubtasksCount = v; }
    public String getSummaryStatus() { return summaryStatus; }
    public void setSummaryStatus(String v) { summaryStatus = v; }
    public String getEnableStatus() { return enableStatus; }
    public void setEnableStatus(String v) { enableStatus = v; }
    public String getEmbeddingModelId() { return embeddingModelId; }
    public void setEmbeddingModelId(String v) { embeddingModelId = v == null ? "" : v; }
    public String getFileName() { return fileName; }
    public void setFileName(String v) { fileName = v; }
    public String getFolderPath() { return folderPath; }
    public void setFolderPath(String v) { folderPath = v == null ? "" : v; }
    public String getFileType() { return fileType; }
    public void setFileType(String v) { fileType = v; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long v) { fileSize = v; }
    public String getFileHash() { return fileHash; }
    public void setFileHash(String v) { fileHash = v; }
    public String getFilePath() { return filePath == null ? "" : filePath; }
    public void setFilePath(String v) { filePath = v; }
    public long getStorageSize() { return storageSize; }
    public void setStorageSize(long v) { storageSize = v; }
    public JsonNode getMetadata() { return metadata; }
    public void setMetadata(JsonNode v) { metadata = v; }
    public JsonNode getCustomMetadata() { return customMetadata; }
    public void setCustomMetadata(JsonNode v) { customMetadata = v; }
    public JsonNode getLastFaqImportResult() { return lastFaqImportResult; }
    public void setLastFaqImportResult(JsonNode v) { lastFaqImportResult = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { createdAt = v; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { updatedAt = v; }
    public OffsetDateTime getProcessedAt() { return processedAt; }
    public void setProcessedAt(OffsetDateTime v) { processedAt = v; }
    public String getErrorMessage() { return errorMessage == null ? "" : errorMessage; }
    public void setErrorMessage(String v) { errorMessage = v == null ? "" : v; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(OffsetDateTime v) { deletedAt = v; }
    public String getKnowledgeBaseName() { return knowledgeBaseName == null ? "" : knowledgeBaseName; }
    public void setKnowledgeBaseName(String v) { knowledgeBaseName = v == null ? "" : v; }

    /** deleting/cancelled 即中止 */
    @JsonIgnore
    public boolean isAborted() {
        return PARSE_DELETING.equals(parseStatus) || PARSE_CANCELLED.equals(parseStatus);
    }
}
