package com.ragagent.session.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agenttools.wiki.IndexEntryView;
import com.ragagent.agenttools.wiki.IndexGroupView;
import com.ragagent.agenttools.wiki.IndexOverviewView;
import com.ragagent.agenttools.wiki.IssueView;
import com.ragagent.agenttools.wiki.PageView;
import com.ragagent.agenttools.wiki.RepairResult;
import com.ragagent.agenttools.wiki.WikiPages;
import com.ragagent.contract.wiki.WikiIndex;
import com.ragagent.contract.wiki.WikiIndexEntry;
import com.ragagent.contract.wiki.WikiLinkRepairResult;
import com.ragagent.contract.wiki.WikiPageIssueView;
import com.ragagent.contract.wiki.WikiPageNotFoundException;
import com.ragagent.contract.wiki.WikiPageOpsPort;
import com.ragagent.contract.wiki.WikiPageView;
import com.ragagent.platform.web.JsonMappers;
import com.ragagent.platform.web.ZeroTimeSerializer;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code AgentToolBackends} 的**wiki 工具簇**：WikiPages 工具端口的实现与
 * wiki 实体 ↔ 工具视图的转换（页面 / 版本 / 问题 / 索引概览 / 时间文本）。
 *
 * <p>为什么单独一类：这一簇只服务 wiki 类工具（读页、列版本、报问题、索引概览），
 * 与 KB 检索、web、datasource 各簇无交集；门面保留 {@code createWikiTool} 装配点与
 * {@code wikiPages()} 薄委托（契约测试直调面）。共享项：{@code wikiPageOps}
 * 门面装配也在用 → 经构造参数传入。</p>
 */
final class AgentToolWikiBackends {

    private final WikiPageOpsPort wikiPageOps;

    AgentToolWikiBackends(WikiPageOpsPort wikiPageOps) {
        this.wikiPageOps = wikiPageOps;
    }

