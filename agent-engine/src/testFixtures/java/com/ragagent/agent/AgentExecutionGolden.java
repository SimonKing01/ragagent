package com.ragagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.platform.fixtures.GoldenResources;

/**
 * Go 录制常量（由录制输出生成，<b>禁止手改</b>）。
 *
 * <p>JSON 已外置到 {@code platform/src/testFixtures/resources/golden/agent-46b.json}；
 * 字段名保持原 API，值由 {@link GoldenResources} 按 SHA-256 校验后加载。</p>
 */
public final class AgentExecutionGolden {

    private static final String BUNDLE = "agent-46b";

    private AgentExecutionGolden() {
    }

    /** 解析一条录制记录。 */
    public static JsonNode rec(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static final String R_EXECUTE_NATURAL_STOP_EVENTS = GoldenResources.get(BUNDLE, "R_EXECUTE_NATURAL_STOP_EVENTS");
    public static final String R_EXECUTE_NATURAL_STOP_STATE = GoldenResources.get(BUNDLE, "R_EXECUTE_NATURAL_STOP_STATE");
    public static final String R_EXECUTE_NATURAL_STOP_CHAT = GoldenResources.get(BUNDLE, "R_EXECUTE_NATURAL_STOP_CHAT");
    public static final String R_EXECUTE_NATURAL_STOP_TOOL_CALLS = GoldenResources.get(BUNDLE, "R_EXECUTE_NATURAL_STOP_TOOL_CALLS");
    public static final String R_EXECUTE_EMPTY_RETRY_EVENTS = GoldenResources.get(BUNDLE, "R_EXECUTE_EMPTY_RETRY_EVENTS");
    public static final String R_EXECUTE_EMPTY_RETRY_STATE = GoldenResources.get(BUNDLE, "R_EXECUTE_EMPTY_RETRY_STATE");
    public static final String R_EXECUTE_EMPTY_RETRY_CHAT = GoldenResources.get(BUNDLE, "R_EXECUTE_EMPTY_RETRY_CHAT");
    public static final String R_EXECUTE_STUCK_LOOP_EVENTS = GoldenResources.get(BUNDLE, "R_EXECUTE_STUCK_LOOP_EVENTS");
    public static final String R_EXECUTE_STUCK_LOOP_STATE = GoldenResources.get(BUNDLE, "R_EXECUTE_STUCK_LOOP_STATE");
    public static final String R_EXECUTE_STUCK_LOOP_ROUNDS = GoldenResources.get(BUNDLE, "R_EXECUTE_STUCK_LOOP_ROUNDS");
    public static final String R_EXECUTE_MAX_ITERATIONS_EVENTS = GoldenResources.get(BUNDLE, "R_EXECUTE_MAX_ITERATIONS_EVENTS");
    public static final String R_EXECUTE_MAX_ITERATIONS_STATE = GoldenResources.get(BUNDLE, "R_EXECUTE_MAX_ITERATIONS_STATE");
    public static final String R_EXECUTE_MAX_ITERATIONS_CHAT = GoldenResources.get(BUNDLE, "R_EXECUTE_MAX_ITERATIONS_CHAT");
    public static final String R_EXECUTE_MAX_ITERATIONS_TOOL_EXEC_COUNT = GoldenResources.get(BUNDLE, "R_EXECUTE_MAX_ITERATIONS_TOOL_EXEC_COUNT");
    public static final String R_EXECUTE_EMPTY_EXHAUSTED_EVENTS = GoldenResources.get(BUNDLE, "R_EXECUTE_EMPTY_EXHAUSTED_EVENTS");
    public static final String R_EXECUTE_EMPTY_EXHAUSTED_STATE = GoldenResources.get(BUNDLE, "R_EXECUTE_EMPTY_EXHAUSTED_STATE");
    public static final String R_EXECUTE_CONTENT_FILTER_EVENTS = GoldenResources.get(BUNDLE, "R_EXECUTE_CONTENT_FILTER_EVENTS");
    public static final String R_EXECUTE_CONTENT_FILTER_STATE = GoldenResources.get(BUNDLE, "R_EXECUTE_CONTENT_FILTER_STATE");
    public static final String R_RUN_TOOL_CALL_ARGS_REPAIR = GoldenResources.get(BUNDLE, "R_RUN_TOOL_CALL_ARGS_REPAIR");
    public static final String R_RUN_TOOL_CALL_ARGS_REPAIR_EXEC = GoldenResources.get(BUNDLE, "R_RUN_TOOL_CALL_ARGS_REPAIR_EXEC");
    public static final String R_RUN_TOOL_CALL_ARGS_REPAIR_EVENTS = GoldenResources.get(BUNDLE, "R_RUN_TOOL_CALL_ARGS_REPAIR_EVENTS");
    public static final String R_RUN_TOOL_CALL_ARGS_TRUNCATED = GoldenResources.get(BUNDLE, "R_RUN_TOOL_CALL_ARGS_TRUNCATED");
    public static final String R_RUN_TOOL_CALL_ARGS_TRUNCATED_EXEC = GoldenResources.get(BUNDLE, "R_RUN_TOOL_CALL_ARGS_TRUNCATED_EXEC");
    public static final String R_RUN_TOOL_CALL_ARGS_TRUNCATED_EVENTS = GoldenResources.get(BUNDLE, "R_RUN_TOOL_CALL_ARGS_TRUNCATED_EVENTS");
    public static final String R_RUN_TOOL_CALL_ARGS_UNREPAIRABLE = GoldenResources.get(BUNDLE, "R_RUN_TOOL_CALL_ARGS_UNREPAIRABLE");
    public static final String R_RUN_TOOL_CALL_ARGS_UNREPAIRABLE_EXEC = GoldenResources.get(BUNDLE, "R_RUN_TOOL_CALL_ARGS_UNREPAIRABLE_EXEC");
    public static final String R_RUN_TOOL_CALL_ARGS_UNREPAIRABLE_EVENTS = GoldenResources.get(BUNDLE, "R_RUN_TOOL_CALL_ARGS_UNREPAIRABLE_EVENTS");
    public static final String R_RUN_TOOL_CALL_ARGS_UNRESOLVED_HANDLES = GoldenResources.get(BUNDLE, "R_RUN_TOOL_CALL_ARGS_UNRESOLVED_HANDLES");
    public static final String R_RUN_TOOL_CALL_ARGS_UNRESOLVED_HANDLES_EXEC = GoldenResources.get(BUNDLE, "R_RUN_TOOL_CALL_ARGS_UNRESOLVED_HANDLES_EXEC");
    public static final String R_RUN_TOOL_CALL_ARGS_UNRESOLVED_HANDLES_EVENTS = GoldenResources.get(BUNDLE, "R_RUN_TOOL_CALL_ARGS_UNRESOLVED_HANDLES_EVENTS");
    public static final String R_EXECUTE_PARALLEL_TOOLS_EVENTS = GoldenResources.get(BUNDLE, "R_EXECUTE_PARALLEL_TOOLS_EVENTS");
    public static final String R_EXECUTE_PARALLEL_TOOLS_STATE = GoldenResources.get(BUNDLE, "R_EXECUTE_PARALLEL_TOOLS_STATE");
    public static final String R_EXECUTE_LENGTH_FINISH_EVENTS = GoldenResources.get(BUNDLE, "R_EXECUTE_LENGTH_FINISH_EVENTS");
    public static final String R_EXECUTE_LENGTH_FINISH_STATE = GoldenResources.get(BUNDLE, "R_EXECUTE_LENGTH_FINISH_STATE");
    public static final String R_STEER_DRAIN_INJECTED = GoldenResources.get(BUNDLE, "R_STEER_DRAIN_INJECTED");
    public static final String R_STEER_DRAIN_MESSAGES = GoldenResources.get(BUNDLE, "R_STEER_DRAIN_MESSAGES");
    public static final String R_STEER_DRAIN_PENDING = GoldenResources.get(BUNDLE, "R_STEER_DRAIN_PENDING");
    public static final String R_STEER_DRAIN_PERSISTED = GoldenResources.get(BUNDLE, "R_STEER_DRAIN_PERSISTED");
    public static final String R_STEER_DRAIN_EVENTS = GoldenResources.get(BUNDLE, "R_STEER_DRAIN_EVENTS");
    public static final String R_STEER_DRAIN_INJECTED_SECOND = GoldenResources.get(BUNDLE, "R_STEER_DRAIN_INJECTED_SECOND");
    public static final String R_STEER_DRAIN_INJECTED_FAILPERSIST = GoldenResources.get(BUNDLE, "R_STEER_DRAIN_INJECTED_FAILPERSIST");
    public static final String R_STEER_DRAIN_PENDING_AFTER_FAIL = GoldenResources.get(BUNDLE, "R_STEER_DRAIN_PENDING_AFTER_FAIL");
    public static final String R_STEER_LOOP_END_INJECT_EVENTS = GoldenResources.get(BUNDLE, "R_STEER_LOOP_END_INJECT_EVENTS");
    public static final String R_STEER_LOOP_END_INJECT_STATE = GoldenResources.get(BUNDLE, "R_STEER_LOOP_END_INJECT_STATE");
    public static final String R_STEER_LOOP_END_INJECT_CHAT = GoldenResources.get(BUNDLE, "R_STEER_LOOP_END_INJECT_CHAT");
    public static final String R_RENDER_USER_TURN_WITH_ALL = GoldenResources.get(BUNDLE, "R_RENDER_USER_TURN_WITH_ALL");
    public static final String R_RENDER_USER_TURN_EMPTY = GoldenResources.get(BUNDLE, "R_RENDER_USER_TURN_EMPTY");
    public static final String R_TRIM_TOOL_RESULTS_OK = GoldenResources.get(BUNDLE, "R_TRIM_TOOL_RESULTS_OK");
    public static final String R_TRIM_TOOL_RESULTS_FULL_TOKENS = GoldenResources.get(BUNDLE, "R_TRIM_TOOL_RESULTS_FULL_TOKENS");
    public static final String R_TRIM_TOOL_RESULTS_TRIMMED = GoldenResources.get(BUNDLE, "R_TRIM_TOOL_RESULTS_TRIMMED");
    public static final String R_MANAGE_CONTEXT_WINDOW_CHANGED = GoldenResources.get(BUNDLE, "R_MANAGE_CONTEXT_WINDOW_CHANGED");
    public static final String R_MANAGE_CONTEXT_WINDOW_BEFORE_TOKENS = GoldenResources.get(BUNDLE, "R_MANAGE_CONTEXT_WINDOW_BEFORE_TOKENS");
    public static final String R_MANAGE_CONTEXT_WINDOW_AFTER_TOKENS = GoldenResources.get(BUNDLE, "R_MANAGE_CONTEXT_WINDOW_AFTER_TOKENS");
    public static final String R_MANAGE_CONTEXT_WINDOW_AFTER_MESSAGES = GoldenResources.get(BUNDLE, "R_MANAGE_CONTEXT_WINDOW_AFTER_MESSAGES");
    public static final String R_MANAGE_CONTEXT_WINDOW_EVENTS = GoldenResources.get(BUNDLE, "R_MANAGE_CONTEXT_WINDOW_EVENTS");
    public static final String R_MANAGE_CONTEXT_WINDOW_SETTINGS = GoldenResources.get(BUNDLE, "R_MANAGE_CONTEXT_WINDOW_SETTINGS");
    public static final String R_MANAGE_CONTEXT_WINDOW_CHANGED_SECOND = GoldenResources.get(BUNDLE, "R_MANAGE_CONTEXT_WINDOW_CHANGED_SECOND");
    public static final String R_MANAGE_CONTEXT_WINDOW_SUMMARIZER_CALLS = GoldenResources.get(BUNDLE, "R_MANAGE_CONTEXT_WINDOW_SUMMARIZER_CALLS");
    public static final String R_ESTIMATE_CURRENT_TOKENS_BASELINE = GoldenResources.get(BUNDLE, "R_ESTIMATE_CURRENT_TOKENS_BASELINE");
    public static final String R_ESTIMATE_CURRENT_TOKENS_NO_BASELINE = GoldenResources.get(BUNDLE, "R_ESTIMATE_CURRENT_TOKENS_NO_BASELINE");
    public static final String R_BUILD_TOOLS_TOOLS = GoldenResources.get(BUNDLE, "R_BUILD_TOOLS_TOOLS");
    public static final String R_BUILD_TOOLS_NAMES = GoldenResources.get(BUNDLE, "R_BUILD_TOOLS_NAMES");
    public static final String R_STREAM_THINKING_ROUTING_EVENTS = GoldenResources.get(BUNDLE, "R_STREAM_THINKING_ROUTING_EVENTS");
    public static final String R_STREAM_THINKING_ROUTING_RESP = GoldenResources.get(BUNDLE, "R_STREAM_THINKING_ROUTING_RESP");
    public static final String R_EXECUTE_STREAM_ERROR_ERROR = GoldenResources.get(BUNDLE, "R_EXECUTE_STREAM_ERROR_ERROR");
    public static final String R_EXECUTE_STREAM_ERROR_STATE = GoldenResources.get(BUNDLE, "R_EXECUTE_STREAM_ERROR_STATE");
    public static final String R_EXECUTE_STREAM_ERROR_EVENTS = GoldenResources.get(BUNDLE, "R_EXECUTE_STREAM_ERROR_EVENTS");
    public static final String R_EXECUTE_GRACEFUL_DEGRADATION_ERROR = GoldenResources.get(BUNDLE, "R_EXECUTE_GRACEFUL_DEGRADATION_ERROR");
    public static final String R_EXECUTE_GRACEFUL_DEGRADATION_STATE = GoldenResources.get(BUNDLE, "R_EXECUTE_GRACEFUL_DEGRADATION_STATE");
    public static final String R_EXECUTE_GRACEFUL_DEGRADATION_EVENTS = GoldenResources.get(BUNDLE, "R_EXECUTE_GRACEFUL_DEGRADATION_EVENTS");
    public static final String R_EXECUTE_GRACEFUL_DEGRADATION_CHAT = GoldenResources.get(BUNDLE, "R_EXECUTE_GRACEFUL_DEGRADATION_CHAT");
    public static final String R_EXECUTE_GRACEFUL_DEGRADATION_TOOL_EXEC_COUNT = GoldenResources.get(BUNDLE, "R_EXECUTE_GRACEFUL_DEGRADATION_TOOL_EXEC_COUNT");
    public static final String R_FINISH_REASON_CLASSES_NATURAL = GoldenResources.get(BUNDLE, "R_FINISH_REASON_CLASSES_NATURAL");
    public static final String R_FINISH_REASON_CLASSES_LENGTH = GoldenResources.get(BUNDLE, "R_FINISH_REASON_CLASSES_LENGTH");
}
