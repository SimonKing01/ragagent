package com.ragagent.knowledge.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.contract.knowledge.FaqChunkMetadata;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.mapper.ChunkMapper;
import com.ragagent.platform.knowledge.FaqChunkMetadataCodec;
import com.ragagent.platform.persistence.DatabaseDialects;
import com.ragagent.platform.persistence.PageRequests;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/**
 * FAQ 条目的 chunk 仓储面（FAQ 条目 = chunk_type=faq 的行）：按 seq_id/知识/KB 的
 * 读取、重复问检测（PG jsonb 与 H2 双方言）、flags 位运算批量更新、按标签批量改字段。
 * <p>方言探测在构造期做一次（PG 的 jsonb/位运算 vs MySQL/H2 的替代语法）；FAQ 关键词
 * 搜索的 jsonb 分支仅真 PG 可跑（H2 集成测试只覆盖排序键）。</p>
 */
@Component
public class FaqChunkRepository {

    /** status=0（stored，默认）。 */
    private static final int STATUS_DEFAULT = 0;

    /** status=2（indexed，向量化完成）。 */
    private static final int STATUS_INDEXED = 2;

    /** 批量扫描的行帽：offset 恒以它为步长（PageRequests.range 的页数学因此成立）。 */
    private static final long SCAN_BATCH = 1000;

    private final ChunkMapper chunkMapper;
    /** 方言（构造期探测一次）：true = postgres。 */
    private final boolean postgres;

    public FaqChunkRepository(ChunkMapper chunkMapper, DataSource dataSource) {
        this.chunkMapper = chunkMapper;
        this.postgres = DatabaseDialects.isPostgres(dataSource);
    }

    // ── FAQ 条目读写（含 flags 位运算的方言分支） ─────────────

