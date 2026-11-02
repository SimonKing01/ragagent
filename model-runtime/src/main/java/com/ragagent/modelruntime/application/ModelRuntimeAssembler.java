package com.ragagent.modelruntime.application;

import com.ragagent.contract.error.BizException;
import com.ragagent.contract.model.ChatConfig;
import com.ragagent.contract.model.Embedder;
import com.ragagent.contract.model.LlmChatClient;
import com.ragagent.contract.model.ModelCatalogPort;
import com.ragagent.contract.model.ModelCatalogPort.ModelRowView;
import com.ragagent.contract.model.ModelParameters;
import com.ragagent.contract.model.ModelRuntime;
import com.ragagent.contract.model.Reranker;
import com.ragagent.contract.model.VlmConfig;
import com.ragagent.contract.model.VlmPredictor;
import com.ragagent.embedding.EmbedderFactory;
import com.ragagent.llm.limiter.ConcurrencyGovernor;
import com.ragagent.llm.limiter.Release;
import com.ragagent.llm.ollama.OllamaService;
import com.ragagent.llm.provider.ProviderName;
import com.ragagent.llm.provider.ProviderRegistry;
import com.ragagent.modelruntime.adapter.provider.chat.common.ProviderAdapters;
import com.ragagent.modelruntime.adapter.provider.chat.common.ThinkingStrategies;
import com.ragagent.modelruntime.adapter.provider.chat.common.ThinkingStrategy;
import com.ragagent.modelruntime.adapter.provider.vlm.VlmClient;
import com.ragagent.modelruntime.adapter.provider.vlm.VlmHttpTransport;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.platform.security.CryptoService;
import com.ragagent.platform.security.SsrfGuard;
import com.ragagent.platform.tracing.decorators.LangfuseChatClient;
import com.ragagent.platform.tracing.decorators.LangfuseEmbedder;
import com.ragagent.platform.tracing.decorators.LangfuseReranker;
import com.ragagent.platform.tracing.decorators.LangfuseVlm;
import com.ragagent.rerank.RerankerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 模型运行时装配（{@link ModelRuntime} 的实现）：chat / embedding / rerank / vlm / asr 五类
 * 运行时客户端的构造归口，服务 {@code models/{id}/debug} 端点与 agent 引擎装配。
 *
 * <p><b>为什么在 model-runtime</b>：构造要用的都是本模块的部件（{@code LlmChatClients} /
 * {@code EmbedderFactory} / {@code RerankerFactory} / {@code OllamaService} / 限流器）；
 * 只有"模型行"与"凭据解密"是外部能力——前者走契约端口 {@link ModelCatalogPort}、
 * 后者走平台层 {@link CryptoService}。这样消费域只 import 契约面，不必看到本模块实现。</p>
 *
 * <p>两类取数口径（差别是契约，别统一）：</p>
 * <ul>
 *   <li><b>embedding / rerank</b> 走 {@code findModelOrEmpty}（带状态闸门：
 *       downloading → "model is currently downloading" 等）；</li>
 *   <li><b>chat / vlm / asr</b> 走 {@code findModelVisible} 直取（无状态闸门）。</li>
 * </ul>
 *
 * <p>错误形态：调用方（DebugModel）把异常消息写进 {@code data.error}（HTTP 200）。
 * 因此本类抛出的 {@link RuntimeException} 的 {@code getMessage()} 即对外文案
 * （错误文案是契约）；底层部件抛 {@link BizException} 时在此拆包取其 message
 * （BizException 的 getMessage 带 "error code: ..." 前缀，不能直接当对外文案用）。</p>
 */
@Component
public class ModelRuntimeAssembler implements ModelRuntime {

    private static final Logger log = LoggerFactory.getLogger(ModelRuntimeAssembler.class);

    private final ModelCatalogPort modelCatalog;
    private final CryptoService cryptoService;
    private final ObjectProvider<OllamaService> ollamaService;
    private final ConcurrencyGovernor concurrencyGovernor;
    private final SsrfGuard ssrfGuard;

    /** VLM 出站通道（无状态，进程内复用）。 */
    private final VlmClient.Transport vlmTransport = new VlmHttpTransport();

    public ModelRuntimeAssembler(ModelCatalogPort modelCatalog,
                               CryptoService cryptoService, ObjectProvider<OllamaService> ollamaService,
                               ConcurrencyGovernor concurrencyGovernor, SsrfGuard ssrfGuard) {
        this.modelCatalog = modelCatalog;
        this.cryptoService = cryptoService;
        this.ollamaService = ollamaService;
        this.concurrencyGovernor = concurrencyGovernor;
        this.ssrfGuard = ssrfGuard;
    }

