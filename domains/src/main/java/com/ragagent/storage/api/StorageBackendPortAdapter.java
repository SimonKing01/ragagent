package com.ragagent.storage.api;

import com.ragagent.contract.storage.StorageBackendPort;
import com.ragagent.contract.storage.StorageBackendView;
import com.ragagent.platform.web.JsonMappers;
import com.ragagent.storage.domain.StorageBackend;
import com.ragagent.storage.repository.StorageBackendRepository;
import com.ragagent.storage.service.StorageBackendService;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@link StorageBackendPort} 的存储域实现：探测与脱敏转发运行时探测服务，清单转发元数据仓储。
 *
 * <p>语义逐字转发，不新增缓冲/改写：探测异常原样上抛（调用方按异常消息分派文案）、脱敏规则
 * 与 {@code StorageBackendService.sanitizeConnectivity} 同一实现。</p>
 */
@Component
public class StorageBackendPortAdapter implements StorageBackendPort {

    private final StorageBackendService backendService;
    private final StorageBackendRepository backendRepository;

    public StorageBackendPortAdapter(StorageBackendService backendService,
                                     StorageBackendRepository backendRepository) {
        this.backendService = backendService;
        this.backendRepository = backendRepository;
    }

    @Override
    public void test(StorageBackendView backend) {
        backendService.test(toEntity(backend));
    }

    @Override
    public String sanitizeConnectivity(String message) {
        return StorageBackendService.sanitizeConnectivity(message);
    }

    @Override
    public List<StorageBackendView> list(long tenantId) {
        return backendRepository.list(tenantId).stream().map(StorageBackendPortAdapter::toView).toList();
    }

    @Override
    public StorageBackendView findById(long tenantId, String id) {
        return toView(backendRepository.getById(tenantId, id).orElse(null));
    }

    @Override
    public StorageBackendView findActiveLegacyAliasByProvider(long tenantId, String provider) {
        return toView(backendRepository.findActiveLegacyAlias(tenantId, provider));
    }

    /** 实体 → 行视图（逐字段搬运；null 透传）。 */
    static StorageBackendView toView(StorageBackend b) {
        if (b == null) {
            return null;
        }
        StorageBackendView v = new StorageBackendView();
        v.setId(b.getId());
        v.setTenantId(b.getTenantId());
        v.setName(b.getName());
        v.setProvider(b.getProvider());
        // config 去 Jackson：JsonNode → 纯 JDK 值（序列化字节一致，见 JsonMappers#toJavaValue）
        v.setConfig(JsonMappers.toJavaValue(b.getConfig()));
        v.setSource(b.getSource());
        v.setStatus(b.getStatus());
        v.setLegacyAlias(b.isLegacyAlias());
        v.setCreatedAt(b.getCreatedAt());
        v.setUpdatedAt(b.getUpdatedAt());
        v.setDeletedAt(b.getDeletedAt());
        return v;
    }

    /** 行视图 → 实体（写面/探测用；逐字段搬运；null 透传）。 */
    static StorageBackend toEntity(StorageBackendView v) {
        if (v == null) {
            return null;
        }
        StorageBackend b = new StorageBackend();
        b.setId(v.getId());
        b.setTenantId(v.getTenantId());
        b.setName(v.getName());
        b.setProvider(v.getProvider());
        b.setConfig(JsonMappers.toJsonNode(v.getConfig()));
        b.setSource(v.getSource());
        b.setStatus(v.getStatus());
        b.setLegacyAlias(v.isLegacyAlias());
        b.setCreatedAt(v.getCreatedAt());
        b.setUpdatedAt(v.getUpdatedAt());
        b.setDeletedAt(v.getDeletedAt());
        return b;
    }
}
