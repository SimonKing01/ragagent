package com.ragagent.system.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.contract.doc.DocReaderAdminPort;
import com.ragagent.contract.doc.ParserEngineCatalogPort;
import com.ragagent.contract.error.AppError;
import com.ragagent.contract.error.BizException;
import com.ragagent.contract.storage.StorageConnectivityPort;
import com.ragagent.contract.tenant.ParserEngineConfig;
import com.ragagent.contract.tenant.TenantConfigLookup;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.platform.security.SsrfGuard;
import com.ragagent.platform.web.ApiResult;
import com.ragagent.platform.web.JsonMappers;
import com.ragagent.system.dto.SystemDtos;
import com.ragagent.system.service.DeploymentCapabilitiesHolder;
import com.ragagent.system.service.SystemInfoService;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/v1/system 组：读端（capabilities/info/parser-engines/storage-engine-status）Viewer+；
 * 探测端（parser-engines/check、docreader/reconnect、storage-engine-check）Admin+——
 * 它们拿租户凭据主动探测远端服务。
 *
 * <p>响应一律为裸资源对象（无 {@code code/data/msg} 信封）；错误走 AppError 信封，
 * docreader 连接失败 → 503、被禁用的存储引擎 → 403。
 * parser/storage 的请求键名保持 snake（与租户配置 jsonb 同形，待解冻后
 * 再 DTO 化）。</p>
 *
 * <p><b>POST /system/sandbox-check 未实现</b>（依赖 sandbox 能力）——
 * Java 侧不映射 → Spring 404。RBAC 与 API-Key 策略表的同名死登记已删除
 * （落地 handler 时一并恢复）。</p>
 */
@RestController
@ApiResult
@RequestMapping("/api/v1/system")
@Tag(name = "System")
public class SystemController {

    private final SystemInfoService infoService;
    private final ParserEngineCatalogPort parserEngines;
    private final TenantConfigLookup tenantConfigLookup;
    private final DocReaderAdminPort docReader;
    private final SsrfGuard ssrfGuard;
    private final DeploymentCapabilitiesHolder capabilitiesHolder;
    private final StorageConnectivityPort storageProbe;

    public SystemController(SystemInfoService infoService,
                            ParserEngineCatalogPort parserEngines,
                            TenantConfigLookup tenantConfigLookup,
                            DocReaderAdminPort docReader,
                            SsrfGuard ssrfGuard,
                            DeploymentCapabilitiesHolder capabilitiesHolder,
                            StorageConnectivityPort storageProbe) {
        this.infoService = infoService;
        this.parserEngines = parserEngines;
        this.tenantConfigLookup = tenantConfigLookup;
        this.docReader = docReader;
        this.ssrfGuard = ssrfGuard;
        this.capabilitiesHolder = capabilitiesHolder;
        this.storageProbe = storageProbe;
    }

    // ── GET /capabilities ─────────────────────────────────────────────────

    /**
     * 启动快照 + 运行时 docker 覆盖
     * （overlayLiveDockerSandboxCapability）。Java 无 docker 后端 → docker 键恒按
     * sandbox=false 推导（route_not_registered）。
     */
    @GetMapping("/capabilities")
    public ResponseEntity<SystemDtos.DeploymentCapabilitiesData> capabilities() {
        return ResponseEntity.ok(capabilitiesHolder.snapshot());
    }


    // ── GET /info ─────────────────────────────────────────────────────────

    @GetMapping("/info")
    public ResponseEntity<SystemDtos.SystemInfoResponse> info() {
        long tenantId = currentTenantId();
        boolean minioEnabled = storageProbe.isMinioConfigured(tenantId);
        String dbVersion = infoService.dbVersion();
        SystemDtos.SystemInfoResponse response = new SystemDtos.SystemInfoResponse(
                infoService.getVersion(),
                infoService.getEdition(),
                infoService.getCommitId(),
                infoService.getBuildTime(),
                infoService.getJavaVersion(),
                infoService.keywordIndexEngine(),
                infoService.vectorStoreEngine(),
                infoService.graphDatabaseEngine(),
                minioEnabled,
                dbVersion,
                null,
                infoService.startedAt(),
                infoService.uptimeSeconds());
        return ResponseEntity.ok(response);
    }

