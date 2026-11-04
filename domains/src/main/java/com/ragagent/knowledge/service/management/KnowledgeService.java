package com.ragagent.knowledge.service.management;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.contract.error.AppError;
import com.ragagent.contract.error.BizException;
import com.ragagent.contract.error.ErrorCode;
import com.ragagent.contract.knowledge.KnowledgeDocumentFacts;
import com.ragagent.contract.knowledge.KnowledgeDocumentGateway;
import com.ragagent.contract.knowledge.KnowledgeIngest;
import com.ragagent.contract.knowledge.error.DuplicateKnowledgeException;
import com.ragagent.contract.knowledge.projection.KnowledgeProjectionAdapter;
import com.ragagent.contract.pagination.PageResult;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.domain.KnowledgeProcessingSpan;
import com.ragagent.knowledge.domain.KnowledgeTag;
import com.ragagent.knowledge.domain.KnowledgeTenants;
import com.ragagent.knowledge.domain.access.KnowledgeReadPort;
import com.ragagent.knowledge.dto.KnowledgeSearchScope;
import com.ragagent.knowledge.dto.doc.KnowledgeMoveProgress;
import com.ragagent.knowledge.dto.doc.UpdateKnowledgeRequest;
import com.ragagent.knowledge.dto.kb.KBCloneProgress;
import com.ragagent.knowledge.index.ChunkVectorIndexer;
import com.ragagent.knowledge.repository.ChunkRepository;
import com.ragagent.knowledge.repository.KnowledgeRepository;
import com.ragagent.knowledge.repository.KnowledgeTagRepository;
import com.ragagent.knowledge.service.KnowledgeAccessHelper;
import com.ragagent.knowledge.service.enrichment.KnowledgeSummaryService;
import com.ragagent.knowledge.service.query.KnowledgeSearchService;
import com.ragagent.knowledge.service.query.KnowledgeSpanService;
import com.ragagent.knowledge.storage.LocalStorageService;
import com.ragagent.knowledge.storage.TenantFileStorage;
import com.ragagent.knowledge.support.KnowledgeMetadataOverrides;
import com.ragagent.knowledge.support.KnowledgePaths;
import com.ragagent.knowledge.support.KnowledgeRowViews;
import com.ragagent.knowledge.support.TagViews;
import com.ragagent.platform.http.UrlBytesFetcher;
import com.ragagent.platform.security.InputSanitizer;
import com.ragagent.platform.web.JsonMappers;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识库文档管理门面：file/url/manual 创建、分页列表、get/update/delete、folders 与标签批量。
 *
 * <p>专项能力在同包协作者：解析队列（{@code KnowledgeProcessWorker}）、摘要
 * （{@code KnowledgeSummaryService}）、文件（{@code KnowledgeFileService}）、解析
 * （{@code KnowledgeParseService}）、批量（{@code KnowledgeBatchOpsService}）、文件夹
 * （{@code KnowledgeFolderService}）、span（{@code KnowledgeSpanService}）、检索
 * （{@code KnowledgeSearchService}）、移动/克隆（{@code KnowledgeMoveService} /
 * {@code KnowledgeCloneService}）。本类保留门面、创建路径、共享常量与跨协作者编排。</p>
 *
 * <p>状态机：pending→processing→(docreader→chunk→embed)→completed/failed；解析任务由
 * {@code KnowledgeProcessWorker} 消费。批量删除/重解析/清空的 HTTP 契约是
 * {@code task_id} + 文案。</p>
 */
@Service
public class KnowledgeService implements KnowledgeReadPort, KnowledgeDocumentGateway {

    // 文档主链路的门面与共享工具（常量/静态助手/直创建路径）；
    // 专项能力已拆出 ProcessWorker/Summary/File/Parse/BatchOps 等协作者，本类保持聚合面。

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    /** 5 个 canonical 阶段名（委任 {@link KnowledgeProcessingSpan#ALL_STAGES}，公共常量不变）。 */
    public static final List<String> ALL_STAGES = KnowledgeProcessingSpan.ALL_STAGES;

