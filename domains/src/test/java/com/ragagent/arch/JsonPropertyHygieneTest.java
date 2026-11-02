package com.ragagent.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A14 守卫：逐字段 {@code @JsonProperty} 只允许出现在「外部协议面」或被证明必要的位置。
 *
 * <p>依据 {@code docs/knowledge-api-contract-v1.md} §1.1：<b>JSON 字段名 = Java 字段名（camelCase）；
 * 禁止逐字段 {@code @JsonProperty}</b>。键与隐式属性名相同时注解是纯噪音，而且注解<b>优先于</b>
 * 字段名——两者一旦漂移就是静默换键。</p>
 *
 * <p>但「键 = 字段名」<b>不充分</b>：注解有时是<b>唯一让 Jackson 看得见该属性</b>的东西。所以本守卫
 * 只在<b>同时满足</b>两条时才判红：</p>
 * <ol>
 *   <li>注解的键 = 该成员的隐式属性名（Jackson 规则）；</li>
 *   <li>Jackson <b>本来就会自动探测</b>该成员——record 分量 / {@code public} 字段 / 有 {@code public}
 *       访问器（{@code getX}/{@code isX}/{@code setX} 或同名 fluent 方法）。否则删掉注解会让属性直接
 *       消失（实测踩过：{@code CreateTenantRequest} 的包级私有字段 → 请求体 400
 *       「field 'name' is required」）。</li>
 * </ol>
 *
 * <p>必要形态只剩四类，规则见下：键≠隐式名（外部协议 / 关键字冲突等）· 键形如 {@code isXxx} 的布尔属性
 * （getter 的隐式名会丢 {@code is}）· Jackson 探测不到的成员 · 「字段序锚」——见
 * {@link #hasRenamedAnnotation}。<b>不再有面级放行表</b>：外部协议面的冗余注解（43 处）也已清掉，
 * 剩下的全是上面四类。</p>
 *
 * <p>B269 判例补录（字节级序快照实证）：</p>
 * <ul>
 *   <li><b>record 分量不受序锚保护</b>——record 序 = 规范构造器序 = 声明序，与注解显隐无关；
 *       混改名 record 文件里的同名注解照样可删（B269① 已删 97 处，快照零差）。</li>
 *   <li><b>class 面删同名必重排</b>——混改名 class 删任一同名注解触发全类型字段序重排
 *       （WikiNode {@code title} 前跳 / DocxBlock {@code block_id} 移尾），且 setter-only 字段会
 *       <b>整键消失</b>（{@code AuthServerMetadata.issuer}）——与有无序列化出口无关，继续整文件保护。</li>
 *   <li>未知键容忍由 mapper 层统一承担（{@code WebConfig} 的 builder 自定义器 +
 *       {@code JsonMappers.lenient()} 双路径显式禁用 {@code FAIL_ON_UNKNOWN_PROPERTIES}），
 *       逐类 {@code ignoreUnknown=true} 已在 B269② 全数摘除——Jackson 2 默认严格，
 *       所以这两条禁用是承重的，不是冗余。</li>
 * </ul>
 *
 * <p>基线 <b>0</b>（不是棘轮——我们自己的面已清零，从第一天起就是"新增即红"）。</p>
 */
class JsonPropertyHygieneTest {

    /**
     * 短形式与<b>全限定</b>形式都要认——HANDOFF §14.6 的口径警告：只扫短名会漏
     * （仓里仍有全限定写法，如 {@code chatpipeline/Rec46cSupport} 用的
     * {@code @com.fasterxml…JsonProperty}）。
     */
    private static final String ANN =
            "@(?:com\\.fasterxml\\.jackson\\.annotation\\.)?JsonProperty";

    private static final Pattern ANNOTATION = Pattern.compile(ANN + "\\s*\\(");
    private static final Pattern KEY =
            Pattern.compile(ANN + "\\s*\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)\"");
    /** 键形如 isXxx（布尔）：getter 的隐式名会丢 is ⇒ 注解必要，整族豁免。 */
    private static final Pattern IS_PREFIXED_KEY = Pattern.compile("is[A-Z].*");
    private static final Pattern METHOD_NAME = Pattern.compile("([A-Za-z_$][\\w$]*)\\s*\\(");
    private static final Pattern RECORD_HEADER = Pattern.compile("\\brecord\\s+\\w+\\s*\\(");

    @Test
    @DisplayName("A14：我们的面里不得有「键=隐式属性名」且「Jackson 本可自动探测」的逐字段 @JsonProperty")
    void redundantJsonPropertyIsForbidden() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path root : SourceRoots.backend("main")) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (var walk = Files.walk(root)) {
                for (Path file : walk.filter(Files::isRegularFile)
                        .filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                    String rel = relative(file);
                    collect(file, rel, offenders);
                }
            }
        }
        assertThat(offenders)
                .as("@JsonProperty 只是噪音的情形：键=隐式属性名 **且** Jackson 本会自己探测到它"
                        + "（record 分量 / public 字段 / public 访问器）。其余形态见类注释，都属必要")
                .isEmpty();
    }

    /** 把 {@code .../java/com/ragagent/xxx} 归一成 {@code xxx}。 */
    private static String relative(Path file) {
        String s = file.toString().replace('\\', '/');
        int i = s.indexOf("com/ragagent/");
        return i < 0 ? s : s.substring(i + "com/ragagent/".length());
    }

    private static void collect(Path file, String rel, List<String> offenders) throws IOException {
        List<String> lines = Files.readAllLines(file);
        String body = String.join("\n", lines);
        boolean[] inRecordHeader = recordComponentLines(lines);
        boolean renamed = hasRenamedAnnotation(lines, inRecordHeader);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher ann = ANNOTATION.matcher(line);
            if (!ann.find() || isComment(line)) {
                continue;
            }
            Matcher key = KEY.matcher(line);
            if (!key.find()) {
                continue;
            }
            String keyName = key.group(1);
            if (IS_PREFIXED_KEY.matcher(keyName).matches()) {
                continue;
            }
            if (renamed && !inRecordHeader[i]) {
                continue;   // 字段序锚——record 分量除外（序=声明序与注解无关，B269① 快照实证）
            }
            Member member = memberAfter(lines, i, ann.start());
            if (member == null || !member.name.equals(keyName)) {
                continue;
            }
            if (!jacksonAutoDetects(member, inRecordHeader[i], body)) {
                continue;   // 注解是唯一可见性来源 ⇒ 必要（实测：删了会 400）
            }
            offenders.add(rel + ":" + (i + 1) + "  键 " + keyName
                    + " = 隐式属性名且 Jackson 自会探测（删掉注解即可，零行为变化）");
        }
    }

    private static boolean isComment(String line) {
        String t = line.stripLeading();
        return t.startsWith("*") || t.startsWith("//") || t.startsWith("/*");
    }

    /**
     * 该类型是否混用「键 ≠ 隐式名」的<b>改名注解</b>。混用时不得再删「键 = 隐式名」的注解：那会把它从
     * <b>显式命名</b>降级成<b>隐式命名</b>，Jackson 据此可能改变属性顺序 ⇒ 线格式<b>字段序</b>漂移
     * （Go 队列载荷断言期望 {@code tenant_id…chat_model_id,language}，删后 {@code language}
     * 跳到最前）。这类冗余注解实为「字段序锚」，一并保留。
     *
     * <p><b>record 分量例外</b>（B269① 快照实证）：record 序 = 规范构造器序 = 声明序，改名不重排——
     * 混改名文件里的 record 分量同名注解仍可删（见 {@link #collect} 的豁免）。</p>
     */
    private static boolean hasRenamedAnnotation(List<String> lines, boolean[] inRecordHeader) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher ann = ANNOTATION.matcher(line);
            if (!ann.find() || isComment(line)) {
                continue;
            }
            Matcher key = KEY.matcher(line);
            if (!key.find()) {
                continue;
            }
            Member member = memberAfter(lines, i, ann.start());
            if (member != null && !member.name.equals(key.group(1))) {
                return true;
            }
        }
        return false;
    }

    /** 注解所修饰成员：隐式属性名 + 是否方法声明 + 声明原文。 */
    private record Member(String name, boolean method, String decl) {}

    /**
     * 求注解所修饰成员的<b>隐式属性名</b>（Jackson 实际规则）：字段/记录分量 = 名字本身；
     * getter/setter 去 {@code get}/{@code set} 并首字母小写，布尔 {@code isXxx()} ⇒ {@code xxx}。
     * 解析不出返回 {@code null}（宁可漏报也不误报）。
     *
     * <p><b>多行注解感知</b>（B269③ 修复）：注解参数可跨行（如 {@code @JsonInclude(value = CUSTOM,}
     * 换行 {@code valueFilter = X.class) Foo foo)}）——按括号深度消费到配对 {@code )}，
     * 取同行剩余作为成员声明。修复前 {@code tracing} 族注解因盲区漏检。</p>
     */
    private static Member memberAfter(List<String> lines, int i, int start) {
        String rest = lines.get(i).substring(start);
        int end = closingParen(rest);
        List<String> candidates = new ArrayList<>();
        if (end >= 0 && !rest.substring(end).isBlank()) {
            String tail = rest.substring(end);
            if (!tail.contains("@")) {
                candidates.add(tail);
            }
        }
        int depth = 0;
        for (int k = i + 1; k < Math.min(i + 12, lines.size()) && candidates.isEmpty(); k++) {
            String s = lines.get(k).strip();
            if (depth == 0 && (s.isEmpty() || isComment(s))) {
                continue;
            }
            if (depth > 0) {                    // 跨行注解延续：消费到配对 ')' 取同行剩余
                int j = 0;
                while (j < s.length() && depth > 0) {
                    char c = s.charAt(j++);
                    if (c == '(') {
                        depth++;
                    } else if (c == ')') {
                        depth--;
                    }
                }
                if (depth == 0 && j < s.length()) {
                    String after = s.substring(j).strip();
                    if (!after.isEmpty() && !after.startsWith("@")) {
                        candidates.add(after);
                    }
                }
                continue;
            }
            if (s.startsWith("@")) {
                int delta = parenDelta(s);
                if (delta > 0) {                // 该注解本身跨行
                    depth = delta;
                    continue;
                }
                String after = s.substring(closingParen(s)).strip();
                if (!after.isEmpty()) {
                    candidates.add(after);
                    break;
                }
                continue;                       // 纯注解行 ⇒ 继续往后
            }
            candidates.add(s);
            break;
        }
        for (String raw : candidates) {
            String s = stripLeadingAnnotations(raw).split("//")[0].strip();
            if (s.isEmpty()) {
                continue;
            }
            int paren = s.indexOf('(');
            int eq = s.indexOf('=');
            if (eq >= 0 && (paren < 0 || eq < paren)) {
                s = s.substring(0, eq).strip();             // private String issuer = ""; ⇒ private String issuer
            }
            if (s.indexOf('(') >= 0) {
                Matcher m = METHOD_NAME.matcher(s);
                if (!m.find()) {
                    continue;
                }
                String method = m.group(1);
                return new Member(stripAccessorPrefix(method), true, raw.strip());
            }
            String t = s.replaceAll("[;,){}\\s]+$", "").strip();
            int sp = t.lastIndexOf(' ');
            String name = sp < 0 ? t : t.substring(sp + 1).strip();
            if (!name.isEmpty()) {
                return new Member(name, false, raw.strip());
            }
        }
        return null;
    }

    /** 行内 '(' 与 ')' 的数量差（正数 ⇒ 注解参数未闭合、跨行）。 */
    private static int parenDelta(String s) {
        int d = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                d++;
            } else if (c == ')') {
                d--;
            }
        }
        return d;
    }

    /**
     * Jackson 是否会<b>自己</b>发现该成员（默认可见性：字段要 public、访问器要 public）：
     * record 分量 ✓ · {@code public} 声明 ✓ · 同类里有 public 访问器（{@code getX}/{@code isX}/{@code setX}
     * 或同名 fluent）✓。
     */
    private static boolean jacksonAutoDetects(Member member, boolean inRecordHeader, String body) {
        if (inRecordHeader || member.method && member.decl.startsWith("public ")) {
            return true;
        }
        if (!member.method && member.decl.startsWith("public ")) {
            return true;
        }
        String cap = Character.toUpperCase(member.name.charAt(0)) + member.name.substring(1);
        Pattern accessor = Pattern.compile("public\\s+[\\w<>\\[\\], .?]+\\s+(?:get|is|set)?"
                + Pattern.quote(cap) + "\\s*\\(");
        return accessor.matcher(body).find();
    }

    private static String stripAccessorPrefix(String method) {
        for (String pre : new String[] {"get", "set"}) {
            if (method.startsWith(pre) && method.length() > 3) {
                return Character.toLowerCase(method.charAt(3)) + method.substring(4);
            }
        }
        if (method.startsWith("is") && method.length() > 2 && Character.isUpperCase(method.charAt(2))) {
            return Character.toLowerCase(method.charAt(2)) + method.substring(3);
        }
        return method;
    }

    /** 剥掉行首的一串注解（{@code @JsonInclude(...) String language,} ⇒ {@code String language,}）。 */
    private static String stripLeadingAnnotations(String line) {
        String s = line.strip();
        while (s.startsWith("@")) {
            int end = closingParen(s);
            if (end < 0) {
                return "";
            }
            s = s.substring(end).strip();
        }
        return s;
    }

    /** 标记「落在 record 头括号内」的行（= 记录分量所在行）。 */
    private static boolean[] recordComponentLines(List<String> lines) {
        boolean[] inside = new boolean[lines.size()];
        int depth = 0;
        boolean inHeader = false;
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (!inHeader) {
                Matcher m = RECORD_HEADER.matcher(l);
                if (!m.find()) {
                    continue;
                }
                inHeader = true;
                depth = 0;
                for (int j = m.end() - 1; j < l.length(); j++) {
                    char c = l.charAt(j);
                    if (c == '(') {
                        depth++;
                    } else if (c == ')') {
                        depth--;
                        if (depth == 0) {
                            inHeader = false;
                            break;
                        }
                    }
                }
                inside[i] = true;
                continue;
            }
            inside[i] = true;
            for (int j = 0; j < l.length(); j++) {
                char c = l.charAt(j);
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                    if (depth == 0) {
                        inHeader = false;
                        break;
                    }
                }
            }
        }
        return inside;
    }

    /** 返回第一个 '(' 对应的 ')' 的<b>后一位</b>；无括号返回 -1。 */
    private static int closingParen(String s) {
        int depth = 0;
        boolean seen = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
                seen = true;
            } else if (c == ')') {
                depth--;
                if (seen && depth == 0) {
                    return i + 1;
                }
            }
        }
        return -1;
    }
}
