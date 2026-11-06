package com.ragagent.contract.event.payload;


/**
 * 用户决定（或超时/取消）的确认事件体。
 * emit 点：common/approval/Gate（{@code <pendingID>-approval-resolved}）。
 *
 * <p>零值输出 {@code {"pendingId":"","approved":false}}；
 * {@code reason}/{@code timed_out}/{@code canceled} 空则省略。</p>
 */

public class ToolApprovalResolvedData {

    private String pendingId = "";

    /** false 恒输出 */
    private boolean approved;

    /** 空串省略 */
    private String reason = "";

    /** false 省略 */
    private boolean timedOut;

    /** false 省略 */
    private boolean canceled;

    public ToolApprovalResolvedData() {
    }

    public ToolApprovalResolvedData(String pendingId, boolean approved, String reason,
                                    boolean timedOut, boolean canceled) {
        this.pendingId = QueryData.orEmpty(pendingId);
        this.approved = approved;
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

    public boolean isApproved() {
        return approved;
    }

    public void setApproved(boolean v) {
        this.approved = v;
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
