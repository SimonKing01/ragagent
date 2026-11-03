package com.ragagent.agenttools.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.contract.agent.SearchAuth;
import com.ragagent.contract.knowledge.ChunkFacts;
import com.ragagent.contract.retrieval.SearchTarget;
import com.ragagent.contract.tool.BaseTool;
import com.ragagent.contract.tool.ToolDefinitions;
import com.ragagent.contract.tool.ToolRequest;
import com.ragagent.contract.tool.ToolResult;
import com.ragagent.platform.knowledge.GrepChunkHit;
import com.ragagent.platform.tool.ToolSchemas;
import com.ragagent.platform.web.JsonMappers;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * grep_chunks 工具。
 *
 * <p>DB 直查经 {@link GrepChunkSearch} seam（scopeClause OR 组合、
 * {@code (content ~* ? OR knowledges.title ~* ?)} 正则条件、
 * {@code ORDER BY created_at DESC LIMIT 500}、按 knowledge_id 的 COUNT(*) 回填
 * totalChunkCount——整段 SQL 方言逻辑落在 seam 实现侧；
 * 本工具只留 seam 调用）。</p>
 *
 * <p>实现边界：JDK 正则与 RE2 方言不同（编译失败文案不同，探针不录非法 regex）；
 * MMR 分词对中文内容走空白分词，冗余度可能不同（探针 MMR 场景用英文）；
 * 并列分数时排序结果不保证确定（探针语料避开并列）；
 * {@code knowledge_base_ids} 按出现序遍历。</p>
 */
public class GrepChunksTool extends BaseTool {

    /** schema 键按字母序：properties < required < type。 */
    private static final String SCHEMA_JSON = """
            {
              "properties": {
                "query": {
                  "description": "A single POSIX regex applied directly to chunk content (case-insensitive). Combine multiple concepts with \\"|\\" alternation in ONE regex (e.g. \\"stardust|skyvault|psionic\\") — do not split into multiple calls.",
                  "minLength": 1,
                  "type": "string"
                }
              },
              "required": ["query"],
              "type": "object"
            }""";

    private static final String DESCRIPTION = "Search knowledge base chunk content with a single POSIX regular expression, applied directly in the database (PostgreSQL ~* / MySQL/SQLite REGEXP, case-insensitive). Behaves like `grep -E -i`.\n"
            + "Pack multiple concepts into ONE regex using `|` alternation — do not call this tool repeatedly for synonyms.\n"
            + "Returns matching chunks with a short cN chunk source ID, a parent dN document ID, and a <match> snippet around the first match.\n"
            + "Examples:\n"
            + "- Alternation (RECOMMENDED): \"stardust|skyvault|psionic\" (matches any of the words)\n"
            + "- Multiple terms in order: \"psionic.*engine\" (matches both words in order)\n"
            + "- Word boundary / anchor: \"\\\\brag\\\\b\" or \"^chapter\\\\s+\\\\d+\"\n"
            + "- Plain text: \"engine\" (matches literal substring anywhere in chunk content)\n"
            + "IMPORTANT — JSON escaping: every backslash in a regex MUST be written as \\\\ inside the JSON tool arguments (e.g. to search for literal \"C++\" write \"C\\\\+\\\\+\", NOT \"C\\+\\+\"; for \"\\d+\" write \"\\\\d+\"). Plain \"\\+\" / \"\\d\" etc. are invalid JSON escapes and will fail to parse.\n"
            + "Use this to locate candidate chunks by exact identifiers, error codes, product names, or recurring terms.\n"
            + "\n"
            + "## Deep read (only when there is insufficient information in the chunks):\n"
            + "- The <match> text is already the matched content; answer from it when it is sufficient.\n"
            + "- **FAQ hit** (chunk type faq): list_knowledge_chunks(faqId=cN) for the full Q/A.\n";

