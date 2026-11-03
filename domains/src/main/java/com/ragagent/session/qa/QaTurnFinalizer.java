package com.ragagent.session.qa;

import com.ragagent.contract.session.SessionLastRequestState;
import com.ragagent.memory.api.MemoryAccess;
import com.ragagent.platform.access.TenantContextSnapshot;
import com.ragagent.platform.access.TenantScope;
import com.ragagent.session.attachment.TemporaryDocumentService;
import com.ragagent.session.conversation.MessageService;
import com.ragagent.session.conversation.SessionLookupScope;
import com.ragagent.session.conversation.SessionService;
import com.ragagent.session.domain.document.TemporaryDocument;
import com.ragagent.session.domain.message.Message;
import com.ragagent.session.service.QaSupport;
import com.ragagent.session.service.QaSupport.QaMode;
import com.ragagent.session.service.QaSupport.QaRequestContext;
import com.ragagent.session.service.SessionKnowledgeQaService;
import com.ragagent.session.service.suggestion.MessageSuggestionService;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@code KnowledgeQaController} 的**收尾簇**：轮次状态落库、待处理附件判定、
 * assistant 消息收尾（落库/标题/建议），以及租户作用域包装 {@code runWithTenant}。
 *
 * <p>{@code runWithTenant} 与 {@code completeAssistantMessage} 集中在本类，
 * SSE 编排协作者持有本类即可转发这两件事，不必回调控制器。</p>
 */
@Component
public final class QaTurnFinalizer {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(QaTurnFinalizer.class);

    private final SessionService sessionService;
    private final MessageService messageService;
    private final MessageSuggestionService suggestionService;
    private final TemporaryDocumentService temporaryDocuments;
    private final MemoryAccess memoryExtraction;

    @Autowired   // 多构造器（含测试接缝）⇒ 显式指定装配用的这一个
    public QaTurnFinalizer(SessionService sessionService, MessageService messageService, MessageSuggestionService suggestionService, TemporaryDocumentService temporaryDocuments,
            org.springframework.beans.factory.ObjectProvider<MemoryAccess> memoryExtractionProvider) {
        this(sessionService, messageService, suggestionService, temporaryDocuments,
                memoryExtractionProvider.getIfAvailable());
    }

    /** 测试接缝：直接给端口实例（不经 Spring 装配）。 */
    public QaTurnFinalizer(SessionService sessionService, MessageService messageService, MessageSuggestionService suggestionService, TemporaryDocumentService temporaryDocuments, MemoryAccess memoryExtraction) {
        this.sessionService = sessionService;
        this.messageService = messageService;
        this.suggestionService = suggestionService;
        this.temporaryDocuments = temporaryDocuments;
        this.memoryExtraction = memoryExtraction;
    }

    /**
     * 借用执行租户运行：只换执行租户，身份（principal/role/userId）原样保留。
     *
     * <p>纪律 #1：借用必须**保存-恢复**而非 clear。早先实现在 finally 里先
     * {@code clear()} 再读 {@code currentPrincipal()/currentRole()/currentUserId()}
     * 去还原——这些读取在 clear 之后恒为 null，等于把调用线程的身份永久抹掉、
     * 只还原了 tenantId。而本方法的调用点包含**同步 EventBus 的内联 handler**
     * （723 的 AGENT_FINAL_ANSWER、1006 的 STOP），handler 跑在引擎/流水线/HTTP
     * 线程上，身份被抹后同线程后续的 owner/权限判定全部失真。</p>
     */
    public void runWithTenant(Long tenantId, Runnable body) {
        runWithTenant(tenantId, null, body);
    }

    /**
     * 同 {@link #runWithTenant(Long, Runnable)}，但允许调用方传入**身份补齐快照**。
     *
     * <p>A5：调用点包含新起的虚拟线程（STOP watcher / 消费线程）——那些线程的
     * TenantContext 是空的，{@code capture()} 只拿到空身份；只做 save/restore 等于把
     * "没有身份"照搬回去（owner 推导成 {@code ""}、下游索引/建议/记忆全部
     * SessionNotFound）。这里：当前线程身份缺失时用 {@code fallback} 补齐；当前线程
     * 已有身份则以它为准（调用方身份优先）。</p>
     */
    public void runWithTenant(Long tenantId, TenantContextSnapshot fallback, Runnable body) {
        TenantContextSnapshot prev =
                TenantContextSnapshot.capture();
        TenantContextSnapshot identity =
                prev.hasIdentity() || fallback == null ? prev : fallback;
        if (tenantId != null) {
            TenantScope.run(identity.withTenantId(tenantId), body);
        } else if (identity != prev) {
            // 新线程 + 未指定执行租户：至少把身份装回来，否则 owner/权限判定失真
            TenantScope.run(identity, body);
        } else {
            body.run();
        }
    }
    public boolean hasPendingAttachments(long tenantId, String sessionId, List<String> ids) {
        for (String id : ids) {
            TemporaryDocument doc;
            try {
                doc = temporaryDocuments.get(tenantId, sessionId, id);
            } catch (RuntimeException e) {
                continue;
            }
            if (doc != null && ("uploaded".equals(doc.getStatus()) || "processing".equals(doc.getStatus()))) {
                return true;
            }
        }
        return false;
    }
    /**
     * 在**调用线程**把输入条状态定型成快照。
     *
     * <p>异步写库只拿这个快照，不再回调 {@code QaRequestContext} 的可变字段——
     * 那些字段属于执行/落库簇，写入时机在本快照之后。</p>
     */
    public SessionLastRequestState lastRequestStateSnapshot(QaRequestContext reqCtx, QaMode mode) {
        boolean agentEnabled = reqCtx.reqAgentEnabled;
        if (mode == QaMode.AGENT && reqCtx.agentConfig != null) {
            agentEnabled = SessionKnowledgeQaService.isAgentMode(reqCtx.agentConfig);
        }
        SessionLastRequestState state = new SessionLastRequestState();
        state.setAgentId(QaSupport.orEmpty(reqCtx.reqAgentId));
        state.setAgentEnabled(agentEnabled);
        state.setModelId(reqCtx.summaryModelId);
        state.setKnowledgeBaseIds(copy(reqCtx.knowledgeBaseIds));
        state.setKnowledgeIds(copy(reqCtx.knowledgeIds));
        state.setTagIds(copy(reqCtx.tagIds));
        state.setMcpServiceIds(copy(reqCtx.mcpServiceIds));
        state.setSkillNames(copy(reqCtx.skillNames));
        state.setMentionedItems(copy(reqCtx.mentionedItems));
        state.setWebSearchEnabled(reqCtx.webSearchEnabled);
        return state;
    }

