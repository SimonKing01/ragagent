package com.ragagent.contract.event.payload;


/**
 * 停止生成请求数据。
 * continue-stream 的 stop watcher 检测到该事件即取消生成
 * （handler/session 层发出）。
 * {@code reason} 空串省略。
 */

public class StopData {

    private String sessionId = "";

    private String messageId = "";

    /** 停止原因（可选）；空串省略 */
    private String reason = "";

    public StopData() {
    }

    public StopData(String sessionId, String messageId, String reason) {
        this.sessionId = QueryData.orEmpty(sessionId);
        this.messageId = QueryData.orEmpty(messageId);
        this.reason = QueryData.orEmpty(reason);
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String v) {
        this.sessionId = QueryData.orEmpty(v);
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String v) {
        this.messageId = QueryData.orEmpty(v);
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String v) {
        this.reason = QueryData.orEmpty(v);
    }
}
