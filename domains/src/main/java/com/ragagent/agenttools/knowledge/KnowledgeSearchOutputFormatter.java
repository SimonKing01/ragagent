package com.ragagent.agenttools.knowledge;

import static com.ragagent.agenttools.knowledge.KnowledgeSearchTool.nz;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agenttools.knowledge.KnowledgeSearchTool.ResultWithMeta;
import com.ragagent.contract.knowledge.ChunkFacts;
import com.ragagent.contract.knowledge.FaqChunkMetadata;
import com.ragagent.contract.tool.ToolResult;
import com.ragagent.platform.knowledge.DocChunkSupport;
import com.ragagent.platform.knowledge.DocChunkSupport.ImageInfoRow;
import com.ragagent.platform.web.JsonMappers;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;


final class KnowledgeSearchOutputFormatter {

    private static final Set<String> VIDEO_TYPES =
            Set.of("mp4", "mov", "m4v", "webm", "mkv", "avi");
    private static final Set<String> AUDIO_TYPES =
            Set.of("mp3", "wav", "m4a", "flac", "ogg", "aac");


    private final KnowledgeSearchTool tool;

    KnowledgeSearchOutputFormatter(KnowledgeSearchTool tool) {
        this.tool = tool;
    }

    FaqChunkMetadata getFAQMetadata(String chunkId, Map<String, FaqChunkMetadata> cache) {
        if (chunkId.isEmpty() || tool.chunkBackend == null) {
            return null;
        }
        if (cache.containsKey(chunkId)) {
            return cache.get(chunkId);
        }
        ChunkFacts chunk;
        try {
            chunk = tool.chunkBackend.faqChunkById(chunkId);
        } catch (RuntimeException e) {
            cache.put(chunkId, null);
            return null;
        }
        if (chunk == null) {
            cache.put(chunkId, null);
            return null;
        }
        FaqChunkMetadata meta = FaqSnippet.faqMetadata(chunk);
        cache.put(chunkId, meta);
        return meta;
    }

    /**
     * 文档级头信息（每文档一次）：用户自定义元数据 + 媒体引用。
     *
     * <p>媒体块是 Agent「主动发视频/图片」的唯一依据：工具输出里没有它，模型就只能
     * 描述“有一个视频”，无法给出可渲染的封面与播放链接。</p>
     */
    static void writeKnowledgeMetadataHeader(StringBuilder ob, List<ResultWithMeta> results) {
        Set<String> seen = new LinkedHashSet<>();
        boolean hasHeader = false;
        StringBuilder documents = new StringBuilder();
        for (ResultWithMeta result : results) {
            if (result == null || result.sr == null || nz(result.sr.knowledgeId).isEmpty()) {
                continue;
            }
            String media = mediaXml(result.sr);
            boolean hasCustom = !nz(result.sr.knowledgeCustomMetadata).isEmpty();
            if (media.isEmpty() && !hasCustom) {
                continue;
            }
            if (!seen.add(result.sr.knowledgeId)) {
                continue;
            }
            hasHeader = true;
            documents.append(String.format(Locale.ROOT,
                    "<document knowledgeId=\"%s\" knowledgeBaseId=\"%s\" title=\"%s\">\n",
                    FaqSnippet.xmlEscape(result.sr.knowledgeId),
                    FaqSnippet.xmlEscape(result.sr.knowledgeBaseId),
                    FaqSnippet.xmlEscape(result.sr.knowledgeTitle)));
            if (hasCustom) {
                documents.append(String.format(Locale.ROOT, "<metadata>%s</metadata>\n",
                        FaqSnippet.xmlEscape(result.sr.knowledgeCustomMetadata)));
            }
            if (!media.isEmpty()) {
                documents.append(media);
            }
            documents.append("</document>\n");
        }
        if (!hasHeader) {
            return;
        }
        ob.append("<documents>\n");
        ob.append(documents);
        ob.append("</documents>\n");
    }

