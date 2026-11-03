package com.ragagent.platform.text;

import java.util.HashMap;
import java.util.Map;

/**
 * 退化文本识别：判断一段模型生成的短文本是否已经退化成重复/噪声串。
 *
 * <p>推荐问题取自模型输出并直接落库（{@code chunks.metadata.generatedQuestions}），
 * 模型偶发进入重复循环时会产出 {@code "度PTRurna iso(Mpp（（ssiMB（（9（。（（（（（…"}
 * 这类串，最终原样出现在推荐问题列表里（线上实际发生过）。这里给的是**保守下限**：
 * 只挡明显退化的串，正常长短的问答不受影响。</p>
 *
 * <p>命中任一即视为退化：空串；码点数 &gt; {@value #MAX_LENGTH}；同一码点连续重复
 * ≥ {@value #MAX_CHAR_RUN} 次；单个非空白码点占比 &gt; 35%；控制符/私有区/未分配/
 * 代理码点占比 &gt; 10%；字母与数字合计占比 &lt; 30%。</p>
 */
public final class DegenerateText {

    /** 单条生成文本的长度上限（码点）。 */
    public static final int MAX_LENGTH = 200;

    private static final int MAX_CHAR_RUN = 8;
    private static final double MAX_SINGLE_CHAR_RATIO = 0.35;
    private static final double MAX_BROKEN_RATIO = 0.10;
    private static final double MIN_LETTER_RATIO = 0.30;

    private DegenerateText() {
    }

    /** null / 空白 / 退化串 → true。 */
    public static boolean isDegenerate(String text) {
        if (text == null) {
            return true;
        }
        String s = UnicodeText.trim(text);
        if (s.isEmpty()) {
            return true;
        }
        if (s.codePointCount(0, s.length()) > MAX_LENGTH) {
            return true;
        }
        Map<Integer, Integer> counts = new HashMap<>();
        int visible = 0;
        int broken = 0;
        int words = 0;
        int prev = -1;
        int run = 0;
        int maxRun = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                prev = -1;
                continue;
            }
            visible++;
            counts.merge(cp, 1, Integer::sum);
            if (cp == prev) {
                run++;
            } else {
                prev = cp;
                run = 1;
            }
            maxRun = Math.max(maxRun, run);
            if (isBrokenCodePoint(cp)) {
                broken++;
            }
            if (Character.isLetter(cp) || Character.isDigit(cp)) {
                words++;
            }
        }
        if (visible == 0 || maxRun >= MAX_CHAR_RUN) {
            return true;
        }
        if (broken > visible * MAX_BROKEN_RATIO) {
            return true;
        }
        if (words < visible * MIN_LETTER_RATIO) {
            return true;
        }
        int maxCount = 0;
        for (int n : counts.values()) {
            maxCount = Math.max(maxCount, n);
        }
        return maxCount > visible * MAX_SINGLE_CHAR_RATIO;
    }

    private static boolean isBrokenCodePoint(int cp) {
        if (!Character.isDefined(cp)) {
            return true;
        }
        switch (Character.getType(cp)) {
            case Character.CONTROL:
            case Character.FORMAT:
            case Character.PRIVATE_USE:
            case Character.SURROGATE:
            case Character.UNASSIGNED:
                return true;
            default:
                return false;
        }
    }
}
