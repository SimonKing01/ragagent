package com.ragagent.knowledge.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ragagent.contract.knowledge.ChunkPageView;
import com.ragagent.contract.knowledge.ChunkRowView;
import com.ragagent.contract.knowledge.FaqEntryPageView;
import com.ragagent.contract.knowledge.FaqEntryView;
import com.ragagent.contract.knowledge.KnowledgeBaseRowView;
import com.ragagent.contract.knowledge.KnowledgeCatalogPort;
import com.ragagent.contract.knowledge.KnowledgePageView;
import com.ragagent.contract.knowledge.KnowledgeRowView;
import com.ragagent.contract.knowledge.KnowledgeTagView;
import com.ragagent.contract.pagination.PageResult;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.domain.KnowledgeTag;
import com.ragagent.knowledge.dto.faq.FaqEntry;
import com.ragagent.knowledge.mapper.KnowledgeBaseMapper;
import com.ragagent.knowledge.mapper.KnowledgeMapper;
import com.ragagent.knowledge.repository.ChunkRepository;
import com.ragagent.knowledge.repository.KnowledgeTagRepository;
import com.ragagent.knowledge.service.access.KnowledgeAccessGuard;
import com.ragagent.knowledge.service.faq.FaqEntryQueryService;
import com.ragagent.knowledge.service.management.KnowledgeBaseService;
import com.ragagent.knowledge.service.management.KnowledgeRecycleService;
import com.ragagent.knowledge.service.management.KnowledgeService;
import com.ragagent.knowledge.service.management.KnowledgeTagService;
import com.ragagent.knowledge.storage.TenantFileStorage;
import com.ragagent.knowledge.support.ChunkRowViews;
import com.ragagent.knowledge.support.KnowledgeBaseRowViews;
import com.ragagent.knowledge.support.KnowledgeRowViews;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.platform.persistence.PgJsonTypeHandler;
import com.ragagent.platform.web.JsonMappers;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link KnowledgeCatalogPort} 的知识域实现：读写逐字转发两个门面服务，
 * 不新增缓冲、不改异常口径（409 重复冲突等由服务原样抛出）。
 */
@Component
public class KnowledgeCatalogAdapter implements KnowledgeCatalogPort {

    private static final ObjectMapper METADATA_MAPPER = JsonMappers.lenient();


    private final KnowledgeService knowledgeService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeAccessGuard accessGuard;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeMapper knowledgeMapper;
    private final KnowledgeTagService knowledgeTagService;
    private final KnowledgeTagRepository knowledgeTagRepository;
    private final TenantFileStorage tenantFileStorage;
    private final ChunkRepository chunkRepository;
    private final FaqEntryQueryService faqEntryQueryService;
    private final KnowledgeRecycleService recycleService;

    public KnowledgeCatalogAdapter(KnowledgeService knowledgeService,
                                   KnowledgeBaseService knowledgeBaseService,
                                   KnowledgeAccessGuard accessGuard,
                                   KnowledgeBaseMapper knowledgeBaseMapper,
                                   KnowledgeMapper knowledgeMapper,
                                   KnowledgeTagService knowledgeTagService,
                                   KnowledgeTagRepository knowledgeTagRepository,
                                   TenantFileStorage tenantFileStorage,
                                   ChunkRepository chunkRepository,
                                   FaqEntryQueryService faqEntryQueryService,
                                   KnowledgeRecycleService recycleService) {
        this.knowledgeService = knowledgeService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.accessGuard = accessGuard;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.knowledgeMapper = knowledgeMapper;
        this.knowledgeTagService = knowledgeTagService;
        this.knowledgeTagRepository = knowledgeTagRepository;
        this.tenantFileStorage = tenantFileStorage;
        this.chunkRepository = chunkRepository;
        this.faqEntryQueryService = faqEntryQueryService;
        this.recycleService = recycleService;
    }

    @Override
    public KnowledgeRowView getKnowledgeByIdOnly(String id) {
        return KnowledgeRowViews.rowView(knowledgeService.getKnowledgeByIdOnly(id));
    }

    @Override
    public List<KnowledgeRowView> getKnowledgeBatch(long tenantId, List<String> ids) {
        return KnowledgeRowViews.rowViews(knowledgeService.getKnowledgeBatch(tenantId, ids));
    }

