package com.ragagent.agenttools.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.contract.agent.SearchAuth;
import com.ragagent.contract.knowledge.ChunkFacts;
import com.ragagent.contract.retrieval.SearchTarget;
import com.ragagent.contract.tool.BaseTool;
import com.ragagent.contract.tool.ToolDefinitions;
import com.ragagent.contract.tool.ToolRequest;
import com.ragagent.contract.tool.ToolResult;
import com.ragagent.platform.knowledge.DocChunkSupport;
import com.ragagent.platform.knowledge.DocChunkSupport.ChunkPage;
import com.ragagent.platform.knowledge.DocChunkSupport.ImageInfoCollector;
import com.ragagent.platform.knowledge.DocChunkSupport.ImageInfoRow;
import com.ragagent.platform.knowledge.DocChunkSupport.KnowledgeInfoReader;
import com.ragagent.platform.knowledge.DocChunkSupport.KnowledgeInfoRow;
import com.ragagent.platform.knowledge.DocChunkSupport.PagedChunks;
import com.ragagent.platform.tool.ToolSchemas;
import com.ragagent.platform.web.JsonMappers;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * list_knowledge_chunks 工具。
 * faq_id/chunk_id 单块路径经 {@code chunkById} seam；
 * 图片富化经 {@link ImageInfoCollector}。
 */
public class ListKnowledgeChunksTool extends BaseTool {

    /** schema 键按字母序：properties < type；属性名与字段键均字母序。 */
    private static final String SCHEMA_JSON = """
            {
              "properties": {
                "chunkId": {
                  "description": "Short cN ID for one non-FAQ chunk",
                  "type": "string"
                },
                "faqId": {
                  "description": "Short cN FAQ chunk ID. Use for FAQ hits instead of the parent dN document ID.",
                  "type": "string"
                },
                "knowledgeId": {
                  "description": "Short dN document ID to list all chunks",
                  "type": "string"
                },
                "limit": {
                  "default": 20,
                  "description": "How many consecutive blocks to read from startChunkIndex (default 20, max 100; use 20-30 for tables, 5 or more when inspecting one hit)",
                  "maximum": 100,
                  "minimum": 1,
                  "type": "integer"
                },
                "startChunkIndex": {
                  "default": 0,
                  "description": "0-based block index to start from (the index shown on grep/search chunks). Exact, not a page number.",
                  "minimum": 0,
                  "type": "integer"
                }
              },
              "type": "object"
            }""";

    private static final String DESCRIPTION =
            "Retrieve full chunk content for a document or a single FAQ entry.\n"
                    + "\n"
                    + "## Use after grep_chunks or knowledge_search:\n"
                    + "- **FAQ hit** (type faq): list_knowledge_chunks(faqId=\"cN\") — reads that one FAQ chunk with answers from metadata.\n"
                    + "- **Document hit**: list_knowledge_chunks(knowledgeId=\"dN\") — pages through all chunks.\n"
                    + "\n"
                    + "## Parameters (Exactly one of chunkId, faqId, knowledgeId is required):\n"
                    + "- faqId (optional): Short cN ID for an FAQ chunk from grep_chunks / knowledge_search.\n"
                    + "- chunkId (optional): Short cN ID for a single non-FAQ chunk.\n"
                    + "- knowledgeId (optional): Short dN document ID to page through all chunks.\n"
                    + "- startChunkIndex + limit (only with knowledgeId): read `limit` consecutive blocks starting at the exact 0-based `startChunkIndex` (default 0/20, max 100). To read one block use limit=1; to read a table region use limit=20-30. For the next region, pass nextStartChunkIndex from the previous result's <pagination>.\n"
                    + "\n"
                    + "## Paging guidance:\n"
                    + "- Use grep_chunks first to locate the relevant chunks; do not use this tool to enumerate an entire document.\n"
                    + "- For table-like documents (参数配置表), read 20-30 blocks per call; a limit like 10 wastes turns.\n"
                    + "- Do not read chunks one by one: per-block calls (limit=1) waste turns and miss adjacent context.\n"
                    + "- Keep step == limit when paging forward; a smaller step re-reads overlapping blocks.\n"
                    + "\n"
                    + "## Output:\n"
                    + "Full chunk content. FAQ entries include <faq> with <answer> from metadata.";

    private final KnowledgeInfoReader knowledgeReader;
    private final Function<String, ChunkFacts> chunkById;
    private final PagedChunks pagedChunks;
    private final ImageInfoCollector imageCollector;
    private final SearchTarget.SearchTargets searchTargets;