    /** 写输入条状态；只接受 {@link #lastRequestStateSnapshot} 的产物。 */
    public void persistLastRequestState(String sessionId, SessionLastRequestState state) {
        try {
            sessionService.updateSessionLastRequestState(sessionId, state);
        } catch (RuntimeException e) {
            log.warn("persist last_request_state failed for session {}", sessionId, e);
        }
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
    /**
     * 收尾 assistant 消息：**CAS 置完成态**（全轮一次性提交门），只有赢家才异步排
     * KB 索引 / 追问建议 / 记忆蒸馏。
     *
     * <p>A2：收尾可能被多处并发触发（STOP watcher / HTTP 消费 / runner finally）。
     * ① 输家（已被别的路径提交过）直接返回——原先每处都排一遍下游，会重复建 knowledge
     * 并产生孤立产物；② 提交本身失败时短路——原先只 warning 后照样启动下游，
     * "消息未完成、索引已建"的孤立产物仍然存在。</p>
     */
    public void completeAssistantMessage(Message assistantMessage, String userQuery,
            String userMessageId, Long tenantId) {
        assistantMessage.setUpdatedAt(OffsetDateTime.now());
        assistantMessage.setCompleted(true);
        final boolean won;
        try {
            won = messageService.completeMessageOnce(assistantMessage);
        } catch (RuntimeException e) {
            log.warn("complete assistant message update failed", e);
            return;
        }
        if (!won) {
            // 输家：别的收尾路径已提交过。**下游只许赢家启动**（重复索引会新建 knowledge
            // 产生孤立产物）；但这行消息仍要补一次幂等落库——终态写入是内容/refs/steps 的
            // 唯一落库点，后到的内存态可能更全（如 STOP 之后桥又补了最终答案）。
            log.info("[QA] assistant message {} already committed elsewhere; persist only, skip index/suggestion/memory",
                    assistantMessage.getId());
            try {
                messageService.updateMessage(assistantMessage);
            } catch (RuntimeException e) {
                log.warn("complete assistant message re-persist failed", e);
            }
            return;
        }
        final String content = assistantMessage.getContent();
        final String amId = assistantMessage.getId();
        final String sessionId = assistantMessage.getSessionId();
        // 纪律 #1：新虚拟线程没有 ThreadLocal——必须捕获**完整身份**（不止 tenantId）
        // 再重放。历史上这里只带 tenantId，principal/userId 丢失后
        // sessionUserIDForLookup() 推导出 owner=""，embed（owner=embed_session:…）与
        // 平台（owner=<userId>）会话的索引与 follow-up 全部 SessionNotFoundException。
        final TenantContextSnapshot asyncTenant =
                TenantContextSnapshot.capture();
        Thread.ofVirtual().start(() -> TenantScope.run(asyncTenant, () -> {
            SessionLookupScope.mark();
            try {
                messageService.indexMessageToKb(userQuery, content, amId, sessionId);
            } catch (RuntimeException e) {
                log.warn("index message to KB failed for message {}", amId, e);
            } finally {
                SessionLookupScope.clear();
            }
        }));
        if (userQuery != null && !userQuery.isEmpty() && suggestionService != null) {
            Thread.ofVirtual().start(() -> TenantScope.run(asyncTenant, () -> {
                SessionLookupScope.mark();
                try {
                    suggestionService.ensureFollowUps(sessionId, amId, false);
                } catch (RuntimeException e) {
                    log.warn("follow-up suggestion generation failed for message {}", amId, e);
                } finally {
                    SessionLookupScope.clear();
                }
            }));
        }
        // 记忆自动蒸馏调度：enabledScope 读当前
        // 租户上下文（调用方已 runWithTenant），model_id 沿 assistant 行取。
        if (memoryExtraction != null) {
            try {
                memoryExtraction.scheduleExtraction(sessionId, amId,
                        assistantMessage.getModelId() == null ? "" : assistantMessage.getModelId());
            } catch (RuntimeException e) {
                log.warn("memory extraction scheduling failed for session {}", sessionId, e);
            }
        }
    }
}