    /** 单次返回上限（硬编码常量）。 */
    static final int LIMIT = 30;
    /** knowledge 元信息查询的行数上限（硬编码常量）。 */
    private static final int MAX_KNOWLEDGE_ROWS = 20;
    /** 命中摘要的上下文窗口（码点）。 */
    static final int SNIPPET_CONTEXT_RUNES = 200;
    /** 命中摘要的单个 match 上限（码点）。 */
    static final int SNIPPET_MAX_MATCH_RUNES = 200;
    /** 命中摘要总长上限（码点）。 */
    static final int SNIPPET_MAX_TOTAL_RUNES = 800;

    /** DB 行视图（chunks 列 + knowledge_title + total_chunk_count）。 */


    /**
     * DB 直查 seam。
     *
     * <p>实现侧负责：{@code chunks} JOIN {@code knowledges}、is_enabled/deleted_at 过滤、
     * scopeClause 的 OR 组合（knowledge_id IN / 标签 EXISTS / kb+tenant 对）、
     * 每个 query 的 {@code content ~* ?}（只搜正文，篇名命中不召回整篇）、
     * {@code ORDER BY chunks.created_at DESC LIMIT 500}、以及按 knowledge_id 的
     * {@code COUNT(*)} 回填 {@link GrepChunkHit#totalChunkCount}。
     * 无有效 scope 或 scope 子句为空时返回空表（两处 early return）。</p>
     */
    public interface GrepChunkSearch {
        List<GrepChunkHit> search(List<String> queries, List<String> fullKbIDs, List<String> knowledgeIDs,
                List<SearchTarget> tagTargets, Map<String, Long> kbTenantMap);
    }

    private final GrepChunkSearch chunkSearch;
    private final SearchTarget.SearchTargets searchTargets;
    /** 会话级已返回 chunk 去重（单实例单线程使用）。 */
    private final LinkedHashSet<String> seenChunks = new LinkedHashSet<>();