    private final KnowledgeRepository knowledgeRepository;
    private final ChunkRepository chunkRepository;
    private final KnowledgeTagRepository tagRepository;
    /** A3-3 尾批：租户感知文件存储（本地契约不变；云 provider 租户真正落对象存储）。 */
    private final TenantFileStorage fileStorage;
    private final KnowledgeIngest knowledgeIngest;
    /** 门面 helper 下沉（M2 解环）：requireKb/getKnowledge 等访问原语的收敛点。 */
    private final KnowledgeAccessHelper access;
    private final ChunkVectorIndexer chunkVectorIndexer;
    /** 图库仓储（D 批）：知识移动后清源命名空间。 */
    private final KnowledgeMoveService moveService;
    private final KnowledgeCloneService cloneService;
    private final KnowledgeSearchService searchService;
    private final KnowledgeFolderService folderService;
    private final KnowledgeSpanService spanService;
    private final KnowledgeSummaryService knowledgeSummaryService;
    private final KnowledgeFileService knowledgeFileService;
    private final KnowledgeParseService knowledgeParseService;
    private final KnowledgeBatchOpsService batchOpsService;
    private final KnowledgeTagBatchService tagBatchService;
    /** 删除回收用例（单条/批量共用同一套资源清单与失败语义）。 */
    private final KnowledgeRecycleService recycleService;

    public KnowledgeService(                            KnowledgeRepository knowledgeRepository,
                            ChunkRepository chunkRepository,
                            KnowledgeTagRepository tagRepository,
                            TenantFileStorage fileStorage,
                            KnowledgeIngest knowledgeIngest,
                            KnowledgeAccessHelper access,
                            ChunkVectorIndexer chunkVectorIndexer,
                            KnowledgeMoveService moveService,
                            KnowledgeCloneService cloneService,
                            KnowledgeSearchService searchService,
                            KnowledgeFolderService folderService,
                            KnowledgeSpanService spanService,
                            KnowledgeSummaryService knowledgeSummaryService,
                            KnowledgeFileService knowledgeFileService,
                            KnowledgeParseService knowledgeParseService,
                            KnowledgeBatchOpsService batchOpsService,
                            KnowledgeTagBatchService tagBatchService,
                            KnowledgeRecycleService recycleService) {
        this.knowledgeRepository = knowledgeRepository;
        this.chunkRepository = chunkRepository;
        this.tagRepository = tagRepository;
        this.fileStorage = fileStorage;
        this.knowledgeIngest = knowledgeIngest;
        this.access = access;
        this.chunkVectorIndexer = chunkVectorIndexer;
        this.moveService = moveService;
        this.cloneService = cloneService;
        this.searchService = searchService;
        this.folderService = folderService;
        this.spanService = spanService;
        this.knowledgeSummaryService = knowledgeSummaryService;
        this.knowledgeFileService = knowledgeFileService;
        this.knowledgeParseService = knowledgeParseService;
        this.batchOpsService = batchOpsService;
        this.tagBatchService = tagBatchService;
        this.recycleService = recycleService;
    }

    /**
 * 同包开放的历史入口： 起**委任**{@link KnowledgeTenants#tenantId}（实现已上移到 domain，
 * 供不再依赖门面的子服务复用）。
 */
    static long tenantId() {
        return KnowledgeTenants.tenantId();
    }

    /** 按 id 查 KB（软删不可见）。实现下沉在 {@link KnowledgeAccessHelper}。 */
    public KnowledgeBase findKb(String kbId) {
        return access.findKb(kbId);
    }

    public KnowledgeBase requireKb(String kbId) {
        return access.requireKb(kbId);
    }

    // ── 创建 ─────────────────────────────────────────────────────────────
    public Knowledge createFromFile(String kbId, byte[] fileContent, String fileName,
                                    String displayName, JsonNode customMetadata, String channel) {
        return createFromFile(kbId, fileContent, fileName, displayName, customMetadata, null, channel);
    }

