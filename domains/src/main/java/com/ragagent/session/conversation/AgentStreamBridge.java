package com.ragagent.session.conversation;

import com.ragagent.contract.agent.AgentStep;
import com.ragagent.contract.event.Event;
import com.ragagent.contract.event.EventBusInterface;
import com.ragagent.contract.event.payload.AgentCompleteData;
import com.ragagent.contract.event.payload.AgentFinalAnswerData;
import com.ragagent.contract.event.payload.AgentThoughtData;
import com.ragagent.contract.event.payload.AgentToolCallData;
import com.ragagent.contract.event.payload.AgentToolResultData;
import com.ragagent.contract.event.payload.ContextCompactedData;
import com.ragagent.contract.event.payload.ErrorData;
import com.ragagent.contract.event.payload.MCPOAuthRequiredData;
import com.ragagent.contract.event.payload.MCPOAuthResolvedData;
import com.ragagent.contract.event.payload.ToolApprovalRequiredData;
import com.ragagent.contract.event.payload.ToolApprovalResolvedData;
import com.ragagent.contract.event.payload.UserMessageInjectedData;
import com.ragagent.contract.model.ResponseType;
import com.ragagent.contract.model.TokenUsage;
import com.ragagent.contract.retrieval.SearchResult;
import com.ragagent.contract.tool.ToolResult;
import com.ragagent.platform.event.EventType;
import com.ragagent.platform.stream.StreamEvent;
import com.ragagent.platform.stream.StreamManager;
import com.ragagent.platform.web.JsonMappers;
import com.ragagent.session.domain.message.Message;
import com.ragagent.session.dto.AgentReferencesData;
import com.ragagent.session.dto.AgentReflectionData;
import com.ragagent.session.dto.MemoryRecalledData;
import com.ragagent.session.dto.PipelineUsedMemoryView;
import com.ragagent.session.dto.SessionTitleData;
import com.ragagent.session.service.TurnMessageGate;
import com.ragagent.session.service.TurnMessageHandle;
import com.ragagent.session.support.PipelineViews;
import com.ragagent.session.support.ToolResultPersist;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * agent 事件订阅桥。
 *
 * <p>每个请求一条专属 EventBusInterface（无 SessionID 过滤），事件按到达序 AppendEvent 进
 * StreamManager（不做累积——前端按 event id 累积）。</p>
 *
 * <h2>16 种事件订阅 + final_answer 分片重组（最高危）</h2>
 * <ul>
 *   <li><b>superseded preamble 剔除</b>：一次非终局轮可能在它自己的 answer event id
 *       下流出一段前导（"让我搜一下…"），随后该轮决定调工具 → 这些段被标 superseded，
 *       不再进持久化的 Message.Content。tool_call 首次到达时统一标记。</li>
 *   <li><b>duration 记账</b>：thought/final_answer 用 evt.ID 记首 chunk 时间；
 *       tool_call/tool_result 用 ToolCallID。</li>
 *   <li><b>complete 事件</b>：usage 恒取（缺省用 NullNode/instanceof 判别）；agent_steps 经 Sanitize 落库；
 *       finalAnswer 为空但有 FinalAnswer 时补发 fallback answer 事件对。</li>
 * </ul>
 *
 */
public final class AgentStreamBridge {

    private static final Logger log = LoggerFactory.getLogger(AgentStreamBridge.class);

    private final String sessionId;
    private final String assistantMessageId;
    private final String requestId;
    /** Handler entry timestamp, used for TTFB logging */
    private final OffsetDateTime receivedAt;
    private boolean ttfbLogged;
    private final Message assistantMessage;

    /** SSE 发射缝（各 handler 的"组装 + 试追加 + 日志"样板收拢处）。 */
    private final AgentStreamEmitter emitter;
    private final EventBusInterface eventBus;
    // ── State tracking ──
    private final List<SearchResult> knowledgeRefs = new ArrayList<>();
    private String finalAnswer = "";
    /** Per-answer-event-ID accumulation, so superseded preambles can be dropped */
    private final List<AnswerSegment> answerSegments = new ArrayList<>();
    /** Track start time for duration calculation */
    private final Map<String, Long> eventStartTimes = new LinkedHashMap<>();
    /** 本轮 assistant 行的共享写入锁（与最终答复/快答时间线同一监视器）。 */
    private final TurnMessageGate messageGate;

