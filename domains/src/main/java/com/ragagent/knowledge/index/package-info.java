/**
 * 索引面：知识条目与向量/检索引擎的绑定、写入与回收适配。
 *
 * <p>{@code KnowledgeVectorWrites} 提供“按 KB 解析已绑定引擎”的只读门面，
 * 由 {@code task/KnowledgeProcessWorker} 使用；{@code KnowledgeIndexCleaner}
 * 负责删除路径的向量/图谱回收（失败只告警，不回头拖垮已提交的软删）。
 * 引擎能力经 {@code contract.retrieval} 端口。</p>
 */
package com.ragagent.knowledge.index;