    public GrepChunksTool(GrepChunkSearch chunkSearch, SearchTarget.SearchTargets searchTargets) {
        super(ToolDefinitions.TOOL_GREP_CHUNKS, DESCRIPTION, ToolSchemas.parse(SCHEMA_JSON));
        this.chunkSearch = chunkSearch;
        this.searchTargets = searchTargets;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        JsonNode args = JsonMappers.toJsonNode(request.args());

        // 入参只有 "query" 一个字段（历史 schema 的数组形态 queries/patterns/pattern
        // 仅为旧前端兼容别名，不再解析）。
        String query = args.path("query").asText("").trim();
        if (query.isEmpty()) {
            return failure("query parameter is required and must be a non-empty regex string");
        }

        // 不区分大小写匹配（(?i) 前缀）。编译失败文案因正则引擎而异（实现边界）。
        final Pattern re;
        try {
            re = Pattern.compile("(?i)" + query);
        } catch (PatternSyntaxException e) {
            return failure("invalid regex query \"" + query + "\": " + e.getDescription());
        }
        List<String> queries = List.of(query);
        List<Pattern> compiled = List.of(re);

        Map<String, Long> kbTenantMap = searchTargets == null ? Map.of() : searchTargets.getKbTenantMap();
        GrepScope scope = resolveGrepScope();
        List<String> kbIDsForMeta = scope.fullKBIDs;
        if (kbIDsForMeta.isEmpty() && searchTargets != null) {
            kbIDsForMeta = searchTargets.getAllKnowledgeBaseIds();
        }

        List<GrepChunkHit> results;
        try {
            results = chunkSearch.search(queries, scope.fullKBIDs, scope.knowledgeIDs,
                    scope.tagTargets, kbTenantMap);
        } catch (RuntimeException e) {
            return failure("Search failed: " + e.getMessage());
        }
        if (results == null) {
            results = List.of();
        }

        List<GrepChunkHit> deduplicated = GrepChunksScoring.deduplicateChunks(results);
        List<GrepChunkHit> scored = GrepChunksScoring.scoreChunks(deduplicated, compiled);

        List<GrepChunkHit> finalResults = scored;
        if (scored.size() > 10) {
            int mmrK = scored.size();
            if (LIMIT > 0 && mmrK > LIMIT) {
                mmrK = LIMIT;
            }
            List<GrepChunkHit> mmrResults = GrepChunksScoring.applyMMR(scored, mmrK, 0.7);
            if (!mmrResults.isEmpty()) {
                finalResults = mmrResults;
            }
        }

        // 排序：MatchedPatterns > MatchScore > ChunkIndex（并列时序不定）。
        finalResults.sort((a, b) -> {
            if (a.matchedPatterns != b.matchedPatterns) {
                return Integer.compare(b.matchedPatterns, a.matchedPatterns);
            }
            if (a.matchScore != b.matchScore) {
                return Double.compare(b.matchScore, a.matchScore);
            }
            return Integer.compare(a.chunkIndex, b.chunkIndex);
        });

        if (finalResults.size() > LIMIT) {
            finalResults = new ArrayList<>(finalResults.subList(0, LIMIT));
        }

        List<Map<String, Object>> chunkResults = buildGrepChunkResults(finalResults, compiled);
        List<KnowledgeAggregation> aggregatedResults = aggregateByKnowledge(finalResults, queries, compiled);
        int documentCount = aggregatedResults == null ? 0 : aggregatedResults.size();
        List<KnowledgeAggregation> knowledgeResultsForUI = aggregatedResults;
        if (knowledgeResultsForUI != null && knowledgeResultsForUI.size() > MAX_KNOWLEDGE_ROWS) {
            knowledgeResultsForUI = new ArrayList<>(knowledgeResultsForUI.subList(0, MAX_KNOWLEDGE_ROWS));
        }

        String output = formatOutput(finalResults, queries, compiled);

        ToolResult result = new ToolResult();
        result.setSuccess(true);
        result.setOutput(output);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", query);
        data.put("queries", queries); // legacy alias for older frontends
        data.put("patterns", queries); // legacy alias for older frontends
        // 空表/无 scope 时这些键序列化为 null，不是 []（输出契约）。
        data.put("chunkResults", chunkResults == null ? null : chunkResults);
        data.put("knowledgeResults", knowledgeResultsForUI == null ? null
                : knowledgeAggregationMaps(knowledgeResultsForUI));
        data.put("resultCount", chunkResults == null ? 0 : chunkResults.size());
        data.put("documentCount", documentCount);
        data.put("totalMatches", finalResults.size());
        data.put("knowledgeBaseIds",
                kbIDsForMeta == null || kbIDsForMeta.isEmpty() ? null : kbIDsForMeta);
        data.put("limit", LIMIT);
        data.put("maxResults", LIMIT); // legacy alias
        data.put("displayType", "grep_results");
        result.setData(data);
        return result;
    }

    private ToolResult failure(String error) {
        ToolResult r = new ToolResult();
        r.setSuccess(false);
        r.setError(error);
        return r;
    }

    /** scope 解析结果三元组（整库 KB / 知识 ID / 标签目标）。 */
    static final class GrepScope {
        final List<String> fullKBIDs = new ArrayList<>();
        final List<String> knowledgeIDs = new ArrayList<>();
        final List<SearchTarget> tagTargets = new ArrayList<>();
    }

    /** 解析检索目标为 grep scope（scope 授权复用 SearchAuth）。 */
    GrepScope resolveGrepScope() {
        GrepScope scope = new GrepScope();
        LinkedHashSet<String> seenKB = new LinkedHashSet<>();
        LinkedHashSet<String> seenKnowledge = new LinkedHashSet<>();
        LinkedHashSet<String> seenTagScope = new LinkedHashSet<>();
        List<SearchTarget> targets = searchTargets == null ? List.of() : searchTargets.list();
        for (SearchTarget target : targets) {
            if (target == null || target.knowledgeBaseId() == null || target.knowledgeBaseId().isEmpty()) {
                continue;
            }
            SearchAuth.Scope targetScope = SearchAuth.searchTargetScope(target);
            List<String> targetKnowledgeIDs = targetScope.knowledgeIds();
            List<String> targetTagIDs = targetScope.tagIds();
            if (targetTagIDs != null && !targetTagIDs.isEmpty()) {
                long tenantId = target.tenantId();
                if (tenantId == 0) {
                    tenantId = searchTargets.getTenantIdForKb(target.knowledgeBaseId());
                }
                List<String> tagIDs = targetTagIDs;
                if (tagIDs.isEmpty() || tenantId == 0) {
                    continue;
                }
                // scopeKey = kb:tenant:tagIDs（\0 连接标签）
                String scopeKey = target.knowledgeBaseId() + ":" + tenantId + ":"
                        + String.join("\u0000", tagIDs);
                if (!seenTagScope.add(scopeKey)) {
                    continue;
                }
                scope.tagTargets.add(new SearchTarget(SearchTarget.TYPE_KNOWLEDGE_BASE,
                        target.knowledgeBaseId(), tenantId, null, tagIDs, null, false));
            } else if (targetKnowledgeIDs != null && !targetKnowledgeIDs.isEmpty()) {
                for (String kid : targetKnowledgeIDs) {
                    if (seenKnowledge.add(kid)) {
                        scope.knowledgeIDs.add(kid);
                    }
                }
            } else {
                if (seenKB.add(target.knowledgeBaseId())) {
                    scope.fullKBIDs.add(target.knowledgeBaseId());
                }
            }
        }
        return scope;
    }








