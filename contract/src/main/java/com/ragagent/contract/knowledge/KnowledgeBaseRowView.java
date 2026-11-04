package com.ragagent.contract.knowledge;

import com.ragagent.contract.knowledge.config.AsrConfigView;
import com.ragagent.contract.knowledge.config.ChunkingConfigView;
import com.ragagent.contract.knowledge.config.ImageProcessingConfigView;
import com.ragagent.contract.knowledge.config.IndexingStrategyView;
import com.ragagent.contract.knowledge.config.StorageConfigView;
import com.ragagent.contract.knowledge.config.StorageProviderConfigView;
import com.ragagent.contract.knowledge.config.VlmConfigView;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 知识库**行视图**（L1）：{@code KnowledgeCatalogPort} 知识库面（读 / 建 / 改 / 授权）+ KB API 线格式
 * （{@code KnowledgeBaseResponse.from}）的载荷。
 *
 * <p><b>为什么有它</b>：实体 {@code KnowledgeBase} 已归位 `knowledge/domain`（HANDOFF 判据 1）；
 * 跨域方（数据源 7 处、初始化 1 处、评估 1 处、会话 7 处）与 L1 的 {@code KnowledgeBaseResponse}/
 * {@code KnowledgeBaseViews} 只应经本视图。</p>
 *
 * <p><b>口径</b>：镜像实体全部 **34 字段**，访问器体与实体**逐字一致**（含归一语义）；字段序与实体一致
 * ⇒ 序列化等价（KB API 由 {@code kb-*.json} / {@code init-*.json} 金片钉住）。</p>
 *
 * <p><b>配置形状（归正）</b>：6 个 typed config 字段均为契约视图（纯 JDK）；领域形状
 * （{@code KnowledgeBase*Config}）与视图的互转在 `knowledge.support.KnowledgeBaseConfigMaps`。
 * 懒初始化 getter 的"null → 默认视图"与领域形状默认构造逐字对应。</p>
 */
public class KnowledgeBaseRowView {

    private String id;
    private String name = "";
    private String type = "";
    private boolean isTemporary;
    private String description;
    private Long tenantId;
    private String creatorId;
    private ChunkingConfigView chunkingConfig;
    private ImageProcessingConfigView imageProcessingConfig;
    private String embeddingModelId;
    private String summaryModelId;
    private VlmConfigView vlmConfig;
    private AsrConfigView asrConfig;
    private StorageProviderConfigView storageProviderConfig;
    private String storageBackendId;
    private StorageConfigView storageConfig;
    private String vectorStoreId;
    /** jsonb 原样（对象/数组/标量；null 表示无）。 */
    private Object extractConfig;
    /** jsonb 原样（对象/数组/标量；null 表示无）。 */
    private Object faqConfig;
    /** jsonb 原样（对象/数组/标量；null 表示无）。 */
    private Object questionGenerationConfig;
    /** jsonb 原样（对象/数组/标量；null 表示无）。 */
    private Object autoTagConfig;
    /** jsonb 原样（对象/数组/标量；null 表示无）。 */
    private Object wikiConfig;
    private IndexingStrategyView indexingStrategy;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime deletedAt;
    private boolean isPinned;
    private OffsetDateTime pinnedAt;
    private long knowledgeCount;
    private long chunkCount;
    private boolean isProcessing;
    private long processingCount;
    private long shareCount;
    private String creatorName;

