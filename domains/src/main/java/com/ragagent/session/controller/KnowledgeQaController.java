package com.ragagent.session.controller;

import com.ragagent.contract.apikey.TenantAPIKeyScope;
import com.ragagent.contract.error.BizException;
import com.ragagent.contract.retrieval.SearchResult;
import com.ragagent.contract.storage.support.Mode;
import com.ragagent.platform.web.ApiResult;
import com.ragagent.platform.web.ServletSseSink;
import com.ragagent.session.dto.QaRequests.SearchKnowledgeRequest;
import com.ragagent.session.qa.QaEntryRunner;
import com.ragagent.session.qa.QaRequestBinder;
import com.ragagent.session.service.QaSupport;
import com.ragagent.session.service.SessionKnowledgeQaService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


/**
 * chat 三入口。
 *
 * <h2>三个端点</h2>
 * <h2>本类承载三个入口（类名只反映了第一个）</h2>
 * <p>拆成三个控制器更符合命名，但三者在<b>同一条 SSE 装配路径</b>上
 * （{@code QaEntryRunner} 收敛绑定→解析→执行），拆开只会让共用点变成三份 import，
 * 所以保留同一类、用本节标明归属。</p>
 * <ul>
 *   <li>POST /api/v1/knowledge-chat/{sessionId} — KnowledgeQA（RAG/纯聊天管线）</li>
 *   <li>POST /api/v1/agent-chat/{sessionId} — AgentQA（agent 引擎）</li>
 *   <li>POST /api/v1/knowledge-search — SearchKnowledge（无 LLM 总结检索）</li>
 * </ul>
 *
 * <h2>关键时序（SSE 时序最高危）</h2>
 * <ol>
 *   <li>agent 模式先 rejectIfOtherAgentRunLive（409）→ Emit(agent.query)；</li>
 *   <li>persistTurnMessages 先建 user/assistant 行；</li>
 *   <li>setupSSEStream：SetLiveRun 在 SSE 头<b>之前</b>（409/503 必须还能改状态码）；
 *       agent_query 事件写流；stop 处理器 + 独立 stop watcher + AgentStreamBridge 订阅；</li>
 *   <li>异步执行 QA 服务（虚拟线程），主线程 handleAgentEventsForSSE 100ms 轮询
 *       StreamManager 推帧，complete 后补 completion 事件；</li>
 *   <li>agent 模式清理时机：completeAssistantMessage → follow-up 交接 → clearLiveRun。</li>
 * </ol>
 *
 * <h2>实现边界</h2>
 * <ul>
 *   <li>共享 agent 解析：resolveAgent 共享优先、source==0 才回落 own；
 *       GetSharedAgentForTenant 的 ApplyBuiltinAgentLocalization 只覆盖
 *       name/description/avatar（QA 消费 config/tenant，不进字节契约），随 agent.management 装配层
 *       统一补齐；access.WithSharedAgent 的 KB grant 机制（检索授权收窄）随检索面
 *       专项收口——Java 检索租户已取 agentRow.tenantId（等价执行范围）。</li>
 *   <li>图片上传/附件的存储写入与 VLM 分析：saveImageAttachments 的对象存储写入
 *       在 dev（本地盘）下同样生效；VLM 分析 emit-only 形态保留。</li>
 *   <li>临时附件的 ResolveForPrompt 内容选择（等待/跳过的事件形态保留，内容解析
 *       seam 随附件管线收口）。</li>
 * </ul>
 */
@RestController
@ApiResult
@Tag(name = "Session")
public class KnowledgeQaController {


    /** 检索面（knowledge-search）。 */
    private final SessionKnowledgeQaService knowledgeQaService;

    /** SSE 双入口（HTTP 与 embed 面共用；绑定→解析→执行收敛在 {@link QaEntryRunner}）。 */
    private final QaEntryRunner qaEntryRunner;

