package com.ragagent.knowledge.controller;

import com.ragagent.contract.apikey.APIKeyScopeContext;
import com.ragagent.contract.apikey.TenantAPIKeyScope;
import com.ragagent.contract.error.AppError;
import com.ragagent.contract.error.BizException;
import com.ragagent.contract.knowledge.KnowledgeBaseResponse;
import com.ragagent.contract.retrieval.HybridSearchPort;
import com.ragagent.contract.retrieval.SearchParams;
import com.ragagent.contract.retrieval.SearchResult;
import com.ragagent.contract.storage.support.PublicModeForbiddenException;
import com.ragagent.contract.storage.support.ResourceModeException;
import com.ragagent.contract.tenant.TenantRole;
import com.ragagent.knowledge.dto.HybridSearchRequest;
import com.ragagent.knowledge.dto.doc.RebuildIndexResponse;
import com.ragagent.knowledge.dto.kb.CopyKnowledgeBaseRequest;
import com.ragagent.knowledge.dto.kb.CopyKnowledgeBaseResponse;
import com.ragagent.knowledge.dto.kb.CreateKnowledgeBaseRequest;
import com.ragagent.knowledge.dto.kb.DuplicateKnowledgeBaseResponse;
import com.ragagent.knowledge.dto.kb.KBCloneProgress;
import com.ragagent.knowledge.dto.kb.UpdateKnowledgeBaseRequest;
import com.ragagent.knowledge.service.access.KnowledgeAccessGuard;
import com.ragagent.knowledge.service.management.KnowledgeBaseService;
import com.ragagent.knowledge.service.management.KnowledgeService;
import com.ragagent.knowledge.support.KnowledgeBaseRowViews;
import com.ragagent.knowledge.support.KnowledgeTaskIdCodec;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.platform.storage.support.ResourceUrlMode;
import com.ragagent.platform.web.ApiResponse;
import com.ragagent.platform.web.ApiResult;
import com.ragagent.platform.web.NonNullBody;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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

/**
 * 知识库 CRUD 与检索入口：列表/详情/更新/删除、置顶、移动目标、混合检索
 * （POST+GET 双路由）、复制/副本/重建索引与复制进度。
 *
 * <p><b>守卫顺序</b>（契约样例依赖，不能重排）：hybrid-search/duplicate 的路由带
 * KBAccessRead（{@code guard.requireKbAccess}）；copy 的源/目标在 body，handler 内
 * {@code kbService.resolveAccess}——跨租户 403 文案与 move 的 handler 检查刻意不同。</p>
 *
 * <p><b>响应契约</b>（见 {@code docs/knowledge-api-contract-v1.md}）：知识库对象统一由
 * {@link KnowledgeBaseResponse} 输出（camelCase、可空字段显式 null、内部字段不下发）；
 * 成功响应不再包 {@code {data, success}} 信封——单资源直接返回对象、列表直接返回数组，
 * 删除返回 204。</p>
 *
 * <p>create 的 {@code CreateKnowledgeBaseRequest} 兼容 {@code storage_config} 形状
 * （对象存储配置仍由知识库实体的 jsonb 列承载）。</p>
 */