    public ListKnowledgeChunksTool(KnowledgeInfoReader knowledgeReader,
            Function<String, ChunkFacts> chunkById, PagedChunks pagedChunks,
            ImageInfoCollector imageCollector, SearchTarget.SearchTargets searchTargets) {
        super(ToolDefinitions.TOOL_LIST_KNOWLEDGE_CHUNKS, DESCRIPTION, ToolSchemas.parse(SCHEMA_JSON));
        this.knowledgeReader = knowledgeReader;
        this.chunkById = chunkById;
        this.pagedChunks = pagedChunks;
        this.imageCollector = imageCollector;
        this.searchTargets = searchTargets;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        JsonNode args = JsonMappers.toJsonNode(request.args());

        String chunkId = args.path("faqId").asText("").trim();
        if (chunkId.isEmpty()) {
            chunkId = args.path("chunkId").asText("").trim();
        }
        String knowledgeId = args.path("knowledgeId").asText("").trim();
        if (!chunkId.isEmpty()) {
            return executeByChunkId(chunkId);
        }
        if (knowledgeId.isEmpty()) {
            return failure("one of faq_id, chunk_id, or knowledge_id is required");
        }
        return executeByKnowledgePage(knowledgeId, args);
    }

    /** knowledgeId 分页路径：offset 是精确块起点，不按 limit 取整。 */
    private ToolResult executeByKnowledgePage(String knowledgeId, JsonNode args) {
        KnowledgeInfoRow knowledge;
        try {
            SearchAuth.authorizeKnowledgeInSearchTargets(searchTargets, knowledgeId,
                    DocChunkSupport.asScopeReader(knowledgeReader));
            knowledge = knowledgeReader == null ? null : knowledgeReader.byIdOnly(knowledgeId);
        } catch (RuntimeException e) {
            return failure("Knowledge is not accessible: " + e.getMessage());
        }
        if (knowledge == null) {
            return failure("Knowledge is not accessible: document " + knowledgeId + " not found: empty result");
        }

        long effectiveTenantId = knowledge.tenantId();

        int chunkLimit = 20;
        if (args.path("limit").asInt(0) > 0) {
            chunkLimit = args.path("limit").asInt(0);
        }
        int startChunkIndex = args.path("startChunkIndex").asInt(-1);
        if (startChunkIndex < 0) {
            // 兼容旧参数名 offset（不写入 schema，仅过渡期读取）
            startChunkIndex = args.path("offset").asInt(0);
        }
        if (startChunkIndex < 0) {
            startChunkIndex = 0;
        }

        int page = startChunkIndex / chunkLimit + 1;
        int pageSize = chunkLimit;

        ChunkPage result;
        try {
            // 精确起点：startChunkIndex 是块号，不按 limit 取整到页边界
            result = pagedChunks == null ? null
                    : pagedChunks.listRange(effectiveTenantId, knowledgeId, startChunkIndex, chunkLimit);
        } catch (RuntimeException e) {
            return failure("failed to list chunks: " + e.getMessage());
        }
        if (result == null || result.chunks() == null) {
            return failure("chunk query returned no data");
        }
        List<ChunkFacts> chunks = result.chunks();
        long totalChunks = result.total();
        int fetched = chunks.size();

        // 越界指引
        if (fetched == 0 && totalChunks > 0 && startChunkIndex >= totalChunks) {
            long suggestedOffset = totalChunks - chunkLimit;
            if (suggestedOffset < 0) {
                suggestedOffset = 0;
            }
            ToolResult r = new ToolResult();
            r.setSuccess(false);
            r.setError(String.format(
                    "startChunkIndex %d is out of range: document has only %d chunks (valid range: 0..%d)."
                            + " Retry with startChunkIndex=%d (or any value < %d).",
                    startChunkIndex, totalChunks, totalChunks - 1, suggestedOffset, totalChunks));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("knowledgeId", knowledgeId);
            data.put("totalChunks", totalChunks);
            data.put("requestedOffset", startChunkIndex);
            data.put("requestedLimit", chunkLimit);
            data.put("suggestedOffset", suggestedOffset);
            r.setData(data);
            return r;
        }

        // 图片富化（惰性）
        if (fetched > 0) {
            DocChunkSupport.enrichChunkImageInfo(imageCollector, effectiveTenantId, chunks);
        }

        String knowledgeTitle = lookupKnowledgeTitle(knowledgeId);
        String output = buildOutput(knowledgeId, knowledgeTitle, totalChunks, fetched, chunks);

        List<Map<String, Object>> formattedChunks = new ArrayList<>();
        for (int idx = 0; idx < chunks.size(); idx++) {
            ChunkFacts c = chunks.get(idx);
            Map<String, Object> chunkData = new LinkedHashMap<>();
            chunkData.put("seq", idx + 1);
            chunkData.put("chunkId", c.id());
            chunkData.put("chunkIndex", c.chunkIndex());
            chunkData.put("content", nz(c.content()));
            chunkData.put("chunkType", c.chunkType());
            chunkData.put("knowledgeId", nz(c.knowledgeId()));
            chunkData.put("knowledgeBase", nz(c.knowledgeBaseId()));
            chunkData.put("startAt", c.startAt());
            chunkData.put("endAt", c.endAt());
            chunkData.put("parentChunkId", nz(c.parentChunkId()));

            FaqSnippet.appendFaqChunkData(chunkData, c);
            FaqSnippet.normalizeFaqChunkDataMap(chunkData, c);

            String imageInfo = c.imageInfo();
            if (imageInfo != null && !imageInfo.isEmpty()) {
                List<ImageInfoRow> imageInfos = DocChunkSupport.parseImageInfoList(imageInfo);
                if (imageInfos != null && !imageInfos.isEmpty()) {
                    List<Map<String, String>> imageList = new ArrayList<>();
                    for (ImageInfoRow img : imageInfos) {
                        Map<String, String> imgData = new LinkedHashMap<>();
                        if (img.url() != null && !img.url().isEmpty()) {
                            imgData.put("url", img.url());
                        }
                        if (img.caption() != null && !img.caption().isEmpty()) {
                            imgData.put("caption", img.caption());
                        }
                        if (img.ocrText() != null && !img.ocrText().isEmpty()) {
                            imgData.put("ocrText", img.ocrText());
                        }
                        if (!imgData.isEmpty()) {
                            imageList.add(imgData);
                        }
                    }
                    if (!imageList.isEmpty()) {
                        chunkData.put("images", imageList);
                    }
                }
            }

            formattedChunks.add(chunkData);
        }

        ToolResult toolResult = new ToolResult();
        toolResult.setSuccess(true);
        toolResult.setOutput(output);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("displayType", "knowledge_chunks_list");
        data.put("knowledgeId", knowledgeId);
        data.put("knowledgeTitle", knowledgeTitle);
        data.put("totalChunks", totalChunks);
        data.put("fetchedChunks", fetched);
        data.put("startChunkIndex", startChunkIndex);
        data.put("page", page);
        data.put("pageSize", pageSize);
        data.put("chunks", formattedChunks);
        toolResult.setData(data);
        return toolResult;
    }

