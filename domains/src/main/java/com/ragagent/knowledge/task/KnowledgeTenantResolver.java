package com.ragagent.knowledge.task;

import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.repository.KnowledgeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 解析「知识处理链要跑在哪个租户作用域」——worker 虚拟线程<b>没有</b> HTTP Filter 链填
 * {@code TenantContext}，而下游模型解析（{@code ModelService.getModelById} /
 * {@code ModelRuntime}）按<b>租户可见性</b>过滤
 * （{@code tenant_id = 当前租户 OR is_builtin}）：空租户会按 0 查，用户<b>自建模型</b>
 * （{@code is_builtin = false}）直接查不到 ⇒ 整条链以
 * {@code error code: 1003, error message: Model not found} 失败
 * （现场：docreader / chunking 都 done，**embedding 阶段**炸）。
 *
 * <p>取值顺序：① <b>提交线程捕获值</b>（入队时 {@code TenantContext.currentTenantId()}，
 * 与 traceparent 同一套"跨线程显式传值"约定）；② 空则回落到<b>知识行所属租户</b>
 * （后台入队 / 任务回放没有请求上下文）；③ 都拿不到返回 {@code null}
 * （调用方 {@code TenantScopes.enterCaptured(null)} ⇒ 不改动上下文，保持改动前行为）。</p>
 *
 * <p>独立成类：{@code KnowledgeProcessWorker} 已在 789 行的大文件棘轮上（>800 需登记例外），
 * 解释与取值逻辑放在这里，worker 只留一行调用。</p>
 */
final class KnowledgeTenantResolver {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeTenantResolver.class);

    private KnowledgeTenantResolver() {}

    /** @return 该任务应进入的租户；确无可依时 {@code null}（不猜测） */
    static Long resolve(KnowledgeRepository knowledgeRepository, String knowledgeId,
                        Long capturedTenantId) {
        if (capturedTenantId != null && capturedTenantId > 0) {
            return capturedTenantId;
        }
        try {
            Knowledge k = knowledgeRepository.findByIdAny(knowledgeId);
            return k == null ? null : k.getTenantId();
        } catch (RuntimeException e) {
            log.warn("tenant scope fallback failed kid={}", knowledgeId, e);
            return null;
        }
    }
}
