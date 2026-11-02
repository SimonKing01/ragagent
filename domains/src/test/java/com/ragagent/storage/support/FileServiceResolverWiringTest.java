package com.ragagent.storage.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.contract.storage.support.FileUrlPort;
import com.ragagent.contract.tenant.TenantConfigLookup.TenantStorageView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 接线：{@code storageurl.FileServiceResolver} 的 provider 级文件服务解析顺序：
 * 真实服务 → local/默认 → null。
 *
 * <p>用 {@code cos} 代表云 provider（构造函数不触网）。断言"解析出非空服务"即
 * 证明工厂被接上；真正的 URL 生成要凭据，属部署态。</p>
 */
class FileServiceResolverWiringTest {

    @Test
    @DisplayName("租户配了 cos：cos:// 引用解析出真 provider 服务；无配置则回落 defaultSvc")
    void resolvesProviderServiceFromTenantConfig() {
        ObjectNode sec = new ObjectMapper().createObjectNode();
        sec.put("defaultProvider", "cos");
        ObjectNode cos = sec.putObject("cos");
        cos.put("secretId", "id");
        cos.put("secretKey", "key");
        cos.put("bucketName", "bk-125");
        cos.put("region", "ap-guangzhou");

        TenantStorageView tenant = new TenantStorageView(0L, null, sec);
        FileServiceResolver resolver = new FileServiceResolver(tenant, null, null);
        FileUrlPort svc = resolver.resolveFileService("cos://bk-125/ap-guangzhou/a/b.png");
        assertNotNull(svc, "cos 配置完备时应解析出真 provider 服务");

        // 同一 (backendId, provider) 第二次命中缓存（同一实例）
        assertEquals(svc, resolver.resolveFileService("cos://bk-125/ap-guangzhou/a/c.png"));
    }

    @Test
    @DisplayName("租户没配存储 / 配置不完备：回落 defaultSvc（引用按 handle 保留）")
    void fallsBackToDefaultService() {
        TenantStorageView empty = new TenantStorageView(0L, null, null); // 无 storageEngineConfig
        FileServiceResolver noConfig = new FileServiceResolver(empty, null, null);
        assertNull(noConfig.resolveFileService("cos://bk/ap-guangzhou/a/b.png"));

        // 引用不带 provider scheme → 用租户 defaultProvider；仍无 → null（不解析）
        assertNull(noConfig.resolveFileService("plain/path.png"));

        // 不完备的 cos 段 → 工厂抛"incomplete cos config" → 回落 defaultSvc（此处非空）
        ObjectNode sec = new ObjectMapper().createObjectNode();
        sec.put("defaultProvider", "cos");
        sec.putObject("cos").put("secretId", "id");
        TenantStorageView partial = new TenantStorageView(0L, null, sec);
        StubUrlFileService fallback = new StubUrlFileService();
        FileServiceResolver partialResolver = new FileServiceResolver(partial, fallback, null);
        assertEquals(fallback, partialResolver.resolveFileService("cos://bk/r/a.png"));
    }

    private static final class StubUrlFileService implements FileUrlPort {
        @Override
        public String getFileURL(String filePath) {
            return "local://" + filePath;
        }
    }
}
