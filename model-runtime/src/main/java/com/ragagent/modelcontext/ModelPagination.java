package com.ragagent.modelcontext;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/**
 * {@code knowledge_chunks_list} 的分页尾注：总块数、本页覆盖的块号范围、下一段起点。
 *
 * <p>只给模型用得上的四个值（total/fromIndex/toIndex/nextStartChunkIndex）；
 * 页码是 UI 概念，不进入模型面。</p>
 */
final class ModelPagination {

    private ModelPagination() {}

    /** 渲染 {@code <pagination ... />}（调用方负责闭合 retrieval 标签）。 */
    static String render(Map<String, Object> data, List<Map<String, JsonNode>> rows) {
        int total = ModelOutput.intValue(data, "totalChunks");
        int fetched = ModelOutput.intValue(data, "fetchedChunks");
        int fromIndex = -1;
        int toIndex = -1;
        for (Map<String, JsonNode> row : rows) {
            if (!row.containsKey("chunkIndex")) {
                continue;
            }
            int idx = ModelOutput.intValue(row, "chunkIndex");
            if (fromIndex < 0 || idx < fromIndex) {
                fromIndex = idx;
            }
            if (idx > toIndex) {
                toIndex = idx;
            }
        }
        StringBuilder pag = new StringBuilder("  <pagination total=\"").append(total).append('"');
        if (fromIndex >= 0) {
            pag.append(" fromIndex=\"").append(fromIndex).append("\" toIndex=\"").append(toIndex).append('"');
        }
        if (total - fetched > 0) {
            // 下一段起点：优先用工具下推的精确块号，旧投影按页反推兜底
            int start = data.containsKey("startChunkIndex") ? ModelOutput.intValue(data, "startChunkIndex")
                    : data.containsKey("offset") ? ModelOutput.intValue(data, "offset")
                    : (ModelOutput.intValue(data, "page") <= 1 ? 0
                            : (ModelOutput.intValue(data, "page") - 1) * ModelOutput.intValue(data, "pageSize"));
            pag.append(" nextStartChunkIndex=\"").append(start + fetched).append('"');
        }
        pag.append(" />");
        return pag.toString();
    }
}
