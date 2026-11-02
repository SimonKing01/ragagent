package com.ragagent.session.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.platform.fixtures.GoldenResources;

/**
 * 4.6c 录制常量（chat_pipeline 检索管线 26 文件），由 Go 录制 JSONL 生成——<b>禁止手改</b>。
 *
 * <p>JSON 已外置到 {@code platform/src/testFixtures/resources/golden/chat-pipeline-46c.json}；
 * 掩码约定见原录制（UUID/日期/端口/durationMs）。</p>
 */
public final class ChatPipelineGolden {

    private static final String BUNDLE = "chat-pipeline-46c";

    private ChatPipelineGolden() {
    }

    /** 解析一条录制记录（传常量原文）。 */
    public static JsonNode rec(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 按组/键查录制常量。 */
    public static String constant(String group, String key) {
        return GoldenResources.get(BUNDLE, group + "/" + key);
    }
}
