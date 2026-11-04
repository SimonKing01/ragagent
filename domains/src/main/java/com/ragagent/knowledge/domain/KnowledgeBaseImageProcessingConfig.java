package com.ragagent.knowledge.domain;


/** 图像处理配置：图片 OCR 开关（modelId 为历史字段，保留兼容）。 */
public class KnowledgeBaseImageProcessingConfig {

    private String modelId = "";

    /**
     * 入库图片是否送 OCR。{@code null} 视为开启——存量配置没有该字段时默认开，
     * 与「图片 OCR 默认开启」的产品口径一致。
     */
    private Boolean ocrEnabled = Boolean.TRUE;

    public String getModelId() { return modelId; }
    public void setModelId(String v) { modelId = v == null ? "" : v; }

    public Boolean getOcrEnabled() { return ocrEnabled; }
    public void setOcrEnabled(Boolean v) { ocrEnabled = v; }

    /** 缺省开启：只有显式 false 才关闭。 */
    public boolean isOcrEnabled() { return ocrEnabled == null || ocrEnabled; }

}
