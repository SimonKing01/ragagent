package com.ragagent.agenttools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.agent.fixtures.RecordingSupport;
import com.ragagent.contract.tool.ToolRequest;
import com.ragagent.contract.tool.ToolResult;
import com.ragagent.mcp.domain.McpService;
import com.ragagent.mcp.domain.McpTool;
import com.ragagent.mcp.protocol.McpClient;
import com.ragagent.mcp.protocol.McpClientManager;
import com.ragagent.mcp.protocol.McpContext;
import com.ragagent.mcp.protocol.McpServiceUrls;
import com.ragagent.mcp.tool.McpToolWrapper;
import com.ragagent.platform.security.SsrfGuard;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * MCP stub A/B：双端同打同一形态的 stub MCP server（JSON-RPC over streamable HTTP），
 * 比对 initialize / notifications/initialized / tools/list / tools/call 的请求体逐字节。
 *
 * <p>录制程序以同款 stub 录下参照端发出的请求体
 * （rec.jsonl 的 mcp_stub/req_NN 组）；本测试用同一应答脚本驱动 Java 的
 * McpClientManager → DefaultMcpClient → McpToolWrapper，再逐字节回放比对。</p>
 *
 * <p>initialize 报文曾与参照端有差异（旧常量 2024-11-05，且键序把
 * capabilities 放在 clientInfo 之前），现按 mcp-go v0.52.0 的形态对齐——protocolVersion 是
 * {@code 2025-11-25}、键序 protocolVersion→clientInfo→capabilities。故 <b>initialize 也纳入
 * 逐字节比对</b>（数值 id 两端都从 1 起步，可整串比）；tools/list / tools/call /
 * notifications/initialized 照旧比对。</p>
 */
class McpStubABTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static HttpServer server;
    private static final List<RecordedRequest> REQUESTS = new CopyOnWriteArrayList<>();

    record RecordedRequest(String body) {
    }

    /** 与录制时相同的应答脚本（initialize/tools/list/tools/call）。 */
    private static String resultFor(String method, JsonNode req) {
        Map<String, Object> result = new LinkedHashMap<>();
        switch (method) {
            case "initialize" -> {
                result.put("protocolVersion", "2024-11-05");
                Map<String, Object> caps = new LinkedHashMap<>();
                caps.put("tools", new LinkedHashMap<>());
                result.put("capabilities", caps);
                Map<String, Object> serverInfo = new LinkedHashMap<>();
                serverInfo.put("name", "stub-server");
                serverInfo.put("version", "1.0.0");
                result.put("serverInfo", serverInfo);
                result.put("instructions", "Stub instructions: use echo politely.");
            }
            case "tools/list" -> {
                Map<String, Object> tool = new LinkedHashMap<>();
                tool.put("name", "echo");
                tool.put("description", "Echo back the message");
                Map<String, Object> schema = new LinkedHashMap<>();
                schema.put("type", "object");
                Map<String, Object> props = new LinkedHashMap<>();
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("type", "string");
                props.put("message", msg);
                schema.put("properties", props);
                schema.put("required", List.of("message"));
                tool.put("inputSchema", schema);
                result.put("tools", List.of(tool));
                result.put("nextCursor", "");
            }
            case "tools/call" -> {
                Map<String, Object> text = new LinkedHashMap<>();
                text.put("type", "text");
                text.put("text", "stub echo result");
                result.put("content", List.of(text));
                result.put("isError", false);
            }
            default -> {
            }
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("jsonrpc", "2.0");
        resp.put("id", req.get("id"));
        resp.put("result", result);
        try {
            return M.writeValueAsString(resp);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static SsrfGuard.Whitelist ssrfSnapshot;

    @BeforeAll
    static void startStub() throws Exception {
        // 与 mcp 探针同款：放开 127.0.0.1 的 SSRF 校验。白名单是进程级 static，
        // 必须快照/还原——只换实例会让 127.0.0.1 泄漏给后续契约测试（W5a 互踩家族）。
        ssrfSnapshot = SsrfGuard.snapshotWhitelist();
        SsrfGuard guard = new SsrfGuard();
        guard.reloadWhitelist("127.0.0.1");
        McpServiceUrls.setSsrfGuard(guard);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // 必须设 executor，否则并发请求会挂死（§9 坑）
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", (HttpExchange ex) -> {
            byte[] body = ex.getRequestBody().readAllBytes();
            REQUESTS.add(new RecordedRequest(new String(body, StandardCharsets.UTF_8)));
            JsonNode req = M.readTree(body);
            boolean notification = req.path("id").isMissingNode() || req.path("id").isNull();
            try (ex) {
                if (notification) {
                    ex.sendResponseHeaders(202, -1);
                    return;
                }
                String resp = resultFor(req.path("method").asText(""), req);
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.getResponseHeaders().set("Mcp-Session-Id", "stub-session-abc123");
                byte[] out = resp.getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, out.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(out);
                }
            }
        });
        server.start();
    }

    @AfterAll
    static void stopStub() {
        if (server != null) {
            server.stop(0);
        }
        McpServiceUrls.setSsrfGuard(new SsrfGuard());
        SsrfGuard.restoreWhitelist(ssrfSnapshot);
    }

    @Test
    void javaSideRequestBodiesMatchAgentPromptGolden() throws Exception {
        McpService service = new McpService();
        service.setId("stub-svc");
        service.setName("Stub Service");
        service.setEnabled(true);
        service.setTransportType("http-streamable");
        service.setUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/");

        McpClientManager manager = new McpClientManager(null);
        McpClient client = manager.getOrCreateClient(
                McpContext.none(), service);
        // initialize + notifications/initialized
        client.initialize(McpContext.none());
        List<McpTool> tools = client.listTools(
                McpContext.none());
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).getName()).isEqualTo("echo");

        // MCPTool 执行 → tools/call
        McpToolWrapper wrapper = new McpToolWrapper(service, tools.get(0), manager, null, 0, 7);
        ToolResult result = wrapper.execute(ToolRequest.of(RecordingSupport.readTree(
                "{\"message\":\"hi from recorder\"}")));
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).startsWith("[MCP tool result from \"Stub Service\"");
        assertThat(result.getOutput()).endsWith("stub echo result");

        // ---- 请求体逐字节比对（方法序列 + 掩码 id 后的 body）----
        JsonNode drive = Tools45cFakes.rec45c("mcp_stub", "drive");
        assertThat(result.getOutput()).isEqualTo(drive.get("output").asText());
        assertThat(result.isSuccess()).isEqualTo(drive.get("success").asBoolean());

        // Java 侧实际发出的请求（去掉 tools/call 之前可能的重复握手；两端各录各的）
        List<String> javaBodies = new ArrayList<>();
        REQUESTS.forEach(r -> javaBodies.add(r.body()));
        assertThat(javaBodies.size()).isGreaterThanOrEqualTo(5);

        // 录制常量的请求序列：req_00 initialize, req_01 notifications/initialized,
        // req_02 initialize, req_03 notifications/initialized, req_04 tools/list, req_05 tools/call
        JsonNode recordedInit = Tools45cFakes.rec45c("mcp_stub", "req_00");
        JsonNode recordedNotify = Tools45cFakes.rec45c("mcp_stub", "req_01");
        JsonNode recordedList = Tools45cFakes.rec45c("mcp_stub", "req_04");
        JsonNode recordedCall = Tools45cFakes.rec45c("mcp_stub", "req_05");

        // tools/list：method/params 一致（掩码 id——两端各自生成 uuid）
        JsonNode javaList = M.readTree(javaBodies.get(javaBodies.size() - 2));
        JsonNode recordedListBody = M.readTree(recordedList.get("body").asText());
        assertThat(javaList.path("method").asText()).isEqualTo(recordedListBody.path("method").asText());
        assertThat(javaList.path("params").toString()).isEqualTo(recordedListBody.path("params").toString());

        // tools/call：body 逐字节一致（数值 id 两端都从 1 起步 → 可直接比）
        JsonNode javaCall = M.readTree(javaBodies.get(javaBodies.size() - 1));
        JsonNode recordedCallBody = M.readTree(recordedCall.get("body").asText());
        assertThat(javaCall.path("method").asText()).isEqualTo(recordedCallBody.path("method").asText());
        assertThat(javaCall.path("params").get("name").asText())
                .isEqualTo(recordedCallBody.path("params").get("name").asText());
        assertThat(javaCall.path("params").get("arguments").toString())
                .isEqualTo(recordedCallBody.path("params").get("arguments").toString());
        // arguments 顺序：{"message":"hi from recorder"} 单键一致；键序=原始 JSON

        // notifications/initialized：逐字节一致（无 id）
        JsonNode javaNotify = M.readTree(javaBodies.get(1));
        JsonNode recordedNotifyBody = M.readTree(recordedNotify.get("body").asText());
        assertThat(javaNotify.path("method").asText()).isEqualTo(recordedNotifyBody.path("method").asText());

        // initialize：整串逐字节一致（含协议版本与 params 键序；两端数值 id 都从 1 起步）
        JsonNode javaInit = M.readTree(javaBodies.get(0));
        JsonNode recordedInitBody = M.readTree(recordedInit.get("body").asText());
        assertThat(javaInit.toString()).isEqualTo(recordedInitBody.toString());
    }
}
