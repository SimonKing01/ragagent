package com.ragagent.platform.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 退化文本判定的语料测试。
 *
 * <p>语料来源：线上真实故障——某 xlsx 分块生成的推荐问题变成
 * {@code "度PTRurna iso(Mpp（（ssiMB（（9（。（（（（（…"}（重复全角括号循环），
 * 该串原样落库并出现在推荐问题列表里。此处把真实串与一批正常问答一起钉住，
 * 确保判定既能拦住退化串、又不误伤中文 / 英文 / 泰文混排的正常问题。</p>
 */
class DegenerateTextTest {

    /** 线上实际落库的退化串（截取前 96 字符，尾部是重复全角括号）。 */
    private static final String PROD_GARBLED =
            "度PTRurna iso(Mpp（（ssiMB（（9（。（（（（（（（（（（（，，，，（，（（（（（（（（（（（ (（，（，（，（（，（，（，（（（，（（（，（（（（（（（（（（（（";

    @Test
    @DisplayName("退化串：真实线上样本、连续重复、单字符占比过高、坏码点、超长——全部判退化")
    void detectsDegenerateText() {
        assertThat(DegenerateText.isDegenerate(PROD_GARBLED)).as("线上真实样本").isTrue();

        // 无长连续重复，但单个字符占比极高（交替出现的标点）
        assertThat(DegenerateText.isDegenerate("（，（，（，（，（，（，（，（，（，（，（，（，（，（，"))
                .as("单字符占比 50%").isTrue();

        // 同一字符连续重复 ≥ 8
        assertThat(DegenerateText.isDegenerate("请问是什么问题？？？？？？？？？")).as("连续问号").isTrue();

        // 控制符 / 私有区码点
        assertThat(DegenerateText.isDegenerate("问题\u0001\u0002\u0003\u0004说明"))
                .as("控制符占比过高").isTrue();

        // 超长（> 200 码点）
        assertThat(DegenerateText.isDegenerate("问题".repeat(120))).as("长度超限").isTrue();

        // 空 / null / 纯空白 / 纯符号
        assertThat(DegenerateText.isDegenerate(null)).isTrue();
        assertThat(DegenerateText.isDegenerate("   ")).isTrue();
        assertThat(DegenerateText.isDegenerate("（）。（）。（）。")).isTrue();
    }

    @Test
    @DisplayName("正常问题：中/英/泰混排、带型号与数字、带标点——都不得误判")
    void keepsNormalQuestions() {
        assertThat(DegenerateText.isDegenerate("什么是知识库？")).isFalse();
        assertThat(DegenerateText.isDegenerate("GAC 经销商 TSGIBKK002 的联系方式和地址是什么")).isFalse();
        assertThat(DegenerateText.isDegenerate("GAC汽车曼谷和清迈经销商的联系方式和地址是什么")).isFalse();
        assertThat(DegenerateText.isDegenerate("What is the contact phone number of the GAC dealer in Bangkok?"))
                .isFalse();
        assertThat(DegenerateText.isDegenerate("GAC เลียบด่วนรามอินทรา 门店的联系电话是多少？")).isFalse();
        assertThat(DegenerateText.isDegenerate("99/6-9 Rama IX Soi 6 Rama Ix Road 的地址是什么")).isFalse();
        assertThat(DegenerateText.isDegenerate("2025 年 6 月新开的门店有哪些？")).isFalse();
        // 接近长度上限的正常长问句
        assertThat(DegenerateText.isDegenerate(
                "GAC 在泰国的授权经销商分布在曼谷、清迈、春武里、孔敬与宋卡等地，"
                        + "请问春武里府 Bang Saen 门店的联系电话与详细地址分别是什么？"))
                .isFalse();
    }
}