@RestController
@ApiResult
@RequestMapping("/api/v1/knowledge-bases")
@Tag(name = "Knowledge")
public class KnowledgeBaseController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseController.class);

    private final KnowledgeBaseService kbService;
    private final KnowledgeService knowledgeService;
    private final KnowledgeAccessGuard guard;
    private final HybridSearchPort hybridSearchService;

    public KnowledgeBaseController(KnowledgeBaseService kbService,
                                   KnowledgeService knowledgeService,
                                   KnowledgeAccessGuard guard,
                                   HybridSearchPort hybridSearchService) {
        this.kbService = kbService;
        this.knowledgeService = knowledgeService;
        this.guard = guard;
        this.hybridSearchService = hybridSearchService;
    }

    /** 创建知识库：请求体字段全部可选（缺省由服务层默认值链补齐）。 */
    @PostMapping
    public ResponseEntity<KnowledgeBaseResponse> createKnowledgeBase(
            @RequestBody(required = false) CreateKnowledgeBaseRequest request) {
        log.info("Start creating knowledge base");
        var kb = kbService.createKnowledgeBase(
                request == null ? CreateKnowledgeBaseRequest.empty().toEntity() : request.toEntity());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(KnowledgeBaseResponse.from(KnowledgeBaseRowViews.rowView(kb), kbService.retrieveDriver()));
    }

    /** 列表；KB 受限的 API Key 只看得到白名单内的库（数据面收口）。 */
    @GetMapping
    public ResponseEntity<List<KnowledgeBaseResponse>> listKnowledgeBases(
            @RequestParam(value = "creator", required = false) String creator) {
        log.info("Start listing knowledge bases");
        var kbs = kbService.listKnowledgeBases(creator);
        var scope = APIKeyScopeContext.current();
        if (scope != null && scope.isKnowledgeBaseRestricted()) {
            kbs = kbs.stream().filter(kb -> scope.allowsKnowledgeBase(kb.getId())).toList();
        }
        String driver = kbService.retrieveDriver();
        return ResponseEntity.ok(kbs.stream().map(kb -> KnowledgeBaseResponse.from(KnowledgeBaseRowViews.rowView(kb), driver)).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<KnowledgeBaseResponse> getKnowledgeBase(@PathVariable("id") String id) {
        log.info("Start retrieving knowledge base, ID: {}", id);
        var kb = kbService.getKnowledgeBase(id);
        return ResponseEntity.ok(KnowledgeBaseResponse.from(KnowledgeBaseRowViews.rowView(kb), kbService.retrieveDriver()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<KnowledgeBaseResponse> updateKnowledgeBase(
            @PathVariable("id") String id,
            @Valid @NonNullBody @RequestBody UpdateKnowledgeBaseRequest req) {
        log.info("Start updating knowledge base, ID: {}", id);
        var existing = kbService.getKnowledgeBase(id);
        kbService.checkOwnership(existing.getId());
        if (req.name() != null && req.name().isEmpty()) {
            throw new BizException(AppError.badRequest("请求参数不合法").withDetails("name: 不能为空"));
        }
        existing = kbService.updateKnowledgeBase(existing, req.name(), req.description(), req.config());
        return ResponseEntity.ok(KnowledgeBaseResponse.from(KnowledgeBaseRowViews.rowView(existing), kbService.retrieveDriver()));
    }

    /** 删除知识库；成功返回 204（无响应体，见契约文档）。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteKnowledgeBase(@PathVariable("id") String id) {
        log.info("Start deleting knowledge base, ID: {}", id);
        var existing = kbService.getKnowledgeBase(id);
        kbService.checkOwnership(existing.getId());
        if (!TenantRole.fromString(TenantContext.currentRole()).hasPermission(TenantRole.ADMIN)) {
            throw new BizException(AppError.forbidden("Only knowledge base owner can delete"));
        }
        kbService.deleteKnowledgeBase(id);
        return ApiResponse.ok();   // 204 退役（空体与「外壳恒存在」冲突）
    }

    @PutMapping("/{id}/pin")
    public ResponseEntity<KnowledgeBaseResponse> togglePin(@PathVariable("id") String id) {
        log.info("Start toggling pin for knowledge base, ID: {}", id);
        var kb = kbService.togglePin(id);
        return ResponseEntity.ok(KnowledgeBaseResponse.from(KnowledgeBaseRowViews.rowView(kb), kbService.retrieveDriver()));
    }

    @GetMapping("/{id}/move-targets")
    public ResponseEntity<List<KnowledgeBaseResponse>> listMoveTargets(@PathVariable("id") String id) {
        log.info("Start listing move targets, ID: {}", id);
        var targets = kbService.listMoveTargets(id);
        String driver = kbService.retrieveDriver();
        return ResponseEntity.ok(targets.stream().map(kb -> KnowledgeBaseResponse.from(KnowledgeBaseRowViews.rowView(kb), driver)).toList());
    }

    // ── hybrid-search / copy / duplicate / rebuild-index / copy progress ──

    /** POST 与 GET 双路由同一 handler（GET 带 JSON body 兼容 #1727）。 */
    @PostMapping("/{id}/hybrid-search")
    public ResponseEntity<List<SearchResult>> hybridSearchPost(
            @PathVariable("id") String id,
            @Valid @NonNullBody @RequestBody HybridSearchRequest req,
            @RequestParam(value = "resourceUrls", required = false) String resourceUrls) {
        return hybridSearch(id, req, resourceUrls);
    }

    @GetMapping("/{id}/hybrid-search")
    public ResponseEntity<List<SearchResult>> hybridSearchGet(
            @PathVariable("id") String id,
            @Valid @NonNullBody @RequestBody HybridSearchRequest req,
            @RequestParam(value = "resourceUrls", required = false) String resourceUrls) {
        return hybridSearch(id, req, resourceUrls);
    }

    private ResponseEntity<List<SearchResult>> hybridSearch(String id, HybridSearchRequest req,
            String resourceUrls) {
        log.info("Start hybrid search");
        var kb = guard.requireKbAccess(id);
        boolean precomputedVectorOnly = req.queryEmbedding() != null
                && req.queryEmbedding().length > 0
                && Boolean.TRUE.equals(req.disableKeywordsMatch())
                && !Boolean.TRUE.equals(req.disableVectorMatch());
        if ((req.queryText() == null || req.queryText().trim().isEmpty()) && !precomputedVectorOnly) {
            throw new BizException(AppError.badRequest("queryText is required"));
        }
        // resource_urls：public 拒绝 → 403；其他坏值 → 400
        try {
            ResourceUrlMode.resolve(resourceUrls);
        } catch (PublicModeForbiddenException e) {
            log.warn("Rejected resource URL mode", e);
            throw new BizException(AppError.forbidden(e.getMessage()));
        } catch (ResourceModeException e) {
            log.warn("Rejected resource URL mode", e);
            throw new BizException(AppError.badRequest(e.getMessage()));
        }
        // 多库 scope：knowledge_base_ids 优先，缺省单库；API-Key 白名单 + 租户归属校验
        List<String> searchKbIds = req.knowledgeBaseIds() != null && !req.knowledgeBaseIds().isEmpty()
                ? new ArrayList<>(req.knowledgeBaseIds())
                : List.of(kb.getId());
        TenantAPIKeyScope.authorizeKnowledgeBases(searchKbIds);
        kbService.validatedSearchKbIds(kb.getId(), req.knowledgeBaseIds());

        // 检索执行：pgvector + ParadeDB BM25 + RRF + FAQ 后处理 + 富化装配
        SearchParams params = new SearchParams();
        params.setQueryText(req.queryText() == null ? "" : req.queryText());
        if (req.queryEmbedding() != null && req.queryEmbedding().length > 0) {
            params.setQueryEmbedding(req.queryEmbedding());
        }
        params.setVectorThreshold(req.vectorThreshold() == null ? 0.0 : req.vectorThreshold());
        params.setKeywordThreshold(req.keywordThreshold() == null ? 0.0 : req.keywordThreshold());
        params.setMatchCount(req.matchCount() == null ? 0 : req.matchCount());
        params.setDisableKeywordsMatch(Boolean.TRUE.equals(req.disableKeywordsMatch()));
        params.setDisableVectorMatch(Boolean.TRUE.equals(req.disableVectorMatch()));
        params.setSkipContextEnrichment(Boolean.TRUE.equals(req.skipContextEnrichment()));
        params.setKnowledgeIds(req.knowledgeIds() == null ? List.of() : req.knowledgeIds());
        params.setTagIds(req.tagIds() == null ? List.of() : req.tagIds());
        params.setKnowledgeBaseIds(searchKbIds);
        List<SearchResult> results =
                hybridSearchService.hybridSearch(kb.getId(), params);
        // 空结果归一为空数组（裸列表契约：不返回空体，也不用 null 表示空）
        return ResponseEntity.ok(results == null ? List.of() : results);
    }

    /** 复制知识库（源在 body）；targetId 缺省 = 创建新库。异步受理 → 202 + 任务信息。 */
    @PostMapping("/copy")
    public ResponseEntity<CopyKnowledgeBaseResponse> copyKnowledgeBase(
            @Valid @NonNullBody @RequestBody CopyKnowledgeBaseRequest req) {
        log.info("Start copying knowledge base");
        String sourceId = req.sourceId() == null ? "" : req.sourceId();
        String targetId = req.targetId() == null ? "" : req.targetId();
        String explicitTaskId = req.taskId() == null ? "" : req.taskId();
        long caller = TenantContext.currentTenantId() == null ? 0 : TenantContext.currentTenantId();
        if (caller == 0) {
            throw new BizException(AppError.unauthorized("Unauthorized"));
        }
        var source = kbService.resolveAccess(sourceId);
        if (source.tenantId() == null || source.tenantId() != caller) {
            throw new BizException(AppError.forbidden("No permission to copy this knowledge base"));
        }
        String taskId = explicitTaskId;
        if (taskId.isEmpty()) {
            taskId = KnowledgeTaskIdCodec.generateTaskId("kb_clone", caller, sourceId);
        } else {
            requireTaskProgressTenant(taskId);
        }
        boolean create = targetId.isEmpty();
        String reservedTargetId;
        String creatorId = "";
        if (create) {
            String uid = TenantContext.currentUserId();
            if (uid != null && !uid.startsWith("system-")) {
                creatorId = uid;
            }
            reservedTargetId = UUID.randomUUID().toString();
            kbService.newTarget(reservedTargetId, caller, creatorId);
        } else {
            var existing = kbService.resolveAccess(targetId);
            if (existing.tenantId() == null || existing.tenantId() != caller) {
                throw new BizException(AppError.forbidden("No permission to copy to this knowledge base"));
            }
            String role = TenantContext.currentRole();
            boolean admin = TenantRole.fromString(role).hasPermission(TenantRole.ADMIN);
            if (!admin && (existing.creatorId() == null || existing.creatorId().isEmpty()
                    || !existing.creatorId().equals(TenantContext.currentUserId()))) {
                throw new BizException(AppError.forbidden(
                        "No permission to replace this knowledge base's contents"));
            }
            try {
                kbService.validateCloneCompatibilityByIds(sourceId, targetId);
            } catch (IllegalArgumentException e) {
                throw new BizException(AppError.badRequest(e.getMessage()));
            }
            reservedTargetId = targetId;
        }
        knowledgeService.startKBClone(caller, taskId, sourceId, reservedTargetId, create, creatorId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new CopyKnowledgeBaseResponse(taskId, sourceId, reservedTargetId));
    }

    /** 复制进度（前端轮询直到 {@code terminal}）。 */
    @GetMapping("/copy/progress/{taskId}")
    public ResponseEntity<KBCloneProgress> getKBCloneProgress(@PathVariable("taskId") String taskId) {
        if (taskId == null || taskId.isEmpty()) {
            throw new BizException(AppError.badRequest("Task ID cannot be empty"));
        }
        requireTaskProgressTenant(taskId);
        KBCloneProgress progress = knowledgeService.getKBCloneProgress(taskId);
        if (progress == null) {
            throw new BizException(AppError.notFound("KB clone task not found"));
        }
        return ResponseEntity.ok(progress);
    }

    /** 任务租户必须与调用方一致，否则 404（不泄露他租户任务存在性）。 */
    private void requireTaskProgressTenant(String taskId) {
        Long taskTenant = KnowledgeTaskIdCodec.taskTenantId(taskId);
        if (taskTenant == null) {
            throw new BizException(AppError.badRequest("invalid task ID"));
        }
        Long caller = TenantContext.currentTenantId();
        if (caller == null || caller == 0) {
            throw new BizException(AppError.unauthorized("Unauthorized"));
        }
        if (!taskTenant.equals(caller)) {
            throw new BizException(AppError.notFound("task not found"));
        }
    }

    /** 索引策略变更后对 KB 内全部知识重跑处理管线（裸返回重建条数）。 */
    @PostMapping("/{id}/rebuild-index")
    public ResponseEntity<RebuildIndexResponse> rebuildIndex(@PathVariable("id") String id) {
        log.info("Start rebuilding knowledge base index, ID: {}", id);
        String kbId = id == null ? "" : id;
        if (kbId.isEmpty()) {
            throw new BizException(AppError.badRequest("Knowledge base ID cannot be empty"));
        }
        guard.requireKbAccess(kbId);
        long callerTenant = TenantContext.currentTenantId() == null ? 0 : TenantContext.currentTenantId();
        var sourceKb = kbService.getAllTenantById(kbId);
        if (sourceKb == null) {
            throw new BizException(AppError.notFound("Knowledge base not found"));
        }
        if (sourceKb.getTenantId() == null || sourceKb.getTenantId() != callerTenant) {
            log.warn("Knowledge base rebuild rejected: belongs to another tenant");
            throw new BizException(AppError.forbidden("No permission to rebuild this knowledge base"));
        }
        int count = knowledgeService.rebuildKnowledgeBaseIndex(kbId);
        return ResponseEntity.ok(new RebuildIndexResponse(count));
    }

    /** 同步克隆设置（名字带 " 副本"，重名去重）。 */
    @PostMapping("/{id}/duplicate")
    public ResponseEntity<DuplicateKnowledgeBaseResponse> duplicateKnowledgeBase(@PathVariable("id") String id) {
        log.info("Start duplicating knowledge base, ID: {}", id);
        String sourceId = id == null ? "" : id;
        if (sourceId.isEmpty()) {
            throw new BizException(AppError.badRequest("Knowledge base ID cannot be empty"));
        }
        guard.requireKbAccess(sourceId);
        long callerTenant = TenantContext.currentTenantId() == null ? 0 : TenantContext.currentTenantId();
        var sourceKb = kbService.getAllTenantById(sourceId);
        if (sourceKb == null) {
            // 路由守卫已兜住缺失；此分支保留 handler 的 NotFound 文案
            throw new BizException(AppError.notFound("Source knowledge base not found"));
        }
        if (sourceKb.getTenantId() == null || sourceKb.getTenantId() != callerTenant) {
            log.warn("Knowledge base duplicate rejected: source belongs to another tenant");
            throw new BizException(AppError.forbidden("No permission to duplicate this knowledge base"));
        }
        var targetKb = knowledgeService.duplicateKnowledgeBase(sourceId);
        return ResponseEntity.status(HttpStatus.CREATED).body(new DuplicateKnowledgeBaseResponse(
                sourceId, targetKb.getId(),
                KnowledgeBaseResponse.from(KnowledgeBaseRowViews.rowView(targetKb), kbService.retrieveDriver())));
    }
}
