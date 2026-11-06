package com.ragagent.session.qa;

import static com.ragagent.session.service.QaSupport.KnowledgeTargets;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.agentmanagement.service.config.AgentConfigJson;
import com.ragagent.contract.agent.AgentLookup;
import com.ragagent.contract.apikey.TenantAPIKeyScope;
import com.ragagent.contract.error.BizException;
import com.ragagent.contract.session.MessageAttachment;
import com.ragagent.contract.session.MessageExecutionContext;
import com.ragagent.contract.session.SuggestionAttribution;
import com.ragagent.contract.storage.support.FileUrlPort;
import com.ragagent.contract.storage.support.Mode;
import com.ragagent.contract.storage.support.PublicModeForbiddenException;
import com.ragagent.contract.storage.support.ResourceModeException;
import com.ragagent.contract.storage.support.StorageBackendResolver;
import com.ragagent.contract.tenant.TenantConfigLookup.TenantStorageView;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.platform.security.LogSanitizer;
import com.ragagent.platform.storage.support.FileResolverFactory;
import com.ragagent.platform.storage.support.ResourceUrlMode;
import com.ragagent.platform.storage.support.Rewriter;
import com.ragagent.platform.storage.support.StreamRewriter;
import com.ragagent.platform.web.JsonMappers;
import com.ragagent.session.attachment.TemporaryDocumentService;
import com.ragagent.session.conversation.SessionService;
import com.ragagent.session.domain.document.TemporaryDocument;
import com.ragagent.session.domain.message.Message;
import com.ragagent.session.dto.QaRequests.CreateKnowledgeQARequest;
import com.ragagent.session.service.AgentResolver;
import com.ragagent.session.service.QaSupport;
import com.ragagent.session.service.QaSupport.QaRequestContext;
import com.ragagent.session.support.AgentConfigSnapshot;
import com.ragagent.session.support.SessionNotFoundException;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@code KnowledgeQaController} 的**请求解析主体**：把 HTTP 请求（会话 id、
 * agentId、资源、模式）解析成 {@code ParsedRequest}（{@code QaRequestContext} + 具名请求体）。
 *
 * <p>参数化而非注入：{@code agentResolver} 由调用方传入（{@link QaEntryRunner} 构造注入后
 * 逐调用快照）；读者租户 {@code readerTenant} 同理由调用方算好传入
 * （{@code QaEntryRunner} 侧的 {@code currentTenant()} 依赖 {@code tenantService}）。共享静态助手
 * {@code KnowledgeQaController.stringListOf} 与嵌套类型 {@code ParsedRequest} 按包内可见引用。</p>
 */
@Component
public final class QaRequestParser {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(QaRequestParser.class);

    private final SessionService sessionService;
    private final TemporaryDocumentService temporaryDocuments;
    private final FileUrlPort fileService;
    private final StorageBackendResolver storageBackendResolver;
    private final FileResolverFactory fileResolverFactory;

    public QaRequestParser(SessionService sessionService, TemporaryDocumentService temporaryDocuments,
            ObjectProvider<FileUrlPort> fileServiceProvider,
            ObjectProvider<StorageBackendResolver> storageBackendResolverProvider,
            FileResolverFactory fileResolverFactory) {
        this.sessionService = sessionService;
        this.temporaryDocuments = temporaryDocuments;
        // 两个端口按 ObjectProvider 取（缺 bean 时 Rewriter 按未装配分支降级，
        // 与控制器原先的手工装配语义一致）
        this.fileService = fileServiceProvider.getIfAvailable();
        this.storageBackendResolver = storageBackendResolverProvider.getIfAvailable();
        this.fileResolverFactory = fileResolverFactory;
    }

