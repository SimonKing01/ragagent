package com.ragagent.agenttools.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agenttools.knowledge.FaqSnippet.SimilarQuestionsDisplay;
import com.ragagent.contract.agent.SearchAuth;
import com.ragagent.contract.knowledge.ChunkFacts;
import com.ragagent.contract.knowledge.FaqChunkMetadata;
import com.ragagent.contract.retrieval.SearchTarget;
import com.ragagent.contract.tool.BaseTool;
import com.ragagent.contract.tool.ToolDefinitions;
import com.ragagent.contract.tool.ToolRequest;
import com.ragagent.contract.tool.ToolResult;
import com.ragagent.platform.knowledge.DocChunkSupport;
import com.ragagent.platform.knowledge.DocChunkSupport.ChunkPage;
import com.ragagent.platform.knowledge.DocChunkSupport.KnowledgeInfoReader;
import com.ragagent.platform.knowledge.DocChunkSupport.KnowledgeInfoRow;
import com.ragagent.platform.knowledge.DocChunkSupport.PagedChunks;
import com.ragagent.platform.tool.ToolSchemas;
import com.ragagent.platform.web.JsonMappers;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * get_document_info 工具。
 * 多文档顺序处理，输出按入参序组装（metadata 多键的顺序为实现边界点——探针只用单键）。
 */
public class GetDocumentInfoTool extends BaseTool {

    /** schema 键按字母序：properties < type；description < items < type。 */
    private static final String SCHEMA_JSON = """
            {
              "properties": {
                "faqIds": {
                  "description": "Short cN FAQ chunk IDs from retrieval results. Use instead of knowledge_ids for a single FAQ Q&A.",
                  "items": { "type": "string" },
                  "type": "array"
                },
                "knowledgeIds": {
                  "description": "Short dN document IDs for regular documents",
                  "items": { "type": "string" },
                  "type": "array"
                }
              },
              "type": "object"
            }""";

    private static final String DESCRIPTION =
            "Retrieve detailed metadata information about documents.\n"
                    + "\n"
                    + "## When to Use\n"
                    + "\n"
                    + "Use this tool when:\n"
                    + "- Need to understand document basic information (title, type, size, etc.)\n"
                    + "- Check if document exists and is available\n"
                    + "- Batch query metadata for multiple documents\n"
                    + "- Understand document processing status\n"
                    + "\n"
                    + "Do not use when:\n"
                    + "- Need document content (use knowledge_search)\n"
                    + "- Need specific text chunks (search results already contain full content)\n"
                    + "\n"
                    + "\n"
                    + "## Returned Information\n"
                    + "\n"
                    + "- Basic info: title, description, source type\n"
                    + "- File info: filename, type, size\n"
                    + "- Processing status: whether processed, chunk count\n"
                    + "- Metadata: custom tags and properties\n"
                    + "\n"
                    + "\n"
                    + "## Notes\n"
                    + "\n"
                    + "- Concurrent query for multiple documents provides better performance\n"
                    + "- Returns complete document metadata, not just title\n"
                    + "- Can check document processing status (parse_status)\n"
                    + "\n"
                    + "## IDs\n"
                    + "- knowledge_ids: regular documents, using the short dN IDs from retrieval results\n"
                    + "- faq_ids: individual FAQ entries, using the short cN chunk IDs. Returns the standard question and answers, not the container title.";

    private final KnowledgeInfoReader knowledgeReader;
    private final Function<String, ChunkFacts> chunkById;
    private final PagedChunks pagedChunks;
    private final SearchTarget.SearchTargets searchTargets;

    public GetDocumentInfoTool(KnowledgeInfoReader knowledgeReader, Function<String, ChunkFacts> chunkById,
            PagedChunks pagedChunks, SearchTarget.SearchTargets searchTargets) {
        super(ToolDefinitions.TOOL_GET_DOCUMENT_INFO, DESCRIPTION, ToolSchemas.parse(SCHEMA_JSON));
        this.knowledgeReader = knowledgeReader;
        this.chunkById = chunkById;
        this.pagedChunks = pagedChunks;
        this.searchTargets = searchTargets;
    }

    private static final class DocInfo {
        KnowledgeInfoRow knowledge;
        ChunkFacts chunk;
        FaqChunkMetadata faqMeta;
        int chunkCount;
        String err;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        JsonNode args = JsonMappers.toJsonNode(request.args());

        List<String> knowledgeIDs = jsonStringList(args.get("knowledgeIds"));
        List<String> faqIDs = jsonStringList(args.get("faqIds"));
        if (knowledgeIDs.isEmpty() && faqIDs.isEmpty()) {
            return failure("knowledge_ids or faq_ids is required (non-empty array)");
        }

        Map<String, DocInfo> results = new HashMap<>();

