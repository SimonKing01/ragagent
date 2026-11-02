package com.ragagent.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.agent.skills.Manager;
import com.ragagent.agent.skills.Skill;
import com.ragagent.agent.skills.SkillSource;
import com.ragagent.contract.agent.SkillCatalogPort;
import com.ragagent.contract.tool.ToolRequest;
import com.ragagent.contract.tool.ToolResult;
import com.ragagent.platform.web.JsonMappers;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code read_file}（技能资源）的行为契约。
 *
 * <p>断言锚在 45C 录像（{@code ToolContractGoldenC} 的 {@code read_file/*}）上：
 * 输出三段式（{@code === File: … ===} / {@code size=…, returned=…} / 围栏内容）、
 * data 键集合、以及每条错误文案；本部署无沙箱，非 {@code skill://} 走
 * {@code exec_no_source} 文案。</p>
 */
class SkillReadFileToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 与录像同形的技能：allowed/{SKILL.md, guide.txt, scripts/run.py}。 */
    private static final class FakeSource implements SkillSource {

        private final Map<String, String> files = new LinkedHashMap<>();
        private final String name;

        FakeSource(String name, Map<String, String> files) {
            this.name = name;
            this.files.putAll(files);
        }

        @Override
        public List<Skill.SkillMetadata> discoverSkills() {
            return List.of(new Skill.SkillMetadata(name, "Test file resources", "skill://" + name));
        }

        @Override
        public Skill loadSkillInstructions(String skillName) {
            require(skillName);
            return Skill.parseSkillFile(files.get(SkillCatalogPort.SKILL_FILE_NAME));
        }

        @Override
        public Skill.SkillFile loadSkillFile(String skillName, String relativePath) {
            require(skillName);
            String content = files.get(relativePath);
            if (content == null) {
                throw new SkillCatalogPort.SkillValidationException("file not found in skill: " + relativePath);
            }
            return new Skill.SkillFile(skillName, relativePath, content, false);
        }

        @Override
        public List<String> listSkillFiles(String skillName) {
            require(skillName);
            return new ArrayList<>(files.keySet());
        }

        @Override
        public String getSkillBasePath(String skillName) {
            require(skillName);
            return "skill://" + name;
        }

        private void require(String skillName) {
            if (!name.equals(skillName)) {
                throw new SkillCatalogPort.SkillValidationException("skill not found: " + skillName);
            }
        }
    }

    private static Map<String, String> allowedFiles() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put(SkillCatalogPort.SKILL_FILE_NAME, """
                ---
                name: 'allowed'
                slug: 'allowed'
                description: 'Test file resources'
                ---

                # allowed

                Follow these steps.
                """);
        files.put("guide.txt", "bundled guide\n");
        files.put("scripts/run.py", "print('hi')\n");
        return files;
    }

    private static Manager manager(List<String> allowedSkills, boolean enabled) {
        Manager manager = new Manager(new Manager.ManagerConfig(
                List.of(new FakeSource("allowed", allowedFiles())), allowedSkills, enabled));
        manager.initialize();
        return manager;
    }

    private static ToolResult run(SkillReadFileTool tool, String json) {
        try {
            return tool.execute(ToolRequest.of(MAPPER.readTree(json)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void exposesRecordedSchemaAndDescription() throws Exception {
        SkillReadFileTool tool = new SkillReadFileTool(manager(List.of(), true));

        assertThat(tool.getName()).isEqualTo("read_file");
        // schema 与描述都是发给模型的字节契约：录像逐字
        assertThat(JsonMappers.toJsonNode(tool.getParameters()).toString())
                .isEqualTo("{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\","
                        + "\"description\":\"Workspace path, skill:// resource, or saved web:// page\"},"
                        + "\"lineOffset\":{\"type\":\"integer\",\"description\":\"Web only: character offset within a long line\"},"
                        + "\"offset\":{\"type\":\"integer\",\"description\":\"1-based line number; continue at next_offset\"},"
                        + "\"limit\":{\"type\":\"integer\",\"description\":\"Maximum lines to return; defaults to 2000.\"},"
                        + "\"maxBytes\":{\"type\":\"integer\",\"description\":\"Text byte budget; at most 65536 (web: 51200)\"}},"
                        + "\"required\":[\"path\"],\"additionalProperties\":false}");
        assertThat(tool.getDescription()).contains("skill://<name>/SKILL.md loads the allowed skill's instructions");
    }

    @Test
    void readsSkillEntrypointLikeRecording() {
        ToolResult result = run(new SkillReadFileTool(manager(List.of(), true)),
                "{\"path\":\"skill://allowed/SKILL.md\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput())
                .startsWith("=== File: skill://allowed/SKILL.md ===\n\nsize=")
                .contains(" bytes\n\n```\n# allowed")
                .endsWith("```\n");
        assertThat(result.getData())
                .containsEntry("binary", false)
                .containsEntry("filePath", "SKILL.md")
                .containsEntry("path", "skill://allowed/SKILL.md")
                .containsEntry("root", "skill://allowed")
                .containsEntry("skillName", "allowed")
                .containsEntry("sessionId", "")
                .containsEntry("truncated", false)
                .containsEntry("startLine", 1);
        assertThat((Integer) result.getData().get("size"))
                .isEqualTo((Integer) result.getData().get("returnedBytes"));
    }

    @Test
    void readsBundledResourceIncludingNestedPath() {
        SkillReadFileTool tool = new SkillReadFileTool(manager(List.of(), true));

        ToolResult guide = run(tool, "{\"path\":\"skill://allowed/guide.txt\"}");
        assertThat(guide.isSuccess()).isTrue();
        // 录像逐字：三段式输出 + 围栏
        assertThat(guide.getOutput()).isEqualTo(
                "=== File: skill://allowed/guide.txt ===\n\nsize=14 bytes, returned=14 bytes\n\n```\nbundled guide\n```\n");
        assertThat(guide.getData()).containsEntry("filePath", "guide.txt");

        ToolResult nested = run(tool, "{\"path\":\"skill://allowed/scripts/run.py\"}");
        assertThat(nested.isSuccess()).isTrue();
        assertThat(nested.getData()).containsEntry("filePath", "scripts/run.py");
        assertThat(nested.getOutput()).contains("print('hi')");
    }

    @Test
    void rejectsNonCanonicalRelativePaths() {
        SkillReadFileTool tool = new SkillReadFileTool(manager(List.of(), true));
        for (String uri : List.of(
                "skill://allowed/../other/SKILL.md",
                "skill://allowed/./guide.txt",
                "skill://allowed//guide.txt",
                "skill://allowed/scripts//run.py")) {
            ToolResult result = run(tool, "{\"path\":\"" + uri + "\"}");
            assertThat(result.isSuccess()).as(uri).isFalse();
            assertThat(result.getError()).as(uri)
                    .isEqualTo("skill resource must have a canonical relative file path without traversal");
        }
    }

    @Test
    void rejectsBadFormAndUnknownOrUnlistedSkills() {
        SkillReadFileTool tool = new SkillReadFileTool(manager(List.of(), true));
        for (String uri : List.of("skill://", "skill://allowed", "skill://allowed/", "skill:///SKILL.md")) {
            assertThat(run(tool, "{\"path\":\"" + uri + "\"}").getError())
                    .as(uri).isEqualTo("use skill://<name>/SKILL.md or skill://<name>/<relative-file>");
        }
        assertThat(run(tool, "{\"path\":\"skill://unknown/SKILL.md\"}").getError())
                .isEqualTo("skill \"unknown\" is not available to this agent");

        // agent 只勾选了 allowed：不在名单里的技能同样报「不可用」
        SkillReadFileTool scoped = new SkillReadFileTool(manager(List.of("allowed"), true));
        assertThat(run(scoped, "{\"path\":\"skill://other/SKILL.md\"}").getError())
                .isEqualTo("skill \"other\" is not available to this agent");
    }

    @Test
    void reportsUnavailableSourcesInsteadOfPretending() {
        SkillReadFileTool disabled = new SkillReadFileTool(manager(List.of(), false));
        assertThat(run(disabled, "{\"path\":\"skill://allowed/SKILL.md\"}").getError())
                .isEqualTo("skills are not enabled for this reader");

        SkillReadFileTool tool = new SkillReadFileTool(manager(List.of(), true));
        // 无沙箱部署：workspace / web:// 明确报错（录像 exec_no_source 文案）
        assertThat(run(tool, "{\"path\":\"/workspace/output/report.txt\"}").getError())
                .isEqualTo("workspace file access is unavailable; only listed skill resources can be read");
        assertThat(run(tool, "{\"path\":\"web://page/1\"}").getError())
                .isEqualTo("workspace file access is unavailable; only listed skill resources can be read");
        assertThat(run(tool, "{}").getError())
                .isEqualTo("path is required; use a known workspace path or a skill resource from the available skills");
        assertThat(run(tool, "{\"path\":\"skill://allowed/SKILL.md\",\"lineOffset\":5}").getError())
                .isEqualTo("line_offset is supported only for saved web:// pages");
    }

    @Test
    void paginatesByOffsetLimitAndByteBudget() {
        SkillReadFileTool tool = new SkillReadFileTool(manager(List.of(), true));

        ToolResult page = run(tool, "{\"path\":\"skill://allowed/SKILL.md\",\"offset\":2,\"limit\":1}");
        assertThat(page.isSuccess()).isTrue();
        assertThat(page.getData())
                .containsEntry("startLine", 2)
                .containsEntry("endLine", 2)
                .containsEntry("truncated", true)
                .containsEntry("nextOffset", 3);

        ToolResult tiny = run(tool, "{\"path\":\"skill://allowed/SKILL.md\",\"maxBytes\":10}");
        assertThat(tiny.isSuccess()).isTrue();
        assertThat(tiny.getData()).containsEntry("truncated", true);
        assertThat((Integer) tiny.getData().get("returnedBytes")).isLessThanOrEqualTo(10);
    }

    @Test
    void uriParsingRules() {
        assertThat(SkillReadFileTool.splitSkillUri("skill://a/SKILL.md")).containsExactly("a", "SKILL.md");
        assertThat(SkillReadFileTool.splitSkillUri("skill://a/b/c.md")).containsExactly("a", "b/c.md");
        assertThat(SkillReadFileTool.splitSkillUri("skill://a")).isNull();
        assertThat(SkillReadFileTool.splitSkillUri("skill://")).isNull();
        assertThat(SkillReadFileTool.splitSkillUri("/workspace/x.txt")).isNull();

        assertThat(SkillReadFileTool.isCanonicalRelative("SKILL.md")).isTrue();
        assertThat(SkillReadFileTool.isCanonicalRelative("scripts/run.py")).isTrue();
        assertThat(SkillReadFileTool.isCanonicalRelative("../x")).isFalse();
        assertThat(SkillReadFileTool.isCanonicalRelative("./x")).isFalse();
        assertThat(SkillReadFileTool.isCanonicalRelative("a//b")).isFalse();
        assertThat(SkillReadFileTool.isCanonicalRelative("/abs")).isFalse();
        assertThat(SkillReadFileTool.isCanonicalRelative("a\\b")).isFalse();
        assertThat(SkillReadFileTool.isCanonicalRelative("")).isFalse();
    }
}