    /** 单个 answer event id 下的分片累积。 */
    private static final class AnswerSegment {
        final String id;
        String content = "";
        boolean superseded;

        AnswerSegment(String id) {
            this.id = id;
        }
    }

    private AnswerSegment findAnswerSegment(String id) {
        for (AnswerSegment seg : answerSegments) {
            if (seg.id.equals(id)) {
                return seg;
            }
        }
        return null;
    }

    /** 所有未 superseded 的段按到达序重组。 */
    private String composeFinalAnswer() {
        StringBuilder b = new StringBuilder();
        for (AnswerSegment seg : answerSegments) {
            if (!seg.superseded) {
                b.append(seg.content);
            }
        }
        return b.toString();
    }

    public AgentStreamBridge(
            String sessionId, String assistantMessageId, String requestId,
            OffsetDateTime receivedAt, TurnMessageHandle handle,
            StreamManager streamManager, EventBusInterface eventBus) {
        this.sessionId = sessionId;
        this.assistantMessageId = assistantMessageId;
        this.requestId = requestId;
        this.receivedAt = receivedAt;
        this.assistantMessage = handle.message();
        this.emitter = new AgentStreamEmitter(sessionId, assistantMessageId, streamManager);
        this.eventBus = eventBus;
        this.messageGate = handle.gate();
    }

    public Message getAssistantMessage() {
        return assistantMessage;
    }

    /** 按固定顺序订阅 16 种事件（订阅序即回调序）。 */
    public void subscribe() {
        eventBus.on(EventType.EVENT_AGENT_THOUGHT, this::handleThought);
        eventBus.on(EventType.EVENT_AGENT_TOOL_CALL, this::handleToolCall);
        eventBus.on(EventType.EVENT_AGENT_TOOL_RESULT, this::handleToolResult);
        eventBus.on(EventType.EVENT_AGENT_REFERENCES, this::handleReferences);
        eventBus.on(EventType.EVENT_MEMORY_RECALLED, this::handleMemoryRecalled);
        eventBus.on(EventType.EVENT_CONTEXT_COMPACTED, this::handleContextCompacted);
        eventBus.on(EventType.EVENT_USER_MESSAGE_INJECTED, this::handleUserMessageInjected);
        eventBus.on(EventType.EVENT_AGENT_FINAL_ANSWER, this::handleFinalAnswer);
        eventBus.on(EventType.EVENT_AGENT_REFLECTION, this::handleReflection);
        eventBus.on(EventType.EVENT_ERROR, this::handleError);
        eventBus.on(EventType.EVENT_SESSION_TITLE, this::handleSessionTitle);
        eventBus.on(EventType.EVENT_AGENT_COMPLETE, this::handleComplete);
        eventBus.on(EventType.EVENT_TOOL_APPROVAL_REQUIRED, this::handleToolApprovalRequired);
        eventBus.on(EventType.EVENT_TOOL_APPROVAL_RESOLVED, this::handleToolApprovalResolved);
        eventBus.on(EventType.EVENT_MCP_OAUTH_REQUIRED, this::handleMCPOAuthRequired);
        eventBus.on(EventType.EVENT_MCP_OAUTH_RESOLVED, this::handleMCPOAuthResolved);
    }

    // ── handleThought ─────────────────────────────────────────

