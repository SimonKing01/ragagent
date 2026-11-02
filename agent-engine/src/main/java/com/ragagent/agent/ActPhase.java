package com.ragagent.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.agent.tool.runtime.JsonRepair;
import com.ragagent.agent.tool.runtime.NormalizeToolCallId;
import com.ragagent.contract.agent.AgentStep;
import com.ragagent.contract.agent.AgentToolCall;
import com.ragagent.contract.agent.ToolCallTarget;
import com.ragagent.contract.error.BizException;
import com.ragagent.contract.event.Event;
import com.ragagent.contract.event.payload.AgentActionData;
import com.ragagent.contract.event.payload.AgentToolCallData;
import com.ragagent.contract.event.payload.AgentToolResultData;
import com.ragagent.contract.model.ChatResponse;
import com.ragagent.contract.model.FunctionCall;
import com.ragagent.contract.model.ToolCall;
import com.ragagent.contract.tool.ToolDefinitions;
import com.ragagent.contract.tool.ToolExecContext;
import com.ragagent.contract.tool.ToolResult;
import com.ragagent.modelcontext.Registry;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.platform.access.TenantContextSnapshot;
import com.ragagent.platform.access.TenantScope;
import com.ragagent.platform.event.EventType;
import com.ragagent.platform.tracing.langfuse.LangfuseManager;
import com.ragagent.platform.tracing.langfuse.Span;
import com.ragagent.platform.web.JsonMappers;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ReAct「Act」段的协作者：工具调用编排——串行/并行执行、截断参数拒执、
 * 工具结果事件与持久化清洗、Langfuse 工具 span，以及工具结果图片的 VLM 描述。
 *
 * <p>持有 {@link AgentEngine} 回引以访问引擎字段；本类不得独立实例化。</p>
 *
 * <p>例外说明：836 行，略超 800 行硬顶——工具调用编排（串行/并行、事件、持久化清洗、
 * Langfuse span、VLM 描述）是一个不可再分的执行职责，再切只会制造参数传递层。</p>
 */
final class ActPhase {

    private static final Logger log = LoggerFactory.getLogger(ActPhase.class);

    private static final ObjectMapper JSON = JsonMappers.lenient();

    /** 工具输出进 Langfuse 前的预览截断长度。 */
    private static final int LANGFUSE_TOOL_OUTPUT_PREVIEW = 4000;

    /** 拒执行截断参数的模型可见文案。 */
    static final String TRUNCATED_ARGUMENTS_ERROR = "Tool call was not executed: the model output was cut off "
            + "before the arguments finished, so they are incomplete rather than wrong. "
            + "Re-issue the call with a complete JSON object. If the payload is large, "
            + "split it across several smaller calls.";

    /** 图片描述提示词（工具结果图片的 VLM 分析）。 */
    static final String TOOL_IMAGE_ANALYSIS_PROMPT = "Describe the content of this image in detail. ";

    private final AgentEngine engine;

    ActPhase(AgentEngine engine) {
        this.engine = engine;
    }




    /** "data:mime;base64,..." → 原始字节；标准解码失败退回无填充解码。 */
    static byte[] decodeDataURIBytes(String dataURI) {
        if (!dataURI.startsWith("data:")) {
            throw new IllegalArgumentException("not a data URI");
        }
        int idx = dataURI.indexOf(";base64,");
        if (idx < 0) {
            throw new IllegalArgumentException("unsupported data URI encoding (expected base64)");
        }
        String raw = dataURI.substring(idx + 8);
        try {
            return java.util.Base64.getDecoder().decode(raw);
        } catch (IllegalArgumentException e) {
            // 一些 MCP 服务器省略尾随 '='——去填充后重试。
            int end = raw.length();
            while (end > 0 && raw.charAt(end - 1) == '=') {
                end--;
            }
            return java.util.Base64.getDecoder().decode(raw.substring(0, end));
        }
    }


    /** 内部工具名 → 展示名。 */
    private static final Map<String, String> TOOL_DISPLAY_NAMES = buildToolDisplayNames();