    /** 按知识聚合的 grep 结果。 */
    static final class KnowledgeAggregation {
        String knowledgeId;
        String knowledgeBaseId;
        String knowledgeTitle;
        String faqQuestion = "";
        int chunkHitCount;
        int totalChunkCount;
        final Map<String, Integer> patternCounts = new LinkedHashMap<>();
        int totalPatternHits;
        int distinctPatterns;
        String matchSnippet = "";
    }

    /** 按知识聚合（排序：TitleMatch &gt; DistinctPatterns &gt; TotalPatternHits &gt; ChunkHitCount &gt; Title，并列时序不定）。 */
    static List<KnowledgeAggregation> aggregateByKnowledge(List<GrepChunkHit> results,
            List<String> queries, List<Pattern> compiled) {
        if (results.isEmpty()) {
            return null; // 空结果在 data 里序列化为 null（输出契约）
        }

        List<String> queryKeys = new ArrayList<>();
        for (String q : queries) {
            if (q == null || q.trim().isEmpty()) {
                continue;
            }
            queryKeys.add(q);
        }

        Map<String, KnowledgeAggregation> aggregated = new LinkedHashMap<>();
        for (GrepChunkHit chunk : results) {
            String knowledgeId = GrepChunkHit.nz(chunk.knowledgeId);
            if (knowledgeId.isEmpty()) {
                knowledgeId = "chunk-" + GrepChunkHit.nz(chunk.id);
            }

            KnowledgeAggregation entry = aggregated.get(knowledgeId);
            if (entry == null) {
                entry = new KnowledgeAggregation();
                entry.knowledgeId = knowledgeId;
                entry.knowledgeBaseId = GrepChunkHit.nz(chunk.knowledgeBaseId);
                String title = GrepChunkHit.nz(chunk.knowledgeTitle);
                if (title.trim().isEmpty()) {
                    title = "Untitled";
                }
                entry.knowledgeTitle = title;
                entry.totalChunkCount = chunk.totalChunkCount;
                for (String qKey : queryKeys) {
                    entry.patternCounts.put(qKey, 0);
                }
                aggregated.put(knowledgeId, entry);
            }

            entry.chunkHitCount++;
            if (entry.faqQuestion.isEmpty()) {
                String q = FaqSnippet.faqStandardQuestion(chunk.toChunk());
                if (!q.isEmpty()) {
                    entry.faqQuestion = q;
                }
            }
            if (entry.matchSnippet.isEmpty()) {
                String snippet = extractChunkMatchSnippet(chunk, compiled);
                if (!snippet.isEmpty()) {
                    entry.matchSnippet = snippet;
                }
            }

            Map<String, Integer> occurrences = countRegexHits(GrepChunkHit.nz(chunk.content), compiled, queryKeys);
            for (String q : queryKeys) {
                int count = occurrences.getOrDefault(q, 0);
                if (count == 0) {
                    continue;
                }
                entry.patternCounts.merge(q, count, Integer::sum);
                entry.totalPatternHits += count;
            }
        }

        List<KnowledgeAggregation> resultSlice = new ArrayList<>(aggregated.values());
        for (KnowledgeAggregation entry : resultSlice) {
            int distinct = 0;
            for (int count : entry.patternCounts.values()) {
                if (count > 0) {
                    distinct++;
                }
            }
            entry.distinctPatterns = distinct;
        }

        resultSlice.sort((a, b) -> {
            if (a.distinctPatterns != b.distinctPatterns) {
                return Integer.compare(b.distinctPatterns, a.distinctPatterns);
            }
            if (a.totalPatternHits != b.totalPatternHits) {
                return Integer.compare(b.totalPatternHits, a.totalPatternHits);
            }
            if (a.chunkHitCount != b.chunkHitCount) {
                return Integer.compare(b.chunkHitCount, a.chunkHitCount);
            }
            return a.knowledgeTitle.compareTo(b.knowledgeTitle);
        });
        return resultSlice;
    }

