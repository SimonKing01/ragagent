package com.ragagent.platform.web;

import com.ragagent.contract.agent.AgentStep;
import com.ragagent.contract.agent.AgentToolCall;
import com.ragagent.contract.agent.ToolCallTarget;
import com.ragagent.contract.auth.UserPreferences;
import com.ragagent.contract.error.AppError;
import com.ragagent.contract.event.payload.AgentActionData;
import com.ragagent.contract.event.payload.AgentCompleteData;
import com.ragagent.contract.event.payload.AgentFinalAnswerData;
import com.ragagent.contract.event.payload.AgentPlanData;
import com.ragagent.contract.event.payload.AgentQueryData;
import com.ragagent.contract.event.payload.AgentToolCallData;
import com.ragagent.contract.event.payload.AgentToolResultData;
import com.ragagent.contract.event.payload.ChatData;
import com.ragagent.contract.event.payload.ContextCompactedData;
import com.ragagent.contract.event.payload.ErrorData;
import com.ragagent.contract.event.payload.MCPOAuthRequiredData;
import com.ragagent.contract.event.payload.MCPOAuthResolvedData;
import com.ragagent.contract.event.payload.MergeData;
import com.ragagent.contract.event.payload.QueryData;
import com.ragagent.contract.event.payload.RerankData;
import com.ragagent.contract.event.payload.RetrievalData;
import com.ragagent.contract.event.payload.StopData;
import com.ragagent.contract.event.payload.ToolApprovalRequiredData;
import com.ragagent.contract.event.payload.ToolApprovalResolvedData;
import com.ragagent.contract.event.payload.UserMessageInjectedData;
import com.ragagent.contract.memory.MemoryMessageCursor;
import com.ragagent.contract.model.ChatMessage;
import com.ragagent.contract.model.ChatOptions;
import com.ragagent.contract.model.ChatResponse;
import com.ragagent.contract.model.ChatTool;
import com.ragagent.contract.model.FunctionDef;
import com.ragagent.contract.model.ImageUrl;
import com.ragagent.contract.model.MessageContentPart;
import com.ragagent.contract.model.RankResult;
import com.ragagent.contract.model.StreamResponse;
import com.ragagent.contract.model.TokenUsage;
import com.ragagent.contract.model.ToolCall;
import com.ragagent.contract.retrieval.SearchParams;
import com.ragagent.contract.retrieval.SearchResult;
import com.ragagent.contract.session.MessageView;
import com.ragagent.contract.tenant.APIPrincipalConfig;
import com.ragagent.contract.tenant.ChatHistoryConfig;
import com.ragagent.contract.tenant.ParserEngineConfig;
import com.ragagent.contract.tenant.RetrievalConfig;
import com.ragagent.contract.tenant.StorageEngineConfig;
import com.ragagent.contract.tenant.WebSearchConfig;
import com.ragagent.contract.tool.ToolResult;
import com.ragagent.contract.vectorstore.ConnectionConfig;
import com.ragagent.contract.vectorstore.IndexConfig;
import com.ragagent.contract.websearch.ImageInfo;
import com.ragagent.contract.wiki.ExtractedItem;
import com.ragagent.contract.wiki.SlugUpdate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 契约载荷的 mixin 映射表（纯 JDK 类型，不绑定 Jackson 版本）。
 *
 * <p>{@link ContractJsonModule} 注册这张表，所有读写契约载荷的 mapper 共用同一份映射，
 * 避免各 mapper 的键名/省略策略漂移。</p>
 */
public final class ContractJsonMixins {

    private ContractJsonMixins() {
    }

