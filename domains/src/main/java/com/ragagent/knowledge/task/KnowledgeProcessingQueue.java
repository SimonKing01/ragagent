package com.ragagent.knowledge.task;

import com.ragagent.platform.task.TaskQueue;

/**
 * 知识处理入队端口。
 *
 * <p>生产方（门面、移动/解析/文件/批处理子服务、datasource 桥）只依赖本接口，
 * 实现方是 {@code KnowledgeProcessWorker}；「提交任务」的类不在 bean 图上
 * 反向依赖 worker 实现。</p>
 */
public interface KnowledgeProcessingQueue extends TaskQueue {

    /** 入队一个知识待处理任务（幂等语义由实现方保证）。 */
    void enqueue(String knowledgeId);
}
