package com.ragagent.knowledge.support;

import com.ragagent.contract.knowledge.config.AsrConfigView;
import com.ragagent.contract.knowledge.config.ChunkingConfigView;
import com.ragagent.contract.knowledge.config.ImageProcessingConfigView;
import com.ragagent.contract.knowledge.config.IndexingStrategyView;
import com.ragagent.contract.knowledge.config.StorageConfigView;
import com.ragagent.contract.knowledge.config.StorageProviderConfigView;
import com.ragagent.contract.knowledge.config.VlmConfigView;
import com.ragagent.knowledge.domain.KnowledgeBaseAsrConfig;
import com.ragagent.knowledge.domain.KnowledgeBaseChunkingConfig;
import com.ragagent.knowledge.domain.KnowledgeBaseImageProcessingConfig;
import com.ragagent.knowledge.domain.KnowledgeBaseIndexingStrategy;
import com.ragagent.knowledge.domain.KnowledgeBaseStorageConfig;
import com.ragagent.knowledge.domain.KnowledgeBaseStorageProviderConfig;
import com.ragagent.knowledge.domain.KnowledgeBaseVlmConfig;
import java.util.List;

/**
 * 知识库配置形状的双向映射（领域形状 ⇄ 契约视图）——域内单一实现。
 *
 * <p>领域形状（{@code KnowledgeBase*Config}，jsonb 列类型）归 {@code knowledge/domain}；
 * 跨域视图（{@code *View}）归契约面。互转只在本类发生。</p>
 *
 * <p><b>VLM 与凭据</b>：{@link VlmConfigView} 按"凭据只进不出"设计不含 {@code apiKey}
 * ⇒ {@link #toDomain(VlmConfigView)} 产出的领域形状 {@code apiKey == null}；
 * 写回数据库前由调用侧与库中现值合并（见 {@code KnowledgeCatalogAdapter.updateKnowledgeBase}）。</p>
 */
public final class KnowledgeBaseConfigMaps {

    private KnowledgeBaseConfigMaps() {
    }

    // ── ASR ──

    /** 领域形状 → 视图（null 透传）。 */
    public static AsrConfigView toView(KnowledgeBaseAsrConfig c) {
        return c == null ? null : new AsrConfigView(c.isEnabled(), c.getModelId(), c.getLanguage());
    }

    public static KnowledgeBaseAsrConfig toDomain(AsrConfigView v) {
        if (v == null) {
            return null;
        }
        KnowledgeBaseAsrConfig c = new KnowledgeBaseAsrConfig();
        c.setEnabled(v.enabled());
        c.setModelId(v.modelId());
        c.setLanguage(v.language());
        return c;
    }

    // ── 分块 ──

    public static ChunkingConfigView toView(KnowledgeBaseChunkingConfig c) {
        if (c == null) {
            return null;
        }
        List<ChunkingConfigView.ParserEngineRuleView> rules = c.getParserEngineRules() == null ? null
                : c.getParserEngineRules().stream()
                        .map(r -> new ChunkingConfigView.ParserEngineRuleView(
                                r.getFileTypes(), r.getEngine(), r.getXlsxFirstRowAsHeader()))
                        .toList();
        return new ChunkingConfigView(c.getChunkSize(), c.getChunkOverlap(), c.getSeparators(),
                rules, c.isEnableParentChild(), c.getParentChunkSize(), c.getChildChunkSize(),
                c.getStrategy(), c.getTokenLimit(), c.getLanguages(),
                c.getTableMetadataInstructions(), c.ingestionInactiveKeys());
    }

    public static KnowledgeBaseChunkingConfig toDomain(ChunkingConfigView v) {
        if (v == null) {
            return null;
        }
        KnowledgeBaseChunkingConfig c = new KnowledgeBaseChunkingConfig();
        c.setChunkSize(v.chunkSize());
        c.setChunkOverlap(v.chunkOverlap());
        c.setSeparators(v.separators());
        if (v.parserEngineRules() != null) {
            c.setParserEngineRules(v.parserEngineRules().stream().map(r -> {
                KnowledgeBaseChunkingConfig.ParserEngineRule rule =
                        new KnowledgeBaseChunkingConfig.ParserEngineRule();
                rule.setFileTypes(r.fileTypes());
                rule.setEngine(r.engine());
                rule.setXlsxFirstRowAsHeader(r.xlsxFirstRowAsHeader());
                return rule;
            }).toList());
        }
        c.setEnableParentChild(v.enableParentChild());
        c.setParentChunkSize(v.parentChunkSize());
        c.setChildChunkSize(v.childChunkSize());
        c.setStrategy(v.strategy());
        c.setTokenLimit(v.tokenLimit());
        c.setLanguages(v.languages());
        c.setTableMetadataInstructions(v.tableMetadataInstructions());
        return c;
    }