        for (String faqIdRaw : faqIDs) {
            String faqId = faqIdRaw == null ? "" : faqIdRaw.trim();
            if (faqId.isEmpty()) {
                continue;
            }
            DocInfo info = new DocInfo();
            ChunkFacts chunk;
            try {
                chunk = SearchAuth.authorizeDomainChunkInSearchTargets(searchTargets, faqId,
                        chunkById, DocChunkSupport.asScopeReader(knowledgeReader));
            } catch (RuntimeException e) {
                info.err = "FAQ entry is not accessible: " + e.getMessage();
                results.put("faq:" + faqId, info);
                continue;
            }
            FaqChunkMetadata meta = null;
            if (chunk != null && "faq".equals(chunk.chunkType())) {
                meta = FaqSnippet.faqMetadata(chunk);
            }
            info.chunk = chunk;
            info.faqMeta = meta;
            info.chunkCount = 1;
            results.put("faq:" + faqId, info);
        }

        for (String knowledgeId : knowledgeIDs) {
            DocInfo info = new DocInfo();
            KnowledgeInfoRow knowledge;
            try {
                SearchAuth.authorizeKnowledgeInSearchTargets(searchTargets, knowledgeId,
                        DocChunkSupport.asScopeReader(knowledgeReader));
                knowledge = knowledgeReader == null ? null : knowledgeReader.byIdOnly(knowledgeId);
            } catch (RuntimeException e) {
                info.err = "failed to get document info: " + e.getMessage();
                results.put(knowledgeId, info);
                continue;
            }
            if (knowledge == null) {
                info.err = "failed to get document info: document " + knowledgeId + " not found: empty result";
                results.put(knowledgeId, info);
                continue;
            }
            long total;
            try {
                ChunkPage page = pagedChunks == null
                        ? null
                        : pagedChunks.listPaged(knowledge.tenantId(), knowledgeId, 1, 1);
                total = page == null ? 0 : page.total();
            } catch (RuntimeException e) {
                info.err = "failed to get document info: " + e.getMessage();
                results.put(knowledgeId, info);
                continue;
            }
            info.knowledge = knowledge;
            info.chunkCount = (int) total;
            results.put(knowledgeId, info);
        }

        int requested = knowledgeIDs.size() + faqIDs.size();
        List<DocInfo> successDocs = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (String knowledgeId : knowledgeIDs) {
            DocInfo result = results.get(knowledgeId);
            if (result == null) {
                errors.add(knowledgeId + ": not found");
                continue;
            }
            if (result.err != null) {
                errors.add(knowledgeId + ": " + result.err);
            } else if (result.knowledge != null) {
                successDocs.add(result);
            }
        }
        for (String faqIdRaw : faqIDs) {
            String faqId = faqIdRaw == null ? "" : faqIdRaw.trim();
            if (faqId.isEmpty()) {
                continue;
            }
            DocInfo result = results.get("faq:" + faqId);
            if (result == null) {
                errors.add("faq:" + faqId + ": not found");
                continue;
            }
            if (result.err != null) {
                errors.add("faq:" + faqId + ": " + result.err);
            } else if (result.chunk != null) {
                successDocs.add(result);
            }
        }

        if (successDocs.isEmpty()) {
            return failure("Failed to retrieve any document info. Errors: " + sliceText(errors));
        }

        StringBuilder output = new StringBuilder();
        output.append("=== Document Info ===\n\n");
        output.append(String.format("Successfully retrieved %d / %d entries\n\n",
                successDocs.size(), requested));

        if (!errors.isEmpty()) {
            output.append("=== Partial Failures ===\n");
            for (String errMsg : errors) {
                output.append("  - ").append(errMsg).append('\n');
            }
            output.append('\n');
        }

        List<Map<String, Object>> formattedDocs = new ArrayList<>();
        for (int i = 0; i < successDocs.size(); i++) {
            DocInfo doc = successDocs.get(i);
            output.append(String.format("[Entry #%d]\n", i + 1));

            if (doc.chunk != null) {
                Map<String, Object> formatted = formatFAQEntryInfo(output, doc.chunk, doc.faqMeta);
                formattedDocs.add(formatted);
                continue;
            }

            KnowledgeInfoRow k = doc.knowledge;
            output.append(String.format("  ID:           %s\n", k.id()));
            output.append(String.format("  Title:        %s\n", k.title()));

            if (k.description() != null && !k.description().isEmpty()) {
                output.append(String.format("  Description:  %s\n", k.description()));
            }

            output.append(String.format("  Source:       %s\n", formatSource(k.type(), k.source())));

            if (k.fileName() != null && !k.fileName().isEmpty()) {
                output.append(String.format("  File Name:    %s\n", k.fileName()));
                output.append(String.format("  File Type:    %s\n", k.fileType()));
                output.append(String.format("  File Size:    %s\n", formatFileSize(k.fileSize())));
            }

            output.append(String.format("  Parse Status: %s\n", formatParseStatus(k.parseStatus())));
            output.append(String.format("  Chunk Count:  %d\n", doc.chunkCount));

            if (k.metadata() != null && !k.metadata().isEmpty()) {
                output.append("  Metadata:\n");
                for (Map.Entry<String, Object> e : k.metadata().entrySet()) {
                    output.append(String.format("    - %s: %s\n", e.getKey(),
                            DocChunkSupport.valueText(e.getValue())));
                }
            }

            output.append('\n');

            Map<String, Object> formatted = new LinkedHashMap<>();
            formatted.put("knowledgeId", k.id());
            formatted.put("title", k.title());
            formatted.put("description", k.description());
            formatted.put("type", k.type());
            formatted.put("source", k.source());
            formatted.put("fileName", k.fileName());
            formatted.put("fileType", k.fileType());
            formatted.put("fileSize", k.fileSize());
            formatted.put("parseStatus", k.parseStatus());
            formatted.put("chunkCount", doc.chunkCount);
            formatted.put("metadata", DocChunkSupport.knowledgeMetadataMap(k.metadata()));
            formatted.put("isFaq", false);
            formattedDocs.add(formatted);
        }