    public KnowledgeQaController(SessionKnowledgeQaService knowledgeQaService,
            QaEntryRunner qaEntryRunner) {
        this.knowledgeQaService = knowledgeQaService;
        this.qaEntryRunner = qaEntryRunner;
    }

    // ── 端点 ──────────────────────────────────────────────────────────────────

    @PostMapping("/api/v1/knowledge-chat/{sessionId}")
    public void knowledgeQA(@PathVariable("sessionId") String rawSessionId,
            @RequestBody(required = false) String rawBody,
            @RequestParam(value = Mode.QUERY_PARAM, required = false) String resourceUrls,
            HttpServletResponse response) throws IOException {
        qaEntryRunner.runKnowledgeQA(rawSessionId, rawBody, resourceUrls, new ServletSseSink(response));
    }

    @PostMapping("/api/v1/agent-chat/{sessionId}")
    public void agentQA(@PathVariable("sessionId") String rawSessionId,
            @RequestBody(required = false) String rawBody,
            @RequestParam(value = Mode.QUERY_PARAM, required = false) String resourceUrls,
            HttpServletResponse response) throws IOException {
        qaEntryRunner.runAgentQA(rawSessionId, rawBody, resourceUrls, new ServletSseSink(response));
    }

    @PostMapping("/api/v1/knowledge-search")
    public List<SearchResult> searchKnowledge(@RequestBody(required = false) String rawBody) {
        SearchKnowledgeRequest request = QaRequestBinder.bindSearchRequest(rawBody);
        if (request.query.isEmpty()) {
            // 空 query 已被绑定校验拦截，此分支仅兜底
            throw BizException.badRequest("Query content cannot be empty");
        }

        // 合并单个 knowledge_base_id 进 knowledge_base_ids
        List<String> knowledgeBaseIds = new ArrayList<>(request.knowledgeBaseIds());
        if (!request.knowledgeBaseId.isEmpty() && !knowledgeBaseIds.contains(request.knowledgeBaseId)) {
            knowledgeBaseIds.add(request.knowledgeBaseId);
        }

        List<QaSupport.TagScope> mentionScopes = QaSupport.tagScopesFromMentionedItems(request.mentionedItems());
        List<String> requestTagIds = QaSupport.dedupRequestStrings(request.tagIds());
        String tagError = QaSupport.validateUnscopedTagIds(
                QaSupport.orphanTagIdsForScope(requestTagIds, mentionScopes), knowledgeBaseIds);
        if (tagError != null) {
            throw BizException.badRequest(tagError);
        }
        List<QaSupport.TagScope> tagScopes = QaSupport.mergeTagScopesFromRequestIds(
                mentionScopes, requestTagIds, knowledgeBaseIds);

        if (knowledgeBaseIds.isEmpty() && request.knowledgeIds().isEmpty() && tagScopes.isEmpty()) {
            throw BizException.badRequest(
                    "At least one knowledge_base_id, knowledge_base_ids, knowledge_ids, or scoped tag must be provided");
        }
        TenantAPIKeyScope.authorizeKnowledgeTargets(
                knowledgeBaseIds, request.knowledgeIds());

        List<SearchResult> searchResults = knowledgeQaService.searchKnowledge(
                knowledgeBaseIds, request.knowledgeIds(), tagScopes, request.query);

        // 裸列表（无 {success,data} 信封）：检索结果直出。
        // 引用形式（resource_urls）在检索面不带存储引用（不经 Rewriter.copyReferences 改写）；
        // handle 模式为透传（public 模式的直链生成经 provider 级文件服务）。
        return searchResults;
    }

    // ── 请求体绑定（绑定错误文案逐字对齐） ───────────────────────────────

    // 曾有的 errorEventText（剥 BizException 取 appError().message()）已删除：
    // 其前提被线上 A/B 推翻（W5γ5.12），现统一走 BizException.wireText，理由见上面的调用点注释。
}