    public ParsedRequest parseQARequest(String rawSessionId, CreateKnowledgeQARequest request,
            String resourceUrls, String logPrefix,
            AgentResolver agentResolver,
            TenantStorageView readerTenant) {
        QaRequestContext rc = new QaRequestContext();

        String sessionId = LogSanitizer.sanitize(rawSessionId);
        if (sessionId.isEmpty()) {
            throw BizException.badRequest("invalid session id");
        }
        rc.sessionId = sessionId;
        rc.query = QaSupport.orEmpty(request.query);
        rc.requestId = TenantContext.currentRequestId() == null ? "" : TenantContext.currentRequestId();

        if (request.query == null || request.query.isEmpty()) {
            throw BizException.badRequest("Query content cannot be empty");
        }

        // 先解析存储引用形态：SSE 起流后非法值不能再落 400
        try {
            Mode mode = ResourceUrlMode.resolve(resourceUrls);
            Rewriter rw = Rewriter.forRequest(mode,
                    fileResolverFactory.create(readerTenant, fileService, storageBackendResolver));
            rc.resourceRewriter = new StreamRewriter(rw);
        } catch (PublicModeForbiddenException e) {
            log.warn("Rejected resource URL mode", e);
            throw BizException.forbidden(e.getMessage());
        } catch (ResourceModeException e) {
            log.warn("Rejected resource URL mode", e);
            throw BizException.badRequest(e.getMessage());
        }

        // 建议归因校验
        if (request.suggestionAttribution != null && !request.suggestionAttribution.isNull()) {
            try {
                rc.suggestionAttribution = JsonMappers.lenient()
                        .treeToValue(request.suggestionAttribution, SuggestionAttribution.class);
            } catch (Exception e) {
                throw BizException.badRequest("invalid suggestion attribution");
            }
        }

        // SSRF：客户端不可携带图片 URL/Caption
        for (var img : request.images()) {
            img.url = "";
            img.caption = "";
        }

        // 严格 owner 范围取会话（QA 写消息，不复用 Admin 读回退）
        try {
            rc.session = sessionService.getOwnedSession(sessionId);
        } catch (SessionNotFoundException e) {
            throw BizException.notFound("Session not found");
        }

        // 解析 agent：共享优先，source==0 才回落自有
        var resolvedAgent = agentResolver.resolve(request.agentId,
                request.agentSourceTenantId);
        AgentLookup.AgentView customAgent = resolvedAgent.agent();
        if (request.agentSourceTenantId != 0 && customAgent == null) {
            throw BizException.notFound("Shared agent not found");
        }
        rc.agentRow = customAgent;
        rc.effectiveTenantId = resolvedAgent.effectiveTenantId();
        if (customAgent != null) {
            ObjectNode cfg = AgentResolver.parseAgentConfig(customAgent);
            AgentConfigJson.ensureDefaults(cfg);
            rc.agentConfig = cfg;
        }

        // @mention 合并
        KnowledgeTargets merged = QaSupport.mergeKnowledgeTargets(
                request.knowledgeBaseIds(), request.knowledgeIds(), request.mentionedItems());
        TenantAPIKeyScope.authorizeKnowledgeTargets(
                merged.kbIds(), merged.knowledgeIds());

        // wiki fixer 的租户作用域：内建 agent id 不匹配即跳过（同款守卫）
        if (rc.agentConfig != null && "builtin-wiki-fixer".equals(customAgent.id())) {
            // 租户作用域随 wiki_config 面（命中条件是 KB 共享关系；dev 单租户等价于不动）。
        }

        saveInlineImages(rc, request, logPrefix);

        saveInlineAttachments(rc, request, sessionId);

        collectPreuploadedAttachments(rc, request, sessionId);

        collectMentionScopes(rc, request, merged);

        buildAssistantMessage(rc, request, customAgent, resolvedAgent);

        rc.knowledgeBaseIds = merged.kbIds();
        rc.knowledgeIds = merged.knowledgeIds();
        rc.summaryModelId = request.summaryModelId;
        rc.webSearchEnabled = request.webSearchEnabled;
        rc.mentionedItems = QaSupport.convertMentionedItems(request.mentionedItems());
        rc.channel = request.channel;
        rc.reqAgentEnabled = request.agentEnabled;
        rc.reqAgentId = request.agentId;
        return new ParsedRequest(rc, request);
    }

