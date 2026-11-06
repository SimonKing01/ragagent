package com.ragagent.session.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ragagent.contract.pagination.PageResult;
import com.ragagent.contract.session.SessionLastRequestState;
import com.ragagent.platform.persistence.DatabaseDialects;
import com.ragagent.platform.persistence.LikeEscapes;
import com.ragagent.platform.persistence.PageRequests;
import com.ragagent.session.domain.conversation.EmbedVisitorSessionRow;
import com.ragagent.session.domain.conversation.Session;
import com.ragagent.session.dto.SessionListItem;
import com.ragagent.session.dto.SessionListQuery;
import com.ragagent.session.mapper.SessionMapper;
import com.ragagent.session.support.SessionNotFoundException;
import com.ragagent.session.support.SessionOwnerIds;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/**
 * 会话仓储。
 *
 * <h2>落库隐式行为清单</h2>
 * <ol>
 *   <li><b>插入前</b>：无条件生成新 UUID。
 *       → {@link #create} 里无条件覆盖新 UUID（**不是**"为空才生成"）。</li>
 *   <li><b>软删除</b>：deleted_at 列。Java 不用 {@code @TableLogic}，每条查询显式加
 *       {@code deleted_at IS NULL}；
 *       删除走 UPDATE 置 {@code deleted_at}——**硬删会连带触发
 *       {@code im_channel_sessions_session_id_fkey} 的 ON DELETE CASCADE**。</li>
 *   <li><b>可见性范围</b>：{@code userId} 非空时加
 *       {@code (user_id = ? OR user_id IS NULL OR user_id = '')}——**空 owner 的历史行/API 行
 *       对所有人都可见**。这条件出现在 Get/GetByTenantID/GetPagedByTenantID/Update/Delete/
 *       BatchDelete/DeleteAllByTenantID/UpdateLastRequestState 共 8 处，Java 侧收敛成
 *       {@link #applyUserScope}。</li>
 *   <li><b>默认排序</b>：列表类查询显式 {@code updated_at DESC}。</li>
 *   <li><b>更新语义</b>：{@code title}/{@code description}
 *       无条件覆盖（改成空串也真的写空，绕开零值跳过规则）。用 {@code LambdaUpdateWrapper.set} 逐列写。</li>
 * </ol>
 */
@Component
public class SessionRepository {

    private static final String EMBED_PREFIX = "embed:";

    private final SessionMapper mapper;
    /** 方言探测（构造期问一次；同 wiki 的做法）。 */
    private final boolean postgres;

    public SessionRepository(SessionMapper mapper, DataSource dataSource) {
        this.mapper = mapper;
        this.postgres = DatabaseDialects.isPostgres(dataSource);
    }

    /**
     * ownerID 为空则不加范围条件，
     * 否则加 {@code (user_id = ? OR user_id IS NULL OR user_id = '')}。
     *
     * <p>这是所有带 owner 范围的查询的收敛点——
     * 改它之前先确认每条路径的语义确实相同（例如 {@link #setOwnerId} 刻意**不用**它）。</p>
     */
    private static LambdaQueryWrapper<Session> applyUserScope(LambdaQueryWrapper<Session> w, String userId) {
        if (userId != null && !userId.isEmpty()) {
            w.and(q -> q.eq(Session::getUserId, userId)
                    .or().isNull(Session::getUserId)
                    .or().eq(Session::getUserId, ""));
        }
        return w;
    }

    private static LambdaUpdateWrapper<Session> applyUserScope(LambdaUpdateWrapper<Session> w, String userId) {
        if (userId != null && !userId.isEmpty()) {
            w.and(q -> q.eq(Session::getUserId, userId)
                    .or().isNull(Session::getUserId)
                    .or().eq(Session::getUserId, ""));
        }
        return w;
    }

    /** LIKE 关键词转义：反斜杠、百分号、下划线。 */
    // ── 写 ──────────────────────────────────────────────────────────────────

    /** 显式写时间戳 + 无条件生成新 UUID。 */
    public Session create(Session session) {
        OffsetDateTime now = OffsetDateTime.now();
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        session.setId(UUID.randomUUID().toString());
        mapper.insert(session);
        return session;
    }

    /**
     * **只写 title / description / updated_at**。
     *
     * <p>逐列显式 SET——把 title 改成空串也会真的写空，而不是被零值跳过规则省略。</p>
     *
     * @return 受影响行数（0 = 没命中，调用方据此区分"不存在/不可见"与真的出错）
     */
    public long update(Session session, String userId) {
        session.setUpdatedAt(OffsetDateTime.now());
        LambdaUpdateWrapper<Session> w = new LambdaUpdateWrapper<Session>()
                .eq(Session::getTenantId, session.getTenantId())
                .eq(Session::getId, session.getId())
                .isNull(Session::getDeletedAt)
                .set(Session::getTitle, session.getTitle())
                .set(Session::getDescription, session.getDescription())
                .set(Session::getUpdatedAt, session.getUpdatedAt());
        applyUserScope(w, userId);
        return mapper.update(null, w);
    }

