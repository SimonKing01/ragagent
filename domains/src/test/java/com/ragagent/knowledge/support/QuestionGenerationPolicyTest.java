package com.ragagent.knowledge.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.platform.web.JsonMappers;
import org.junit.jupiter.api.Test;

/** 推荐问题开关：逐文档覆盖优先于 KB 级配置。 */
class QuestionGenerationPolicyTest {

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    private static KnowledgeBase kb(boolean enabled, int count) throws Exception {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setQuestionGenerationConfig(MAPPER.readTree(
                "{\"enabled\":" + enabled + ",\"questionCount\":" + count + "}"));
        return kb;
    }

    private static Knowledge doc(String metadata) throws Exception {
        Knowledge k = new Knowledge();
        k.setId("k1");
        if (metadata != null) {
            k.setMetadata(MAPPER.readTree(metadata));
        }
        return k;
    }

    @Test
    void fallsBackToKnowledgeBaseWhenNoOverride() throws Exception {
        assertThat(QuestionGenerationPolicy.enabled(doc(null), kb(true, 5))).isTrue();
        assertThat(QuestionGenerationPolicy.enabled(doc("{}"), kb(false, 5))).isFalse();
        assertThat(QuestionGenerationPolicy.count(doc(null), kb(true, 5))).isEqualTo(5);
    }

    @Test
    void documentOverrideWins() throws Exception {
        String disabled = "{\"processOverrides\":{\"questionGenerationConfig\":{\"enabled\":false}}}";
        String enabled = "{\"processOverrides\":{\"questionGenerationConfig\":{\"enabled\":true}}}";

        // 用户在上传弹窗里关掉 → KB 开着也不生成
        assertThat(QuestionGenerationPolicy.enabled(doc(disabled), kb(true, 5))).isFalse();
        // 反过来也成立：KB 关着，但该文档显式开启
        assertThat(QuestionGenerationPolicy.enabled(doc(enabled), kb(false, 5))).isTrue();
    }

    @Test
    void documentOverrideControlsCount() throws Exception {
        String metadata = "{\"processOverrides\":{\"questionGenerationConfig\":{\"enabled\":true,\"questionCount\":2}}}";
        assertThat(QuestionGenerationPolicy.count(doc(metadata), kb(true, 5))).isEqualTo(2);
    }

    @Test
    void toleratesMissingKnowledgeOrConfig() throws Exception {
        assertThat(QuestionGenerationPolicy.enabled(doc(null), null)).isFalse();
        assertThat(QuestionGenerationPolicy.count(doc(null), null)).isZero();
        assertThat(QuestionGenerationPolicy.enabled(doc(null), new KnowledgeBase())).isFalse();
    }
}
