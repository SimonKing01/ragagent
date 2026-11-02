package com.ragagent.platform.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ragagent.contract.model.PromptCacheStatus;
import java.util.List;
import java.util.Map;


/**
 * 非事件面契约载荷的 Jackson mixin（error/model/retrieval/tenant/tool/vectorstore/wiki）。
 *
 * <p>为什么独立成文件：契约面（{@code contract.*}）要求<b>纯 JDK</b>——载荷类的线格式由
 * 这里的 mixin 承载（Jackson 只在读写时把注解套上去），契约类本身零框架引用。</p>
 */
public final class ContractJsonMixinsCore {

    private ContractJsonMixinsCore() {
    }

    /** AppError 的 mixin（注解逐条对位，行为与搬迁前一致）。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public abstract static class AppErrorMixin {
    }

    /** TokenUsage 的 mixin（注解逐条对位，行为与搬迁前一致）。 */
    public abstract static class TokenUsageMixin {
        @JsonProperty("prompt_tokens")
        private int promptTokens;

        @JsonProperty("completion_tokens")
        private int completionTokens;

        @JsonProperty("total_tokens")
        private int totalTokens;

        @JsonProperty("cached_tokens")
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        private int cachedTokens;

        @JsonProperty("cache_read_tokens")
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        private int cacheReadTokens;

        @JsonProperty("cache_write_tokens")
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        private int cacheWriteTokens;

        @JsonProperty("cache_miss_tokens")
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        private int cacheMissTokens;

        @JsonProperty("cache_reported")
        private boolean cacheReported;

        @JsonProperty("cache_status")
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        private PromptCacheStatus cacheStatus;

    }

    /** SearchParams 的 mixin（注解逐条对位，行为与搬迁前一致）。 */
    public abstract static class SearchParamsMixin {
        @JsonProperty("query_text")
        private String queryText;

        @JsonProperty("query_embedding")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private float[] queryEmbedding;

        @JsonProperty("vector_threshold")
        private double vectorThreshold;

        @JsonProperty("keyword_threshold")
        private double keywordThreshold;

        @JsonProperty("match_count")
        private int matchCount;

        @JsonProperty("disable_keywords_match")
        private boolean disableKeywordsMatch;

        @JsonProperty("disable_vector_match")
        private boolean disableVectorMatch;

        @JsonProperty("knowledge_ids")
        private List<String> knowledgeIds;

        @JsonProperty("tag_ids")
        private List<String> tagIds;

        @JsonProperty("scope_tag_ids")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private List<String> scopeTagIds;

        @JsonProperty("only_recommended")
        private boolean onlyRecommended;

        @JsonProperty("knowledge_base_ids")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private List<String> knowledgeBaseIds;

        @JsonProperty("skip_context_enrichment")
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        private boolean skipContextEnrichment;

    }

    /** WebSearchConfig 的 mixin（注解逐条对位，行为与搬迁前一致）。 */
    public abstract static class WebSearchConfigMixin {
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        @JsonProperty("provider")
        private String provider;

        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        @JsonProperty("api_key")
        private String apiKey;

        @JsonProperty("max_results")
        private int maxResults;

        @JsonProperty("include_date")
        private boolean includeDate;

        @JsonProperty("compression_method")
        private String compressionMethod;

        @JsonProperty("blacklist")
        private List<String> blacklist;

        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        @JsonProperty("embedding_model_id")
        private String embeddingModelId;

        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        @JsonProperty("embedding_dimension")
        private int embeddingDimension;

        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        @JsonProperty("rerank_model_id")
        private String rerankModelId;

        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        @JsonProperty("document_fragments")
        private int documentFragments;

        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        @JsonProperty("proxy_url")
        private String proxyUrl;

    }

    /** ToolResult 的 mixin（注解逐条对位，行为与搬迁前一致）。 */
    @JsonIgnoreProperties(value = {"outputFiles"})
    public abstract static class ToolResultMixin {
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        private Map<String, Object> data;

        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        private String error;

        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        private List<String> images;

    }

    /** ConnectionConfig 的 mixin（注解逐条对位，行为与搬迁前一致）。 */
    @JsonIgnoreProperties(value = {"endpoint"})
    public abstract static class ConnectionConfigMixin {
    }

    /** IndexConfig 的 mixin（注解逐条对位，行为与搬迁前一致）。 */
    public abstract static class IndexConfigMixin {
    }

    /** ExtractedItem 的 mixin（注解逐条对位，行为与搬迁前一致）。 */
    public abstract static class ExtractedItemMixin {
        @JsonProperty("name")
        private String name;

        @JsonProperty("slug")
        private String slug;

        @JsonProperty("aliases")
        private List<String> aliases;

        @JsonProperty("description")
        private String description;

        @JsonProperty("details")
        private String details;

        @JsonProperty("source_chunks")
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        private List<String> sourceChunks;

    }

    /** SlugUpdate 的 mixin（注解逐条对位，行为与搬迁前一致）。 */
    @JsonIgnoreProperties(value = {"retractType"})
    public abstract static class SlugUpdateMixin {
    }


    /** 解析引擎配置段（ParserEngineConfig）线格式（去框架化契约类的注解载体）。 */
    public abstract static class ParserEngineConfigMixin {
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        @JsonProperty("chat_parser_engine_rules")
        private List<Object> chatParserEngineRules;
        @JsonProperty("mineru_endpoint")
        private String mineruEndpoint;
        @JsonProperty("mineru_api_key")
        private String mineruApiKey;
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        @JsonProperty("mineru_model")
        private String mineruModel;
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        @JsonProperty("mineru_vlm_server_url")
        private String mineruVlmServerUrl;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("mineru_enable_formula")
        private Boolean mineruEnableFormula;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("mineru_enable_table")
        private Boolean mineruEnableTable;
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        @JsonProperty("mineru_parse_method")
        private String mineruParseMethod;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("mineru_enable_ocr")
        private Boolean mineruEnableOcr;
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        @JsonProperty("mineru_language")
        private String mineruLanguage;
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        @JsonProperty("mineru_cloud_model")
        private String mineruCloudModel;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("mineru_cloud_enable_formula")
        private Boolean mineruCloudEnableFormula;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("mineru_cloud_enable_table")
        private Boolean mineruCloudEnableTable;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("mineru_cloud_enable_ocr")
        private Boolean mineruCloudEnableOcr;
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        @JsonProperty("mineru_cloud_language")
        private String mineruCloudLanguage;
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        @JsonProperty("odl_hybrid")
        private String odlHybrid;
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        @JsonProperty("odl_hybrid_url")
        private String odlHybridUrl;
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        @JsonProperty("odl_hybrid_mode")
        private String odlHybridMode;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("odl_hybrid_fallback")
        private Boolean odlHybridFallback;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("odl_markdown_with_html")
        private Boolean odlMarkdownWithHtml;
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        @JsonProperty("paddleocr_vl_endpoint")
        private String paddleOcrVlEndpoint;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("paddleocr_vl_use_seal_recognition")
        private Boolean paddleOcrVlUseSealRecognition;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("paddleocr_vl_use_chart_recognition")
        private Boolean paddleOcrVlUseChartRecognition;
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        @JsonProperty("paddleocr_vl_cloud_token")
        private String paddleOcrVlCloudToken;
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        @JsonProperty("paddleocr_vl_cloud_model")
        private String paddleOcrVlCloudModel;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("paddleocr_vl_cloud_use_seal_recognition")
        private Boolean paddleOcrVlCloudUseSealRecognition;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("paddleocr_vl_cloud_use_chart_recognition")
        private Boolean paddleOcrVlCloudUseChartRecognition;
    }

}
