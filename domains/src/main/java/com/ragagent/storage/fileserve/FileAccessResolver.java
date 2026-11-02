package com.ragagent.storage.fileserve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.contract.storage.fileserve.MessageFactsLookup;
import com.ragagent.contract.storage.fileserve.MessageFileFacts;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.platform.storage.fileserve.StoragePaths;
import com.ragagent.platform.web.JsonMappers;
import com.ragagent.storage.domain.StoredResource;
import com.ragagent.storage.service.ResourceCatalogService;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * KB / 消息两个 scoped 文件代理的授权判定（ResolveKBFile / ResolveMessageFile /
 * AuthorizeMessageFile / MessageReferencesFile / resolveFile）；
 * 跨租户双授予链已随空间分享裁撤，消息文件授权只认本租户。
 *
 * <p>错误以 {@link FileAccessException} 抛出，由代理服务按三态映射写响应。</p>
 */
@Component
public class FileAccessResolver {

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    private final ResourceCatalogService catalog;

    public FileAccessResolver(ResourceCatalogService catalog) {
        this.catalog = catalog;
    }

    // ── resolveFile ─────────────────────────────────────────────────────────

    private record ResolvedFile(FileAccess file, StoredResource resource) {
    }

    private ResolvedFile resolveFile(String reference) {
        ResourceCatalogService.ResolvedPath resolved = catalog.resolvePath(reference);
        if (resolved.error()) {
            throw FileAccessException.notFound();
        }
        StoredResource resource = resolved.resource();
        String path = resolved.physicalPath();
        String filename = resolved.physicalPath();
        long owner = 0;
        String backendId = "";
        if (resource != null) {
            owner = resource.getTenantId();
            backendId = resource.getStorageBackendId();
            if (resource.getOriginalName() != null && !resource.getOriginalName().trim().isEmpty()) {
                filename = resource.getOriginalName();
            }
        }
        return new ResolvedFile(new FileAccess(owner, path, filename, backendId), resource);
    }

    // ── ResolveKBFile ───────────────────────────────────────────────────────

    /**
     * 要求路由的<b>精确 KB grant</b> 与独立的
     * 存活绑定检查。重写执行租户不足以授权。
     *
     * <p>grant 段（KB 归属一致、caller 一致、Viewer 权限、
     * API-Key 白名单）由 {@code ChunkAccessGuard.requireKbAccess} 在控制器
     * 内先行完成，本方法从 owner 段接续。</p>
     *
     * API-Key KB 白名单的越界异常由 requireKbAccess 按中间件形态抛出。
     */
        /** 形参由 KB 实体改为其租户 id（本方法只用这一个字段；存储域不应持有知识域实体）。 */
    public FileAccess resolveKbFile(Long kbTenantId, String kbId, String reference) {
        // grant 检查通过后：owner = grant.EffectiveTenantID（= KB 行的租户）
        long owner = kbTenantId == null ? 0 : kbTenantId;
        ResolvedFile resolved = resolveFile(reference);
        FileAccess file = resolved.file();
        StoredResource resource = resolved.resource();
        if (owner == 0 || (resource != null && resource.getTenantId() != owner)) {
            throw FileAccessException.forbidden();
        }
        String pathError = StoragePaths.validateKbScopedStoragePathError(file.path(), owner);
        if (pathError != null) {
            throw FileAccessException.forbidden();
        }
        boolean bound = catalog.isReferencedByKnowledgeBase(owner, kbId, reference);
        if (!bound) {
            throw FileAccessException.forbidden();
        }
        return new FileAccess(owner, file.path(), file.filename(), file.storageBackendId());
    }

    // ── MessageReferencesFile ───────────────────────────────────────────────

