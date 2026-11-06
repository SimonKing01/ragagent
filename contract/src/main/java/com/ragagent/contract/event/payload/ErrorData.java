package com.ragagent.contract.event.payload;

import java.util.Map;


/**
 * 错误事件数据。
 * agent 引擎唯一 emit 点：AgentEngine（stage="agent_execution"）。
 */

public class ErrorData {

    private String error = "";

    /** 空串省略 */
    private String errorCode = "";

    /** 错误发生的阶段（恒输出） */
    private String stage = "";

    private String sessionId = "";

    /** 空串省略 */
    private String query = "";

    /** null 或空省略 */
    private Map<String, Object> extra;

    public ErrorData() {
    }

    public ErrorData(String error, String errorCode, String stage, String sessionId,
                     String query, Map<String, Object> extra) {
        this.error = QueryData.orEmpty(error);
        this.errorCode = QueryData.orEmpty(errorCode);
        this.stage = QueryData.orEmpty(stage);
        this.sessionId = QueryData.orEmpty(sessionId);
        this.query = QueryData.orEmpty(query);
        this.extra = extra;
    }

    public String getError() {
        return error;
    }

    public void setError(String v) {
        this.error = QueryData.orEmpty(v);
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String v) {
        this.errorCode = QueryData.orEmpty(v);
    }

    public String getStage() {
        return stage;
    }

    public void setStage(String v) {
        this.stage = QueryData.orEmpty(v);
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String v) {
        this.sessionId = QueryData.orEmpty(v);
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String v) {
        this.query = QueryData.orEmpty(v);
    }

    public Map<String, Object> getExtra() {
        return extra;
    }

    public void setExtra(Map<String, Object> v) {
        this.extra = v;
    }
}
