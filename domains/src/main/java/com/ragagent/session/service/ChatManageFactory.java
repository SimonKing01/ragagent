package com.ragagent.session.service;

import com.ragagent.contract.event.EventBusInterface;
import com.ragagent.platform.settings.ConversationProperties;
import com.ragagent.session.conversation.SessionService;
import com.ragagent.session.rag.ChatManage;
import com.ragagent.session.rag.SummaryConfig;
import com.ragagent.session.service.SessionKnowledgeQaService.KnowledgeResolution;
import com.ragagent.session.service.SessionKnowledgeQaService.SearchTargetView;
import com.ragagent.session.support.PipelineViews;
import java.util.List;

/**
 * {@link ChatManage} 的请求装配工厂：把 40+ 个 setter 按语义收成 5 组。
 *
 * <p>只做「值 → setter」的搬运，不引入第二份状态：{@link ChatManage} 仍保持可变，
 * 插件间流转的字段与 setter 不动（录制等价性测试依赖这一形状）。</p>
 */
final class ChatManageFactory {

    private ChatManageFactory() {
    }

    /** 身份/请求/运行时句柄：query、会话、租户、多模态、附件、事件总线。 */
    static ChatManage create(QaSupport.QaRequest req, String sessionId, long tenantId,
                             EventBusInterface eventBus) {
        ChatManage cm = new ChatManage();
        cm.setQuery(req.query);
        cm.setSessionId(sessionId);
        cm.setUserId(SessionService.sessionUserIdForLookup());
        cm.setTenantId(tenantId);
        cm.setImages(req.imageUrls);
        cm.setAttachments(PipelineViews.ofAttachments(req.attachments));
        cm.setLanguage(SessionKnowledgeQaService.currentLanguage());
        cm.getQueryPlan().setRewriteQuery(req.query);
        cm.getQueryPlan().setImageDescription(req.imageDescription);
        cm.getQueryPlan().setQuotedContext(req.quotedContext);
        cm.getRuntime().setEventBus(eventBus);
        cm.getRuntime().setMessageId(req.assistantMessageId);
        cm.getRuntime().setUserMessageId(req.userMessageId);
        return cm;
    }

    /** 检索范围与阈值：KB/知识范围、搜索目标、向量/关键词/重排参数。 */
    static void applyRetrieval(ChatManage cm, KnowledgeResolution kb,
                               List<SearchTargetView> searchTargets, ConversationProperties cfg) {
        cm.setMaxRounds(cfg.getMaxRounds());
        cm.setKnowledgeBaseIds(kb.kbIds());
        cm.setKnowledgeIds(kb.knowledgeIds());
        cm.setSearchTargets(SearchTargetView.toPipeline(searchTargets));
        cm.setVectorThreshold(cfg.getVectorThreshold());
        cm.setKeywordThreshold(cfg.getKeywordThreshold());
        cm.setEmbeddingTopK(cfg.getEmbeddingTopK());
        cm.setRerankTopK(cfg.getRerankTopK());
        cm.setRerankThreshold(cfg.getRerankThreshold());
    }

    /** 模型与摘要：chat model、summary 配置、视觉模型路由。 */
    static void applyModel(ChatManage cm, String chatModelId, SummaryConfig summaryConfig,
                           boolean chatModelSupportsVision, String vlmModelId) {
        cm.setChatModelId(chatModelId);
        cm.setSummaryConfig(summaryConfig);
        cm.setChatModelSupportsVision(chatModelSupportsVision);
        cm.setVlmModelId(vlmModelId);
    }

    /** 生成/改写策略与 fallback。 */
    static void applyGeneration(ChatManage cm, ConversationProperties cfg, String fallbackStrategy) {
        cm.setFallbackStrategy(fallbackStrategy);
        cm.setFallbackResponse(cfg.getFallbackResponse());
        cm.setFallbackPrompt(cfg.getFallbackPrompt());
        cm.setEnableRewrite(cfg.isEnableRewrite());
        cm.setEnableQueryExpansion(cfg.isEnableQueryExpansion());
        cm.setRewritePromptSystem(cfg.getRewritePromptSystem());
        cm.setRewritePromptUser(cfg.getRewritePromptUser());
    }

    /** Web 搜索/抓取开关（provider 解析依赖租户）。 */
    static void applyWeb(ChatManage cm, QaSupport.QaRequest req, long tenantId,
                         QaWebSearchParams webSearchParams) {
        cm.setWebSearchEnabled(req.webSearchEnabled);
        cm.setWebSearchProviderId(webSearchParams.resolveWebSearchProviderId(req, tenantId));
        cm.setWebSearchMaxResults(webSearchParams.resolveWebSearchMaxResults(req));
        cm.setWebFetchEnabled(webSearchParams.resolveWebFetchEnabled(req));
        cm.setWebFetchTopN(webSearchParams.resolveWebFetchTopN(req));
    }

    /** 摘要配置的唯一构造点（原先在 service 里逐字段灌装）。 */
    static SummaryConfig summaryConfig(ConversationProperties cfg) {
        SummaryConfig summaryConfig = new SummaryConfig();
        summaryConfig.setPrompt(cfg.getSummaryPrompt());
        summaryConfig.setContextTemplate(cfg.getSummaryContextTemplate());
        summaryConfig.setTemperature(cfg.getSummaryTemperature());
        summaryConfig.setNoMatchPrefix(cfg.getSummaryNoMatchPrefix());
        summaryConfig.setMaxCompletionTokens(cfg.getSummaryMaxCompletionTokens());
        return summaryConfig;
    }
}