    /**
     * 只查<b>持久化的渲染/输出字段</b>（content / artifacts[].url / knowledge_references /
     * images / agent_steps[].tool_calls[].result）——工具参数与请求元数据不算证据。
     * 列表字段整体经 Jackson 序列化为 JSON 后跑整 token 匹配
     * （引用 token 本身不含 HTML 特殊字符，转义差异不影响匹配）。
     */
    public boolean messageReferencesFile(MessageFileFacts facts, String reference) {
        if (facts == null) {
            return false;
        }
        if (StoragePaths.containsStorageReference(facts.content(), reference)) {
            return true;
        }
        if (facts.artifactUrls() != null) {
            for (String url : facts.artifactUrls()) {
                if (url != null && reference.equals(url)) {
                    return true;
                }
            }
        }
        for (List<?> value : List.of(facts.knowledgeReferences(), facts.images())) {
            try {
                String data = value == null ? "null" : MAPPER.writeValueAsString(value);
                if (StoragePaths.containsStorageReference(data, reference)) {
                    return true;
                }
            } catch (Exception ignored) {
                // 序列化失败被吞（data 为空串 → 匹配不上）
            }
        }
        for (Object result : facts.toolResults()) {
            if (result == null) {
                continue;
            }
            try {
                String data = MAPPER.writeValueAsString(result);
                if (StoragePaths.containsStorageReference(data, reference)) {
                    return true;
                }
            } catch (Exception ignored) {
                // 序列化失败被吞（data 为空串 → 匹配不上）
            }
        }
        return false;
    }

    // ── ResolveMessageFile / AuthorizeMessageFile ───────────────────────────

    /**
     * 消息中「与文件引用匹配 / 授权」相关的字段（端口载荷）。
     *
     * <p>不让 storage 依赖 session 的 {@code Message} 实体：会话侧只把需要的字段映射进来，
     * 授权与匹配逻辑也只看这几段。**别改成"整条消息序列化"**——那会让匹配范围变宽，等于越权。</p>
     */

    /**
     * 用原始 caller 加载会话授权的消息；每次请求重查，不做缓存。
     */
    public FileAccess resolveMessageFile(String sessionId, String messageId, String reference,
            MessageFactsLookup messages) {
        Long callerTenant = TenantContext.currentTenantId();
        if (callerTenant == null || callerTenant == 0) {
            throw FileAccessException.unauthorized();
        }
        MessageFileFacts facts;
        try {
            facts = messages.lookup(sessionId, messageId);
        } catch (RuntimeException e) {
            // 消息加载的任何异常 → 404（无响应体）
            throw FileAccessException.notFound();
        }
        if (facts == null) {
            throw FileAccessException.notFound();
        }
        return authorizeMessageFile(facts, reference);
    }

    /** 引用匹配 + 归属校验（跨租户双授予已随空间分享裁撤，只认本租户）。 */
    private FileAccess authorizeMessageFile(MessageFileFacts facts, String reference) {
        Long callerTenant = TenantContext.currentTenantId();
        if (callerTenant == null || callerTenant == 0) {
            throw FileAccessException.unauthorized();
        }
        if (!messageReferencesFile(facts, reference)) {
            throw FileAccessException.forbidden();
        }
        ResolvedFile resolved = resolveFile(reference);
        FileAccess file = resolved.file();
        StoredResource resource = resolved.resource();

        long owner = facts.agentTenantId();
        if (resource != null) {
            owner = resource.getTenantId();
        }
        if (owner == 0) {
            throw FileAccessException.forbidden();
        }
        if ("user".equals(facts.role()) && owner != callerTenant) {
            throw FileAccessException.forbidden();
        }
        // 空间分享裁撤：跨租户双授予（org-shared KB 证据链 + shared-agent 授予）不再使用，
        // 消息文件授权只认本租户。
        if (owner != callerTenant) {
            throw FileAccessException.forbidden();
        }
        if (resource == null) {
            String pathError = StoragePaths.validateStoragePathTenantError(file.path(), owner);
            if (pathError != null) {
                throw FileAccessException.forbidden();
            }
        }
        return new FileAccess(owner, file.path(), file.filename(), file.storageBackendId());
    }

}
