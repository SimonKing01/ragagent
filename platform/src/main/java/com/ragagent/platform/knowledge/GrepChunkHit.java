package com.ragagent.platform.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.contract.knowledge.ChunkFacts;

public final class GrepChunkHit {
    public String id;
    public String content;
    public int chunkIndex;
    public String knowledgeId;
    public String knowledgeBaseId;
    public String chunkType;
    public JsonNode metadata;
    public String parentChunkId;
    public String knowledgeTitle;
    public int totalChunkCount;
    // 打分阶段回填（MatchScore/MatchedPatterns）。
    public double matchScore;
    public int matchedPatterns;

    /** null 视为 ""（零值语义）。 */
    public static String nz(String v) {
        return v == null ? "" : v;
    }

    /** 转 L1 载荷以复用 FaqSnippet（FAQ 元数据/标准问题路径）。 */
    public ChunkFacts toChunk() {
        return new ChunkFacts(nz(id), nz(knowledgeId), nz(knowledgeBaseId), content,
                chunkType == null ? "" : chunkType, "", false, chunkIndex, 0, 0, 0,
                parentChunkId, "", "", null, metadata, "");
    }
}
