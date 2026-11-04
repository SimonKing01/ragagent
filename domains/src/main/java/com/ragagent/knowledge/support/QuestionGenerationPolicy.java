package com.ragagent.knowledge.support;

import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;

/**
 * 推荐问题是否生成的判定：逐文档覆盖优先，其次知识库级配置。
 *
 * <p>上传确认弹窗里的「生成推荐问题」开关会随 processConfig 落到文档
 * {@code metadata.processOverrides.questionGenerationConfig}；只读 KB 级配置会让
 * 用户在弹窗里关掉、实际仍生成。</p>
 */
public final class QuestionGenerationPolicy {

    private QuestionGenerationPolicy() {
    }

    /** 该文档是否要生成推荐问题。 */
    public static boolean enabled(Knowledge k, KnowledgeBase kb) {
        Boolean override = k == null ? null
                : KnowledgeMetadataOverrides.questionGenerationEnabled(k.getMetadata());
        if (override != null) {
            return override;
        }
        return kb != null && kb.getQuestionGenerationConfig() != null
                && kb.getQuestionGenerationConfig().path("enabled").asBoolean(false);
    }

    /** 每批生成几条：逐文档覆盖优先，其次知识库级配置，都没有则 0。 */
    public static int count(Knowledge k, KnowledgeBase kb) {
        Integer override = k == null ? null : KnowledgeMetadataOverrides.questionCount(k.getMetadata());
        if (override != null) {
            return override;
        }
        return kb == null || kb.getQuestionGenerationConfig() == null ? 0
                : kb.getQuestionGenerationConfig().path("questionCount").asInt(0);
    }
}
