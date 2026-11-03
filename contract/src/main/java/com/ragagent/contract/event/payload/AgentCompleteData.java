package com.ragagent.contract.event.payload;

import java.util.List;
import java.util.Map;


/**
 * Agent 完成事件数据。
 * emit 点：FinalizePhase（{@code generateEventId("complete")}），见包注释 emit 表 #16。
 *
 * <p>{@code knowledge_refs}/{@code agent_steps}/{@code usage} 空则整键省略
 * （空列表也省略）；{@code total_duration_ms} 恒输出。</p>
 */

public class AgentCompleteData {

    private String sessionId = "";

    private int totalSteps;

    private String finalAnswer = "";

    /** null 或空列表都省略 */
    private List<Object> knowledgeRefs;

    /** null 或空省略 */
    private Object agentSteps;

    /** null 省略 */
    private Object usage;

    /** 0 恒输出 */
    private long totalDurationMs;

    /** Assistant message ID；空串省略 */
    private String messageId = "";

    /** 空串省略 */
    private String requestId = "";

    /** null 或空省略；**失败收束**时显式携带 {@code {"outcome":"failed","error":<msg>}}
     *  （A1-AGENT：客户端据此区分"正常收尾"与"失败收尾"；成功不带 = 缺省即成功）。 */
    private Map<String, Object> extra;

    public AgentCompleteData() {
    }

    public AgentCompleteData(String sessionId, int totalSteps, String finalAnswer,
                             List<Object> knowledgeRefs, Object agentSteps, Object usage,
                             long totalDurationMs, String messageId, String requestId,
                             Map<String, Object> extra) {
        this.sessionId = QueryData.orEmpty(sessionId);
        this.totalSteps = totalSteps;
        this.finalAnswer = QueryData.orEmpty(finalAnswer);
        this.knowledgeRefs = knowledgeRefs;
        this.agentSteps = agentSteps;
        this.usage = usage;
        this.totalDurationMs = totalDurationMs;
        this.messageId = QueryData.orEmpty(messageId);
        this.requestId = QueryData.orEmpty(requestId);
        this.extra = extra;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String v) {
        this.sessionId = QueryData.orEmpty(v);
    }

    public int getTotalSteps() {
        return totalSteps;
    }

    public void setTotalSteps(int v) {
        this.totalSteps = v;
    }

    public String getFinalAnswer() {
        return finalAnswer;
    }

    public void setFinalAnswer(String v) {
        this.finalAnswer = QueryData.orEmpty(v);
    }

    public List<Object> getKnowledgeRefs() {
        return knowledgeRefs;
    }

    public void setKnowledgeRefs(List<Object> v) {
        this.knowledgeRefs = v;
    }

    public Object getAgentSteps() {
        return agentSteps;
    }

    public void setAgentSteps(Object v) {
        this.agentSteps = v;
    }

    public Object getUsage() {
        return usage;
    }

    public void setUsage(Object v) {
        this.usage = v;
    }

    public long getTotalDurationMs() {
        return totalDurationMs;
    }

    public void setTotalDurationMs(long v) {
        this.totalDurationMs = v;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String v) {
        this.messageId = QueryData.orEmpty(v);
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String v) {
        this.requestId = QueryData.orEmpty(v);
    }

    public Map<String, Object> getExtra() {
        return extra;
    }

    public void setExtra(Map<String, Object> v) {
        this.extra = v;
    }
}
