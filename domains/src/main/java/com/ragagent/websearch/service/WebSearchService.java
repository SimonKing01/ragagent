package com.ragagent.websearch.service;


import com.ragagent.contract.retrieval.SearchResult;
import com.ragagent.contract.retrieval.WebResultConverter;
import com.ragagent.contract.websearch.WebSearchFilters;
import com.ragagent.contract.websearch.WebSearchResult;
import com.ragagent.websearch.config.WebSearchProviderParams;
import com.ragagent.websearch.domain.WebSearchProviderEntity;
import com.ragagent.websearch.provider.WebSearchProvider;
import com.ragagent.websearch.provider.WebSearchProviderRegistry;
import com.ragagent.websearch.repository.WebSearchProviderRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 网络搜索执行服务（搜索执行链：search / resolveProvider / 黑名单过滤 /
 * 结果转换 + RAG 压缩的纯辅助族）。
 *
 * <h2>结构要点</h2>
 * <ul>
 *   <li>timeout：缺省 10s，构造参数注入。</li>
 *   <li>{@code resolveProvider}：providerID 路径从仓储取实体（合并 call-time 代理
 *       覆盖）→ 注册表创建；缺省回落 deprecated 的 config.Provider（warn 日志）；
 *       两者都空 → {@code no web search provider configured}。</li>
 *   <li>过滤分支：filters 的 country/freshness 非空时先校验再要求 provider 支持
 *       （不支持 = {@code provider %s does not support country/freshness filters;
 *       omit them or select Brave}）。</li>
 *   <li>{@code compressWithRag}：kbSvc/knowSvc 依赖用端口接口注入
 *       （hybrid 检索与段落摄入由检索引擎接线）。</li>
 * </ul>
 */
@Service
public class WebSearchService {

    private final WebSearchProviderRegistry registry;
    private final WebSearchProviderRepository providerRepo;

    @org.springframework.beans.factory.annotation.Autowired
    public WebSearchService(WebSearchProviderRegistry registry,
                            WebSearchProviderRepository providerRepo) {
        this(registry, providerRepo, 10);
    }

    public WebSearchService(WebSearchProviderRegistry registry,
                            WebSearchProviderRepository providerRepo, int timeoutSeconds) {
        this.registry = registry;
        this.providerRepo = providerRepo;
    }

    /**
     * 搜索入口：config 必填 → 解析 provider → 过滤分支 → 黑名单过滤。
     * query 级超时由调用方（agent 引擎/管线）以 deadline 形式施加
     * （本服务内部不设 query 级超时）。
     */
    public List<WebSearchResult> search(long tenantId, String providerId,
                                        WebSearchConfig config, String query) {
        if (config == null) {
            throw new IllegalStateException("web search config is required");
        }
        WebSearchProvider searchProvider =
                resolveProvider(tenantId, providerId, config);

        List<WebSearchResult> results;
        if (!config.filters.country().isEmpty() || !config.filters.freshness().isEmpty()) {
            config.filters.validate();
            try {
                results = searchProvider.searchWithFilters(query, config.maxResults,
                        config.includeDate, config.filters);
            } catch (UnsupportedOperationException e) {
                throw new IllegalStateException("provider " + searchProvider.name()
                        + " does not support country/freshness filters; "
                        + "omit them or select Brave");
            }
        } else {
            results = searchProvider.search(query, config.maxResults, config.includeDate);
        }
        return filterBlacklist(results, config.blacklist);
    }

    /** resolveProvider：优先显式 providerId，其次租户默认 provider，最后兼容内联 provider。 */
    private WebSearchProvider resolveProvider(
            long tenantId, String providerId, WebSearchConfig cfg) {
        String effectiveId = providerId == null ? "" : providerId;
        // 智能体链路常只带开关不带 providerId（内置智能体、会话快照都没有这一项），
        // 缺省时回落到租户的默认搜索源；兼容路径（cfg.provider 内联）保持原优先级。
        boolean inlineProvider = cfg != null && cfg.provider != null && !cfg.provider.isEmpty();
        if (effectiveId.isEmpty() && !inlineProvider) {
            effectiveId = defaultProviderId(tenantId);
        }
        if (!effectiveId.isEmpty()) {
            WebSearchProviderEntity entity = providerRepo.getById(tenantId, effectiveId);
            if (entity == null) {
                throw new IllegalStateException("web search provider not found: " + effectiveId);
            }
            WebSearchProviderParams params = mergeProxyFromWebSearchConfig(
                    entity.getParameters(), cfg);
            try {
                return registry.createProvider(entity.getProvider(), params);
            } catch (RuntimeException e) {
                throw new IllegalStateException("failed to create provider " + entity.getName()
                        + " (" + entity.getProvider() + "): " + e.getMessage());
            }
        }
        // 直接调用方（L2 WebSearchRequest）可带内联 provider/apiKey；租户 KV 路径不再产生它们。
        if (cfg.provider != null && !cfg.provider.isEmpty()) {
            WebSearchProviderParams base = new WebSearchProviderParams();
            base.setApiKey(cfg.apiKey);
            WebSearchProviderParams params = mergeProxyFromWebSearchConfig(base, cfg);
            try {
                return registry.createProvider(cfg.provider, params);
            } catch (RuntimeException e) {
                throw new IllegalStateException("web search provider " + cfg.provider
                        + " is not available: " + e.getMessage());
            }
        }
        throw new IllegalStateException("no web search provider configured");
    }

