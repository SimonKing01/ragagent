package com.ragagent.session.qa;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.contract.agent.AgentStep;
import com.ragagent.session.domain.message.Message;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** QA 编排的共享小工具（阶段 5 从 {@code KnowledgeQaController} 的静态助手迁出）。 */
public final class QaTurnSupport {

    private QaTurnSupport() {
    }

    /** 文本数组字段解析（非数组/非文本项忽略）。 */
    public static List<String> stringListOf(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode n : arr) {
                if (n.isTextual()) {
                    out.add(n.asText());
                }
            }
        }
        return out;
    }

    /** 快速回答阶段的 step 占位（消息还没有 agentSteps 时补一个）。 */
    public static AgentStep ensureQuickAnswerStep(Message msg) {
        if (msg.getAgentSteps() == null || msg.getAgentSteps().isEmpty()) {
            AgentStep step = new AgentStep();
            step.setIteration(0);
            step.setTimestamp(OffsetDateTime.now());
            step.setToolCalls(new ArrayList<>());
            List<AgentStep> steps = new ArrayList<>();
            steps.add(step);
            msg.setAgentSteps(steps);
        }
        return msg.getAgentSteps().get(0);
    }

    /** 把快速回答的推理内容追加到 step（空内容忽略）。 */
    public static void appendQuickAnswerReasoning(Message msg, String content) {
        if (content == null || content.isEmpty()) {
            return;
        }
        AgentStep step = ensureQuickAnswerStep(msg);
        step.setReasoningContent(orEmpty(step.getReasoningContent()) + content);
    }

    /** null → 空串。 */
    static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    /** dev 缺省（100MB 上传闸门同形）。 */
    public static long maxFileBytes() {
        return 100L * 1024 * 1024;
    }
}
