package com.ragagent.modelruntime.adapter.provider.asr;

import com.ragagent.contract.model.AsrTranscriber;
import com.ragagent.platform.security.SsrfGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * ASR 端口的路由：按 Base URL 选择协议实现。
 *
 * <ul>
 *   <li>百炼专属部署等 {@code /multimodal-generation}、{@code maas.aliyuncs.com}
 *       端点 → {@link DashScopeAsrTranscriber}（原生协议，音频走 base64）；</li>
 *   <li>其余（OpenAI 兼容 {@code /audio/transcriptions}）→ {@link OpenAiAsrTranscriber}。</li>
 * </ul>
 *
 * <p>为什么要路由：同一个 {@code ASR} 模型类型下，云端标准服务和专属实例的协议不同，
 * 之前只有 OpenAI 兼容一条路，配了专属实例就会 400「Model not exist.」。</p>
 */
@Primary
@Component
public class AsrTranscriberRouter implements AsrTranscriber {

    private final AsrTranscriber openAiCompatible;
    private final AsrTranscriber dashScopeNative;

    @Autowired
    public AsrTranscriberRouter(SsrfGuard ssrfGuard) {
        this(new OpenAiAsrTranscriber(ssrfGuard), new DashScopeAsrTranscriber(ssrfGuard));
    }

    AsrTranscriberRouter(AsrTranscriber openAiCompatible, AsrTranscriber dashScopeNative) {
        this.openAiCompatible = openAiCompatible;
        this.dashScopeNative = dashScopeNative;
    }

    @Override
    public TranscriptionResult transcribe(AsrConfig config, byte[] audioBytes, String fileName) {
        return select(config).transcribe(config, audioBytes, fileName);
    }

    /** 按 Base URL 选实现（空 URL 走 OpenAI 兼容，保持原报错口径）。 */
    AsrTranscriber select(AsrConfig config) {
        String baseUrl = config == null ? null : config.baseUrl();
        return DashScopeAsrTranscriber.supports(baseUrl) ? dashScopeNative : openAiCompatible;
    }
}
