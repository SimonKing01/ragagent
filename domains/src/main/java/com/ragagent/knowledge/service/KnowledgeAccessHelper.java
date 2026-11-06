package com.ragagent.knowledge.service;

import com.ragagent.contract.apikey.TenantAPIKeyScope;
import com.ragagent.contract.error.AppError;
import com.ragagent.contract.error.BizException;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.domain.KnowledgeTag;
import com.ragagent.knowledge.domain.KnowledgeTenants;
import com.ragagent.knowledge.repository.KnowledgeBaseRepository;
import com.ragagent.knowledge.repository.KnowledgeRepository;
import com.ragagent.knowledge.repository.KnowledgeTagRepository;
import com.ragagent.knowledge.support.TagViews;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 知识域访问面收敛点。
 *
 * <p>requireKb / findKb / getKnowledge / getKnowledgeBatch 等是全子服务共用的
 * 访问原语，下沉为本类后子服务直接依赖这里、不回注门面
 * （数据访问契约与门面公开语义一致）。</p>
 */
@Component
public class KnowledgeAccessHelper {

    private final KnowledgeRepository knowledgeRepository;
    private final KnowledgeBaseRepository kbRepository;
    private final KnowledgeTagRepository tagRepository;

    public KnowledgeAccessHelper(KnowledgeRepository knowledgeRepository,
                                 KnowledgeBaseRepository kbRepository,
                                 KnowledgeTagRepository tagRepository) {
        this.knowledgeRepository = knowledgeRepository;
        this.kbRepository = kbRepository;
        this.tagRepository = tagRepository;
    }

    /** 按 id 查 KB（软删不可见）。 */
    public KnowledgeBase findKb(String kbId) {
        return kbRepository.findByIdOnly(kbId);
    }

    public KnowledgeBase requireKb(String kbId) {
        // KB 受限的 API Key 不能触碰白名单外的库。
        TenantAPIKeyScope.authorizeKnowledgeBases(
                kbId == null ? List.of() : List.of(kbId));
        KnowledgeBase kb = kbRepository.findById(KnowledgeTenants.tenantId(), kbId);
        if (kb == null) {
            throw new BizException(AppError.notFound("knowledge base not found"));
        }
        return kb;
    }

    public Knowledge getKnowledge(String id) {
        Knowledge k = knowledgeRepository.findById(KnowledgeTenants.tenantId(), id);
        if (k == null) {
            throw new BizException(AppError.notFound("Knowledge not found"));
        }
        // 按 knowledgeId 操作的端点，
        // 用其所属 KB 做 scope 校验（KB 受限的 Key 不得越界）。
        TenantAPIKeyScope.authorizeKnowledgeBases(
                List.of(k.getKnowledgeBaseId()));
        // 回填 tags（knowledge_tag_relations 连接查；
        // 无关系 → 保持 null，与 契约样例 "tags":null 一致）
        attachTags(k);
        return k;
    }

    /** 调用者空间内的可空读取。 */
    public Knowledge getKnowledgeInTenant(long tenantId, String id) {
        return knowledgeRepository.findById(tenantId, id);
    }

    /**
     * 按 (tenant, ids) 批量取，<b>不回填 tags</b>
     */
    public List<Knowledge> getKnowledgeBatch(long tenantId, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return new ArrayList<>();
        }
        return knowledgeRepository.listByIds(tenantId, ids);
    }

    /**
     * 本仓未实现，共享路径的"补捞"只对同租户行有效，而租户内行
     */
    public List<Knowledge> getKnowledgeBatchWithSharedAccess(long tenantId, List<String> ids) {
        return getKnowledgeBatch(tenantId, ids);
    }

    /** 有关系才回填（无关系保持 null）。 */
    private void attachTags(Knowledge k) {
        if (k == null) {
            return;
        }
        List<KnowledgeTag> rows = tagRepository.listTagsWithKnowledgeId(k.getId());
        if (!rows.isEmpty()) {
            k.setTags(new ArrayList<>(rows.stream().map(TagViews::tagView).toList()));
        }
    }
}
