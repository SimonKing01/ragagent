package com.ragagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.platform.fixtures.GoldenResources;

/**
 * Go 录制常量（由录制输出生成，<b>禁止手改</b>）。
 *
 * <p>JSON 已外置到 {@code platform/src/testFixtures/resources/golden/agent-46.json}；
 * 字段名保持原 API，值由 {@link GoldenResources} 按 SHA-256 校验后加载。</p>
 */
public final class AgentPromptGolden {

    private static final String BUNDLE = "agent-46";

    private AgentPromptGolden() {
    }

    /** 解析一条录制记录。 */
    public static JsonNode rec(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static final String STR_SUMMARIZATIONSYSTEMPROMPT = GoldenResources.get(BUNDLE, "STR_SUMMARIZATIONSYSTEMPROMPT");
    public static final String STR_SUMMARYFORMAT = GoldenResources.get(BUNDLE, "STR_SUMMARYFORMAT");
    public static final String STR_INITIALSUMMARIZATIONINSTRUCTIONS = GoldenResources.get(BUNDLE, "STR_INITIALSUMMARIZATIONINSTRUCTIONS");
    public static final String STR_UPDATESUMMARIZATIONINSTRUCTIONS = GoldenResources.get(BUNDLE, "STR_UPDATESUMMARIZATIONINSTRUCTIONS");
    public static final String STR_TURNPREFIXINSTRUCTIONS = GoldenResources.get(BUNDLE, "STR_TURNPREFIXINSTRUCTIONS");
    public static final String STR_SPLITTURNSEPARATOR = GoldenResources.get(BUNDLE, "STR_SPLITTURNSEPARATOR");
    public static final String STR_SUMMARYPREFIX = GoldenResources.get(BUNDLE, "STR_SUMMARYPREFIX");
    public static final String STR_SUMMARYSUFFIX = GoldenResources.get(BUNDLE, "STR_SUMMARYSUFFIX");
    public static final String STR_PROMPT_INITIAL = GoldenResources.get(BUNDLE, "STR_PROMPT_INITIAL");
    public static final String STR_PROMPT_UPDATE = GoldenResources.get(BUNDLE, "STR_PROMPT_UPDATE");
    public static final String STR_E2E_REACT12_SUMMARY = GoldenResources.get(BUNDLE, "STR_E2E_REACT12_SUMMARY");
    public static final String STR_E2E_LENGTH_STOP_SUMMARY = GoldenResources.get(BUNDLE, "STR_E2E_LENGTH_STOP_SUMMARY");
    public static final String STR_E2E_LLM_ERROR_SUMMARY = GoldenResources.get(BUNDLE, "STR_E2E_LLM_ERROR_SUMMARY");
    public static final String STR_GROWN_PROMPT0 = GoldenResources.get(BUNDLE, "STR_GROWN_PROMPT0");
    public static final String STR_KBLIST = GoldenResources.get(BUNDLE, "STR_KBLIST");
    public static final String STR_KBLIST_INJECTION = GoldenResources.get(BUNDLE, "STR_KBLIST_INJECTION");
    public static final String STR_KBLIST_EMPTY = GoldenResources.get(BUNDLE, "STR_KBLIST_EMPTY");
    public static final String STR_KBLIST_ALLNIL = GoldenResources.get(BUNDLE, "STR_KBLIST_ALLNIL");
    public static final String STR_CONVERSATION = GoldenResources.get(BUNDLE, "STR_CONVERSATION");
    public static final String STR_CONVERSATION_EMPTY = GoldenResources.get(BUNDLE, "STR_CONVERSATION_EMPTY");
    public static final String STR_TRUNC_SHORT = GoldenResources.get(BUNDLE, "STR_TRUNC_SHORT");
    public static final String STR_TRUNC_EXACT = GoldenResources.get(BUNDLE, "STR_TRUNC_EXACT");
    public static final String STR_TRUNC_OVER = GoldenResources.get(BUNDLE, "STR_TRUNC_OVER");
    public static final String STR_TRUNC_CJK = GoldenResources.get(BUNDLE, "STR_TRUNC_CJK");
    public static final String STR_TRUNC_TRIM = GoldenResources.get(BUNDLE, "STR_TRUNC_TRIM");
    public static final String STR_TRUNC_MULTILINE = GoldenResources.get(BUNDLE, "STR_TRUNC_MULTILINE");
    public static final String STR_TRUNC_NEWLINE_MARKER = GoldenResources.get(BUNDLE, "STR_TRUNC_NEWLINE_MARKER");
    public static final String STR_RAW_ARCHIVE = GoldenResources.get(BUNDLE, "STR_RAW_ARCHIVE");
    public static final String STR_RAW_ARCHIVE_EMPTY = GoldenResources.get(BUNDLE, "STR_RAW_ARCHIVE_EMPTY");
    public static final String STR_CALLS2 = GoldenResources.get(BUNDLE, "STR_CALLS2");
    public static final String STR_CALLS0 = GoldenResources.get(BUNDLE, "STR_CALLS0");
    public static final String STR_CALLS_BAD = GoldenResources.get(BUNDLE, "STR_CALLS_BAD");
    public static final String STR_PHS_FULL = GoldenResources.get(BUNDLE, "STR_PHS_FULL");
    public static final String STR_PHS_DISABLED = GoldenResources.get(BUNDLE, "STR_PHS_DISABLED");
    public static final String STR_PHS_AUTOFILL = GoldenResources.get(BUNDLE, "STR_PHS_AUTOFILL");
    public static final String STR_PHS_UNKNOWN_LEFT = GoldenResources.get(BUNDLE, "STR_PHS_UNKNOWN_LEFT");
    public static final String STR_SKILLS_META = GoldenResources.get(BUNDLE, "STR_SKILLS_META");
    public static final String STR_SKILLS_META_EMPTY = GoldenResources.get(BUNDLE, "STR_SKILLS_META_EMPTY");
    public static final String STR_SANDBOX_ARTIFACT = GoldenResources.get(BUNDLE, "STR_SANDBOX_ARTIFACT");
    public static final String STR_STEER_GUIDANCE = GoldenResources.get(BUNDLE, "STR_STEER_GUIDANCE");
    public static final String STR_RUNTIME_CONTRACT = GoldenResources.get(BUNDLE, "STR_RUNTIME_CONTRACT");
    public static final String STR_OUTPUT_PROMPT = GoldenResources.get(BUNDLE, "STR_OUTPUT_PROMPT");
    public static final String STR_BOUNDARY_PROMPT = GoldenResources.get(BUNDLE, "STR_BOUNDARY_PROMPT");
    public static final String STR_FULL_CUSTOM = GoldenResources.get(BUNDLE, "STR_FULL_CUSTOM");
    public static final String STR_FULL_LEGACY = GoldenResources.get(BUNDLE, "STR_FULL_LEGACY");
    public static final String STR_FULL_INSTALL = GoldenResources.get(BUNDLE, "STR_FULL_INSTALL");
    public static final String STR_BD_STRING = GoldenResources.get(BUNDLE, "STR_BD_STRING");
    public static final String STR_BD0_STRING = GoldenResources.get(BUNDLE, "STR_BD0_STRING");
    public static final String STR_RESOLVE1_FORMAT = GoldenResources.get(BUNDLE, "STR_RESOLVE1_FORMAT");
    public static final String STR_RESOLVE2_FORMAT = GoldenResources.get(BUNDLE, "STR_RESOLVE2_FORMAT");
    public static final String STR_RESOLVE3_FORMAT = GoldenResources.get(BUNDLE, "STR_RESOLVE3_FORMAT");
    public static final String STR_RESOLVE4_FORMAT = GoldenResources.get(BUNDLE, "STR_RESOLVE4_FORMAT");
    public static final String STR_RESOLVE5_FORMAT = GoldenResources.get(BUNDLE, "STR_RESOLVE5_FORMAT");
    public static final String STR_ARGS0 = GoldenResources.get(BUNDLE, "STR_ARGS0");
    public static final String STR_ARGS1 = GoldenResources.get(BUNDLE, "STR_ARGS1");
    public static final String STR_ARGS2 = GoldenResources.get(BUNDLE, "STR_ARGS2");
    public static final String STR_ARGS3 = GoldenResources.get(BUNDLE, "STR_ARGS3");
    public static final String STR_ARGS4 = GoldenResources.get(BUNDLE, "STR_ARGS4");
    public static final String STR_ARGS5 = GoldenResources.get(BUNDLE, "STR_ARGS5");
    public static final String STR_ARGS6 = GoldenResources.get(BUNDLE, "STR_ARGS6");
    public static final String STR_ARGS7 = GoldenResources.get(BUNDLE, "STR_ARGS7");
    public static final String STR_ARGS8 = GoldenResources.get(BUNDLE, "STR_ARGS8");
    public static final String STR_ARGS9 = GoldenResources.get(BUNDLE, "STR_ARGS9");
    public static final String STR_ARGS10 = GoldenResources.get(BUNDLE, "STR_ARGS10");
    public static final String STR_ARGS11 = GoldenResources.get(BUNDLE, "STR_ARGS11");
    public static final String STR_ARGS12 = GoldenResources.get(BUNDLE, "STR_ARGS12");
    public static final String STR_ARGS13 = GoldenResources.get(BUNDLE, "STR_ARGS13");
    public static final String STR_ARGS14 = GoldenResources.get(BUNDLE, "STR_ARGS14");
    public static final String STR_ARGS15 = GoldenResources.get(BUNDLE, "STR_ARGS15");
    public static final String STR_DSUM0 = GoldenResources.get(BUNDLE, "STR_DSUM0");
    public static final String STR_DSUM1 = GoldenResources.get(BUNDLE, "STR_DSUM1");
    public static final String STR_DSUM2 = GoldenResources.get(BUNDLE, "STR_DSUM2");
    public static final String STR_DSUM3 = GoldenResources.get(BUNDLE, "STR_DSUM3");
    public static final String STR_DSUM4 = GoldenResources.get(BUNDLE, "STR_DSUM4");
    public static final String STR_DSUM5 = GoldenResources.get(BUNDLE, "STR_DSUM5");
    public static final String STR_DSUM6 = GoldenResources.get(BUNDLE, "STR_DSUM6");
    public static final String STR_DSUM7 = GoldenResources.get(BUNDLE, "STR_DSUM7");
    public static final String STR_DSUM8 = GoldenResources.get(BUNDLE, "STR_DSUM8");
    public static final String STR_ESC0 = GoldenResources.get(BUNDLE, "STR_ESC0");
    public static final String STR_ESC1 = GoldenResources.get(BUNDLE, "STR_ESC1");
    public static final String STR_ESC2 = GoldenResources.get(BUNDLE, "STR_ESC2");
    public static final String STR_ESC3 = GoldenResources.get(BUNDLE, "STR_ESC3");
    public static final String STR_ESC4 = GoldenResources.get(BUNDLE, "STR_ESC4");
    public static final String STR_GUID0 = GoldenResources.get(BUNDLE, "STR_GUID0");
    public static final String STR_GUID1 = GoldenResources.get(BUNDLE, "STR_GUID1");
    public static final String STR_GUID2 = GoldenResources.get(BUNDLE, "STR_GUID2");
    public static final String STR_GUID3 = GoldenResources.get(BUNDLE, "STR_GUID3");
    public static final String STR_GUID4 = GoldenResources.get(BUNDLE, "STR_GUID4");
    public static final String STR_GUID5 = GoldenResources.get(BUNDLE, "STR_GUID5");
    public static final String STR_GUID6 = GoldenResources.get(BUNDLE, "STR_GUID6");
    public static final String STR_GROUND0 = GoldenResources.get(BUNDLE, "STR_GROUND0");
    public static final String STR_GROUND1 = GoldenResources.get(BUNDLE, "STR_GROUND1");
    public static final String STR_GROUND2 = GoldenResources.get(BUNDLE, "STR_GROUND2");
    public static final String STR_GROUND3 = GoldenResources.get(BUNDLE, "STR_GROUND3");
    public static final String STR_GROUND4 = GoldenResources.get(BUNDLE, "STR_GROUND4");
    public static final String STR_GROUND5 = GoldenResources.get(BUNDLE, "STR_GROUND5");
    public static final String STR_GROUND6 = GoldenResources.get(BUNDLE, "STR_GROUND6");
    public static final String STR_FSIZE0 = GoldenResources.get(BUNDLE, "STR_FSIZE0");
    public static final String STR_FSIZE1 = GoldenResources.get(BUNDLE, "STR_FSIZE1");
    public static final String STR_FSIZE2 = GoldenResources.get(BUNDLE, "STR_FSIZE2");
    public static final String STR_FSIZE3 = GoldenResources.get(BUNDLE, "STR_FSIZE3");
    public static final String STR_FSIZE4 = GoldenResources.get(BUNDLE, "STR_FSIZE4");
    public static final String STR_FSIZE5 = GoldenResources.get(BUNDLE, "STR_FSIZE5");
    public static final String STR_FSIZE6 = GoldenResources.get(BUNDLE, "STR_FSIZE6");
    public static final String STR_FSIZE7 = GoldenResources.get(BUNDLE, "STR_FSIZE7");
    public static final String STR_FSIZE8 = GoldenResources.get(BUNDLE, "STR_FSIZE8");
    public static final String STR_FSIZE9 = GoldenResources.get(BUNDLE, "STR_FSIZE9");
    public static final String STR_FSIZE10 = GoldenResources.get(BUNDLE, "STR_FSIZE10");
    public static final String STR_SUMMARY_CONTENT = GoldenResources.get(BUNDLE, "STR_SUMMARY_CONTENT");
}
