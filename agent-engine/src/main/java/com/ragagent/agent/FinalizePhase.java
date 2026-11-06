package com.ragagent.agent;

import com.fasterxml.jackson.databind.node.NullNode;
import com.ragagent.agent.domain.AgentState;
import com.ragagent.contract.agent.AgentStep;
import com.ragagent.contract.agent.ThinkBlocks;
import com.ragagent.contract.event.Event;
import com.ragagent.contract.event.payload.AgentCompleteData;
import com.ragagent.contract.event.payload.AgentFinalAnswerData;
import com.ragagent.contract.model.ChatMessage;
import com.ragagent.contract.model.ChatOptions;
import com.ragagent.contract.model.ResponseType;
import com.ragagent.platform.event.EventIds;
import com.ragagent.platform.event.EventType;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ReAct「Finalize」段的协作者：最终答案合成与完成事件——从既有工具结果兜底合成答案、\n * 迭代上限兜底、complete 事件发射、答案流收束。
 *
 * <p>持有 {@link AgentEngine} 回引以访问引擎字段；本类不得独立实例化。</p>
 */
final class FinalizePhase {

    private static final Logger log = LoggerFactory.getLogger(FinalizePhase.class);


    private final AgentEngine engine;

    FinalizePhase(AgentEngine engine) {
        this.engine = engine;
    }

    /** 自然停答案真正收束时发 Done:true；循环结束注入跳过它（客户端不掉 isReplying）。 */
    void closeAnswerStream(String sessionId, String answerId) {
        if (engine.eventBus == null || answerId == null || answerId.isEmpty()) {
            return;
        }
        engine.eventBus.emit(new Event(answerId, EventType.EVENT_AGENT_FINAL_ANSWER, sessionId,
                new AgentFinalAnswerData("", true, false), null, ""));
    }


    /**
     * 最终答案合成流；失败抛 AgentEngineException。
     */
    void streamFinalAnswerToEventBus(String query, AgentState state, String sessionId,
            List<ChatMessage> conversation) {
        int totalToolCalls = AgentEngine.countTotalToolCalls(state.getRoundSteps());
        log.info("[Agent][FinalAnswer] Synthesizing from {} steps, {} tool calls",
                state.getRoundSteps().size(), totalToolCalls);
        log.info("[PIPELINE] stage=Agent action=final_answer_start session_id=\"{}\" query=\"{}\" steps={} tool_results={}",
                sessionId, query, state.getRoundSteps().size(), totalToolCalls);

        // 复用活 transcript（含历史/图片/压缩/steer 消息）。工具输出保持 role 与 call ID，
        // 错误恢复/轮次上限合成时绝不把它提成 user 指令。
        List<ChatMessage> messages = new ArrayList<>(conversation);
        messages.add(new ChatMessage("user",
                "Tool execution has ended for this run. Respond to the current task, including "
                        + "the latest user corrections and source restrictions in the conversation. Base claims on "
                        + "the evidence actually obtained; distinguish completed work from remaining work and explain "
                        + "any missing evidence. Use the user's requested language and format. Do not claim that an "
                        + "unperformed action succeeded."));

        // 整个最终答案流共用一个 ID
        String answerId = EventIds.generateEventId("answer");
        log.debug("[Agent][FinalAnswer] AnswerID: {}", answerId);
        AtomicBoolean answerDoneEmitted = new AtomicBoolean(false);

        int budget = engine.clampCompletionBudgetToContext(engine.tokenEstimator.estimateMessages(messages));
        ChatOptions opts = new ChatOptions();
        opts.setTemperature(engine.config.getTemperature());
        opts.setMaxCompletionTokens(budget);
        opts.setPromptCacheKey(sessionId);
        opts.setToolChoice("none");
        // 思考策略与主循环同源：取 agent 配置的三态值（null = 交由模型默认决定）。
        // 不显式传值时，模型的 thinking_control 策略（如 enable_thinking 且 alwaysSend=false）
        // 会让该路径退化为上游默认（思考开）——本部署实测会因此返回 2751 字 reasoning，
        // 显式传值正是为挡住它。
        opts.setThinking(engine.config.getThinking());

        ThinkPhase.StreamLLMResult llmResult = engine.think.streamLLMToEventBus(messages, opts, (chunk, fullContent) -> {
            // 防御过滤：只发答案内容，跳过思考分片。
            if (chunk.getResponseType() == ResponseType.THINKING) {
                return;
            }
            if (chunk.getContent() != null && !chunk.getContent().isEmpty()) {
                log.debug("[Agent][FinalAnswer] Emitting answer chunk: {} chars", chunk.getContent().length());
                engine.eventBus.emit(new Event(answerId, EventType.EVENT_AGENT_FINAL_ANSWER, sessionId,
                        new AgentFinalAnswerData(chunk.getContent(), chunk.isDone(), false), null, ""));
                if (chunk.isDone()) {
                    answerDoneEmitted.set(true);
                }
            }
        });

        if (!answerDoneEmitted.get()) {
            engine.eventBus.emit(new Event(answerId, EventType.EVENT_AGENT_FINAL_ANSWER, sessionId,
                    new AgentFinalAnswerData("", true, false), null, ""));
        }

        // 合成调用常常是本轮最大的一笔——usage 并进轮累计（与每个 ReAct 轮一致）。
        if (llmResult.usage != null) {
            state.getTurnUsage().accumulate(llmResult.usage);
        }

        // 安全网：剥掉漏进来的残余 <think> 块。
        String fullAnswer = ThinkBlocks.stripThinkBlocks(llmResult.content);
        log.info("[Agent][FinalAnswer] Final answer generated: {} characters", fullAnswer.length());
        log.info("[PIPELINE] stage=Agent action=final_answer_done session_id=\"{}\" answer_len={}",
                sessionId, fullAnswer.length());
        state.setFinalAnswer(fullAnswer);
    }

