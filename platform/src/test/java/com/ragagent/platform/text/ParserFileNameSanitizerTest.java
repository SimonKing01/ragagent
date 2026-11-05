package com.ragagent.platform.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 送解析器的文件名不能带会截断 markdown 图片链接的字符。 */
class ParserFileNameSanitizerTest {

    @Test
    void stripsParenthesesThatBreakMarkdownImageTargets() {
        assertThat(ParserFileNameSanitizer.sanitize("埃安(AION)i60图片卖点08.jpg"))
                .isEqualTo("埃安_AION_i60图片卖点08.jpg");
    }

    @Test
    void keepsHarmlessNamesUntouched() {
        assertThat(ParserFileNameSanitizer.sanitize("昊铂S600卖点图片13.jpg"))
                .isEqualTo("昊铂S600卖点图片13.jpg");
    }

    @Test
    void replacesBracketsAnglesAndSpaces() {
        assertThat(ParserFileNameSanitizer.sanitize("a [1] <b> c.pdf")).isEqualTo("a_1_b_c.pdf");
    }

    @Test
    void keepsExtensionAndFallsBackWhenNameIsOnlySeparators() {
        assertThat(ParserFileNameSanitizer.sanitize("(1).png")).isEqualTo("1.png");
        assertThat(ParserFileNameSanitizer.sanitize("()")).isEqualTo("file");
        assertThat(ParserFileNameSanitizer.sanitize("  ")).isEqualTo("file");
        assertThat(ParserFileNameSanitizer.sanitize(null)).isEqualTo("file");
    }

    @Test
    void preservesMultipleDotsInName() {
        assertThat(ParserFileNameSanitizer.sanitize("v1.2(最终).docx")).isEqualTo("v1.2_最终.docx");
    }
}
