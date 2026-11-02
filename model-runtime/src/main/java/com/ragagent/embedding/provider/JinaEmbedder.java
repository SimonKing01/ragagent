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
 * Jina AI embedding 客户端。
 *
 * <p>Jina 与 OpenAI 兼容但<b>不支持</b> {@code truncate_prompt_tokens}
 * （不存 truncatePromptTokens）；用 {@code truncate:true} 布尔
 * 开启长文本截断；{@code dimensions} 仅在 supportsDimensionsParam 时出现。</p>
 */
public final class JinaEmbedder extends BaseEmbedder {

    private final String baseUrl;
    private final Duration timeout = EmbeddingHttp.DEFAULT_TIMEOUT;

    public JinaEmbedder(String apiKey, String baseUrl, String modelName,
                        int truncatePromptTokens, int dimensions, String modelId,
                        EmbedderPooler pooler) {
        super(modelName, truncatePromptTokens, dimensions, modelId, pooler);
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = "https://api.jina.ai/v1";
        }
        if (modelName == null || modelName.isEmpty()) {
            throw new EmbeddingHttp.EmbeddingException("model name is required");
        }
        EmbeddingHttp.validateEmbeddingBaseUrl(baseUrl);
        this.baseUrl = baseUrl;
        setApiKey(apiKey);
    }

    @Override
    public List<float[]> batchEmbed(List<String> texts) {
        // 请求体字段序：model/input/truncate/dimensions；truncate:true 恒发
        ObjectNode reqBody = ProviderJson.object();
        reqBody.put("model", modelName);
        reqBody.set("input", ProviderJson.arrayOfStrings(texts));
        reqBody.put("truncate", true);
        if (supportsDimensionsParam()) {
            reqBody.put("dimensions", dimensions);
        }
        byte[] jsonData = ProviderJson.marshal(reqBody);

        EmbeddingHttp.Result resp;
        try {
            resp = EmbeddingHttp.postWithRetry(baseUrl + "/embeddings", jsonData,
                    "Authorization", "Bearer " + apiKey, customHeaders, timeout);
        } catch (EmbeddingHttp.EmbeddingException e) {
            throw new EmbeddingHttp.EmbeddingException("send request: " + e.getMessage(), e);
        }

        if (resp.status() != 200) {
            throw new EmbeddingHttp.EmbeddingException("EmbedBatch API error: Http Status "
                    + resp.statusLine());
        }

        JsonNode response = ProviderJson.parse(resp.bodyText());
        if (response == null) {
            throw new EmbeddingHttp.EmbeddingException("unmarshal response: "
                    + resp.bodyText());
        }
        return EmbeddingVectors.fromData(response.path("data"), resp.bodyText(), texts.size());
    }
}