    /** 租户默认搜索源：取 isDefault 的第一个；查询失败或没配置时返回空串。 */
    private String defaultProviderId(long tenantId) {
        try {
            for (WebSearchProviderEntity candidate : providerRepo.list(tenantId)) {
                if (candidate != null && candidate.isDefault()
                        && candidate.getId() != null && !candidate.getId().isEmpty()) {
                    return candidate.getId();
                }
            }
        } catch (RuntimeException ignored) {
            // 查询失败按「没有默认源」处理，保持原有报错路径
        }
        return "";
    }

    /** mergeProxyFromWebSearchConfig：cfg 的 proxyUrl 非空时调用期覆盖。 */
    static WebSearchProviderParams mergeProxyFromWebSearchConfig(WebSearchProviderParams base,
                                                                 WebSearchConfig cfg) {
        WebSearchProviderParams p = base == null ? new WebSearchProviderParams() : base;
        if (cfg != null) {
            String pu = cfg.proxyUrl == null ? "" : cfg.proxyUrl.trim();
            if (!pu.isEmpty()) {
                p.setProxyUrl(pu);
            }
        }
        return p;
    }

    /** 黑名单过滤：URL 命中任一规则即弃。 */
    public static List<WebSearchResult> filterBlacklist(List<WebSearchResult> results,
                                                        List<String> blacklist) {
        if (blacklist == null || blacklist.isEmpty()) {
            return results;
        }
        List<WebSearchResult> filtered = new ArrayList<>(results.size());
        for (WebSearchResult result : results) {
            boolean shouldFilter = false;
            for (String rule : blacklist) {
                if (matchesBlacklistRule(result.getUrl(), rule)) {
                    shouldFilter = true;
                    break;
                }
            }
            if (!shouldFilter) {
                filtered.add(result);
            }
        }
        return filtered;
    }