    /** 目标类型 → mixin 类型（顺序稳定，便于差异对比）。 */
    public static Map<Class<?>, Class<?>> mappings() {
        Map<Class<?>, Class<?>> m = new LinkedHashMap<>();
        m.put(AgentActionData.class, ContractJsonMixinsEvent.AgentActionDataMixin.class);
        m.put(AgentCompleteData.class, ContractJsonMixinsEvent.AgentCompleteDataMixin.class);
        m.put(AgentFinalAnswerData.class, ContractJsonMixinsEvent.AgentFinalAnswerDataMixin.class);
        m.put(AgentPlanData.class, ContractJsonMixinsEvent.AgentPlanDataMixin.class);
        m.put(AgentQueryData.class, ContractJsonMixinsEvent.AgentQueryDataMixin.class);
        m.put(AgentStep.class, ContractJsonMixinsAgent.AgentStepMixin.class);
        m.put(AgentToolCall.class, ContractJsonMixinsAgent.AgentToolCallMixin.class);
        m.put(AgentToolCallData.class, ContractJsonMixinsEvent.AgentToolCallDataMixin.class);
        m.put(AgentToolResultData.class, ContractJsonMixinsEvent.AgentToolResultDataMixin.class);
        m.put(AppError.class, ContractJsonMixinsCore.AppErrorMixin.class);
        m.put(ChatData.class, ContractJsonMixinsEvent.ChatDataMixin.class);
        m.put(ChatMessage.class, ContractJsonMixinsModelChat.ChatMessageMixin.class);
        m.put(ChatOptions.class, ContractJsonMixinsModelChat.ChatOptionsMixin.class);
        m.put(ChatResponse.class, ContractJsonMixinsModelChat.ChatResponseMixin.class);
        m.put(ChatTool.class, ContractJsonMixinsModelChat.ChatToolMixin.class);
        m.put(ConnectionConfig.class, ContractJsonMixinsCore.ConnectionConfigMixin.class);
        m.put(ContextCompactedData.class, ContractJsonMixinsEvent.ContextCompactedDataMixin.class);
        m.put(ErrorData.class, ContractJsonMixinsEvent.ErrorDataMixin.class);
        m.put(ExtractedItem.class, ContractJsonMixinsCore.ExtractedItemMixin.class);
        m.put(FunctionDef.class, ContractJsonMixinsModelChat.FunctionDefMixin.class);
        m.put(ImageInfo.class, ContractJsonMixinsWebSearch.ImageInfoMixin.class);
        m.put(RankResult.class, ContractJsonMixinsModel.RankResultMixin.class);
        m.put(RankResult.DocumentInfo.class, ContractJsonMixinsModel.RankResultMixin.DocumentInfoMixin.class);
        m.put(StreamResponse.class, ContractJsonMixinsModel.StreamResponseMixin.class);
        m.put(ImageUrl.class, ContractJsonMixinsModelChat.ImageUrlMixin.class);
        m.put(IndexConfig.class, ContractJsonMixinsCore.IndexConfigMixin.class);
        m.put(MessageContentPart.class, ContractJsonMixinsModelChat.MessageContentPartMixin.class);
        m.put(MCPOAuthRequiredData.class, ContractJsonMixinsEvent.MCPOAuthRequiredDataMixin.class);
        m.put(MCPOAuthResolvedData.class, ContractJsonMixinsEvent.MCPOAuthResolvedDataMixin.class);
        m.put(MergeData.class, ContractJsonMixinsEvent.MergeDataMixin.class);
        m.put(MemoryMessageCursor.class, ContractJsonMixinsMemory.MemoryMessageCursorMixin.class);
        m.put(MessageView.class, ContractJsonMixinsSession.MessageViewMixin.class);
        m.put(QueryData.class, ContractJsonMixinsEvent.QueryDataMixin.class);
        m.put(RerankData.class, ContractJsonMixinsEvent.RerankDataMixin.class);
        m.put(RetrievalData.class, ContractJsonMixinsEvent.RetrievalDataMixin.class);
        m.put(SearchParams.class, ContractJsonMixinsCore.SearchParamsMixin.class);
        m.put(SearchResult.class, ContractJsonMixinsRetrieval.SearchResultMixin.class);
        m.put(SlugUpdate.class, ContractJsonMixinsCore.SlugUpdateMixin.class);
        m.put(StopData.class, ContractJsonMixinsEvent.StopDataMixin.class);
        m.put(TokenUsage.class, ContractJsonMixinsCore.TokenUsageMixin.class);
        m.put(ToolApprovalRequiredData.class, ContractJsonMixinsEvent.ToolApprovalRequiredDataMixin.class);
        m.put(ToolApprovalResolvedData.class, ContractJsonMixinsEvent.ToolApprovalResolvedDataMixin.class);
        m.put(ToolCall.class, ContractJsonMixinsModelChat.ToolCallMixin.class);
        m.put(ToolCallTarget.class, ContractJsonMixinsAgent.ToolCallTargetMixin.class);
        m.put(ToolResult.class, ContractJsonMixinsCore.ToolResultMixin.class);
        m.put(UserMessageInjectedData.class, ContractJsonMixinsEvent.UserMessageInjectedDataMixin.class);
        m.put(UserPreferences.class, ContractJsonMixinsAuth.UserPreferencesMixin.class);
        m.put(WebSearchConfig.class, ContractJsonMixinsCore.WebSearchConfigMixin.class);
        m.put(ParserEngineConfig.class, ContractJsonMixinsCore.ParserEngineConfigMixin.class);
        m.put(APIPrincipalConfig.class, ContractJsonMixinsTenant.APIPrincipalConfigMixin.class);
        m.put(ChatHistoryConfig.class, ContractJsonMixinsTenant.ChatHistoryConfigMixin.class);
        m.put(RetrievalConfig.class, ContractJsonMixinsTenant.RetrievalConfigMixin.class);
        m.put(StorageEngineConfig.class, ContractJsonMixinsTenant.StorageEngineConfigMixin.class);
        return Map.copyOf(m);
    }
}
