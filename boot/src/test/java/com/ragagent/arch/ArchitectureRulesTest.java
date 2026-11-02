package com.ragagent.arch;




import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.ragagent.RagAgentApplication;
import com.ragagent.contract.storage.StorageEnvLookup;
import com.ragagent.contract.wiki.WikiLanguageSupport;
import com.ragagent.platform.config.AppEnvLookup;
import com.ragagent.platform.security.CryptoService;
import com.ragagent.platform.security.SsrfGuard;
import com.ragagent.platform.storage.config.StorageRuntimeEnv;
import com.ragagent.platform.storage.config.UploadLimits;
import com.ragagent.retrieval.config.RetrievalEnvLookup;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

/**
 * 全仓架构规则（把几轮重构攒下的约定固化成 CI 红条）。
 *
 * <p><b>分工</b>：包级结构（包间环 / 分层 / 域依赖 {@code config}）由
 * {@code scripts/check-package-cycles.py}（CI 的 guards job，带基线棘轮）守；本类守
 * <b>代码级</b>规则——脚本表达不了、而这几轮真实踩过坑的：</p>
 *
 * <ol>
 *   <li><b>裸读环境变量</b>：裸 {@code System.getenv} 已从 149 处全仓清零，四类落点
 *       （{@code @ConfigurationProperties} / 域查找面 / 启动期值快照 / 全局查找面 + 启动钩子）
 *       已定型；本规则防止回流。</li>
 *   <li><b>{@code @ConfigurationProperties} 类必须被 {@code @ConfigurationPropertiesScan}
 *       名单覆盖</b>：漏一处的表现是<b>静默</b>的——不报错，只是值永远是默认值
 *       （反复踩过：新属性类放进未扫描的包）。</li>
 *   <li><b>配置类不得同时是 {@code @Component}/{@code @Service}</b>：属性绑定 + 组件扫描
 *       双装配，语义含糊。</li>
 *   <li><b>{@code install*} 只许装配层调用</b>：查找面/快照类的 {@code install} 是启动期
 *       一次性写入，运行期调用即「把配置当状态改」（各 holder 注释均写明此约束）。</li>
 *   <li><b>禁 {@code @Lazy} 注入</b>（A6）：循环依赖要拆（下沉/接口反转），不许懒加载
 *       掩盖设计缺陷；knowledge 域门面环在棘轮基线，解环专项落地后清空。</li>
 *   <li><b>裸 JDBC 白名单</b>（A7）：业务单表 CRUD 走 MyBatis-Plus；方言探测 / PG 专有
 *       SQL / 非业务库引擎 / 启动修复四类 MP 能力边界场景登记放行，新类须在 PR 论证。</li>
 *   <li><b>禁字符串列名 wrapper</b>（A8）：条件构造器一律 Lambda 方法引用，硬编码列名
 *       无编译期保护；存量在基线，随逐域 Lambda 化清空。</li>
 *   <li><b>{@code .last(} 只许纯字符串字面量</b>（A9）：拼接既是注入面也是方言漂移点；
 *       动态行数走分页插件或注解 SQL {@code #{}} 参数化。</li>
 *   <li><b>分层倒挂</b>（A10）：{@code service} 不得依赖 {@code controller}（方向必须
 *       controller → service → repository/mapper），{@code domain} 不得依赖
 *       {@code service}/{@code controller}（领域模型保持纯净）。</li>
 *   <li><b>Mapper 接口包约定</b>（A11）：命名以 {@code Mapper} 结尾的<b>顶层</b>接口必须落在
 *       {@code ..mapper..} 包——{@code @MapperScan("com.ragagent.**.mapper")} 只扫这些包，
 *       放错包的表现是启动期缺 Bean 或该 mapper <b>静默不注册</b>。</li>
 * </ol>
 *
 * <p>新加规则请只加<b>当前零违例</b>的规则，否则等于把存量违例变成噪声；确有存量违例要
 * 棘轮化的，走 A6-A9 的代码内基线模式——Set/Map 逐条登记（附理由）+ {@link #ratchet}
 * 双断言：基线外新增违例即红（拦增量），基线条目不再违例也红（防规则空转，强制随清理
 * 收紧）。不引入 FreezingArchRule 的存储文件；包级棘轮仍在脚本里。</p>
 *
 * <p><b>零基线规则</b>：A6 / A9 / A11b / A16 / A17 的基线已是空集——含义与"有基线的棘轮"
 * 不同：不是"允许存量、只拦增量"，而是<b>新增一行即红</b>。改动 service 层时按三条路选落点：
 * 持久化走 {@code repository} 门面、外部能力走自有端口、适配器/装配分别归 {@code api}/
 * {@code config}。落点还会同时受 C10（控制器不 import {@code ..domain..}）与包规模红线
 * 约束，选之前先看这两条，能少试两轮。</p>
 */
class ArchitectureRulesTest {

    /**
     * 全仓<b>主源集</b>（含所有域）。
     *
     * <p>必须按输出目录过滤：{@code importPackages("com.ragagent")} 会把 src/test 的类一起扫进来，
     * 而测试里读真实环境变量是<b>合法</b>的（例如各 connector 桩要读宿主 env 拼 SSRF 白名单），
     * 那些不是本规则的治理对象。</p>
     */
    private static final JavaClasses MAIN = new ClassFileImporter()
            // 多模块布局：**不能用 location.asURI()** —— ArchUnit 对 jar 内的类求 asURI
            // 会抛异常，而"抛异常的导入选项"被当作**排除**，于是 :common（在 :boot 的
            // 类路径上以 jar 形态出现）被整段排除，A7 基线条目随即报"已不再违例"（实测踩到，
            // 探针四变体定位：asURI 版命中 0、Location.contains 版命中 1）。
            // 改用 Location.contains 排除测试类。
            // 测试夹具也是测试基建（EmbeddedRedis / TestSchema / 金片加载器），不属于 A1 的治理对象。
            // 四种形态都要排：Gradle 的 classes/java/test 与 <模块>-test-fixtures.jar；
            // Maven 的 target/test-classes（reactor 内直接引目录）与 <模块>-tests.jar。
            .withImportOption(location -> !location.contains("/classes/java/test/")
                    && !location.contains("/target/test-classes/")
                    && !location.contains("-test-fixtures.jar")
                    && !location.contains("-tests.jar"))
            .importPackages("com.ragagent");

    /**
     * 启动期一次性写入的查找面/快照（各类注释：只允许装配层调用 install）。
     *
     * <p>键是 {@code 属主全名#方法名}——<b>刻意不用 {@code JavaMethod.getFullName()}</b>：
     * 后者带参数类型（{@code ...install(java.util.function.Function)}），按它建集合会让规则
     * <b>永远绿</b>（实测：探针类照样通过，属"空转规则"）。</p>
     */
    private static final Set<String> INSTALL_TARGETS = Set.of(
            WikiLanguageSupport.class.getName() + "#installLanguage",
            UploadLimits.class.getName() + "#installFileSizeMb",
            CryptoService.class.getName() + "#installAesKey",
            SsrfGuard.class.getName() + "#installWhitelist",
            StorageRuntimeEnv.class.getName() + "#install",
            StorageEnvLookup.class.getName() + "#install",
            RetrievalEnvLookup.class.getName() + "#install",
            AppEnvLookup.class.getName() + "#install");

    // ── A1 禁裸读环境变量 ──────────────────────────────────────────────────

