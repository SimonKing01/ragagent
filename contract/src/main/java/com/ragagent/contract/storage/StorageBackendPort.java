package com.ragagent.contract.storage;

import java.util.List;

/**
 * 存储后端的运维面（L1）：连通性探测、错误脱敏、按租户列后端。
 *
 * <p><b>为什么是端口</b>：系统域的系统信息与"存储连通性检测"页需要这些能力，但探测逻辑
 * （各云 SDK）与元数据读取属存储域实现。端口只承载 {@link StorageBackendView}（行视图，
 * 实体已归位 `storage/domain`）。</p>
 */
public interface StorageBackendPort {

    /** 连通性探测：不可达 ⇒ 抛 {@code RuntimeException}（消息为 SDK 原文，供调用方分派文案）。 */
    void test(StorageBackendView backend);

    /** 连通性错误信息脱敏（抹掉凭证、内网地址等敏感片段）。 */
    String sanitizeConnectivity(String message);

    /** 租户的存储后端清单（顺序与仓储一致）。 */
    List<StorageBackendView> list(long tenantId);

    // ── 行级读取面（供知识域等按 id / provider 解析 KB 绑定的后端） ──

    /**
     * 租户内 + 未软删，按 id 取后端；无 ⇒ null。
     * （语义与 storage 域仓储 {@code getById} 一致：{@code tenant_id = ? AND id = ? AND deleted_at IS NULL}。）
     */
    StorageBackendView findById(long tenantId, String id);

    /**
     * 租户内按 provider 取**未软删**的 legacy alias 后端；按主键序取第一条；无 ⇒ null。
     *
     * <p>⚠️ <b>与 storage 域自用的 {@code findLegacyAlias} 刻意不同</b>：后者**不过滤
     * {@code deleted_at}**（storage 侧注释明示"刻意保留，别顺手修好"）。本方法保留知识域
     * 迁移前的语义（过滤未删行、无 ORDER BY 但有 LIMIT 1）；**两侧语义差异是既有事实，
     * 未在本刀合并**（如需统一须单独拍板）。</p>
     */
    StorageBackendView findActiveLegacyAliasByProvider(long tenantId, String provider);
}