        String firstTitle = "";
        if (!formattedDocs.isEmpty()) {
            Object t = formattedDocs.get(0).get("title");
            if (t instanceof String s) {
                firstTitle = s;
            }
        }

        ToolResult toolResult = new ToolResult();
        toolResult.setSuccess(true);
        toolResult.setOutput(output.toString());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("documents", formattedDocs);
        data.put("totalDocs", successDocs.size());
        data.put("requested", requested);
        data.put("errors", errors.isEmpty() ? null : errors);
        data.put("displayType", "document_info");
        data.put("title", firstTitle);
        toolResult.setData(data);
        return toolResult;
    }

    private static Map<String, Object> formatFAQEntryInfo(StringBuilder output, ChunkFacts chunk,
            FaqChunkMetadata meta) {
        String title = FaqSnippet.faqStandardQuestion(chunk);
        if ((title == null || title.isEmpty()) && meta != null) {
            title = meta.standardQuestion == null ? "" : meta.standardQuestion.trim();
        }
        if (title == null || title.isEmpty()) {
            title = "FAQ Entry";
        }

        output.append(String.format("  FAQ ID:       %s\n", chunk.id()));
        output.append(String.format("  Question:     %s\n", title));
        if (chunk.knowledgeId() != null && !chunk.knowledgeId().isEmpty()) {
            output.append(String.format("  Container ID: %s\n", chunk.knowledgeId()));
        }
        if (meta != null && meta.answers != null && !meta.answers.isEmpty()) {
            output.append("  Answers:\n");
            for (String ans : meta.answers) {
                output.append(String.format("    - %s\n", ans));
            }
        }
        if (meta != null && meta.similarQuestions != null && !meta.similarQuestions.isEmpty()) {
            SimilarQuestionsDisplay display =
                    FaqSnippet.truncateSimilarQuestionsForDisplay(meta.similarQuestions);
            output.append("  Similar Questions:\n");
            for (String sq : display.display()) {
                output.append(String.format("    - %s\n", sq));
            }
            if (display.omitted() > 0) {
                output.append(String.format("    ... and %d more omitted\n", display.omitted()));
            }
        }
        output.append('\n');

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("faqId", chunk.id());
        entry.put("knowledgeId", chunk.knowledgeId());
        entry.put("title", title);
        entry.put("faqQuestion", title);
        entry.put("type", "faq");
        entry.put("isFaq", true);
        entry.put("chunkCount", 1);
        if (meta != null) {
            if (meta.answers != null && !meta.answers.isEmpty()) {
                entry.put("faqAnswers", meta.answers);
            }
            FaqSnippet.appendSimilarQuestionsToChunkData(entry, meta.similarQuestions);
        }
        return entry;
    }

    static String formatSource(String knowledgeType, String source) {
        if ("file".equals(knowledgeType)) {
            return "File Upload";
        }
        if ("url".equals(knowledgeType)) {
            return "URL: " + source;
        }
        if ("passage".equals(knowledgeType)) {
            return "Text Input";
        }
        return knowledgeType;
    }

    static String formatFileSize(long size) {
        if (size == 0) {
            return "Unknown";
        }
        final long unit = 1024;
        if (size < unit) {
            return size + " B";
        }
        long div = unit;
        int exp = 0;
        for (long n = size / unit; n >= unit; n /= unit) {
            div *= unit;
            exp++;
        }
        return String.format(Locale.US, "%.1f %cB", (double) size / div, "KMGTPE".charAt(exp));
    }

    static String formatParseStatus(String status) {
        switch (status == null ? "" : status) {
            case "pending":
                return "Pending";
            case "processing":
                return "Processing";
            case "completed":
            case "success":
                return "Completed";
            case "failed":
                return "Failed";
            default:
                return status;
        }
    }

    private static List<String> jsonStringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return out;
        }
        for (JsonNode item : node) {
            if (item.isTextual()) {
                out.add(item.asText());
            }
        }
        return out;
    }

    /** 列表的输出形态：" [a b c]"。 */
    private static String sliceText(List<String> items) {
        return "[" + String.join(" ", items) + "]";
    }

    private static ToolResult failure(String message) {
        ToolResult result = new ToolResult();
        result.setSuccess(false);
        result.setError(message);
        return result;
    }
}
