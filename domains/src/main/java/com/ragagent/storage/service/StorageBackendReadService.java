package com.ragagent.storage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.contract.error.BizException;
import com.ragagent.platform.web.JsonMappers;
import com.ragagent.storage.domain.StorageBackend;
import com.ragagent.storage.dto.StorageBackendResponse;
import com.ragagent.storage.dto.StorageConfig;
import com.ragagent.storage.provider.StorageProviders;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 存储后端控制面读模型：控制器只拿响应 DTO。 */
@Service
public class StorageBackendReadService {

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    private final StorageBackendService service;

    public StorageBackendReadService(StorageBackendService service) {
        this.service = service;
    }

    public StorageBackendResponse getResponse(long tenantId, String id) {
        StorageBackend backend = service.getBackend(tenantId, id);
        return backend == null ? null : response(backend);
    }

    public List<StorageBackendResponse> listResponses(long tenantId) {
        List<StorageBackendResponse> out = new ArrayList<>();
        for (StorageBackend backend : service.listBackends(tenantId)) {
            out.add(response(backend));
        }
        return out;
    }

    public StorageBackendResponse createResponse(long tenantId, String name, String provider,
            StorageConfig config, String status) {
        StorageBackend backend = carrier(tenantId, name, provider, config, status);
        service.create(backend);
        return response(backend);
    }

    public StorageBackendResponse updateResponse(long tenantId, String id, String name,
            String provider, StorageConfig config, String status) {
        StorageBackend backend = carrier(tenantId, name, provider, config, status);
        backend.setId(id);
        service.update(backend);
        StorageBackend refreshed = service.getBackend(tenantId, id);
        return refreshed == null ? null : response(refreshed);
    }

    public Map<String, Object> testRawResponse(long tenantId, String name, String provider,
            StorageConfig config, String status) {
        StorageBackend backend = carrier(tenantId, name, provider, config, status);
        service.validate(backend);
        try {
            service.test(backend);
        } catch (RuntimeException e) {
            return failureBody(e);
        }
        return connectedBody();
    }

    public Map<String, Object> testByIdResponse(long tenantId, String id) {
        StorageBackend backend = service.getBackend(tenantId, id);
        if (backend == null) {
            return null;
        }
        try {
            service.test(backend);
        } catch (RuntimeException e) {
            return failureBody(e);
        }
        return connectedBody();
    }

    private StorageBackend carrier(long tenantId, String name, String provider,
            StorageConfig config, String status) {
        StorageBackend backend = new StorageBackend();
        backend.setTenantId(tenantId);
        backend.setName(name);
        backend.setProvider(provider);
        backend.setConfig(MAPPER.valueToTree(config == null ? new StorageConfig() : config));
        backend.setStatus(status == null ? "" : status);
        return backend;
    }

    private StorageBackendResponse response(StorageBackend backend) {
        StorageBackendResponse r = new StorageBackendResponse();
        r.id = backend.getId();
        r.tenantId = backend.getTenantId() == null ? 0 : backend.getTenantId();
        r.name = backend.getName();
        r.provider = backend.getProvider();
        r.config = service.maskedConfig(backend);
        r.source = backend.getSource();
        r.status = backend.getStatus();
        r.legacyAlias = backend.isLegacyAlias();
        r.createdAt = backend.getCreatedAt();
        r.updatedAt = backend.getUpdatedAt();
        r.deletedAt = backend.getDeletedAt();
        return r;
    }

    private Map<String, Object> failureBody(RuntimeException e) {
        String message;
        if (e instanceof BizException biz) {
            message = biz.appError().message();
        } else if (e instanceof StorageProviders.ConnectorFailure cf) {
            message = StorageBackendService.sanitizeConnectivity(cf.getMessage());
        } else {
            message = StorageBackendService.sanitizeConnectivity(e.getMessage());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("connected", false);
        body.put("error", message);
        return body;
    }

    private static Map<String, Object> connectedBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("connected", true);
        return body;
    }

    // ── 校验 ──────────────────────────────────────────────────────────────

    /** 校验：**会就地修改** name（trim）/provider（小写）/status（缺省 active） */

}
