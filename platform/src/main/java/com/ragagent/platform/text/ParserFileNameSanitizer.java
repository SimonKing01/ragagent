package com.ragagent.platform.text;

import java.util.regex.Pattern;

/**
 * 送解析器（docreader）的文件名安全化。
 *
 * <p>解析器会用文件名生成正文里的图片引用（如 {@code ![alt](images/文件名)}）。
 * 文件名里出现 {@code ()[]<>} 或空白时，这段 markdown 会被提前截断——括号内的
 * {@code )} 会被当成链接结尾，于是引用变成 {@code images/xxx(AION} 之类的残片：
 * 前端判为无效链接、图片改写与 OCR 也匹配不上。</p>
 *
 * <p>因此只把**传给解析器的名字**做替换，文档标题/展示名/落盘文件名一律保持原样。</p>
 */
public final class ParserFileNameSanitizer {

    /** markdown 链接/标题里会破坏语法的字符 + 空白。 */
    private static final Pattern UNSAFE = Pattern.compile("[()\\[\\]<>\\s]+");

    private ParserFileNameSanitizer() {
    }

    /** 把不安全字符替换为单个下划线；空名回落 {@code file}。 */
    public static String sanitize(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "file";
        }
        String trimmed = fileName.trim();
        int dot = trimmed.lastIndexOf('.');
        String base = dot > 0 ? trimmed.substring(0, dot) : trimmed;
        String ext = dot > 0 ? trimmed.substring(dot) : "";
        String safeBase = collapse(UNSAFE.matcher(base).replaceAll("_"));
        if (safeBase.isEmpty()) {
            safeBase = "file";
        }
        String safeExt = UNSAFE.matcher(ext).replaceAll("");
        return safeBase + safeExt;
    }

    private static String collapse(String value) {
        String collapsed = value.replaceAll("_+", "_");
        while (collapsed.startsWith("_")) {
            collapsed = collapsed.substring(1);
        }
        while (collapsed.endsWith("_")) {
            collapsed = collapsed.substring(0, collapsed.length() - 1);
        }
        return collapsed;
    }
}
