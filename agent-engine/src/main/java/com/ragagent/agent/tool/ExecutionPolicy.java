package com.ragagent.agent.tool;
import com.ragagent.contract.tool.ToolDefinitions;

/**
 * 并发执行白名单。
 *
 * <p>只有内建<b>读</b>工具允许并发。写操作、任意代码、未知/MCP 工具都是执行屏障——
 * 尤其是文件写必须在前一个 shell / skill 调用开始前完成。</p>
 *
 * <p><b>有会话状态</b>的读工具也是屏障：{@code knowledge_search} / {@code grep_chunks}
 * 的输出带 `already_seen` 标记，依据是工具实例上的会话级 `seenChunks`——并发会让多线程写
 * 同一个非线程安全集合，且"谁算已见"取决于完成顺序而非调用顺序（乱序完成 ⇒ 输出不稳定）。
 * 要让它们恢复并发，得先把"取数"与"渲染 + 标记"拆成两段（渲染交给单 writer 按调用序执行），
 * 或做成"同类有序、异类可并行"的批次策略。</p>
 */
public final class ExecutionPolicy {

    private ExecutionPolicy() {
    }

    public static boolean canRunConcurrently(String name) {
        return switch (name) {
            case ToolDefinitions.TOOL_LIST_KNOWLEDGE_CHUNKS,
                    ToolDefinitions.TOOL_QUERY_KNOWLEDGE_GRAPH,
                    ToolDefinitions.TOOL_GET_DOCUMENT_INFO,
                    ToolDefinitions.TOOL_SEARCH_CONVERSATIONS,
                    ToolDefinitions.TOOL_SEARCH_MEMORY,
                    ToolDefinitions.TOOL_DATA_SCHEMA,
                    ToolDefinitions.TOOL_WEB_SEARCH,
                    ToolDefinitions.TOOL_WEB_FETCH,
                    ToolDefinitions.TOOL_READ_FILE,
                    ToolDefinitions.TOOL_LIST_SANDBOX_FILES,
                    ToolDefinitions.TOOL_WIKI_SEARCH,
                    ToolDefinitions.TOOL_WIKI_READ_PAGE,
                    ToolDefinitions.TOOL_WIKI_READ_SOURCE_DOC,
                    ToolDefinitions.TOOL_WIKI_READ_ISSUE -> true;
            default -> false;
        };
    }
}
