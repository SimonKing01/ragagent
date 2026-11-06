package com.ragagent.contract.audit;

import java.util.Map;

/**
 * 知识库活动流的 wiki 变更投影接缝（**端口**：定义在中性包 `common.audit`，
 * 实现由审计模块提供；本接口从 `wiki.domain` 搬来，使 `audit` 不再依赖 `wiki`）。
 *
 * <p>HTTP 层把人工页面变更写成一条 {@code wiki.content_changed} 审计事件。
 * 净效果是一条 {@code AuditLog}：Action={@code wiki.content_changed}、ScopeType={@code knowledge_base}、
 * TargetType={@code wiki}/targetId={@code kbId}、Outcome={@code success}、
 * Details={@code {"count":N,"actions":{...}}}（只在 count &gt; 0 时写）。</p>
 *
 * <p><b>为什么是接缝而不是直接调用</b>：定义端口是为了固定埋点位置、
 * 且不把 wiki 模块反向耦合到审计实现上。没有实现 bean 时
 * {@code ObjectProvider.getIfAvailable()} 返回 null，行为退化为一条 debug 日志——
 * 等价于审计服务缺位的情形（埋点本身尽力而为、绝不影响编辑本身）。</p>
 *
 * <p><b>实现已就位</b>：{@code com.ragagent.audit.service.WikiActivityAuditRecorder}
 * （审计模块提供）实现了本接口，@Component 自动装配，因此
 * {@code WikiPageOps} 的 4 处人工埋点（经 {@code WikiActivityRecorder}）与 ingest 侧
 * {@code WikiIngestRunSupport} 的批量摘要现在都<b>真正落库</b>为 {@code wiki.content_changed} 审计行。</p>
 */
public interface WikiActivityAudit {

    /**
     * 写入一条 {@code wiki.content_changed} 审计事件
     * （Details 为 {@code {"count": sum(actions), "actions": actions}}）。
     *
     * @param tenantId        页面所属工作空间
     * @param knowledgeBaseId 页面所属知识库（同时是审计的 scopeId 与 targetId）
     * @param actions         动作名 → 次数（调用方恒传单键 {@code {action: 1}}）
     */
    void wikiContentChanged(long tenantId, String knowledgeBaseId, Map<String, Integer> actions);
}
