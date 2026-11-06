package com.ragagent.knowledge.domain.access;

import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import java.util.List;

/**
 * 访问守卫所需的**最小知识读取面**。
 *
 * <p>访问守卫只经本端口读知识（4 个方法，契约形状预算内）；实现方为
 * {@code com.ragagent.knowledge.service.management.KnowledgeService}
 * （依赖方向 {@code service → domain.access}，Spring 按类型注入，端口只有这一个实现）。</p>
 */
public interface KnowledgeReadPort {

    /** 按 id 查 KB（软删不可见）。 */
    KnowledgeBase findKb(String kbId);

    /** 全局（不校验归属）取 Knowledge；找不到抛 {@code KnowledgeNotFoundException}。 */
    Knowledge getKnowledge(String id);

    /** 仅按 id 取 Knowledge（不做归属校验的原始读）。 */
    Knowledge getKnowledgeByIdOnly(String id);

    /** 批量取（保持入参顺序；租户内）。 */
    List<Knowledge> getKnowledgeBatch(long tenantId, List<String> ids);
}
