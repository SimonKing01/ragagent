package com.ragagent.retrieval.engine;

import com.ragagent.contract.retrieval.TenantStoreOwnership;
import com.ragagent.contract.vectorstore.VectorStoreLookup;

/**
 * {@link TenantStoreOwnership} 的生产实现。
 *
 * <p>仓储的 {@code getById} 自带租户范围（{@code WHERE id = ? AND tenant_id = ?}），
 * 所以"在该租户下存在"就等于"属于该租户"——返回行上不需要再比一次 tenant id。</p>
 */
public class VectorStoreRepoOwnership implements TenantStoreOwnership {

    private final VectorStoreLookup storeLookup;

    public VectorStoreRepoOwnership(VectorStoreLookup storeLookup) {
        this.storeLookup = storeLookup;
    }

    /**
     * store 非空即"属于"。仓储异常原样冒泡。
     */
    @Override
    public boolean storeOwnedBy(String storeId, long tenantId) {
        return storeLookup.byId(tenantId, storeId) != null;
    }
}
