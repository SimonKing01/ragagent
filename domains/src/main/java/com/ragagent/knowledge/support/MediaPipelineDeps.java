package com.ragagent.knowledge.support;

import com.ragagent.contract.model.AsrTranscriber;
import com.ragagent.contract.model.ModelCatalogPort;
import com.ragagent.contract.model.ModelRuntime;
import com.ragagent.knowledge.repository.KnowledgeBaseRepository;
import com.ragagent.knowledge.repository.KnowledgeRepository;

/**
 * 音视频入库所需的依赖集合。
 *
 * <p>拆成一组是为了守住「构造器参数 ≤7」的卫生预算：转写器本身只需要
 * {@code (图片存储, 这组依赖)} 两个参数。</p>
 */
public record MediaPipelineDeps(
        ModelCatalogPort modelCatalog,
        ModelRuntime modelRuntime,
        AsrTranscriber asrTranscriber,
        KnowledgeBaseRepository kbRepository,
        KnowledgeRepository knowledgeRepository,
        FfmpegMedia media,
        int maxDurationSeconds) {
}
