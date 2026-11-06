package com.ragagent.contract.event.payload;


/**
 * 会话内 OAuth 提示结果（authorized / timeout / cancel）确认事件体。
 * emit 点：common/approval/Gate（{@code <pendingID>-mcp-oauth-resolved}）。
 *
 * <p>零值输出 {@code {"pendingId":"","serviceId":"","authorized":false}}；
 * {@code reason}/{@code timed_out}/{@code canceled} 空则省略。</p>
 */

public class MCPOAuthResolvedData {

    private String pendingId = "";

    private String serviceId = "";

    /** false 恒输出 */
    private boolean authorized;

    /** 空串省略 */
    private String reason = "";

    /** false 省略 */
    private boolean timedOut;

    /** false 省略 */
    private boolean canceled;

    public MCPOAuthResolvedData() {
    }

    public MCPOAuthResolvedData(String pendingId, String serviceId, boolean authorized,
                                String reason, boolean timedOut, boolean canceled) {
        this.pendingId = QueryData.orEmpty(pendingId);
        this.serviceId = QueryData.orEmpty(serviceId);
        this.authorized = authorized;
        this.reason = QueryData.orEmpty(reason);
        this.timedOut = timedOut;
        this.canceled = canceled;
    }

    public String getPendingId() {
        return pendingId;
    }

    public void setPendingId(String v) {
        this.pendingId = QueryData.orEmpty(v);
    }

    public String getServiceId() {
        return serviceId;
    }

    public void setServiceId(String v) {
        this.serviceId = QueryData.orEmpty(v);
    }

    public boolean isAuthorized() {
        return authorized;
    }

    public void setAuthorized(boolean v) {
        this.authorized = v;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String v) {
        this.reason = QueryData.orEmpty(v);
    }

    public boolean isTimedOut() {
        return timedOut;
    }

    public void setTimedOut(boolean v) {
        this.timedOut = v;
    }

    public boolean isCanceled() {
        return canceled;
    }

    public void setCanceled(boolean v) {
        this.canceled = v;
    }
}