    /** 直取模型行（无状态闸门）→ chat 客户端装配。 */
    @Override
    public LlmChatClient getChatModel(String modelId) {
        ModelRowView model = getModelDirect(modelId);
        log.info("Getting chat model: {}, source: {}", model.name(), model.source());
        try {
            // langfuse generation 装饰（未启用时原样返回，零成本）
            return LangfuseChatClient.wrap(chatClientFor(model));
        } catch (BizException e) {
            throw new RuntimeException(e.appError().message());
        }
    }

    /** 带状态闸门取行 → embedder 工厂装配。 */
    @Override
    public Embedder getEmbeddingModel(String modelId) {
        ModelRowView model = getModelGated(modelId);
        log.info("Getting embedding model: {}, source: {}", model.name(), model.source());
        try {
            // pooler 只服务批量向量化；debug 只走单文本 embed，工厂面传 null
            // langfuse generation 装饰
            return LangfuseEmbedder.wrap(embedderFor(model));
        } catch (BizException e) {
            throw new RuntimeException(e.appError().message());
        }
    }

    /** 带状态闸门取行 → reranker 工厂装配。 */
    @Override
    public Reranker getRerankModel(String modelId) {
        ModelRowView model = getModelGated(modelId);
        log.info("Getting rerank model: {}, source: {}", model.name(), model.source());
        try {
            // langfuse generation 装饰
            return LangfuseReranker.wrap(rerankerFor(model));
        } catch (BizException e) {
            throw new RuntimeException(e.appError().message());
        }
    }

    /** 直取（无状态闸门）。调用方再 configFromModel + predict。 */
    @Override
    public ModelRowView getVlmModel(String modelId) {
        ModelRowView model = getModelDirect(modelId);
        log.info("Getting VLM model: {}, source: {}", model.name(), model.source());
        return model;
    }

    /**
     * VLM 客户端配置：构造 + 非 ollama 的基址 SSRF 校验
     * （validateVLMBaseURL；ollama 不校验基址）。
     *
     * <p>失败抛 {@link RuntimeException}，message 即对外错误文案——调用方
     * （模型调试端点、agent 引擎装配）直接写进 {@code data.error}。</p>
     */
    @Override
    public VlmConfig vlmConfigFor(ModelRowView model) {
        VlmConfig config = ModelRuntimeConfigs.vlmConfig(model);
        if (!config.isOllama()) {
            validateVlmBaseUrl(config.baseUrl());
        }
        return config;
    }

    /**
 * VLM 预测器：配置（含 SSRF 校验）→ 观测装饰 → 命名并发闸门 → 真实 provider 往返。
 *
 * <p>装饰顺序与搬迁前一致：闸门在最外层（等待不计入 langfuse 计时）。</p>
 *
 * <p><b>实现边界（随 从 VlmDescriberWiring 迁来）</b>：没有批级 60s 超时——
 * Java 侧调用是阻塞的、没有协作取消通道，实际超时由 {@code VLM_HTTP_TIMEOUT_SECONDS}
 * （缺省 180s）在 HTTP 层兜底。</p>
 */
    @Override
    public VlmPredictor vlmPredictorFor(ModelRowView model) {
        VlmConfig config = vlmConfigFor(model);
        int limit = model.parameters() == null ? 0 : model.parameters().getMaxConcurrency();
        String modelName = model.name() == null ? "" : model.name();
        String modelId = model.id() == null ? "" : model.id();
        LangfuseVlm.PredictFn predict = LangfuseVlm.wrap(
                (images, prompt) -> VlmClient.predict(config, vlmTransport, images, prompt),
                modelName, modelId);
        return (imgBytes, prompt) -> {
            try (Release release = concurrencyGovernor.gateNamedN(modelId, modelName, limit)) {
                try {
                    return predict.predict(new byte[][] {imgBytes}, prompt);
                } catch (Exception e) {
                    throw new RuntimeException(
                            e.getMessage() == null ? e.toString() : e.getMessage(), e);
                }
            }
        };
    }

    /** 直取（无状态闸门）。调用方再组 AsrConfig + transcribe。 */
    @Override
    public ModelRowView getAsrModel(String modelId) {
        ModelRowView model = getModelDirect(modelId);
        log.info("Getting ASR model: {}, source: {}", model.name(), model.source());
        return model;
    }