    /** 内联 base64 图片：解码 → 大小校验 → 落临时文件 → 回填 URL（SSRF 已在调用方清空客户端 url）。 */
    private void saveInlineImages(QaRequestContext rc, CreateKnowledgeQARequest request, String logPrefix) {
    // 内联 base64 图片（落盘后回填 URL，消息/检索/VLM
    // 三处消费同一引用；SSRF 已在上方清空客户端 url/caption）
    if (!request.images().isEmpty()) {
        if (rc.agentConfig == null || !rc.agentConfig.path("imageUploadEnabled").asBoolean(false)) {
            log.warn("[{}] Image upload is not enabled for this agent, rejecting {} images",
                    logPrefix, request.images().size());
            throw BizException.badRequest("Image upload is not enabled for this agent");
        }
        long tenantId = rc.session.getTenantId();
        for (var img : request.images()) {
            QaSupport.QaRequestsImage view = new QaSupport.QaRequestsImage();
            if (img.data != null && !img.data.isEmpty()) {
                byte[] bytes;
                try {
                    bytes = Base64Support.decode(img.data);
                } catch (RuntimeException e) {
                    throw BizException.badRequest("image decode failed: " + e.getMessage());
                }
                if (bytes.length == 0) {
                    throw BizException.badRequest("image decode failed: empty payload");
                }
                if (bytes.length > QaTurnSupport.maxFileBytes()) {
                    throw BizException.badRequest(
                            "image exceeds size limit of " + QaTurnSupport.maxFileBytes() + " bytes");
                }
                try {
                    view.url = temporaryDocuments.saveInlineImageBytes(tenantId, bytes);
                } catch (RuntimeException e) {
                    throw BizException.internal("failed to save image: " + e.getMessage());
                }
            }
            rc.images.add(view);
        }
    }

    }

    /** 内联附件：批量校验 → 解码 → 落临时附件表 → 记 id。 */
    private void saveInlineAttachments(QaRequestContext rc, CreateKnowledgeQARequest request,
            String sessionId) {
    // 内联附件（base64 直传，落临时附件表后走既有的 attachment_ids 解析链）
    if (!request.attachmentUploads().isEmpty()) {
        QaRequestBinder.decodeAndValidateAttachmentUploads(request.attachmentUploads(),
                QaSupport.MAX_ATTACHMENT_UPLOADS_PER_REQUEST,
                QaTurnSupport.maxFileBytes(),
                QaSupport.MAX_ATTACHMENT_UPLOAD_TOTAL_BYTES);
        long tenantId = rc.session.getTenantId();
        int i = 1;
        for (var up : request.attachmentUploads()) {
            byte[] data;
            try {
                data = Base64Support.decode(up.data);
            } catch (RuntimeException e) {
                throw BizException.badRequest("attachment " + i + " decode failed: " + e.getMessage());
            }
            try {
                TemporaryDocument doc = temporaryDocuments.create(tenantId, sessionId,
                        up.fileName == null || up.fileName.isEmpty() ? "attachment-" + i : up.fileName,
                        "", up.fileSize, data);
                rc.attachmentIDs.add(doc.getId());
            } catch (IllegalArgumentException e) {
                throw BizException.badRequest("attachment " + i + ": " + e.getMessage());
            }
            i++;
        }
    }

    }

    /** 预上传附件：只取元数据并校验文件类型（内容在 SSE 起流后解析）。 */
    private void collectPreuploadedAttachments(QaRequestContext rc, CreateKnowledgeQARequest request,
            String sessionId) {
    // 预上传附件：只取元数据（内容在 SSE 起流后解析）
    if (!request.attachmentIds().isEmpty()) {
        List<String> normalizedIds = QaSupport.normalizeTemporaryAttachmentIds(
                request.attachmentIds(), 5);
        long tenantId = rc.session.getTenantId();
        for (String id : normalizedIds) {
            TemporaryDocument doc;
            try {
                doc = temporaryDocuments.get(tenantId, sessionId, id);
            } catch (RuntimeException e) {
                doc = null;
            }
            if (doc == null) {
                throw BizException.badRequest(
                        "attachment " + LogSanitizer.sanitize(id)
                                + " was not found in this session");
            }
            if (rc.agentConfig != null) {
                List<String> supported = QaTurnSupport.stringListOf(rc.agentConfig.get("supportedFileTypes"));
                if (!supported.isEmpty()) {
                    String ext = doc.getFileType() == null ? ""
                            : (doc.getFileType().startsWith(".")
                                    ? doc.getFileType().substring(1).toLowerCase()
                                    : doc.getFileType().toLowerCase());
                    if (!supported.contains(ext)) {
                        throw BizException.badRequest(
                                "file type " + ext + " is not supported by this agent");
                    }
                }
            }
            MessageAttachment meta = new MessageAttachment();
            meta.setId(doc.getId());
            meta.setUrlValue(doc.getResourceRef());
            meta.setFileName(doc.getFileName());
            meta.setFileType(doc.getFileType());
            meta.setFileSize(doc.getFileSize());
            rc.attachmentMetas.add(meta);
        }
        rc.attachmentIDs = normalizedIds;
    }

    }