    @Test
    @DisplayName("A1：主代码不得调用 System.getenv（走四类已登记落点）")
    void noRawEnvReads() {
        // 过滤失败会静默「零类可查」→ 规则永远绿；先自证导入面正常
        assertThat(MAIN.size()).as("主源集导入为空或过少：检查 ImportOption 的输出目录过滤").isGreaterThan(500);
        noClasses()
                .should().callMethod(System.class, "getenv", String.class)
                .because("裸 getenv 绕过 @ConfigurationProperties/查找面：既不能被属性源与命令行覆盖，也"
                        + "无法在测试里注入。改用：@ConfigurationProperties 注入 / 域查找面（*EnvLookup）/ "
                        + "启动期值快照 / 全局 AppEnvLookup（启动钩子装配）")
                .check(MAIN);
        noClasses()
                .should().callMethod(System.class, "getenv")
                .because("同上（无参重载）")
                .check(MAIN);
    }

    // ── A2/A3 属性类必须被扫描覆盖、且不双装配 ──────────────────────────────

    @Test
    @DisplayName("A2：@ConfigurationProperties 类必须落在 @ConfigurationPropertiesScan 名单覆盖的包内")
    void propertiesClassesAreScanned() {
        Set<String> scanned = scannedPackages();
        assertThat(scanned).as("RagAgentApplication 的 @ConfigurationPropertiesScan 名单不应为空").isNotEmpty();

        List<String> missing = new ArrayList<>();
        for (JavaClass clazz : MAIN) {
            if (!clazz.isAnnotatedWith(ConfigurationProperties.class)) {
                continue;
            }
            String pkg = clazz.getPackageName();
            boolean covered = scanned.stream().anyMatch(s -> pkg.equals(s) || pkg.startsWith(s + "."));
            if (!covered) {
                missing.add(clazz.getFullName() + "（包 " + pkg + "）");
            }
        }
        assertThat(missing)
                .as("属性类漏扫描的后果是静默失效（值永远取默认）；把所在包加进 RagAgentApplication "
                        + "的 @ConfigurationPropertiesScan 名单")
                .isEmpty();
    }

    @Test
    @DisplayName("A3：@ConfigurationProperties 类不得同时是 @Component/@Service（避免双装配）")
    void propertiesClassesAreNotComponents() {
        noClasses()
                .that().areAnnotatedWith(ConfigurationProperties.class)
                .should().beAnnotatedWith(Component.class)
                .orShould().beAnnotatedWith(Service.class)
                .because("属性绑定已由 @ConfigurationPropertiesScan 负责，再加组件注解属双装配")
                .check(MAIN);
    }

    // ── A4 install* 只许装配层调用 ─────────────────────────────────────────

