package com.ragagent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.auth.service.TenantService;
import com.ragagent.contract.storage.fileserve.FileContentService;
import com.ragagent.contract.storage.fileserve.OpenedFile;
import com.ragagent.contract.tenant.TenantConfigLookup.TenantStorageView;
import com.ragagent.modelruntime.adapter.provider.chat.ImageResolver;
import com.ragagent.platform.storage.fileserve.StoragePaths;
import com.ragagent.storage.domain.StorageBackend;
import com.ragagent.storage.domain.StoredResource;
import com.ragagent.storage.fileserve.StorageFileResolver;
import com.ragagent.storage.repository.ResourceRepository;
import com.ragagent.storage.repository.StorageBackendRepository;
import com.ragagent.storage.service.ResourceCatalogService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

/**
 * {@link ChatLocalImageResolverWiring} 的装配单测（mock 依赖）+ 两条集成式断言
 * （真实 {@link ResourceCatalogService} / {@link StorageFileResolver} /
 * {@link LocalFileContentService} 链 + 临时目录落盘）。
 *
 * <p>覆盖解析闭包的各分支。
 * 环境变量说明：测试 JVM 无法设置 {@code LOCAL_STORAGE_BASE_DIR}
 * （JDK 21 模块系统挡掉 ProcessEnvironment 反射；Mockito 显式拒绝 mock
 * {@code java.lang.System}），集成式断言改用 {@code mockStatic(StoragePaths)} 把
 * {@code localStorageBaseDir()}（env 读数的唯一收口，含工厂缺省路径）钉到临时目录
 * ——try-with-resources 退出即还原。</p>
 */
class ChatLocalImageResolverWiringTest {

    /** 1x1 PNG 的头部（足以让 DetectContentType 认出 image/png，同 ImageResolverTest）。 */
    private static final byte[] PNG_BYTES = {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 'I', 'H', 'D', 'R'};

    /** 恰好 22 字符的合法 resource:// 手柄（[A-Za-z0-9_-]）。 */
    private static final String HANDLE = "Abc123_-Abc123_-Abc123";

    private final ResourceCatalogService catalog = mock(ResourceCatalogService.class);
    private final TenantService tenantService = mock(TenantService.class);
    private final StorageFileResolver storageResolver = mock(StorageFileResolver.class);

    private final ChatLocalImageResolverWiring wiring =
            new ChatLocalImageResolverWiring(catalog, tenantService, storageResolver);

    @AfterEach
    void tearDown() {
        // 集成式断言装上的静态钩子必须复位（同 ImageResolverTest 的纪律）
        ImageResolver.setLocalImageResolver(null);
    }

    // ── 单元分支（mock 依赖）────────────────────────────────────────────────

    /** resource 命中：路径里没有数字段（路径租户解析=0），租户只能来自 resource.TenantID。 */
    @Test
    void resourceHitUsesResourceTenantId() throws IOException {
        String url = "resource://" + HANDLE;
        StoredResource resource = new StoredResource();
        resource.setPhysicalPath("local://kb-assets/img.png");
        resource.setTenantId(42L);
        resource.setStorageBackendId("");
        when(catalog.resolvePath(url)).thenReturn(
                new ResourceCatalogService.ResolvedPath("local://kb-assets/img.png", resource, false));

        TenantStorageView tenant = new TenantStorageView(42L, null, null);
        when(tenantService.storageView(42L)).thenReturn(tenant);

        FileContentService fileSvc = mock(FileContentService.class);
        when(fileSvc.getFile("local://kb-assets/img.png"))
                .thenReturn(OpenedFile.ofBytes(PNG_BYTES));
        when(storageResolver.resolveFileService(eq(tenant), eq(""), eq("local"), anyString()))
                .thenReturn(new StorageFileResolver.Resolution(fileSvc, "local", null));

        assertThat(wiring.resolve(url)).isEqualTo(PNG_BYTES);
        verify(tenantService).storageView(42L);
    }

    /** resource:// 解析失败（缺行/非法引用）→ null，且后续步骤一步都不走。 */
    @Test
    void resolvePathErrorReturnsNull() {
        String url = "resource://" + HANDLE;
        when(catalog.resolvePath(url)).thenReturn(
                new ResourceCatalogService.ResolvedPath("", null, true));

        assertThat(wiring.resolve(url)).isNull();
        verifyNoInteractions(tenantService, storageResolver);
    }