    private static Map<String, String> buildToolDisplayNames() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(ToolDefinitions.TOOL_DISCOVER_MCP_TOOLS, "查看外部工具");
        m.put(ToolDefinitions.TOOL_CALL_MCP_TOOL, "调用外部工具");
        m.put(ToolDefinitions.TOOL_THINKING, "深度思考");
        m.put(ToolDefinitions.TOOL_TODO_WRITE, "制定计划");
        m.put(ToolDefinitions.TOOL_GREP_CHUNKS, "关键词搜索");
        m.put(ToolDefinitions.TOOL_KNOWLEDGE_SEARCH, "知识搜索");
        m.put(ToolDefinitions.TOOL_LIST_KNOWLEDGE_CHUNKS, "查看文档分块");
        m.put(ToolDefinitions.TOOL_QUERY_KNOWLEDGE_GRAPH, "查询知识图谱");
        m.put(ToolDefinitions.TOOL_GET_DOCUMENT_INFO, "获取文档信息");
        m.put(ToolDefinitions.TOOL_SEARCH_CONVERSATIONS, "回顾历史对话");
        m.put(ToolDefinitions.TOOL_SEARCH_MEMORY, "查询长期记忆");
        m.put(ToolDefinitions.TOOL_DATABASE_QUERY, "查询数据");
        m.put(ToolDefinitions.TOOL_DATA_ANALYSIS, "数据分析");
        m.put(ToolDefinitions.TOOL_DATA_SCHEMA, "查看数据结构");
        m.put(ToolDefinitions.TOOL_WEB_SEARCH, "搜索网页");
        m.put(ToolDefinitions.TOOL_WEB_FETCH, "获取网页");
        m.put(ToolDefinitions.LEGACY_TOOL_EXECUTE_SKILL_SCRIPT, "执行技能脚本");
        m.put(ToolDefinitions.LEGACY_TOOL_READ_SKILL, "读取技能");
        m.put(ToolDefinitions.TOOL_READ_FILE, "读取文件");
        m.put(ToolDefinitions.TOOL_LIST_SANDBOX_FILES, "列出沙箱文件");
        m.put(ToolDefinitions.LEGACY_TOOL_READ_SANDBOX_FILE, "读取沙箱文件");
        m.put(ToolDefinitions.TOOL_WRITE_SANDBOX_FILE, "写入沙箱文件");
        m.put(ToolDefinitions.TOOL_EDIT_SANDBOX_FILE, "编辑沙箱文件");
        m.put(ToolDefinitions.TOOL_SHELL_EXEC, "执行沙箱命令");
        return m;
    }

    /** 参数不该进提示的工具（SQL 会泄漏实现细节）。 */
    private static final Map<String, Boolean> TOOL_HINT_SENSITIVE_ARGS = Map.of(
            ToolDefinitions.TOOL_DATABASE_QUERY, true);

    /** 工具调用的人读提示，如 `搜索网页("query")`。 */
    static String formatToolHint(String name, Map<String, Object> args) {
        String displayName = TOOL_DISPLAY_NAMES.getOrDefault(name, name);
        if (args == null || args.isEmpty() || Boolean.TRUE.equals(TOOL_HINT_SENSITIVE_ARGS.get(name))) {
            return displayName;
        }
        for (Object v : args.values()) {
            if (v instanceof String s) {
                if (s.length() > 40) {
                    s = s.substring(0, 40) + "…";
                }
                return displayName + "(\"" + s + "\")";
            }
        }
        return displayName;
    }

    /** 本轮全部工具调用入口。 */
    void executeToolCalls(ChatResponse response, AgentStep step, int iteration,
            String sessionId, String assistantMessageID) {
        if (response.getToolCalls() == null || response.getToolCalls().isEmpty()) {
            return;
        }
        int round = iteration + 1;
        int n = response.getToolCalls().size();

        // 补全预算从中间切断响应 → 每个调用的参数都可能不完整。执行比失败更糟：
        // 截断的 write_sandbox_file 会写半个文件还报成功。
        if (AgentEngine.isLengthFinishReason(response.getFinishReason())) {
            log.warn("[Agent][Round-{}] Response hit the completion-token cap (finish={}); refusing {} tool call(s) with possibly truncated arguments",
                    round, response.getFinishReason(), n);
            failTruncatedToolCalls(response, step, iteration, sessionId);
            return;
        }

        log.info("[Agent][Round-{}] Executing {} tool call(s)", round, n);

        if (engine.config.isParallelToolCalls() && n >= 2) {
            executeToolCallsParallel(response, step, iteration, sessionId, assistantMessageID);
            return;
        }
        for (int i = 0; i < n; i++) {
            executeSingleToolCall(response.getToolCalls().get(i), i, step, iteration, round,
                    sessionId, assistantMessageID);
        }
    }

    private void failTruncatedToolCalls(ChatResponse response, AgentStep step, int iteration,
            String sessionId) {
        List<ToolCall> calls = response.getToolCalls();
        for (int i = 0; i < calls.size(); i++) {
            ToolCall tc = calls.get(i);
            AgentToolCall toolCall = new AgentToolCall();
            toolCall.setId(NormalizeToolCallId.normalize(tc.getId(), tc.getFunction().getName(), i));
            toolCall.setName(tc.getFunction().getName());
            Map<String, Object> rawArgs = new LinkedHashMap<>();
            rawArgs.put("_raw", tc.getFunction().getArguments());
            toolCall.setArgs(rawArgs);
            toolCall.setProviderMetadata(tc.getProviderMetadata());
            ToolResult r = new ToolResult();
            r.setSuccess(false);
            r.setError(TRUNCATED_ARGUMENTS_ERROR);
            toolCall.setResult(r);
            step.getToolCalls().add(toolCall);
            emitToolOutcome(toolCall, iteration, sessionId);
        }
    }

    /**
     * 并发执行（读并行、写屏障、并发上限 8、中断取消+等齐——语义都在 {@link ToolBatchExecutor}）。
     * 租户/主体在引擎线程解析成显式值传入虚拟线程——不共享 ThreadLocal。
     */
    private void executeToolCallsParallel(ChatResponse response, AgentStep step, int iteration,
            String sessionId, String assistantMessageID) {
        int round = iteration + 1;
        List<ToolCall> calls = response.getToolCalls();
        TenantContextSnapshot tenant = TenantContextSnapshot.capture();

        // 结果按调用序返回（乱序完成也稳定回填）；屏障调用借快照在引擎线程执行。
        List<AgentToolCall> results = ToolBatchExecutor.execute(calls, round,
                tenant, (idx, call, inWorker) -> runToolCall(call, idx, iteration, round, sessionId,
                        assistantMessageID, inWorker ? null : tenant));

        for (AgentToolCall toolCall : results) {
            step.getToolCalls().add(toolCall);
            emitToolOutcome(toolCall, iteration, sessionId);
        }
    }

    /** 一个完成工具调用的结果/动作事件（所有路径共用）。 */
    private void emitToolOutcome(AgentToolCall toolCall, int iteration, String sessionId) {
        ToolResult result = toolCall.getResult();
        if (result == null) {
            result = new ToolResult();
            result.setSuccess(false);
            result.setError("no result");
        }

        engine.eventBus.emit(new Event(toolCall.getId() + "-tool-result",
                EventType.EVENT_AGENT_TOOL_RESULT, sessionId,
                new AgentToolResultData(toolCall.getId(), toolCall.getExecutionName(),
                        result.getOutput(), result.getError(), result.isSuccess(),
                        toolCall.getDuration(), iteration,
                        deepSortedMap(sanitizeToolDataForPersist(toolCall.getName(),
                                result.getData()))),
                null, ""));

        engine.eventBus.emit(new Event(toolCall.getId() + "-tool-exec",
                EventType.EVENT_AGENT_TOOL, sessionId,
                new AgentActionData(iteration, toolCall.getExecutionName(),
                        deepSortedMap(toolCall.getExecutionArgs()), result.getOutput(),
                        result.isSuccess(), result.getError(), toolCall.getDuration()),
                null, ""));
    }

    private void executeSingleToolCall(ToolCall tc, int i, AgentStep step,
            int iteration, int round, String sessionId, String assistantMessageID) {
        AgentToolCall toolCall = runToolCall(tc, i, iteration, round, sessionId, assistantMessageID, null);
        step.getToolCalls().add(toolCall);
        emitToolOutcome(toolCall, iteration, sessionId);
    }

    /**
     * 单个工具调用：参数解析、执行、日志。可从多线程调用；
     * tenant 为 null 时用当前线程上下文（顺序路径）。
     */
    AgentToolCall runToolCall(ToolCall tc, int i, int iteration, int round,
            String sessionId, String assistantMessageID, TenantContextSnapshot tenant) {
        if (tenant == null) {
            // 顺序路径：直接用当前线程上下文（引擎线程）
            return runToolCallInner(tc, i, iteration, round, sessionId, assistantMessageID);
        }
        // 借用快照执行：并发执行的子线程传 null（子线程自行 replay/clear），
        // 突变屏障分支在**主线程**以非 null 快照调用——TenantScope 保存-恢复，
        // 否则引擎线程的租户/身份会被清掉，后续轮次的模型/KB 解析全部失败。
        return TenantScope.supply(tenant,
                () -> runToolCallInner(tc, i, iteration, round, sessionId, assistantMessageID));
    }

    /** 参数解析结果：{@code Ok} 可执行；{@code Failed} 已是可直接返回的失败调用。 */
    sealed interface ArgumentsOutcome {

        record Ok(Map<String, Object> args) implements ArgumentsOutcome {
        }

        record Failed(AgentToolCall call) implements ArgumentsOutcome {
        }
    }

    /** 构造「参数不可用」的失败调用（三处失败分支共用同一形状）。 */
    private static AgentToolCall failedCall(ToolCall tc, String rawJson, String errorMessage) {
        AgentToolCall c = new AgentToolCall();
        c.setId(tc.getId());
        c.setName(tc.getFunction().getName());
        Map<String, Object> rawArgs = new LinkedHashMap<>();
        rawArgs.put("_raw", rawJson);
        c.setArgs(rawArgs);
        c.setProviderMetadata(tc.getProviderMetadata());
        ToolResult r = new ToolResult();
        r.setSuccess(false);
        r.setError(errorMessage);
        c.setResult(r);
        return c;
    }

    /**
     * 解析工具参数；坏 JSON 走修复，修复后仍不可用或**被截断**则返回失败调用。
     *
     * <p>截断必须拒绝：补齐未终止的字符串/括号能让 payload 解析，但值是 provider 挤出的
     * 残缺值——执行会写半个文件或搜半个查询还报成功。这是无 finish reason 断流的保险带。</p>
     */
    private ArgumentsOutcome parseArguments(ToolCall tc, String argsStr, String toolTag) {
        try {
            return new ArgumentsOutcome.Ok(parseArgsMap(argsStr));
        } catch (Exception firstError) {
            JsonRepair.RepairResult repaired = JsonRepair.repairJsonDetail(argsStr);
            try {
                parseArgsMap(repaired.repaired());
            } catch (Exception ignored) {
                log.error("{} Failed to parse arguments (repair failed)", toolTag, firstError);
                return new ArgumentsOutcome.Failed(failedCall(tc, argsStr,
                        "Failed to parse tool arguments: " + firstError.getMessage()
                                + "\n\nIf the JSON looks cut off, the previous round likely hit the output "
                                + "token cap. Retry with complete JSON (required fields first) and a smaller "
                                + "payload.\n\n[Analyze the error above and try a different approach.]"));
            }
            if (repaired.truncated()) {
                log.warn("{} Arguments were cut off mid-emission ({} bytes); refusing to execute",
                        toolTag, argsStr == null ? 0 : argsStr.length());
                return new ArgumentsOutcome.Failed(failedCall(tc, argsStr, TRUNCATED_ARGUMENTS_ERROR));
            }
            log.warn("{} Repaired malformed JSON arguments", toolTag);
            // 首趟 model-context 扫不动坏 JSON：执行前解码修复后的 payload，
            // 同时保留 tc.ModelArguments 里的原始 provider 载荷。
            ToolCall decoded = new ToolCall();
            decoded.setId(tc.getId());
            decoded.setType(tc.getType());
            decoded.setModelArguments("");
            decoded.setFunction(new FunctionCall(tc.getFunction().getName(), repaired.repaired()));
            decoded.setProviderMetadata(tc.getProviderMetadata());
            engine.modelContext.decodeToolCalls(List.of(decoded));
            tc.getFunction().setArguments(decoded.getFunction().getArguments());
            tc.setArgumentResolution(decoded.getArgumentResolution());
            tc.setUnresolvedHandles(decoded.getUnresolvedHandles());
            try {
                return new ArgumentsOutcome.Ok(parseArgsMap(tc.getFunction().getArguments()));
            } catch (Exception e) {
                return new ArgumentsOutcome.Failed(failedCall(tc, tc.getFunction().getArguments(),
                        "Failed to parse repaired tool arguments: " + e.getMessage()));
            }
        }
    }

    private AgentToolCall runToolCallInner(ToolCall tc, int i, int iteration,
            int round, String sessionId, String assistantMessageID) {
        log.info("[Agent][Round-{}][Tool {}] tenantId={}", round, tc.getFunction().getName(),
                TenantContext.currentTenantId());
        tc.setId(NormalizeToolCallId.normalize(tc.getId(), tc.getFunction().getName(), i));
        String total = "?"; // 孤立时未知；调用方记批量大小
        String toolTag = String.format("[Agent][Round-%d][Tool %s (%d/%s)]",
                round, tc.getFunction().getName(), i + 1, total);

        String argsStr = tc.getFunction().getArguments();
        ArgumentsOutcome outcome = parseArguments(tc, argsStr, toolTag);
        if (outcome instanceof ArgumentsOutcome.Failed failed) {
            return failed.call();
        }
        Map<String, Object> args = ((ArgumentsOutcome.Ok) outcome).args();

        // provider 可见的代理调用保持完整；为活事件/持久展示/追踪解析独立的目标身份。
        ToolCallTarget target = null;
        if (tc.getUnresolvedHandles() == null || tc.getUnresolvedHandles().isEmpty()) {
            try {
                JsonNode raw = JSON.readTree(argsStr == null ? "null" : argsStr);
                target = engine.toolRegistry.mcpCallTarget(tc.getFunction().getName(), raw);
            } catch (Exception e) {
                target = null;
            }
        }
        String executionName = tc.getFunction().getName();
        Map<String, Object> executionArgs = args;
        if (target != null) {
            executionName = target.getName();
            executionArgs = target.getArgs();
        }

        log.debug("{} Args: {}", toolTag, tc.getFunction().getArguments());

        Instant toolCallStartTime = Instant.now();

        // UI 进度提示事件
        String toolHint = formatToolHint(executionName, executionArgs);
        engine.eventBus.emit(new Event(tc.getId() + "-tool-hint", EventType.EVENT_AGENT_TOOL_CALL, sessionId,
                new AgentToolCallData(tc.getId(), executionName,
                        executionArgs,
                        iteration, toolHint), null, ""));

        log.info("[PIPELINE] stage=Agent action=tool_call_start iteration={} round={} tool={} tool_call_id={} tool_index={}/{}",
                iteration, round, executionName, tc.getId(), i + 1, total);

        // 工具执行的 Langfuse span（trace → agent.execute → agent.round.N → agent.tool.<name>）。
        // database_query 的 SQL 被提示层判敏感；langfuse 同策略：原始参数只报键。
        Map<String, Object> toolSpanInput = buildToolSpanInput(tc, executionArgs,
                Boolean.TRUE.equals(TOOL_HINT_SENSITIVE_ARGS.get(executionName)));
        if (target != null) {
            toolSpanInput.put("mcp_service", target.getServiceName());
            toolSpanInput.put("mcp_tool", target.getToolName());
        }
        Object resolutionValue = toolSpanInput.get("argument_resolution");
        String argumentResolution = resolutionValue instanceof String s ? s : "";
        Map<String, Object> toolSpanMeta = new LinkedHashMap<>();
        toolSpanMeta.put("iteration", iteration);
        toolSpanMeta.put("round", round);
        toolSpanMeta.put("tool_index", i + 1);
        toolSpanMeta.put("toolCallId", tc.getId());
        toolSpanMeta.put("sessionId", sessionId);
        toolSpanMeta.put("argument_resolution", argumentResolution);
        toolSpanMeta.put("unresolved_handle_count",
                tc.getUnresolvedHandles() == null ? 0 : tc.getUnresolvedHandles().size());
        Span toolSpan = LangfuseManager.get().startSpan(new LangfuseManager.SpanOptions(
                "agent.tool." + executionName, toolSpanInput, toolSpanMeta));

        Duration execTimeout = AgentConsts.toolExecutionTimeout(tc.getFunction().getName(),
                tc.getFunction().getArguments());
        // 取消语义（不带每工具超时的父取消源）：approvalCancellation=null
        // 时回落外层取消源；人工审批长等待经 ApprovalBridge 桥接，不受工具超时限制。
        ToolExecContext toolExecCtx = new ToolExecContext(sessionId, assistantMessageID, "",
                tc.getId(), TenantContext.currentPrincipal() == null ? ""
                        : TenantContext.currentPrincipal().id(),
                engine.eventBus, null, execTimeout.toMillis());

        ToolResult result = null;
        RuntimeException execError = null;
        if (tc.getUnresolvedHandles() != null && !tc.getUnresolvedHandles().isEmpty()) {
            // 临时句柄不是应用身份：幻觉/过期的 cN/dN/bN/wN/iN/res:// 令牌不能到
            // 持久层、外部服务或路由判定。
            execError = new AgentEngineException(
                    "tool arguments contain unresolved model handles: " + tc.getUnresolvedHandles()
                            + ". Use only handles present in the current conversation; handle numbers are"
                            + " re-assigned every turn, so re-read the latest tool results and retry.");
        } else {
            try {
                JsonNode raw = JSON.readTree(tc.getFunction().getArguments() == null ? "null"
                        : tc.getFunction().getArguments());
                result = engine.toolRegistry.executeTool(engine::pollCancellation, toolExecCtx,
                        tc.getFunction().getName(), raw);
            } catch (JsonProcessingException e) {
                execError = new AgentEngineException(e.getMessage());
            } catch (RuntimeException e) {
                execError = e;
            }
        }
        long duration = Duration.between(toolCallStartTime, Instant.now()).toMillis();

        AgentToolCall toolCall = new AgentToolCall();
        toolCall.setTarget(target);
        toolCall.setId(tc.getId());
        toolCall.setName(tc.getFunction().getName());
        toolCall.setArgs(args);
        toolCall.setResult(result);
        toolCall.setDuration(duration);
        toolCall.setProviderMetadata(tc.getProviderMetadata());

        if (execError != null) {
            // 错误文本要**带着 `error code: N, error message: ` 前缀**（BizException.wireText）。
            // 取 getMessage() 会把前缀丢掉，SSE 终止错误帧的 content 就与既有线格式不一致。
            String execText = BizException.wireText(execError);
            log.error("{} Failed in {}ms: {}", toolTag, duration, execText);
            ToolResult r = new ToolResult();
            r.setSuccess(false);
            r.setError(execText);
            toolCall.setResult(r);
        } else {
            boolean success = toolCall.getResult() != null && toolCall.getResult().isSuccess();
            int outputLen = toolCall.getResult() == null ? 0 : toolCall.getResult().getOutput().length();
            log.info("{} Completed in {}ms: success={}, output={} chars", toolTag, duration, success,
                    outputLen);
        }

        finishToolSpan(toolSpan, toolCall, execError, duration);

        // Pipeline 监控事件（日志）
        boolean toolSuccess = toolCall.getResult() != null && toolCall.getResult().isSuccess();
        String pipelineError = toolCall.getResult() == null ? "" : toolCall.getResult().getError();
        if (execError != null) {
            log.error("[PIPELINE] stage=Agent action=tool_call_result iteration={} round={} tool={} tool_call_id={} duration_ms={} success={} error=\"{}\"",
                    iteration, round, executionName, tc.getId(), duration, toolSuccess, pipelineError);
        } else if (toolSuccess) {
            log.info("[PIPELINE] stage=Agent action=tool_call_result iteration={} round={} tool={} tool_call_id={} duration_ms={} success={}",
                    iteration, round, executionName, tc.getId(), duration, toolSuccess);
        } else {
            log.warn("[PIPELINE] stage=Agent action=tool_call_result iteration={} round={} tool={} tool_call_id={} duration_ms={} success={} error=\"{}\"",
                    iteration, round, executionName, tc.getId(), duration, toolSuccess, pipelineError);
        }

        if (toolCall.getResult() != null && !toolCall.getResult().getOutput().isEmpty()) {
            String preview = toolCall.getResult().getOutput();
            if (preview.length() > 500) {
                preview = preview.substring(0, 500) + "... (truncated)";
            }
            log.debug("{} Output preview:\n{}", toolTag, preview);
        }
        if (toolCall.getResult() != null && !toolCall.getResult().getError().isEmpty()) {
            log.debug("{} Tool error: {}", toolTag, toolCall.getResult().getError());
        }

        return toolCall;
    }

    /** 解析参数 JSON；"null" 输入 → null map。 */
    private static Map<String, Object> parseArgsMap(String argsStr) throws Exception {
        return JSON.readValue(argsStr == null ? "null" : argsStr,
                JSON.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
    }

    /** Langfuse 工具 span 收尾。 */
    private static void finishToolSpan(Span span, AgentToolCall tc, RuntimeException execErr, long durationMs) {
        if (span == null) {
            return;
        }
        boolean success = tc.getResult() != null && tc.getResult().isSuccess();
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("success", success);
        output.put("durationMs", durationMs);
        if (tc.getResult() != null) {
            if (!tc.getResult().getOutput().isEmpty()) {
                output.put("output", AgentEngine.truncateRunes(tc.getResult().getOutput(), LANGFUSE_TOOL_OUTPUT_PREVIEW));
                output.put("output_len", tc.getResult().getOutput().length());
            }
            if (!tc.getResult().getError().isEmpty()) {
                output.put("error", tc.getResult().getError());
            }
            if (tc.getResult().getData() != null && !tc.getResult().getData().isEmpty()) {
                // Data 结构化但可以任意大——只报键形状。
                output.put("data_keys", sortedKeys(tc.getResult().getData()));
            }
            if (tc.getResult().getImages() != null && !tc.getResult().getImages().isEmpty()) {
                output.put("image_count", tc.getResult().getImages().size());
            }
        }
        // span 结果分类：execErr 恒错误；Success=false 的结果也当错误（LLM 会换个思路重试）。
        String spanErr = null;
        if (execErr != null) {
            spanErr = execErr.getMessage();
        } else if (tc.getResult() != null && !tc.getResult().isSuccess()) {
            String msg = tc.getResult().getError();
            if (msg == null || msg.isEmpty()) {
                msg = "tool returned success=false";
            }
            spanErr = msg;
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("success", success);
        meta.put("durationMs", durationMs);
        span.finish(output, meta, spanErr);
    }

    /** 键序按 UTF-8 字节序比较（事件 payload 与既有 jsonb 记录逐字节一致）。 */
    static final Comparator<String> KEY_BYTE_ORDER = (a, b) -> {
        byte[] x = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] y = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int n = Math.min(x.length, y.length);
        for (int idx = 0; idx < n; idx++) {
            if (x[idx] != y[idx]) {
                return Integer.compare(x[idx] & 0xFF, y[idx] & 0xFF);
            }
        }
        return Integer.compare(x.length, y.length);
    };

    private static List<String> sortedKeys(Map<String, Object> data) {
        List<String> keys = new ArrayList<>(data.keySet());
        keys.sort(KEY_BYTE_ORDER);
        return keys;
    }

    /** 递归按 UTF-8 字节序排序键（事件 payload 的 map 契约）；null 原样返回。 */
    static Map<String, Object> deepSortedMap(Map<String, Object> in) {
        if (in == null) {
            return null;
        }
        Map<String, Object> out = new TreeMap<>(KEY_BYTE_ORDER);
        for (Map.Entry<String, Object> e : in.entrySet()) {
            out.put(e.getKey(), deepSortValue(e.getValue()));
        }
        return out;
    }

    private static Object deepSortValue(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new TreeMap<>(KEY_BYTE_ORDER);
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), deepSortValue(e.getValue()));
            }
            return out;
        }
        if (v instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(deepSortValue(item));
            }
            return out;
        }
        return v;
    }

    /** Langfuse 入参双面（model_arguments + resolved_arguments）。 */
    private static Map<String, Object> buildToolSpanInput(ToolCall tc,
            Map<String, Object> resolvedArgs, boolean sensitive) {
        String modelArguments = tc.getModelArguments();
        if (modelArguments == null || modelArguments.isEmpty()) {
            modelArguments = tc.getFunction().getArguments();
        }
        String resolution = tc.getArgumentResolution();
        if (resolution == null || resolution.isEmpty()) {
            resolution = Registry.ARGUMENT_RESOLUTION_UNCHANGED;
        }
        if (sensitive) {
            List<String> modelArgKeys = null;
            Object parsed = traceArgumentValue(modelArguments);
            if (parsed instanceof Map<?, ?> m) {
                modelArgKeys = new ArrayList<>();
                for (Object k : m.keySet()) {
                    modelArgKeys.add(String.valueOf(k));
                }
                modelArgKeys.sort(KEY_BYTE_ORDER);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("toolCallId", tc.getId());
            out.put("model_arg_keys", modelArgKeys);
            out.put("resolved_arg_keys", resolvedArgs == null ? List.of() : sortedKeys(resolvedArgs));
            out.put("argument_resolution", resolution);
            out.put("unresolved_handle_count",
                    tc.getUnresolvedHandles() == null ? 0 : tc.getUnresolvedHandles().size());
            out.put("args_redacted", true);
            return out;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("toolCallId", tc.getId());
        out.put("model_arguments", traceArgumentValue(modelArguments));
        out.put("resolved_arguments", deepSortedMap(resolvedArgs));
        out.put("argument_resolution", resolution);
        out.put("unresolved_handles", tc.getUnresolvedHandles());
        return out;
    }

    /** 合法 JSON 保结构、坏载荷原样保留。 */
    private static Object traceArgumentValue(String raw) {
        try {
            return JSON.readTree(raw);
        } catch (Exception e) {
            return raw;
        }
    }

    /**
     * 按工具剥离的持久化字段表（引擎侧桥）。本类唯一消费点是 emitToolOutcome：
     * SSE 回放/DB 存储前剥掉大字段。
     */
    private static final Map<String, List<String>> PERSIST_STRIP_FIELDS_BY_TOOL = buildPersistStripByTool();

    private static Map<String, List<String>> buildPersistStripByTool() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put(ToolDefinitions.TOOL_READ_FILE, List.of("content", "content_base64", "instructions"));
        m.put(ToolDefinitions.TOOL_SHELL_EXEC, List.of("content", "content_base64"));
        m.put(ToolDefinitions.LEGACY_TOOL_READ_SANDBOX_FILE, List.of("content", "content_base64"));
        m.put(ToolDefinitions.TOOL_WRITE_SANDBOX_FILE, List.of("content", "content_base64"));
        m.put(ToolDefinitions.TOOL_EDIT_SANDBOX_FILE, List.of("content", "content_base64"));
        return m;
    }

    /** display_type 携带的批量字段剥离表。 */
    private static final Map<String, List<String>> PERSIST_STRIP_FIELDS = Map.of(
            "knowledge_chunks_list", List.of("chunks"),
            "grep_results", List.of("chunkResults"));

    /** 返回一份 DB / SSE 回放安全的 Data 副本。 */
    static Map<String, Object> sanitizeToolDataForPersist(String toolName, Map<String, Object> data) {
        if (data == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>(data);
        Object displayTypeValue = data.get("displayType");
        String displayType = displayTypeValue instanceof String s ? s.trim() : "";
        List<String> extraOmit = PERSIST_STRIP_FIELDS_BY_TOOL.get(toolName);
        if (extraOmit != null) {
            for (String key : extraOmit) {
                out.remove(key);
            }
        }
        List<String> byDisplay = PERSIST_STRIP_FIELDS.get(displayType);
        if (byDisplay != null) {
            for (String key : byDisplay) {
                out.remove(key);
            }
        }
        return out;
    }
}