    private Object handleThought(Event evt) {
        if (!(evt.getData() instanceof AgentThoughtData data)) {
            return null;
        }
        Map<String, Object> metadata;
        synchronized (messageGate.monitor()) {
            eventStartTimes.putIfAbsent(evt.getId(), System.currentTimeMillis());
            if (data.isDone()) {
                long startTime = eventStartTimes.getOrDefault(evt.getId(), System.currentTimeMillis());
                long duration = System.currentTimeMillis() - startTime;
                metadata = new LinkedHashMap<>();
                metadata.put("eventId", evt.getId());
                metadata.put("durationMs", duration);
                metadata.put("completedAt", System.currentTimeMillis() / 1000L);
                eventStartTimes.remove(evt.getId());
            } else {
                metadata = new LinkedHashMap<>();
                metadata.put("eventId", evt.getId());
            }
        }
        emitter.emit(evt.getId(), ResponseType.THINKING, orEmpty(data.getContent()), data.isDone(), metadata,
                "Append thought event to stream failed");
        return null;
    }

    // ── handleToolCall（含 superseded preamble 剔除） ─────────────────────────

    private Object handleToolCall(Event evt) {
        if (!(evt.getData() instanceof AgentToolCallData data)) {
            return null;
        }
        synchronized (messageGate.monitor()) {
            boolean first = !eventStartTimes.containsKey(data.getToolCallId());
            if (first) {
                eventStartTimes.put(data.getToolCallId(), System.currentTimeMillis());
                // Any answer text streamed before this tool call was a non-terminal round's
                // preamble, not the final answer. Drop those segments from the persisted
                // answer so the preamble never leaks into Message.Content.
                boolean supersededAny = false;
                for (AnswerSegment seg : answerSegments) {
                    if (!seg.superseded && !seg.content.isEmpty()) {
                        seg.superseded = true;
                        supersededAny = true;
                    }
                }
                if (supersededAny) {
                    finalAnswer = composeFinalAnswer();
                }
            }
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("toolName", data.getToolName());
        metadata.put("arguments", data.getArguments());
        metadata.put("toolCallId", data.getToolCallId());

        emitter.emit(evt.getId(), ResponseType.TOOL_CALL, "Calling tool: " + data.getToolName(), false,
                metadata, "Append tool call event to stream failed");
        return null;
    }

    // ── handleToolResult ──────────────────────────────────────

    private Object handleToolResult(Event evt) {
        if (!(evt.getData() instanceof AgentToolResultData data)) {
            return null;
        }
        long durationMs;
        synchronized (messageGate.monitor()) {
            Long startTime = eventStartTimes.get(data.getToolCallId());
            if (startTime != null) {
                durationMs = System.currentTimeMillis() - startTime;
                eventStartTimes.remove(data.getToolCallId());
            } else if (data.getDurationMs() > 0) {
                // Fallback to provided duration if start time not tracked
                durationMs = data.getDurationMs();
            } else {
                durationMs = 0;
            }
        }

        // Send SSE response (both success and failure)
        ResponseType responseType = ResponseType.TOOL_RESULT;
        Map<String, Object> resultData = data.getData();
        String content = ToolResultPersist.streamContentForToolResult(
                data.getToolName(), data.isSuccess(), data.getError(), resultData);
        if (!data.isSuccess()) {
            responseType = ResponseType.ERROR;
            if (content.isEmpty() && data.getError() != null && !data.getError().isEmpty()) {
                content = data.getError();
            }
        }

        // Build metadata including tool result data for rich frontend rendering
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("toolName", data.getToolName());
        metadata.put("success", data.isSuccess());
        metadata.put("error", data.getError());
        metadata.put("durationMs", durationMs);
        metadata.put("toolCallId", data.getToolCallId());

        ToolResult tr = new ToolResult();
        tr.setSuccess(data.isSuccess());
        tr.setOutput(data.getOutput());
        tr.setError(data.getError());
        tr.setData(resultData);
        Map<String, Object> clientData = ToolResultPersist.sanitizeToolResultForClient(data.getToolName(), tr);
        metadata.putAll(clientData);

        emitter.emit(evt.getId(), responseType, content, false, metadata, "Append tool result event to stream failed");
        return null;
    }

    // ── 审批 / OAuth ─────────────────────────────────────────────────────────

    private static final com.fasterxml.jackson.databind.ObjectMapper CAST_MAPPER =
            JsonMappers.lenient();

    private static Map<String, Object> toolApprovalDataToMap(Object v) {
        try {
            Map<String, Object> m = CAST_MAPPER.convertValue(v,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            return m == null ? new LinkedHashMap<>() : m;
        } catch (RuntimeException e) {
            log.warn("Failed to convert event payload to map, using empty payload", e);
            return new LinkedHashMap<>();
        }
    }

    private Object handleToolApprovalRequired(Event evt) {
        if (!(evt.getData() instanceof ToolApprovalRequiredData data)) {
            return null;
        }
        Map<String, Object> meta = toolApprovalDataToMap(data);
        meta.put("pendingId", data.getPendingId());
        emitter.emit(evt.getId(), ResponseType.TOOL_APPROVAL_REQUIRED, "MCP tool requires human approval",
                true, meta, "Append tool approval required event failed");
        return null;
    }

    private Object handleToolApprovalResolved(Event evt) {
        if (!(evt.getData() instanceof ToolApprovalResolvedData data)) {
            return null;
        }
        Map<String, Object> meta = toolApprovalDataToMap(data);
        meta.put("pendingId", data.getPendingId());
        emitter.emit(evt.getId(), ResponseType.TOOL_APPROVAL_RESOLVED, "MCP tool approval resolved", true,
                meta, "Append tool approval resolved event failed");
        return null;
    }

    private Object handleMCPOAuthRequired(Event evt) {
        if (!(evt.getData() instanceof MCPOAuthRequiredData data)) {
            return null;
        }
        Map<String, Object> meta = toolApprovalDataToMap(data);
        meta.put("pendingId", data.getPendingId());
        emitter.emit(evt.getId(), ResponseType.MCP_OAUTH_REQUIRED, "MCP service requires OAuth authorization",
                true, meta, "Append mcp oauth required event failed");
        return null;
    }

    private Object handleMCPOAuthResolved(Event evt) {
        if (!(evt.getData() instanceof MCPOAuthResolvedData data)) {
            return null;
        }
        Map<String, Object> meta = toolApprovalDataToMap(data);
        meta.put("pendingId", data.getPendingId());
        emitter.emit(evt.getId(), ResponseType.MCP_OAUTH_RESOLVED, "MCP OAuth authorization resolved", true,
                meta, "Append mcp oauth resolved event failed");
        return null;
    }

    // ── handleReferences ──────────────────────────────────────

    private Object handleReferences(Event evt) {
        if (!(evt.getData() instanceof AgentReferencesData data)) {
            return null;
        }
        synchronized (messageGate.monitor()) {
            // Extract knowledge references（Java：List<SearchResult> 直 cast，Map 回退重建）
            if (data.getReferences() instanceof List<?> refs) {
                for (Object ref : refs) {
                    if (ref instanceof SearchResult sr) {
                        knowledgeRefs.add(sr);
                    } else if (ref instanceof Map<?, ?> refMap) {
                        knowledgeRefs.add(searchResultFromMap(refMap));
                    }
                }
            }
            assistantMessage.setKnowledgeReferences(knowledgeRefs);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("references", List.copyOf(knowledgeRefs));

        emitter.emit(evt.getId(), ResponseType.REFERENCES, "", false, payload,
                "Append references event to stream failed");
        return null;
    }

    // ── handleMemoryRecalled ──────────────────────────────────

    private Object handleMemoryRecalled(Event evt) {
        if (!(evt.getData() instanceof MemoryRecalledData data)) {
            return null;
        }
        if (!(data.getMemories() instanceof List<?> used) || used.isEmpty()) {
            return null;
        }
        synchronized (messageGate.monitor()) {
            // 事件载荷是跨域视图（chatpipeline 侧产生），落库前映射回实体
            List<PipelineUsedMemoryView> views = new ArrayList<>();
            for (Object item : used) {
                if (item instanceof PipelineUsedMemoryView v) {
                    views.add(v);
                }
            }
            assistantMessage.setUsedMemories(PipelineViews.toUsedMemories(views));
        }
        emitter.emit(evt.getId(), ResponseType.MEMORY_RECALLED, "", false, Map.of("memories",
                data.getMemories()), "Append memory recalled event to stream failed");
        return null;
    }

    // ── handleContextCompacted ────────────────────────────────

    private Object handleContextCompacted(Event evt) {
        if (!(evt.getData() instanceof ContextCompactedData data)) {
            return null;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", data.getReason());
        payload.put("round", data.getRound());
        payload.put("tokensBefore", data.getTokensBefore());
        payload.put("tokensAfter", data.getTokensAfter());
        payload.put("messagesBefore", data.getMessagesBefore());
        payload.put("messagesAfter", data.getMessagesAfter());
        payload.put("summary", data.getSummary());
        payload.put("degraded", data.isDegraded());
        payload.put("splitTurn", data.isSplitTurn());
        emitter.emit(evt.getId(), ResponseType.CONTEXT_COMPACTED, "", true, payload,
                "Append context compacted event to stream failed");
        return null;
    }

    // ── handleFinalAnswer（event-id 分片重组） ────────────────────────────────

    private Object handleFinalAnswer(Event evt) {
        if (!(evt.getData() instanceof AgentFinalAnswerData data)) {
            return null;
        }
        Map<String, Object> metadata;
        synchronized (messageGate.monitor()) {
            eventStartTimes.putIfAbsent(evt.getId(), System.currentTimeMillis());

            // One-shot TTFB log on the first answer chunk.
            if (!ttfbLogged && receivedAt != null) {
                ttfbLogged = true;
                long ttfb = System.currentTimeMillis() - receivedAt.toInstant().toEpochMilli();
                log.info("TTFB:first_answer_chunk request_id={}, session_id={}, ttfb_ms={}",
                        requestId, sessionId, ttfb);
            }

            // Accumulate per event ID so a later supersede can subtract this segment.
            if (data.getContent() != null && !data.getContent().isEmpty()) {
                AnswerSegment seg = findAnswerSegment(evt.getId());
                if (seg == null) {
                    seg = new AnswerSegment(evt.getId());
                    answerSegments.add(seg);
                }
                seg.content += data.getContent();
                finalAnswer = composeFinalAnswer();
            }
            if (data.isFallback()) {
                assistantMessage.setFallback(true);
            }

            if (data.isDone()) {
                long startTime = eventStartTimes.getOrDefault(evt.getId(), System.currentTimeMillis());
                long duration = System.currentTimeMillis() - startTime;
                metadata = new LinkedHashMap<>();
                metadata.put("eventId", evt.getId());
                metadata.put("durationMs", duration);
                metadata.put("completedAt", System.currentTimeMillis() / 1000L);
                eventStartTimes.remove(evt.getId());
            } else {
                metadata = new LinkedHashMap<>();
                metadata.put("eventId", evt.getId());
            }
            if (data.isFallback()) {
                metadata.put("isFallback", true);
            }
        }
        emitter.emit(evt.getId(), ResponseType.ANSWER, orEmpty(data.getContent()), data.isDone(), metadata,
                "Append answer event to stream failed");
        return null;
    }

    // ── handleReflection ──────────────────────────────────────

    private Object handleReflection(Event evt) {
        if (!(evt.getData() instanceof AgentReflectionData data)) {
            return null;
        }
        emitter.emit(evt.getId(), ResponseType.REFLECTION, orEmpty(data.getContent()), data.isDone(), null,
                "Append reflection event to stream failed");
        return null;
    }

    // ── handleError ───────────────────────────────────────────

    private Object handleError(Event evt) {
        if (!(evt.getData() instanceof ErrorData data)) {
            return null;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("stage", data.getStage());
        metadata.put("error", data.getError());
        emitter.emit(evt.getId(), ResponseType.ERROR, orEmpty(data.getError()), true, metadata,
                "Append error event to stream failed");
        return null;
    }

    // ── handleSessionTitle ────────────────────────────────────

    private Object handleSessionTitle(Event evt) {
        if (!(evt.getData() instanceof SessionTitleData data)) {
            return null;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", data.getSessionId());
        payload.put("title", data.getTitle());
        emitter.emitTolerant(evt.getId(), ResponseType.SESSION_TITLE, data.getTitle(), true, payload,
                "Append session title event to stream failed (stream may have ended)");
        return null;
    }

    // ── handleUserMessageInjected ─────────────────────────────

    private Object handleUserMessageInjected(Event evt) {
        if (!(evt.getData() instanceof UserMessageInjectedData data)) {
            return null;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("steerId", data.getSteerId());
        payload.put("messageId", data.getMessageId());
        payload.put("content", data.getContent());
        payload.put("userMessageId", data.getUserMessageId());
        emitter.emit(evt.getId(), ResponseType.USER_MESSAGE_INJECTED, "", true, payload,
                "Append user message injected event to stream failed");
        return null;
    }

    // ── handleComplete ────────────────────────────────────────

    private Object handleComplete(Event evt) {
        if (!(evt.getData() instanceof AgentCompleteData data)) {
            return null;
        }
        synchronized (messageGate.monitor()) {
            if (assistantMessageId.equals(data.getMessageId())) {
                assistantMessage.setCompleted(true);
                assistantMessage.setAgentDurationMs(data.getTotalDurationMs());

                // Update knowledge references if provided
                if (data.getKnowledgeRefs() != null && !data.getKnowledgeRefs().isEmpty()) {
                    List<SearchResult> refs = new ArrayList<>();
                    for (Object ref : data.getKnowledgeRefs()) {
                        if (ref instanceof SearchResult sr) {
                            refs.add(sr);
                        }
                    }
                    assistantMessage.setKnowledgeReferences(refs);
                }

                assistantMessage.setContent(assistantMessage.getContent() + orEmpty(data.getFinalAnswer()));

                // Update agent steps if provided
                if (data.getAgentSteps() instanceof List<?> rawSteps) {
                    List<AgentStep> steps = new ArrayList<>();
                    for (Object raw : rawSteps) {
                        if (raw instanceof AgentStep as) {
                            steps.add(as);
                        }
                    }
                    assistantMessage.setAgentSteps(ToolResultPersist.sanitizeAgentStepsForStorage(steps));
                }

                // Persist the turn's aggregated LLM usage（NullNode/空 = 无用量）
                if (data.getUsage() instanceof TokenUsage usage) {
                    assistantMessage.setUsage(usage);
                }
            }

            // Fallback: no answer events streamed but a final answer exists → emit answer pair.
            if (finalAnswer.isEmpty() && data.getFinalAnswer() != null && !data.getFinalAnswer().isEmpty()) {
                log.warn("No answer events were streamed, emitting fallback answer (len={}). "
                        + "This typically happens when: (1) model stopped naturally and content was sent as "
                        + "thought events, or (2) Ollama model returned tool calls non-incrementally. "
                        + "total_steps={}, total_duration_ms={}",
                        data.getFinalAnswer().length(), data.getTotalSteps(), data.getTotalDurationMs());
                String fallbackId = "answer-fallback-" + System.currentTimeMillis();
                StreamEvent first = new StreamEvent();
                first.setId(fallbackId);
                first.setType(ResponseType.ANSWER);
                first.setContent(data.getFinalAnswer());
                first.setDone(false);
                first.setTimestamp(OffsetDateTime.now());
                Map<String, Object> d1 = new LinkedHashMap<>();
                d1.put("eventId", fallbackId);
                d1.put("isFallback", true);
                first.setData(d1);
                StreamEvent second = new StreamEvent();
                second.setId(fallbackId);
                second.setType(ResponseType.ANSWER);
                second.setContent("");
                second.setDone(true);
                second.setTimestamp(OffsetDateTime.now());
                second.setData(new LinkedHashMap<>(d1));
                emitter.appendAll("Append fallback answer event failed", first, second);
            }
        }

        // Completion event for the stream manager（SSE 据此收流）
        Map<String, Object> completeData = new LinkedHashMap<>();
        completeData.put("totalSteps", data.getTotalSteps());
        completeData.put("totalDurationMs", data.getTotalDurationMs());
        completeData.put("finalContent", assistantMessage.getContent());
        TokenUsage turnUsage = data.getUsage() instanceof TokenUsage u ? u : null;
        if (turnUsage != null) {
            completeData.put("usage", turnUsage);
        }
        StreamEvent se = emitter.event(evt.getId(), ResponseType.COMPLETE, "", true, completeData);
        se.setUsage(turnUsage);
        emitter.append(se, "Append complete event to stream failed");
        return null;
    }

    // ── searchResultFromMap（见 StreamResponseBuilder 同款） ──

    private static SearchResult searchResultFromMap(Map<?, ?> refMap) {
        SearchResult sr = new SearchResult();
        sr.setId(getString(refMap, "id"));
        sr.setContent(getString(refMap, "content"));
        sr.setKnowledgeId(getString(refMap, "knowledgeId"));
        sr.setChunkIndex((int) getFloat64(refMap, "chunkIndex"));
        sr.setKnowledgeTitle(getString(refMap, "knowledgeTitle"));
        sr.setStartAt((int) getFloat64(refMap, "startAt"));
        sr.setEndAt((int) getFloat64(refMap, "endAt"));
        sr.setSeq((int) getFloat64(refMap, "seq"));
        sr.setScore(getFloat64(refMap, "score"));
        sr.setChunkType(getString(refMap, "chunkType"));
        sr.setParentChunkId(getString(refMap, "parentChunkId"));
        sr.setImageInfo(getString(refMap, "imageInfo"));
        sr.setKnowledgeFilename(getString(refMap, "knowledgeFilename"));
        sr.setKnowledgeSource(getString(refMap, "knowledgeSource"));
        sr.setKnowledgeDescription(getString(refMap, "knowledgeDescription"));
        sr.setKnowledgeBaseId(getString(refMap, "knowledgeBaseId"));
        if (refMap.get("metadata") instanceof Map<?, ?> meta) {
            Map<String, String> metadata = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : meta.entrySet()) {
                if (entry.getKey() instanceof String key && entry.getValue() instanceof String value) {
                    metadata.put(key, value);
                }
            }
            sr.setMetadata(metadata);
        }
        return sr;
    }

    private static String getString(Map<?, ?> m, String key) {
        Object val = m.get(key);
        return val instanceof String s ? s : "";
    }

    private static double getFloat64(Map<?, ?> m, String key) {
        Object val = m.get(key);
        return val instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 供 executeQA 的「无 answer 事件但有最终答案」判定读取。 */
    public String composedFinalAnswer() {
        synchronized (messageGate.monitor()) {
            return finalAnswer;
        }
    }

    /** 已订阅的事件类型集合（自检用；增删订阅时与 {@link #subscribedEventTypes()} 同步）。 */
    public static Set<String> subscribedEventTypes() {
        Set<String> types = new LinkedHashSet<>();
        types.add(EventType.EVENT_AGENT_THOUGHT);
        types.add(EventType.EVENT_AGENT_TOOL_CALL);
        types.add(EventType.EVENT_AGENT_TOOL_RESULT);
        types.add(EventType.EVENT_AGENT_REFERENCES);
        types.add(EventType.EVENT_MEMORY_RECALLED);
        types.add(EventType.EVENT_CONTEXT_COMPACTED);
        types.add(EventType.EVENT_USER_MESSAGE_INJECTED);
        types.add(EventType.EVENT_AGENT_FINAL_ANSWER);
        types.add(EventType.EVENT_AGENT_REFLECTION);
        types.add(EventType.EVENT_ERROR);
        types.add(EventType.EVENT_SESSION_TITLE);
        types.add(EventType.EVENT_AGENT_COMPLETE);
        types.add(EventType.EVENT_TOOL_APPROVAL_REQUIRED);
        types.add(EventType.EVENT_TOOL_APPROVAL_RESOLVED);
        types.add(EventType.EVENT_MCP_OAUTH_REQUIRED);
        types.add(EventType.EVENT_MCP_OAUTH_RESOLVED);
        return types;
    }
}
