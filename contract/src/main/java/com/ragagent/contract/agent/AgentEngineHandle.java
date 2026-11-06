package com.ragagent.contract.agent;

import com.ragagent.contract.model.ChatMessage;
import java.util.List;
import java.util.function.Supplier;

/**
 * agent 引擎句柄（业务域对运行中引擎的用法面）。
 *
 * <p>{@code AgentEngine} 的门面端口，只收窄面：域侧真调用的 4 个方法
 * （`execute` 的返回值 {@code AgentState} 域侧并不使用 ⇒ 句柄声明为 void）。
 * 引擎构造、内置工具与技能装配在 {@link AgentEngineFactory}。</p>
 *
 * <p>失败语义：引擎是失败上报的**唯一所有者**——失败时 error 事件已在抛出前发出，
 * 调用方只需带栈留痕；异常类型不跨模块暴露（实现侧抛自身的运行时异常）。</p>
 */
public interface AgentEngineHandle {

    /** 本轮召回记忆的提示词（可为空串）。 */
    void setMemoryPrompt(String prompt);

    /** 转向信号（用户在流式中途插话）。 */
    void setSteerSink(SteerSink sink);

    /** 取消探针（返回取消原因；null = 未取消）。 */
    void setCancellationSource(Supplier<String> source);

    /** 执行本轮：会话/消息标识 + 查询文本 + LLM 上下文 + 图片 URL。 */
    void execute(String sessionId, String messageId, String query, List<ChatMessage> llmContext,
                 List<String> imageUrls);
}
