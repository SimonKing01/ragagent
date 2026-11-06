package com.ragagent.contract.tool;



/**
 * 工具执行的入参。
 *
 * <p>携带两样东西：</p>
 * <ol>
 * <li><b>调用元数据</b>：{@code #execMeta}（可为 null，直连执行时没有）。</li>
 * <li><b>输出预算</b>：{@code #budget}。{@code #outputBudget()} ≤0 时回落
 * {@link ToolOutput#DEFAULT_MAX_TOOL_OUTPUT}。</li>
 * </ol>
 *
 * <p>取消探测独立于预算：{@code #cancellation}。</p>
 *
 * <p><b>（去 Jackson）</b>：{@code args} 由 {@code JsonNode} 改为**纯 JDK 值**
 * （对象→{@code Map}、数组→{@code List}、标量→包装类型）。工具实现侧若要按 JSON 树读
 * 入参，用 {@code JsonMappers.toJsonNode(request.args())} 归一（对已是节点的输入幂等，
 * 兼容期测试直接传节点亦可）。</p>
 */
public record ToolRequest(Object args, ToolExecContext execMeta, ToolCancellation cancellation, int budget) {

    /** 无元数据、无取消、无预算的直连请求（测试/独立执行用）。 */
    public static ToolRequest of(Object args) {
        return new ToolRequest(args, null, ToolCancellation.LIVE, 0);
    }

    public ToolRequest {
        if (cancellation == null) {
            cancellation = ToolCancellation.LIVE;
        }
    }

    /** 输出预算：≤0 回落 {@code ToolOutput.DEFAULT_MAX_TOOL_OUTPUT}。 */
    public int outputBudget() {
        return budget > 0 ? budget : ToolOutput.DEFAULT_MAX_TOOL_OUTPUT;
    }

    /** 是否已取消。 */
    public boolean isCancelled() {
        return cancellation.cancellationError() != null;
    }

    /** 带 execMeta 时的 sessionId 快捷读取（无元数据返回 ""）。 */
    public String sessionId() {
        return execMeta != null ? execMeta.sessionId() : "";
    }

    /** 带 execMeta 时的 toolCallId 快捷读取（无元数据返回 ""）。 */
    public String toolCallId() {
        return execMeta != null ? execMeta.toolCallId() : "";
    }
}
