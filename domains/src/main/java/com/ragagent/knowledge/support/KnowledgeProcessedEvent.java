package com.ragagent.knowledge.support;

/**
 * 单个知识处理完成事件：worker finalize 后发布本事件；订阅方
 * {@code KnowledgeSummaryService#onKnowledgeProcessed} 同步在发布线程内执行，
 * 异常从发布调用方冒出（worker 侧 try/catch 兜底）。
 */
public record KnowledgeProcessedEvent(String knowledgeId) {
}
