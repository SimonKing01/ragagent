package com.ragagent.contract.event.payload;


/**
 * 最终答案流式数据。
 * 7 个 emit 点（ThinkPhase / ObservePhase / FinalizePhase）——
 * 最高危的 SSE 时序事件：同一 id 的分片在客户端重组（扣留键 = 类型 + NUL + 事件 id）。
 *
 * <p>零值输出 {@code {"content":"","done":false}}；{@code isFallback}
 * 为 true 才输出（无知识库命中的兜底回答标记）。</p>
 */

public class AgentFinalAnswerData {

    private String content = "";

    /** false 恒输出（Done:true 是收尾标记） */
    private boolean done;

    /** 兜底回答（无知识库命中）标记；false 省略 */
    private boolean isFallback;

    /**
     * 本轮业务终态（null=正常 done；FAILED=异常收束）。
     *
     * <p>终态答案帧只表示“流已关闭”，不表示业务成功；收尾方必须看本字段，
     * 否则超时/ERROR+done 补的空终态帧会被当成成功。</p>
     */
    private String outcome;

    public AgentFinalAnswerData() {
    }

    public AgentFinalAnswerData(String content, boolean done, boolean isFallback) {
        this.content = QueryData.orEmpty(content);
        this.done = done;
        this.isFallback = isFallback;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String v) {
        this.content = QueryData.orEmpty(v);
    }

    public boolean isDone() {
        return done;
    }

    public void setDone(boolean v) {
        this.done = v;
    }

    /** getter 也标注同名列：否则 Jackson 会把 isFallback() 拆成多余的 "fallback" 属性（实测踩过） */
    public boolean isFallback() {
        return isFallback;
    }

    public void setIsFallback(boolean v) {
        this.isFallback = v;
    }

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String v) {
        outcome = v == null || v.isEmpty() ? null : v;
    }
}
