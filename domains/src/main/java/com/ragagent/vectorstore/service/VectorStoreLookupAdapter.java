package com.ragagent.vectorstore.service;

import com.ragagent.contract.vectorstore.ConnectionConfig;
import com.ragagent.contract.vectorstore.IndexConfig;
import com.ragagent.contract.vectorstore.VectorStoreLookup;
import com.ragagent.contract.vectorstore.VectorStoreView;
import com.ragagent.vectorstore.domain.VectorStore;
import com.ragagent.vectorstore.repository.VectorStoreRepository;
import org.springframework.stereotype.Component;

/**
 * {@link VectorStoreLookup} 的 vectorstore 侧实现。
 *
 * <p>直接委托既有仓储的 {@code getById(tenantId, storeId)}（含其租户过滤与解密的
 * 类型处理器），不复制任何查询逻辑。</p>
 */
@Component
public class VectorStoreLookupAdapter implements VectorStoreLookup {

    private final VectorStoreRepository repository;

    public VectorStoreLookupAdapter(VectorStoreRepository repository) {
        this.repository = repository;
    }

    @Override
    public VectorStoreView byId(long tenantId, String storeId) {
        VectorStore store = repository.getById(tenantId, storeId);
        if (store == null) {
            return null;
        }
        ConnectionConfig conn = store.getConnectionConfig();
        IndexConfig idx = store.getIndexConfig();
        return new VectorStoreView(store.getId(), store.getTenantId() == null ? 0L : store.getTenantId(),
                store.getName(), store.getEngineType(), conn, idx);
    }
}