    /**
     * @param processConfig 逐文档处理覆盖（可空）：目前只需 {@code imageProcessingConfig.ocrEnabled}，
     *                      落在 {@code metadata.processOverrides}，解析时优先于 KB 级配置。
     */
    public Knowledge createFromFile(String kbId, byte[] fileContent, String fileName,
                                    String displayName, JsonNode customMetadata,
                                    JsonNode processConfig, String channel) {
        KnowledgeBase kb = requireKb(kbId);
        long maxBytes = LocalStorageService.maxFileSizeBytes();
        if (fileContent.length > maxBytes) {
            throw new BizException(AppError.badRequest(
                    String.format("文件大小不能超过%dMB", LocalStorageService.maxFileSizeMb())));
        }
        String hash = LocalStorageService.md5Hex(fileContent);
        // 重复文件检查
        Knowledge dup = knowledgeRepository.findDuplicateByHash(kbId, hash);
        if (dup != null) {
            throw new DuplicateKnowledgeException(KnowledgeRowViews.rowView(dup), ErrorCode.KNOWLEDGE_DUPLICATE_FILE,
                    "文件已存在（相同内容）");
        }
        // 前端把「目标目录」编码进 fileName（如 "2026/合同/a.pdf"，拖拽上传目录同理）；
        // 不拆会退化成「标题带目录前缀、目录本身不存在」——文件夹树按 folder_path 聚合。
        KnowledgePaths.UploadIdentity upload = KnowledgePaths.resolveUploadIdentity(fileName, displayName);
        String folderPath = upload.folderPath();
        String baseName = upload.fileName();
        String title = upload.title();
        String fileType = baseName.contains(".")
                ? baseName.substring(baseName.lastIndexOf('.') + 1).toLowerCase() : "";

        Knowledge k = newKnowledge(kb, "file", title, channel);
        k.setFileName(baseName);
        k.setFolderPath(folderPath);
        k.setFileType(fileType);
        k.setFileSize((long) fileContent.length);
        k.setFileHash(hash);
        k.setFilePath(fileStorage.save(tenantId(), k.getId(), baseName, fileContent));
        k.setCustomMetadata(mergeCustomMetadata(customMetadata));
        k.setMetadata(KnowledgeMetadataOverrides.merge(k.getMetadata(), processConfig));
        knowledgeRepository.insert(k);
        knowledgeIngest.enqueueProcessing(k.getId());
        return k;
    }

    /** 拉取 URL 内容按文件入库（SSRF 在 controller；抓取口径与大小上限见 {@link UrlBytesFetcher}） */
    public Knowledge createFromUrl(String kbId, String url, String fileName, String fileType,
                                   String title, String channel) {
        KnowledgeBase kb = requireKb(kbId);
        UrlBytesFetcher.FetchedUrl fetched = UrlBytesFetcher.fetchWithMeta(url, null);
        byte[] content = fetched.body();
        String hash = LocalStorageService.md5Hex(content);
        Knowledge dup = knowledgeRepository.findDuplicateBySource(kbId, url);
        if (dup != null) {
            throw new DuplicateKnowledgeException(KnowledgeRowViews.rowView(dup), ErrorCode.KNOWLEDGE_DUPLICATE_URL,
                    "URL 已存在");
        }
        String fname = fileName != null && !fileName.isEmpty() ? fileName : KnowledgePaths.extractFileNameFromUrl(url);
        String resolvedType = UrlFileTypes.resolve(fileType, fname, fetched.contentType());
        // 标题优先级：显式 title > 页面 <title> > 文件名（URL 末段）
        String htmlTitle = "html".equals(resolvedType)
                ? HtmlTitles.extract(content, fetched.contentType()) : null;
        String resolvedTitle = title != null && !title.isEmpty() ? title
                : (htmlTitle != null ? htmlTitle : fname);
        Knowledge k = newKnowledge(kb, "file", resolvedTitle, channel);
        k.setSource(url);
        k.setFileName(fname);
        k.setFileType(resolvedType);
        k.setFileSize((long) content.length);
        k.setFileHash(hash);
        k.setFilePath(fileStorage.save(tenantId(), k.getId(), fname, content));
        k.setCustomMetadata(mergeCustomMetadata(null));
        knowledgeRepository.insert(k);
        knowledgeIngest.enqueueProcessing(k.getId());
        return k;
    }

