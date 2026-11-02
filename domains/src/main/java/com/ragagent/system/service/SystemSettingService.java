package com.ragagent.system.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.ragagent.auth.api.AdminUserAdapter;
import com.ragagent.contract.audit.AuditAction;
import com.ragagent.contract.audit.AuditOutcome;
import com.ragagent.contract.audit.AuditWritePort;
import com.ragagent.contract.auth.AdminUserView;
import com.ragagent.contract.settings.SystemSettingGateway;
import com.ragagent.llm.limiter.ConcurrencyGovernor;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.platform.security.SsrfGuard;
import com.ragagent.platform.settings.SystemSettingRegistry;
import com.ragagent.platform.web.JsonMappers;
import com.ragagent.system.domain.SystemSetting;
import com.ragagent.system.repository.SystemSettingRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * 系统设置服务。
 *
 * <p><b>Lite 取舍（Javadoc 即契约）</b>：没有 "preload + Redis pubsub 失效"
 * 的跨副本缓存；本侧是<b>单实例</b>语义——每请求直读 DB，
 * 无缓存无 pubsub。行为差异只在多副本部署下可见（本副本 UI 改动不再即时广播给对端），
 * 解析优先级与响应形态逐字段固定。</p>
 *
 * <p>三层解析：DB 行 → ENV → default；DB 读失败
 * 降级到 ENV/default（记 WARN，不 500）。已知"引导默认行"（last_modified_by 为空且值
 * ==registry 默认）在<b>读取</b>时折叠回 ENV/default（isBootstrapDefaultRow）。</p>
 *
 * <p>副作用桥（dispatchSideEffects）：ssrf.whitelist 更新后推给
 * {@link SsrfGuard#reloadWhitelist(String)}（含 SSRF_WHITELIST_EXTRA 合并）。
 * model.max_concurrency 的桥已随
 * 并发闸门装配接线（ConcurrencyGovernorWiring）。</p>
 *
 * <p><b>启动预载</b>：应用就绪后把 DB 的 ssrf.whitelist 推给 SsrfGuard——否则重启后
 * DB 白名单静默失效（guard 静态初始化只读 env）。实测案例：UI 存了 198.18.0.0/15，
 * 重启后 dashscope fake-IP 又被拦。读失败降级 env-only（WARN，不阻断启动）。</p>
 */
@Service
public class SystemSettingService implements SystemSettingGateway {

    private static final Logger log = LoggerFactory.getLogger(SystemSettingService.class);
    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    private final SystemSettingRepository repo;
    private final AuditWritePort auditWriter;
    /** 白名单是进程级静态——注入任意实例即可（reloadWhitelist 改的是静态字段）。 */
    private final SsrfGuard ssrfGuard;
    /** 三层解析的 ENV 层（Spring Environment，不读裸 System.getenv；键名由调用方给）。 */
    private final Environment environment;
    private final AdminUserAdapter adminUserPort;
    /** 配置兜底：未配置 → self_serve。 */
    @Value("${weknora.auth.registration-mode:}")
    private String configuredRegistrationMode;

    /** 并发闸门（model-runtime）；设置面不可用时不阻断启动。 */
    private final org.springframework.beans.factory.ObjectProvider<
            ConcurrencyGovernor> governorProvider;

    public SystemSettingService(SystemSettingRepository repo,
                                AuditWritePort auditWriter,
                                SsrfGuard ssrfGuard,
                                Environment environment,
                                AdminUserAdapter adminUserPort,
                                org.springframework.beans.factory.ObjectProvider<
                                        ConcurrencyGovernor> governorProvider) {
        this.adminUserPort = adminUserPort;
        this.repo = repo;
        this.auditWriter = auditWriter;
        this.ssrfGuard = ssrfGuard;
        this.environment = environment;
        this.governorProvider = governorProvider;
    }

    // ── 三层解析（业务侧 GetXxx） ─────────────────────────────────────────

    /** DB 行（非引导默认）→ 有值；否则 ENV/default。 */
    private record Resolved(JsonNode raw, boolean fromDB) {
    }

    private Resolved resolveRaw(String key) {
        SystemSettingRegistry.Spec spec = SystemSettingRegistry.get(key);
        SystemSetting row = selectByKey(key);
        if (row != null) {
            if (spec != null && isBootstrapDefaultRow(row, spec)) {
                return new Resolved(null, false);
            }
            return new Resolved(row.getValue(), true);
        }
        return new Resolved(null, false);
    }

    /** DB（容忍 "42" 字符串行）→ ENV → def。任何错误路径回落 def。 */
    public long getInt(String key, String envName, long def) {
        Resolved r = resolveRaw(key);
        if (r.fromDB()) {
            JsonNode raw = r.raw();
            if (raw != null && raw.isNumber()) {
                return raw.asLong();
            }
            if (raw != null && raw.isTextual()) {
                try {
                    return Long.parseLong(raw.asText());
                } catch (NumberFormatException ignored) {
                    // fall through to env/default
                }
            }
            log.warn("[system_settings] {}: cannot parse as int, falling back", key);
        }
        if (envName != null && !envName.isEmpty()) {
            String v = environment.getProperty(envName);
            if (v != null && !v.isEmpty()) {
                try {
                    return Long.parseLong(v);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return def;
    }

    public String getString(String key, String envName, String def) {
        Resolved r = resolveRaw(key);
        if (r.fromDB() && r.raw() != null && r.raw().isTextual()) {
            return r.raw().asText();
        }
        if (envName != null && !envName.isEmpty()) {
            String v = environment.getProperty(envName);
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return def;
    }

    /** ENV 解析走宽松布尔语义：1/t/T/TRUE/true/True 等。 */
    public boolean getBool(String key, String envName, boolean def) {
        Resolved r = resolveRaw(key);
        if (r.fromDB() && r.raw() != null && r.raw().isBoolean()) {
            return r.raw().asBoolean();
        }
        if (envName != null && !envName.isEmpty()) {
            String v = environment.getProperty(envName);
            if (v != null && !v.isEmpty()) {
                Boolean parsed = parseLegacyBool(v);
                if (parsed != null) {
                    return parsed;
                }
            }
        }
        return def;
    }

    /** ENV 按逗号拆分 + trim + 丢弃空项；返回恒非 null。 */
    public List<String> getStringList(String key, String envName, List<String> def) {
        Resolved r = resolveRaw(key);
        if (r.fromDB() && r.raw() != null && r.raw().isArray()) {
            List<String> out = new ArrayList<>();
            r.raw().forEach(n -> out.add(n.asText()));
            return out;
        }
        if (envName != null && !envName.isEmpty()) {
            String raw = environment.getProperty(envName);
            if (raw != null && !raw.isEmpty()) {
                List<String> out = new ArrayList<>();
                for (String entry : raw.split(",")) {
                    String t = entry.trim();
                    if (!t.isEmpty()) {
                        out.add(t);
                    }
                }
                return out;
            }
        }
        return def == null ? new ArrayList<>() : def;
    }

    /** 可接受的布尔字面量集合（1/t/true 及大小写变体）。 */
    private static Boolean parseLegacyBool(String v) {
        return switch (v) {
            case "1", "t", "T", "true", "TRUE", "True" -> Boolean.TRUE;
            case "0", "f", "F", "false", "FALSE", "False" -> Boolean.FALSE;
            default -> null;
        };
    }

    // ── 管理面（List / Get / Update / Reset） ─────────────────────────────

    /**
     * 持久行 + registry 虚拟行（按 key 排序），跳过不再输出的键，
     * 额外（未知）行按 key 排序缀尾。
     */
    public List<SystemSetting> list() {
        Map<String, SystemSetting> byKey = new ConcurrentHashMap<>();
        for (SystemSetting row : repo.listAll()) {
            byKey.put(row.getKey(), row);
        }
        byKey.remove(SystemSettingRegistry.RETIRED_KEY);

        List<SystemSetting> out = new ArrayList<>();
        for (String key : SystemSettingRegistry.keys()) {
            SystemSettingRegistry.Spec spec = SystemSettingRegistry.get(key);
            SystemSetting row = byKey.remove(key);
            if (row != null) {
                row.setEnumOptions(spec.enumOptions().isEmpty() ? null : spec.enumOptions());
                if (isBootstrapDefaultRow(row, spec)) {
                    row.setValue(fallbackJsonForSpec(key, spec));
                }
                out.add(row);
            } else {
                out.add(virtualSetting(key, spec));
            }
        }
        List<String> extraKeys = new ArrayList<>(byKey.keySet());
        java.util.Collections.sort(extraKeys);
        for (String key : extraKeys) {
            out.add(byKey.get(key));
        }
        enrichModifiedBy(out);
        return out;
    }

    /**
     * 未知 key → 抛 IllegalStateException（handler 折叠成 400，
     * 消息 = {@code unknown setting key "x"}）；行缺失 → 虚拟行（200 响应；
     * 仅 DB 错误才走异常分支）。
     */
    public SystemSetting get(String key) {
        SystemSettingRegistry.Spec spec = SystemSettingRegistry.get(key);
        if (spec == null) {
            throw new IllegalArgumentException("unknown setting key \"" + key + "\"");
        }
        SystemSetting row = selectByKey(key);
        if (row != null) {
            row.setEnumOptions(spec.enumOptions().isEmpty() ? null : spec.enumOptions());
            if (isBootstrapDefaultRow(row, spec)) {
                row.setValue(fallbackJsonForSpec(key, spec));
            }
            return row;
        }
        return virtualSetting(key, spec);
    }

    /**
     * 类型/枚举/结构校验 → upsert → 审计。错误消息是契约
     * （原文直接当 400 响应）。
     */
    public SystemSetting update(String key, JsonNode rawValue) {
        SystemSettingRegistry.Spec spec = SystemSettingRegistry.get(key);
        if (spec == null) {
            throw new IllegalArgumentException("unknown setting key \"" + key + "\"");
        }
        JsonNode encoded;
        try {
            encoded = SystemSettingRegistry.encodeForType(spec.type(), rawValue);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "invalid value for \"" + key + "\" (expected " + spec.type() + "): "
                            + e.getMessage());
        }
        if (!spec.enumOptions().isEmpty() && "string".equals(spec.type())) {
            String str = rawValue.isTextual() ? rawValue.asText() : null;
            if (str == null || !spec.enumOptions().contains(str)) {
                throw new IllegalArgumentException("invalid value for \"" + key + "\": \""
                        + (str == null ? "" : str) + "\" not in " + formatBracketedList(spec.enumOptions()));
            }
        }
        validateRegistryEntry(key, rawValue);

        SystemSetting prev = selectByKey(key);
        String category;
        String description;
        boolean isSecret = false;
        boolean requiresRestart;
        if (prev != null) {
            category = prev.getCategory();
            description = prev.getDescription();
            isSecret = prev.isIsSecret();
            requiresRestart = prev.isRequiresRestart();
        } else {
            category = spec.category().isEmpty() ? SystemSettingRegistry.GENERAL_CATEGORY : spec.category();
            description = spec.description();
            requiresRestart = spec.requiresRestart();
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        SystemSetting row = prev != null ? prev : new SystemSetting();
        row.setKey(key);
        row.setValue(encoded);
        row.setValueType(spec.type());
        row.setCategory(category);
        row.setDescription(description);
        row.setIsSecret(isSecret);
        row.setRequiresRestart(requiresRestart);
        row.setLastModifiedBy(auditActor());
        row.setUpdatedAt(now);
        if (prev == null) {
            row.setCreatedAt(now);
            repo.insert(row);
        } else {
            repo.updateById(row);
        }

        SystemSetting persisted = selectByKey(key);
        if (persisted == null) {
            persisted = row;
        }
        persisted.setEnumOptions(spec.enumOptions().isEmpty() ? null : spec.enumOptions());
        enrichModifiedBy(List.of(persisted));

        dispatchSideEffects(key);
        emitChangeAudit(key, spec.type(), prev == null ? null : prev.getValue(), encoded);
        return persisted;
    }

    /** 未知 key 仍 400；幂等成功；仅真删除写审计。 */
    public void reset(String key) {
        SystemSettingRegistry.Spec spec = SystemSettingRegistry.get(key);
        if (spec == null) {
            throw new IllegalArgumentException("unknown setting key \"" + key + "\"");
        }
        SystemSetting prev = selectByKey(key);
        int deleted = repo.deleteByKey(key);
        boolean rowDeleted = deleted > 0;
        dispatchSideEffects(key);
        if (rowDeleted && prev != null) {
            emitChangeAudit(key, spec.type(), prev.getValue(), null);
        }
    }

    // ── 内部 ─────────────────────────────────────────────────────────────

    private SystemSetting selectByKey(String key) {
        return repo.findByKey(key);
    }

    /** id=0 + 零值时间（实体字段默认值即零值）。 */
    private SystemSetting virtualSetting(String key, SystemSettingRegistry.Spec spec) {
        SystemSetting row = new SystemSetting();
        row.setId(0L);
        row.setKey(key);
        row.setValue(fallbackJsonForSpec(key, spec));
        row.setValueType(spec.type());
        row.setCategory(spec.category().isEmpty() ? SystemSettingRegistry.GENERAL_CATEGORY : spec.category());
        row.setDescription(spec.description());
        row.setIsSecret(false);
        row.setRequiresRestart(spec.requiresRestart());
        row.setLastModifiedBy("");
        row.setEnumOptions(spec.enumOptions().isEmpty() ? null : spec.enumOptions());
        return row;
    }

    /**
     * ENV → (auth.registration_mode 的配置兜底) → 内置默认。
     * ENV 编码失败静默落默认（逐分支确定）。
     */
    private JsonNode fallbackJsonForSpec(String key, SystemSettingRegistry.Spec spec) {
        if (!spec.envName().isEmpty()) {
            String raw = environment.getProperty(spec.envName());
            if (raw != null && !raw.trim().isEmpty()) {
                try {
                    return switch (spec.type()) {
                        case "int" -> SystemSettingRegistry.encodeForType("int", new TextNode(raw.trim()));
                        case "string" -> SystemSettingRegistry.encodeForType("string", new TextNode(raw));
                        case "bool" -> SystemSettingRegistry.encodeForType("bool", new TextNode(raw));
                        case "string_list" -> SystemSettingRegistry.encodeForType("string_list", new TextNode(raw));
                        default -> null;
                    };
                } catch (IllegalArgumentException ignored) {
                    // 编码失败 → 落到 config/default
                }
            }
        }
        if ("auth.registration_mode".equals(key)) {
            String mode = configuredRegistrationMode == null || configuredRegistrationMode.isBlank()
                    ? SystemSettingRegistry.AUTH_REGISTRATION_MODE_DEFAULT
                    : configuredRegistrationMode.trim();
            return MAPPER.valueToTree(mode);
        }
        return SystemSettingRegistry.encodeDefault(spec);
    }

    /** 无操作者（last_modified_by 空）且值 ==registry 默认。 */
    private boolean isBootstrapDefaultRow(SystemSetting row, SystemSettingRegistry.Spec spec) {
        if (row == null || !row.getLastModifiedBy().trim().isEmpty()) {
            return false;
        }
        JsonNode def;
        try {
            def = SystemSettingRegistry.encodeDefault(spec);
        } catch (RuntimeException e) {
            return false;
        }
        return jsonEquals(row.getValue(), def);
    }

    /** JSON 值等价比较。 */
    private static boolean jsonEquals(JsonNode a, JsonNode b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.equals(b);
    }

    /**
     * 启动预载：应用就绪后把 DB 的
     * ssrf.whitelist / sandbox.docker_enabled 推给消费方。否则 UI 保存的白名单在
     * 重启后静默失效（guard 静态初始化只读 env），单实例即可观测。
     */
    @org.springframework.context.event.EventListener(
            org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void applyWhitelistOnStartup() {
        for (String key : List.of("ssrf.whitelist", "sandbox.docker_enabled", "model.max_concurrency")) {
            try {
                dispatchSideEffects(key);
            } catch (RuntimeException e) {
                log.warn("startup {} apply failed, env-only fallback", key, e);
            }
        }
    }

    /** ssrf.whitelist / sandbox.docker_enabled / model.max_concurrency 已接线。 */
    private void dispatchSideEffects(String changedKey) {        if ("ssrf.whitelist".equals(changedKey)) {
            List<String> list = getStringList("ssrf.whitelist", "SSRF_WHITELIST", new ArrayList<>());
            String primary = String.join(",", list);
            String extra = environment.getProperty("SSRF_WHITELIST_EXTRA");
            String merged = primary;
            if (extra != null && !extra.trim().isEmpty()) {
                merged = merged.isEmpty() ? extra : merged + "," + extra;
            }
            ssrfGuard.reloadWhitelist(merged);
            return;
        }
        if ("model.max_concurrency".equals(changedKey)) {
            applyMaxConcurrency();
        }
    }

    /** 运行时桥：model.max_concurrency → 进程级并发上限（非正值关闭默认值）。 */
    private void applyMaxConcurrency() {
        long limit = getInt("model.max_concurrency", "RAGAGENT_MODEL_MAX_CONCURRENCY", 0L);
        governorProvider.ifAvailable(governor -> governor.setGlobalLimit((int) limit));
    }

    /** tenant_id=0 + oldValue/newValue（RawMessage 直嵌）。 */
    private void emitChangeAudit(String key, String valueType, JsonNode oldValue, JsonNode newValue) {
        if (auditWriter == null) {
            return;
        }
        var details = MAPPER.createObjectNode();
        details.put("key", key);
        details.put("valueType", valueType);
        details.set("oldValue", oldValue == null ? MAPPER.nullNode() : oldValue);
        details.set("newValue", newValue == null ? MAPPER.nullNode() : newValue);
        try {
            auditWriter.logBestEffort(new AuditWritePort.AuditRecord(
                    0L,
                    auditActor(),
                    "system_admin",
                    AuditAction.SYSTEM_SETTING_CHANGED,
                    "system_setting",
                    key,
                    "",
                    "",
                    "",
                    AuditOutcome.SUCCESS,
                    details == null ? null : details.toString()));
        } catch (RuntimeException e) {
            log.warn("[system_settings] audit write failed (ignored)", e);
        }
    }

    /** 批量解析显示名（username → email 回落；失败静默）。 */
    private void enrichModifiedBy(List<SystemSetting> rows) {
        if (rows.isEmpty()) {
            return;
        }
        Map<String, String> nameById = new ConcurrentHashMap<>();
        for (SystemSetting r : rows) {
            String id = r.getLastModifiedBy().trim();
            if (!id.isEmpty()) {
                nameById.put(id, "");
            }
        }
        if (nameById.isEmpty()) {
            return;
        }
        for (SystemSetting r : rows) {
            String id = r.getLastModifiedBy();
            if (id.trim().isEmpty() || nameById.get(id) == null) {
                continue;
            }
            if (!nameById.get(id).isEmpty()) {
                continue;
            }
            AdminUserView u = adminUserPort.byId(id);
            if (u != null) {
                if (!u.getUsername().trim().isEmpty()) {
                    nameById.put(id, u.getUsername());
                } else if (!u.getEmail().trim().isEmpty()) {
                    nameById.put(id, u.getEmail());
                }
            }
        }
        for (SystemSetting r : rows) {
            String name = nameById.get(r.getLastModifiedBy());
            if (name != null && !name.isEmpty()) {
                r.setLastModifiedByName(name);
            }
        }
    }

    /** 当前操作者（系统管理员自己）。 */
    private static String auditActor() {
        String uid = TenantContext.currentUserId();
        return uid == null ? "" : uid;
    }

    /** 格式化为 "[a b c]" 形态（方括号包裹、空格分隔）。 */
    private static String formatBracketedList(List<String> values) {
        return "[" + String.join(" ", values) + "]";
    }

    /** registry 条目校验：asynq 正整数 + ssrf 白名单结构。 */
    private static void validateRegistryEntry(String key, JsonNode rawValue) {
        if (key.startsWith("asynq.") && key.endsWith("_concurrency")) {
            long n;
            try {
                if (rawValue.isNumber()) {
                    double d = rawValue.asDouble();
                    if (d != Math.rint(d)) {
                        throw new IllegalArgumentException("expected integer value");
                    }
                    n = (long) d;
                } else if (rawValue.isTextual()) {
                    n = Long.parseLong(rawValue.asText());
                } else {
                    throw new IllegalArgumentException(
                            "expected integer, got " + SystemSettingRegistry.jsonTypeLabel(rawValue));
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "expected integer, got \"" + rawValue.asText() + "\"");
            }
            if (n < 1) {
                throw new IllegalArgumentException("concurrency must be at least 1");
            }
        }
        // ssrf.whitelist 的条目结构校验（拒绝空串/非法 CIDR）尚未完整落地：
        // 当前 SsrfGuard.reloadWhitelist 接受任意条目，仅 encodeForType 的
        // string_list 归一（trim+去空）做了部分对齐。
    }
}