    // ── GET /parser-engines ───────────────────────────────────────────────

    @GetMapping("/parser-engines")
    public ResponseEntity<SystemDtos.ParserEnginesResponse> listParserEngines() {
        return ResponseEntity.ok(engineListBody(engineOverrides()));
    }

    // ── POST /parser-engines/check ────────────────────────────────────────

    /**
     * 请求体 = ParserEngineConfig（未保存的表单值）。
     *
     * <p>请求键名保持 snake：与租户配置 jsonb 同形，待边界解冻后再 DTO 化。</p>
     */
    @PostMapping("/parser-engines/check")
    public ResponseEntity<SystemDtos.ParserEnginesResponse> checkParserEngines(
            @RequestBody(required = false) String rawBody) {
        JsonNode body = parseBody(rawBody);
        if (body == null) {
            throw new BizException(AppError.badRequest("请求参数不合法").withDetails("请求体格式不正确"));
        }
        Map<String, String> overrides = overridesFromBody(body);
        return ResponseEntity.ok(engineListBody(overrides));
    }

    // ── POST /docreader/reconnect ─────────────────────────────────────────

    @PostMapping("/docreader/reconnect")
    public ResponseEntity<SystemDtos.ParserEnginesResponse> reconnectDocReader(
            @RequestBody(required = false) String rawBody) {
        JsonNode body = parseBody(rawBody);
        String addr = body == null || !body.has("addr") ? null : body.path("addr").asText(null);
        if (addr == null) {
            throw new BizException(AppError.badRequest("请提供 addr 参数"));
        }
        addr = addr.trim();
        if (addr.isEmpty()) {
            throw new BizException(AppError.badRequest("addr 不能为空"));
        }
        // SSRF 校验（DocReader 地址）
        try {
            ssrfGuard.validateURLForSSRF(addr);
        } catch (RuntimeException e) {
            throw new BizException(AppError.badRequest(
                    ssrfGuard.formatSSRFError("DocReader 地址", addr, e)));
        }
        try {
            docReader.reconnect(addr);
        } catch (RuntimeException e) {
            // 下游 docreader 不可达：503（错误语义化）
            throw new BizException(AppError.serviceUnavailable("连接失败: " + e.getMessage()));
        }
        List<DocReaderAdminPort.RemoteEngineView> engines = parserEngines.listAll(
                true, engineOverrides(),
                parserEngines.fetchRemote(true, engineOverrides()));
        return ResponseEntity.ok(new SystemDtos.ParserEnginesResponse(
                true, addr, docReader.config().transport(), engines));
    }

    // ── 租户/引擎上下文 ───────────────────────────────────────────────────

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

    private long currentTenantId() {
        Long tid = TenantContext.currentTenantId();
        return tid == null ? 0 : tid;
    }

    /** overrides 组装：租户 ParserEngineConfig → map。 */
    private Map<String, String> engineOverrides() {
        JsonNode parserCfg = JsonMappers.toJsonNode(
                tenantConfigLookup.parserEngineConfig(currentTenantId()));
        return overridesFromBody(parserCfg);
    }

    /** jsonb 节点 → 契约配置的 Map 形态（键集与 snake 键名在 contract 侧）。 */
    private static Map<String, String> overridesFromBody(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return new LinkedHashMap<>();
        }
        Object value = JsonMappers.toJavaValue(node);
        if (!(value instanceof Map<?, ?> map)) {
            return new LinkedHashMap<>();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = (Map<String, Object>) map;
        return ParserEngineConfig.overridesFrom(raw);
    }

    // ── 响应组装辅助 ──────────────────────────────────────────────────────

    /** parser-engines 响应组装（连接态 + docreader 信息 + 引擎清单）。 */
    private SystemDtos.ParserEnginesResponse engineListBody(Map<String, String> overrides) {
        boolean connected = docReader.isConnected();
        List<DocReaderAdminPort.RemoteEngineView> remote =
                parserEngines.fetchRemote(connected, overrides);
        List<DocReaderAdminPort.RemoteEngineView> engines =
                parserEngines.listAll(connected, overrides, remote);
        return new SystemDtos.ParserEnginesResponse(
                connected, docReader.config().addr(), docReader.config().transport(), engines);
    }

}
