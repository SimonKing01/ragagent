package com.ragagent.config;


import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.ragagent.auth.apikey.controller.filter.APIKeyAuthChannel;
import com.ragagent.auth.apikey.controller.filter.APIKeyGateInterceptor;
import com.ragagent.auth.apikey.controller.filter.APIKeyRouteAuthorizer;
import com.ragagent.auth.apikey.controller.filter.APIKeyRoutePolicies;
import com.ragagent.auth.apikey.controller.filter.APIKeyScopeCleanupFilter;
import com.ragagent.auth.apikey.controller.filter.AllowFileServeAPIKeyInterceptor;
import com.ragagent.auth.apikey.controller.filter.DenyAPIKeyPrincipalInterceptor;
import com.ragagent.auth.apikey.service.TenantAPIKeyService;
import com.ragagent.auth.controller.filter.AuthFilter;
import com.ragagent.auth.controller.filter.WsAuthSupport;
import com.ragagent.auth.service.UserService;
import com.ragagent.platform.config.DeploymentProperties;
import com.ragagent.platform.config.TenantProperties;
import com.ragagent.platform.tracing.langfuse.LangfuseHttpInterceptor;
import com.ragagent.platform.web.ApiResultInterceptor;
import com.ragagent.platform.web.ContractJsonModule;
import com.ragagent.platform.web.JsonMappers;
import com.ragagent.platform.web.RbacInterceptor;
import com.ragagent.platform.web.RequestIdFilter;
import com.ragagent.system.service.DeploymentCapabilitiesHolder;
import java.util.List;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 全局装配顺序：CORS → RequestID → Auth（认证走 servlet filter）。
 * 错误处理由 GlobalExceptionHandler + Spring 默认错误机制承担，
 * 响应契约由 golden 测试锁定。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * MVC 侧 mapper 与 {@link JsonMappers#lenient()} 同口径：契约 mixin、宽松读策略、
     * 时间按 ISO-8601 而非 epoch 输出。
     *
     * <p>只挂 customizer 而不自建 {@code ObjectMapper} bean —— 保留 Boot 的默认装配
     * （{@code ParameterNamesModule}、{@code spring.jackson.*}），避免 MVC 与业务注入
     * 拿到两份行为不同的 mapper。</p>
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer contractJsonCustomizer() {
        return builder -> builder
                .modulesToInstall(new ContractJsonModule())
                .featuresToDisable(
                        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                        DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private final TenantProperties tenantProperties;

    public WebConfig(TenantProperties tenantProperties) {
        this.tenantProperties = tenantProperties;
    }

    /** CORS：通配 Origin、显式头清单、MaxAge 12h */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of(
                "Origin", "Content-Type", "Accept", "Authorization", "X-API-Key",
                "X-Request-ID", "X-Tenant-ID", "X-Embed-Session",
                "X-External-User-ID", "X-External-User-Token"));
        config.setExposedHeaders(List.of("Content-Length", "Access-Control-Allow-Origin"));
        config.setAllowCredentials(true);
        config.setMaxAge(12L * 3600);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilter() {
        FilterRegistrationBean<CorsFilter> bean = new FilterRegistrationBean<>(new CorsFilter(corsConfigurationSource()));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }

    @Bean
    public FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        FilterRegistrationBean<RequestIdFilter> bean = new FilterRegistrationBean<>(new RequestIdFilter());
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        bean.addUrlPatterns("/*");
        return bean;
    }

    /** 认证过滤器（全局，覆盖 /*）。JWT 认证装配链在
     *  {@link com.ragagent.auth.controller.filter.WsAuthSupport}（@Component），
     *  本过滤器只组装三通道分派。 */
    @Bean
    public FilterRegistrationBean<AuthFilter> authFilter(UserService userService,
                                                         WsAuthSupport wsAuthSupport,
                                                         APIKeyAuthChannel apiKeyAuthChannel) {
        FilterRegistrationBean<AuthFilter> bean =
                new FilterRegistrationBean<>(new AuthFilter(userService, wsAuthSupport,
                        apiKeyAuthChannel));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        bean.addUrlPatterns("/*");
        return bean;
    }

    /**
     * 清理 API Key 作用域 ThreadLocal。Servlet 线程池会复用线程，
     * 不清理会让后续的 JWT 请求被误判成 API Key 主体。
     */
    @Bean
    public FilterRegistrationBean<APIKeyScopeCleanupFilter> apiKeyScopeCleanupFilter() {
        FilterRegistrationBean<APIKeyScopeCleanupFilter> bean =
                new FilterRegistrationBean<>(new APIKeyScopeCleanupFilter());
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 15);
        bean.addUrlPatterns("/*");
        return bean;
    }

    /**
     * 拦截器链装配。拦截器运行在 servlet filter（Auth）之后、controller 之前；
     * 生效顺序由 order() 决定：ApiResult(-100，信封打标) → APIKeyGate(-1，能力维度) →
     * AllowFileServe / DenyAPIKey / rbac（同 0，按登记序）→ Langfuse(10)。
     * RBAC 角色下限矩阵本身在 {@link RbacRoutePolicies#registerAll} 登记
     * （登记顺序即语义：静态段规则先于 /{id} 通配，首个命中生效）。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // API Key 能力维度的门禁。
        // 必须**排在角色维度的 RbacInterceptor 之前**：能力判定先于角色判定，
        // 且 RbacInterceptor 对 API Key 主体短路（见其 apiKeyShortCircuit）。
        APIKeyRouteAuthorizer apiKeyAuthorizer =
                new APIKeyRouteAuthorizer();
        APIKeyRoutePolicies.registerAll(apiKeyAuthorizer);
        registry.addInterceptor(new APIKeyGateInterceptor(apiKeyAuthorizer))
                .addPathPatterns("/api/v1/**")
                // /api/v1/files/presigned 与 presigned-preview 不走组级 APIKeyGate——
                // presigned 靠 HMAC 自证、preview 显式
                // DenyAPIKeyPrincipal（FileProxyController 内，403）。
                .excludePathPatterns("/api/v1/files/**")
                // 沙箱终端 WS 不走组级 APIKeyGate——票据自鉴权。
                .excludePathPatterns("/api/v1/sessions/*/sandbox/terminal")
                .order(-1);

        // 角色维度的守卫：规则矩阵（登记顺序即语义）集中在 RbacRoutePolicies。
        RbacInterceptor rbac = new RbacInterceptor(tenantProperties);
        RbacRoutePolicies.registerAll(rbac);

        // /files 与 KB 图片代理的 API-Key 自带守卫——这两个路由不在 /api/v1 组的门禁下，
        // KB 受限 Key 拒绝、full-access 与 retrieve 放行，JWT 直通。
        // 注册先于 rbac（守卫链序：API-Key 守卫 → Viewer）。
        registry.addInterceptor(new AllowFileServeAPIKeyInterceptor())
                .addPathPatterns("/files", "/api/v1/knowledge-bases/*/files")
                .order(0);
        // presigned-preview 的 DenyAPIKeyPrincipal（角色门禁对 Key 短路，须显式拒绝 Key）。
        registry.addInterceptor(new DenyAPIKeyPrincipalInterceptor())
                .addPathPatterns("/api/v1/files/presigned-preview")
                .order(0);

        registry.addInterceptor(rbac).addPathPatterns("/api/v1/sessions/**",
                "/api/v1/messages/**",
                "/api/v1/models/**",
                "/api/v1/knowledge-bases/**", "/api/v1/knowledge/**",
                "/api/v1/chunks/**", "/api/v1/faq/**",
                "/api/v1/knowledge-chat/**", "/api/v1/agent-chat/**", "/api/v1/knowledge-search",
                "/api/v1/mcp-services/**", "/api/v1/agent/**", "/api/v1/knowledgebase/**",
                "/api/v1/tenants/**", "/api/v1/system/**", "/api/v1/memory/**",
                "/api/v1/datasource/**",
                "/api/v1/web-search-providers/**", "/api/v1/web-search/**",
                "/api/v1/vector-stores/**", "/api/v1/storage-backends/**",
                "/api/v1/user/favorites/**", "/api/v1/chunker/**",
                "/api/v1/skills/**",
                "/api/v1/agents/**",
                "/api/v1/initialization/**",
                "/api/v1/embed-channels/**", "/api/v1/im-channels/**", "/api/v1/wechat/**",
                // presigned-preview 的 ADMIN 规则要有 pattern 才能命中；
                // presigned 与 preview 的 GET/HEAD 也流经本拦截器，但无规则即放行
                //（两条路由在引擎根注册、无 RBAC 中间件）。
                "/api/v1/files/**");
        // 漂移修复补的 pattern：chunks/messages/faq/knowledge-chat/agent-chat/
        // knowledge-search 六个前缀的 addRule 早已存在（chunks 读组、faq/import/progress、
        // chat 三入口），但拦截器此前不覆盖这些前缀 → 规则空转。im 的 engine 级回调
        // 路由（/api/v1/im/callback/**）刻意不在清单：注册在 Auth 之前、无 RBAC。

        // langfuse 请求级 trace。链序 Auth → langfuse → Audit。order=10 排在门禁之后：
        // 被 RBAC/API-Key 拒绝的请求不产生 trace（langfuse 在 Auth 的下游）。
        // 统一响应外壳打标：order=-100 ⇒ 先于 API-Key / RBAC 门禁运行 ⇒
        // 被门禁拒绝的 @ApiResult 路由同样拿到新错误形态（{code,message,data}）。
        // 约定见 docs/api/README.md。
        registry.addInterceptor(new ApiResultInterceptor())
                .addPathPatterns("/api/v1/**")
                .order(-100);

        registry.addInterceptor(new LangfuseHttpInterceptor())
                .addPathPatterns("/api/v1/**")
                .order(10);
    }

    /**
     * GET /system/capabilities 的启动快照：以"模块是否已注册"表达部署能力。
     * organizations 随空间分享裁撤；这是**部署状态**而非代码契约，各部署按各自状态断言。
     */
    @org.springframework.context.annotation.Bean
    public DeploymentCapabilitiesHolder deploymentCapabilitiesHolder(
            TenantAPIKeyService apiKeyService,
            DeploymentProperties deploymentProperties) {
        var holder = new DeploymentCapabilitiesHolder(deploymentProperties);
        holder.bind(
                /* agents */ true, // agents 家族路由已登记
                /* im */ true, // im 渠道 CRUD 面已登记
                /* embed */ true, // embed 渠道管理/公开面已登记
                /* api */ apiKeyService != null,
                /* mcp */ true,
                /* webSearch */ true,
                /* vectorStore */ true,
                /* storage */ true);
        return holder;
    }
}