    /** 构建结果列表（空串/0/false 的字段不写入 map——输出契约）。 */
    static List<Map<String, Object>> buildGrepChunkResults(List<GrepChunkHit> results,
            List<Pattern> compiled) {
        if (results.isEmpty()) {
            return null; // 空结果在 data 里序列化为 null（输出契约）
        }
        List<Map<String, Object>> out = new ArrayList<>(results.size());
        for (GrepChunkHit r : results) {
            Map<String, Object> item = new LinkedHashMap<>();
            String chunkType = GrepChunkHit.nz(r.chunkType);
            item.put("knowledgeId", GrepChunkHit.nz(r.knowledgeId));
            item.put("knowledgeBaseId", GrepChunkHit.nz(r.knowledgeBaseId));
            item.put("knowledgeTitle", GrepChunkHit.nz(r.knowledgeTitle));
            item.put("chunkType", chunkType);
            if (r.totalChunkCount > 0) {
                item.put("totalChunkCount", r.totalChunkCount);
            }
            String snippet = extractChunkMatchSnippet(r, compiled);
            if (!snippet.isEmpty()) {
                item.put("matchSnippet", snippet);
            }
            item.put("score", r.matchScore);
            if ("faq".equals(chunkType)) {
                if (!GrepChunkHit.nz(r.id).isEmpty()) {
                    item.put("faqId", GrepChunkHit.nz(r.id));
                }
                if (r.chunkIndex != 0) {
                    item.put("index", r.chunkIndex);
                }
                String q = FaqSnippet.faqStandardQuestion(r.toChunk());
                if (!q.isEmpty()) {
                    item.put("faqQuestion", q);
                }
            } else {
                if (!GrepChunkHit.nz(r.id).isEmpty()) {
                    item.put("chunkId", GrepChunkHit.nz(r.id));
                }
                if (r.chunkIndex != 0) {
                    item.put("chunkIndex", r.chunkIndex);
                }
            }
            out.add(item);
        }
        return out;
    }