    /**
     * 桥到 {@link WikiPageOpsPort} 的真实实现。
     *
     * <p>三处契约转换：</p>
     * <ol>
     *   <li>{@link WikiPageNotFoundException} 对接接缝约定「返回 null = 页不存在」：
     *       getPageBySlug 单独吞掉它、其余异常照抛（resolveUniqueWikiPage 会跳过
     *       null 但把异常当致命错误）。</li>
     *   <li>编辑来源 editSource 由端口实现经编辑上下文携带（service 落库时读取）。</li>
     *   <li>时间以 RFC3339 文本进视图；IssueView 以字符串承载，故在此格式化
     *       （读库得到 UTC location）。</li>
     * </ol>
     */
    WikiPages wikiPages() {
        return new WikiPages() {

            @Override
            public PageView getPageBySlug(String kbId, String slug) {
                try {
                    return toPageView(wikiPageOps.getPageBySlug(kbId, slug));
                } catch (WikiPageNotFoundException e) {
                    return null;
                }
            }

            @Override
            public PageView createPage(PageView page, String editSource) {
                WikiPageView entity = toEntity(page);
                // 工具经 PageView.copy() 会带来旧 ID，必须在接缝处清掉——
                // 新建路径语义是"由 service 生成新 UUID"，否则与旧页撞主键。
                entity.setId(null);
                return toPageView(wikiPageOps.createPage(entity, editSource));
            }

            @Override
            public void updatePage(PageView page, String editSource) {
                wikiPageOps.updatePage(toEntity(page), editSource);
            }

            @Override
            public void updateAutoLinkedContent(PageView page, String editSource) {
                wikiPageOps.updateAutoLinkedContent(toEntity(page), editSource);
            }

            @Override
            public void deletePage(String kbId, String slug, String editSource) {
                wikiPageOps.deletePage(kbId, slug, editSource);
            }

            @Override
            public RepairResult repairContentLinks(String kbId, String slug, String content) {
                try {
                    WikiLinkRepairResult r =
                            wikiPageOps.repairContentLinks(kbId, slug, content);
                    return r == null ? null : new RepairResult(r.content(), r.changed());
                } catch (RuntimeException e) {
                    // 修复失败永不阻塞写入
                    return null;
                }
            }

            @Override
            public void injectCrossLinks(String kbId, List<String> slugs) {
                wikiPageOps.injectCrossLinks(kbId, slugs);
            }

            @Override
            public void rebuildIndexPage(String kbId) {
                wikiPageOps.rebuildIndexPage(kbId);
            }

            @Override
            public List<IssueView> listIssues(String kbId, String slug, String status) {
                List<IssueView> out = new ArrayList<>();
                for (WikiPageIssueView issue : wikiPageOps.listIssues(kbId, slug, status)) {
                    if (issue != null) {
                        out.add(toIssueView(issue));
                    }
                }
                return out;
            }

            @Override
            public IssueView createIssue(IssueView issue) {
                WikiPageIssueView entity = new WikiPageIssueView();
                entity.setTenantId(issue.tenantId());
                entity.setKnowledgeBaseId(issue.knowledgeBaseId());
                entity.setSlug(issue.slug());
                entity.setIssueType(issue.issueType());
                entity.setDescription(issue.description());
                entity.setSuspectedKnowledgeIds(issue.suspectedKnowledgeIds());
                entity.setStatus(issue.status());
                entity.setReportedBy(issue.reportedBy());
                return toIssueView(wikiPageOps.createIssue(entity));
            }

            @Override
            public void updateIssueStatus(String issueId, String status) {
                wikiPageOps.updateIssueStatus(issueId, status);
            }

            @Override
            public List<PageView> searchPages(String kbId, String query, int limit) {
                List<PageView> out = new ArrayList<>();
                for (WikiPageView page : wikiPageOps.searchPages(kbId, query, limit)) {
                    if (page != null) {
                        out.add(toPageView(page));
                    }
                }
                return out;
            }

            @Override
            public IndexOverviewView getIndexView(String kbId, int topK) {
                try {
                    // 索引视图（无过滤、全量统计）
                    WikiIndex.Response resp =
                            wikiPageOps.getIndexView(kbId, topK);
                    return toIndexOverviewView(resp);
                } catch (RuntimeException e) {
                    // 获取失败或无 overview 时静默跳过
                    return null;
                }
            }
        };
    }
    /** WikiPage → 工具侧页视图。 */
    static PageView toPageView(WikiPageView page) {
        PageView view = PageView.of(page.getKnowledgeBaseId(),
                page.getSlug());
        view.setId(page.getId());
        view.setTenantId(page.getTenantId() == null ? 0L : page.getTenantId());
        view.setTitle(page.getTitle());
        view.setPageType(page.getPageType());
        view.setStatus(page.getStatus());
        view.setContent(page.getContent());
        view.setSummary(page.getSummary());
        view.setAliases(page.getAliases());
        view.setParentSlug(page.getParentSlug());
        view.setFolderId(page.getFolderId());
        view.setSortOrder(page.getSortOrder());
        view.setSourceRefs(page.getSourceRefs());
        view.setChunkRefs(page.getChunkRefs());
        view.setInLinks(page.getInLinks());
        view.setOutLinks(page.getOutLinks());
        var meta = JsonMappers.toJsonNode(page.getPageMetadata());
        view.setPageMetadata(meta == null || meta.isNull() ? "" : meta.toString());
        return view;
    }
    /** 工具侧页视图 → WikiPage 实体（service 就地补全 ID/Status/Version/OutLinks）。 */
    static WikiPageView toEntity(PageView view) {
        WikiPageView entity = new WikiPageView();
        entity.setId(view.id());
        entity.setTenantId(view.tenantId());
        entity.setKnowledgeBaseId(view.knowledgeBaseId());
        entity.setSlug(view.slug());
        entity.setTitle(view.title());
        entity.setPageType(view.pageType());
        entity.setStatus(view.status());
        entity.setContent(view.content());
        entity.setSummary(view.summary());
        entity.setAliases(view.aliases());
        entity.setParentSlug(view.parentSlug());
        entity.setFolderId(view.folderId());
        entity.setSortOrder(view.sortOrder());
        entity.setSourceRefs(view.sourceRefs());
        entity.setChunkRefs(view.chunkRefs());
        entity.setInLinks(view.inLinks());
        entity.setOutLinks(view.outLinks());
        JsonNode metadata = AgentToolBackends.readJson(view.pageMetadata());
        entity.setPageMetadata(metadata);
        return entity;
    }
    /** WikiPageIssue → 工具侧 issue 视图（时间以 RFC3339 文本透传）。 */
    static IssueView toIssueView(WikiPageIssueView issue) {
        IssueView view = new IssueView();
        view.setId(issue.getId());
        view.setTenantId(issue.getTenantId() == null ? 0L : issue.getTenantId());
        view.setKnowledgeBaseId(issue.getKnowledgeBaseId());
        view.setSlug(issue.getSlug());
        view.setIssueType(issue.getIssueType());
        view.setDescription(issue.getDescription());
        view.setSuspectedKnowledgeIds(issue.getSuspectedKnowledgeIds());
        view.setStatus(issue.getStatus());
        view.setReportedBy(issue.getReportedBy());
        view.setCreatedAt(timeText(issue.getCreatedAt()));
        view.setUpdatedAt(timeText(issue.getUpdatedAt()));
        view.setDeletedAtValid(issue.getDeletedAt() != null);
        view.setDeletedAt(timeText(issue.getDeletedAt()));
        return view;
    }
    /** wiki 索引 → 工具侧 overview 视图。 */
    static IndexOverviewView toIndexOverviewView(WikiIndex.Response resp) {
        if (resp == null) {
            return null;
        }
        List<IndexGroupView> groups = new ArrayList<>();
        for (WikiIndex.Group g : resp.getGroups()) {
            List<IndexEntryView> items = new ArrayList<>();
            for (WikiIndexEntry entry : g.getItems()) {
                items.add(new IndexEntryView(entry.getSlug(), entry.getTitle(),
                        entry.getSummary()));
            }
            groups.add(new IndexGroupView(g.getType(), g.getTotal(), items));
        }
        return new IndexOverviewView(resp.getIntro(), groups);
    }
    /**
     * 时间的线格式：RFC3339 按 UTC 渲染；小数秒**尾零连同空小数点一起
     * 去掉**（Java 的 ISO_OFFSET_DATE_TIME 会补齐到 3/6/9 位）；零值写
     * {@code 0001-01-01T00:00:00Z}。
     */
    static String timeText(java.time.OffsetDateTime value) {
        if (ZeroTimeSerializer.isZeroValue(value)) {
            return ZeroTimeSerializer.ZERO_TIME_LITERAL;
        }
        String s = value.atZoneSameInstant(java.time.ZoneOffset.UTC).toOffsetDateTime()
                .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        int dot = s.indexOf('.');
        if (dot < 0) {
            return s;
        }
        int fracEnd = dot + 1;
        while (fracEnd < s.length() && Character.isDigit(s.charAt(fracEnd))) {
            fracEnd++;
        }
        int keep = fracEnd;
        while (keep > dot + 1 && s.charAt(keep - 1) == '0') {
            keep--;
        }
        return s.substring(0, dot) + (keep > dot + 1 ? s.substring(dot, keep) : "")
                + s.substring(fracEnd);
    }
}
