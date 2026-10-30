package com.ragagent.contract.session;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * embed 渠道需要的**会话读写操作**（L1）：会话归属校验、访客会话创建/列表、渠道会话计数。
 *
 * <p><b>为什么是端口</b>：embedchannel 此前直连 session 的 {@code SessionService}（域服务 ✗）、
 * {@code SessionRepository}（持久层 ✗）与 {@code Session} 实体 / {@code EmbedVisitorSessionRow}
 * 投影（实体出域 ✗），还要自己拼 {@code SessionOwnerIds.EMBED_SESSION_PREFIX} 的 owner 串 ✗。
 * 本端口把读写收回会话域实现（{@code session/api/EmbedSessionOpsAdapter}），调用方只 import 本端口与两个视图。</p>
 *
 * <p><b>分段说明</b>：本端口只覆盖"服务侧"（会话元信息 / 访客会话）。QA 流、消息加载、建议与停止
 * 仍是 embedchannel 对 session controller 的 HTTP 委托 ✗（其中 QA 是 **servlet + SSE**、消息加载返回
 * **实体列表**）—— 属"会话实体 → 视图"的 K 级重设计范畴，单独立刀。</p>
 */
public interface EmbedSessionOps {

    /** 会话归属视图（embed 校验用）；不存在 / 查询失败 ⇒ {@code null}。 */
    OwnershipView ownership(long tenantId, String sessionId);

    /**
     * 建访客会话并落库：空标题 + {@code marker} 描述 + 访客 id，并补齐 owner。
     *
     * @param channelId 渠道 id（owner 串用）
     * @param marker    会话描述标记（＝ embed 侧 {@code embedSessionDescription(channelId)}；格式归 embed 侧所有）
     * @return 新会话 id
     */
    String createVisitorSession(long tenantId, String channelId, String marker, String visitorId);

    /** 补齐 owner（owner 串规则在实现内，不出域）；失败静默——调用方语义是"尽力而为"。 */
    void attachOwner(long tenantId, String channelId, String sessionId);

    /** embed 渠道来源的会话计数（{@code source=embed:channelId}）。 */
    long countSessions(long tenantId, String channelId);

    /** 访客历史会话行（按最后活动倒序，上限 {@code limit}）。 */
    List<VisitorSessionRow> visitorSessions(long tenantId, String marker, String visitorId, int limit);

    /**
     * 删除本访客在本渠道下的某个会话（软删）。
     *
     * @return true=确实删除；false=会话不属于该访客/渠道或已删除（调用方按 404 处理）
     */
    boolean deleteVisitorSession(long tenantId, String marker, String visitorId, String sessionId);

    /** 会话归属视图（三字段；与 {@code Session} 实体同名同义）。 */
    record OwnershipView(Long tenantId, String description, String userId) {
    }

    /** 访客历史会话的列表行（列表渲染三字段）。 */
    record VisitorSessionRow(String id, String title, OffsetDateTime updatedAt) {
    }
}
