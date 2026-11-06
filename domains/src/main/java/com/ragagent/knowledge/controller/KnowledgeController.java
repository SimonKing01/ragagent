package com.ragagent.knowledge.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.contract.error.AppError;
import com.ragagent.contract.error.BizException;
import com.ragagent.contract.tenant.TenantRole;
import com.ragagent.knowledge.dto.TaskIdResponse;
import com.ragagent.knowledge.dto.chunk.UpdateImageInfoRequest;
import com.ragagent.knowledge.dto.doc.ClearContentsResponse;
import com.ragagent.knowledge.dto.doc.CreateFromUrlRequest;
import com.ragagent.knowledge.dto.doc.CreateManualRequest;
import com.ragagent.knowledge.dto.doc.KnowledgeListResponse;
import com.ragagent.knowledge.dto.doc.KnowledgeResponse;
import com.ragagent.knowledge.dto.doc.UpdateKnowledgeRequest;
import com.ragagent.knowledge.dto.doc.UpdateManualRequest;
import com.ragagent.knowledge.service.management.KnowledgeFileService;
import com.ragagent.knowledge.service.management.KnowledgeService;
import com.ragagent.knowledge.storage.LocalStorageService;
import com.ragagent.knowledge.support.access.KnowledgeRouteGuards;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.platform.security.LogSanitizer;
import com.ragagent.platform.security.SsrfGuard;
import com.ragagent.platform.storage.fileserve.FileTransport;
import com.ragagent.platform.text.ContentTypeByFilename;
import com.ragagent.platform.web.ApiResponse;
import com.ragagent.platform.web.ApiResult;
import com.ragagent.platform.web.JsonMappers;
import com.ragagent.platform.web.NonNullBody;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识文档主面：创建（文件/URL/手工）、列表/详情/批量取、解析生命周期
 * （重析/取消/进度 span）、下载/预览、图片信息、摘要与单文档更新/删除。
 * 运营操作面（搜索/批量/搬移/标签/文件夹）在 {@link KnowledgeOperationsController}。
 *
 * <p>守卫链经 {@link KnowledgeRouteGuards}（全局缺失 404 大写 K → [ownership 403
 * 纯字符串] → KB 访问 404/403）；重复文档 409 为特殊信封
 * （code/data/message/success），不走全局错误处理器。</p>
 */