    /** 聚合结果 → JSON map（faq_question/match_snippet 空不入）。 */
    static List<Map<String, Object>> knowledgeAggregationMaps(List<KnowledgeAggregation> entries) {
        List<Map<String, Object>> out = new ArrayList<>(entries.size());
        for (KnowledgeAggregation e : entries) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("knowledgeId", e.knowledgeId);
            m.put("knowledgeBaseId", e.knowledgeBaseId);
            m.put("knowledgeTitle", e.knowledgeTitle);
            if (!e.faqQuestion.isEmpty()) {
                m.put("faqQuestion", e.faqQuestion);
            }
            m.put("chunkHitCount", e.chunkHitCount);
            m.put("totalChunkCount", e.totalChunkCount);
            m.put("patternCounts", e.patternCounts);
            m.put("totalPatternHits", e.totalPatternHits);
            m.put("distinctPatterns", e.distinctPatterns);
            if (!e.matchSnippet.isEmpty()) {
                m.put("matchSnippet", e.matchSnippet);
            }
            out.add(m);
        }
        return out;
    }

    /** 正则命中计数（key = 原始 query 串，value = 全匹配数）。 */
    static Map<String, Integer> countRegexHits(String content, List<Pattern> compiled, List<String> patterns) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (content == null || content.isEmpty() || compiled == null || compiled.isEmpty()) {
            return counts;
        }
        for (int i = 0; i < compiled.size() && i < patterns.size(); i++) {
            Pattern re = compiled.get(i);
            if (re == null) {
                continue;
            }
            int n = 0;
            java.util.regex.Matcher m = re.matcher(content);
            while (m.find()) {
                n++;
            }
            counts.put(patterns.get(i), n);
        }
        return counts;
    }

    /** 命中摘要提取（FAQ 走 faqMatchSnippet，其他走 extractSnippetRegex）。 */
    static String extractChunkMatchSnippet(GrepChunkHit chunk, List<Pattern> compiled) {
        ChunkFacts c = chunk.toChunk();
        if ("faq".equals(c.chunkType())) {
            String s = FaqSnippet.faqMatchSnippet(c, compiled);
            if (!s.isEmpty()) {
                return s;
            }
        }
        return extractSnippetRegex(chunk.content == null ? "" : chunk.content, compiled);
    }

    /**
     * 跨 pattern 取最早命中（按码点位置比较），
     * 上下文各截 SNIPPET_CONTEXT_RUNES，match 超 200 码点截+"..."，
     * 换行转空格并折叠连续空格，总长超 800 码点截+"..."，"... x ..." 包裹。
     */
    static String extractSnippetRegex(String content, List<Pattern> compiled) {
        if (content == null || content.isEmpty() || compiled == null || compiled.isEmpty()) {
            return "";
        }

        // 记录最早命中的（码点起点, 起点 char, 终点 char）。
        int earliestRune = -1;
        int earliestStart = -1;
        int earliestEnd = -1;
        for (Pattern re : compiled) {
            if (re == null) {
                continue;
            }
            java.util.regex.Matcher m = re.matcher(content);
            if (!m.find()) {
                continue;
            }
            int startRune = content.codePointCount(0, m.start());
            if (earliestRune < 0 || startRune < earliestRune) {
                earliestRune = startRune;
                earliestStart = m.start();
                earliestEnd = m.end();
            }
        }
        if (earliestRune < 0) {
            return "";
        }

        String matchStr = content.substring(earliestStart, earliestEnd);
        String before = content.substring(0, earliestStart);
        String after = content.substring(earliestEnd);

        String beforeTrimmed = lastRunes(before, SNIPPET_CONTEXT_RUNES);
        String afterTrimmed = firstRunes(after, SNIPPET_CONTEXT_RUNES);
        String matchTrimmed = firstRunes(matchStr, SNIPPET_MAX_MATCH_RUNES);
        if (matchStr.codePointCount(0, matchStr.length()) > SNIPPET_MAX_MATCH_RUNES) {
            matchTrimmed = matchTrimmed + "...";
        }

        String snippet = beforeTrimmed + matchTrimmed + afterTrimmed;
        snippet = snippet.replace("\n", " ");
        while (snippet.contains("  ")) {
            snippet = snippet.replace("  ", " ");
        }
        snippet = snippet.trim();
        if (snippet.codePointCount(0, snippet.length()) > SNIPPET_MAX_TOTAL_RUNES) {
            snippet = firstRunes(snippet, SNIPPET_MAX_TOTAL_RUNES) + "...";
        }
        return "... " + snippet + " ...";
    }

    /** 取前 maxRunes 个码点。 */
    static String firstRunes(String s, int maxRunes) {
        if (s.codePointCount(0, s.length()) <= maxRunes) {
            return s;
        }
        return s.substring(0, s.offsetByCodePoints(0, maxRunes));
    }

    /** 取后 maxRunes 个码点。 */
    static String lastRunes(String s, int maxRunes) {
        int total = s.codePointCount(0, s.length());
        if (total <= maxRunes) {
            return s;
        }
        return s.substring(s.offsetByCodePoints(0, total - maxRunes));
    }

    /** XML 输出（seenChunks 会话级去重 → already_seen）。 */
    String formatOutput(List<GrepChunkHit> results, List<String> queries, List<Pattern> compiled) {
        StringBuilder b = new StringBuilder();

        b.append(String.format(Locale.ROOT, "<grepResults chunkCount=\"%d\">\n", results.size()));
        for (String q : queries) {
            b.append(String.format(Locale.ROOT, "<query>%s</query>\n", FaqSnippet.xmlEscape(q)));
        }

        if (results.isEmpty()) {
            b.append("</grepResults>");
            return b.toString();
        }

        for (GrepChunkHit r : results) {
            Map<String, Integer> counts = countRegexHits(r.content == null ? "" : r.content, compiled, queries);
            String snippet = extractChunkMatchSnippet(r, compiled);

            String extraAttr = "";
            String faqQ = FaqSnippet.faqStandardQuestion(r.toChunk());
            if (!faqQ.isEmpty()) {
                extraAttr = String.format(Locale.ROOT, " faqQuestion=\"%s\"", FaqSnippet.xmlEscape(faqQ));
            }
            boolean isFAQ = "faq".equals(GrepChunkHit.nz(r.chunkType));

            boolean seen = !seenChunks.add(GrepChunkHit.nz(r.id));

            String id = GrepChunkHit.nz(r.id);
            String knowledgeId = GrepChunkHit.nz(r.knowledgeId);
            String knowledgeTitle = GrepChunkHit.nz(r.knowledgeTitle);
            if (isFAQ) {
                if (seen) {
                    b.append(String.format(Locale.ROOT,
                            "<faq faqId=\"%s\" knowledgeTitle=\"%s\"%s index=\"%d\" score=\"%.3f\" already_seen=\"true\">\n",
                            FaqSnippet.xmlEscape(id), FaqSnippet.xmlEscape(knowledgeTitle),
                            extraAttr, r.chunkIndex, r.matchScore));
                } else {
                    b.append(String.format(Locale.ROOT,
                            "<faq faqId=\"%s\" knowledgeTitle=\"%s\"%s index=\"%d\" score=\"%.3f\">\n",
                            FaqSnippet.xmlEscape(id), FaqSnippet.xmlEscape(knowledgeTitle),
                            extraAttr, r.chunkIndex, r.matchScore));
                }
            } else if (seen) {
                b.append(String.format(Locale.ROOT,
                        "<chunk chunkId=\"%s\" knowledgeId=\"%s\" knowledgeTitle=\"%s\"%s chunkIndex=\"%d\" score=\"%.3f\" already_seen=\"true\">\n",
                        FaqSnippet.xmlEscape(id), FaqSnippet.xmlEscape(knowledgeId),
                        FaqSnippet.xmlEscape(knowledgeTitle),
                        extraAttr, r.chunkIndex, r.matchScore));
            } else {
                b.append(String.format(Locale.ROOT,
                        "<chunk chunkId=\"%s\" knowledgeId=\"%s\" knowledgeTitle=\"%s\"%s chunkIndex=\"%d\" score=\"%.3f\">\n",
                        FaqSnippet.xmlEscape(id), FaqSnippet.xmlEscape(knowledgeId),
                        FaqSnippet.xmlEscape(knowledgeTitle),
                        extraAttr, r.chunkIndex, r.matchScore));
            }

            for (String q : queries) {
                int c = counts.getOrDefault(q, 0);
                if (c > 0) {
                    b.append(String.format(Locale.ROOT, "<queryHit query=\"%s\" count=\"%d\" />\n",
                            FaqSnippet.xmlEscape(q), c));
                }
            }
            if (seen) {
                b.append("<note>(snippet omitted, already returned in a previous grep_chunks call this session)</note>\n");
            } else if (!snippet.isEmpty()) {
                b.append(String.format(Locale.ROOT, "<matchSnippet>%s</matchSnippet>\n",
                        FaqSnippet.xmlEscape(snippet)));
            }
            if (isFAQ) {
                b.append("</faq>\n");
            } else {
                b.append("</chunk>\n");
            }
        }

        b.append("</grepResults>");
        return b.toString();
    }
}