    /** faq_id / chunk_id 单块路径。 */
    private ToolResult executeByChunkId(String chunkId) {
        ChunkFacts chunk;
        try {
            chunk = SearchAuth.authorizeDomainChunkInSearchTargets(searchTargets, chunkId,
                    chunkById, DocChunkSupport.asScopeReader(knowledgeReader));
        } catch (RuntimeException e) {
            return failure("chunk is not accessible: " + e.getMessage());
        }
        if (chunk == null) {
            return failure("chunk is not accessible: chunk " + chunkId + " not found: empty result");
        }

        List<ChunkFacts> chunks = List.of(chunk);
        String imageInfo = chunk.imageInfo();
        if (imageInfo == null || imageInfo.isEmpty()) {
            long effectiveTenantId = searchTargets == null
                    ? 0
                    : searchTargets.getTenantIdForKb(chunk.knowledgeBaseId());
            if (effectiveTenantId > 0 && imageCollector != null) {
                Map<String, String> infoMap = imageCollector.collect(effectiveTenantId,
                        List.of(chunk.id()));
                if (infoMap != null) {
                    String merged = infoMap.get(chunk.id());
                    if (merged != null && !merged.isEmpty()) {
                        // L1 载荷不可变：富化 = 复制-替换
                        chunk = DocChunkSupport.withImageInfo(chunk, merged);
                        chunks = List.of(chunk);
                    }
                }
            }
        }

        String knowledgeTitle = lookupKnowledgeTitle(chunk.knowledgeId());
        String output = buildOutput(chunk.knowledgeId(), knowledgeTitle, 1, 1, chunks);

        List<Map<String, Object>> formattedChunks = List.of(singleChunkData(chunk, 1));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("displayType", "knowledge_chunks_list");
        data.put("knowledgeId", chunk.knowledgeId());
        data.put("knowledgeTitle", knowledgeTitle);
        data.put("totalChunks", 1L);
        data.put("fetchedChunks", 1);
        data.put("page", 1);
        data.put("pageSize", 1);
        data.put("chunks", formattedChunks);
        data.put("faqId", chunk.id());
        data.put("singleChunk", true);
        String q = FaqSnippet.faqStandardQuestion(chunk);
        if (q != null && !q.isEmpty()) {
            data.put("faqQuestion", q);
        }

        ToolResult toolResult = new ToolResult();
        toolResult.setSuccess(true);
        toolResult.setOutput(output);
        toolResult.setData(data);
        return toolResult;
    }

