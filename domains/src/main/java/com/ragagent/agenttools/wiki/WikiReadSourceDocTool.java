package com.ragagent.agenttools.wiki;

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
import com.ragagent.platform.knowledge.DocChunkSupport.KnowledgeInfoReader;
import com.ragagent.platform.knowledge.DocChunkSupport.KnowledgeInfoRow;
import com.ragagent.platform.knowledge.DocChunkSupport.PagedChunks;
import com.ragagent.platform.tool.ToolSchemas;
import com.ragagent.platform.web.JsonMappers;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * wiki_read_source_doc 工具。
 *
 * <p>{@code searchTargets != null} 即启用 scope 强制（空 scope 失败关闭）。
 * 正则用 {@code (?i)+query} 编译；正则方言在交集语法外行为不同
 * （编译失败文案差异列入报告实现边界）。</p>
 */
public class WikiReadSourceDocTool extends BaseTool {

    private static final String SCHEMA_JSON = """
            {
              "type": "object",
              "properties": {
                "knowledgeId": {
                  "type": "string",
                  "description": "The short dN source document ID from the <sources> block"
                },
                "query": {
                  "type": "string",
                  "description": "Optional: A regex query to filter the document chunks. Use this to find specific quotes or details efficiently. Remember to double-escape backslashes for JSON: write \\"C\\\\\\\\+\\\\\\\\+\\" (NOT \\"C\\\\+\\\\+\\") and \\"\\\\\\\\d+\\" (NOT \\"\\\\d+\\")."
                },
                "startChunkIndex": {
                  "type": "integer",
                  "description": "Optional: The starting chunk index (1-based) to read a specific range."
                },
                "endChunkIndex": {
                  "type": "integer",
                  "description": "Optional: The ending chunk index (1-based) to read a specific range. Must be >= start_chunk_index."
                }
              },
              "required": ["knowledgeId"]
            }""";

    private static final String DESCRIPTION =
            "Read or search within a specific source document to drill down for details omitted from the wiki.\n"
                    + "Provide the knowledge_id from the <sources> block.\n"
                    + "You can EITHER search using a regex query OR fetch a specific contiguous range of chunks using start_chunk_index and end_chunk_index (useful for expanding context around a known chunk).\n"
                    + "If neither query nor range is provided, it returns the beginning of the document.";

    private final KnowledgeInfoReader knowledgeReader;
    private final PagedChunks pagedChunks;
    private final ImageInfoCollector imageCollector;
    private final SearchTarget.SearchTargets searchTargets;

