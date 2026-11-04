package com.ragagent.knowledge.task;

import com.ragagent.knowledge.chunker.Chunker;
import com.ragagent.knowledge.chunker.ParsedChunk;
import com.ragagent.knowledge.chunker.SplitterConfig;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.domain.KnowledgeBaseChunkingConfig;
import com.ragagent.knowledge.domain.KnowledgeProcessingSpan;
import com.ragagent.knowledge.domain.span.SpanTracker;
import com.ragagent.knowledge.repository.ChunkRepository;
import com.ragagent.knowledge.support.ProcessOverridesResolver;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 入库阶段二：分块并落库。
 *
 * <p>KB 配置 0 值回退默认 512/80；行间 pre/next 双向链；阶段 span 记录写出量与总字符数。</p>
 */
final class KnowledgeChunkWriter {

    private final ChunkRepository chunkRepository;
    private final SpanTracker spanTracker;

    KnowledgeChunkWriter(ChunkRepository chunkRepository, SpanTracker spanTracker) {
        this.chunkRepository = chunkRepository;
        this.spanTracker = spanTracker;
    }

    List<Chunk> chunkAndPersist(Knowledge k, KnowledgeBase kb, String knowledgeId,
            String markdown, int attempt) {
        List<String> inactiveKeys = kb.getChunkingConfig() == null ? List.of()
                : kb.getChunkingConfig().ingestionInactiveKeys();
        SpanTracker.SpanHandle chunkSpan = beginStageSpan(attempt, knowledgeId,
                KnowledgeProcessingSpan.STAGE_CHUNKING,
                inactiveKeys.isEmpty() ? null
                        : Map.of("inactive_config_keys", String.join(",", inactiveKeys)));
        // 逐文档覆盖优先：上传/重解析弹窗里的分块设置要真正生效
        SplitterConfig cfg = toSplitterConfig(
                ProcessOverridesResolver.chunking(k, kb.getChunkingConfig()));
        List<ParsedChunk> parsedChunks = Chunker.split(markdown, cfg);
        List<Chunk> chunks = new ArrayList<>(parsedChunks.size());
        String prevId = null;
        for (int i = 0; i < parsedChunks.size(); i++) {
            ParsedChunk pc = parsedChunks.get(i);
            Chunk c = new Chunk();
            c.setId(UUID.randomUUID().toString());
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            c.setCreatedAt(now);
            c.setUpdatedAt(now);
            c.setTenantId(k.getTenantId());
            c.setKnowledgeId(knowledgeId);
            c.setKnowledgeBaseId(k.getKnowledgeBaseId());
            c.setContent(pc.getContent());
            c.setSourceContent(pc.getContent());
            c.setContextHeader(pc.getContextHeader());
            c.setChunkIndex(i);
            c.setStartAt(pc.getStart());
            c.setEndAt(pc.getEnd());
            c.setChunkType("text");
            c.setPreChunkId(prevId);
            chunks.add(c);
            prevId = c.getId();
        }
        for (int i = 0; i < chunks.size(); i++) {
            if (i + 1 < chunks.size()) {
                chunks.get(i).setNextChunkId(chunks.get(i + 1).getId());
            }
            chunkRepository.insert(chunks.get(i));
        }
        int totalChars = 0;
        for (Chunk c : chunks) {
            totalChars += c.getContent() == null ? 0 : c.getContent().length();
        }
        endStageSpan(chunkSpan, Map.of(
                "chunks_written", chunks.size(), "total_text_chars", totalChars));
        return chunks;
    }

    /** KB 配置 → chunker 配置（0 值回退默认：512/80/separators）。 */
    private static SplitterConfig toSplitterConfig(KnowledgeBaseChunkingConfig kbc) {
        SplitterConfig cfg = new SplitterConfig();
        cfg.setChunkSize(kbc.getChunkSize() <= 0
                ? SplitterConfig.DEFAULT_CHUNK_SIZE : kbc.getChunkSize());
        int overlap = kbc.getChunkOverlap() <= 0
                ? SplitterConfig.DEFAULT_CHUNK_OVERLAP : kbc.getChunkOverlap();
        cfg.setChunkOverlap(Math.min(overlap, cfg.getChunkSize() / 2));
        cfg.setSeparators(kbc.getSeparators() == null
                ? SplitterConfig.DEFAULT_SEPARATORS : kbc.getSeparators());
        cfg.setStrategy(kbc.getStrategy() == null || kbc.getStrategy().isEmpty()
                ? null : kbc.getStrategy());
        cfg.setTokenLimit(kbc.getTokenLimit());
        cfg.setLanguages(kbc.getLanguages());
        return cfg;
    }

    private SpanTracker.SpanHandle beginStageSpan(int attempt, String knowledgeId,
            String stage, Map<String, Object> input) {
        if (attempt <= 0) {
            return null;
        }
        return spanTracker.beginStage(knowledgeId, attempt, stage, input);
    }

    private void endStageSpan(SpanTracker.SpanHandle span, Map<String, Object> output) {
        if (span != null) {
            spanTracker.endSpan(span, output);
        }
    }
}