    /** 单块 data 条目（chunkId 单条路径）。 */
    private static Map<String, Object> singleChunkData(ChunkFacts chunk, int seq) {
        Map<String, Object> chunkData = new LinkedHashMap<>();
        chunkData.put("seq", seq);
        chunkData.put("chunkId", chunk.id());
        chunkData.put("chunkIndex", chunk.chunkIndex());
        chunkData.put("content", nz(chunk.content()));
        chunkData.put("chunkType", chunk.chunkType());
        chunkData.put("knowledgeId", nz(chunk.knowledgeId()));
        chunkData.put("knowledgeBase", nz(chunk.knowledgeBaseId()));
        FaqSnippet.appendFaqChunkData(chunkData, chunk);
        FaqSnippet.normalizeFaqChunkDataMap(chunkData, chunk);
        return chunkData;
    }

    /** 取文档标题（trim 后返回；失败/无服务 → ""）。 */
    private String lookupKnowledgeTitle(String knowledgeId) {
        if (knowledgeReader == null) {
            return "";
        }
        KnowledgeInfoRow knowledge;
        try {
            knowledge = knowledgeReader.byIdOnly(knowledgeId);
        } catch (RuntimeException e) {
            return "";
        }
        if (knowledge == null || knowledge.title() == null) {
            return "";
        }
        return knowledge.title().trim();
    }

    /** XML 输出。 */
    private String buildOutput(String knowledgeId, String knowledgeTitle, long total,
            int fetched, List<ChunkFacts> chunks) {
        StringBuilder b = new StringBuilder();

        String titleAttr = "";
        if (knowledgeTitle != null && !knowledgeTitle.isEmpty()) {
            titleAttr = String.format(" title=\"%s\"", knowledgeTitle);
        }
        b.append(String.format("<knowledgeChunks knowledgeId=\"%s\"%s total=\"%d\" fetched=\"%d\">\n",
                knowledgeId, titleAttr, total, fetched));

        if (fetched == 0) {
            b.append("</knowledgeChunks>");
            return b.toString();
        }

        for (ChunkFacts c : chunks) {
            if ("faq".equals(c.chunkType())) {
                FaqSnippet.writeFaqEntryXml(b, c);
                writeChunkImagesMarkdown(b, c);
                continue;
            }

            String q = FaqSnippet.faqStandardQuestion(c);
            if (q != null && !q.isEmpty()) {
                b.append(String.format("<chunk chunkId=\"%s\" chunkIndex=\"%d\" type=\"%s\" question=\"%s\">\n",
                        c.id(), c.chunkIndex(), c.chunkType(), FaqSnippet.xmlEscape(q)));
            } else {
                b.append(String.format("<chunk chunkId=\"%s\" chunkIndex=\"%d\" type=\"%s\">\n",
                        c.id(), c.chunkIndex(), c.chunkType()));
            }
            b.append(String.format("<content>%s</content>\n", summarizeContent(c.content())));
            writeChunkImagesMarkdown(b, c);
            b.append("</chunk>\n");
        }

        if (fetched < total) {
            b.append(String.format("<pagination remaining=\"%d\" />\n", total - fetched));
        }

        b.append("</knowledgeChunks>");
        return b.toString();
    }

    /** 逐图 markdown + "\n"。 */
    private static void writeChunkImagesMarkdown(StringBuilder b, ChunkFacts c) {
        if (c == null) {
            return;
        }
        String imageInfo = c.imageInfo();
        if (imageInfo == null || imageInfo.isEmpty()) {
            return;
        }
        List<ImageInfoRow> imageInfos = DocChunkSupport.parseImageInfoList(imageInfo);
        if (imageInfos == null || imageInfos.isEmpty()) {
            return;
        }
        for (ImageInfoRow img : imageInfos) {
            String md = DocChunkSupport.buildImageInfoMarkdownWithURL(img.url(), img);
            if (!md.isEmpty()) {
                b.append(md).append('\n');
            }
        }
    }

    /** 字符串字段 null 规范化为 ""（输出契约）。 */
    static String nz(String v) {
        return v == null ? "" : v;
    }

    /** 内容摘要。 */
    static String summarizeContent(String content) {
        String cleaned = content == null ? "" : content.trim();
        if (cleaned.isEmpty()) {
            return "(empty)";
        }
        return cleaned.trim();
    }

    private static ToolResult failure(String message) {
        ToolResult result = new ToolResult();
        result.setSuccess(false);
        result.setError(message);
        return result;
    }
}
