package com.ragagent.storage.fileserve;

import static org.assertj.core.api.Assertions.assertThat;

import com.ragagent.contract.storage.support.FileUrlPort;
import com.ragagent.contract.storage.support.Resolver;
import com.ragagent.contract.storage.support.StorageBackendResolver;
import com.ragagent.platform.storage.support.Rewriter;
import java.util.Iterator;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@code storageUrlResolver} 这个 bean 的存在性守卫。
 *
 * <p>IM 出站的 {@link Rewriter} 用 {@code ObjectProvider<Resolver>} 取依赖：容器里没有
 * {@code Resolver} 时拿到 null，重写被**静默跳过**，飞书里图片只能退化成文件名
 * （2026-11-07 线上实测：日志里一条 {@code storage URL rewrite} 都没有）。</p>
 */
class StorageUrlWiringConfigTest {

    /** 只实现用到的那几个方法：容器里没有 StorageBackendResolver。 */
    private static final ObjectProvider<StorageBackendResolver> NO_BACKENDS =
            new ObjectProvider<>() {
                @Override
                public StorageBackendResolver getObject(Object... args) {
                    throw new IllegalStateException("no StorageBackendResolver");
                }

                @Override
                public StorageBackendResolver getObject() {
                    throw new IllegalStateException("no StorageBackendResolver");
                }

                @Override
                public StorageBackendResolver getIfAvailable() {
                    return null;
                }

                @Override
                public StorageBackendResolver getIfUnique() {
                    return null;
                }

                @Override
                public StorageBackendResolver getIfAvailable(
                        Supplier<StorageBackendResolver> defaultSupplier) {
                    return defaultSupplier.get();
                }

                @Override
                public StorageBackendResolver getIfUnique(
                        Supplier<StorageBackendResolver> defaultSupplier) {
                    return defaultSupplier.get();
                }

                @Override
                public Iterator<StorageBackendResolver> iterator() {
                    return java.util.Collections.emptyIterator();
                }
            };

    @Test
    void resolverRewritesLocalRefToHttp() {
        FileUrlPort defaultPort = filePath -> "https://agent.example.com/r/token";

        Resolver resolver = new StorageUrlWiringConfig()
                .storageUrlResolver(defaultPort, NO_BACKENDS);

        assertThat(resolver).isNotNull();
        Rewriter rewriter = new Rewriter(resolver, "test");
        assertThat(rewriter.enabled()).isTrue();
        assertThat(rewriter.rewrite("![封面](local://1/exports/cover.jpg)"))
                .isEqualTo("![封面](https://agent.example.com/r/token)");
    }
}
