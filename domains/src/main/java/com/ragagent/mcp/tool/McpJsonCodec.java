package com.ragagent.mcp.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.contract.tool.ToolDefinitions;
import com.ragagent.contract.tool.ToolResult;
import com.ragagent.platform.web.JsonMappers;
import com.ragagent.platform.web.ToolJson;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 目录的 JSON 编解码：插入序 struct 输出、schema enum 注入、调用参数解码与 cursor。
 *
 * <p>与目录状态机无关；键序是线格式契约（struct 字段声明序，不能按 map 排序），
 * 所以单独一类承载。</p>
 */
final class McpJsonCodec {

    /**
     * struct 形态 JSON 编码器：插入序（字段声明序）。
     * 不能用 {@link ToolJson#write}（它按 map 语义排序键，struct 契约会乱序）。
     */
    static final ObjectMapper STRUCT_JSON = JsonMappers.lenient();

    private McpJsonCodec() {
    }

    /** 结果 JSON（插入序键序；序列化失败走 error 页）。 */
    static ToolResult mcpJsonResult(Object value) {
        try {
            return mcpJsonResult(STRUCT_JSON.writeValueAsString(value));
        } catch (Exception e) {
            return mcpDiscoveryFailure("MCP definition is not valid JSON", "error");
        }
    }

    /** Output 文本直用的重载（页已序列化过时避免二次转换）。 */
    static ToolResult mcpJsonResult(String json) {
        ToolResult r = new ToolResult();
        r.setSuccess(true);
        r.setOutput(json);
        return r;
    }

    /** 目录不可用的统一失败形态。 */
    static ToolResult mcpDiscoveryFailure(String err, String status) {
        ToolResult r = new ToolResult();
        r.setSuccess(false);
        r.setError(err);
        Map<String, Object> data = new HashMap<>();
        data.put("status", status);
        r.setData(data);
        return r;
    }

    /** 把 enum 注入 schema 的 properties[key]（map 键序经 ToolJson 排序）。 */
    static String mcpSchemaWithEnum(String raw, String key, List<String> values) {
        if (values == null || values.isEmpty()) {
            return raw;
        }
        try {
            ObjectMapper plain = JsonMappers.lenient();
            JsonNode schema = plain.readTree(raw);
            ((com.fasterxml.jackson.databind.node.ObjectNode) schema.path("properties").path(key))
                    .set("enum", plain.valueToTree(values));
            return ToolJson.write(schema);
        } catch (Exception e) {
            return raw;
        }
    }

    /** ref 解码；失败 message 以 mcpCallArgumentsHint 结尾。 */
    static DecodeResult decodeMcpCall(JsonNode raw) {
        String toolRef = raw.path("toolRef").asText("");
        JsonNode arguments = raw.get("arguments");
        boolean bad = toolRef.isEmpty()
                || arguments == null
                || arguments.isNull()
                || arguments.isMissingNode()
                || !arguments.isObject();
        if (bad) {
            throw new IllegalArgumentException(
                    "toolRef and an arguments object are required."
                            + ToolDefinitions.MCP_CALL_ARGUMENTS_HINT);
        }
        return new DecodeResult(toolRef, arguments);
    }

    record DecodeResult(String toolRef, JsonNode arguments) {
    }

    /** cursor 的 base64url 编码（无 padding）。 */
    static String b64UrlEncode(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    static byte[] b64UrlDecode(String s) {
        return Base64.getUrlDecoder().decode(s);
    }

    /** JSON 字符串字面量（保留既有转义形状）。 */
    static String quoteGo(String s) {
        return ToolJson.quoted(s);
    }
}