    /** status 仅 draft/publish */
    public Knowledge createManual(String kbId, String title, String content, String status,
                                  String channel) {
        KnowledgeBase kb = requireKb(kbId);
        if (!"draft".equals(status) && !"publish".equals(status)) {
            throw new BizException(AppError.validation("状态仅支持 draft 或 publish"));
        }
        Knowledge k = newKnowledge(kb, "manual", title, channel);
        k.setSource("manual");
        k.setFileName(KnowledgePaths.ensureManualFileName(title));
        k.setFilePath("");
        k.setFileType("manual");
        k.setFileSize(0L);
        k.setFileHash("");
        // 入库后经 PG jsonb 规范化（键按长度+字节序），读回路径的 canonical 化在 PgJsonTypeHandler
        ObjectNode metadata = MAPPER.createObjectNode();
        metadata.put("content", content == null ? "" : content);
        metadata.put("format", "markdown");
        metadata.put("status", status);
        metadata.put("version", 1);
        metadata.put("updated_at", OffsetDateTime.now(ZoneOffset.UTC).toString());
        k.setMetadata(metadata);
        k.setCustomMetadata(mergeCustomMetadata(null));
        if ("draft".equals(status)) {
            k.setParseStatus("draft");
        }
        knowledgeRepository.insert(k);
        if ("publish".equals(status)) {
            knowledgeIngest.enqueueProcessing(k.getId());
        }
        return k;
    }

    /**
     * 段落<b>直接成 chunk</b>（不经 docreader/chunker），
     * 同步建索引后立即可检索。临时知识库（建库后立即检索）专用。
     * <p>固定语义：type="passage"、title 零值 ""、channel 空 → "web"；逐段 ValidateInput
     * 空段跳过后索引不回填）、Start/End 按码点数累计；终态
     * enable_status=enabled + processed_at + updated_at，parse_status 有文本 chunk 时
     * 保持 processing。</p>
     * （KnowledgeService 无 audit 依赖，文件/手工路径同形）；② 问题生成
     * （QuestionGenerationConfig）与多模态未翻（与 worker 路径一致）；③ 向量/keyword
     */
    public Knowledge createFromPassageSync(String kbId, List<String> passages, String channel) {
        KnowledgeBase kb = requireKb(kbId);

        List<String> safePassages = new ArrayList<>(passages.size());
        for (int i = 0; i < passages.size(); i++) {
            String p = passages.get(i) == null ? "" : passages.get(i);
            String safe = InputSanitizer.validateInput(p);
            if (safe == null) {
                throw new BizException(AppError.validation("段落 " + (i + 1) + " 包含非法内容"));
            }
            safePassages.add(safe);
        }

        Knowledge k = newKnowledge(kb, "passage", "", channel);
        knowledgeRepository.insert(k);

        // 先原子翻 processing 再处理
        k.setParseStatus(Knowledge.PARSE_PROCESSING);
        k.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        knowledgeRepository.updateById(k);

        processPassagesSync(kb, k, safePassages);
        return k;
    }