    /** 租户查不到 → null，不再解析后端。 */
    @Test
    void tenantMissingReturnsNull() {
        String url = "resource://" + HANDLE;
        StoredResource resource = new StoredResource();
        resource.setPhysicalPath("local://kb-assets/img.png");
        resource.setTenantId(42L);
        resource.setStorageBackendId("");
        when(catalog.resolvePath(url)).thenReturn(
                new ResourceCatalogService.ResolvedPath("local://kb-assets/img.png", resource, false));
        when(tenantService.storageView(42L)).thenReturn(null);

        assertThat(wiring.resolve(url)).isNull();
        verify(storageResolver, never()).resolveFileService(any(), anyString(), anyString(), anyString());
    }

    /** 路径无数字段且无 resource 行 → 租户 ID=0 → null。 */
    @Test
    void tenantIdZeroReturnsNull() {
        String url = "s3://mybucket/objects/photo.png";
        when(catalog.resolvePath(url)).thenReturn(
                new ResourceCatalogService.ResolvedPath(url, null, false));

        assertThat(wiring.resolve(url)).isNull();
        verifyNoInteractions(tenantService, storageResolver);
    }

    /** 后端解析失败 → null。 */
    @Test
    void resolveFileServiceErrorReturnsNull() {
        String url = "resource://" + HANDLE;
        StoredResource resource = new StoredResource();
        resource.setPhysicalPath("local://kb-assets/img.png");
        resource.setTenantId(42L);
        resource.setStorageBackendId("");
        when(catalog.resolvePath(url)).thenReturn(
                new ResourceCatalogService.ResolvedPath("local://kb-assets/img.png", resource, false));
        TenantStorageView tenant = new TenantStorageView(42L, null, null);
        when(tenantService.storageView(42L)).thenReturn(tenant);
        when(storageResolver.resolveFileService(eq(tenant), eq(""), eq("local"), anyString()))
                .thenReturn(new StorageFileResolver.Resolution(null, "", "storage backend not found"));

        assertThat(wiring.resolve(url)).isNull();
    }

    /** 成功读字节（seekable 文件支路）；provider 从路径前缀解析为 local。 */
    @Test
    void readsBytesFromSeekableFile(@TempDir Path tmp) throws IOException {
        Path img = tmp.resolve("img.png");
        Files.write(img, PNG_BYTES);
        String url = "local://7/x/img.png";
        when(catalog.resolvePath(url)).thenReturn(new ResourceCatalogService.ResolvedPath(url, null, false));
        TenantStorageView tenant = new TenantStorageView(7L, null, null);
        when(tenantService.storageView(7L)).thenReturn(tenant);

        FileContentService fileSvc = mock(FileContentService.class);
        when(fileSvc.getFile(url)).thenReturn(OpenedFile.ofSeekable(img, PNG_BYTES.length));
        when(storageResolver.resolveFileService(eq(tenant), eq(""), eq("local"), anyString()))
                .thenReturn(new StorageFileResolver.Resolution(fileSvc, "local", null));

        assertThat(wiring.resolve(url)).isEqualTo(PNG_BYTES);
    }

    /** resource 行带 StorageBackendID 时覆盖从路径解析出的 backendID。 */
    @Test
    void resourceBackendIdOverridesParsedBackendId() throws IOException {
        String url = "storage://bk1/local://7/x/img.png";
        StoredResource resource = new StoredResource();
        resource.setPhysicalPath(url);
        resource.setTenantId(7L);
        resource.setStorageBackendId("bk2");
        when(catalog.resolvePath(url)).thenReturn(new ResourceCatalogService.ResolvedPath(url, resource, false));
        TenantStorageView tenant = new TenantStorageView(7L, null, null);
        when(tenantService.storageView(7L)).thenReturn(tenant);

        FileContentService fileSvc = mock(FileContentService.class);
        when(fileSvc.getFile(url)).thenReturn(OpenedFile.ofBytes(PNG_BYTES));
        // scoped=true → providerPath 剥包装后仍是 local 前缀；backendID 被覆盖成 bk2
        when(storageResolver.resolveFileService(eq(tenant), eq("bk2"), eq("local"), anyString()))
                .thenReturn(new StorageFileResolver.Resolution(fileSvc, "local", null));

        assertThat(wiring.resolve(url)).isEqualTo(PNG_BYTES);
        verify(storageResolver).resolveFileService(eq(tenant), eq("bk2"), eq("local"), anyString());
    }

