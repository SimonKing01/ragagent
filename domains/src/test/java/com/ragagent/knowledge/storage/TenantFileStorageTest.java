package com.ragagent.knowledge.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.auth.service.TenantService;
import com.ragagent.contract.error.BizException;
import com.ragagent.contract.storage.fileserve.ProviderFileStorePort;
import com.ragagent.contract.tenant.TenantConfigLookup;
import com.ragagent.contract.tenant.TenantConfigLookup.TenantStorageView;
import com.ragagent.storage.api.ProviderFileStoreAdapter;
import com.ragagent.storage.fileserve.StorageFileResolver;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A3-3 尾批：租户感知文件存储门面（{@link TenantFileStorage}）与写面 provider 解析。
 *
 * <p>关键契约：<b>本地形态逐字节不变</b>（{@code resource://{tenant}/{knowledge}/{name}}，
 * golden 锁定）——无租户/租户无云配置/引用无 scheme 三种情形都必须落到本地；
 * 只有"引用带 provider scheme"或"租户 default provider 是云"才进对象存储。</p>
 */
class TenantFileStorageTest {

    private static final String MIME_AGNOSTIC = "hello";

    private TenantFileStorage facade(Path baseDir, TenantStorageView tenant) {
        return new TenantFileStorage(new LocalStorageService(baseDir.toString()),
                new ProviderFileStoreAdapter(new StorageFileResolver(null, null),
                        tenantServiceReturning(tenant), baseDir.toString()));
    }

    /** 端口实现（真实适配器 + 直连 resolver）：写面 provider 解析的钉子。 */
    private static ProviderFileStorePort providerStore(Path baseDir, TenantStorageView tenant) {
        return new ProviderFileStoreAdapter(new StorageFileResolver(null, null),
                tenantServiceReturning(tenant), baseDir.toString());
    }

    private static TenantConfigLookup tenantServiceReturning(TenantStorageView tenant) {
        return new TenantService(null, null, null) {
            @Override
            public TenantStorageView storageView(long id) {
                return tenant != null && tenant.tenantId() == id ? tenant : null;
            }
        };
    }

    @Test
    @DisplayName("无租户/租户无云配置：落本地盘，返回 resource:// 路径（golden 形态）")
    void localContractUnchanged(@TempDir Path dir) {
        TenantFileStorage storage = facade(dir, null); // 无租户
        byte[] content = MIME_AGNOSTIC.getBytes(StandardCharsets.UTF_8);

        String path = storage.save(7L, "k-1", "报告.pdf", content);
        assertEquals("resource://7/k-1/报告.pdf", path);
        assertTrue(Files.exists(dir.resolve("7/k-1/报告.pdf")));

        assertEquals(MIME_AGNOSTIC, new String(storage.read(7L, path), StandardCharsets.UTF_8));
        assertEquals(MIME_AGNOSTIC,
                new String(storage.readChecked(7L, path), StandardCharsets.UTF_8));

        storage.delete(7L, "k-1", path);
        assertFalse(Files.exists(dir.resolve("7/k-1")));
    }

    @Test
    @DisplayName("local:// 引用归本地盘（Go local provider 的原生形态），不当云 provider 处理")
    void localSchemeStaysLocal(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("7/k-5"));
        Files.write(dir.resolve("7/k-5/a.txt"), "hi".getBytes(StandardCharsets.UTF_8));

        TenantFileStorage storage = facade(dir, null);
        assertEquals("hi", new String(storage.readChecked(7L, "local://7/k-5/a.txt"),
                StandardCharsets.UTF_8));
        assertEquals("hi", new String(storage.read(7L, "local://7/k-5/a.txt"),
                StandardCharsets.UTF_8));

        storage.delete(7L, "k-5", "local://7/k-5/a.txt"); // 不抛错，只清本地
        assertFalse(Files.exists(dir.resolve("7/k-5")));
    }

    @Test
    @DisplayName("租户只配 local：仍是本地契约（不换成 provider 的 local:// 布局）")
    void localProviderStaysOnLocalContract(@TempDir Path dir) {
        ObjectNode sec = new ObjectMapper().createObjectNode();
        sec.put("defaultProvider", "local");

        TenantStorageView tenant = new TenantStorageView(7L, null, sec);
        TenantFileStorage storage = facade(dir, tenant);
        String path = storage.save(7L, "k-2", "a.txt", "x".getBytes(StandardCharsets.UTF_8));
        assertEquals("resource://7/k-2/a.txt", path);
        // local 的 provider 服务为 null（写面解析的"走本地"信号）。
        // 经租户 defaultProvider 表达——显式传 provider 会走 legacy alias 查询（要 DB 仓储）。
        ProviderFileStorePort.Resolution r = providerStore(dir, tenant).resolve(7L, null);
        assertNull(r.ops());
        assertNull(r.error());
        assertFalse(r.ok());
    }

    @Test
    @DisplayName("租户配云：写面解析出真 provider 服务（cos 代表，构造不触网）")
    void cloudProviderResolvedForWrite(@TempDir Path dir) {
        ObjectNode sec = new ObjectMapper().createObjectNode();
        sec.put("defaultProvider", "cos");
        TenantStorageView tenant = new TenantStorageView(9L, null, sec);
        ObjectNode cos = sec.putObject("cos");
        cos.put("secretId", "id");
        cos.put("secretKey", "key");
        cos.put("bucketName", "bk-125");
        cos.put("region", "ap-guangzhou");

        ProviderFileStorePort.Resolution r = providerStore(dir, tenant).resolve(9L, null);
        assertTrue(r.ok(), r.error());
        assertEquals("cos", r.provider());
        assertNotNull(r.ops());
    }

    @Test
    @DisplayName("租户配了云但配置不完备：写面明确报错（不静默退回本地）")
    void brokenCloudConfigFailsExplicitly(@TempDir Path dir) {
        ObjectNode sec = new ObjectMapper().createObjectNode();
        sec.put("defaultProvider", "cos");
        sec.putObject("cos").put("secretId", "id"); // 缺 secretKey/bucket/region

        TenantStorageView tenant = new TenantStorageView(9L, null, sec);
        TenantFileStorage storage = facade(dir, tenant);
        IllegalStateException err = assertThrows(IllegalStateException.class,
                () -> storage.save(9L, "k-3", "a.txt", "x".getBytes(StandardCharsets.UTF_8)));
        assertTrue(err.getMessage().contains("incomplete cos config"), err.getMessage());
        // 库里没留下半成品
        assertFalse(Files.exists(dir.resolve("9/k-3")));
    }

    @Test
    @DisplayName("读取：provider 引用在租户解析失败时折成 Failed to retrieve file 信封")
    void providerReadFailureEnvelope(@TempDir Path dir) {
        ObjectNode sec = new ObjectMapper().createObjectNode();
        sec.put("defaultProvider", "cos"); // cos 段缺失 → 解析失败

        TenantStorageView tenant = new TenantStorageView(11L, null, sec);
        TenantFileStorage storage = facade(dir, tenant);
        BizException err =
                assertThrows(BizException.class,
                        () -> storage.readChecked(11L, "cos://bk/ap-guangzhou/a/b.png"));
        assertTrue(String.valueOf(err.getMessage()).contains("Failed to retrieve file"),
                String.valueOf(err.getMessage()));
        // 删除是 best-effort：不抛
        storage.delete(11L, "k-4", "cos://bk/ap-guangzhou/a/b.png");
    }
}
