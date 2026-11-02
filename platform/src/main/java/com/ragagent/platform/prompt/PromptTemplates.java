package com.ragagent.platform.prompt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.platform.web.JsonMappers;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提示词模板的按 id 取值（L1 唯一 YAML 解析点）。
 *
 * <p>用途：把"某个模板 id 的 content/user"给到需要**静态生效值**的消费方
 * （例：评估参数默认值），避免在 Java 里再抄一份字面量造成双源漂移。</p>
 *
 * <p>解析沿用本仓既有组合：snakeyaml → Jackson（classpath 无
 * jackson-dataformat-yaml）。按文件缓存解析结果，进程内只解析一次；
 * 文件缺失/损坏 → 空表，调用方按各自降级策略处理。</p>
 */
public final class PromptTemplates {

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    /** fileName → (templateId → 模板节点)。 */
    private static final Map<String, Map<String, JsonNode>> CACHE = new ConcurrentHashMap<>();

    private PromptTemplates() {
    }

    /** 取模板 {@code content}；文件或 id 缺失 → ""。 */
    public static String content(String fileName, String templateId) {
        return field(fileName, templateId, "content");
    }

    /** 取模板 {@code user}；文件或 id 缺失 → ""。 */
    public static String user(String fileName, String templateId) {
        return field(fileName, templateId, "user");
    }

    private static String field(String fileName, String templateId, String field) {
        JsonNode template = templates(fileName).get(templateId);
        return template == null ? "" : template.path(field).asText("");
    }

    /** 装载并缓存某文件里 id → 模板节点（首个 id 命中胜）。 */
    private static Map<String, JsonNode> templates(String fileName) {
        return CACHE.computeIfAbsent(fileName, name -> {
            Map<String, JsonNode> byId = new LinkedHashMap<>();
            try (var in = PromptSource.open(name)) {
                if (in == null) {
                    return byId;
                }
                JsonNode root = MAPPER.valueToTree(new org.yaml.snakeyaml.Yaml().load(in));
                JsonNode list = root == null ? null : root.get("templates");
                if (list != null && list.isArray()) {
                    for (JsonNode t : list) {
                        String id = t.path("id").asText("");
                        if (!id.isEmpty()) {
                            byId.putIfAbsent(id, t);
                        }
                    }
                }
            } catch (Exception e) {
                // 模板缺失/损坏 → 空表（消费方各自降级，不打断启动）
            }
            return byId;
        });
    }
}