    // ── 集成式断言（真实解析链 + 临时目录）──────────────────────────────────

    /** 装配后 ImageResolver.resolveImageUrlForLlm 把 storage:// URL 转成 data: URI。 */
    @Test
    void wiredResolverTurnsStorageUrlIntoDataUri(@TempDir Path tmp) throws IOException {
        Path img = tmp.resolve("7").resolve("abc").resolve("img.png");
        Files.createDirectories(img.getParent());
        Files.write(img, PNG_BYTES);

        TenantStorageView tenant = new TenantStorageView(7L, null, null);
        TenantService tenants = mock(TenantService.class);
        when(tenants.storageView(7L)).thenReturn(tenant);

        StorageBackendRepository backendRepo = mock(StorageBackendRepository.class);
        when(backendRepo.tenantDefaultBackendId(7L)).thenReturn(null);
        when(backendRepo.getByID(7L, "bk1")).thenReturn(Optional.of(localBackend()));

        ResourceRepository resourceRepo = mock(ResourceRepository.class);
        ResourceCatalogService realCatalog = new ResourceCatalogService(resourceRepo);
        StorageFileResolver realResolver = new StorageFileResolver(backendRepo, realCatalog);

        ChatLocalImageResolverWiring wiring = new ChatLocalImageResolverWiring(realCatalog, tenants, realResolver);
        wiring.afterPropertiesSet();
        try (MockedStatic<StoragePaths> storagePaths = mockStatic(StoragePaths.class, CALLS_REAL_METHODS)) {
            storagePaths.when(StoragePaths::localStorageBaseDir).thenReturn(tmp.toString());

            String got = ImageResolver.resolveImageUrlForLlm("storage://bk1/local://7/abc/img.png");
            assertThat(got).isEqualTo("data:image/png;base64," + Base64.getEncoder().encodeToString(PNG_BYTES));
        }
    }

    /** 同链路、resource:// 入口：手柄经真实 ResourceCatalogService 换物理路径，租户取 resource.TenantID。 */
    @Test
    void wiredResolverTurnsResourceHandleIntoDataUri(@TempDir Path tmp) throws IOException {
        Path img = tmp.resolve("7").resolve("abc").resolve("img.png");
        Files.createDirectories(img.getParent());
        Files.write(img, PNG_BYTES);

        StoredResource resource = new StoredResource();
        resource.setHandle(HANDLE);
        resource.setPhysicalPath("local://7/abc/img.png");
        resource.setTenantId(7L);
        resource.setStorageBackendId("");
        ResourceRepository resourceRepo = mock(ResourceRepository.class);
        when(resourceRepo.getByHandle(HANDLE)).thenReturn(Optional.of(resource));

        TenantStorageView tenant = new TenantStorageView(7L, null, null);
        TenantService tenants = mock(TenantService.class);
        when(tenants.storageView(7L)).thenReturn(tenant);

        StorageBackendRepository backendRepo = mock(StorageBackendRepository.class);
        when(backendRepo.tenantDefaultBackendId(7L)).thenReturn(null);

        ResourceCatalogService realCatalog = new ResourceCatalogService(resourceRepo);
        StorageFileResolver realResolver = new StorageFileResolver(backendRepo, realCatalog);
        ChatLocalImageResolverWiring wiring = new ChatLocalImageResolverWiring(realCatalog, tenants, realResolver);
        wiring.afterPropertiesSet();
        try (MockedStatic<StoragePaths> storagePaths = mockStatic(StoragePaths.class, CALLS_REAL_METHODS)) {
            storagePaths.when(StoragePaths::localStorageBaseDir).thenReturn(tmp.toString());

            String got = ImageResolver.resolveImageUrlForLlm("resource://" + HANDLE);
            assertThat(got).isEqualTo("data:image/png;base64," + Base64.getEncoder().encodeToString(PNG_BYTES));
        }
    }

    /** active 的 local 后端行（config 无 path_prefix → baseDir 原样）。 */
    private static StorageBackend localBackend() {
        StorageBackend b = new StorageBackend();
        b.setId("bk1");
        b.setTenantId(7L);
        b.setName("System local");
        b.setProvider("local");
        b.setSource("env");
        b.setStatus("active");
        b.setConfig(new ObjectMapper().createObjectNode());
        return b;
    }
}
