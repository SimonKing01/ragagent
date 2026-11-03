package com.ragagent.agentmanagement.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ragagent.agentmanagement.mapper.AgentQuestionMapper;
import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 推荐问题（suggested-questions）的键名守卫。
 *
 * <p><b>实锤过的故障</b>：生成问题写在 {@code chunks.metadata.generatedQuestions}
 * （camel——写入侧是 Java 域类型 {@code DocumentChunkMetadata}，Jackson 默认 camel，
 * 且从未有过改名迁移、全库实测 0 行 snake），而推荐问题的读取侧（本包的
 * {@code firstGeneratedQuestion} 与 {@code AgentQuestionMapper} 的 {@code LIKE} 过滤）
 * 却按 snake {@code generated_questions} 取 → SQL 恒命中 0 行、解析恒 null
 * ⇒ 智能体「推荐问题」永远为空（真机：端点返回 {@code []}，修复后返回库里的问题）。</p>
 *
 * <p>这里同时钉住两处：SQL 过滤用 camel 键、解析用 camel 键。<b>不再要求"容忍 snake
 * 存量行"</b>——那段理由属已整体作废的"兼容历史数据"类，且本测试自己的注释早写明
 * "从未有过改名迁移、全库实测 0 行 snake"，容忍分支实测不可达；留着它反而把死代码锁在库里。</p>
 */
class AgentSuggestedQuestionsTest {

    @Test
    @DisplayName("解析：只认 camel 键；snake 存量行**不再**回落；畸形/空值不抛错")
    void firstGeneratedQuestionReadsCamelOnly() {
        String camel = "{\"generatedQuestions\":[{\"id\":\"q1\",\"question\":\"camel 问题\","
                + "\"contentRevision\":0}],\"generatedQuestionsRevision\":0}";
        assertThat(AgentSuggestedQuestions.firstGeneratedQuestion(camel)).isEqualTo("camel 问题");

        // snake 不再回落（"兼容历史数据"政策作废；实测全库 0 行 snake）
        String snake = "{\"generated_questions\":[{\"question\":\"snake 问题\"}]}";
        assertThat(AgentSuggestedQuestions.firstGeneratedQuestion(snake)).isNull();

        // 纯字符串数组元素（兼容旧形态）
        String plain = "{\"generatedQuestions\":[\"纯字符串问题\"]}";
        assertThat(AgentSuggestedQuestions.firstGeneratedQuestion(plain)).isEqualTo("纯字符串问题");

        // 退化串（模型重复循环落库的历史脏数据）→ null：不进推荐池（线上真实样本）
        String garbled = "{\"generatedQuestions\":[{\"question\":\"度PTRurna iso(Mpp（（ssiMB（（9（。（（（（（（"
                + "（（（（（（（（（（（，，，，（，（（（（（（（（（（\"}]}";
        assertThat(AgentSuggestedQuestions.firstGeneratedQuestion(garbled)).isNull();

        // 空数组 / 缺键 / null / 畸形 JSON → null（不得抛错）
        assertThat(AgentSuggestedQuestions.firstGeneratedQuestion("{\"generatedQuestions\":[]}")).isNull();
        assertThat(AgentSuggestedQuestions.firstGeneratedQuestion("{\"other\":1}")).isNull();
        assertThat(AgentSuggestedQuestions.firstGeneratedQuestion(null)).isNull();
        assertThat(AgentSuggestedQuestions.firstGeneratedQuestion("")).isNull();
        assertThat(AgentSuggestedQuestions.firstGeneratedQuestion("{不是 json")).isNull();
    }

    @Test
    @DisplayName("SQL 过滤：必须用 camel 键（%generatedQuestions%）——写错键名会让推荐问题恒空")
    void recommendedChunksQueryFiltersOnCamelKey() throws Exception {
        // 逐条扫：凡是按生成问题键过滤的查询，都必须用 camel（单一词汇，不再容忍 snake）。
        // 注意别只盯某一个方法——本映射里还有 FAQ 面的查询（按 chunk_type='faq' 过滤，
        // 与生成问题无关），钉错方法会得到假红/假绿。
        int checked = 0;
        for (Method m : AgentQuestionMapper.class.getDeclaredMethods()) {
            Select select = m.getAnnotation(Select.class);
            if (select == null) {
                continue;
            }
            String sql = String.join(" ", select.value());
            if (!sql.contains("generatedQuestions") && !sql.contains("generated_questions")
                    && !sql.contains("generatedQuestion")) {
                continue;
            }
            checked++;
            assertThat(sql)
                    .as("%s：键名以写入侧为准（camel generatedQuestions）", m.getName())
                    .contains("'%generatedQuestions%'");
            assertThat(sql)
                    .as("%s：不得再出现 Go 期 snake 键（单一词汇）", m.getName())
                    .doesNotContain("generated_questions");
        }
        assertThat(checked)
                .as("应至少有一条按生成问题键过滤的查询（否则本守卫形同虚设）")
                .isGreaterThan(0);
    }
}