    /**
     * tenant + seq_id，软删行不可见。
     * 这里返回 null 由调用方决定文案（ChunkRepository 的既有先例）。
     */
    public Chunk getChunkBySeqId(long tenantId, long seqId) {
        return chunkMapper.selectOne(new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getTenantId, tenantId)
                .eq(Chunk::getSeqId, seqId)
                .isNull(Chunk::getDeletedAt)
                .last("LIMIT 1"));
    }

    /** tenant + seq_id IN；空入参 → 空列表。 */
    public List<Chunk> listChunksBySeqId(long tenantId, List<Long> seqIds) {
        if (seqIds == null || seqIds.isEmpty()) {
            return List.of();
        }
        return chunkMapper.selectList(new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getTenantId, tenantId)
                .in(Chunk::getSeqId, seqIds)
                .isNull(Chunk::getDeletedAt));
    }

    /**
     * 只取
     * {@code id, content_hash} 投影（replace 模式 hash 比对用），chunk_type='faq'。
     */
    public List<Chunk> listAllFAQChunksByKnowledgeId(long tenantId, String knowledgeId) {
        List<Chunk> all = new ArrayList<>();
        int offset = 0;
        while (true) {
            List<Chunk> batch = chunkMapper.selectList(PageRequests.range(offset / SCAN_BATCH + 1, SCAN_BATCH),
                    new LambdaQueryWrapper<Chunk>()
                            .select(Chunk::getId, Chunk::getContentHash)
                            .eq(Chunk::getTenantId, tenantId)
                            .eq(Chunk::getKnowledgeId, knowledgeId)
                            .eq(Chunk::getChunkType, "faq")
                            .isNull(Chunk::getDeletedAt));
            if (batch.isEmpty()) {
                break;
            }
            all.addAll(batch);
            if (batch.size() < SCAN_BATCH) {
                break;
            }
            offset += SCAN_BATCH;
        }
        return all;
    }

    /**
     * * {@code id, metadata} 投影 + chunk_type='faq' + <b>status=2（indexed）</b>
     * （append 校验/合并只看已索引行）。
     */
    public List<Chunk> listAllFAQChunksWithMetadataByKnowledgeBaseId(long tenantId, String kbId) {
        List<Chunk> all = new ArrayList<>();
        int offset = 0;
        while (true) {
            List<Chunk> batch = chunkMapper.selectList(PageRequests.range(offset / SCAN_BATCH + 1, SCAN_BATCH),
                    new LambdaQueryWrapper<Chunk>()
                            .select(Chunk::getId, Chunk::getMetadata)
                            .eq(Chunk::getTenantId, tenantId)
                            .eq(Chunk::getKnowledgeBaseId, kbId)
                            .eq(Chunk::getChunkType, "faq")
                            .eq(Chunk::getStatus, STATUS_INDEXED)
                            .isNull(Chunk::getDeletedAt));
            if (batch.isEmpty()) {
                break;
            }
            all.addAll(batch);
            if (batch.size() < SCAN_BATCH) {
                break;
            }
            offset += SCAN_BATCH;
        }
        return all;
    }

    /**
     * 找单个
     * standardQuestion 或 similarQuestions 与给定问题集重叠的 FAQ chunk
     * （status ∈ {0,1,2} 全算——stored 的兄弟请求也算，软删行不可见）。
     * 退化为「取候选行后在 JVM 内按同一集合语义过滤」——功能等价、数据量是
     * 多行重叠时取哪一行本就不确定）。</p>
     */
    public Chunk findFAQChunkWithDuplicateQuestion(
            long tenantId, String kbId, String excludeChunkId, List<String> questions) {
        if (questions == null || questions.isEmpty()) {
            return null;
        }
        if (postgres) {
            return chunkMapper.findFaqDuplicateChunk(tenantId, kbId, excludeChunkId, questions);
        }
        // 非 PG：同 WHERE 的 JVM 版（questions 的集合语义逐条对照）
        List<Chunk> candidates = chunkMapper.selectList(new LambdaQueryWrapper<Chunk>()
                .select(Chunk::getId, Chunk::getMetadata)
                .eq(Chunk::getTenantId, tenantId)
                .eq(Chunk::getKnowledgeBaseId, kbId)
                .eq(Chunk::getChunkType, "faq")
                .in(Chunk::getStatus, STATUS_DEFAULT, 1, STATUS_INDEXED)
                .ne(Chunk::getId, excludeChunkId)
                .isNull(Chunk::getDeletedAt));
        Set<String> wanted = new HashSet<>(questions);
        for (Chunk c : candidates) {
            FaqChunkMetadata meta = parseFaqMetadata(c.getMetadata());
            if (meta == null) {
                continue;
            }
            if (meta.standardQuestion != null && wanted.contains(meta.standardQuestion)) {
                return c;
            }
            if (meta.similarQuestions != null) {
                for (String q : meta.similarQuestions) {
                    if (wanted.contains(q)) {
                        return c;
                    }
                }
            }
        }
        return null;
    }

    /**
     * * {@code id, metadata, tag_id, is_enabled, flags} + status=2 + {@code created_at ASC}。
     */
    public List<Chunk> listAllFAQChunksForExport(long tenantId, String knowledgeId) {
        List<Chunk> all = new ArrayList<>();
        int offset = 0;
        while (true) {
            List<Chunk> batch = chunkMapper.selectList(PageRequests.range(offset / SCAN_BATCH + 1, SCAN_BATCH),
                    new LambdaQueryWrapper<Chunk>()
                            .select(Chunk::getId, Chunk::getMetadata, Chunk::getTagId,
                                    Chunk::isIsEnabled, Chunk::getFlags)
                            .eq(Chunk::getTenantId, tenantId)
                            .eq(Chunk::getKnowledgeId, knowledgeId)
                            .eq(Chunk::getChunkType, "faq")
                            .eq(Chunk::getStatus, STATUS_INDEXED)
                            .isNull(Chunk::getDeletedAt)
                            .orderByAsc(Chunk::getCreatedAt));
            if (batch.isEmpty()) {
                break;
            }
            all.addAll(batch);
            if (batch.size() < SCAN_BATCH) {
                break;
            }
            offset += SCAN_BATCH;
        }
        return all;
    }

    /**
     * * {@code flags = (flags | set) & ~clear} + updated_at=NOW()，单条 UPDATE
     * （所有行共享同一时刻，与列表页排序有关——别改成循环）。
     */
    public void updateChunkFlagsBatch(long tenantId, String kbId,
                                      Map<String, Integer> setFlags, Map<String, Integer> clearFlags) {
        if ((setFlags == null || setFlags.isEmpty()) && (clearFlags == null || clearFlags.isEmpty())) {
            return;
        }
        LinkedHashSet<String> allIds = new LinkedHashSet<>();
        if (setFlags != null) {
            allIds.addAll(setFlags.keySet());
        }
        if (clearFlags != null) {
            allIds.addAll(clearFlags.keySet());
        }
        String setExpr = buildFlagCase(setFlags);
        String clearExpr = buildFlagCase(clearFlags);
        chunkMapper.update(null, new LambdaUpdateWrapper<Chunk>()
                .setSql("flags = " + flagsBitExpr("(flags | (" + setExpr + ")) & ~(" + clearExpr + ")",
                        "BITAND(BITOR(flags, " + setExpr + "), BITNOT(" + clearExpr + "))"))
                .setSql("updated_at = NOW()")
                .eq(Chunk::getTenantId, tenantId)
                .eq(Chunk::getKnowledgeBaseId, kbId)
                .isNull(Chunk::getDeletedAt)
                .in(Chunk::getId, allIds));
    }

    /**
     * FAQ metadata 列（json 投影）→ {@link FaqChunkMetadata}；解析失败/空 → null
     */
    public static FaqChunkMetadata parseFaqMetadata(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode() || node.isEmpty()) {
            return null;
        }
        return FaqChunkMetadataCodec.fromJson(node);
    }

    /** 位运算表达式的方言切换：PG 用 | &~，H2 2.x 无位运算符 → BITOR/BITAND/BITNOT（语义等价）。 */
    private String flagsBitExpr(String postgresExpr, String h2Expr) {
        return postgres ? postgresExpr : h2Expr;
    }

    private static String buildFlagCase(Map<String, Integer> flags) {
        if (flags == null || flags.isEmpty()) {
            return "0";
        }
        StringBuilder sb = new StringBuilder("CASE");
        for (Map.Entry<String, Integer> e : flags.entrySet()) {
            sb.append(" WHEN id = '").append(e.getKey().replace("'", "''"))
                    .append("' THEN ").append(e.getValue());
        }
        sb.append(" ELSE 0 END");
        return sb.toString();
    }

    /**
     * 把某 tag 下（可排除若干 id）
     * 的全部 FAQ chunk 更新 is_enabled / flags / tag_id，返回受影响 id（先 Pluck 后更新）。
     */
    public List<String> updateChunkFieldsByTagId(long tenantId, String kbId, String tagId,
                                                 Boolean isEnabled, int setFlags, int clearFlags,
                                                 String newTagId, List<String> excludeIds) {
        if (isEnabled == null && setFlags == 0 && clearFlags == 0 && newTagId == null) {
            return List.of();
        }
        LambdaQueryWrapper<Chunk> selection = new LambdaQueryWrapper<Chunk>()
                .select(Chunk::getId)
                .eq(Chunk::getTenantId, tenantId)
                .eq(Chunk::getKnowledgeBaseId, kbId)
                .eq(Chunk::getChunkType, "faq")
                .isNull(Chunk::getDeletedAt);
        if (tagId != null && !tagId.isEmpty()) {
            selection.eq(Chunk::getTagId, tagId);
        }
        if (excludeIds != null && !excludeIds.isEmpty()) {
            selection.notIn(Chunk::getId, excludeIds);
        }
        List<String> affectedIds = chunkMapper.selectList(selection)
                .stream().map(Chunk::getId).toList();

        LambdaUpdateWrapper<Chunk> update = new LambdaUpdateWrapper<Chunk>()
                .eq(Chunk::getTenantId, tenantId)
                .eq(Chunk::getKnowledgeBaseId, kbId)
                .eq(Chunk::getChunkType, "faq");
        if (tagId != null && !tagId.isEmpty()) {
            update.eq(Chunk::getTagId, tagId);
        }
        if (excludeIds != null && !excludeIds.isEmpty()) {
            update.notIn(Chunk::getId, excludeIds);
        }
        update.set(Chunk::getUpdatedAt, OffsetDateTime.now());
        if (isEnabled != null) {
            update.set(Chunk::isIsEnabled, isEnabled);
        }
        if (newTagId != null) {
            update.set(Chunk::getTagId, newTagId);
        }
        if (setFlags != 0 || clearFlags != 0) {
            String expr = "flags";
            if (setFlags != 0) {
                expr = postgres ? expr + " | " + setFlags
                        : "BITOR(" + expr + ", " + setFlags + ")";
            }
            if (clearFlags != 0) {
                expr = postgres ? expr + " & ~" + clearFlags
                        : "BITAND(" + expr + ", BITNOT(" + clearFlags + "))";
            }
            update.setSql("flags = " + expr);
        }
        chunkMapper.update(null, update);
        return affectedIds;
    }

    /**
     * CASE 批量更新 content / is_enabled /
     * tag_id / flags / status + updated_at=NOW()（一条语句一个时刻，列表排序敏感）。
     */
    public void updateChunks(List<Chunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        chunkMapper.updateChunksCase(chunks);
    }
}