    /**
     * 黑名单规则匹配：`/.../` 是正则（非法正则告警后按不匹配）；否则 `*` → `.*` 全串锚定。
     */
    static boolean matchesBlacklistRule(String url, String rule) {
        String u = url == null ? "" : url;
        if (rule.startsWith("/") && rule.endsWith("/") && rule.length() >= 2) {
            String pattern = rule.substring(1, rule.length() - 1);
            try {
                // `/.../` 形态是**非锚定**子串匹配 → Matcher.find()
                return java.util.regex.Pattern.compile(toJavaRegex(pattern))
                        .matcher(u).find();
            } catch (RuntimeException e) {
                return false;
            }
        }
        String pattern = "^" + rule.replace("*", ".*") + "$";
        try {
            return u.matches(pattern);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * `/.../` 内的正则原样透传：`\` 序列原样保留（仅支持各引擎语义兼容的
     * 公共子集）；非法时 matches 抛错 → false。
     */
    private static String toJavaRegex(String pattern) {
        return pattern;
    }

    /** 结果转换（seq = 下标）。 */
    public static List<SearchResult> convertWebSearchResults(List<WebSearchResult> webResults) {
        return WebResultConverter.convert(webResults, idx -> idx);
    }

    // ── compressWithRag 的纯辅助族 ──────────

    /**
     * 按 source URL 公平轮选至 limit 条。
     * refs 的 URL 从 content 首行标记提取。
     */
    public static List<SearchResult> selectReferencesRoundRobin(List<WebSearchResult> raw,
                                                                List<SearchResult> refs,
                                                                int limit) {
        if (limit <= 0 || refs == null || refs.isEmpty()) {
            return List.of();
        }
        Map<String, List<SearchResult>> urlToRefs = new LinkedHashMap<>();
        for (SearchResult r : refs) {
            String url = extractSourceUrlFromContent(r.getContent());
            if (url.isEmpty()) {
                continue;
            }
            urlToRefs.computeIfAbsent(url, k -> new ArrayList<>()).add(r);
        }
        List<String> order = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (WebSearchResult r : raw) {
            if (!r.getUrl().isEmpty() && !seen.contains(r.getUrl())) {
                order.add(r.getUrl());
                seen.add(r.getUrl());
            }
        }
        List<SearchResult> out = new ArrayList<>();
        while (out.size() < limit) {
            boolean progress = false;
            for (String url : order) {
                if (out.size() >= limit) {
                    break;
                }
                List<SearchResult> list = urlToRefs.get(url);
                if (list == null || list.isEmpty()) {
                    continue;
                }
                out.add(list.get(0));
                urlToRefs.put(url, list.subList(1, list.size()));
                progress = true;
            }
            if (!progress) {
                break;
            }
        }
        return out;
    }

    /** 按 URL 把选中引用合并回原始结果。 */
    public static List<WebSearchResult> consolidateReferencesByURL(List<WebSearchResult> raw,
                                                                   List<SearchResult> selected) {
        if (selected == null || selected.isEmpty()) {
            return raw;
        }
        Map<String, List<String>> agg = new LinkedHashMap<>();
        for (SearchResult ref : selected) {
            String url = extractSourceUrlFromContent(ref.getContent());
            if (url.isEmpty()) {
                continue;
            }
            agg.computeIfAbsent(url, k -> new ArrayList<>()).add(stripMarker(ref.getContent()));
        }
        List<WebSearchResult> out = new ArrayList<>(raw.size());
        for (WebSearchResult r : raw) {
            List<String> parts = agg.get(r.getUrl());
            if (parts == null || parts.isEmpty()) {
                out.add(r);
                continue;
            }
            WebSearchResult merged = new WebSearchResult();
            merged.setTitle(r.getTitle());
            merged.setUrl(r.getUrl());
            merged.setSnippet(r.getSnippet());
            merged.setContent(String.join("\n---\n", parts));
            merged.setSource(r.getSource());
            merged.setPublishedAt(r.getPublishedAt());
            out.add(merged);
        }
        return out;
    }

    /** 首行 "[sourceUrl]: " 标记。 */
    public static String extractSourceUrlFromContent(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        String[] lines = content.split("\n", -1);
        if (lines.length == 0) {
            return "";
        }
        String first = lines[0].trim();
        String prefix = "[sourceUrl]: ";
        if (first.startsWith(prefix)) {
            return first.substring(prefix.length()).trim();
        }
        return "";
    }

    /** 剥掉首行标记避免重复。 */
    public static String stripMarker(String content) {
        if (content == null) {
            return "";
        }
        String[] lines = content.split("\n", -1);
        if (lines.length == 0) {
            return content;
        }
        if (lines[0].trim().startsWith("[sourceUrl]: ")) {
            return String.join("\n", java.util.Arrays.copyOfRange(lines, 1, lines.length));
        }
        return content;
    }

    /**
     * 搜索配置（执行面字段子集；
     * jsonb 形状以 session 配置为准，这里只承载执行所需）。
     */
    public static final class WebSearchConfig {
        public WebSearchFilters filters = WebSearchFilters.EMPTY;
        public int maxResults;
        public boolean includeDate;
        public List<String> blacklist = new ArrayList<>();
        public String embeddingModelId = "";
        public int documentFragments;
        public String proxyUrl = "";
        /** 直接调用方（L2 请求）的内联 provider/apiKey；租户 KV 不再写它们。 */
        public String provider = "";
        public String apiKey = "";

        public WebSearchFilters getFilters() {
            return filters;
        }
        /**
         * 租户配置（{@code common.tenant}，L1）→ 执行面配置（由管线搬入：
         * 管线只持有 L1 配置，转换发生在域侧，依赖方向保持"管线 → L1 ← websearch"）。
         *
         * <p>缺省合并口径与迁移前逐字一致：{@code cfg == null} → 全默认；各字段空值回落默认。</p>
         */
        public static WebSearchConfig from(com.ragagent.contract.tenant.WebSearchConfig cfg) {
            WebSearchConfig out = new WebSearchConfig();
            if (cfg == null) {
                return out;
            }
            out.blacklist = cfg.getBlacklist() == null
                    ? new java.util.ArrayList<>() : new java.util.ArrayList<>(cfg.getBlacklist());
            out.documentFragments = cfg.getDocumentFragments();
            out.embeddingModelId = cfg.getEmbeddingModelId() == null ? "" : cfg.getEmbeddingModelId();
            out.includeDate = cfg.isIncludeDate();
            out.maxResults = cfg.getMaxResults();
            out.proxyUrl = cfg.getProxyUrl() == null ? "" : cfg.getProxyUrl();
            return out;
            // 尚有 rerank_model_id/embedding_dimension 两个键未在执行形状 WebSearchConfig
            // 承载（仅 RAG 压缩消费，search 路径不用），随压缩路径接线时补。
        }
    }

}
