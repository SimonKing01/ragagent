package com.ragagent.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agent.skills.Manager;
import com.ragagent.agent.skills.Skill;
import com.ragagent.contract.agent.SkillCatalogPort;
import com.ragagent.contract.tool.BaseTool;
import com.ragagent.contract.tool.ToolDefinitions;
import com.ragagent.contract.tool.ToolRequest;
import com.ragagent.contract.tool.ToolResult;
import com.ragagent.platform.tool.ToolSchemas;
import com.ragagent.platform.web.JsonMappers;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code read_file}：技能资源读取（无沙箱部署下技能是唯一可读的"文件源"）。
 *
 * <p><b>为什么需要它</b>：技能三级注入里，Level 1（名称+描述）进系统提示词，
 * Level 2/3（正文与附随文件）按需读取——而按需读取的唯一通道就是本工具
 * （提示词里给模型的路径是 {@code skill://<name>/SKILL.md}）。技能入库时
 * {@link Manager} 的读面已就绪，但工具侧一度缺位——模型看得到技能清单、却读不到正文，
 * 本工具即为此而设。</p>
 *
 * <p><b>契约以回归录像为准</b>（{@code ToolContractGoldenC} 的 {@code read_file/*} 31 条记录）：
 * schema、描述、输出格式（{@code === File: … === / size=… / ```内容```}）、data 键集合
 * （{@code path/file_path/root/skill_name/size/returned_bytes/start_line/end_line/total_lines/truncated}）、
 * 以及错误文案（越界走 {@code canonical relative file path without traversal}、
 * 不在允许名单走 {@code skill "x" is not available to this agent} 等）逐条对齐。</p>
 *
 * <p><b>本部署的范围</b>：沙箱不再使用 → {@code /workspace} 与 {@code web://} 无实现，
 * 非 {@code skill://} 路径按录像的 {@code exec_no_source} 文案明确报错（不假装存在）。</p>
 */
public class SkillReadFileTool extends BaseTool {

    /** 录像 read_file/schema 逐字。 */
    private static final String SCHEMA_JSON = """
            {"type":"object","properties":{"path":{"type":"string","description":"Workspace path, skill:// resource, or saved web:// page"},"lineOffset":{"type":"integer","description":"Web only: character offset within a long line"},"offset":{"type":"integer","description":"1-based line number; continue at next_offset"},"limit":{"type":"integer","description":"Maximum lines to return; defaults to 2000."},"maxBytes":{"type":"integer","description":"Text byte budget; at most 65536 (web: 51200)"}},"required":["path"],"additionalProperties":false}""";

    /** 录像 read_file/description_skills_noshell 逐字。 */
    private static final String TOOL_DESCRIPTION = """
            Read text from the available file sources.
            Skill resources: skill://<name>/SKILL.md loads the allowed skill's instructions, file list and execution guidance; skill://<name>/<relative-file> reads a bundled resource. These are package resources, not shell paths or arbitrary host files.
            offset is a 1-based line number; limit defaults to 2000 lines. max_bytes is capped at 65536; the tool output budget also applies. Continue at the returned next_offset when truncated. Binary content is suppressed.""";

    static final int DEFAULT_LIMIT = 2000;
    static final int MAX_BYTES = 65536;

    static final String ERR_PATH_REQUIRED =
            "path is required; use a known workspace path or a skill resource from the available skills";
    static final String ERR_LINE_OFFSET =
            "line_offset is supported only for saved web:// pages";
    static final String ERR_NO_SOURCE =
            "workspace file access is unavailable; only listed skill resources can be read";
    static final String ERR_SKILLS_OFF =
            "skills are not enabled for this reader";
    static final String ERR_BAD_FORM =
            "use skill://<name>/SKILL.md or skill://<name>/<relative-file>";
    static final String ERR_TRAVERSAL =
            "skill resource must have a canonical relative file path without traversal";

    private final Manager skills;

    public SkillReadFileTool(Manager skills) {
        super(ToolDefinitions.TOOL_READ_FILE, TOOL_DESCRIPTION, ToolSchemas.parse(SCHEMA_JSON));
        this.skills = skills;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        JsonNode args = JsonMappers.toJsonNode(request.args());
        String path = args == null ? "" : args.path("path").asText("");
        if (args != null && args.path("lineOffset").asInt(0) != 0) {
            // 录像：line_offset 只对保存的 web:// 页面有意义
            return fail(ERR_LINE_OFFSET);
        }
        if (path.isBlank()) {
            return fail(ERR_PATH_REQUIRED);
        }
        if (!path.startsWith("skill://")) {
            // 沙箱移除：workspace / web:// 在本部署没有实现，明确报错而不是假装读到空
            return fail(ERR_NO_SOURCE);
        }
        if (skills == null || !skills.isEnabled()) {
            return fail(ERR_SKILLS_OFF);
        }

        String[] target = splitSkillUri(path);
        if (target == null) {
            return fail(ERR_BAD_FORM);
        }
        String skillName = target[0];
        String relative = target[1];
        if (!isCanonicalRelative(relative)) {
            return fail(ERR_TRAVERSAL);
        }
        if (!allowedSkillNames(skills).contains(skillName)) {
            return fail("skill \"" + skillName + "\" is not available to this agent");
        }

        String content;
        try {
            content = relative.equals(SkillCatalogPort.SKILL_FILE_NAME) || relative.endsWith("/" + SkillCatalogPort.SKILL_FILE_NAME)
                    ? skills.loadSkill(skillName).instructions
                    : skills.readSkillFile(skillName, relative);
        } catch (SkillCatalogPort.SkillValidationException e) {
            return fail(e.getMessage() == null ? "failed to read " + path : e.getMessage());
        } catch (Exception e) {
            return fail("failed to read " + path + ": " + e.getMessage());
        }
        return readResult(skillName, path, relative, content, args);
    }

    // ── 组装（可单测）────────────────────────────────────────────────────

    private ToolResult readResult(String skillName, String uri, String relative, String content, JsonNode args) {
        int offset = args == null ? 1 : Math.max(1, args.path("offset").asInt(1));
        int limit = args == null ? DEFAULT_LIMIT : args.path("limit").asInt(DEFAULT_LIMIT);
        int maxBytes = args == null ? MAX_BYTES : args.path("maxBytes").asInt(MAX_BYTES);
        if (limit <= 0) {
            limit = DEFAULT_LIMIT;
        }
        if (maxBytes <= 0 || maxBytes > MAX_BYTES) {
            maxBytes = MAX_BYTES;
        }

        int size = content.getBytes(StandardCharsets.UTF_8).length;
        Slice slice = slice(content, offset, limit, maxBytes);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("binary", false);
        data.put("endLine", slice.endLine());
        data.put("filePath", relative);
        data.put("path", uri);
        data.put("returnedBytes", slice.returnedBytes());
        data.put("root", "skill://" + skillName);
        data.put("sessionId", "");
        data.put("size", size);
        data.put("skillName", skillName);
        data.put("startLine", slice.startLine());
        data.put("totalLines", slice.totalLines());
        data.put("truncated", slice.truncated());
        if (slice.truncated()) {
            data.put("nextOffset", slice.nextOffset());
        }

        ToolResult result = new ToolResult();
        result.setSuccess(true);
        result.setOutput("=== File: " + uri + " ===\n\nsize=" + size + " bytes, returned="
                + slice.returnedBytes() + " bytes\n\n```\n" + slice.text() + "```\n");
        result.setData(data);
        return result;
    }

    /** 行/字节双限切片（与录像的 offset/limit/max_bytes 语义一致）。 */
    record Slice(String text, int startLine, int endLine, int totalLines, int returnedBytes,
            boolean truncated, int nextOffset) {
    }

    static Slice slice(String content, int offset, int limit, int maxBytes) {
        List<String> lines = new ArrayList<>(List.of(content.split("\n", -1)));
        // 行号按"可见行"计：尾随换行不额外算一行（录像：12 字节的 run.py 是 total_lines=1）
        boolean trailingNewline = content.endsWith("\n");
        if (trailingNewline && !lines.isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        int total = lines.size();
        int from = Math.min(offset - 1, total);
        int to = Math.min(from + limit, total);
        String text = String.join("\n", lines.subList(from, to));
        if (to == total && trailingNewline && from < to) {
            // 读到文件尾时保留原始结尾换行，字节数与 size 一致
            text = text + "\n";
        }
        boolean truncated = to < total;

        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) {
            text = new String(bytes, 0, maxBytes, StandardCharsets.UTF_8);
            // 截断到字节预算时可能有半个多字节字符：去掉尾部替换符
            int cut = 0;
            while (cut < text.length() && text.charAt(text.length() - 1 - cut) == '\uFFFD') {
                cut++;
            }
            if (cut > 0) {
                text = text.substring(0, text.length() - cut);
            }
            truncated = true;
        }
        return new Slice(text, from + 1, to, total,
                text.getBytes(StandardCharsets.UTF_8).length, truncated, to + 1);
    }

    // ── 纯逻辑（可单测）──────────────────────────────────────────────────

    /**
     * {@code skill://<name>/<relative-file>} → {@code [name, relative]}；
     * 形态不合法（没有 name 或没有 "/相对路径"）返回 {@code null}（对应录像的 bad_form 文案）。
     */
    static String[] splitSkillUri(String uri) {
        if (uri == null || !uri.startsWith("skill://")) {
            return null;
        }
        String rest = uri.substring("skill://".length());
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash == rest.length() - 1) {
            return null;
        }
        String name = rest.substring(0, slash);
        String relative = rest.substring(slash + 1);
        if (name.isBlank() || relative.isBlank()) {
            return null;
        }
        return new String[] {name, relative};
    }

    /** 相对路径必须是规范的（无 {@code ..}/{@code .} 段、无双斜杠、不以 / 开头、无反斜杠）。 */
    static boolean isCanonicalRelative(String relative) {
        if (relative == null || relative.isBlank()) {
            return false;
        }
        if (relative.startsWith("/") || relative.contains("\\") || relative.contains("//")) {
            return false;
        }
        for (String segment : relative.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    /** 该 agent 可见的技能名（选择器/装配已按 allowedSkills 过滤，此处只做名单判定）。 */
    private static java.util.Set<String> allowedSkillNames(Manager skills) {
        java.util.Set<String> names = new java.util.HashSet<>();
        List<Skill.SkillMetadata> metadata = skills.getAllMetadata();
        if (metadata != null) {
            for (Skill.SkillMetadata m : metadata) {
                if (m != null && m.name() != null) {
                    names.add(m.name());
                }
            }
        }
        return names;
    }

    private static ToolResult fail(String message) {
        ToolResult result = new ToolResult();
        result.setSuccess(false);
        result.setError(message);
        return result;
    }
}
