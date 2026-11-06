package com.ragagent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.ragagent.agent.budget.TokenEstimator;
import com.ragagent.agent.compaction.CompactionSettings;
import com.ragagent.agent.domain.AgentState;
import com.ragagent.agent.fixtures.ContractJson;
import com.ragagent.agent.prompt.PinnedMCPServiceInfo;
import com.ragagent.agent.prompt.PinnedSkillInfo;
import com.ragagent.contract.agent.AgentToolCall;
import com.ragagent.contract.agent.KnowledgeBaseInfo;
import com.ragagent.contract.agent.RecentDocInfo;
import com.ragagent.contract.agent.SelectedDocumentInfo;
import com.ragagent.contract.event.EventBusInterface;
import com.ragagent.contract.model.ChatMessage;
import com.ragagent.contract.model.ChatResponse;
import com.ragagent.contract.model.ChatTool;
import com.ragagent.contract.model.FunctionCall;
import com.ragagent.contract.model.StreamResponse;
import com.ragagent.contract.model.TokenUsage;
import com.ragagent.platform.event.EventJson;
import com.ragagent.platform.web.JsonMappers;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 引擎录制回放：Java 引擎用同一脚本驱动，事件序列 / AgentState 快照 /
 * stub 收到的消息与选项 / steer 注入路径与 {@link AgentExecutionGolden} 的录制常量比对。
 *
 * <p>比对方式为「{@link com.ragagent.agent.fixtures.ContractJson#deep}
 * 语义比较」：键序 / HTML 转义形态 / 时间写法不再构成断言目标；
 * 录制文件保留为历史基准（禁止手改）。</p>
 *
 * <p>掩码约定见 {@link Engine46bStubSupport}。LLM 全走 stub（纪律：真实 LLM 链路零测试）；
 * 全部纯单测，无 @SpringBootTest。</p>
 */
class EngineRecordingTest {

    /**
     * 录制语义比较：两侧经 {@link ContractJson#deep} 归一后比较——
     * 键序 / HTML 转义形态 / 时间写法不再构成断言目标（与 ContractJson 的既定方针一致；
     * golden 常量保留为历史基准，不再逐字节对齐）。
     */
    /**
     * 系统提示词正文不参与录制比对：{@code role=system} 的 content 归一为占位符，
     * {@code prompt_len} 归零。提示词属于产品文案，迭代频繁；其余结构（消息、工具调用、
     * 选项）仍逐字段比对。
     */
    private static Object systemPromptAgnostic(String text, int depth) {
        if (text == null) {
            return null;
        }
        if (depth < 4 && looksJson(text)) {
            try {
                Object parsed = JsonMappers.lenient().readValue(text, Object.class);
                return systemPromptAgnostic(parsed, depth + 1);
            } catch (Exception e) {
                return text;
            }
        }
        return text;
    }

    private static Object systemPromptAgnostic(Object node, int depth) {
        if (node instanceof java.util.Map<?, ?> map) {
            java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
            boolean system = "system".equals(map.get("role"));
            for (java.util.Map.Entry<?, ?> e : map.entrySet()) {
                String k = String.valueOf(e.getKey());
                Object v = e.getValue();
                if (system && "content".equals(k)) {
                    out.put(k, "<system-prompt>");
                } else if ("prompt_len".equals(k)) {
                    out.put(k, 0);
                } else {
                    out.put(k, systemPromptAgnostic(v, depth + 1));
                }
            }
            return out;
        }
        if (node instanceof java.util.List<?> list) {
            java.util.List<Object> out = new java.util.ArrayList<>(list.size());
            for (Object item : list) {
                out.add(systemPromptAgnostic(item, depth + 1));
            }
            return out;
        }
        if (node instanceof String text) {
            return systemPromptAgnostic(text, depth);
        }
        return node;
    }

    private static boolean looksJson(String text) {
        String t = text.trim();
        return !t.isEmpty() && (t.charAt(0) == '{' || t.charAt(0) == '[')
                && (t.endsWith("}") || t.endsWith("]"));
    }

    private static void assertRecording(String actual, String recording) {
        assertThat(systemPromptAgnostic(ContractJson.deep(actual), 0))
                .isEqualTo(systemPromptAgnostic(ContractJson.deep(recording), 0));
    }

    private static String mask(String s) {
        return Engine46bStubSupport.mask(s);
    }

    private static String stateJson(AgentState state) {
        return mask(EventJson.write(state));
    }

    private static String messagesJson(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < messages.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(EventJson.write(messages.get(i)));
        }
        return mask(sb.append(']').toString());
    }

    private static String toolCallJson(AgentToolCall call) {
        return mask(EventJson.write(call));
    }

    // ------------------------------------------------------------------
    // S1: 两轮 ReAct——工具轮（pending/progress/hint/result/exec）+ 自然停
    // ------------------------------------------------------------------

    @Test
    void executeNaturalStop() {
        List<List<StreamResponse>> responses = List.of(
                List.of(
                        Engine46bStubSupport.msgChunk("Let me search.", false, ""),
                        Engine46bStubSupport.toolCallChunk("call-1", "knowledge_search", ""),
                        Engine46bStubSupport.toolCallChunk("call-1", "knowledge_search",
                                "{\"query\":\"weknora\"}"),
                        Engine46bStubSupport.assembledToolCallChunk("call-1", "knowledge_search",
                                "{\"query\":\"weknora\"}"),
                        Engine46bStubSupport.msgChunk("", true, "tool_calls")),
                List.of(
                        Engine46bStubSupport.msgChunk("The ", false, ""),
                        Engine46bStubSupport.msgChunk("answer is 42.", true, "stop")));
        Engine46bStubSupport.StubChat chat = new Engine46bStubSupport.StubChat(responses);
        AgentEngine engine = Engine46bStubSupport.newEngine(chat);
        Engine46bStubSupport.StubTool tool = new Engine46bStubSupport.StubTool("knowledge_search");
        engine.getRegistryForTest().registerTool(tool);
        Engine46bStubSupport.EventRecorder sink = new Engine46bStubSupport.EventRecorder()
                .attach(engine.getBusForTest());

        AgentState state = engine.execute("sess-1", "msg-1", "what is weknora",
                Engine46bStubSupport.emptyMessages());

        assertRecording(sink.toJson(), AgentExecutionGolden.R_EXECUTE_NATURAL_STOP_EVENTS);
        assertRecording(stateJson(state), AgentExecutionGolden.R_EXECUTE_NATURAL_STOP_STATE);
        assertRecording(Engine46bStubSupport.chatShape(chat), AgentExecutionGolden.R_EXECUTE_NATURAL_STOP_CHAT);
        assertRecording(String.valueOf(tool.calls), AgentExecutionGolden.R_EXECUTE_NATURAL_STOP_TOOL_CALLS);
    }

    // ------------------------------------------------------------------
    // S2: 空内容 → nudge 重试 → 回答
    // ------------------------------------------------------------------

    @Test
    void executeEmptyRetry() {
        List<List<StreamResponse>> responses = List.of(
                List.of(Engine46bStubSupport.msgChunk("", true, "")),
                List.of(Engine46bStubSupport.msgChunk("Recovered answer.", true, "stop")));
        Engine46bStubSupport.StubChat chat = new Engine46bStubSupport.StubChat(responses);
        AgentEngine engine = Engine46bStubSupport.newEngine(chat);
        Engine46bStubSupport.EventRecorder sink = new Engine46bStubSupport.EventRecorder()
                .attach(engine.getBusForTest());

        AgentState state = engine.execute("sess-1", "msg-1", "q", Engine46bStubSupport.emptyMessages());

        assertRecording(sink.toJson(), AgentExecutionGolden.R_EXECUTE_EMPTY_RETRY_EVENTS);
        assertRecording(stateJson(state), AgentExecutionGolden.R_EXECUTE_EMPTY_RETRY_STATE);
        assertRecording(Engine46bStubSupport.chatShape(chat), AgentExecutionGolden.R_EXECUTE_EMPTY_RETRY_CHAT);
    }

    // ------------------------------------------------------------------
    // S3: 卡死循环检测（非自然停 finish + 相同内容 ×3 → 第 3 轮强制收束）
    // ------------------------------------------------------------------

    @Test
    void executeStuckLoop() {
        List<List<StreamResponse>> responses = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            responses.add(List.of(Engine46bStubSupport.msgChunk("Same.", true, "function_call")));
        }
        Engine46bStubSupport.StubChat chat = new Engine46bStubSupport.StubChat(responses);
        AgentEngine engine = Engine46bStubSupport.newEngine(chat);
        Engine46bStubSupport.EventRecorder sink = new Engine46bStubSupport.EventRecorder()
                .attach(engine.getBusForTest());

        AgentState state = engine.execute("sess-1", "msg-1", "q", Engine46bStubSupport.emptyMessages());

        assertRecording(sink.toJson(), AgentExecutionGolden.R_EXECUTE_STUCK_LOOP_EVENTS);
        assertRecording(stateJson(state), AgentExecutionGolden.R_EXECUTE_STUCK_LOOP_STATE);
        assertRecording(String.valueOf(state.getCurrentRound()), AgentExecutionGolden.R_EXECUTE_STUCK_LOOP_ROUNDS);
    }

    // ------------------------------------------------------------------
    // S4: MaxIterations=2 耗尽 → finalize 合成 final answer（含 usage 累计）
    // ------------------------------------------------------------------

    @Test
    void executeMaxIterations() {
        TokenUsage roundUsage = new TokenUsage();
        roundUsage.setPromptTokens(100);
        roundUsage.setCompletionTokens(10);
        roundUsage.setTotalTokens(110);
        TokenUsage finalUsage = new TokenUsage();
        finalUsage.setPromptTokens(50);
        finalUsage.setCompletionTokens(5);
        finalUsage.setTotalTokens(55);

        List<StreamResponse> round = List.of(
                Engine46bStubSupport.toolCallChunk("call-1", "knowledge_search", ""),
                Engine46bStubSupport.toolCallChunk("call-1", "knowledge_search", "{\"query\":\"x\"}"),
                Engine46bStubSupport.assembledToolCallChunk("call-1", "knowledge_search",
                        "{\"query\":\"x\"}"),
                withUsage(Engine46bStubSupport.msgChunk("Working...", true, "tool_calls"), roundUsage));
        List<List<StreamResponse>> responses = List.of(
                round,
                List.of(
                        Engine46bStubSupport.toolCallChunk("call-2", "knowledge_search", ""),
                        Engine46bStubSupport.toolCallChunk("call-2", "knowledge_search",
                                "{\"query\":\"x\"}"),
                        Engine46bStubSupport.assembledToolCallChunk("call-2", "knowledge_search",
                                "{\"query\":\"x\"}"),
                        withUsage(Engine46bStubSupport.msgChunk("Working...", true, "tool_calls"),
                                roundUsage)),
                List.of(
                        Engine46bStubSupport.msgChunk("Synthesized ", false, ""),
                        withUsage(Engine46bStubSupport.msgChunk("answer", true, "stop"), finalUsage)));
        Engine46bStubSupport.StubChat chat = new Engine46bStubSupport.StubChat(responses);
        AgentConfig cfg = Engine46bStubSupport.configWith(chat, c -> c.setMaxIterations(2));
        AgentEngine engine = Engine46bStubSupport.newEngine(cfg, chat);
        Engine46bStubSupport.StubTool tool = new Engine46bStubSupport.StubTool("knowledge_search");
        engine.getRegistryForTest().registerTool(tool);
        Engine46bStubSupport.EventRecorder sink = new Engine46bStubSupport.EventRecorder()
                .attach(engine.getBusForTest());

        AgentState state = engine.execute("sess-1", "msg-1", "q", Engine46bStubSupport.emptyMessages());

        assertRecording(sink.toJson(), AgentExecutionGolden.R_EXECUTE_MAX_ITERATIONS_EVENTS);
        assertRecording(stateJson(state), AgentExecutionGolden.R_EXECUTE_MAX_ITERATIONS_STATE);
        assertRecording(Engine46bStubSupport.chatShape(chat), AgentExecutionGolden.R_EXECUTE_MAX_ITERATIONS_CHAT);
        assertRecording(String.valueOf(tool.calls), AgentExecutionGolden.R_EXECUTE_MAX_ITERATIONS_TOOL_EXEC_COUNT);
    }

    private static StreamResponse withUsage(StreamResponse r, TokenUsage usage) {
        r.setUsage(usage);
        return r;
    }

    // ------------------------------------------------------------------
    // S5: 空内容重试耗尽 → 固定兜底答案
    // ------------------------------------------------------------------

    @Test
    void executeEmptyExhausted() {
        List<List<StreamResponse>> responses = List.of(
                List.of(Engine46bStubSupport.msgChunk("", true, "")),
                List.of(Engine46bStubSupport.msgChunk("", true, "")),
                List.of(Engine46bStubSupport.msgChunk("", true, "")));
        Engine46bStubSupport.StubChat chat = new Engine46bStubSupport.StubChat(responses);
        AgentEngine engine = Engine46bStubSupport.newEngine(chat);
        Engine46bStubSupport.EventRecorder sink = new Engine46bStubSupport.EventRecorder()
                .attach(engine.getBusForTest());

        AgentState state = engine.execute("sess-1", "msg-1", "q", Engine46bStubSupport.emptyMessages());

        assertRecording(sink.toJson(), AgentExecutionGolden.R_EXECUTE_EMPTY_EXHAUSTED_EVENTS);
        assertRecording(stateJson(state), AgentExecutionGolden.R_EXECUTE_EMPTY_EXHAUSTED_STATE);
    }

    // ------------------------------------------------------------------
    // S6: content_filter → 固定兜底答案 + content/Done 两连发
    // ------------------------------------------------------------------

    @Test
    void executeContentFilter() {
        Engine46bStubSupport.StubChat chat = new Engine46bStubSupport.StubChat(List.of(
                List.of(Engine46bStubSupport.msgChunk("", true, "content_filter"))));
        AgentEngine engine = Engine46bStubSupport.newEngine(chat);
        Engine46bStubSupport.EventRecorder sink = new Engine46bStubSupport.EventRecorder()
                .attach(engine.getBusForTest());

        AgentState state = engine.execute("sess-1", "msg-1", "q", Engine46bStubSupport.emptyMessages());

        assertRecording(sink.toJson(), AgentExecutionGolden.R_EXECUTE_CONTENT_FILTER_EVENTS);
        assertRecording(stateJson(state), AgentExecutionGolden.R_EXECUTE_CONTENT_FILTER_STATE);
    }

    // ------------------------------------------------------------------
    // S7: runToolCall 参数解析四态
    // ------------------------------------------------------------------

    @Test
    void runToolCallArgs() {
        record Case(String name, String id, String args) {
        }
        List<Case> cases = List.of(
                new Case("repair", "call-r", "{\"query\":\"weknora\",}"),
                new Case("truncated", "call-t", "{\"query\":\"weknora"),
                new Case("unrepairable", "call-u", "{{{"),
                new Case("unresolved_handles", "call-h", "{\"query\":\"d99\"}"));

        for (Case c : cases) {
            Engine46bStubSupport.StubChat chat = new Engine46bStubSupport.StubChat(List.of());
            AgentEngine engine = Engine46bStubSupport.newEngine(chat);
            Engine46bStubSupport.StubTool tool = new Engine46bStubSupport.StubTool("knowledge_search");
            engine.getRegistryForTest().registerTool(tool);
            Engine46bStubSupport.EventRecorder sink = new Engine46bStubSupport.EventRecorder()
                    .attach(engine.getBusForTest());

            com.ragagent.contract.model.ToolCall tc = new com.ragagent.contract.model.ToolCall();
            tc.setId(c.id());
            tc.getFunction().setName("knowledge_search");
            tc.getFunction().setArguments(c.args());
            if ("unresolved_handles".equals(c.name())) {
                tc.setModelArguments(c.args());
                tc.setArgumentResolution("unresolved");
                tc.setUnresolvedHandles(List.of("d99"));
            }

            AgentToolCall got = engine.runToolCall(tc, 0, 0, 1, "sess-1", "msg-1", null);
            String groupPrefix = "R_RUN_TOOL_CALL_ARGS_" + c.name().toUpperCase(java.util.Locale.ROOT);
            if ("unrepairable".equals(c.name())) {
                // 已知通道差异：参数解析的历史错误文案（
                // "invalid character '{' looking for beginning of object key string"）
                // 与 Jackson 不同——只锁静态骨架（前缀 + 两段固定提示）与结构。
                String gotJson = toolCallJson(got);
                assertThat(gotJson).startsWith("{\"id\":\"call-u\",\"name\":\"knowledge_search\",\"args\":{\"_raw\":\"{{{\"},\"result\":{\"success\":false,\"output\":\"\",\"error\":\"Failed to parse tool arguments: ");
                assertThat(gotJson).contains("If the JSON looks cut off, the previous round likely hit the "
                        + "output token cap. Retry with complete JSON (required fields first) and a smaller "
                        + "payload.\\n\\n[Analyze the error above and try a different approach.]");
            } else {
                assertThat(toolCallJson(got)).isEqualTo(constant(groupPrefix));
            }
            assertThat(sink.toJson()).isEqualTo(constant(groupPrefix + "_EVENTS"));
            assertThat(String.valueOf(tool.calls)).isEqualTo(constant(groupPrefix + "_EXEC"));
        }
    }

    private static String constant(String name) {
        try {
            return (String) AgentExecutionGolden.class.getField(name).get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------
    // S8: 并行工具调用（读-写-读 → 写是屏障）+ 事件按原序
    // ------------------------------------------------------------------

    @Test
    void executeParallelTools() {
        List<List<StreamResponse>> responses = List.of(
                List.of(
                        Engine46bStubSupport.assembledToolCallChunk("call-1", "grep_chunks",
                                "{\"query\":\"a\"}"),
                        Engine46bStubSupport.assembledToolCallChunk("call-2", "write_sandbox_file",
                                "{\"path\":\"/w/a.txt\"}"),
                        Engine46bStubSupport.assembledToolCallChunk("call-3", "grep_chunks",
                                "{\"query\":\"b\"}"),
                        Engine46bStubSupport.msgChunk("", true, "tool_calls")),
                List.of(Engine46bStubSupport.msgChunk("done answer", true, "stop")));
        Engine46bStubSupport.StubChat chat = new Engine46bStubSupport.StubChat(responses);
        AgentConfig cfg = Engine46bStubSupport.configWith(chat, c -> c.setParallelToolCalls(true));
        AgentEngine engine = Engine46bStubSupport.newEngine(cfg, chat);
        engine.getRegistryForTest().registerTool(new Engine46bStubSupport.StubTool("grep_chunks"));
        engine.getRegistryForTest().registerTool(new Engine46bStubSupport.StubTool("write_sandbox_file"));
        Engine46bStubSupport.EventRecorder sink = new Engine46bStubSupport.EventRecorder()
                .attach(engine.getBusForTest());

        AgentState state = engine.execute("sess-1", "msg-1", "q", Engine46bStubSupport.emptyMessages());

        assertRecording(sink.toJson(), AgentExecutionGolden.R_EXECUTE_PARALLEL_TOOLS_EVENTS);
        assertRecording(stateJson(state), AgentExecutionGolden.R_EXECUTE_PARALLEL_TOOLS_STATE);
    }

    // ------------------------------------------------------------------
    // S9: finish=length → 拒执行可能截断的调用
    // ------------------------------------------------------------------

    @Test
    void executeLengthFinish() {
        List<List<StreamResponse>> responses = List.of(
                List.of(
                        Engine46bStubSupport.assembledToolCallChunk("call-1", "write_sandbox_file",
                                "{\"path\":\"/w/a.txt\"}"),
                        Engine46bStubSupport.msgChunk("", true, "length")),
                List.of(Engine46bStubSupport.msgChunk("final answer after refusal", true, "stop")));
        Engine46bStubSupport.StubChat chat = new Engine46bStubSupport.StubChat(responses);
        AgentEngine engine = Engine46bStubSupport.newEngine(chat);
        engine.getRegistryForTest().registerTool(new Engine46bStubSupport.StubTool("write_sandbox_file"));
        Engine46bStubSupport.EventRecorder sink = new Engine46bStubSupport.EventRecorder()
                .attach(engine.getBusForTest());

        AgentState state = engine.execute("sess-1", "msg-1", "q", Engine46bStubSupport.emptyMessages());

        assertRecording(sink.toJson(), AgentExecutionGolden.R_EXECUTE_LENGTH_FINISH_EVENTS);
        assertRecording(stateJson(state), AgentExecutionGolden.R_EXECUTE_LENGTH_FINISH_STATE);
    }

    // ------------------------------------------------------------------
    // S10: steer——(a) 直接 drain；(b) 循环结束注入续跑
    // ------------------------------------------------------------------

    @Test
    void steerDrain() {
        Engine46bStubSupport.StubChat chat = new Engine46bStubSupport.StubChat(List.of());
        AgentEngine engine = Engine46bStubSupport.newEngine(chat);
        EventBusInterface bus = engine.getBusForTest();
        Engine46bStubSupport.EventRecorder sink = new Engine46bStubSupport.EventRecorder().attach(bus);

        Engine46bStubSupport.FakeSteerSink fake = new Engine46bStubSupport.FakeSteerSink(
                Engine46bStubSupport.steerEntry("steer-1", "再补充一点：也对比一下成本"),
                Engine46bStubSupport.steerEntry("steer-2", "  \r\n  "));
        engine.setSteerSink(fake);

        List<ChatMessage> messages = new ArrayList<>(List.of(
                new ChatMessage("system", "sys"),
                new ChatMessage("user", "original query")));
        AgentState state = new AgentState();
        state.setCurrentRound(1);
        AgentEngine.MsgRef messagesRef = new AgentEngine.MsgRef(messages);

        int injected = engine.drainSteerMessages(state, messagesRef, "sess", "msg");
        assertRecording(String.valueOf(injected), AgentExecutionGolden.R_STEER_DRAIN_INJECTED);
        assertRecording(messagesJson(messagesRef.items), AgentExecutionGolden.R_STEER_DRAIN_MESSAGES);
        assertRecording(mask(EventJson.write(state.getPendingSteerMessages())), AgentExecutionGolden.R_STEER_DRAIN_PENDING);
        assertRecording(mask(EventJson.write(fake.persisted)), AgentExecutionGolden.R_STEER_DRAIN_PERSISTED);
        assertRecording(sink.toJson(), AgentExecutionGolden.R_STEER_DRAIN_EVENTS);

        // 第二次 drain：队列消费完（consumed），无新增。
        int injected2 = engine.drainSteerMessages(state, messagesRef, "sess", "msg");
        assertRecording(String.valueOf(injected2), AgentExecutionGolden.R_STEER_DRAIN_INJECTED_SECOND);

        // 持久化失败：事件留在队列，不进消息。
        Engine46bStubSupport.FakeSteerSink failFake = new Engine46bStubSupport.FakeSteerSink(
                Engine46bStubSupport.steerEntry("steer-9", "will fail"));
        failFake.failPersist = true;
        engine.setSteerSink(failFake);
        int injected3 = engine.drainSteerMessages(state, messagesRef, "sess", "msg");
        assertRecording(String.valueOf(injected3), AgentExecutionGolden.R_STEER_DRAIN_INJECTED_FAILPERSIST);
        assertRecording(mask(EventJson.write(state.getPendingSteerMessages())), AgentExecutionGolden.R_STEER_DRAIN_PENDING_AFTER_FAIL);
    }

    @Test
    void steerLoopEndInject() {
        List<List<StreamResponse>> responses = List.of(
                List.of(Engine46bStubSupport.msgChunk("interim answer", true, "stop")),
                List.of(Engine46bStubSupport.msgChunk("final answer after steer", true, "stop")));
        Engine46bStubSupport.StubChat chat = new Engine46bStubSupport.StubChat(responses);
        AgentEngine engine = Engine46bStubSupport.newEngine(chat);
        engine.setSteerSink(new Engine46bStubSupport.FakeSteerSink(
                Engine46bStubSupport.steerEntry("steer-1", "please continue with costs")));
        Engine46bStubSupport.EventRecorder sink = new Engine46bStubSupport.EventRecorder()
                .attach(engine.getBusForTest());

        AgentState state = engine.execute("sess-1", "msg-1", "q", Engine46bStubSupport.emptyMessages());

        assertRecording(sink.toJson(), AgentExecutionGolden.R_STEER_LOOP_END_INJECT_EVENTS);
        assertRecording(stateJson(state), AgentExecutionGolden.R_STEER_LOOP_END_INJECT_STATE);
        assertRecording(Engine46bStubSupport.chatShape(chat), AgentExecutionGolden.R_STEER_LOOP_END_INJECT_CHAT);
    }

    // ------------------------------------------------------------------
    // S11: RenderUserTurnContent（runtime_context + must_use；日期段掩码）
    // ------------------------------------------------------------------

    @Test
    void renderUserTurn() {
        Engine46bStubSupport.StubChat chat = new Engine46bStubSupport.StubChat(List.of());
        AgentEngine engine = Engine46bStubSupport.newEngine(chat);
        engine.setKnowledgeBasesInfoForTest(List.of(
                new KnowledgeBaseInfo("kb-1", "工程文档", "document", "engine docs", 3,
                        List.of("wiki", "chunks"), List.of(
                                new RecentDocInfo("ch-1", "kb-1", "doc-1", "Doc One", "",
                                        "", 0L, "text", "", null, null, null)))));
        engine.setSelectedDocsForTest(List.of(
                new SelectedDocumentInfo("doc-9", "kb-1", "", "a.pdf", "pdf")));
        engine.setPinnedMentions(
                List.of(new PinnedMCPServiceInfo(true, "svc-1", "iwiki", "",
                        List.of("mcp_iwiki_get", "mcp_iwiki_put"))),
                List.of(new PinnedSkillInfo("writer", "writes")));

        String withAll = mask(EventJson.write(engine.renderUserTurnContent("sess-1", "什么是 WeKnora？")));
        assertRecording(withAll, AgentExecutionGolden.R_RENDER_USER_TURN_WITH_ALL);

        AgentEngine empty = Engine46bStubSupport.newEngine(
                new Engine46bStubSupport.StubChat(List.of()));
        String emptyTurn = mask(EventJson.write(empty.renderUserTurnContent("s", "q")));
        assertRecording(emptyTurn, AgentExecutionGolden.R_RENDER_USER_TURN_EMPTY);
    }

    // ------------------------------------------------------------------
    // S12: trimToolResultsToBudget 纯函数
    // ------------------------------------------------------------------

    @Test
    void trimToolResults() {
        TokenEstimator est = new TokenEstimator();
        String big = "tool output content ".repeat(300);
        List<ChatMessage> messages = List.of(
                new ChatMessage("system", "sys"),
                new ChatMessage("user", "q"),
                assistantWithCall("c1"),
                toolMsg("c1", big),
                assistantWithCall("c2"),
                toolMsg("c2", big));
        int full = est.estimateMessages(messages);
        ContextBudget.TrimOutcome trimmed = ContextBudget.trimToolResultsToBudget(messages, est, full / 3);
        assertRecording(String.valueOf(trimmed.ok()), AgentExecutionGolden.R_TRIM_TOOL_RESULTS_OK);
        assertRecording(String.valueOf(full), AgentExecutionGolden.R_TRIM_TOOL_RESULTS_FULL_TOKENS);
        assertRecording(messagesJson(trimmed.messages()), AgentExecutionGolden.R_TRIM_TOOL_RESULTS_TRIMMED);
    }

    private static ChatMessage assistantWithCall(String id) {
        return assistantWithCall(id, "t", "");
    }

    private static ChatMessage assistantWithCall(String id, String toolName, String args) {
        ChatMessage m = new ChatMessage("assistant", "");
        com.ragagent.contract.model.ToolCall call = new com.ragagent.contract.model.ToolCall();
        call.setId(id);
        call.setType("function");
        call.setFunction(new FunctionCall(toolName, args));
        m.setToolCalls(new ArrayList<>(List.of(call)));
        return m;
    }

    private static ChatMessage toolMsg(String callId, String content) {
        return ChatMessage.tool(callId, "t", content);
    }

    // ------------------------------------------------------------------
    // S13: manageContextWindow 触发 compaction
    // ------------------------------------------------------------------

    @Test
    void manageContextWindow() {
        Engine46bStubSupport.StubChat chat = new Engine46bStubSupport.StubChat(List.of());
        chat.setNonStreamResponse(summaryResponse());
        AgentConfig cfg = Engine46bStubSupport.configWith(chat, c -> {
            c.setMaxContextTokens(20000);
            c.setMaxCompletionTokens(1024);
        });
        AgentEngine engine = Engine46bStubSupport.newEngine(cfg, chat);
        EventBusInterface bus = engine.getBusForTest();
        Engine46bStubSupport.EventRecorder sink = new Engine46bStubSupport.EventRecorder().attach(bus);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("system", "you are an agent"));
        messages.add(new ChatMessage("user", "build me a deck"));
        String body = "tool output content ".repeat(400);
        for (int i = 0; i < 20; i++) {
            messages.add(assistantWithCall("call-" + i, "write_sandbox_file",
                    "{\"path\":\"/w/a.html\"}"));
            messages.add(ChatMessage.tool("call-" + i, "write_sandbox_file", body));
        }
        int before = engine.tokenEstimatorForTest().estimateMessages(messages);
        TokenUsage baseline = new TokenUsage();
        baseline.setPromptTokens(12345);
        baseline.setCompletionTokens(7);
        baseline.setTotalTokens(12352);
        engine.setUsageBaselineForTest(baseline, 3);
        AgentEngine.WindowOutcome outcome = engine.manageContextWindow(messages, 1, before);

        assertRecording(String.valueOf(outcome.changed()), AgentExecutionGolden.R_MANAGE_CONTEXT_WINDOW_CHANGED);
        assertRecording(String.valueOf(before), AgentExecutionGolden.R_MANAGE_CONTEXT_WINDOW_BEFORE_TOKENS);
        assertRecording(String.valueOf(engine.tokenEstimatorForTest().estimateMessages(outcome.messages())), AgentExecutionGolden.R_MANAGE_CONTEXT_WINDOW_AFTER_TOKENS);
        assertRecording(messagesJson(outcome.messages()), AgentExecutionGolden.R_MANAGE_CONTEXT_WINDOW_AFTER_MESSAGES);
        assertRecording(sink.toJson(), AgentExecutionGolden.R_MANAGE_CONTEXT_WINDOW_EVENTS);
        assertRecording(settingsJson(engine.compactorSettingsForTest()), AgentExecutionGolden.R_MANAGE_CONTEXT_WINDOW_SETTINGS);

        // 二次压缩：已耗尽标记生效，不再调摘要
        AgentEngine.WindowOutcome second = engine.manageContextWindow(outcome.messages(), 2,
                engine.tokenEstimatorForTest().estimateMessages(outcome.messages()));
        assertRecording(String.valueOf(second.changed()), AgentExecutionGolden.R_MANAGE_CONTEXT_WINDOW_CHANGED_SECOND);
        assertRecording(String.valueOf(chat.nonStreamCalls), AgentExecutionGolden.R_MANAGE_CONTEXT_WINDOW_SUMMARIZER_CALLS);
    }

    private static ChatResponse summaryResponse() {
        ChatResponse resp = new ChatResponse();
        resp.setContent("## Goal\ndo the thing");
        resp.setFinishReason("stop");
        return resp;
    }

    /** 历史线格式的 settings JSON（键为旧字段名）。 */
    private static String settingsJson(CompactionSettings s) {
        return "{\"Enabled\":" + s.enabled()
                + ",\"MaxContextTokens\":" + s.maxContextTokens()
                + ",\"ReserveTokens\":" + s.reserveTokens()
                + ",\"KeepRecentTokens\":" + s.keepRecentTokens()
                + ",\"MaxSummaryTokens\":" + s.maxSummaryTokens() + "}";
    }

    // ------------------------------------------------------------------
    // S14: estimateCurrentTokens（usage 基线 + delta，不重复计回复）
    // ------------------------------------------------------------------

    @Test
    void estimateCurrentTokens() {
        Engine46bStubSupport.StubChat chat = new Engine46bStubSupport.StubChat(List.of());
        AgentConfig cfg = Engine46bStubSupport.configWith(chat, c -> c.setMaxContextTokens(128000));
        AgentEngine engine = Engine46bStubSupport.newEngine(cfg, chat);

        List<ChatMessage> sent = List.of(
                new ChatMessage("system", "you are an agent"),
                new ChatMessage("user", "do the thing"));
        ChatMessage reply = new ChatMessage("assistant", "working on it");
        ChatMessage toolResult = ChatMessage.tool("c1", "t", "result");
        List<ChatMessage> messages = new ArrayList<>(sent);
        messages.add(reply);
        messages.add(toolResult);
        int replyTokens = engine.tokenEstimatorForTest().estimateMessage(reply);
        TokenUsage usage = new TokenUsage();
        usage.setPromptTokens(5000);
        usage.setCompletionTokens(replyTokens);
        usage.setTotalTokens(5000 + replyTokens);
        engine.setUsageBaselineForTest(usage, sent.size());

        int got = engine.estimateCurrentTokens(messages);
        assertRecording(String.valueOf(got), AgentExecutionGolden.R_ESTIMATE_CURRENT_TOKENS_BASELINE);

        engine.setUsageBaselineForTest(new TokenUsage(), 0);
        assertRecording(String.valueOf(engine.estimateCurrentTokens(messages)), AgentExecutionGolden.R_ESTIMATE_CURRENT_TOKENS_NO_BASELINE);
    }

    // ------------------------------------------------------------------
    // S15: buildToolsForLLM + listToolNames
    // ------------------------------------------------------------------

    @Test
    void buildToolsForLLM() {
        Engine46bStubSupport.StubChat chat = new Engine46bStubSupport.StubChat(List.of());
        AgentEngine engine = Engine46bStubSupport.newEngine(chat);
        engine.getRegistryForTest().registerTool(new Engine46bStubSupport.StubTool("knowledge_search"));
        List<ChatTool> tools = engine.buildToolsForLLM();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < tools.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(EventJson.write(tools.get(i)));
        }
        assertRecording(mask(sb.append(']').toString()), AgentExecutionGolden.R_BUILD_TOOLS_TOOLS);
        assertRecording(mask(EventJson.write(AgentEngine.listToolNames(tools))), AgentExecutionGolden.R_BUILD_TOOLS_NAMES);
    }

    // ------------------------------------------------------------------
    // S16: streamThinkingToEventBus 事件路由（reasoning 通道 / 内联 <think>）
    // ------------------------------------------------------------------

    @Test
    void streamThinkingRouting() {
        List<List<StreamResponse>> responses = List.of(List.of(
                Engine46bStubSupport.thinkingChunk("let me reason", false),
                Engine46bStubSupport.thinkingChunk("", true),
                Engine46bStubSupport.msgChunk("<think>hidden</think>", false, ""),
                Engine46bStubSupport.msgChunk("Visible ", false, ""),
                Engine46bStubSupport.msgChunk("answer.", true, "stop")));
        Engine46bStubSupport.StubChat chat = new Engine46bStubSupport.StubChat(responses);
        AgentEngine engine = Engine46bStubSupport.newEngine(chat);
        Engine46bStubSupport.EventRecorder sink = new Engine46bStubSupport.EventRecorder()
                .attach(engine.getBusForTest());

        ChatResponse resp = engine.streamThinkingToEventBus(Engine46bStubSupport.emptyMessages(),
                List.of(), 0, "sess-1");

        assertRecording(sink.toJson(), AgentExecutionGolden.R_STREAM_THINKING_ROUTING_EVENTS);
        assertRecording(mask(EventJson.write(resp)), AgentExecutionGolden.R_STREAM_THINKING_ROUTING_RESP);
    }

    // ------------------------------------------------------------------
    // S17: 流错误 → Execute 错误路径；有工具结果时优雅降级
    // ------------------------------------------------------------------

    @Test
    void executeStreamError() {
        Engine46bStubSupport.StubChat chat = new Engine46bStubSupport.StubChat(List.of(
                List.of(Engine46bStubSupport.errorChunk("provider exploded", "incomplete"))));
        AgentEngine engine = Engine46bStubSupport.newEngine(chat);
        Engine46bStubSupport.EventRecorder sink = new Engine46bStubSupport.EventRecorder()
                .attach(engine.getBusForTest());

        AgentState state = null;
        String errorText = "";
        try {
            state = engine.execute("sess-1", "msg-1", "q", Engine46bStubSupport.emptyMessages());
        } catch (AgentEngineException e) {
            errorText = e.getMessage();
            state = e.getState();
        }
        assertRecording(Engine46bStubSupport.jsonStr(errorText), AgentExecutionGolden.R_EXECUTE_STREAM_ERROR_ERROR);
        assertRecording(stateJson(state), AgentExecutionGolden.R_EXECUTE_STREAM_ERROR_STATE);
        assertRecording(sink.toJson(), AgentExecutionGolden.R_EXECUTE_STREAM_ERROR_EVENTS);
    }

    @Test
    void executeGracefulDegradation() {
        List<List<StreamResponse>> responses = List.of(
                List.of(
                        Engine46bStubSupport.assembledToolCallChunk("call-1", "knowledge_search",
                                "{\"query\":\"x\"}"),
                        Engine46bStubSupport.msgChunk("", true, "tool_calls")),
                List.of(Engine46bStubSupport.errorChunk("provider exploded on round 2", "incomplete")),
                List.of(Engine46bStubSupport.msgChunk("synthesized from tools", true, "stop")));
        Engine46bStubSupport.StubChat chat = new Engine46bStubSupport.StubChat(responses);
        AgentEngine engine = Engine46bStubSupport.newEngine(chat);
        Engine46bStubSupport.StubTool tool = new Engine46bStubSupport.StubTool("knowledge_search");
        engine.getRegistryForTest().registerTool(tool);
        Engine46bStubSupport.EventRecorder sink = new Engine46bStubSupport.EventRecorder()
                .attach(engine.getBusForTest());

        AgentState state = null;
        String errorText = "";
        try {
            state = engine.execute("sess-1", "msg-1", "q", Engine46bStubSupport.emptyMessages());
        } catch (AgentEngineException e) {
            errorText = e.getMessage();
            state = e.getState();
        }
        assertRecording(Engine46bStubSupport.jsonStr(errorText), AgentExecutionGolden.R_EXECUTE_GRACEFUL_DEGRADATION_ERROR);
        assertRecording(stateJson(state), AgentExecutionGolden.R_EXECUTE_GRACEFUL_DEGRADATION_STATE);
        assertRecording(sink.toJson(), AgentExecutionGolden.R_EXECUTE_GRACEFUL_DEGRADATION_EVENTS);
        assertRecording(Engine46bStubSupport.chatShape(chat), AgentExecutionGolden.R_EXECUTE_GRACEFUL_DEGRADATION_CHAT);
        assertRecording(String.valueOf(tool.calls), AgentExecutionGolden.R_EXECUTE_GRACEFUL_DEGRADATION_TOOL_EXEC_COUNT);
    }

    // ------------------------------------------------------------------
    // S18: finish reason 分类
    // ------------------------------------------------------------------

    @Test
    void finishReasonClasses() {
        assertRecording(mask(EventJson.write(List.of(
                AgentEngine.isNaturalStopFinishReason("stop"),
                AgentEngine.isNaturalStopFinishReason(" End_Turn "),
                AgentEngine.isNaturalStopFinishReason("stop_sequence"),
                AgentEngine.isNaturalStopFinishReason("toolCalls"),
                AgentEngine.isNaturalStopFinishReason("")))), AgentExecutionGolden.R_FINISH_REASON_CLASSES_NATURAL);
        assertRecording(mask(EventJson.write(List.of(
                AgentEngine.isLengthFinishReason("length"),
                AgentEngine.isLengthFinishReason("MAX_TOKENS"),
                AgentEngine.isLengthFinishReason("max_output_tokens"),
                AgentEngine.isLengthFinishReason("stop")))), AgentExecutionGolden.R_FINISH_REASON_CLASSES_LENGTH);
    }
}
