package com.ragagent.agenttools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agent.fixtures.RecordingSupport;
import com.ragagent.agent.fixtures.ToolContractGoldenA;
import com.ragagent.contract.tool.ToolCapabilities;
import com.ragagent.contract.tool.ToolCapabilities.KbCapability;
import com.ragagent.contract.tool.ToolCapabilities.KbCaps;
import com.ragagent.contract.tool.ToolCapabilities.KbFilter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ToolCapabilities 的录制判定（12 组：{@code deriveKbFilter*}
 * {@code /kbSatisfies* /toolsConsumeFiles}）。AnyOf 的成员序在录制侧不定
 * （map 迭代随机序），录制时已排序——Java 侧按集合比对；布尔判定逐值比对。
 */
class ToolCapabilitiesRecordingTest {

    private static final String[] CASES = {
            "R_CAPS_ONLY_UNKNOWN",
            "R_CAPS_THINKING_ONLY",
            "R_CAPS_RETRIEVAL",
            "R_CAPS_WIKI",
            "R_CAPS_MIXED",
            "R_CAPS_QUICK_ANSWER",
            "R_CAPS_QUICK_ANSWER_WIKI_ONLY",
            "R_CAPS_NORMAL_MODE_WIKI_ONLY",
            "R_CAPS_EMPTY_TOOLS_CONSUME_FILES",
            "R_CAPS_UNKNOWN_TOOL_CONSUME_FILES",
            "R_CAPS_NONFILE_TOOL_CONSUME_FILES",
            "R_CAPS_FILE_TOOL_CONSUME_FILES",
    };

    @Test
    void capabilityGatingMatchesAgentPromptGolden() {
        for (String name : CASES) {
            JsonNode r = RecordingSupport.rec(field(name));
            List<String> tools = new ArrayList<>();
            r.get("tools").forEach(n -> tools.add(n.asText()));
            String mode = r.get("mode").asText();
            KbCaps caps = capsOf(r);

            assertThat(capSet(ToolCapabilities.deriveKbFilterFromTools(tools)))
                    .as("deriveKbFilterFromTools %s", r.get("id").asText())
                    .containsExactlyInAnyOrderElementsOf(capList(r.get("filter")));
            assertThat(capSet(ToolCapabilities.deriveKbFilterForAgent(mode, tools)))
                    .as("deriveKbFilterForAgent %s", r.get("id").asText())
                    .containsExactlyInAnyOrderElementsOf(capList(r.get("agentFilter")));
            assertThat(ToolCapabilities.kbSatisfiesToolRequirements(caps, tools))
                    .as("kbSatisfiesToolRequirements %s", r.get("id").asText())
                    .isEqualTo(r.get("satTools").asBoolean());
            assertThat(ToolCapabilities.kbSatisfiesAgentRequirements(caps, mode, tools))
                    .as("kbSatisfiesAgentRequirements %s", r.get("id").asText())
                    .isEqualTo(r.get("satAgent").asBoolean());
            assertThat(ToolCapabilities.toolsConsumeFiles(tools))
                    .as("toolsConsumeFiles %s", r.get("id").asText())
                    .isEqualTo(r.get("consume").asBoolean());
        }
    }

    private static KbCaps capsOf(JsonNode r) {
        // 探针里 caps 是按 case 编码的常量；从录制反推不可行，按 id 重建
        // （caps 只进布尔判定，录制值已含判定结果）。
        return switch (r.get("id").asText()) {
            case "quick_answer" -> new KbCaps(true, false, false, false, false);
            case "quick_answer_wiki_only", "normal_mode_wiki_only" -> new KbCaps(false, false, true, false, false);
            default -> KbCaps.NONE;
        };
    }

    private static List<String> capList(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static List<String> capSet(KbFilter f) {
        List<String> out = new ArrayList<>();
        if (f.anyOf() != null) {
            for (KbCapability c : f.anyOf()) {
                out.add(c.value);
            }
        }
        return out;
    }

    private static String field(String name) {
        try {
            return (String) ToolContractGoldenA.class.getField(name).get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
