package com.ragagent.storage.fileserve;


import com.ragagent.contract.storage.support.FileUrlPort;
import com.ragagent.contract.storage.support.Resolver;
import com.ragagent.contract.storage.support.StorageBackendResolver;
import com.ragagent.storage.support.FileServiceResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把 {@code storageurl} 包的两个端口接到生产实现（A3-3 接线）：
 *
 * <ul>
 *   <li>{@link com.ragagent.contract.storage.support.StorageBackendResolver} → 见
 *       {@link FileserveStorageBackendResolver}（{@code @Component}）；</li>
 *   <li>{@link com.ragagent.contract.storage.support.FileUrlPort}（进程级默认）→ 本类的 bean：
 *       恒 local 基座 + resource
 *       catalog 装饰（于是 {@code resource://} 手柄在 {@code APP_EXTERNAL_URL} 在位时
 *       能派生出 {@code /r/<token>} 能力链接，此前该分支恒不可达）。</li>
 * </ul>
 *
 * <p>此前这两个端口都没有生产实现，三处 HTTP 调用点按 {@code ObjectProvider} 取到空、
 * 于是引用一律保留成 handle。</p>
 */
@Configuration
public class StorageUrlWiringConfig {

    /**
     * 进程级默认文件服务（只暴露 {@code GetFileURL} 的窄口）。
     *
     * <p>本地根目录与 {@link FileProxyService} 用同一个属性链
     * （{@code weknora.storage.local-base-dir} → {@code LOCAL_STORAGE_BASE_DIR} →
     * {@code ${user.home}/ragagent-data/files}），避免两处对"根在哪"有分歧。</p>
     */
    /**
     * 进程级 {@link Resolver}：IM 出站的 {@code Rewriter} 靠它把 {@code local://…}
     * 换成公网 {@code /r/{token}}。
     *
     * <p>此前只有 {@code FileUrlPort} 一个 bean，没有 {@code Resolver} ——
     * {@code ImTurnOrchestrator} 用 {@code ObjectProvider} 取到空，
     * 于是出站重写永远跳过：飞书里图片只能退化成文件名。</p>
     */
    @Bean
    public Resolver storageUrlResolver(FileUrlPort storageUrlDefaultFileService,
            org.springframework.beans.factory.ObjectProvider<StorageBackendResolver> storageBackends) {
        return new FileServiceResolver(null, storageUrlDefaultFileService,
                storageBackends.getIfAvailable());
    }

    @Bean
    public FileUrlPort storageUrlDefaultFileService(
            StorageFileResolver resolver,
            @Value("${weknora.storage.local-base-dir:${LOCAL_STORAGE_BASE_DIR:${user.home}/ragagent-data/files}}")
                    String localBaseDir) {
        return new DefaultFileUrlPort(resolver.globalFileService(localBaseDir));
    }

}