    /**
     * 构造期校验（validateVLMBaseURL）：SSRF 失败文案
     * "base URL SSRF check failed: ..."。
     */
    @Override
    public void validateVlmBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isEmpty()) {
            return;
        }
        try {
            ssrfGuard.validateURLForSSRF(baseUrl);
        } catch (RuntimeException e) {
            throw new RuntimeException("base URL SSRF check failed: " + e.getMessage(), e);
        }
    }

    /** 解析生效的思考控制（provider 能力 + extra_config 覆盖）；观测/调试面用。 */
    @Override
    public String effectiveThinkingControl(ChatConfig config) {
        if (config == null) {
            return "none";
        }
        ThinkingStrategy override = ThinkingStrategies.parseThinkingOverride(config.getExtraConfig());
        if (override != null) {
            return override.name();
        }
        // provider 为空串才探测回落；非空但未知名（fromValue=null）不回落
        // ——resolve(null) 无命中 → BaseProvider
        String pv = config.getProvider() == null ? "" : config.getProvider();
        ProviderName name = pv.isEmpty()
                ? ProviderRegistry.detectProvider(config.getBaseUrl())
                : ProviderName.fromValue(pv);
        return ProviderAdapters.resolve(name, config.getModelName()).thinking().name();
    }

    // ── 按行视图的工厂面（不做取数、不挂观测装饰）──────────────────────────

    /** 按行视图造聊天客户端：密钥/基址/来源取自行参数，appSecret 在此解密。 */
    @Override
    public LlmChatClient chatClientFor(ModelRowView model) {
        String[] creds = modelCredentials(model.parameters());
        return LlmChatClients.create(ModelRuntimeConfigs.chatConfig(model, creds[0], creds[1]),
                ollamaService.getIfAvailable(), concurrencyGovernor);
    }

    /** 按行视图造嵌入客户端（池化留给需要它的内部路径）。 */
    @Override
    public Embedder embedderFor(ModelRowView model) {
        String[] creds = modelCredentials(model.parameters());
        return EmbedderFactory.newEmbedder(ModelRuntimeConfigs.embedderConfig(model, creds[0], creds[1]),
                null, ollamaService.getIfAvailable(), concurrencyGovernor);
    }

    /** 按行视图造重排客户端。 */
    @Override
    public Reranker rerankerFor(ModelRowView model) {
        String[] creds = modelCredentials(model.parameters());
        return RerankerFactory.newReranker(ModelRuntimeConfigs.rerankerConfig(model, creds[0], creds[1]));
    }

    /** 按行视图取聊天配置。 */
    @Override
    public ChatConfig chatConfigFor(ModelRowView model) {
        String[] creds = modelCredentials(model.parameters());
        return ModelRuntimeConfigs.chatConfig(model, creds[0], creds[1]);
    }

    // ── 取数口径 ─────────────────────────────────────────────────────────

    /** 租户可见模型行（含 is_builtin；无状态闸门）。 */
    private ModelRowView getModelDirect(String modelId) {
        if (modelId == null || modelId.isEmpty()) {
            throw new RuntimeException("model ID cannot be empty");
        }
        long tid = TenantContext.currentTenantId() == null ? 0 : TenantContext.currentTenantId();
        ModelRowView model = modelCatalog.findModelVisible(tid, modelId);
        if (model == null) {
            throw new RuntimeException("model not found");
        }
        return model;
    }

    /**
     * 带状态闸门取行，错误文案拆包为对外原文。
     *
     * <p>{@code findModelOrEmpty} 只把"行不存在"收成空（别的失败照抛），
     * 状态闸门的 BizException 因此原样冒出来，在此拆成对外文案。</p>
     */
    private ModelRowView getModelGated(String modelId) {
        if (modelId == null || modelId.isEmpty()) {
            throw new RuntimeException("model ID cannot be empty");
        }
        try {
            return modelCatalog.findModelOrEmpty(modelId)
                    .orElseThrow(() -> new RuntimeException("model not found"));
        } catch (BizException e) {
            throw new RuntimeException(e.appError().message());
        }
    }

    // ── 模型级凭证 ───────────────────────────────────────────────────────

    /** 模型级 appId/appSecret（通用凭据承载；cloud provider 已裁撤，无租户回落）。 */
    private String[] modelCredentials(ModelParameters params) {
        String appId = params == null || params.getAppId() == null ? "" : params.getAppId();
        String appSecret = decryptAppSecret(params == null ? null : params.getAppSecret());
        return new String[] {appId, appSecret};
    }

    /** 空原样返回；宽容解密（失败原样返回）。 */
    private String decryptAppSecret(String encrypted) {
        if (encrypted == null || encrypted.isEmpty()) {
            return encrypted;
        }
        var decrypted = cryptoService.decryptStoredSecretLenient(encrypted);
        return decrypted.ok() ? decrypted.plaintext() : encrypted;
    }
}