    @Test
    @DisplayName("A4：install*（启动期写入查找面/快照）只许装配层（*.config 包）调用")
    void installOnlyFromWiring() {
        // 注意：这里必须是 classes().should(customCondition)，不能用 noClasses().should(...)
        // ——后者会把条件取反，自定义条件里手写的 violation 会被反转成通过（实测踩坑：
        // 该规则曾一度「永远绿」，靠探针类才暴露）。
        classes()
                .should(new ArchCondition<>("调用启动期 install* 且不在装配层") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        if (isWiringPackage(item.getPackageName())) {
                            return;
                        }
                        item.getMethodCallsFromSelf().stream()
                                .filter(call -> INSTALL_TARGETS.contains(call.getTarget().getOwner().getFullName()
                                        + "#" + call.getTarget().getName()))
                                .forEach(call -> events.add(SimpleConditionEvent.violated(item,
                                        item.getFullName() + " 调用了启动期写入 "
                                                + call.getTarget().getFullName()
                                                + "——只允许 *.config 装配层调用；运行期改配置属状态变更，"
                                                + "应另行设计（见各 holder 的类注释）")));
                    }
                })
                .check(MAIN);
    }

    // ── A5 源码不得含裸 NUL 字节 ────────────────────────────────────────────

    @Test
    @DisplayName("A5：源文件不得含裸 NUL 字节（会让 grep/ripgrep 判为二进制并静默跳过该文件）")
    void noRawNulBytesInSources() throws java.io.IOException {
        java.util.List<String> offenders = new java.util.ArrayList<>();
        for (java.nio.file.Path root : java.util.stream.Stream.of(
                backendSourceRoots("main"), backendSourceRoots("test"))
                .flatMap(java.util.List::stream).toList()) {
            if (!java.nio.file.Files.isDirectory(root)) {
                continue;
            }
            try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(root)) {
                for (java.nio.file.Path file : walk.filter(java.nio.file.Files::isRegularFile)
                        .filter(f -> f.toString().endsWith(".java")).toList()) {
                    byte[] raw = java.nio.file.Files.readAllBytes(file);
                    for (byte b : raw) {
                        if (b == 0) {
                            offenders.add(file.toString());
                            break;
                        }
                    }
                }
            }
        }
        assertThat(offenders)
                .as("裸 NUL（常见于把 \\0 哨兵直接写成字节）会让文本工具跳过整个文件，"
                        + "审计因此静默漏文件；改写为 Java 的 \\0 八进制转义即可（实测）")
                .isEmpty();
    }

    // ── A6 禁 @Lazy 注入 ────────────────────────────────────────────────────

    /**
     * 棘轮基线：已清空（2026-10-05 解环专项落地）。拆法：
     * 门面 helper 下沉 {@code KnowledgeAccessHelper}（子服务不再回注门面）；
     * enqueue 端口外提为 {@code KnowledgeProcessingQueue}（提交方不再依赖 worker 类型）；
     * worker 的摘要触发改同步领域事件 {@code KnowledgeProcessedEvent}（订阅方
     * KnowledgeSummaryService）。本规则现为零基线纯禁令。
     */
    private static final Set<String> LAZY_BASELINE = Set.of();

    @Test
    @DisplayName("A6：构造器参数/字段不得标 @Lazy（循环依赖要拆，不许懒加载掩盖）")
    void noLazyInjection() {
        ratchet("A6 @Lazy", LAZY_BASELINE, violatingClasses(ArchitectureRulesTest::hasLazyInjectionPoint));
    }

    private static boolean hasLazyInjectionPoint(JavaClass clazz) {
        for (JavaConstructor ctor : clazz.getConstructors()) {
            for (var annos : ctor.getParameterAnnotations()) {
                for (JavaAnnotation<?> anno : annos) {
                    if (anno.getRawType().getName().equals(Lazy.class.getName())) {
                        return true;
                    }
                }
            }
        }
        for (var field : clazz.getFields()) {
            if (field.isAnnotatedWith(Lazy.class)) {
                return true;
            }
        }
        return false;
    }

    // ── A7 裸 JDBC 白名单 ───────────────────────────────────────────────────

    /** 命中即算裸 JDBC 的目标类型（按声明类型判定，含子类）。 */
    private static final List<Class<?>> JDBC_TARGET_TYPES = List.of(
            org.springframework.jdbc.core.JdbcTemplate.class,
            org.springframework.jdbc.core.simple.JdbcClient.class,
            java.sql.DriverManager.class,
            java.sql.Connection.class,
            java.sql.Statement.class,
            java.sql.PreparedStatement.class,
            javax.sql.DataSource.class);

    /**
     * 裸 JDBC 棘轮基线（类 → 理由）。业务单表 CRUD 走 MyBatis-Plus；以下三类是 MP 的
     * 能力边界，登记放行。新类加进来必须在 PR 里论证属于同一类：
     * <ul>
     *   <li><b>PG/方言专有 SQL</b>——jsonb/向量操作符、批量写、清理任务等 wrapper
     *       表达不了的语句；</li>
     *   <li><b>非业务库引擎</b>——DuckDB / Doris / SQLite / pgvector，不在 MP 管辖；</li>
     *   <li><b>启动期修复</b>——StartupTaskRecovery。</li>
     * </ul>
     * <p>方言探测类副本已归一到 {@code DatabaseDialects.isPostgres}（原
     * detectPostgres 八处复制清零，相应白名单条目随之摘除）。</p>
     */
    private static final Map<String, String> JDBC_BASELINE = Map.ofEntries(
            Map.entry("com.ragagent.platform.persistence.DatabaseDialects", "方言探测（收敛点）"),
            Map.entry("com.ragagent.config.StartupTaskRecovery", "启动期任务修复（同步日志面）"),
            Map.entry("com.ragagent.knowledge.recovery.KnowledgeStartupRecovery", "启动恢复（知识/摘要复位，自 boot 下沉）"),
            Map.entry("com.ragagent.agent.skills.SkillCatalogService", "PG 专有 SQL（技能目录）"),
            Map.entry("com.ragagent.agenttools.datasource.AnalysisDuckDbJdbc", "非业务库引擎（DuckDB）"),
            Map.entry("com.ragagent.knowledge.repository.KnowledgeSpanRepository", "PG 专有 SQL（span 批量写）"),
            Map.entry("com.ragagent.knowledge.service.HousekeepingService", "PG 专有 SQL（超时清理）"),
            Map.entry("com.ragagent.auth.api.TenantStorageQuotaAdapter", "PG 专有 SQL（存储用量；A③ 自 knowledge 收回租户域）"),
            Map.entry("com.ragagent.retrieval.engine.VectorStoreEngineCatalog",
                    "连接探测（驱动级：postgres JDBC liveness；R3d 自 VectorStoreConfigService 随探测体迁入）"),
            Map.entry("com.ragagent.retrieval.engine.PgVectorEngineRepository", "非业务库引擎（pgvector 管理）"),
            Map.entry("com.ragagent.retrieval.engine.PgVectorRetrieveRepository", "非业务库引擎（pgvector 检索）"),
            Map.entry("com.ragagent.retrieval.engine.VectorStoreService", "非业务库引擎（pgvector 读写）"),
            Map.entry("com.ragagent.retrieval.engine.doris.DorisRetrieveRepository", "非业务库引擎（Doris）"),
            Map.entry("com.ragagent.retrieval.engine.doris.JdbcDorisSqlExecutor", "非业务库引擎（Doris 连接池）"),
            Map.entry("com.ragagent.retrieval.engine.sqlite.SqliteRetrieveRepository", "非业务库引擎（SQLite）"),
            Map.entry("com.ragagent.retrieval.engine.sqlite.SqliteSearchOps", "非业务库引擎（SQLite）"),
            Map.entry("com.ragagent.retrieval.engine.sqlite.SqliteWriteOps", "非业务库引擎（SQLite）"),
            Map.entry("com.ragagent.session.service.AgentToolBackends", "PG 专有 SQL（工具后端）"),
            Map.entry("com.ragagent.session.service.AgentToolKbBackends", "PG 专有 SQL（工具知识库后端）"),
            Map.entry("com.ragagent.session.service.AgentWebPages", "PG 专有 SQL（网页缓存）"),
            Map.entry("com.ragagent.system.service.SystemInfoService", "PG 专有 SQL（系统元数据）"),
            Map.entry("com.ragagent.vectorstore.service.VectorStoreConfigService", "PG 专有 SQL（向量库配置）"),
            Map.entry("com.ragagent.memory.repository.MemoryVectorStore", "列存在性探测（JDBC 元数据）"),
            Map.entry("com.ragagent.storage.repository.ResourceRepository", "PG 专有 SQL（存储资源查询，JdbcClient）"),
            Map.entry("com.ragagent.storage.repository.StorageBackendRepository", "PG 专有 SQL（存储后端元数据，JdbcClient）"));

    @Test
    @DisplayName("A7：裸 JDBC（JdbcTemplate/java.sql 连接与语句/DataSource）只许白名单类")
    void rawJdbcOnlyFromWhitelist() {
        Set<String> actual = new TreeSet<>();
        for (JavaClass clazz : MAIN) {
            if (isRawJdbcUser(clazz)) {
                actual.add(topLevel(clazz).getName());
            }
        }
        ratchet("A7 裸 JDBC", JDBC_BASELINE.keySet(), actual);
    }

    /**
     * MyBatis TypeHandler 是框架回调扩展点——PreparedStatement/ResultSet 由框架传入，
     * 属于 MyBatis 体系内，不算裸 JDBC。匿名/内部类归属其顶层类（同一源文件同责）。
     */
    private static boolean isRawJdbcUser(JavaClass clazz) {
        if (clazz.isAssignableTo(org.apache.ibatis.type.BaseTypeHandler.class)) {
            return false;
        }
        for (JavaCall<?> call : clazz.getMethodCallsFromSelf()) {
            for (Class<?> type : JDBC_TARGET_TYPES) {
                if (call.getTarget().getOwner().isAssignableTo(type)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static JavaClass topLevel(JavaClass clazz) {
        JavaClass cur = clazz;
        while (cur.getEnclosingClass().isPresent()) {
            cur = cur.getEnclosingClass().get();
        }
        return cur;
    }

    // ── A8 禁字符串列名 wrapper ─────────────────────────────────────────────

    /** 字符串列名 wrapper 的类型（应改用 LambdaQueryWrapper / LambdaUpdateWrapper）。 */
    private static final Set<String> STRING_WRAPPER_TYPES = Set.of(
            "com.baomidou.mybatisplus.core.conditions.query.QueryWrapper",
            "com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper",
            "com.baomidou.mybatisplus.core.conditions.query.QueryChainWrapper",
            "com.baomidou.mybatisplus.core.conditions.update.UpdateChainWrapper");

    /** {@code Wrappers} 上产出字符串 wrapper 的工厂方法（lambdaQuery/lambdaUpdate 不在内）。 */
    private static final Set<String> STRING_WRAPPER_FACTORY_METHODS = Set.of("query", "update", "emptyWrapper");

    /**
     * 棘轮基线：唯一保留项 {@code MessageRepository.update}——字符串 UpdateWrapper 是
     * 作者实测后的刻意选择：jsonb 列必须靠三参 {@code set(col, val, "typeHandler=…")}
     * 显式挂处理器，方法注释里记载了 H2 下退化形态的具体报错。其余存量（含全限定名
     * 形式，grep 之前漏数的）已于 2026-10-05 分三批 + knowledge 硬文件全部 Lambda 化。
     */
    private static final Set<String> STRING_WRAPPER_BASELINE = Set.of(
            "com.ragagent.session.repository.MessageRepository");

    @Test
    @DisplayName("A8：禁字符串列名 wrapper（new QueryWrapper/UpdateWrapper、Wrappers.query/update/emptyWrapper）")
    void noStringColumnWrappers() {
        ratchet("A8 字符串 wrapper", STRING_WRAPPER_BASELINE, violatingClasses(clazz -> {
            for (JavaConstructorCall call : clazz.getConstructorCallsFromSelf()) {
                if (STRING_WRAPPER_TYPES.contains(call.getTarget().getOwner().getName())) {
                    return true;
                }
            }
            for (JavaCall<?> call : clazz.getMethodCallsFromSelf()) {
                if (call.getTarget().getOwner().getName().equals(com.baomidou.mybatisplus.core.toolkit.Wrappers.class
                        .getName())
                        && STRING_WRAPPER_FACTORY_METHODS.contains(call.getTarget().getName())) {
                    return true;
                }
            }
            return false;
        }));
    }

    // ── A9 .last( 只许纯字符串字面量 ────────────────────────────────────────

    /**
     * 棘轮基线：已清空（2026-10-05）——13 文件 23 处拼接全部迁 {@code PageRequests}：
     * 手搓分页 → {@code range}、任意 offset → {@code atOffset}、行帽 → {@code cap}。
     * 本规则现为零基线纯禁令；{@code .last("LIMIT 1")} 等纯字面量仍合法。
     */
    private static final Map<String, String> LAST_CONCAT_BASELINE = Map.of();

    /**
     * 后端源码根：本类在组合根模块 {@code :boot} 里运行，共享内核（{@code common}/
     * {@code event} 等，已抽到 {@code :common}）的根也一并枚举进去。
     * 不存在的根由调用方跳过 ⇒ 单模块布局下仍然可用。
     * 包级可见：{@link ClasspathResourcesTest} 的反漂移扫描复用同一份根清单。
     */
    static java.util.List<java.nio.file.Path> backendSourceRoots(String sourceSet) {
        return SourceRoots.backend(sourceSet);   // 仓库根定位（多模块夹具）
    }

    @Test
    @DisplayName("A9：.last(...) 参数必须是纯字符串字面量（拼接=注入面+方言漂移）")
    void lastOnlyConstantStrings() throws java.io.IOException {
        Set<String> actual = new TreeSet<>();
        for (java.nio.file.Path root : backendSourceRoots("main")) {
            if (!java.nio.file.Files.isDirectory(root)) {
                continue;
            }
            try (var walk = java.nio.file.Files.walk(root)) {
                for (java.nio.file.Path file : walk.filter(java.nio.file.Files::isRegularFile)
                        .filter(f -> f.toString().endsWith(".java")).toList()) {
                    boolean[] inBlockComment = {false};
                    for (String line : java.nio.file.Files.readAllLines(file)) {
                        if (isConcatLast(stripComments(line, inBlockComment))) {
                            actual.add(file.toString().replace('\\', '/'));
                            break;
                        }
                    }
                }
            }
        }
        ratchet("A9 .last 拼接", LAST_CONCAT_BASELINE.keySet(), actual);
    }

    /** 剥掉块注释/行注释（保留字符串字面量），注释里的 {@code .last(} 样例不算违例。 */
    private static String stripComments(String line, boolean[] inBlockComment) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < line.length()) {
            if (inBlockComment[0]) {
                int end = line.indexOf("*/", i);
                if (end < 0) {
                    return sb.toString();
                }
                inBlockComment[0] = false;
                i = end + 2;
            } else if (i + 1 < line.length() && line.charAt(i) == '/' && line.charAt(i + 1) == '*') {
                inBlockComment[0] = true;
                i += 2;
            } else if (i + 1 < line.length() && line.charAt(i) == '/' && line.charAt(i + 1) == '/') {
                return sb.toString();
            } else if (line.charAt(i) == '"') {
                // 字符串字面量原样保留（含其中的 // 与 /*）；不做转义处理——本仓 SQL 字面量无嵌套引号
                int close = line.indexOf('"', i + 1);
                if (close < 0) {
                    return sb.append(line, i, line.length()).toString();
                }
                sb.append(line, i, close + 1);
                i = close + 1;
            } else {
                sb.append(line.charAt(i));
                i++;
            }
        }
        return sb.toString();
    }

    /** 行内出现 {@code .last(} 且参数不是单个纯字符串字面量 → 违例（注释里的样本同样命中，宁严勿漏）。 */
    private static boolean isConcatLast(String line) {
        int idx = line.indexOf(".last(");
        if (idx < 0) {
            return false;
        }
        String rest = line.substring(idx + ".last(".length()).trim();
        if (!rest.startsWith("\"")) {
            return true;
        }
        int close = rest.indexOf('"', 1);
        if (close < 0) {
            return true; // 字面量跨行：按违例处理
        }
        String tail = rest.substring(close + 1).trim();
        return !tail.isEmpty() && !tail.startsWith(")");
    }

    // ── 棘轮辅助 ────────────────────────────────────────────────────────────

    private static Set<String> violatingClasses(Predicate<JavaClass> detector) {
        Set<String> out = new TreeSet<>();
        for (JavaClass clazz : MAIN) {
            if (detector.test(clazz)) {
                out.add(clazz.getName());
            }
        }
        return out;
    }

    /**
     * 棘轮双断言：基线外新增违例=拦增量；基线条目不再违例=基线过期（也红——若检测逻辑
     * 空转，所有条目同时过期，规则不可能静默变绿）。与 A4 的 INSTALL_TARGETS、包级棘轮
     * 脚本同一套哲学。
     */
    private static void ratchet(String rule, Set<String> baseline, Set<String> actual) {
        Set<String> fresh = new TreeSet<>(actual);
        fresh.removeAll(baseline);
        assertThat(fresh)
                .as(rule + "：基线外新增违例——修掉，或论证属于同类场景后登记基线（附理由）")
                .isEmpty();
        Set<String> stale = new TreeSet<>(baseline);
        stale.removeAll(actual);
        assertThat(stale)
                .as(rule + "：基线条目已不再违例——清理已完成，请删除条目收紧基线")
                .isEmpty();
    }

    private static boolean isWiringPackage(String packageName) {
        return "com.ragagent.config".equals(packageName) || packageName.endsWith(".config");
    }

    private static Set<String> scannedPackages() {
        ConfigurationPropertiesScan scan =
                RagAgentApplication.class.getAnnotation(ConfigurationPropertiesScan.class);
        if (scan == null) {
            return Set.of();
        }
        Set<String> packages = new LinkedHashSet<>();
        packages.addAll(List.of(scan.value()));
        packages.addAll(List.of(scan.basePackages()));
        return packages;
    }


    // ── A10 分层倒挂 / A11 Mapper 包约定 ────────────────────────────────────

    @Test
    @DisplayName("A10：service 不得依赖 controller；domain 不得依赖 service/controller")
    void noLayerInversions() {
        assertThat(MAIN.size()).as("主源集导入为空或过少：检查 ImportOption 的输出目录过滤").isGreaterThan(500);
        noClasses().that().resideInAPackage("..service..")
                .should().dependOnClassesThat().resideInAPackage("..controller..")
                .because("依赖方向必须 controller → service → repository/mapper；"
                        + "service 回头引用 controller 即分层倒挂")
                .check(MAIN);
        // `..domain.access..`（落位，方案 §8.8.2）：**访问守卫**面——`ChunkAccessGuard` /
        // `FaqGuard` / `KnowledgeAccessGuard` 的职责就是"代表域校验访问"，必须查 service 侧状态；
        // 它是守卫/适配面而非数据模型，故从本规则的域模型面中排除（其余 `..domain..` 不变）。
        noClasses().that().resideInAPackage("..domain..")
                .and().resideOutsideOfPackage("..domain.access..")
                .should().dependOnClassesThat().resideInAnyPackage("..service..", "..controller..")
                .because("domain 是数据/领域模型，不得反向依赖 service/controller"
                        + "（domain.access 访问守卫面除外，见 §8.8.2）")
                .check(MAIN);
    }

    @Test
    @DisplayName("A11：*Mapper 接口必须落在 ..mapper.. 包（@MapperScan 范围）")
    void mapperInterfacesLiveInMapperPackages() {
        // 只约束顶层接口：@MapperScan 注册的是顶层接口，嵌套的 helper 接口（如
        // DorisSqlExecutor$RowMapper，JDBC 行映射用）不在其语义内。
        classes().that().areInterfaces().and().areNotNestedClasses().and().haveSimpleNameEndingWith("Mapper")
                .should().resideInAPackage("..mapper..")
                .because("@MapperScan(\"com.ragagent.**.mapper\") 只扫 mapper 包；"
                        + "放错包 = 启动期缺 Bean 或该 mapper 静默不注册")
                .check(MAIN);
    }

    /**
     * 棘轮基线：已清零（全仓手写门面都已在该域 {@code repository/} 包）。
     *
     * <p>口径不含 {@code retrieval/engine/**}——引擎适配仓储与检索是一套自成体系的目录约定，
     * 不适用本分层划分。</p>
     */
    private static final Set<String> REPOSITORY_IN_MAPPER_BASELINE = Set.of();

    @Test
    @DisplayName("A11b：仓储门面不得留在 mapper 包（A11 的镜像面：mapper 只放 MyBatis-Plus 接口）")
    void repositoriesDoNotLiveInMapperPackages() {
        // 约定来自分包地图：`mapper/` 只放 MyBatis-Plus 接口、`repository/` 放仓储门面。
        // 门面混在 mapper 包不会报错（Spring stereotype 与 @MapperScan 两条注册路径互不相干），
        // 代价是"按域类比写代码"的人会把门面继续放进 mapper 包 —— 分层约定就是这样一点点失效的。
        Set<String> actual = new TreeSet<>();
        for (JavaClass clazz : MAIN) {
            JavaClass top = topLevel(clazz);
            if (top.getSimpleName().endsWith("Repository")
                    && top.getPackageName().endsWith(".mapper")
                    && !isMyBatisMapper(top)) {
                actual.add(top.getName());
            }
        }
        ratchet("A11b mapper 包里的仓储门面", REPOSITORY_IN_MAPPER_BASELINE, actual);
    }

    /**
     * 真 MyBatis(-Plus) 映射接口判据：带 {@code @Mapper} 注解，或祖先链含 {@code BaseMapper}。
     *
     * <p>为什么必须按注解判而非按名字：本仓有 40+ 个 {@code extends BaseMapper} 的映射接口，
     * 其中两个以 {@code Repository} 结尾——按名字判会把它们当成"留在 mapper 包的门面"，
     * 让 A11b 带着两条**假阳性**长期占着基线（B267 现场核对时逐条读注解才发现）。</p>
     */
    private static boolean isMyBatisMapper(JavaClass clazz) {
        if (clazz.isAnnotatedWith("org.apache.ibatis.annotations.Mapper")) {
            return true;
        }
        return clazz.getAllRawInterfaces().stream()
                .anyMatch(i -> "com.baomidou.mybatisplus.core.mapper.BaseMapper".equals(i.getName()));
    }

    /**
     * 棘轮基线：仍有理由自建 mapper 的 7 处，每处都在类注释里写明了不能收编的原因。
     * <ul>
     *   <li>{@code JsonMappers} —— 工厂自身（它就是 lenient 的实现处）；</li>
     *   <li>{@code LangfuseAttributes} / {@code StreamJson} / {@code EventJson} ——
     *       {@code ORDER_MAP_ENTRIES_BY_KEYS} 或零值时间序列化器，**键序/时间字面量是线上字节面**；</li>
     *   <li>{@code GitLabConnector} —— 落库 jsonb 字节必须与既有 cursor 一致；</li>
     *   <li>{@code NotionJson} —— {@code ADJUST_DATES_TO_CONTEXT_TIME_ZONE=false}
     *       （收编会让写回 cursor 的偏移字面量漂移）；</li>
     *   <li>{@code ModelJsonValues} —— {@code FAIL_ON_TRAILING_TOKENS=true}（LLM 输出的严格解析）。</li>
     * </ul>
     * <p>全仓自建 mapper 已从 168 处收到 7 处：裸 {@code new ObjectMapper()}
     * 没注册 JSR-310、又开着严格模式，与 §2-12「宽松读统一策略」正好相反。</p>
     */
    private static final Set<String> SELF_MADE_MAPPER_BASELINE = Set.of(
            "com.ragagent.platform.web.JsonMappers",
            "com.ragagent.platform.tracing.langfuse.LangfuseAttributes",
            "com.ragagent.platform.stream.StreamJson",
            "com.ragagent.platform.event.EventJson",
            "com.ragagent.datasource.connector.gitlab.GitLabConnector",
            "com.ragagent.datasource.connector.notion.NotionJson",
            "com.ragagent.modelcontext.ModelJsonValues");

    @Test
    @DisplayName("A15：JSON mapper 必须来自 JsonMappers 工厂（不得自建 ObjectMapper / JsonMapper.builder）")
    void jsonMappersComeFromTheFactory() {
        // 判据（§2-12）：读落库/外部 JSON 统一忽略未知属性，JsonMappers.lenient() 是唯一工厂。
        // 自建 mapper 的代价不是"多写一行"，而是**策略分叉**：裸 new ObjectMapper() 没注册 JSR-310
        // （遇 java.time 字段直接抛 InvalidDefinitionException），且 FAIL_ON_UNKNOWN_PROPERTIES 为 true
        // ——与政策恰好相反，且只在特定数据到达时才炸。
        Set<String> actual = new TreeSet<>();
        for (JavaClass clazz : MAIN) {
            boolean selfMade = false;
            for (JavaConstructorCall call : clazz.getConstructorCallsFromSelf()) {
                if (call.getTarget().getOwner().isEquivalentTo(ObjectMapper.class)) {
                    selfMade = true;
                }
            }
            for (JavaCall<?> call : clazz.getMethodCallsFromSelf()) {
                if (call.getTarget().getOwner().isEquivalentTo(JsonMapper.class)
                        && "builder".equals(call.getTarget().getName())) {
                    selfMade = true;
                }
            }
            if (selfMade) {
                actual.add(topLevel(clazz).getName());
            }
        }
        ratchet("A15 自建 mapper", SELF_MADE_MAPPER_BASELINE, actual);
    }

    @Test
    @DisplayName("A13：@EventListener/@PostConstruct/@PreDestroy 方法必须声明在 Spring 扫描到的类上")
    void lifecycleHooksMustLiveOnScannedBeans() {
        // 判据来自一次真实事故：把 @EventListener/@PreDestroy 随方法一起
        // 搬进"由门面 new 出来的协作者类"——那个类不是 Spring bean ⇒ 注解**静默失效**
        // （渠道不再随应用启动、停机钩子不再执行），而编译与 4,700+ 全量测试**全部照绿**。
        // 这类失效没有任何运行期报错，只能靠本规则兜住。
        //
        // 口径：方法所在类必须被 Spring 扫到。（探针实测修正过一次判据：只认 @Component 派生会把
        // @ConfigurationProperties 类误报——它们经 @ConfigurationPropertiesScan 注册，同样是 bean，
        // 且 A2 已保证这类类落在扫描名单覆盖的包内，两条规则正好接上。）
        // 由 @Bean 方法返回的生命周期对象不在此列——那是 SmartLifecycle 语义，不是方法注解。
        Set<String> actual = new TreeSet<>();
        for (JavaClass clazz : MAIN) {
            if (clazz.isMetaAnnotatedWith(Component.class)
                    || clazz.isAnnotatedWith(ConfigurationProperties.class)) {
                continue;
            }
            for (JavaMethod method : clazz.getMethods()) {
                if (method.isAnnotatedWith(EventListener.class)
                        || method.isAnnotatedWith(PostConstruct.class)
                        || method.isAnnotatedWith(PreDestroy.class)) {
                    actual.add(topLevel(clazz).getName() + "#" + method.getName());
                }
            }
        }
        assertThat(actual)
                .as("A13 钩子方法不在 Spring 扫描到的类上 ⇒ 注解静默失效（无报错、测试也绿）。"
                        + "修法：把钩子挪到 bean（如域门面）上，或把该类变成 bean")
                .isEmpty();
    }

    // ── A16 边界层不得被用例层引用 / A17 service 不得依赖具体技术 ──────────────

    /**
     * A16 棘轮基线：{@code ..service..} 包内仍直接依赖边界层
     * （{@code controller} / {@code mapper} / {@code task} / {@code client} / {@code channel}）的类。
     *
     * <p><b>已清零（2026-10-19，L36~L44c）</b>：立规时存量 69 类，按「碰到哪域归哪域」
     * 逐域收敛——门面化（mapper → repository）、端口化（task/client → 自有接口）、
     * 落点纠偏（适配器 → api、装配 → config）三条路走完。本规则现为零基线纯禁令。</p>
     */
    private static final Set<String> SERVICE_BOUNDARY_BASELINE = Set.of();

    /**
     * A17 棘轮基线：{@code ..service..} 包内仍直接依赖具体技术实现
     * （MyBatis / Redis / Servlet / Langfuse）的类。
     *
     * <p><b>已清零（2026-10-19，L37~L45）</b>：立规时存量 60 类。收敛路径——
     * MyBatis/Redis 走 repository 门面或运行面外提；langfuse 走域内观测门面
     * （返回自有 Scope/SpanHandle，不泄露框架类型）；装饰器收进运行面入口。
     * 本规则现为零基线纯禁令。</p>
     */
    private static final Set<String> SERVICE_TECH_BASELINE = Set.of();

    /** service 层判据：包名任一段等于 {@code service}（对齐 ArchUnit {@code ..service..} 语义）。 */
    /**
     * 用例层判定：包名任一段是 {@code service}，或落在管线引擎 {@code rag} 下。
     *
     * <p>{@code rag} 是管线本体（原 {@code session.service.rag} 于 v0.5.0 提升为
     * 独立子域）——它同样属用例层，A16/A17 的约束必须跟着走，否则换包即静默脱管。</p>
     */
    private static boolean isServicePackage(String packageName) {
        for (String part : packageName.split("\\.")) {
            if ("service".equals(part) || "rag".equals(part)) {
                return true;
            }
        }
        // 会话域按用例层拆出的子包：与 service/rag 同属 A16/A17 约束面，
        // 不纳入会因换包而静默脱管（agent 只按 session 前缀收，避免误扫 agent-engine）。
        if (packageName.startsWith("com.ragagent.session.")) {
            for (String name : new String[] {"conversation", "attachment", "assembly"}) {
                if (packageName.equals("com.ragagent.session." + name)
                        || packageName.startsWith("com.ragagent.session." + name + ".")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 包名任一段等于给定边界层名（{@code ..mapper..} 形态）。 */
    private static boolean packageHasComponent(String packageName, String component) {
        for (String part : packageName.split("\\.")) {
            if (component.equals(part)) {
                return true;
            }
        }
        return false;
    }

    /** 直接依赖的目标是否命中给定前缀（技术实现面）。 */
    private static boolean dependsOnTechPrefix(JavaClass clazz, List<String> prefixes) {
        for (Dependency dependency : clazz.getDirectDependenciesFromSelf()) {
            String target = dependency.getTargetClass().getName();
            for (String prefix : prefixes) {
                if (target.startsWith(prefix)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 边界层名单：边界类由装配层/外部调用；用例层（service）经 repository / 端口使用能力。
     * 注：同包依赖不算跨层（边界类若与用例同类同包，是落点问题、由 Mapper 包约定等规则管）。
     */
    /** L1 底座顶层包：它们不是业务域/适配包，其内的 `task` 之类段名不算"边界层"。 */
    private static final Set<String> BASE_TOP_LEVEL = Set.of(
            "common", "platform", "contract", "event", "tracing", "stream", "config");

    private static final List<String> SERVICE_FORBIDDEN_BOUNDARY =
            List.of("controller", "mapper", "task", "client", "channel");

    /** 具体技术实现前缀：MyBatis / Redis / Servlet / Langfuse（厂商 SDK 碰到再加）。 */
    private static final List<String> SERVICE_FORBIDDEN_TECH = List.of(
            "com.baomidou.",
            "org.springframework.data.redis.",
            "redis.clients.",
            "jakarta.servlet.",
            "com.ragagent.platform.tracing.langfuse.",
            "com.ragagent.platform.tracing.decorators.");

    @Test
    @DisplayName("A16：service 不得直接依赖边界层（controller/mapper/task/client/channel）")
    void servicesDoNotDependOnBoundaryLayers() {
        Set<String> actual = new TreeSet<>();
        for (JavaClass clazz : MAIN) {
            if (!isServicePackage(clazz.getPackageName())) {
                continue;
            }
            for (Dependency dependency : clazz.getDirectDependenciesFromSelf()) {
                String target = dependency.getTargetClass().getPackageName();
                if (target.equals(clazz.getPackageName())) {
                    continue;
                }
                // 边界层匹配：排除 L1 底座（platform/common/contract/event/tracing/stream/config）后，
                // 任意 `com.ragagent.<段>.<边界层>` 都算 —— 既覆盖各业务域，也覆盖 agentmanagement/
                // agenttools 这类适配包；裸段名匹配会把底座面 `platform.task` 误判成边界层（B268② 实测假阳性）。
                String rest = target.startsWith("com.ragagent.")
                        ? target.substring("com.ragagent.".length()) : "";
                String head = rest.isEmpty() ? "" : rest.split("\\.", 2)[0];
                boolean hit = !rest.isEmpty() && !BASE_TOP_LEVEL.contains(head)
                        && SERVICE_FORBIDDEN_BOUNDARY.stream()
                                .anyMatch(component -> packageHasComponent(rest, component));
                if (hit) {
                    actual.add(topLevel(clazz).getName());
                    break;
                }
            }
        }
        ratchet("A16 service 依赖边界层", SERVICE_BOUNDARY_BASELINE, actual);
    }

    @Test
    @DisplayName("A17：service 不得依赖具体技术（MyBatis/Redis/Servlet/Langfuse）")
    void servicesDoNotDependOnConcreteTech() {
        Set<String> actual = new TreeSet<>();
        for (JavaClass clazz : MAIN) {
            if (!isServicePackage(clazz.getPackageName())) {
                continue;
            }
            if (dependsOnTechPrefix(clazz, SERVICE_FORBIDDEN_TECH)) {
                actual.add(topLevel(clazz).getName());
            }
        }
        ratchet("A17 service 依赖具体技术", SERVICE_TECH_BASELINE, actual);
    }

    // ── A18 状态所有权：协作者不回引引擎 / 运行态无公共可变字段 ──────────────────

    /** {@code AgentEngine} 全限定名（判据用字符串，测试不直接依赖引擎类型）。 */
    private static final String AGENT_ENGINE_NAME = "com.ragagent.agent.AgentEngine";

    /**
     * A18a 棘轮基线（状态所有权）：{@code com.ragagent.agent} 包内**回引 {@code AgentEngine}**
     * 的协作者（phase / assembler / emitter 形态）。
     *
     * <p>登记理由（方案 §9.1 / §12.2「状态所有权」）：协作者只许接自己需要的窄端口——
     * 回引整个引擎会让运行态变成"谁都能写"，单测也被迫整机装配。实测存量 8 类入基线：
     * 只许减、新增即红，随阶段 6（domains 重排）按状态所有权逐域消化。</p>
     */
    private static final Set<String> AGENT_ENGINE_BACKREF_BASELINE = Set.of(
            "com.ragagent.agent.ActPhase",
            "com.ragagent.agent.ContextDebugEmitter",
            "com.ragagent.agent.ConversationAssembler",
            "com.ragagent.agent.FinalizePhase",
            "com.ragagent.agent.ObservePhase",
            "com.ragagent.agent.ReActIteration",
            "com.ragagent.agent.SteerIntake",
            "com.ragagent.agent.ThinkPhase",
            // R5c（2026-10-16）登记：`AgentEngineHandleAdapter` 是**契约句柄的唯一适配器**——
            // 它的职责正是在边界上把 AgentEngine 的宽面收窄成域侧需要的 4 个方法
            // （setMemoryPrompt/setSteerSink/setCancellationSource/execute，且 execute 归 void）。
            // 属"合法的单一回引点"：若把它算违例，等于禁止句柄模式本身。
            "com.ragagent.agent.AgentEngineHandleAdapter");

    /**
     * A18b 棘轮基线：运行态包（agent / chatpipeline / session.controller）里仍带
     * **公共可变实例字段**的类。
     *
     * <p>登记理由（方案 §12.2「状态所有权」）：公共可变字段 = 多个写入者，无法回答
     * "这个字段的 writer 是谁"；运行态应单一 writer。实测存量见下（只许减、新增即红）。
     * 注：内嵌 DTO/视图载体（解析结果、工具结果行）不在本规则治理范围
     * （只查顶层类）——它们是数据形状，不是运行态。</p>
     */
    private static final Set<String> RUNTIME_PUBLIC_FIELD_BASELINE = Set.of(
            // skill 定义载体：带 loaded / frontmatterRepaired 运行标记——收编为不可变 model +
            // 独立状态容器的候选（阶段 6 消化）。其余命中都在内嵌 DTO（不在治理范围）。
            "com.ragagent.agent.skills.Skill");

    /** A18b 作用域：运行态与编排协作者所在包。 */
    private static boolean isRuntimePackage(String packageName) {
        return packageName.startsWith("com.ragagent.agent")
                || packageName.startsWith("com.ragagent.session.rag")
                || packageName.startsWith("com.ragagent.session.controller");
    }

    @Test
    @DisplayName("A18：agent 协作者不得回引 AgentEngine（状态所有权：只接窄端口）")
    void agentCollaboratorsDoNotHoldTheEngine() {
        Set<String> actual = new TreeSet<>();
        for (JavaClass clazz : MAIN) {
            if (!clazz.getPackageName().startsWith("com.ragagent.agent")
                    || AGENT_ENGINE_NAME.equals(clazz.getName())) {
                continue;
            }
            boolean holds = clazz.getFields().stream()
                    .anyMatch(f -> AGENT_ENGINE_NAME.equals(f.getRawType().getName()));
            if (holds) {
                actual.add(topLevel(clazz).getName());
            }
        }
        ratchet("A18 agent 协作者回引 AgentEngine", AGENT_ENGINE_BACKREF_BASELINE, actual);
    }

    @Test
    @DisplayName("A18：运行态类不得有公共可变实例字段（状态所有权：单一 writer）")
    void runtimeClassesHaveNoPublicMutableFields() {
        Set<String> actual = new TreeSet<>();
        for (JavaClass clazz : MAIN) {
            if (!isRuntimePackage(clazz.getPackageName()) || !clazz.isTopLevelClass()) {
                continue;
            }
            boolean hit = clazz.getFields().stream().anyMatch(f ->
                    f.getModifiers().contains(JavaModifier.PUBLIC)
                            && !f.getModifiers().contains(JavaModifier.STATIC)
                            && !f.getModifiers().contains(JavaModifier.FINAL));
            if (hit) {
                actual.add(clazz.getName());
            }
        }
        ratchet("A18 运行态公共可变字段", RUNTIME_PUBLIC_FIELD_BASELINE, actual);
    }

    // ── A19 分层方向（domain 不碰技术栈 / repository 不回引 service）──────

    /**
     * 棘轮基线：{@code domain} 面里仍依赖 Spring 构造型 / {@code ..mapper..} 包 的 **6 个类**
     * （A19 立规时 10 个 —— 其中 4 个实体的那条已于 A③-⑩ 收口）。
     *
     * <p>两类登记理由：① ~~4 个实体直接 import 其他域 {@code mapper} 里的
     * {@code JsonbRawStringTypeHandler}（{@code CustomAgentEntity} / {@code EmbedChannelEntity} /
     * {@code ChannelSessionEntity} / {@code ImChannelEntity}）~~ —— **2026-10-14 A③-⑩ 已收口**：
     * 该处理器归位 L1 {@code com.ragagent.platform.persistence}（三方共用），实体只留
     * {@code @TableField} 注解引用 ⇒ 基线相应收紧 4 条；② **6 个 {@code @Component} 的领域
     * 守卫 / 接线类**（knowledge 的 4 个 guard + {@code SpanTracker} + {@code ConnectorHttpWiring}）
     * ——收敛方向＝改由装配层注入，或搬去域外的接线面。**基线只许减不许增**。</p>
     *
     * <p><b>有意偏离（判据边界）</b>：{@code org.apache.ibatis.type.*}（实体的
     * {@code @TableField(jdbcType=…, typeHandler=…)} 注解参数）**未纳入判据**——它覆盖 49 个实体，
     * 是全仓 MyBatis-Plus 实体约定，纳入即等于给日常写法立规（每加一个 JSONB 列就红）；
     * 若将来实体注解下沉出 domain，再把该判据加进来。</p>
     */
    private static final Set<String> DOMAIN_TECH_DEPENDENCY_BASELINE = Set.of(
            "com.ragagent.datasource.domain.connector.ConnectorHttpWiring",
            "com.ragagent.knowledge.domain.access.FaqGuard",
            "com.ragagent.knowledge.domain.span.SpanTracker");

    @Test
    @DisplayName("A19：domain 不得依赖 Spring 构造型 / mapper 包；repository 不得依赖 service")
    void domainStaysTechnologyFreeAndRepositoryDoesNotDependOnService() {
        Set<String> techInDomain = new TreeSet<>();
        for (JavaClass clazz : MAIN) {
            if (!clazz.isTopLevelClass() || !packageHasComponent(clazz.getPackageName(), "domain")) {
                continue;
            }
            boolean hit = clazz.getDirectDependenciesFromSelf().stream()
                    .map(dep -> dep.getTargetClass().getPackageName())
                    .anyMatch(pkg -> pkg.startsWith("org.springframework")
                            || packageHasComponent(pkg, "mapper"));
            if (hit) {
                techInDomain.add(clazz.getName());
            }
        }
        ratchet("A19 domain 技术依赖", DOMAIN_TECH_DEPENDENCY_BASELINE, techInDomain);

        Set<String> repositoryToService = new TreeSet<>();
        for (JavaClass clazz : MAIN) {
            if (!clazz.isTopLevelClass() || !packageHasComponent(clazz.getPackageName(), "repository")) {
                continue;
            }
            clazz.getDirectDependenciesFromSelf().stream()
                    .filter(dep -> isServicePackage(dep.getTargetClass().getPackageName()))
                    .forEach(dep -> repositoryToService.add(
                            clazz.getName() + " → " + dep.getTargetClass().getName()));
        }
        ratchet("A19 repository→service", Set.of(), repositoryToService);
    }

    // ── A20 装配契约（每个 common 契约端口恰一个域侧实现）────────────────

    @Test
    @DisplayName("A20：每个 common 契约端口恰有一个域侧实现（装配契约：提供方唯一）")
    void eachContractPortHasExactlyOneImplementation() {
        // 契约模式（B264 起）：跨域调用一律经 `common/<域>/XxxPort`，实现方是该域的 api/ 适配器
        // （如 knowledge/api/ChunkPortAdapter）。实现方唯一是这套模式能让 @Lazy 环消失、装配面
        // 可审计的前提：0 个 = 契约悬空（调用方拿到 NoSuchBean 才发现）；≥2 个 = 提供方不唯一，
        // 契约失去"单点替换"语义。
        Set<String> implemented = new TreeSet<>();
        Set<String> duplicated = new TreeSet<>();
        for (JavaClass clazz : MAIN) {
            if (clazz.isInterface() || !clazz.isTopLevelClass()) {
                continue;
            }
            clazz.getAllRawInterfaces().stream()
                    .filter(ArchitectureRulesTest::isContractPort)
                    .forEach(port -> {
                        if (!implemented.add(port.getName())) {
                            duplicated.add(port.getName());
                        }
                    });
        }
        Set<String> violations = new TreeSet<>();
        for (JavaClass clazz : MAIN) {
            if (!isContractPort(clazz) || !clazz.isTopLevelClass()) {
                continue;
            }
            if (!implemented.contains(clazz.getName())) {
                violations.add(clazz.getName() + "（0 个实现：契约悬空）");
            }
            if (duplicated.contains(clazz.getName())) {
                violations.add(clazz.getName() + "（≥2 个实现：提供方不唯一）");
            }
        }
        ratchet("A20 契约端口唯一实现", Set.of(), violations);
    }

    /**
     * 契约端口判据：顶层接口、名字以 {@code Port} 结尾、位于契约面（{@code com.ragagent.contract}）
     * 或尚未迁完的 {@code com.ragagent.common}（A② 收纳进行中——两处都收，规则不会因搬家而静默失去
     * 覆盖面；待 {@code common} 侧端口清零后再收窄为单前缀）。
     */
    private static boolean isContractPort(JavaClass clazz) {
        return clazz.isInterface()
                && clazz.getSimpleName().endsWith("Port")
                && (clazz.getPackageName().startsWith("com.ragagent.contract")
                        || clazz.getPackageName().startsWith("com.ragagent.common"));
    }

    // ── 工具/管理适配包不得直连业务域 service/mapper ───────────────────

    /**
 * `agenttools` / `agentmanagement` 是 domains 侧对 Agent 的**适配包**
 * （工具实现 / Agent 定义管理），只能依赖 contracts/platform 与各域**公开用例**；
 * 直接 import 业务域的 `service` / `mapper` 会绕过域边界，把域内部结构（事务边界、
 * 持久层）纳入 Agent 面——这正是阶段 6 要消除的一类耦合。
 *
 * <p>域清单**显式列举**（单段域包的 service/mapper）：不用 `com.ragagent.*.service..`
 * 通配——那会把适配包**自己的**`agentmanagement.service/mapper` 也算成违规（假阳性）。</p>
 */
    private static final String[] FORBIDDEN_DOMAIN_PACKAGES = {
            "session", "knowledge", "wiki", "memory", "datasource", "model", "im", "storage",
            "websearch", "vectorstore", "settings", "auth", "mcp", "audit", "approval",
            "embedchannel", "evaluation", "favorite", "initialization", "skill", "system", "tenant"
    };

    /**
 * 基线：**已于 清零**（零容忍——新增即红）。
 *
 * <p>清零路径（都走"`common/&lt;域&gt;/XxxPort` + 该域 `api/` 适配器"）：`SkillController`
 * 的 audit 直连 → {@code common.audit.AuditWritePort}；`CustomAgentService` 的 knowledge 直连
 * → {@code common.knowledge.KnowledgeBaseCatalogPort}、auth 直连
 * → {@code common.auth.UserLookupPort}；`AgentSuggestedQuestions` 的条目经复核为**过期**（它只依赖
 * {@code knowledge.domain}，规则本就不禁）已删。</p>
 */
    private static final Set<String> AGENT_ADAPTER_DOMAIN_SERVICE_BASELINE = Set.of();

    @Test
    @DisplayName("B257：agenttools / agentmanagement 不得依赖业务域 service/mapper（棘轮）")
    void agentAdapterPackagesDoNotTouchDomainServices() {
        Set<String> actual = new TreeSet<>();
        for (JavaClass clazz : MAIN) {
            String pkg = clazz.getPackageName();
            if (!pkg.startsWith("com.ragagent.agenttools")
                    && !pkg.startsWith("com.ragagent.agentmanagement")) {
                continue;
            }
            boolean hit = clazz.getDirectDependenciesFromSelf().stream()
                    .anyMatch(dep -> isDomainServiceOrMapper(dep.getTargetClass().getPackageName()));
            if (hit) {
                actual.add(clazz.getName());
            }
        }
        ratchet("B257 适配包→域 service/mapper", AGENT_ADAPTER_DOMAIN_SERVICE_BASELINE, actual);
    }

    // ── 运行面不得依赖工具实现面 ────────────────────────────────────────

    /**
 * 工具**框架**（{@code AgentTool} / {@code BaseTool} / {@code ToolDefinitions} /
 * {@code ToolRegistry} / 执行上下文与预算 / 参数校验 / 输出持久化 / 流式修正 —— 现落
 * {@code com.ragagent.agent.tool*}）属 **Agent 运行面**；工具**实现**（按能力分族的业务工具 ——
 * {@code com.ragagent.agenttools}）属 **适配面**。
 *
 * <p>运行面反向依赖实现面，等于把"引擎 → 具体工具"写成硬编码依赖：引擎只该看框架与注册表，
 * 具体工具经注册/契约进入。 已把基础设施由 {@code agenttools} 迁入 {@code agent.tool*}，
 * 本规则把该方向**锁死**（基线 0 = 零容忍：运行面新增任何一条 {@code agenttools} import 即越界）。</p>
 */
    @Test
    @DisplayName("B260：Agent 运行面不得依赖工具实现面（agent → agenttools = 0）")
    void agentRuntimeDoesNotDependOnToolImplementations() {
        Set<String> violations = new TreeSet<>();
        for (JavaClass clazz : MAIN) {
            String pkg = clazz.getPackageName();
            if (!pkg.equals("com.ragagent.agent") && !pkg.startsWith("com.ragagent.agent.")) {
                continue;
            }
            clazz.getDirectDependenciesFromSelf().stream()
                    .filter(dep -> dep.getTargetClass().getPackageName()
                            .startsWith("com.ragagent.agenttools"))
                    .forEach(dep -> violations.add(clazz.getName() + " → " + dep.getTargetClass().getName()));
        }
        ratchet("B260 运行面→工具实现面", Set.of(), violations);
    }

    /** 目标包是否落在「业务域 service / mapper」面上（域清单显式列举，避免自身子包假阳性）。 */
    private static boolean isDomainServiceOrMapper(String packageName) {
        for (String domain : FORBIDDEN_DOMAIN_PACKAGES) {
            for (String face : List.of("service", "mapper")) {
                String prefix = "com.ragagent." + domain + "." + face;
                if (packageName.equals(prefix) || packageName.startsWith(prefix + ".")) {
                    return true;
                }
            }
        }
        return false;
    }
}
