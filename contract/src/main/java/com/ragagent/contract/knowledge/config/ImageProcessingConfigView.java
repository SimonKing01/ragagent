package com.ragagent.contract.knowledge.config;

/** 图像处理配置视图：modelId 为历史字段；ocrEnabled 控制入库图片是否送 OCR（缺省开启）。 */
public record ImageProcessingConfigView(String modelId, Boolean ocrEnabled) {
}