    /** **不做 user 范围**，只按租户 + id。 */
    public long setOwnerId(long tenantId, String id, String ownerId) {
        LambdaUpdateWrapper<Session> w = new LambdaUpdateWrapper<Session>()
                .eq(Session::getTenantId, tenantId)
                .eq(Session::getId, id)
                .isNull(Session::getDeletedAt)
                .set(Session::getUserId, ownerId)
                .set(Session::getUpdatedAt, OffsetDateTime.now());
        return mapper.update(null, w);
    }

    /**
     * 置顶/取消置顶。
     *
     * <p>取消置顶时 {@code pinned_at} 显式写 **NULL**——
     * 这不是"省略该列"，是清空它。</p>
     */
    public long setPinned(long tenantId, String userId, String id, boolean pinned) {
        OffsetDateTime now = OffsetDateTime.now();
        LambdaUpdateWrapper<Session> w = new LambdaUpdateWrapper<Session>()
                .eq(Session::getTenantId, tenantId)
                .eq(Session::getId, id)
                .isNull(Session::getDeletedAt)
                .set(Session::isPinned, pinned)
                .set(Session::getPinnedAt, pinned ? now : null)
                .set(Session::getUpdatedAt, now);
        applyUserScope(w, userId);
        return mapper.update(null, w);
    }

    /**
     * 只写 agent_config + updated_at，
     * 不扰动 title/description。
     */
    public long updateLastRequestState(long tenantId, String userId, String sessionId,
            SessionLastRequestState state) {
        // 走 mapper 的显式 UPDATE：jsonb 列必须挂类型处理器，UpdateWrapper.set 不生效。
        boolean userScoped = userId != null && !userId.isEmpty();
        return mapper.updateLastRequestState(tenantId, sessionId, state, OffsetDateTime.now(),
                userScoped, userId);
    }

    // ── 读 ──────────────────────────────────────────────────────────────────

    /** 租户 + id + user 范围；零行抛 404。 */
    public Session get(long tenantId, String userId, String id) {
        LambdaQueryWrapper<Session> w = new LambdaQueryWrapper<Session>()
                .eq(Session::getTenantId, tenantId)
                .eq(Session::getId, id)
                .isNull(Session::getDeletedAt);
        applyUserScope(w, userId);
        Session session = mapper.selectOne(w);
        if (session == null) {
            throw new SessionNotFoundException();
        }
        return session;
    }

    /**
     * 批量取可见会话（软删过滤 + user 范围）；缺失/不可见的 id 不在返回集中。
     * 批量删除用它替代逐条 {@link #get}。
     */
    public List<Session> getByIds(long tenantId, String userId, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<Session> w = new LambdaQueryWrapper<Session>()
                .eq(Session::getTenantId, tenantId)
                .in(Session::getId, ids)
                .isNull(Session::getDeletedAt);
        applyUserScope(w, userId);
        return mapper.selectList(w);
    }

    /** **不带 user 范围**（管理员越权读的第二跳）。 */
    public Session getById(long tenantId, String id) {
        LambdaQueryWrapper<Session> w = new LambdaQueryWrapper<Session>()
                .eq(Session::getTenantId, tenantId)
                .eq(Session::getId, id)
                .isNull(Session::getDeletedAt);
        Session session = mapper.selectOne(w);
        if (session == null) {
            throw new SessionNotFoundException();
        }
        return session;
    }

    /**
     * **查不到不是错误**——零行时归一为 ""。
     */
    public String getImPlatform(long tenantId, String sessionId) {
        String platform = mapper.selectImPlatform(tenantId, sessionId);
        return platform == null ? "" : platform;
    }

    public List<Session> getByTenantId(long tenantId, String userId) {
        LambdaQueryWrapper<Session> w = new LambdaQueryWrapper<Session>()
                .eq(Session::getTenantId, tenantId)
                .isNull(Session::getDeletedAt)
                .orderByDesc(Session::getUpdatedAt);
        applyUserScope(w, userId);
        return mapper.selectList(w);
    }

    /**
     * 分页读本租户会话。
     *
     * @param page 从 1 起；OFFSET 为 {@code (page-1)*pageSize}
     */
    public PagedSessions getPagedByTenantId(long tenantId, String userId, int page, int pageSize) {
        // 归一化兜底：
        // page < 1 → 1；pageSize < 1 → 20；pageSize > 1000 → 1000。别改成"0 就是不限"。
        int p = page < 1 ? 1 : page;
        int size = pageSize < 1 ? 20 : Math.min(pageSize, 1000);

        LambdaQueryWrapper<Session> countQ = new LambdaQueryWrapper<Session>()
                .eq(Session::getTenantId, tenantId)
                .isNull(Session::getDeletedAt);
        applyUserScope(countQ, userId);
        long total = mapper.selectCount(countQ);

        LambdaQueryWrapper<Session> listQ = new LambdaQueryWrapper<Session>()
                .eq(Session::getTenantId, tenantId)
                .isNull(Session::getDeletedAt)
                .orderByDesc(Session::getUpdatedAt);
        applyUserScope(listQ, userId);
        // LIMIT/OFFSET 语法由分页拦截器按方言生成（替代手写 FETCH 分支）
        return new PagedSessions(mapper.selectList(PageRequests.range(p, size), listQ), total);
    }