    @Override
    public List<KnowledgeRowView> getKnowledgeBatchWithSharedAccess(long tenantId, List<String> ids) {
        return KnowledgeRowViews.rowViews(knowledgeService.getKnowledgeBatchWithSharedAccess(tenantId, ids));
    }

    @Override
    public KnowledgeRowView getKnowledgeInTenant(long tenantId, String id) {
        return KnowledgeRowViews.rowView(knowledgeService.getKnowledgeInTenant(tenantId, id));
    }

    @Override
    public List<String> listKnowledgeIdsByTagIds(long tenantId, String kbId, List<String> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) {
            return List.of();
        }
        return knowledgeTagRepository.listKnowledgeIdsByTagIds(tenantId, kbId, tagIds);
    }

    @Override
    public KnowledgePageView listKnowledge(String kbId, long page, long pageSize,
            String keyword, String parseStatus, String fileType,
            String folderPath, boolean folderPresent) {
        return KnowledgeRowViews.pageView(knowledgeService.listKnowledge(kbId, page, pageSize, keyword, parseStatus,
                fileType, folderPath, folderPresent));
    }

    @Override
    public String deleteKnowledge(String id) {
        return knowledgeService.deleteKnowledge(id);
    }

    @Override
    public KnowledgeRowView createFromPassageSync(String kbId, List<String> passages, String channel) {
        return KnowledgeRowViews.rowView(knowledgeService.createFromPassageSync(kbId, passages, channel));
    }

    @Override
    public KnowledgeRowView createFromFile(String kbId, byte[] fileContent, String fileName,
            String displayName, Object customMetadata, String channel) {
        return KnowledgeRowViews.rowView(knowledgeService.createFromFile(kbId, fileContent, fileName,
                displayName, JsonMappers.toJsonNode(customMetadata), channel));
    }

    @Override
    public ChunkRowView getChunkByIdOnly(String id) {
        return ChunkRowViews.rowView(chunkRepository.getChunkByIdOnly(id));
    }

    @Override
    public List<ChunkRowView> listChunksById(long tenantId, List<String> ids) {
        return ChunkRowViews.rowViews(chunkRepository.listChunksById(tenantId, ids));
    }

    @Override
    public List<ChunkRowView> listChunkByParentId(long tenantId, String parentId) {
        return ChunkRowViews.rowViews(chunkRepository.listChunkByParentId(tenantId, parentId));
    }

    @Override
    public List<ChunkRowView> listChunksByParentIDs(long tenantId, List<String> parentIds) {
        return ChunkRowViews.rowViews(chunkRepository.listChunksByParentIDs(tenantId, parentIds));
    }

    @Override
    public ChunkPageView listPagedChunksByKnowledgeId(long tenantId, String knowledgeId,
            int offset, int limit, List<String> chunkTypes, List<String> tagIds,
            String keyword, String searchField, String sortOrder,
            String knowledgeType, Boolean isEnabled) {
        return ChunkRowViews.pageView(chunkRepository.listPagedChunksByKnowledgeId(tenantId, knowledgeId,
                offset, limit, chunkTypes, tagIds, keyword, searchField, sortOrder,
                knowledgeType, isEnabled));
    }

    @Override
    public FaqEntryPageView listFaqEntries(String kbId, int page, int pageSize,
            List<String> tagUuids, long legacyTagSeqId,
            String keyword, String searchField, String sortOrder, Boolean isEnabled) {
        PageResult<FaqEntry> domain = faqEntryQueryService.listEntries(kbId, page, pageSize, tagUuids,
                legacyTagSeqId, keyword, searchField, sortOrder, isEnabled);
        if (domain == null) {
            return null;
        }
        List<FaqEntryView> items = domain.items() == null ? null
                : domain.items().stream().map(KnowledgeCatalogAdapter::toFaqEntryView).toList();
        return new FaqEntryPageView(items, domain.page(), domain.pageSize(), domain.total());
    }

    /** 域内 FAQ 条目 dto → 契约视图（字段＝契约面实际用度；null 透传）。 */
    static FaqEntryView toFaqEntryView(FaqEntry e) {
        if (e == null) {
            return null;
        }
        return new FaqEntryView(e.chunkId(), e.knowledgeBaseId(), e.knowledgeId(),
                e.standardQuestion(), e.similarQuestions(), e.answers(), e.createdAt());
    }

    @Override
    public byte[] readKnowledgeFile(long tenantId, String filePath) {
        return tenantFileStorage.read(tenantId, filePath);
    }

    @Override
    public KnowledgeBaseRowView getKnowledgeBase(String id) {
        return KnowledgeBaseRowViews.rowView(knowledgeBaseService.getKnowledgeBase(id));
    }

    @Override
    public KnowledgeBaseRowView createKnowledgeBase(KnowledgeBaseRowView kb) {
        KnowledgeBase saved = knowledgeBaseService.createKnowledgeBase(KnowledgeBaseRowViews.toEntity(kb));
        KnowledgeBaseRowViews.copyInto(kb, saved);
        return KnowledgeBaseRowViews.rowView(saved);
    }

    @Override
    public List<KnowledgeBaseRowView> listKnowledgeBases(String creator) {
        return KnowledgeBaseRowViews.rowViews(knowledgeBaseService.listKnowledgeBases(creator));
    }

    @Override
    public KnowledgeBaseRowView getAllTenantById(String id) {
        return KnowledgeBaseRowViews.rowView(knowledgeBaseService.getAllTenantById(id));
    }

    @Override
    public void deleteKnowledgeBase(String id) {
        knowledgeBaseService.deleteKnowledgeBase(id);
    }

    @Override
    public KnowledgeBaseRowView requireKbAccess(String kbId) {
        return KnowledgeBaseRowViews.rowView(accessGuard.requireKbAccess(kbId));
    }

    @Override
    public String retrieveDriver() {
        return knowledgeBaseService.retrieveDriver();
    }

    @Override
    public void updateKnowledgeBase(KnowledgeBaseRowView kb) {
        KnowledgeBase entity = KnowledgeBaseRowViews.toEntity(kb);
        // VLM 凭据只进不出（视图不含 apiKey）⇒ 写回前与库中现值合并，避免覆盖清空
        KnowledgeBase current = knowledgeBaseMapper.selectById(entity.getId());
        if (current != null && entity.getVlmConfig() != null && current.getVlmConfig() != null) {
            entity.getVlmConfig().setApiKey(current.getVlmConfig().getApiKey());
        }
        knowledgeBaseMapper.updateById(entity);
    }

    @Override
    public boolean hasFiles(String kbId) {
        Long tid = TenantContext.currentTenantId();
        Long count = knowledgeMapper.selectCount(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getKnowledgeBaseId, kbId)
                .eq(tid != null, Knowledge::getTenantId, tid)
                .isNull(Knowledge::getDeletedAt));
        return count != null && count > 0;
    }

    // ── 摄取写面（SQL 与查询口径自 datasource 侧 verbatim 搬入） ──

    @Override
    public KnowledgeBaseRowView findKnowledgeBaseRow(String kbId) {
        return KnowledgeBaseRowViews.rowView(knowledgeBaseMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, kbId)
                .isNull(KnowledgeBase::getDeletedAt)
                .last("LIMIT 1")));
    }

    @Override
    public KnowledgeRowView findKnowledgeByExternalId(long tenantId, String kbId, String dataSourceId, String externalId) {
        List<Knowledge> rows = knowledgeMapper.selectList(
                new LambdaQueryWrapper<Knowledge>()
                        .eq(Knowledge::getTenantId, tenantId)
                        .eq(Knowledge::getKnowledgeBaseId, kbId)
                        .apply("deleted_at IS NULL")
                        .apply("metadata->>'datasourceId' = {0}", dataSourceId)
                        .apply("metadata->>'externalId' = {0}", externalId)
                        .last("LIMIT 1"));
        return rows == null || rows.isEmpty() ? null : KnowledgeRowViews.rowView(rows.get(0));
    }

    @Override
    public List<KnowledgeRowView> listKnowledgeByMetadataPrefix(long tenantId, String kbId,
            String metadataKey, String rawPrefix) {
        List<Knowledge> rows = knowledgeMapper.selectList(
                new LambdaQueryWrapper<Knowledge>()
                        .eq(Knowledge::getTenantId, tenantId)
                        .eq(Knowledge::getKnowledgeBaseId, kbId)
                        .apply("deleted_at IS NULL")
                        .apply("metadata->>'" + metadataKey.replace("'", "''") + "' LIKE {0} ESCAPE '\\\\'",
                                escapeLike(rawPrefix) + "%"));
        return rows == null ? List.of() : KnowledgeRowViews.rowViews(rows);
    }

    @Override
    public List<KnowledgeRowView> listKnowledgeLive(long tenantId, String kbId) {
        List<Knowledge> rows = knowledgeMapper.selectList(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getTenantId, tenantId)
                .eq(Knowledge::getKnowledgeBaseId, kbId)
                .isNull(Knowledge::getDeletedAt));
        return rows == null ? List.of() : KnowledgeRowViews.rowViews(rows);
    }

    @Override
    public KnowledgeRowView findKnowledgeByHash(String kbId, String fileHash, String fileType) {
        return KnowledgeRowViews.rowView(knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getKnowledgeBaseId, kbId)
                .eq(Knowledge::getFileHash, fileHash)
                .eq(Knowledge::getFileType, fileType)
                .isNull(Knowledge::getDeletedAt)
                .last("LIMIT 1")));
    }

    @Override
    public void insertKnowledge(KnowledgeRowView knowledge) {
        knowledgeMapper.insert(KnowledgeRowViews.toEntity(knowledge));
    }

    @Override
    public String saveKnowledgeFile(long tenantId, String knowledgeId, String fileName, byte[] content) {
        return tenantFileStorage.save(tenantId, knowledgeId, fileName, content);
    }

    @Override
    public void deleteKnowledgeFile(long tenantId, String knowledgeId, String filePath) {
        tenantFileStorage.delete(tenantId, knowledgeId, filePath);
    }

    @Override
    public void updateKnowledgeMetadataRow(KnowledgeRowView knowledge, Map<String, String> metadata) {
        LambdaUpdateWrapper<Knowledge> uw = new LambdaUpdateWrapper<Knowledge>()
                .eq(Knowledge::getId, knowledge.getId())
                .eq(Knowledge::getTenantId, knowledge.getTenantId())
                .set(Knowledge::getMetadata, metadataNode(metadata), "typeHandler="
                        + PgJsonTypeHandler.class.getName())
                .set(Knowledge::getUpdatedAt, OffsetDateTime.now(ZoneOffset.UTC));
        knowledgeMapper.update(null, uw);
    }

    @Override
    public void softDeleteKnowledgeFull(String knowledgeId, long tenantId, OffsetDateTime now) {
        // 同步 worker 没有租户上下文，走显式租户的回收用例（软删 + 文件/图片/索引清理）。
        recycleService.recycleForTenant(tenantId, knowledgeId, now);
    }

    @Override
    public void hardDeleteKnowledge(String knowledgeId, long tenantId) {
        recycleService.recycleForTenant(tenantId, knowledgeId, OffsetDateTime.now(ZoneOffset.UTC));
        knowledgeMapper.delete(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getId, knowledgeId)
                .eq(Knowledge::getTenantId, tenantId));
    }

    @Override
    public void hardDeleteKnowledgeBatch(long tenantId, List<String> knowledgeIds) {
        if (knowledgeIds != null) {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            for (String id : knowledgeIds) {
                recycleService.recycleForTenant(tenantId, id, now);
            }
        }
        knowledgeMapper.delete(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getTenantId, tenantId)
                .in(Knowledge::getId, knowledgeIds));
    }

    @Override
    public KnowledgeTagView createKnowledgeTag(String kbId, String name, String color, int sortOrder) {
        return tagView(knowledgeTagService.createTag(kbId, name, color, sortOrder));
    }

    /** 实体 → L1 视图（域内投影；空透传）。 */
    private static KnowledgeTagView tagView(KnowledgeTag t) {
        if (t == null) {
            return null;
        }
        return new KnowledgeTagView(t.getId(), t.getKnowledgeBaseId(), t.getTenantId(),
                t.getName(), t.getColor(), t.getSortOrder());
    }

    @Override
    public KnowledgeTagView findKnowledgeTagByName(long tenantId, String kbId, String name) {
        return tagView(knowledgeTagRepository.getByName(tenantId, kbId, name));
    }

    /** metadata map → JSON 节点（自 datasource 侧 verbatim 搬入）。 */
    private static JsonNode metadataNode(Map<String, String> metadata) {
        ObjectNode node = METADATA_MAPPER.createObjectNode();
        if (metadata != null) {
            for (Map.Entry<String, String> e : metadata.entrySet()) {
                node.put(e.getKey(), e.getValue() == null ? "" : e.getValue());
            }
        }
        return node;
    }

    /** SQL LIKE 转义（自 datasource 侧 verbatim 搬入）。 */
    static String escapeLike(String v) {
        return v.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

}
