package com.ragagent.storage.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.contract.error.AppError;
import com.ragagent.contract.error.BizException;
import com.ragagent.contract.storage.StorageConnectivityPort;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.platform.web.ApiResult;
import com.ragagent.platform.web.JsonMappers;
import com.ragagent.storage.dto.StorageEngineDtos;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 存储引擎能力面：状态清单 + 连通性检测。
 *
 * <p>路由从 {@code /api/v1/system/storage-engine-*} 迁来——存储探测是 storage 域的能力，
 * system 只保留"系统信息/能力探测"（capabilities/info）。请求体键名保持 snake：
 * 与租户存储配置 jsonb 同形。</p>
 */
@RestController
@ApiResult
@RequestMapping("/api/v1/storage")
@Tag(name = "Storage")
public class StorageEngineController {

    private final StorageConnectivityPort storageProbe;

    public StorageEngineController(StorageConnectivityPort storageProbe) {
        this.storageProbe = storageProbe;
    }

    @GetMapping("/engine-status")
    public ResponseEntity<StorageEngineDtos.StorageEngineStatusResponse> storageEngineStatus() {
        var status = storageProbe.status(currentTenantId());
        List<StorageEngineDtos.StorageEngineStatusItem> engines = status.engines().stream()
                .map(e -> new StorageEngineDtos.StorageEngineStatusItem(
                        e.provider(), e.allowed(), e.configured(), e.description()))
                .toList();
        return ResponseEntity.ok(new StorageEngineDtos.StorageEngineStatusResponse(
                engines, status.allowedProviders(), status.minioEnvAvailable()));
    }

    @PostMapping("/engine-check")
    public ResponseEntity<StorageEngineDtos.StorageCheckResponse> storageEngineCheck(
            @RequestBody(required = false) String rawBody) {
        JsonNode body = parseBody(rawBody);
        if (body == null) {
            throw new BizException(AppError.badRequest("请求参数不合法").withDetails("请求体格式不正确"));
        }
        String provider = body.path("provider").asText("");
        if (!storageProbe.isAllowed(provider)) {
            throw new BizException(AppError.forbidden("该存储引擎已被禁用"));
        }
        JsonNode cfg = body.get(provider);
        var outcome = storageProbe.check(provider, configMap(cfg), currentTenantId());
        return ResponseEntity.ok(
                new StorageEngineDtos.StorageCheckResponse(outcome.ok(), outcome.message(), false));
    }

    /** ShouldBindJSON 等价：解析失败（含 EOF）→ null，调用方落各自的 400 文案。 */
    private static JsonNode parseBody(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return null;
        }
        try {
            JsonNode node = JsonMappers.lenient().readTree(rawBody);
            return node == null || !node.isObject() ? null : node;
        } catch (Exception e) {
            return null;
        }
    }

    /** 契约端口只收纯 JDK 值：对象节点转 Map，其余视为缺失。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> configMap(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        Object value = JsonMappers.toJavaValue(node);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private long currentTenantId() {
        Long tid = TenantContext.currentTenantId();
        return tid == null ? 0 : tid;
    }
}
