package com.ragagent.platform.prompt;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 提示词模板的唯一装载入口。
 *
 * <p>模板 vendored 到 classpath {@code prompts/}（本模块 resources）。全仓只此一处
 * 拼资源路径、只此一份文件清单——消费方（agent 侧多个组装点、会话侧回填、对外模板目录）
 * 一律经本类读取，避免"同一目录多份清单"漂移。</p>
 *
 * <p>{@link #files()} 的顺序即按 id 查找模板的查找序：首个 id 命中即返回，
 * 调整顺序会改变 id 冲突时的取值，勿随意重排。</p>
 *
 * <p>缺失语义与 {@link ClassLoader#getResourceAsStream} 一致：文件不在 → {@code null}，
 * 由消费方按各自降级策略处理（多数是"静默跳过"）。</p>
 */
public final class PromptSource {

    /** 模板目录的 classpath 前缀。 */
    public static final String DIR = "prompts/";

    // ── 模板文件名：全仓唯一字面量来源（消费方引用常量，别再写字面量）──
    public static final String AGENT_SYSTEM_PROMPT = "agent_system_prompt.yaml";
    public static final String SYSTEM_PROMPT = "system_prompt.yaml";
    public static final String CONTEXT_TEMPLATE = "context_template.yaml";
    public static final String REWRITE = "rewrite.yaml";
    public static final String FALLBACK = "fallback.yaml";
    public static final String GENERATE_SUMMARY = "generate_summary.yaml";
    public static final String GENERATE_SESSION_TITLE = "generate_session_title.yaml";
    public static final String KEYWORDS_EXTRACTION = "keywords_extraction.yaml";
    public static final String GRAPH_EXTRACTION = "graph_extraction.yaml";
    public static final String GENERATE_QUESTIONS = "generate_questions.yaml";
    public static final String INTENT_PROMPTS = "intent_prompts.yaml";

    /** 模板文件清单（顺序 = 按 id 查找的查找序，共 11 份）。 */
    private static final List<String> FILES = List.of(
            AGENT_SYSTEM_PROMPT, SYSTEM_PROMPT, CONTEXT_TEMPLATE,
            FALLBACK, GENERATE_SUMMARY, GENERATE_SESSION_TITLE,
            REWRITE, GRAPH_EXTRACTION, GENERATE_QUESTIONS,
            KEYWORDS_EXTRACTION, INTENT_PROMPTS);

    private PromptSource() {
    }

    /** 清单视图（不可变，顺序固定）。 */
    public static List<String> files() {
        return FILES;
    }

    /** 打开模板文件；缺失返回 {@code null}（调用方负责关闭流）。 */
    public static InputStream open(String fileName) {
        return PromptSource.class.getClassLoader().getResourceAsStream(DIR + fileName);
    }

    /** 读取模板全文（UTF-8）；缺失或读失败返回空串。 */
    public static String read(String fileName) {
        try (InputStream in = open(fileName)) {
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
