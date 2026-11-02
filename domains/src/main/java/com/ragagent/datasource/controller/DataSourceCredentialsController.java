package com.ragagent.datasource.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.contract.error.AppError;
import com.ragagent.contract.error.BizException;
import com.ragagent.contract.knowledge.KnowledgeBaseRowView;
import com.ragagent.datasource.dto.DataSourceOwnership;
import com.ragagent.datasource.dto.DatasourceCredentialsResponse;
import com.ragagent.datasource.service.DataSourceReadService;
import com.ragagent.datasource.service.DataSourceService;
import com.ragagent.datasource.service.KnowledgeBridge;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.platform.web.ApiResponse;
import com.ragagent.platform.web.ApiResult;
import com.ragagent.platform.web.JsonBodyErrors;
import com.ragagent.platform.web.JsonMappers;
import com.ragagent.platform.web.RequestFields;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据源的凭据子资源（{@code PUT /datasource/:id/credentials} 与
 * {@code DELETE /datasource/:id/credentials/:field}）。
 *
 * <h2>为什么只有一个逻辑字段</h2>
 * <p>MCP / Model / WebSearch 的凭据是若干个<b>具名字段</b>，可以逐字段增删；数据源的
 * 凭据却是<b>按连接器而异的原子 map</b>（OAuth token 对、Confluence 的 email+token
 * 组合……）。拆成字段会造出"配了一半、根本认证不了"的中间态，所以这里只有一个
 * {@code "credentials"}：PUT 整张替换、DELETE 整张清空。</p>
 *
 * <h2>错误形态（与 {@link DataSourceController} <b>一致</b>）</h2>
 * <p>两者都走<b>统一响应外壳</b> {@code {code,message,data}}（{@code @ApiResult} + 全局处理器），
 * 见 {@code docs/api/README.md}。</p>
 * <p>两类的错误形态与状态码一致：均抛 {@code BizException}，由全局处理器按
 * {@code @ApiResult} 输出外壳。</p>
 */
@RestController
@ApiResult
@Tag(name = "Datasource")
public class DataSourceCredentialsController {

    private static final Logger log = LoggerFactory.getLogger(DataSourceCredentialsController.class);

    private static final ObjectMapper MAPPER = JsonMappers.lenient()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);


    private final DataSourceService service;
    private final DataSourceReadService readService;
    private final KnowledgeBridge kbBridge;

    public DataSourceCredentialsController(DataSourceService service,
                                          DataSourceReadService readService,
                                          KnowledgeBridge kbBridge) {
        this.service = service;
        this.readService = readService;
        this.kbBridge = kbBridge;
    }

    /**
     * 整体替换凭据并立刻做一次真实连接校验
     * ——用户当场就知道新 token 对不对，不必等下一次定时同步。
     *
     * <p>成功体是<b>裸对象</b> {@code {"fields":{"credentials":{"configured":bool}}}}
     * （不包 data/success 信封）。</p>
     */
    @PutMapping("/api/v1/datasource/{id}/credentials")
    public ResponseEntity<?> put(@PathVariable("id") String id,
                                 @RequestBody(required = false) String rawBody) {
        DataSourceOwnership ds = ownDataSource(id);
        PutRequest req = parsePutBody(rawBody);
        // 字段校验（map 判 null）+ 紧随其后的非空校验
        if (req == null || req.credentials() == null) {
            throw new BizException(AppError.badRequest(requiredFieldMessage()));
        }
        if (req.credentials().isEmpty()) {
            throw new BizException(AppError.badRequest(
                    "credentials map must be non-empty; to remove credentials use "
                            + "DELETE /credentials/credentials"));
        }
        boolean configured;
        try {
            configured = readService.updateCredentials(ds.id(), req.credentials());
        } catch (RuntimeException e) {
            log.error("[datasource] failed to update credentials for data_source_id={}", ds.id(), e);
            throw new BizException(AppError.badRequest(
                    "failed to update credentials: " + e.getMessage()));
        }
        return ResponseEntity.ok(DatasourceCredentialsResponse.credentials(configured));
    }

    /**
     * 只认 {@code "credentials"} 这一个字段名。
     *
     * <p>清空成功回 200 + {@code ApiResponse} 外壳；service 报错落 500（不是 400）
     * ——与 PUT 的映射刻意不同。</p>
     */
    @DeleteMapping("/api/v1/datasource/{id}/credentials/{field}")
    public ApiResponse<Void> deleteField(@PathVariable("id") String id,
                                         @PathVariable("field") String field) {
        DataSourceOwnership ds = ownDataSource(id);
        if (!"credentials".equals(field)) {
            throw new BizException(AppError.badRequest("unknown credential field: " + field));
        }
        try {
            service.clearDataSourceCredentials(ds.id());
        } catch (RuntimeException e) {
            log.error("[datasource] failed to clear credentials for data_source_id={}", ds.id(), e);
            throw new BizException(AppError.internal(
                    "failed to clear credentials: " + e.getMessage()));
        }
        return ApiResponse.ok();   // 204 退役 ⇒ 200 + 外壳
    }

    // ── 归属判定 ──

    /**
     * 归属判定：与 {@code DataSourceController} 那份是<b>同型</b>，
     * 但错误映射不同（见类注释）。
     */
    private DataSourceOwnership ownDataSource(String id) {
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null || tenantId == 0L) {
            throw new BizException(AppError.badRequest("Workspace ID cannot be empty"));
        }
        DataSourceOwnership ds;
        try {
            ds = readService.ownership(id);
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            // 基础设施异常不能伪装成 404：否则前端把"数据库/连接故障"当资源不存在
            throw new BizException(AppError.internal("data source lookup failed"));
        }
        if (ds == null) {
            throw new BizException(AppError.notFound("data source not found"));
        }
        KnowledgeBaseRowView kb = null;
        try {
            kb = kbBridge.findKnowledgeBase(ds.knowledgeBaseId());
        } catch (RuntimeException ignored) {
            // 查询失败按"库不存在"处理
        }
        // 必须用 Objects.equals：Long 装箱值超出缓存区间时 `!=` 比引用。
        if (kb == null || !java.util.Objects.equals(kb.getTenantId(), tenantId)) {
            throw new BizException(AppError.notFound("data source not found"));
        }
        return ds;
    }

    // ── 请求体 ──

    /**
     * 解析 PUT 的请求体。
     *
     * <p>三态：空 body → {@code "No content to map due to end-of-input"}；JSON 语法错误 → 回解析器原文；
     * JSON 合法但字段缺失 → {@code null}，由调用方换成校验文案。</p>
     */
    private static PutRequest parsePutBody(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            throw new BizException(AppError.badRequest(JsonBodyErrors.EMPTY_BODY));
        }
        try {
            return MAPPER.readValue(rawBody, PutRequest.class);
        } catch (JsonProcessingException e) {
            throw new BizException(AppError.badRequest(e.getMessage()));
        }
    }

    /**
     * 请求体缺 {@code credentials} 字段时的校验失败文案（保持线上原文）。
     *
     * <p>它<b>会出现在线上</b>——契约测试逐字节比对时不能整条掩码掉。</p>
     */
    static String requiredFieldMessage() {
        return RequestFields.message("Credentials", "required");
    }

    /** PUT 请求体。 */
    record PutRequest(Map<String, Object> credentials) {
    }
}