@RestController
@ApiResult
@RequestMapping("/api/v1")
@Tag(name = "Knowledge")
public class KnowledgeController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeController.class);
    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    private final KnowledgeService knowledgeService;
    private final KnowledgeRouteGuards guards;
    private final SsrfGuard ssrfGuard;

    public KnowledgeController(KnowledgeService knowledgeService,
                               KnowledgeRouteGuards guards,
                               SsrfGuard ssrfGuard) {
        this.knowledgeService = knowledgeService;
        this.guards = guards;
        this.ssrfGuard = ssrfGuard;
    }

    /**
     * multipart 上传文档（同步受理，异步解析）。
     *
     * <p>返回 201 与新文档视图；真正的"解析 → 分块 → 向量化"由后台流水线执行，
     * 前端凭响应里的 {@code parseStatus} 与进度接口跟进。</p>
     *
     * <p>{@code processConfig} 中的逐文档覆盖（分块/解析引擎/图谱抽取/图片 OCR/推荐问题）
     * 整体写入 {@code metadata.processOverrides}，解析与收尾时优先于 KB 级配置
     * （消费点见 {@link com.ragagent.knowledge.support.ProcessOverridesResolver}）。</p>
     */
    @PostMapping("/knowledge-bases/{id}/knowledge/file")
    public ResponseEntity<KnowledgeResponse> createFromFile(
            @PathVariable("id") String kbId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "fileName", required = false) String fileName,
            @RequestParam(value = "metadata", required = false) String metadataJson,
            @RequestParam(value = "tagIds", required = false) String tagIds,
            @RequestParam(value = "channel", required = false) String channel,
            @RequestParam(value = "processConfig", required = false) String processConfigJson)
            throws IOException {
        log.info("Start creating knowledge from file, KB: {}", kbId);
        JsonNode customMetadata = KnowledgeRouteGuards.parseJsonParam(MAPPER, metadataJson, "metadata");
        // 逐文档处理覆盖：整体落 metadata.processOverrides，解析时优先于 KB 级配置；
        // 解析失败按「未传」处理，不阻断上传。
        JsonNode processConfig = KnowledgeRouteGuards.parseJsonParam(MAPPER, processConfigJson, "processConfig");
        byte[] content = LocalStorageService.readAll(file.getInputStream());
        var k = knowledgeService.createFromFile(
                kbId, content,
                fileName != null && !fileName.isEmpty() ? fileName : file.getOriginalFilename(),
                fileName, customMetadata, processConfig, channel);
        return ResponseEntity.status(HttpStatus.CREATED).body(KnowledgeResponse.from(k));
    }

    /** 从 URL 创建：SSRF 校验先行。 */
    @PostMapping("/knowledge-bases/{id}/knowledge/url")
    public ResponseEntity<KnowledgeResponse> createFromUrl(
            @PathVariable("id") String kbId,
            @Valid @NonNullBody @RequestBody CreateFromUrlRequest req) {
        log.info("Start creating knowledge from URL, KB: {}", kbId);
        try {
            ssrfGuard.validateURLForSSRF(req.url());
        } catch (SsrfGuard.SsrfException e) {
            throw new BizException(AppError.badRequest(ssrfGuard.formatSSRFError("URL", req.url(), e)));
        }
        var k = knowledgeService.createFromUrl(kbId, req.url(),
                req.fileName(), req.fileType(), req.title(), req.channel());
        return ResponseEntity.status(HttpStatus.CREATED).body(KnowledgeResponse.from(k));
    }

    /** 手工创建纯文本文档。 */
    @PostMapping("/knowledge-bases/{id}/knowledge/manual")
    public ResponseEntity<KnowledgeResponse> createManual(
            @PathVariable("id") String kbId,
            @Valid @NonNullBody @RequestBody CreateManualRequest req) {
        log.info("Start creating manual knowledge, KB: {}", kbId);
        var k = knowledgeService.createManual(kbId, req.title(),
                req.content() == null ? "" : req.content(),
                req.status() == null ? "" : req.status(),
                req.channel());
        return ResponseEntity.status(HttpStatus.CREATED).body(KnowledgeResponse.from(k));
    }

    /** 知识库内文档分页列表：{@code {items, page, pageSize, total}}。 */
    @GetMapping("/knowledge-bases/{id}/knowledge")
    public ResponseEntity<KnowledgeListResponse> listKnowledge(
            @PathVariable("id") String kbId,
            @RequestParam(value = "page", defaultValue = "1") long page,
            @RequestParam(value = "pageSize", defaultValue = "20") long pageSize,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "parseStatus", required = false) String parseStatus,
            @RequestParam(value = "fileType", required = false) String fileType,
            @RequestParam(value = "folderPath", required = false) String folderPath) {
        log.info("Start listing knowledge, KB: {}", kbId);
        if (page < 1) {
            throw new BizException(AppError.badRequest("page must be at least 1"));
        }
        if (pageSize < 1 || pageSize > 1000) {
            throw new BizException(AppError.badRequest("pageSize must be between 1 and 1000"));
        }
        boolean folderPresent = folderPath != null;
        var result = knowledgeService.listKnowledge(
                        kbId, page, pageSize, keyword, parseStatus, fileType, folderPath, folderPresent);
        List<KnowledgeResponse> items = result.items().stream()
                .map(KnowledgeResponse::from)
                .toList();
        return ResponseEntity.ok(new KnowledgeListResponse(items, page, pageSize, result.total()));
    }

    /** 文件夹树（结构由数据驱动，保持不透明 JSON 载荷）。 */
    @GetMapping("/knowledge-bases/{id}/knowledge/folders")
    public ResponseEntity<JsonNode> listFolders(
            @PathVariable("id") String kbId) {
        return ResponseEntity.ok(knowledgeService.folderTree(kbId));
    }

    @GetMapping("/knowledge/{id}")
    public ResponseEntity<KnowledgeResponse> getKnowledge(@PathVariable("id") String id) {
        log.info("Start retrieving knowledge, ID: {}", id);
        String safeId = requireKnowledgeId(id);
        guards.resolveKnowledgeByGuard(safeId, false);
        return ResponseEntity.ok(KnowledgeResponse.from(knowledgeService.getKnowledge(safeId)));
    }

    /**
     * 按 ID 批量取文档（直接返回数组；查不到的 ID 静默省略）。
     *
     * <p>query 绑定：{@code ids} required（{@code "ids="} → {@code [""]} 通过绑定，
     * 服务层查不到行 → 空数组）。</p>
     */
    @GetMapping("/knowledge/batch")
    public ResponseEntity<List<KnowledgeResponse>> getKnowledgeBatch(
            @RequestParam(value = "ids", required = false) List<String> ids,
            @RequestParam(value = "kbId", required = false) String kbId) {
        long callerTenant = KnowledgeRouteGuards.tenantId();
        if (callerTenant == 0) {
            throw new BizException(AppError.unauthorized("Unauthorized"));
        }
        if (ids == null) {
            throw new BizException(AppError.badRequest("请求参数不合法").withDetails("ids: 不能为空"));
        }
        var knowledges = LogSanitizer.sanitize(kbId == null ? "" : kbId).isEmpty()
                ? knowledgeService.getKnowledgeBatchWithSharedAccess(callerTenant, ids).stream()
                        .map(KnowledgeResponse::from).toList()
                : filteredKnowledgeBatch(callerTenant, ids, kbId);
        return ResponseEntity.ok(knowledges);
    }

    /** {@code /stages} 与 {@code /spans} 两个路径同 handler（不透明 JSON 载荷）。 */
    @GetMapping({"/knowledge/{id}/stages", "/knowledge/{id}/spans"})
    public ResponseEntity<ObjectNode> getKnowledgeSpans(
            @PathVariable("id") String id,
            @RequestParam(value = "attempt", required = false) String attempt) {
        String safeId = requireKnowledgeId(id);
        guards.resolveKnowledgeByGuard(safeId, false);
        var knowledge = knowledgeService.getKnowledge(safeId);
        int requestedAttempt = 0;
        if (attempt != null && !attempt.trim().isEmpty()) {
            try {
                int n = Integer.parseInt(attempt.trim());
                if (n > 0) {
                    requestedAttempt = n;
                }
            } catch (NumberFormatException ignored) {
                // 非法 attempt 保持 0（取最新）
            }
        }
        return ResponseEntity.ok(knowledgeService.knowledgeSpans(knowledge, requestedAttempt));
    }

    /** 无既有摘要 → 同步重生；有 → 入队刷新并回读 pending 态。 */
    @PostMapping("/knowledge/{id}/regenerate-summary")
    public ResponseEntity<KnowledgeResponse> regenerateKnowledgeSummary(
            @PathVariable("id") String id) {
        String safeId = requireKnowledgeId(id);
        var knowledge = guards.resolveKnowledgeByGuard(safeId, true);
        var result = knowledge.getSummaryStatus() == null || knowledge.getSummaryStatus().isEmpty()
                || "none".equals(knowledge.getSummaryStatus())
                ? KnowledgeResponse.from(knowledgeService.regenerateKnowledgeSummary(safeId))
                : refreshAndReload(safeId);
        return ResponseEntity.ok(result);
    }

    @PutMapping("/knowledge/manual/{id}")
    public ResponseEntity<KnowledgeResponse> updateManualKnowledge(
            @PathVariable("id") String id,
            @Valid @NonNullBody @RequestBody UpdateManualRequest req) {
        String safeId = requireKnowledgeId(id);
        guards.resolveKnowledgeByGuard(safeId, true);
        var k = knowledgeService.updateManualKnowledge(safeId,
                req.title(), req.content(), req.status(), req.channel());
        return ResponseEntity.ok(KnowledgeResponse.from(k));
    }

    /**
     * 重新解析（异步受理）：返回受理后的文档视图，前端凭 {@code parseStatus} 跟进。
     *
     * <p>body 可整体省略（保留上传时的 {@code metadata.processOverrides}）；带
     * {@code processConfig} 时用它**替换**既有覆盖——重解析弹窗里的选择必须生效。</p>
     */
    @PostMapping("/knowledge/{id}/reparse")
    public ResponseEntity<KnowledgeResponse> reparseKnowledge(
            @PathVariable("id") String id,
            @RequestBody(required = false) JsonNode body) {
        String safeId = requireKnowledgeId(id);
        guards.resolveKnowledgeByGuard(safeId, true);
        JsonNode processConfig = body == null ? null : body.get("processConfig");
        var k = knowledgeService.reparseKnowledge(safeId, processConfig);
        return ResponseEntity.ok(KnowledgeResponse.from(k));
    }

    /** 取消进行中的解析（异步受理）。 */
    @PostMapping("/knowledge/{id}/cancel-parse")
    public ResponseEntity<KnowledgeResponse> cancelKnowledgeParse(
            @PathVariable("id") String id) {
        String safeId = requireKnowledgeId(id);
        guards.resolveKnowledgeByGuard(safeId, true);
        var k = knowledgeService.cancelKnowledgeParse(safeId);
        return ResponseEntity.ok(KnowledgeResponse.from(k));
    }

    /** Contributor 路由门 + KBAccessWrite + handler 内 Editor 检查（守卫链 write 分支）。 */
    @GetMapping("/knowledge/{id}/download")
    public void downloadKnowledgeFile(@PathVariable("id") String id,
                                      HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String safeId = requireKnowledgeId(id);
        guards.resolveKnowledgeByGuard(safeId, false, true);
        KnowledgeFileService.KnowledgeFileStream file = knowledgeService.openKnowledgeFile(safeId);
        response.setHeader("Content-Description", "File Transfer");
        response.setHeader("Content-Transfer-Encoding", "binary");
        response.setHeader("Expires", "0");
        FileTransport.serve(response, request, file.opened(), new FileTransport.Options(
                file.filename(), true, "application/octet-stream",
                KnowledgeRouteGuards.contentDisposition("attachment",
                        KnowledgeRouteGuards.safeFilename(file.filename())),
                "private, no-store", file.opened().size()));
    }

    /** Viewer + KBAccessRead，Content-Type 按扩展名。 */
    @GetMapping("/knowledge/{id}/preview")
    public void previewKnowledgeFile(@PathVariable("id") String id,
                                     HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String safeId = requireKnowledgeId(id);
        guards.resolveKnowledgeByGuard(safeId, false);
        KnowledgeFileService.KnowledgeFileStream file = knowledgeService.openKnowledgeFile(safeId);
        ContentTypeByFilename.Record safe = ContentTypeByFilename.safe(file.filename());
        FileTransport.serve(response, request, file.opened(), new FileTransport.Options(
                file.filename(), !safe.inline(), safe.contentType(),
                KnowledgeRouteGuards.contentDisposition(safe.inline() ? "inline" : "attachment",
                        KnowledgeRouteGuards.safeFilename(file.filename())),
                "private, no-store", file.opened().size()));
    }

    /** 更新分块图片信息（无响应体）。 */
    @PutMapping("/knowledge/image/{id}/{chunkId}")
    public ApiResponse<Void> updateImageInfo(
            @PathVariable("id") String id,
            @PathVariable("chunkId") String chunkId,
            @Valid @NonNullBody @RequestBody UpdateImageInfoRequest req) {
        String safeId = requireKnowledgeId(id);
        String safeChunkId = LogSanitizer.sanitize(chunkId);
        if (safeChunkId.isEmpty()) {
            throw new BizException(AppError.badRequest("Chunk ID cannot be empty"));
        }
        guards.resolveKnowledgeByGuard(safeId, true);
        String imageInfo = req.imageInfo() == null ? "" : req.imageInfo();
        knowledgeService.updateImageInfo(safeId, safeChunkId, imageInfo);
        return ApiResponse.ok();   // 204 退役（空体与「外壳恒存在」冲突）
    }

    /** 守卫链 + 部分更新（更新字段集合见 {@link UpdateKnowledgeRequest}）。 */
    /** 文档部分更新（更新字段集合见 {@link UpdateKnowledgeRequest}）。 */
    @PutMapping("/knowledge/{id}")
    public ResponseEntity<KnowledgeResponse> updateKnowledge(
            @PathVariable("id") String id,
            @RequestBody(required = false) UpdateKnowledgeRequest request) {
        log.info("Start updating knowledge, ID: {}", id);
        String safeId = requireKnowledgeId(id);
        guards.resolveKnowledgeByGuard(safeId, true);
        var k = knowledgeService.updateKnowledge(safeId, request);
        return ResponseEntity.ok(KnowledgeResponse.from(k));
    }

    /** 守卫链 + 搬移中拒绝（service 内）+ 异步删除语义。 */
    /**
 * 删除文档：异步受理（索引清理在后台进行）→ 202 + {@code {taskId}}。
 *
 * <p>为何不是 204：删除尚未完成，前端需要 taskId 轮询进度；
 * 同步完成的删除才用 204（见契约文档）。</p>
 */
    @DeleteMapping("/knowledge/{id}")
    public ResponseEntity<TaskIdResponse> deleteKnowledge(@PathVariable("id") String id) {
        log.info("Start deleting knowledge, ID: {}", id);
        String safeId = requireKnowledgeId(id);
        guards.resolveKnowledgeByGuard(safeId, true);
        String taskId = knowledgeService.deleteKnowledge(safeId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new TaskIdResponse(taskId));
    }

    /** Admin 路由门 + 仅 owner 租户可清。 */
    /** 清空知识库内容：异步受理 → 202 + {@code {deletedCount}}。 */
    @DeleteMapping("/knowledge-bases/{id}/knowledge")
    public ResponseEntity<ClearContentsResponse> clearKnowledgeBaseContents(
            @PathVariable("id") String id) {
        log.info("Start clearing knowledge base contents");
        String kbId = LogSanitizer.sanitize(id);
        var kb = guards.requireKbAccess(kbId);
        Long callerTenant = TenantContext.currentTenantId();
        String role = TenantContext.currentRole();
        boolean admin = TenantRole.fromString(role)
                .hasPermission(TenantRole.ADMIN);
        if (kb.getTenantId() == null || !kb.getTenantId().equals(callerTenant) || !admin) {
            throw new BizException(AppError.forbidden("Only knowledge base owner can clear contents"));
        }
        int count = knowledgeService.clearKnowledgeBaseContents(kbId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new ClearContentsResponse(count));
    }

    private List<KnowledgeResponse> filteredKnowledgeBatch(long tenant, List<String> ids,
            String kbId) {
        String safeKbId = LogSanitizer.sanitize(kbId);
        guards.batchAccessChecks(safeKbId);
        return knowledgeService.getKnowledgeBatch(tenant, ids).stream()
                .filter(k -> safeKbId.equals(k.getKnowledgeBaseId()))
                .map(KnowledgeResponse::from)
                .toList();
    }

    private KnowledgeResponse refreshAndReload(String safeId) {
        knowledgeService.requestKnowledgeSummaryRefresh(safeId);
        return KnowledgeResponse.from(knowledgeService.getKnowledge(safeId));
    }

    private String requireKnowledgeId(String id) {
        String safeId = LogSanitizer.sanitize(id);
        if (safeId.isEmpty()) {
            throw new BizException(AppError.badRequest("Knowledge ID cannot be empty"));
        }
        return safeId;
    }

}