    /** 从 metadata.mediaRefs 渲染 {@code <media>} 块；没有或不合法时返回空串。 */
    static String mediaXml(KnowledgeSearchTool.SearchResultView sr) {
        if (sr.metadata == null) {
            return "";
        }
        String raw = sr.metadata.get("mediaRefs");
        if (raw == null || raw.isBlank()) {
            return "";
        }
        JsonNode node;
        try {
            node = JsonMappers.lenient().readTree(raw);
        } catch (IOException e) {
            return "";
        }
        if (node == null || !node.isObject()) {
            return "";
        }
        String poster = node.path("posterRef").asText("");
        String play = node.path("mediaRef").asText("");
        if (poster.isEmpty() && play.isEmpty()) {
            return "";
        }
        String kind = mediaKind(node.path("fileType").asText(""));
        StringBuilder b = new StringBuilder();
        b.append(String.format(Locale.ROOT, "<media type=\"%s\">\n", FaqSnippet.xmlEscape(kind)));
        if (!poster.isEmpty()) {
            b.append(String.format(Locale.ROOT, "<poster>%s</poster>\n", FaqSnippet.xmlEscape(poster)));
        }
        if (!play.isEmpty()) {
            b.append(String.format(Locale.ROOT, "<play>%s</play>\n", FaqSnippet.xmlEscape(play)));
        }
        b.append("</media>\n");
        return b.toString();
    }

    /** 扩展名 → 媒体类型（video / audio / image）。 */
    static String mediaKind(String fileType) {
        String ext = fileType == null ? "" : fileType.toLowerCase(Locale.ROOT);
        if (VIDEO_TYPES.contains(ext)) {
            return "video";
        }
        if (AUDIO_TYPES.contains(ext)) {
            return "audio";
        }
        return "image";
    }

    /** 工具输出渲染。 */
    ToolResult formatOutput(List<ResultWithMeta> results, List<String> kbsToSearch, List<String> queries) {
        if (results.isEmpty()) {
            return emptyResult(kbsToSearch, queries);
        }

        Map<String, Integer> kbCounts = new LinkedHashMap<>();
        for (ResultWithMeta r : results) {
            kbCounts.merge(nz(r.sr.knowledgeBaseId), 1, Integer::sum);
        }

        StringBuilder ob = new StringBuilder();
        ob.append(String.format(Locale.ROOT, "<searchResults count=\"%d\">\n", results.size()));
        for (String q : queries) {
            ob.append(String.format(Locale.ROOT, "<query>%s</query>\n", FaqSnippet.xmlEscape(q)));
        }
        writeKnowledgeMetadataHeader(ob, results);

        List<Map<String, Object>> formattedResults = new ArrayList<>(results.size());
        Map<String, FaqChunkMetadata> faqMetadataCache = new LinkedHashMap<>();
        Map<String, Set<Integer>> knowledgeChunkMap = new LinkedHashMap<>();
        Map<String, Long> knowledgeTotalMap = new LinkedHashMap<>();
        Map<String, String> knowledgeTitleMap = new LinkedHashMap<>();

        for (int i = 0; i < results.size(); i++) {
            ResultWithMeta result = results.get(i);
            FaqChunkMetadata faqMeta = KnowledgeSearchTool.KB_TYPE_FAQ.equals(result.knowledgeBaseType)
                    ? getFAQMetadata(nz(result.sr.id), faqMetadataCache) : null;

            knowledgeChunkMap.computeIfAbsent(nz(result.sr.knowledgeId), k -> new LinkedHashSet<>())
                    .add(result.sr.chunkIndex);
            knowledgeTitleMap.put(nz(result.sr.knowledgeId), nz(result.sr.knowledgeTitle));
            knowledgeTotalMap.computeIfAbsent(nz(result.sr.knowledgeId),
                    k -> totalChunksFor(result));

            boolean seen = !tool.seenChunks.add(nz(result.sr.id));
            String sourceQuery = nz(result.sourceQuery);
            appendResultXml(ob, i, result, faqMeta, queries, seen, sourceQuery);
            long totalChunks = knowledgeTotalMap.getOrDefault(nz(result.sr.knowledgeId), 0L);
            formattedResults.add(formattedResult(i, result, faqMeta, sourceQuery, totalChunks));
        }

        ob.append("<retrievalStatistics>\n");
        for (Map.Entry<String, Set<Integer>> e : knowledgeChunkMap.entrySet()) {
            String knowledgeId = e.getKey();
            long totalChunks = knowledgeTotalMap.getOrDefault(knowledgeId, 0L);
            int retrievedCount = e.getValue().size();
            String title = knowledgeTitleMap.getOrDefault(knowledgeId, "");
            if (totalChunks > 0) {
                long remaining = totalChunks - retrievedCount;
                double percentage = retrievedCount / (double) totalChunks * 100;
                ob.append(String.format(Locale.ROOT,
                        "<documentStat knowledgeId=\"%s\" title=\"%s\" totalChunks=\"%d\" retrieved=\"%d\" remaining=\"%d\" coverage=\"%.1f%%\" />\n",
                        FaqSnippet.xmlEscape(knowledgeId), FaqSnippet.xmlEscape(title), totalChunks,
                        retrievedCount, remaining, percentage));
            }
        }
        ob.append("</retrievalStatistics>\n");
        ob.append("</searchResults>");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("knowledgeBaseIds", kbsToSearch);
        data.put("results", formattedResults);
        data.put("count", formattedResults.size());
        data.put("kbCounts", kbCounts);
        data.put("displayType", "search_results");
        if (!queries.isEmpty()) {
            data.put("queries", queries);
        }

        ToolResult r = new ToolResult();
        r.setSuccess(true);
        r.setOutput(ob.toString());
        r.setData(data);
        return r;
    }


