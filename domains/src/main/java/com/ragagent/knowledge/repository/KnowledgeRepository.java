package com.ragagent.knowledge.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.ragagent.contract.pagination.PageResult;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.dto.KnowledgeSearchScope;
import com.ragagent.knowledge.mapper.KnowledgeMapper;
import com.ragagent.platform.persistence.LikeEscapes;
import com.ragagent.platform.persistence.PageRequests;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * knowledge 行级读写（由 {@code service.KnowledgeService} 的直连 mapper 抽出；对应方案
 * 的 {@code repository/KnowledgeRepository}）。
 *
 * <p>契约（避免复活已断的环）：本层**只做数据访问**——不抛业务异常（重复冲突由 service 侧判定并
 * 抛 {@code DuplicateKnowledgeException}）、不依赖任何 service 类型；方向恒为 repository ← service。</p>
 */
@Component
public class KnowledgeRepository {

    private final KnowledgeMapper knowledgeMapper;

    public KnowledgeRepository(KnowledgeMapper knowledgeMapper) {
        this.knowledgeMapper = knowledgeMapper;
    }

    /** 同 KB 内按文件哈希找未软删的重复行（找不到返回 {@code null}）。 */
    public Knowledge findDuplicateByHash(String kbId, String fileHash) {
        return knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getKnowledgeBaseId, kbId)
                .eq(Knowledge::getFileHash, fileHash)
                .isNull(Knowledge::getDeletedAt)
                .last("LIMIT 1"));
    }

    /** 同 KB 内按来源 URL 找未软删的重复行（找不到返回 {@code null}）。 */
    public Knowledge findDuplicateBySource(String kbId, String source) {
        return knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getKnowledgeBaseId, kbId)
                .eq(Knowledge::getSource, source)
                .isNull(Knowledge::getDeletedAt)
                .last("LIMIT 1"));
    }

    /** 按 id 取租户内未软删行（找不到返回 {@code null}）。 */
    public Knowledge findById(long tenantId, String id) {
        return knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getId, id)
                .eq(Knowledge::getTenantId, tenantId)
                .isNull(Knowledge::getDeletedAt)
                .last("LIMIT 1"));
    }

    /**
 * 摘要列写（由 {@code service.KnowledgeFileService} 归位；调用方直连本方法）。
 * <p>❌ 勿改走 {@link #updateRow}（**全列写**）：它会把<b>加载时</b>的旧
 * {@code parse_status} 一并写回。摘要是在导入后处理的 finalizing 交接**之后**才跑完 LLM
 * （数十秒），回写就把 `finalizing/completed` 打回加载时的 {@code processing} ✗
 * （用户报障：`05.03-问题发布.md` 卡 processing ✗）。</p>
 * <p>本方法只写摘要自己那几列（description / summary_status / metadata / updated_at），
 * 绝不碰 parse_status、enable_status、pending_subtasks_count。</p>
 */
    public void updateSummaryColumns(Knowledge k) {
        knowledgeMapper.update(null, new LambdaUpdateWrapper<Knowledge>()
                .eq(Knowledge::getId, k.getId())
                .set(Knowledge::getDescription, k.getDescription())
                .set(Knowledge::getSummaryStatus, k.getSummaryStatus())
                .set(Knowledge::getMetadata, k.getMetadata() == null
                                ? JsonNodeFactory.instance.objectNode()
                                : k.getMetadata(),
                        "typeHandler=com.ragagent.platform.persistence.PgJsonTypeHandler")
                .set(Knowledge::getUpdatedAt, k.getUpdatedAt() == null ? OffsetDateTime.now(ZoneOffset.UTC) : k.getUpdatedAt()));
    }

    /**
     * 只写 metadata（解析收尾用）：媒体引用这类信息在分块之外，不影响解析状态列，
     * 走全列写会把并发中的 parse_status 打回旧值。
     */
    public void updateMetadataOnly(String id, JsonNode metadata) {
        knowledgeMapper.update(null, new LambdaUpdateWrapper<Knowledge>()
                .eq(Knowledge::getId, id)
                .set(Knowledge::getMetadata, metadata == null
                                ? JsonNodeFactory.instance.objectNode()
                                : metadata,
                        "typeHandler=com.ragagent.platform.persistence.PgJsonTypeHandler")
                .set(Knowledge::getUpdatedAt, OffsetDateTime.now(ZoneOffset.UTC)));
    }

    /** 全列写（DeletedAt/PendingSubtasksCount 除外；零值也落库）—— 由 KnowledgeFileService 归位。 */
    public void updateRow(Knowledge k, JsonNode metadata) {
        knowledgeMapper.update(null, new LambdaUpdateWrapper<Knowledge>()
                .eq(Knowledge::getId, k.getId())
                .set(Knowledge::getType, k.getType())
                .set(Knowledge::getTitle, k.getTitle())
                .set(Knowledge::getDescription, k.getDescription())
                .set(Knowledge::getSource, k.getSource())
                .set(Knowledge::getParseStatus, k.getParseStatus())
                .set(Knowledge::getParseAttempt, k.getParseAttempt())
                .set(Knowledge::getSummaryStatus, k.getSummaryStatus())
                .set(Knowledge::getEnableStatus, k.getEnableStatus())
                .set(Knowledge::getEmbeddingModelId, k.getEmbeddingModelId())
                .set(Knowledge::getFileName, k.getFileName())
                .set(Knowledge::getFileType, k.getFileType())
                .set(Knowledge::getFileSize, k.getFileSize() == null ? 0L : k.getFileSize())
                .set(Knowledge::getFileHash, k.getFileHash())
                .set(Knowledge::getFilePath, k.getFilePath())
                .set(Knowledge::getMetadata, metadata,
                        "typeHandler=com.ragagent.platform.persistence.PgJsonTypeHandler")
                .set(Knowledge::getUpdatedAt, k.getUpdatedAt())
                .set(Knowledge::getProcessedAt, k.getProcessedAt())
                .set(Knowledge::getErrorMessage, k.getErrorMessage()));
    }

    /** 只刷 file_hash + updated_at（图片信息更新后的对照哈希）—— 归位。 */
    public void updateFileHash(String id, String fileHash, OffsetDateTime now) {
        knowledgeMapper.update(null, new LambdaUpdateWrapper<Knowledge>()
                .eq(Knowledge::getId, id)
                .set(Knowledge::getFileHash, fileHash)
                .set(Knowledge::getUpdatedAt, now));
    }

    /** 该 KB 下未软删的 knowledge 计数（null 视为 0）。 */
    public long countByKnowledgeBaseId(String knowledgeBaseId) {
        Long c = knowledgeMapper.selectCount(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getKnowledgeBaseId, knowledgeBaseId)
                .isNull(Knowledge::getDeletedAt));
        return c == null ? 0 : c;
    }

    /** 该 KB 下处于给定解析状态的计数（状态集由调用方给：列表口径 2 个、摘要口径 3 个）。 */
    public long countByParseStatuses(String knowledgeBaseId, String... parseStatuses) {
        Long c = knowledgeMapper.selectCount(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getKnowledgeBaseId, knowledgeBaseId)
                .isNull(Knowledge::getDeletedAt)
                .in(Knowledge::getParseStatus, (Object[]) parseStatuses));
        return c == null ? 0 : c;
    }

    /** 级联软删：该 KB 下全部 knowledge 行（删库路径归位）。 */
    public void softDeleteByKnowledgeBaseId(String knowledgeBaseId, OffsetDateTime now) {
        knowledgeMapper.update(null, new LambdaUpdateWrapper<Knowledge>()
                .eq(Knowledge::getKnowledgeBaseId, knowledgeBaseId).set(Knowledge::getDeletedAt, now));
    }

    /** 文件夹树计数用：取该 KB 下未软删、非 deleting 的行（只选 folder_path）。 */
    public List<Knowledge> listFolderPathsForTree(long tenantId, String knowledgeBaseId) {
        return knowledgeMapper.selectList(new LambdaQueryWrapper<Knowledge>()
                .select(Knowledge::getFolderPath)
                .eq(Knowledge::getKnowledgeBaseId, knowledgeBaseId)
                .eq(Knowledge::getTenantId, tenantId)
                .isNull(Knowledge::getDeletedAt)
                .ne(Knowledge::getParseStatus, Knowledge.PARSE_DELETING));
    }

    /** 行级改写 folder_path（给定 id 集合；返回影响行数）。 */
    public int updateFolderPath(long tenantId, String knowledgeBaseId, List<String> ids,
                                String folderPath, OffsetDateTime now) {
        return knowledgeMapper.update(null, new LambdaUpdateWrapper<Knowledge>()
                .eq(Knowledge::getTenantId, tenantId)
                .eq(Knowledge::getKnowledgeBaseId, knowledgeBaseId)
                .in(Knowledge::getId, ids)
                .set(Knowledge::getFolderPath, folderPath)
                .set(Knowledge::getUpdatedAt, now));
    }

    /** 取 folder_path = prefix 或 prefix/… 的行（只选 id 与 folder_path；租户 + KB 内、未软删）。 */
    public List<Knowledge> listByFolderPathPrefix(long tenantId, String knowledgeBaseId, String prefix) {
        return knowledgeMapper.selectList(new LambdaQueryWrapper<Knowledge>()
                .select(Knowledge::getId, Knowledge::getFolderPath)
                .eq(Knowledge::getTenantId, tenantId)
                .eq(Knowledge::getKnowledgeBaseId, knowledgeBaseId)
                .isNull(Knowledge::getDeletedAt)
                .and(w -> w.eq(Knowledge::getFolderPath, prefix)
                        .or().likeRight(Knowledge::getFolderPath, prefix + "/")));
    }

    /** 按 (tenant, ids) 批量取未软删行（不回填 tags）。 */
    public List<Knowledge> listByIds(long tenantId, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return knowledgeMapper.selectList(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getTenantId, tenantId)
                .in(Knowledge::getId, ids)
                .isNull(Knowledge::getDeletedAt));
    }

    /**
 * 检索主查询（由 {@code KnowledgeSearchService} 的查询构建整体搬入）。
 *
 * <p>语义逐字保留：未软删 + 作用域 OR 组（每对 tenant/kb 一组）+ keyword 对
 * {@code LOWER(file_name)}/{@code LOWER(title)} LIKE（转义后）+ 文件类型别名模式
 * （{@code <<url>>} 走 {@code type='url'} 分支）；created_at DESC；取 {@code limit+1} 行用于
 * 探测 has_more，total 是过滤后的全量计数（由调用方切分）。</p>
 */
    public SearchRows searchScopedRows(List<KnowledgeSearchScope> scopes, String keyword,
                                       List<String> fileTypes, int offset, int limit) {
        LambdaQueryWrapper<Knowledge> qw = new LambdaQueryWrapper<Knowledge>().isNull(Knowledge::getDeletedAt);
        qw.and(w -> {
            for (KnowledgeSearchScope s : scopes) {
                w.or(i -> i.eq(Knowledge::getTenantId, s.tenantId())
                        .eq(Knowledge::getKnowledgeBaseId, s.kbId()));
            }
        });
        String kw = keyword == null ? "" : keyword;
        if (!kw.isEmpty()) {
            String pat = "%" + LikeEscapes.escape(kw.toLowerCase()) + "%";
            qw.and(w -> w.apply("LOWER(file_name) LIKE {0}", pat)
                    .or()
                    .apply("LOWER(title) LIKE {0}", pat));
        }
        List<String> patterns = fileTypePatterns(fileTypes);
        boolean includeUrl = patterns.remove("<<url>>");
        if (!patterns.isEmpty() || includeUrl) {
            qw.and(w -> {
                for (int i = 0; i < patterns.size(); i++) {
                    if (i > 0) {
                        w.or();
                    }
                    w.apply("LOWER(file_name) LIKE {0}", patterns.get(i));
                }
                if (includeUrl) {
                    if (!patterns.isEmpty()) {
                        w.or();
                    }
                    w.eq(Knowledge::getType, "url");
                }
            });
        }
        long total = knowledgeMapper.selectCount(qw);
        List<Knowledge> rows = knowledgeMapper.selectList(PageRequests.atOffset(offset, (long) limit + 1),
                qw.orderByDesc(Knowledge::getCreatedAt));
        return new SearchRows(rows, total);
    }

    /** 检索查询结果：{@code rows} 含 limit+1 探测行，{@code total} 为过滤后全量计数。 */
    public record SearchRows(List<Knowledge> rows, long total) {}

    private static List<String> fileTypePatterns(List<String> fileTypes) {
        List<String> patterns = new ArrayList<>();
        if (fileTypes == null) {
            return patterns;
        }
        Set<String> seen = new HashSet<>();
        for (String ft : fileTypes) {
            String f = ft == null ? "" : ft.toLowerCase();
            while (f.startsWith(".")) {
                f = f.substring(1);
            }
            if ("url".equals(f) || "html".equals(f)) {
                if (!seen.contains("<<url>>")) {
                    seen.add("<<url>>");
                    patterns.add("<<url>>");
                }
                continue;
            }
            String pat = "%." + f;
            if (!seen.contains(pat)) {
                seen.add(pat);
                patterns.add(pat);
            }
            List<String> aliases = switch (f) {
                case "xlsx" -> List.of("%.xls");
                case "xls" -> List.of("%.xlsx");
                case "docx" -> List.of("%.doc");
                case "doc" -> List.of("%.docx");
                case "jpg" -> List.of("%.jpeg", "%.png");
                case "jpeg" -> List.of("%.jpg", "%.png");
                case "png" -> List.of("%.jpg", "%.jpeg");
                default -> List.<String>of();
            };
            for (String alias : aliases) {
                if (!seen.contains(alias)) {
                    seen.add(alias);
                    patterns.add(alias);
                }
            }
        }
        return patterns;
    }

    /** 同租户 + 同 KB 的未软删行（**不排序、不筛 type**；FAQ 容器由调用方自行判定）。 */
    public List<Knowledge> listByTenantAndKb(long tenantId, String knowledgeBaseId) {
        return knowledgeMapper.selectList(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getTenantId, tenantId)
                .eq(Knowledge::getKnowledgeBaseId, knowledgeBaseId)
                .isNull(Knowledge::getDeletedAt));
    }

    /** 插入新行。 */
    public void insert(Knowledge k) {
        knowledgeMapper.insert(k);
    }

    /** 分页查询（条件由调用方以 wrapper 传入：查询构建仍在用例侧，本层只执行）。 */
    public Page<Knowledge> selectPage(
            Page<Knowledge> page,
            LambdaQueryWrapper<Knowledge> qw) {
        return knowledgeMapper.selectPage(page, qw);
    }

    /**
     * 知识条目搜索分页：按标题关键字 / 解析状态 / 文件类型 / 目录路径过滤。
     *
     * <p>条件构造收在仓储侧，用例层只传语义参数——分页插件类型不外泄。</p>
     */
    public PageResult<Knowledge> searchPage(String kbId, long page, long pageSize,
            String keyword, String parseStatus, String fileType, String folderPath, boolean folderPresent) {
        LambdaQueryWrapper<Knowledge> qw = new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getKnowledgeBaseId, kbId)
                .isNull(Knowledge::getDeletedAt)
                .orderByDesc(Knowledge::getCreatedAt);
        if (keyword != null && !keyword.isEmpty()) {
            qw.like(Knowledge::getTitle, keyword);
        }
        if (parseStatus != null && !parseStatus.isEmpty()) {
            qw.eq(Knowledge::getParseStatus, parseStatus);
        }
        if (fileType != null && !fileType.isEmpty()) {
            qw.eq(Knowledge::getFileType, fileType);
        }
        if (folderPresent) {
            qw.eq(Knowledge::getFolderPath, folderPath == null ? "" : folderPath);
        }
        Page<Knowledge> result = knowledgeMapper.selectPage(new Page<>(page, pageSize), qw);
        return PageResult.of(result.getRecords(), result.getTotal());
    }

    /** 仅按 id 取未软删行（无租户过滤；守卫/权限解析用）。 */
    public Knowledge findByIdOnly(String id) {
        return knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getId, id)
                .isNull(Knowledge::getDeletedAt)
                .last("LIMIT 1"));
    }

    /**
 * 按 id 取行，**不过滤软删**（与 {@code mapper.selectById} 一致）。
 *
 * <p>与 {@link #findByIdOnly(String)} 的区别：后者排除软删行。管线任务（图抽取/问题生成）
 * 需要看到已软删行以判定 aborted 并跳过，故单列此法。</p>
 */
    public Knowledge findByIdAny(String id) {
        return knowledgeMapper.selectById(id);
    }

    /**
     * 心跳：只把 updated_at 推到 now（**不按租户、不看软删**——与原直连语句逐字一致，供 domain.span.SpanTracker 用）。
     */
    public int touchUpdatedAt(String id, OffsetDateTime now) {
        return knowledgeMapper.update(null, new LambdaUpdateWrapper<Knowledge>()
                .eq(Knowledge::getId, id)
                .set(Knowledge::getUpdatedAt, now));
    }

    /** 软删 knowledge 行（置 deleted_at）。 */
    public void softDelete(String id, OffsetDateTime now) {
        knowledgeMapper.update(null, new LambdaUpdateWrapper<Knowledge>()
                .eq(Knowledge::getId, id).set(Knowledge::getDeletedAt, now));
    }

    /**
 * 软删（**仅当尚未软删**：带 {@code isNull(deleted_at)}，返回影响行数）。
 *
 * <p>与 {@link #softDelete(String, OffsetDateTime)} 的区别就在这个条件——批量删除路径
 * 逐条调用它（由 service 直连归位）。</p>
 */
    public int softDeleteIfActive(String id, OffsetDateTime now) {
        return knowledgeMapper.update(null, new LambdaUpdateWrapper<Knowledge>()
                .eq(Knowledge::getId, id)
                .isNull(Knowledge::getDeletedAt)
                .set(Knowledge::getDeletedAt, now));
    }

    /** 批量置 {@code parse_status='deleting'} 并推 updated_at（清空知识库用）。 */
    public int setParseStatusDeleting(List<String> ids, OffsetDateTime now) {
        return knowledgeMapper.update(null, new LambdaUpdateWrapper<Knowledge>()
                .in(Knowledge::getId, ids)
                .set(Knowledge::getParseStatus, Knowledge.PARSE_DELETING)
                .set(Knowledge::getUpdatedAt, now));
    }

    /**
 * 状态列更新（worker 用）：{@code errorMessage} / {@code enable} 为 {@code null} 表示**不更新该列**；
 * {@code enable} 非空时同时写 {@code enable_status} 与 {@code processed_at}（由 service 直连归位，条件语义逐字保留）。
 */
    public int updateStatusColumns(String id, String parseStatus, String errorMessage, Boolean enable,
                                   OffsetDateTime now) {
        LambdaUpdateWrapper<Knowledge> uw = new LambdaUpdateWrapper<Knowledge>()
                .eq(Knowledge::getId, id);
        uw.set(Knowledge::getParseStatus, parseStatus);
        uw.set(Knowledge::getUpdatedAt, now);
        if (errorMessage != null) {
            uw.set(Knowledge::getErrorMessage, errorMessage);
        }
        if (enable != null) {
            uw.set(Knowledge::getEnableStatus, enable ? "enabled" : "disabled");
            uw.set(Knowledge::getProcessedAt, now);
        }
        return knowledgeMapper.update(null, uw);
    }

    /**
     * 待重试的删除清理行：已软删、但物理资源（文件/图片/索引）回收失败。
     * 按 {@code updated_at} 升序，先失败先重试。
     */
    public List<String> listCleanupFailedIds(int limit) {
        LambdaQueryWrapper<Knowledge> qw = new LambdaQueryWrapper<Knowledge>()
                .select(Knowledge::getId)
                .eq(Knowledge::getParseStatus, Knowledge.PARSE_DELETE_FAILED)
                .isNotNull(Knowledge::getDeletedAt)
                .orderByAsc(Knowledge::getUpdatedAt);
        Page<Knowledge> page = new Page<>(1, Math.max(1, limit));
        return knowledgeMapper.selectPage(page, qw).getRecords().stream()
                .map(Knowledge::getId)
                .toList();
    }

    /** 只写 {@code summary_status}（pending/failed 等；由 service 直连归位）。 */
    public int setSummaryStatus(String id, String summaryStatus) {
        return knowledgeMapper.update(null, new LambdaUpdateWrapper<Knowledge>()
                .eq(Knowledge::getId, id)
                .set(Knowledge::getSummaryStatus, summaryStatus));
    }

    /**
 * 搬行到目标 KB：改 {@code knowledge_base_id} + 落终态 completed + 清 error_message + 推 updated_at
 * （原样搬 {@code reuse_vectors} 分支的直连语句）。
 */
    public int moveRowToKnowledgeBase(String id, String targetKbId, OffsetDateTime now) {
        return knowledgeMapper.update(null, new LambdaUpdateWrapper<Knowledge>()
                .eq(Knowledge::getId, id)
                .set(Knowledge::getKnowledgeBaseId, targetKbId)
                .set(Knowledge::getParseStatus, Knowledge.PARSE_COMPLETED)
                .set(Knowledge::getErrorMessage, "")
                .set(Knowledge::getUpdatedAt, now));
    }

    /**
 * 搬行到目标 KB 的**待解析态**（{@code reparse} 模式：改 KB/嵌入模型 + pending + disabled + 清描述/时间/容量）。
 */
    public int resetRowForReparseInKnowledgeBase(String id, String targetKbId, String embeddingModelId,
                                                 OffsetDateTime now) {
        return knowledgeMapper.update(null, new LambdaUpdateWrapper<Knowledge>()
                .eq(Knowledge::getId, id)
                .set(Knowledge::getKnowledgeBaseId, targetKbId)
                .set(Knowledge::getEmbeddingModelId, embeddingModelId)
                .set(Knowledge::getParseStatus, Knowledge.PARSE_PENDING)
                .set(Knowledge::getErrorMessage, "")
                .set(Knowledge::getEnableStatus, "disabled")
                .set(Knowledge::getDescription, "")
                .set(Knowledge::getProcessedAt, null)
                .set(Knowledge::getStorageSize, 0L)
                .set(Knowledge::getUpdatedAt, now));
    }

    /**
 * 子任务收尾：原子递减 {@code pending_subtasks_count} 并**钳在零**（{@code > 0} 才减），
 * 返回影响行数（0 = 已经是零）。 由 wiki finalize 适配器直连语句原样搬入。
 */
    public int decrementPendingSubtasks(String id, OffsetDateTime now) {
        return knowledgeMapper.update(null, new LambdaUpdateWrapper<Knowledge>()
                .eq(Knowledge::getId, id)
                .gt(Knowledge::getPendingSubtasksCount, 0)
                .setSql("pending_subtasks_count = pending_subtasks_count - 1")
                .set(Knowledge::getUpdatedAt, now));
    }

    /** 写 {@code summary_status} 并推 updated_at（无 updated_at 的旧重载见 {@link #setSummaryStatus(String, String)}）。 */
    public int setSummaryStatus(String id, String summaryStatus, OffsetDateTime now) {
        return knowledgeMapper.update(null, new LambdaUpdateWrapper<Knowledge>()
                .eq(Knowledge::getId, id)
                .set(Knowledge::getSummaryStatus, summaryStatus)
                .set(Knowledge::getUpdatedAt, now));
    }

    /** 按主键整行更新。 */
    public void updateById(Knowledge k) {
        knowledgeMapper.updateById(k);
    }
}