    /**
     * 段落同步处理体：
     * 段落 1:1 成 chunk（空段跳过）→ 落库（前后链）→ 向量化 → 终态落库。
     */
    private void processPassagesSync(KnowledgeBase kb, Knowledge k, List<String> passages) {
        List<Chunk> chunks = new ArrayList<>(passages.size());
        int start = 0;
        int end = 0;
        String prevId = null;
        for (int i = 0; i < passages.size(); i++) {
            String p = passages.get(i);
            if (p.isEmpty()) {
                continue;
            }
            end += p.codePointCount(0, p.length());
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            Chunk c = new Chunk();
            c.setId(UUID.randomUUID().toString());
            c.setCreatedAt(now);
            c.setUpdatedAt(now);
            c.setTenantId(k.getTenantId());
            c.setKnowledgeId(k.getId());
            c.setKnowledgeBaseId(k.getKnowledgeBaseId());
            c.setContent(p);
            c.setSourceContent(p);
            c.setChunkIndex(i); // 段落索引
            c.setStartAt(start);
            c.setEndAt(end);
            c.setChunkType("text");
            c.setIsEnabled(true); //  true（实体默认亦为 true）
            c.setPreChunkId(prevId);
            chunks.add(c);
            prevId = c.getId();
            start = end;
        }
        for (int i = 0; i < chunks.size(); i++) {
            if (i + 1 < chunks.size()) {
                chunks.get(i).setNextChunkId(chunks.get(i + 1).getId());
            }
            chunkRepository.insert(chunks.get(i));
        }
        if (!chunks.isEmpty()) {
            chunkVectorIndexer.updateChunkVector(kb.getId(), chunks);
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        k.setParseStatus(chunks.isEmpty() ? Knowledge.PARSE_COMPLETED : Knowledge.PARSE_PROCESSING);
        k.setEnableStatus("enabled");
        k.setProcessedAt(now);
        k.setUpdatedAt(now);
        knowledgeRepository.updateById(k);
    }

    private Knowledge newKnowledge(KnowledgeBase kb, String type, String title, String channel) {
        Knowledge k = new Knowledge();
        k.setId(UUID.randomUUID().toString());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        k.setCreatedAt(now);
        k.setUpdatedAt(now);
        k.setTenantId(tenantId());
        k.setKnowledgeBaseId(kb.getId());
        k.setType(type);
        // Source 按来源各异——file 上传为零值 ""，url 记 url，manual 记 manual
        k.setTitle(title == null ? "" : title);
        k.setParseStatus(Knowledge.PARSE_PENDING);
        k.setEnableStatus("disabled"); // 契约样例锁定：上传后 disabled，处理完成转 enabled
        k.setEmbeddingModelId(kb.getEmbeddingModelId());
        k.setChannel(channel == null || channel.isEmpty() ? "web" : channel); // 缺省 web
        k.setFolderPath(""); // PG 列 NOT NULL，空串为缺省
        return k;
    }

    private static JsonNode mergeCustomMetadata(JsonNode provided) {
        if (provided != null && provided.isObject()) {
            return provided;
        }
        return MAPPER.createObjectNode();
    }

    // ── 查询 / 更新 / 删除 ────────────────────────────────────────────────

    /** 真分页（page 默认 1，page_size 默认 20 上限 1000）。 */
    public PageResult<Knowledge> listKnowledge(String kbId, long page, long pageSize,
                                         String keyword, String parseStatus, String fileType,
                                         String folderPath, boolean folderPresent) {
        requireKb(kbId);
        return knowledgeRepository.searchPage(
                kbId, page, pageSize, keyword, parseStatus, fileType, folderPath, folderPresent);
    }

    public Knowledge getKnowledge(String id) {
        return access.getKnowledge(id);
    }

    /** 无租户过滤（守卫/权限解析用）。 */
    public Knowledge getKnowledgeByIdOnly(String id) {
        return knowledgeRepository.findByIdOnly(id);
    }

    /** 调用者空间内的可空读取。 */
    public Knowledge getKnowledgeInTenant(long tenantId, String id) {
        return access.getKnowledgeInTenant(tenantId, id);
    }

    /**
     * 按 (tenant, ids) 批量取，<b>不回填 tags</b>
     */
    public List<Knowledge> getKnowledgeBatch(long tenantId, List<String> ids) {
        return access.getKnowledgeBatch(tenantId, ids);
    }

    /** 共享访问口径：当前与 {@link #getKnowledgeBatch} 同实现（端口语义保留）。 */
    public List<Knowledge> getKnowledgeBatchWithSharedAccess(long tenantId, List<String> ids) {
        return access.getKnowledgeBatchWithSharedAccess(tenantId, ids);
    }

    /**
     * 跨域只读端口的实现（{@link KnowledgeDocumentGateway}）：检索结果装配按 id 批量取
     * 文档元数据，语义与 {@link #getKnowledgeBatchWithSharedAccess} 一致。
     */
    @Override
    public List<KnowledgeDocumentFacts> findAccessibleDocuments(long tenantId, List<String> knowledgeIds) {
        List<KnowledgeDocumentFacts> out = new ArrayList<>();
        for (Knowledge k : getKnowledgeBatchWithSharedAccess(tenantId, knowledgeIds)) {
            out.add(KnowledgeProjectionAdapter.factsOf(KnowledgeRowViews.rowView(k)));
        }
        return out;
    }

    /** 有关系才回填（无关系保持 null）。 */
    public void attachTags(Knowledge k) {
        if (k == null) {
            return;
        }
        List<KnowledgeTag> rows = tagRepository.listTagsWithKnowledgeId(k.getId());
        if (!rows.isEmpty()) {
            k.setTags(new ArrayList<>(rows.stream().map(TagViews::tagView).toList()));
        }
    }





    /** title/description(指针)/custom_metadata 部分更新 */
    public Knowledge updateKnowledge(String id, UpdateKnowledgeRequest req) {
        Knowledge k = getKnowledge(id);
        if (req != null) {
            if (req.title() != null) {
                k.setTitle(req.title());
            }
            if (req.description() != null) {
                k.setDescription(req.description().isNull() ? "" : req.description().asText());
                // description 显式更新联动 summary_status
                k.setSummaryStatus(k.getDescription().isEmpty() ? "none" : "completed");
            }
            JsonNode cm = req.customMetadata();
            if (cm != null) {
                k.setCustomMetadata(cm.isObject() ? cm : MAPPER.createObjectNode());
            }
        }
        k.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        knowledgeRepository.updateById(k);
        return getKnowledge(id);
    }

    /**
     * 删除文档：库内状态与物理清理的编排全部收口到 {@link KnowledgeRecycleService}。
     * 这里只保留门面入口（HTTP 契约的 task id 不变）。
     */
    public String deleteKnowledge(String id) {
        return recycleService.recycleOne(id);
    }

    // ── folders / 文件夹移动 / 重命名（委托 KnowledgeFolderService） ─────

    public JsonNode folderTree(String kbId) {
        return folderService.folderTree(kbId);
    }


    @Transactional
    public long moveKnowledgeToFolder(String kbId, List<String> ids, String folderPath) {
        return folderService.moveKnowledgeToFolder(kbId, ids, folderPath);
    }

    @Transactional
    public long renameKnowledgeFolder(String kbId, String from, String to) {
        return folderService.renameKnowledgeFolder(kbId, from, to);
    }

    public List<Knowledge> loadKnowledgeWriteBatch(List<String> ids, String grantedKbId) {
        return folderService.loadKnowledgeWriteBatch(ids, grantedKbId);
    }

    public Knowledge loadKnowledgeWrite(String id) {
        return folderService.loadKnowledgeWrite(id);
    }

    // ── 文档操作面（委托 KnowledgeSpanService） ─────────────────────

    public ObjectNode knowledgeSpans(Knowledge knowledge, int requestedAttempt) {
        return spanService.knowledgeSpans(knowledge, requestedAttempt);
    }

    // ── 解析状态机 + 摘要管线（委托专项服务） ────

    public Knowledge regenerateKnowledgeSummary(String id) {
        return knowledgeSummaryService.regenerateKnowledgeSummary(id);
    }

    public void requestPostProcessSummaryGeneration(String knowledgeId) {
        knowledgeSummaryService.requestPostProcessSummaryGeneration(knowledgeId);
    }

    public void requestKnowledgeSummaryRefresh(String id) {
        knowledgeSummaryService.requestKnowledgeSummaryRefresh(id);
    }

    public Knowledge updateManualKnowledge(String id, String title, String content,
                                           String status, String channel) {
        return knowledgeFileService.updateManualKnowledge(id, title, content, status, channel);
    }

    public Knowledge reparseKnowledge(String id) {
        return knowledgeParseService.reparseKnowledge(id);
    }

    /** 重解析并替换逐文档处理覆盖（弹窗里的选择要生效，不能沿用它上次导入时的值）。 */
    public Knowledge reparseKnowledge(String id, JsonNode processConfig) {
        return knowledgeParseService.reparseKnowledge(id, processConfig);
    }

    public Knowledge cancelKnowledgeParse(String id) {
        return knowledgeParseService.cancelKnowledgeParse(id);
    }

    public KnowledgeFileService.KnowledgeFileStream openKnowledgeFile(String id) {
        return knowledgeFileService.openKnowledgeFile(id);
    }

    @Transactional
    public void updateImageInfo(String knowledgeId, String chunkId, String rawImageInfo) {
        knowledgeFileService.updateImageInfo(knowledgeId, chunkId, rawImageInfo);
    }

    // ── tags 批量 ──────────────────────────────────────────────────

    /**
     * * authorizedKBID 为空 = 未显式给 kb_id（由首条 knowledge 推导的授权范围）。
     */
    /** 标签批量；事务在 {@link KnowledgeTagBatchService#updateTagBatch}。 */
    public void updateKnowledgeTagBatch(String authorizedKBId, Map<String, List<String>> updates) {
        tagBatchService.updateTagBatch(authorizedKBId, updates);
    }

    // ── 批量删除 / 批量重解析 / 清空（委托 KnowledgeBatchOpsService） ──

    @Transactional
    public String batchDeleteKnowledge(String kbId, List<String> ids) {
        return batchOpsService.batchDeleteKnowledge(kbId, ids);
    }

    public String batchReparseKnowledge(String kbId, List<String> ids) {
        return batchOpsService.batchReparseKnowledge(kbId, ids);
    }

    public int rebuildKnowledgeBaseIndex(String kbId) {
        return batchOpsService.rebuildKnowledgeBaseIndex(kbId);
    }

    @Transactional
    public int clearKnowledgeBaseContents(String kbId) {
        return batchOpsService.clearKnowledgeBaseContents(kbId);
    }

    public Knowledge loadById(String id) {
        return batchOpsService.loadById(id);
    }

    public void updateStatus(String id, String parseStatus, String errorMessage, Boolean enable) {
        batchOpsService.updateStatus(id, parseStatus, errorMessage, enable);
    }

    // ── 搜索（委托 KnowledgeSearchService） ──────────────────────────────

    public KnowledgeSearchService.SearchOutcome searchKnowledge(String keyword, int offset, int limit,
            List<String> fileTypes) {
        return searchService.searchKnowledge(keyword, offset, limit, fileTypes);
    }

    public KnowledgeSearchService.SearchOutcome searchKnowledgeInScopes(
            List<KnowledgeSearchScope> scopes, String keyword,
            int offset, int limit, List<String> fileTypes) {
        return searchService.searchKnowledgeInScopes(scopes, keyword, offset, limit, fileTypes);
    }

    // ── Move（委托 KnowledgeMoveService；空间/段拆分见该类） ─────────────

    public void startKnowledgeMove(long tenantId, String taskId, List<String> knowledgeIds,
                                   String sourceKbId, String targetKbId, String mode) {
        moveService.startKnowledgeMove(tenantId, taskId, knowledgeIds, sourceKbId, targetKbId, mode);
    }

    public void saveKnowledgeMoveProgress(KnowledgeMoveProgress p) {
        moveService.saveKnowledgeMoveProgress(p);
    }

    public KnowledgeMoveProgress getKnowledgeMoveProgress(String taskId) {
        return moveService.getKnowledgeMoveProgress(taskId);
    }

    // ── KB clone / Duplicate / 跨库兼容性（委托 KnowledgeCloneService） ──

    public void startKBClone(long tenantId, String taskId, String sourceId, String targetId,
            boolean createTarget, String creatorId) {
        cloneService.startKBClone(tenantId, taskId, sourceId, targetId, createTarget, creatorId);
    }

    public void saveKBCloneProgress(KBCloneProgress p) {
        cloneService.saveKBCloneProgress(p);
    }

    public KBCloneProgress getKBCloneProgress(String taskId) {
        return cloneService.getKBCloneProgress(taskId);
    }

    public KnowledgeBase duplicateKnowledgeBase(String sourceId) {
        return cloneService.duplicateKnowledgeBase(sourceId);
    }

    public static void validateKBTransferCompatibility(KnowledgeBase source, KnowledgeBase target,
            String requestedVectorStoreId) {
        KnowledgeCloneService.validateKBTransferCompatibility(source, target, requestedVectorStoreId);
    }

    public static void validateCloneCompatibility(KnowledgeBase source, KnowledgeBase target) {
        KnowledgeCloneService.validateCloneCompatibility(source, target);
    }



    /** 入队端口外提为 {@code KnowledgeProcessingQueue}
     *  （实现：KnowledgeProcessWorker）——原嵌套接口让全部提交方反向依赖门面类型，
     *  是 M2 解环的根之一，已删。 */
}
