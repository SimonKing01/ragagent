package com.ragagent.embedding.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.embedding.EmbedderPooler;
import com.ragagent.embedding.EmbeddingHttp;
import com.ragagent.embedding.EmbeddingVectors;
import com.ragagent.platform.web.ProviderJson;
import java.time.Duration;
import java.util.List;

/**
 * 智谱 embedding 客户端。
 *
 * <p>请求体字段序固定（model/input/dimensions/
 * truncate_prompt_tokens）；无 encoding_format。
 * 默认 base = 智谱 embedding 端点。</p>
 */
public final class ZhipuEmbedder extends BaseEmbedder {

    /** 与 {@code ProviderBaseURLs.ZHIPU_EMBEDDING_BASE_URL} 一致。 */
    public static final String ZHIPU_EMBEDDING_BASE_URL = "https://open.bigmodel.cn/api/paas/v4";

    private final String baseUrl;
    private final Duration timeout = EmbeddingHttp.DEFAULT_TIMEOUT;

    public ZhipuEmbedder(String apiKey, String baseUrl, String modelName,
                         int truncatePromptTokens, int dimensions, String modelId,
                         EmbedderPooler pooler) {
        super(modelName, truncatePromptTokens, dimensions, modelId, pooler);
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = ZHIPU_EMBEDDING_BASE_URL;
        }
        if (modelName == null || modelName.isEmpty()) {
            throw new EmbeddingHttp.EmbeddingException("model name is required");
        }
        if (truncatePromptTokens == 0) {
            truncatePromptTokens = 511;
        }
        this.truncatePromptTokens = truncatePromptTokens;
        EmbeddingHttp.validateEmbeddingBaseUrl(baseUrl);
        this.baseUrl = baseUrl;
        setApiKey(apiKey);
    }

    @Override
    public List<float[]> batchEmbed(List<String> texts) {
        ObjectNode reqBody = ProviderJson.object();
        reqBody.put("model", modelName);
        reqBody.set("input", ProviderJson.arrayOfStrings(texts));
        if (supportsDimensionsParam()) {
            reqBody.put("dimensions", dimensions);
        }
        reqBody.put("truncate_prompt_tokens", truncatePromptTokens);
        byte[] jsonData = ProviderJson.marshal(reqBody);

        EmbeddingHttp.Result resp;
        try {
            resp = EmbeddingHttp.postWithRetry(baseUrl + "/embeddings", jsonData,
                    "Authorization", "Bearer " + apiKey, customHeaders, timeout);
        } catch (EmbeddingHttp.EmbeddingException e) {
            throw new EmbeddingHttp.EmbeddingException("send request: " + e.getMessage(), e);
        }

        if (resp.status() != 200) {
            throw new EmbeddingHttp.EmbeddingException("BatchEmbed API error: Http Status "
                    + resp.statusLine() + ", Response: " + truncateBody(resp.bodyText()));
        }

        JsonNode response = ProviderJson.parse(resp.bodyText());
        if (response == null) {
            throw new EmbeddingHttp.EmbeddingException("unmarshal response: "
                    + resp.bodyText());
        }
        return EmbeddingVectors.fromData(response.path("data"), resp.bodyText(), texts.size());
    }
}
