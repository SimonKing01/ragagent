package com.ragagent.im.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.contract.agent.AgentLookup;
import com.ragagent.contract.json.JsonValues;
import com.ragagent.contract.session.MessageAttachment;
import com.ragagent.contract.session.MessageView;
import com.ragagent.contract.session.QaRequestPayload;
import com.ragagent.contract.session.SessionView;
import com.ragagent.im.runtime.ImFormat;
import com.ragagent.im.runtime.IncomingMessage;
import com.ragagent.im.service.ImTurnOrchestrator.InflightEntry;
import java.util.List;

/**
 * QA 管线的共享底座：agent 模式判定、IM 请求构造（含 agent config 缺省补全）、
 * 用户/助手消息落库。runQA（同步）与流式管线共用。
 */
final class ImQaRequests {

    private final ImTurnOrchestrator service;

    ImQaRequests(ImTurnOrchestrator service) {
        this.service = service;
    }


    static boolean isAgentMode(String configJson) {
        // agentMode == "smart-reasoning" 即智能推理
        if (configJson == null || configJson.isEmpty()) {
            return false;
        }
        try {
            JsonNode cfg = ImTurnOrchestrator.JSON.readTree(configJson);
            return "smart-reasoning".equals(cfg.path("agentMode").asText(""));
        } catch (Exception e) {
            return false;
        }
    }

    /** 构造 QA 请求（含 agent config 缺省补全与 /stop 取消探针）。 */
    QaRequestPayload buildIMQARequest(SessionView session, String query,
            String assistantMessageId, String userMessageId, AgentLookup.AgentView agent,
            IncomingMessage.QuotedMessage quote, InflightEntry inflight) {
        QaRequestPayload req = new QaRequestPayload();
        req.session = session;
        req.query = query;
        req.assistantMessageId = assistantMessageId;
        req.userMessageId = userMessageId;
        // agent 实体由 session 面按 id 解析；装配面只传 id + 归一化配置（域间只经契约）
        req.agentId = agent == null ? "" : agent.id();
        if (agent != null) {
            req.agentConfig = service.agentLookup.defaultedConfig(agent.configJson());
        }
        req.webSearchEnabled = JsonValues.boolAt(req.agentConfig, "webSearchEnabled", false);
        req.quotedContext = ImFormat.formatQuotedContext(quote);
        if (inflight != null) {
            // IM /stop → 引擎取消：探针读队列层的取消标志（与 web 面 QaTurnExecutor 同契约，
            // 贯穿 think/act/审批等待三条路）。
            req.cancellationProbe = () -> inflight.queueReq.isCancelled()
                    ? "context canceled" : null;
        }

        return req;
    }

    /** 用户消息落库（带附件元数据；无附件传空表）。 */
    MessageView createUserMessage(String sessionId, String content, String requestId,
            List<MessageAttachment> attachments) {
        MessageView m = new MessageView();
        m.setSessionId(sessionId);
        m.setRole("user");
        m.setContent(content);
        m.setRequestId(requestId);
        m.setCompleted(true);
        m.setChannel("im");
        if (attachments != null && !attachments.isEmpty()) {
            m.setAttachments(attachments);
        }
        return service.sessionQaPort.createMessage(m);
    }

    /** 助手消息落库。 */
    MessageView createAssistantMessage(String sessionId, String requestId) {
        MessageView m = new MessageView();
        m.setSessionId(sessionId);
        m.setRole("assistant");
        m.setRequestId(requestId);
        m.setChannel("im");
        return service.sessionQaPort.createMessage(m);
    }
}