    public String getId() { return id; }
    public void setId(String v) { id = v; }
    public String getName() { return name; }
    public void setName(String v) { name = v == null ? "" : v; }
    public String getType() { return type; }
    public void setType(String v) { type = v == null ? "" : v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { description = v; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long v) { tenantId = v; }
    public String getCreatorId() { return creatorId == null ? "" : creatorId; }
    public void setCreatorId(String v) { creatorId = v == null ? "" : v; }
    public String getEmbeddingModelId() { return embeddingModelId; }
    public void setEmbeddingModelId(String v) { embeddingModelId = v == null ? "" : v; }
    public String getSummaryModelId() { return summaryModelId; }
    public void setSummaryModelId(String v) { summaryModelId = v == null ? "" : v; }
    public StorageProviderConfigView getStorageProviderConfig() { return storageProviderConfig; }
    public void setStorageProviderConfig(StorageProviderConfigView v) { storageProviderConfig = v; }
    public String getStorageBackendId() { return storageBackendId; }
    public void setStorageBackendId(String v) { storageBackendId = v; }
    public String getVectorStoreId() { return vectorStoreId; }
    public void setVectorStoreId(String v) { vectorStoreId = v; }
    public Object getExtractConfig() { return extractConfig; }
    public void setExtractConfig(Object v) { extractConfig = v; }
    public Object getFaqConfig() { return faqConfig; }
    public void setFaqConfig(Object v) { faqConfig = v; }
    public Object getQuestionGenerationConfig() { return questionGenerationConfig; }
    public void setQuestionGenerationConfig(Object v) { questionGenerationConfig = v; }
    public Object getAutoTagConfig() { return autoTagConfig; }
    public void setAutoTagConfig(Object v) { autoTagConfig = v; }
    public Object getWikiConfig() { return wikiConfig; }
    public void setWikiConfig(Object v) { wikiConfig = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { createdAt = v; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { updatedAt = v; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(OffsetDateTime v) { deletedAt = v; }
    public OffsetDateTime getPinnedAt() { return pinnedAt; }
    public void setPinnedAt(OffsetDateTime v) { pinnedAt = v; }
    public long getKnowledgeCount() { return knowledgeCount; }
    public void setKnowledgeCount(long v) { knowledgeCount = v; }
    public long getChunkCount() { return chunkCount; }
    public void setChunkCount(long v) { chunkCount = v; }
    public long getProcessingCount() { return processingCount; }
    public void setProcessingCount(long v) { processingCount = v; }
    public long getShareCount() { return shareCount; }
    public void setShareCount(long v) { shareCount = v; }
    public String getCreatorName() { return creatorName; }
    public void setCreatorName(String v) { creatorName = v; }

    public boolean hasVectorStore() {
        return vectorStoreId != null && !vectorStoreId.isEmpty();
    }

    public Capabilities capabilities() {
        IndexingStrategyView s = getIndexingStrategy();
        return new Capabilities(s.vectorEnabled(), s.keywordEnabled(), s.wikiEnabled(),
                s.graphEnabled() && JsonValueSupport.boolAt(extractConfig, "enabled", false),
                "faq".equals(type));
    }

    public String getStorageProvider() {
        if (storageProviderConfig != null) {
            String p = storageProviderConfig.provider().toLowerCase().trim();
            if (!p.isEmpty() && !p.equals("__pending_env__")) {
                return p;
            }
        }
        return getStorageConfig().provider().toLowerCase().trim();
    }

    public record Capabilities(boolean vector, boolean keyword, boolean wiki, boolean graph, boolean faq) {}

    public boolean isIsTemporary() { return isTemporary; }

    public void setIsTemporary(boolean v) { isTemporary = v; }


    public ChunkingConfigView getChunkingConfig() {
        if (chunkingConfig == null) {
            chunkingConfig = new ChunkingConfigView(0, 0, null, null, false, 0, 0,
                    null, 0, null, null, List.of());
        }
        return chunkingConfig;
    }

    public void setChunkingConfig(ChunkingConfigView v) { chunkingConfig = v; }

    public ImageProcessingConfigView getImageProcessingConfig() {
        if (imageProcessingConfig == null) imageProcessingConfig = new ImageProcessingConfigView("", true);
        return imageProcessingConfig;
    }

    public void setImageProcessingConfig(ImageProcessingConfigView v) { imageProcessingConfig = v; }

    public VlmConfigView getVlmConfig() {
        if (vlmConfig == null) vlmConfig = new VlmConfigView(false, "", null, null, "", "", "");
        return vlmConfig;
    }

    public void setVlmConfig(VlmConfigView v) { vlmConfig = v; }

    public AsrConfigView getAsrConfig() {
        if (asrConfig == null) asrConfig = new AsrConfigView(false, "", "");
        return asrConfig;
    }

    public void setAsrConfig(AsrConfigView v) { asrConfig = v; }

    public void setStorageConfig(StorageConfigView v) { storageConfig = v; }

    public void setIndexingStrategy(IndexingStrategyView v) { indexingStrategy = v; }

    public boolean isIsPinned() { return isPinned; }

    public void setIsPinned(boolean v) { isPinned = v; }


    public boolean isIsProcessing() { return isProcessing; }

    public void setIsProcessing(boolean v) { isProcessing = v; }

    public StorageConfigView getStorageConfig() {
        if (storageConfig == null) {
            storageConfig = new StorageConfigView("", "", "", "", "", "", "", null, false, false);
        }
        return storageConfig;
    }

    public IndexingStrategyView getIndexingStrategy() {
        // 零值/缺省 → 默认（vector+keyword）；faq 且 faq_config 为 NULL → 保持零值
        if (indexingStrategy != null) {
            return indexingStrategy;
        }
        return "faq".equals(type) && faqConfig == null
                ? new IndexingStrategyView(false, false, false, false)
                : new IndexingStrategyView(true, true, false, false);
    }

    public void setStorageProvider(String provider) {
        this.storageProviderConfig = new StorageProviderConfigView(provider);
    }
}
