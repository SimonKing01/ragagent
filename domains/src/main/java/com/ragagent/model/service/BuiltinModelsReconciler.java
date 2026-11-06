package com.ragagent.model.service;

import com.ragagent.contract.model.ModelParameters;
import com.ragagent.model.domain.Model;
import com.ragagent.model.repository.ModelRepository;
import com.ragagent.platform.config.AppEnvLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * 声明式内置模型（config/builtin_models.yaml，env BUILTIN_MODELS_CONFIG 可覆盖）与
 * models 表中由 YAML 托管的行集（managed_by='yaml'）对账。
 *
 * 生命周期契约：
 * - 只写 managed_by='yaml' 行；UI/API/SQL 创建的同名行（managed_by=''）保留不动
 * - 逐条 UPSERT（deleted_at 强制复位 NULL）；运行时覆盖（系统管理员编辑清空 managed_by）保留
 * - 对账清扫：文件里消失且 managed_by='yaml' 的行软删除
 * - is_default 维持 (tenant_id, type) 桶内唯一
 * - 文件缺失/目录/解析失败 → no-op（清扫不跑）
 *
 * 默认不捆绑 builtin_models.yaml → 默认 no-op。
 */
@Component
public class BuiltinModelsReconciler implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BuiltinModelsReconciler.class);
    public static final String MANAGED_BY_YAML = "yaml";
    private static final long DEFAULT_TENANT_ID = 10000;
    private static final Pattern ENV_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    private final ModelRepository modelRepo;

    public BuiltinModelsReconciler(ModelRepository modelRepo) {
        this.modelRepo = modelRepo;
    }

    @Override
    public void run(ApplicationArguments args) {
        String pathEnv = AppEnvLookup.get("BUILTIN_MODELS_CONFIG");
        Path path = Path.of(pathEnv != null && !pathEnv.isEmpty() ? pathEnv : "./config/builtin_models.yaml");
        try {
            if (!Files.isRegularFile(path)) {
                log.info("[builtin-models] config not present at {}; skipping", path);
                return;
            }
            reconcile(path);
        } catch (Exception e) {
            log.warn("[builtin-models] reconcile failed; continuing", e);
        }
    }

    @SuppressWarnings("unchecked")
    private void reconcile(Path path) throws Exception {
        String raw = Files.readString(path);
        String expanded = interpolateEnv(raw);
        Map<String, Object> file = new Yaml().load(expanded);
        List<Map<String, Object>> entries = file == null ? null
                : (List<Map<String, Object>>) file.get("builtin_models");
        if (entries == null) {
            entries = List.of();
        }

        java.util.Set<String> yamlIds = new java.util.HashSet<>();
        int applied = 0;
        for (int i = 0; i < entries.size(); i++) {
            Map<String, Object> e = entries.get(i);
            String id = str(e.get("id"));
            if (id.isEmpty() || id.length() > 64) {
                log.debug("[builtin-models] WARN: entry {} has empty/oversized id; skipping", i);
                continue;
            }
            String type = str(e.get("type"));
            if (!List.of("KnowledgeQA", "Embedding", "Rerank", "VLLM", "ASR").contains(type)) {
                log.warn("[builtin-models] WARN: entry {} ({}) has unknown type \"{}\"; skipping",
                        i, id, type);
                continue;
            }
            // 运行时覆盖保留（含软删除的手工行，查询不过滤 deleted_at）
            Model existing = modelRepo.byIdIncludingDeleted(id);
            if (existing != null && !MANAGED_BY_YAML.equals(str(existing.getManagedBy()))) {
                log.info("[builtin-models] preserving runtime override: id={}", id);
                continue;
            }

            Model m = new Model();
            m.setId(id);
            m.setTenantId(num(e.get("tenant_id")) == 0 ? DEFAULT_TENANT_ID : num(e.get("tenant_id")));
            m.setName(str(e.get("name")));
            m.setType(type);
            m.setSource(str(e.get("source")).isEmpty() ? "remote" : str(e.get("source")));
            m.setDescription(str(e.get("description")));
            m.setParameters(parseParameters(e.get("parameters")));
            m.setIsDefault(bool(e.get("is_default")));
            m.setIsBuiltin(true);
            m.setManagedBy(MANAGED_BY_YAML);
            String status = str(e.get("status"));
            m.setStatus(status.isEmpty() ? "active" : status);
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            m.setCreatedAt(now);
            m.setUpdatedAt(now);

            if (m.isIsDefault()) {
                // 同 (tenant_id, type) 桶内清其他默认（排除自身）
                modelRepo.clearDefaultForBucket(m.getTenantId(), m.getType(), m.getId());
            }
            upsert(m);
            applied++;
            yamlIds.add(id);
            log.info("[builtin-models] upserted: id={} name={} type={}", id, m.getName(), type);
        }

        // 漂移清扫：YAML 托管且不在文件中的行软删除
        int pruned = 0;
        List<Model> managed = modelRepo.listManagedBy(MANAGED_BY_YAML);
        for (Model row : managed) {
            if (!yamlIds.contains(row.getId())) {
                modelRepo.softDeleteById(row.getId(), OffsetDateTime.now(ZoneOffset.UTC));
                pruned++;
            }
        }
        log.info("[builtin-models] applied: {} upserted, {} pruned from {}", applied, pruned, path);
    }

    /**
     * 按 id upsert：先 UPDATE 非 NULL 列，0 行则 INSERT（PK 冲突兜底重试）。
     * 更新列集不含 created_at：MyBatis-Plus 非空字段全量 SET，实体的 createdAt
     * 恒为 now，不携带它才能避免已存在行的 created_at 每次启动被改写。
     */
    private void upsert(Model m) {
        OffsetDateTime createdAt = m.getCreatedAt();
        m.setCreatedAt(null);
        try {
            // MyBatis-Plus 无原生 upsert：先按 id 尝试更新，影响 0 行则插入（PK 冲突兜底）
            // 并发安全由 PK 唯一约束保证：插入冲突时重试更新
            int updated = modelRepo.updateByIdNonNull(m);
            if (updated == 0) {
                m.setCreatedAt(createdAt);
                try {
                    modelRepo.insert(m);
                } catch (org.springframework.dao.DuplicateKeyException ex) {
                    m.setCreatedAt(null);
                    modelRepo.updateByIdNonNull(m);
                }
            }
        } finally {
            m.setCreatedAt(createdAt);
        }
    }

    private static ModelParameters parseParameters(Object raw) {
        ModelParameters p = new ModelParameters();
        if (!(raw instanceof Map<?, ?> map)) {
            return p;
        }
        p.setBaseUrl(str(map.get("baseUrl")));
        p.setApiKey(str(map.get("apiKey")));
        p.setInterfaceType(str(map.get("interfaceType")));
        if (map.get("embeddingParameters") instanceof Map<?, ?> ep) {
            ModelParameters.EmbeddingParameters embedding = new ModelParameters.EmbeddingParameters();
            embedding.setDimension((int) num(ep.get("dimension")));
            embedding.setTruncatePromptTokens((int) num(ep.get("truncatePromptTokens")));
            embedding.setSupportsDimensionOverride(bool(ep.get("supportsDimensionOverride")));
            p.setEmbeddingParameters(embedding);
        }
        p.setParameterSize(str(map.get("parameterSize")));
        p.setProvider(str(map.get("provider")));
        if (map.get("extraConfig") instanceof Map<?, ?> ec) {
            java.util.Map<String, String> extra = new java.util.TreeMap<>();
            ec.forEach((k, v) -> extra.put(String.valueOf(k), str(v)));
            p.setExtraConfig(extra);
        }
        if (map.get("customHeaders") instanceof Map<?, ?> ch) {
            java.util.Map<String, String> headers = new java.util.TreeMap<>();
            ch.forEach((k, v) -> headers.put(String.valueOf(k), str(v)));
            p.setCustomHeaders(headers);
        }
        p.setSupportsVision(bool(map.get("supportsVision")));
        p.setContextWindow((int) num(map.get("contextWindow")));
        p.setMaxOutputTokens((int) num(map.get("maxOutputTokens")));
        p.setMaxConcurrency((int) num(map.get("maxConcurrency")));
        p.setAppId(str(map.get("appId")));
        p.setAppSecret(str(map.get("appSecret")));
        return p;
    }

    /** ${NAME} 用 env 替换，未设置保留字面量 */
    static String interpolateEnv(String s) {
        java.util.regex.Matcher m = ENV_PATTERN.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = AppEnvLookup.get(m.group(1));
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(v != null && !v.isEmpty() ? v : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static long num(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        return 0;
    }

    private static boolean bool(Object v) {
        return Boolean.TRUE.equals(v);
    }

}