    /** 轮次耗尽且无自然停时合成最终答案，置 IsComplete。 */
    void handleMaxIterations(String query, AgentState state, String sessionId,
            List<ChatMessage> messages) {
        log.info("Reached max iterations, generating final answer");
        log.warn("[PIPELINE] stage=Agent action=max_iterations_reached iterations={} max={}",
                state.getCurrentRound(), engine.config.getMaxIterations());

        try {
            streamFinalAnswerToEventBus(query, state, sessionId, messages);
        } catch (RuntimeException e) {
            log.error("Failed to synthesize final answer", e);
            log.error("[PIPELINE] stage=Agent action=final_answer_failed error=\"\"", e);
            state.setFinalAnswer("Sorry, I was unable to generate a complete answer.");
        }
        state.setComplete(true);
    }

    /** 完成事件（成功收尾；由 executeLoop 的 finally 保证恰好一次）。 */
    void emitCompletionEvent(AgentState state, String sessionId, String messageId,
            Instant startTime) {
        emitCompletionEvent(state, sessionId, messageId, startTime, null);
    }

    /**
     * 完成事件（失败收尾由执行入口在**错误事件之后**补发；两者共用终态门 ⇒ 恰好一次）。
     *
     * @param failureError 非 null ⇒ 失败收束：载荷显式携带
     *                     {@code extra={"outcome":"failed","error":<msg>}}，客户端可区分
     *                     "正常收尾"与"失败收尾"；成功传 null（缺省即成功，不改既有线格式）
     */
    void emitCompletionEvent(AgentState state, String sessionId, String messageId,
            Instant startTime, String failureError) {
        List<AgentStep> steps = state.getRoundSteps();
        if (state.getPendingSteerMessages() != null && !state.getPendingSteerMessages().isEmpty()) {
            // 停止/模型失败可能发生在投递后、下一响应前；保住边界，不虚构答案。
            steps = new ArrayList<>(state.getRoundSteps());
            AgentStep extra = new AgentStep();
            extra.setIteration(state.getCurrentRound());
            extra.setUserMessagesBefore(new ArrayList<>(state.getPendingSteerMessages()));
            steps.add(extra);
        }
        List<Object> knowledgeRefsInterface = new ArrayList<>(
                state.getKnowledgeRefs() == null ? List.of() : state.getKnowledgeRefs());

        // complete 事件的 SessionID 恒为 ""（既有消费者的线格式约定）。
        engine.eventBus.emit(new Event(EventIds.generateEventId("complete"), EventType.EVENT_AGENT_COMPLETE,
                sessionId, new AgentCompleteData("", state.getRoundSteps().size(),
                        state.getFinalAnswer(), knowledgeRefsInterface, alwaysPresentList(steps),
                        turnUsageOf(state),
                        Duration.between(startTime, Instant.now()).toMillis(), messageId, "",
                        failureExtra(failureError)),
                null, ""));

        log.info("Agent execution completed in {} rounds", state.getCurrentRound());
    }

    /** 失败 outcome 载荷：键序固定（outcome → error），保证录制/快照可比。 */
    private static Map<String, Object> failureExtra(String failureError) {
        if (failureError == null) {
            return null;
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("outcome", "failed");
        extra.put("error", failureError);
        return extra;
    }

    /**
     * 轮累计用量；无轮上报用量时返回 {@link NullNode}——
     * {@code "usage":null} 恒在，键不省略。
     */
    private static Object turnUsageOf(AgentState state) {
        if (state == null || state.getTurnUsage().getTotalTokens() == 0) {
            return NullNode.instance;
        }
        return state.getTurnUsage();
    }

    /**
     * {@code agent_steps} 字段的<b>恒输出</b>语义对应物：空列表也要输出
     * {@code "agent_steps":[]}。event 包不可改：空列表用 {@link RawValue}
     * 原文过 NON_EMPTY（非空列表走 List 序列化器，形状一致）。
     */
    private static Object alwaysPresentList(List<?> value) {
        return value.isEmpty()
                ? new com.fasterxml.jackson.databind.util.RawValue("[]")
                : value;
    }
}