    /** 空结果：固定文案 + 空 data。 */
    private ToolResult emptyResult(List<String> kbsToSearch, List<String> queries) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("knowledgeBaseIds", kbsToSearch);
        data.put("results", List.of());
        data.put("count", 0);
        if (!queries.isEmpty()) {
            data.put("queries", queries);
        }
        String output = String.format(Locale.ROOT,
                "No relevant content found in %d knowledge base(s).\n\n", kbsToSearch.size())
                + "=== ⚠️ CRITICAL - Next Steps ===\n"
                + "- ❌ DO NOT use training data or general knowledge to answer\n"
                + "- ✅ If web_search is enabled: You MUST use web_search to find information\n"
                + "- ✅ If web_search is disabled: State 'I couldn't find relevant information in the knowledge base'\n"
                + "- NEVER fabricate or infer answers - ONLY use retrieved content\n";
        ToolResult r = new ToolResult();
        r.setSuccess(true);
        r.setOutput(output);
        r.setData(data);
        return r;
    }

    /** 单条结果的 XML 片段（已见 / 新命中两条路径）。 */
    private void appendResultXml(StringBuilder ob, int i, ResultWithMeta result,
                                 FaqChunkMetadata faqMeta, List<String> queries,
                                 boolean seen, String sourceQuery) {
        boolean isFAQ = faqMeta != null;
        if (seen) {
            if (isFAQ) {
                ob.append(String.format(Locale.ROOT,
                        "<faq rank=\"%d\" faqId=\"%s\" index=\"%d\" knowledgeBaseId=\"%s\" knowledgeTitle=\"%s\" score=\"%.3f\" sourceQuery=\"%s\" already_seen=\"true\">\n",
                        i + 1, FaqSnippet.xmlEscape(nz(result.sr.id)), result.sr.chunkIndex,
                        FaqSnippet.xmlEscape(nz(result.sr.knowledgeBaseId)),
                        FaqSnippet.xmlEscape(nz(result.sr.knowledgeTitle)),
                        result.sr.score, FaqSnippet.xmlEscape(sourceQuery)));
            } else {
                ob.append(String.format(Locale.ROOT,
                        "<chunk rank=\"%d\" chunkId=\"%s\" chunkIndex=\"%d\" knowledgeId=\"%s\" knowledgeBaseId=\"%s\" knowledgeTitle=\"%s\" score=\"%.3f\" sourceQuery=\"%s\" already_seen=\"true\">\n",
                        i + 1, FaqSnippet.xmlEscape(nz(result.sr.id)), result.sr.chunkIndex,
                        FaqSnippet.xmlEscape(nz(result.sr.knowledgeId)),
                        FaqSnippet.xmlEscape(nz(result.sr.knowledgeBaseId)),
                        FaqSnippet.xmlEscape(nz(result.sr.knowledgeTitle)),
                        result.sr.score, FaqSnippet.xmlEscape(sourceQuery)));
            }
            ob.append("<note>(content omitted, already returned in a previous knowledge_search call this session)</note>\n");
            ob.append(isFAQ ? "</faq>\n" : "</chunk>\n");
            return;
        }
        if (isFAQ) {
            ob.append(String.format(Locale.ROOT,
                    "<faq rank=\"%d\" faqId=\"%s\" index=\"%d\" knowledgeBaseId=\"%s\" knowledgeTitle=\"%s\" score=\"%.3f\" sourceQuery=\"%s\">\n",
                    i + 1, FaqSnippet.xmlEscape(nz(result.sr.id)), result.sr.chunkIndex,
                    FaqSnippet.xmlEscape(nz(result.sr.knowledgeBaseId)),
                    FaqSnippet.xmlEscape(nz(result.sr.knowledgeTitle)),
                    result.sr.score, FaqSnippet.xmlEscape(sourceQuery)));
        } else {
            ob.append(String.format(Locale.ROOT,
                    "<chunk rank=\"%d\" chunkId=\"%s\" chunkIndex=\"%d\" knowledgeId=\"%s\" knowledgeBaseId=\"%s\" knowledgeTitle=\"%s\" score=\"%.3f\" sourceQuery=\"%s\">\n",
                    i + 1, FaqSnippet.xmlEscape(nz(result.sr.id)), result.sr.chunkIndex,
                    FaqSnippet.xmlEscape(nz(result.sr.knowledgeId)),
                    FaqSnippet.xmlEscape(nz(result.sr.knowledgeBaseId)),
                    FaqSnippet.xmlEscape(nz(result.sr.knowledgeTitle)),
                    result.sr.score, FaqSnippet.xmlEscape(sourceQuery)));
        }
        String snippet = "";
        if (faqMeta != null) {
            snippet = FaqSnippet.faqMatchSnippetFromQueries(faqMeta, queries);
        }
        if (snippet.isEmpty()) {
            snippet = extractSnippetForQueries(nz(result.sr.content), queries);
        }
        if (!snippet.isEmpty()) {
            ob.append(String.format(Locale.ROOT, "<matchSnippet>%s</matchSnippet>\n",
                    FaqSnippet.xmlEscape(snippet)));
        }
        // content 原样输出，不做 xmlEscape（输出契约）。
        ob.append(String.format(Locale.ROOT, "<content>%s</content>\n", nz(result.sr.content)));

        if (!nz(result.sr.imageInfo).isEmpty()) {
            for (ImageInfoRow img : parseImageInfoList(result.sr.imageInfo)) {
                String md = DocChunkSupport.buildImageInfoMarkdownWithURL(img.url(), img);
                if (!md.isEmpty()) {
                    ob.append(md).append('\n');
                }
            }
        }
        if (isFAQ) {
            FaqSnippet.writeFaqFieldsXml(ob, faqMeta);
            ob.append("</faq>\n");
        } else {
            ob.append("</chunk>\n");
        }
    }

    /** 单条结果的结构化 data 条目。 */
    private Map<String, Object> formattedResult(int i, ResultWithMeta result,
                                                FaqChunkMetadata faqMeta, String sourceQuery, long totalChunks) {
        Map<String, Object> formatted = new LinkedHashMap<>();
        formatted.put("resultIndex", i + 1);
        formatted.put("content", nz(result.sr.content));
        formatted.put("knowledgeId", nz(result.sr.knowledgeId));
        formatted.put("knowledgeBaseId", nz(result.sr.knowledgeBaseId));
        formatted.put("knowledgeTitle", nz(result.sr.knowledgeTitle));
        if (totalChunks > 0) {
            formatted.put("totalChunks", totalChunks);
        }
        formatted.put("knowledgeMetadata", nz(result.sr.knowledgeCustomMetadata));
        formatted.put("matchType", result.sr.matchType);
        formatted.put("sourceQuery", sourceQuery);
        formatted.put("queryType", nz(result.queryType));
        formatted.put("knowledgeBaseType", nz(result.knowledgeBaseType));

        if (!nz(result.sr.imageInfo).isEmpty()) {
            List<Map<String, String>> imageList = new ArrayList<>();
            for (ImageInfoRow img : parseImageInfoList(result.sr.imageInfo)) {
                Map<String, String> imgData = new LinkedHashMap<>();
                if (!nz(img.url()).isEmpty()) {
                    imgData.put("url", img.url());
                }
                if (!nz(img.caption()).isEmpty()) {
                    imgData.put("caption", img.caption());
                }
                if (!nz(img.ocrText()).isEmpty()) {
                    imgData.put("ocrText", img.ocrText());
                }
                if (!imgData.isEmpty()) {
                    imageList.add(imgData);
                }
            }
            if (!imageList.isEmpty()) {
                formatted.put("images", imageList);
            }
        }
        if (faqMeta != null) {
            formatted.put("faqId", nz(result.sr.id));
            formatted.put("index", result.sr.chunkIndex);
            if (!nz(faqMeta.standardQuestion).isEmpty()) {
                formatted.put("faqStandardQuestion", faqMeta.standardQuestion);
            }
            FaqSnippet.appendSimilarQuestionsToChunkData(formatted, faqMeta.similarQuestions);
            if (faqMeta.answers != null && !faqMeta.answers.isEmpty()) {
                formatted.put("faqAnswers", faqMeta.answers);
            }
        } else {
            formatted.put("chunkId", nz(result.sr.id));
            formatted.put("chunkIndex", result.sr.chunkIndex);
        }
        return formatted;
    }

    /** 单篇文档的总 chunk 数；租户不可解析或后端失败回 0。 */
    private long totalChunksFor(ResultWithMeta result) {
        long effectiveTenantId = tool.searchTargets == null ? 0
                : tool.searchTargets.getTenantIdForKb(nz(result.sr.knowledgeBaseId));
        if (effectiveTenantId == 0) {
            return 0L;
        }
        try {
            return tool.chunkBackend.totalChunks(effectiveTenantId, nz(result.sr.knowledgeId));
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    /** ImageInfo JSON 解析（url/caption/ocr_text 数组）。 */
    static List<ImageInfoRow> parseImageInfoList(String imageInfoJson) {
        List<ImageInfoRow> out = new ArrayList<>();
        JsonNode arr;
        try {
            arr = KnowledgeSearchTool.RecordingSupportHolder.MAPPER.readTree(imageInfoJson);
        } catch (java.io.IOException e) {
            return out;
        }
        if (arr == null || !arr.isArray()) {
            return out;
        }
        for (JsonNode img : arr) {
            out.add(new ImageInfoRow(img.path("url").asText(""),
                    img.path("caption").asText(""), img.path("ocrText").asText("")));
        }
        return out;
    }

    /**
     * query token 最早命中上下文（各 200 runes），
     * 无命中回落前 400 runes + " ..."；单线折叠空格，"... x ..." 包裹。
     */
    static String extractSnippetForQueries(String content, List<String> queries) {
        content = nz(content).trim();
        if (content.isEmpty()) {
            return "";
        }

        List<String> tokens = FaqSnippet.searchQueryTokens(queries);

        String lowered = content.toLowerCase(Locale.ROOT);
        int earliest = -1;
        int earliestEnd = -1;
        for (String tok : tokens) {
            int idx = lowered.indexOf(tok);
            if (idx < 0) {
                continue;
            }
            int end = idx + tok.length();
            if (earliest < 0 || idx < earliest) {
                earliest = idx;
                earliestEnd = end;
            }
        }

        if (earliest < 0) {
            if (content.codePointCount(0, content.length()) > 400) {
                return content.substring(0, content.offsetByCodePoints(0, 400)).trim() + " ...";
            }
            return content;
        }

        String matchStr = content.substring(earliest, earliestEnd);
        String before = content.substring(0, earliest);
        String after = content.substring(earliestEnd);

        String beforeRunes = GrepChunksTool.lastRunes(before, 200);
        String afterRunes = GrepChunksTool.firstRunes(after, 200);

        String snippet = beforeRunes + matchStr + afterRunes;
        snippet = snippet.replace("\n", " ");
        while (snippet.contains("  ")) {
            snippet = snippet.replace("  ", " ");
        }
        return "... " + snippet.trim() + " ...";
    }
}