    public WikiReadSourceDocTool(KnowledgeInfoReader knowledgeReader, PagedChunks pagedChunks,
            ImageInfoCollector imageCollector,
            SearchTarget.SearchTargets searchTargets) {
        super(ToolDefinitions.TOOL_WIKI_READ_SOURCE_DOC, DESCRIPTION, ToolSchemas.parse(SCHEMA_JSON));
        this.knowledgeReader = knowledgeReader;
        this.pagedChunks = pagedChunks;
        this.imageCollector = imageCollector;
        this.searchTargets = searchTargets;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        JsonNode args = JsonMappers.toJsonNode(request.args());

        String knowledgeId = args.path("knowledgeId").asText("").trim();
        if (knowledgeId.isEmpty()) {
            return failure("knowledge_id is required");
        }
        String query = args.path("query").asText("");
        int startChunkIndex = args.path("startChunkIndex").asInt(0);
        int endChunkIndex = args.path("endChunkIndex").asInt(0);

        KnowledgeInfoRow knowledge;
        if (searchTargets != null) {
            try {
                SearchAuth.authorizeKnowledgeInSearchTargets(
                        searchTargets, knowledgeId, DocChunkSupport.asScopeReader(knowledgeReader));
            } catch (RuntimeException e) {
                return failure("Document not found: " + e.getMessage());
            }
            // 授权用适配视图丢了富字段，重新取一次（seam 无状态，结果一致）
            knowledge = knowledgeReader == null ? null : knowledgeReader.byIdOnly(knowledgeId);
        } else {
            try {
                knowledge = knowledgeReader == null ? null : knowledgeReader.byIdOnly(knowledgeId);
            } catch (RuntimeException e) {
                return failure("Document not found: " + e.getMessage());
            }
        }
        if (knowledge == null) {
            return failure("Document not found: knowledge service returned an empty result");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<sourceDocument>\n<metadata>\n");
        sb.append("<title>").append(knowledge.title()).append("</title>\n");
        sb.append("<knowledgeId>").append(knowledgeId).append("</knowledgeId>\n");

        boolean hasRange = startChunkIndex > 0;
        Pattern re = null;

        if (hasRange) {
            if (endChunkIndex < startChunkIndex) {
                endChunkIndex = startChunkIndex + 10; // 默认窗口
            }
            if (endChunkIndex - startChunkIndex > 50) {
                endChunkIndex = startChunkIndex + 50; // 最多 50 块
            }
            sb.append("<chunkRange start=\"").append(startChunkIndex).append("\" end=\"")
                    .append(endChunkIndex).append("\"/>\n");
        } else if (!query.isEmpty()) {
            try {
                re = Pattern.compile("(?i)" + query);
            } catch (java.util.regex.PatternSyntaxException e) {
                return failure("Invalid regex query '" + query + "': " + e.getMessage());
            }
            sb.append("<query>").append(query).append("</query>\n");
        }

        int matchCount = 0;
        int pageSize = 100;
        int page = 1;
        if (hasRange) {
            page = (startChunkIndex - 1) / pageSize + 1;
        }

        StringBuilder chunksOutput = new StringBuilder();
        List<Map<String, Object>> formattedChunks = new ArrayList<>();
        long totalChunks = 0;
        boolean reachedMax = false;

        ChunkFacts prevChunk = null;
        boolean forceOutputNext = false;
        Set<Integer> outputtedIndices = new HashSet<>();

        final String knowledgeTitle = knowledge.title();
        final String knowledgeBaseId = knowledge.knowledgeBaseId();
        final long tenantId = knowledge.tenantId();

        java.util.function.BiConsumer<ChunkFacts, String> appendFormattedChunk = (chunk, content) -> {
            if (chunk == null) {
                return;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("chunkId", chunk.id());
            m.put("chunkIndex", chunk.chunkIndex());
            m.put("chunkType", chunk.chunkType());
            m.put("content", content);
            m.put("knowledgeId", knowledgeId);
            m.put("knowledgeBase", knowledgeBaseId);
            m.put("knowledgeTitle", knowledgeTitle);
            formattedChunks.add(m);
        };

        final Pattern reFinal = re;
        final int startFinal = startChunkIndex;
        final int endFinal = endChunkIndex;
        final boolean hasRangeFinal = hasRange;

        outer:
        while (true) {
            ChunkPage result;
            try {
                result = pagedChunks.listPaged(tenantId, knowledgeId, page, pageSize);
            } catch (RuntimeException e) {
                return failure("Failed to list chunks: " + e.getMessage());
            }
            List<ChunkFacts> chunks = result == null ? null : result.chunks();
            long total = result == null ? 0 : result.total();

            if (page == 1) {
                totalChunks = total;
            }

            if (chunks == null || chunks.isEmpty()) {
                break;
            }

            DocChunkSupport.enrichChunkImageInfo(imageCollector, tenantId, chunks);

            for (ChunkFacts c : chunks) {
                int chunkNum = c.chunkIndex() + 1;
                String chunkContent = DocChunkSupport.enrichChunkContent(c);

                if (hasRangeFinal) {
                    if (chunkNum < startFinal) {
                        continue;
                    }
                    if (chunkNum > endFinal) {
                        reachedMax = true;
                        break outer;
                    }
                    chunksOutput.append("<chunk index=\"").append(chunkNum)
                            .append("\" type=\"range\">\n").append(chunkContent).append("\n</chunk>\n");
                    appendFormattedChunk.accept(c, chunkContent);
                    matchCount++;
                    continue;
                }

                boolean isMatch = reFinal == null || reFinal.matcher(chunkContent).find();

                if (isMatch) {
                    matchCount++;

                    if (reFinal != null && prevChunk != null
                            && !outputtedIndices.contains(prevChunk.chunkIndex())) {
                        String prevContent = DocChunkSupport.enrichChunkContent(prevChunk);
                        chunksOutput.append("<chunk index=\"").append(prevChunk.chunkIndex() + 1)
                                .append("\" type=\"context_before\">\n").append(prevContent)
                                .append("\n</chunk>\n");
                        appendFormattedChunk.accept(prevChunk, prevContent);
                        outputtedIndices.add(prevChunk.chunkIndex());
                    }

                    if (!outputtedIndices.contains(c.chunkIndex())) {
                        String matchAttr = reFinal != null ? " type=\"match\"" : "";
                        chunksOutput.append("<chunk index=\"").append(c.chunkIndex() + 1)
                                .append("\"").append(matchAttr).append(">\n")
                                .append(chunkContent).append("\n</chunk>\n");
                        appendFormattedChunk.accept(c, chunkContent);
                        outputtedIndices.add(c.chunkIndex());
                    }

                    if (reFinal != null) {
                        forceOutputNext = true;
                    }
                } else if (forceOutputNext) {
                    if (!outputtedIndices.contains(c.chunkIndex())) {
                        chunksOutput.append("<chunk index=\"").append(c.chunkIndex() + 1)
                                .append("\" type=\"context_after\">\n").append(chunkContent)
                                .append("\n</chunk>\n");
                        appendFormattedChunk.accept(c, chunkContent);
                        outputtedIndices.add(c.chunkIndex());
                    }
                    forceOutputNext = false;
                }

                prevChunk = c;

                if (reFinal == null && matchCount >= 10) {
                    break;
                }
                if (reFinal != null && matchCount >= 20) {
                    break;
                }
            }

            if (hasRangeFinal && reachedMax) {
                break;
            }

            if (!hasRangeFinal) {
                if (reFinal == null && matchCount >= 10) {
                    break;
                }
                if (reFinal != null && matchCount >= 20) {
                    reachedMax = true;
                    break;
                }
            }

            if ((long) page * pageSize >= total) {
                break;
            }
            page++;
        }

        sb.append("<totalChunks>").append(totalChunks).append("</totalChunks>\n</metadata>\n");

        if (matchCount > 0) {
            sb.append("<chunks count=\"").append(matchCount).append("\">\n");
            sb.append(chunksOutput);
            sb.append("</chunks>\n");
        } else {
            sb.append("<chunks count=\"0\" />\n");
        }

        if (reachedMax) {
            sb.append("<message>Reached maximum limit for fetching chunks in a single call. "
                    + "Please refine your query or range if needed.</message>\n");
        } else if (matchCount == 0) {
            if (hasRangeFinal) {
                sb.append("<message>No chunks found in the specified range.</message>\n");
            } else if (reFinal != null) {
                sb.append("<message>No chunks matched your query in this document.</message>\n");
            } else {
                sb.append("<message>Document has no text chunks available.</message>\n");
            }
        } else if (!hasRangeFinal && reFinal == null) {
            sb.append("<message>No query or range provided. Showing the first 10 chunks as a preview.</message>\n");
        }

        sb.append("</sourceDocument>");

        ToolResult toolResult = new ToolResult();
        toolResult.setSuccess(true);
        toolResult.setOutput(sb.toString());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("displayType", "knowledge_chunks_list");
        data.put("knowledgeId", knowledgeId);
        data.put("knowledgeTitle", knowledgeTitle);
        data.put("totalChunks", totalChunks);
        data.put("fetchedChunks", formattedChunks.size());
        data.put("chunks", formattedChunks);
        toolResult.setData(data);
        return toolResult;
    }

    private static ToolResult failure(String message) {
        ToolResult result = new ToolResult();
        result.setSuccess(false);
        result.setError(message);
        return result;
    }
}
