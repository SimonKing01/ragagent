package com.ragagent.contract.model;

import java.util.List;

/**
 * 文本向量化客户端接口。
 *
 * <p>调用不携带租户语义（租户由调用侧的 TenantContext 决定，接口不传）。</p>
 */
public interface Embedder {

    /** 单文本转向量。空结果时实现应报 {@code no embedding returned}。 */
    float[] embed(String text);

    /** 批量转向量，返回顺序与输入一致（由各 provider 保证）。 */
    List<float[]> batchEmbed(List<String> texts);

    String getModelName();

    int getDimensions();

    String getModelId();
}