    // ── 删 ──────────────────────────────────────────────────────────────────

    /**
     * 软删单条。
     *
     * <p>这里用 UPDATE deleted_at 而不是 DELETE——硬删会连带触发
     * {@code im_channel_sessions_session_id_fkey} 的 ON DELETE CASCADE。</p>
     */
    public long delete(long tenantId, String userId, String id) {
        return softDelete(tenantId, List.of(id), userId);
    }

    /** 空 ids 直接返回 0。 */
    public long batchDelete(long tenantId, String userId, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        return softDelete(tenantId, ids, userId);
    }

    /** 没有 id 条件（全量软删）。 */
    public long deleteAllByTenantId(long tenantId, String userId) {
        return softDelete(tenantId, null, userId);
    }

    /** 三条删除路径的公共实现；{@code userId} 为空时不加 owner 范围（对照 applySessionUserScope）。 */
    private long softDelete(long tenantId, List<String> ids, String userId) {
        OffsetDateTime now = OffsetDateTime.now();
        LambdaUpdateWrapper<Session> w = new LambdaUpdateWrapper<Session>()
                .eq(Session::getTenantId, tenantId)
                .isNull(Session::getDeletedAt)
                .set(Session::getDeletedAt, now);
        if (ids != null) {
            w.in(Session::getId, ids);
        }
        applyUserScope(w, userId);
        return mapper.update(null, w);
    }

    // ── 列表（QueryPaged） ──────────────────────────────────────────────────

    /**
     * 服务层负责在调用前把 tenantId/userId 填好
     * （含"渠道来源筛选需要 Admin+，且要丢掉按人裁剪"那段判定）。
     *
     * <p>方言差异只在两处，都由 {@code postgres} 开关切换：{@code ILIKE} 与 {@code NULLS LAST}
     * （H2 两个都不支持）。方言在进程生命周期内不变，
     * 故与 wiki 一样在构造期探测一次。</p>
     */
    public PageResult<SessionListItem> queryPaged(SessionListQuery q) {
        String rawSource = q.source() == null ? "" : q.source().trim();
        String src = rawSource.toLowerCase(Locale.ROOT);

        // 先取原始大小写的 channelID（不用 lower 后的串），
        // 再拼成 embed_channel:<channelID> 做**等值**匹配（不是 LIKE）
        String channelDesc = null;
        if (src.startsWith(EMBED_PREFIX)) {
            String channelId = rawSource.substring(EMBED_PREFIX.length()).trim();
            if (!channelId.isEmpty()) {
                channelDesc = Session.EMBED_SESSION_MARKER_PREFIX + channelId;
            }
        }

        String kw = q.keyword() == null ? "" : q.keyword().trim();
        String keywordLike = kw.isEmpty() ? null : "%" + LikeEscapes.escape(kw) + "%";

        // 四个 LIKE 用的前缀串（拼好传参）
        String skillMarker = Session.SKILL_MAINTENANCE_SESSION_MARKER + "%";
        String embedLike = Session.EMBED_SESSION_MARKER_PREFIX + "%";
        String apiTenantLike = SessionOwnerIds.API_TENANT_KEY_PREFIX + "%";
        String apiExternalLike = SessionOwnerIds.API_EXTERNAL_USER_PREFIX + "%";

        long total = mapper.countPaged(q, postgres, keywordLike, src, channelDesc,
                embedLike, apiTenantLike, apiExternalLike, skillMarker);

        // 归一化兜底（page/size 同规则）
        int page = q.page() < 1 ? 1 : q.page();
        int size = q.pageSize() < 1 ? 20 : Math.min(q.pageSize(), 1000);

        List<SessionListItem> items = mapper.queryPaged(q, postgres, keywordLike, src, channelDesc,
                embedLike, apiTenantLike, apiExternalLike, skillMarker, size, (page - 1) * size);

        return PageResult.of(items, total, page, size);
    }

    /**
     * embed 访客的历史会话（只列有消息的；{@code updated_at} 倒序，上限 {@code limit}）。
     *
     * <p>{@code description} 由调用方（embed 域）拼好传入——session 包不反向依赖
     * embedchannel 包。</p>
     */
    public List<EmbedVisitorSessionRow> listEmbedVisitorSessions(long tenantId, String description,
                                                                 String visitorId, int limit) {
        return mapper.listEmbedVisitorSessions(tenantId, description, visitorId, limit);
    }

    /** {@code GetPagedByTenantID} 的返回：一页数据 + 总数。 */
    public record PagedSessions(List<Session> sessions, long total) {
    }

    /** {@code queryPaged} 的返回：items + total + page + pageSize。 */}
