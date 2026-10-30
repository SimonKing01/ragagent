package com.ragagent.knowledge.chunker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 标题层级栈：未活动的层级必须是空串（不能是 null），否则同层级连续标题会 NPE。 */
class HeadingHierarchyTest {

    @Test
    @DisplayName("以 H3 开头再遇到同层级标题不 NPE（PDF markdown 常见形态）")
    void sameLevelAfterDeepStartDoesNotThrow() {
        HeadingHierarchy h = new HeadingHierarchy();

        assertThatCode(() -> {
            h.observe("### 第一章");
            h.observe("### 第二章");
        }).doesNotThrowAnyException();
        assertThat(h.breadcrumb()).contains("第二章");
    }

    @Test
    @DisplayName("同级连续标题反复压栈也安全")
    void repeatedSameLevel() {
        HeadingHierarchy h = new HeadingHierarchy();
        for (int i = 0; i < 5; i++) {
            h.observe("## 小节" + i);
        }
        assertThat(h.breadcrumb()).contains("小节4");
    }

    @Test
    @DisplayName("从深层回到浅层后 breadcrumb 收缩，且不 NPE")
    void shrinkDepth() {
        HeadingHierarchy h = new HeadingHierarchy();
        h.observe("### 深");
        h.observe("# 浅");

        assertThat(h.breadcrumb()).contains("浅");
    }
}