    // ── 图像处理 ──

    public static ImageProcessingConfigView toView(KnowledgeBaseImageProcessingConfig c) {
        return c == null ? null : new ImageProcessingConfigView(c.getModelId(), c.isOcrEnabled());
    }

    public static KnowledgeBaseImageProcessingConfig toDomain(ImageProcessingConfigView v) {
        if (v == null) {
            return null;
        }
        KnowledgeBaseImageProcessingConfig c = new KnowledgeBaseImageProcessingConfig();
        c.setModelId(v.modelId());
        c.setOcrEnabled(v.ocrEnabled());
        return c;
    }

    // ── 索引策略 ──

    public static IndexingStrategyView toView(KnowledgeBaseIndexingStrategy s) {
        return s == null ? null
                : new IndexingStrategyView(s.isVectorEnabled(), s.isKeywordEnabled(),
                        s.isWikiEnabled(), s.isGraphEnabled());
    }

    public static KnowledgeBaseIndexingStrategy toDomain(IndexingStrategyView v) {
        if (v == null) {
            return null;
        }
        KnowledgeBaseIndexingStrategy s = new KnowledgeBaseIndexingStrategy();
        s.setVectorEnabled(v.vectorEnabled());
        s.setKeywordEnabled(v.keywordEnabled());
        s.setWikiEnabled(v.wikiEnabled());
        s.setGraphEnabled(v.graphEnabled());
        return s;
    }

    // ── VLM ──

    public static VlmConfigView toView(KnowledgeBaseVlmConfig c) {
        if (c == null) {
            return null;
        }
        return new VlmConfigView(c.isEnabled(), c.getModelId(), c.getDescriptionLanguage(),
                c.getCustomInstructions(), c.getModelName(), c.getBaseUrl(), c.getInterfaceType());
    }

    /** 视图 → 领域形状；{@code apiKey} 不在视图内 ⇒ 结果该字段为 {@code null}（写回前需与库中现值合并）。 */
    public static KnowledgeBaseVlmConfig toDomain(VlmConfigView v) {
        if (v == null) {
            return null;
        }
        KnowledgeBaseVlmConfig c = new KnowledgeBaseVlmConfig();
        c.setEnabled(v.enabled());
        c.setModelId(v.modelId());
        c.setDescriptionLanguage(v.descriptionLanguage());
        c.setCustomInstructions(v.customInstructions());
        c.setModelName(v.modelName());
        c.setBaseUrl(v.baseUrl());
        c.setInterfaceType(v.interfaceType());
        return c;
    }

    // ── 存储 ──

    public static StorageConfigView toView(KnowledgeBaseStorageConfig c) {
        if (c == null) {
            return null;
        }
        return new StorageConfigView(c.getSecretId(), c.getSecretKey(), c.getRegion(),
                c.getBucketName(), c.getAppId(), c.getPathPrefix(), c.getProvider(),
                c.getEndpoint(), c.isUseSsl(), c.isForcePathStyle());
    }

    public static KnowledgeBaseStorageConfig toDomain(StorageConfigView v) {
        if (v == null) {
            return null;
        }
        KnowledgeBaseStorageConfig c = new KnowledgeBaseStorageConfig();
        c.setSecretId(v.secretId());
        c.setSecretKey(v.secretKey());
        c.setRegion(v.region());
        c.setBucketName(v.bucketName());
        c.setAppId(v.appId());
        c.setPathPrefix(v.pathPrefix());
        c.setProvider(v.provider());
        c.setEndpoint(v.endpoint());
        c.setUseSsl(v.useSsl());
        c.setForcePathStyle(v.forcePathStyle());
        return c;
    }

    // ── 存储提供方 ──

    public static StorageProviderConfigView toView(KnowledgeBaseStorageProviderConfig c) {
        return c == null ? null : new StorageProviderConfigView(c.getProvider());
    }

    public static KnowledgeBaseStorageProviderConfig toDomain(StorageProviderConfigView v) {
        if (v == null) {
            return null;
        }
        KnowledgeBaseStorageProviderConfig c = new KnowledgeBaseStorageProviderConfig();
        c.setProvider(v.provider());
        return c;
    }
}
