package com.ragagent.vectorstore.repository;

import com.ragagent.vectorstore.domain.VectorStore;
import com.ragagent.vectorstore.mapper.VectorStoreMapper;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * 向量库仓储门面：把 {@link VectorStoreMapper} 的显式 SQL 方法收在一处，
 * service 与端口适配器只依赖本类。
 */
@Repository
public class VectorStoreRepository {

    private final VectorStoreMapper mapper;

    public VectorStoreRepository(VectorStoreMapper mapper) {
        this.mapper = mapper;
    }

    /** 按 (tenant, id) 取一条（含租户过滤与 config 解密）。 */
    public VectorStore getById(long tenantId, String id) {
        return mapper.getById(tenantId, id);
    }

    public List<VectorStore> list(long tenantId) {
        return mapper.list(tenantId);
    }

    public int create(VectorStore store, OffsetDateTime now) {
        return mapper.create(store, now);
    }

    public int updateName(VectorStore store, OffsetDateTime now) {
        return mapper.updateName(store, now);
    }

    public int updateConnectionConfig(VectorStore store) {
        return mapper.updateConnectionConfig(store);
    }

    public int delete(long tenantId, String id) {
        return mapper.delete(tenantId, id);
    }
}