    /** tag / mcp / skill 的合并与 tag 作用域校验。 */
    private void collectMentionScopes(QaRequestContext rc, CreateKnowledgeQARequest request,
            KnowledgeTargets merged) {
    // tag scopes / ids / mcp / skills
    List<QaSupport.TagScope> mentionScopes = QaSupport.tagScopesFromMentionedItems(request.mentionedItems());
    List<String> requestTagIds = QaSupport.dedupRequestStrings(request.tagIds());
    String tagError = QaSupport.validateUnscopedTagIds(
            QaSupport.orphanTagIdsForScope(requestTagIds, mentionScopes), merged.kbIds());
    if (tagError != null) {
        throw BizException.badRequest(tagError);
    }
    rc.tagScopes = QaSupport.mergeTagScopesFromRequestIds(mentionScopes, requestTagIds, merged.kbIds());
    List<String> tagIds = QaSupport.dedupRequestStrings(QaRequestBinder.appendAll(request.tagIds(),
            QaSupport.mentionedIdsByType(request.mentionedItems(), "tag")));
    List<String> mcpServiceIds = QaSupport.dedupRequestStrings(QaRequestBinder.appendAll(request.mcpServiceIds(),
            QaSupport.mentionedIdsByType(request.mentionedItems(), "mcp")));
    List<String> skillNames = QaSupport.dedupRequestStrings(QaRequestBinder.appendAll(request.skillNames(),
            QaSupport.mentionedIdsByType(request.mentionedItems(), "skill")));

        rc.tagIds = tagIds;
        rc.mcpServiceIds = mcpServiceIds;
        rc.skillNames = skillNames;
    }

    /** assistant 消息骨架（后续由 QaTurnExecutor 补内容）。 */
    private void buildAssistantMessage(QaRequestContext rc, CreateKnowledgeQARequest request,
            AgentLookup.AgentView customAgent, AgentResolver.ResolvedAgent resolvedAgent) {
    // assistant 消息骨架（buildMessageExecutionContext 的 agent 字段）
    String agentId = customAgent == null ? "" : customAgent.id();
    // 共享 agent 的租户以 effectiveTenantId 为准，为 0 才回落 agent 自身租户
    long agentTenantId = resolvedAgent.effectiveTenantId() != 0 ? resolvedAgent.effectiveTenantId()
            : (customAgent != null && customAgent.tenantId() != null ? customAgent.tenantId() : 0);
    String modelId = request.summaryModelId;
    if (modelId.isEmpty() && rc.agentConfig != null) {
        modelId = rc.agentConfig.path("modelId").asText("");
    }
    Message assistant = new Message();
    assistant.setSessionId(rc.sessionId);
    assistant.setRole("assistant");
    assistant.setRequestId(rc.requestId);
    assistant.setCompleted(false);
    assistant.setChannel(request.channel);
    assistant.setAgentId(agentId);
    assistant.setAgentTenantId(agentTenantId);
    assistant.setModelId(modelId);
    // 追问建议的执行上下文：主流程结束后异步发起时，只认这份快照
    AgentConfigSnapshot.Snapshot snapshot = AgentConfigSnapshot.of(rc.agentConfig);
    MessageExecutionContext ctx = new MessageExecutionContext();
    ctx.setAgentConfigHash(snapshot.configHash());
    ctx.setQuestionSuggestions(snapshot.questionSuggestions());
    assistant.setExecutionContext(ctx);
    rc.assistantMessage = assistant;

    }
}
