package com.ragagent.knowledge.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.contract.knowledge.projection.KnowledgeProjectionAdapter;
import com.ragagent.knowledge.domain.KnowledgeTag;
import com.ragagent.platform.web.JsonMappers;

/**
 * 标签实体 → JSON 视图投影（域内；方法体自 L1
 * {@code KnowledgeProjectionAdapter#tagView} 原样搬入，时间序列化复用其 {@code timeString}）。
 *
 * <p>搬下来的理由：本投影吃**实体**，而实体属知识域内部（HANDOFF 判据 1）——
 * L1 只保留吃载荷的投影（{@code timeString}）。</p>
 */
public final class TagViews {

    /** 与全局 Jackson 配置同式。 */
    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    private TagViews() {
    }

    /** 标签视图（时间序列化与全局 Jackson 配置同式）。 */
    public static ObjectNode tagView(KnowledgeTag t) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("id", t.getId());
        n.put("seqId", t.getSeqId() == null ? 0L : t.getSeqId());
        n.put("knowledgeBaseId", t.getKnowledgeBaseId());
        n.put("name", t.getName());
        n.put("color", t.getColor() == null ? "" : t.getColor());
        n.put("sortOrder", t.getSortOrder() == null ? 0 : t.getSortOrder());
        n.put("createdAt", t.getCreatedAt() == null ? null : KnowledgeProjectionAdapter.timeString(t.getCreatedAt()));
        n.put("updatedAt", t.getUpdatedAt() == null ? null : KnowledgeProjectionAdapter.timeString(t.getUpdatedAt()));
        return n;
    }
}
