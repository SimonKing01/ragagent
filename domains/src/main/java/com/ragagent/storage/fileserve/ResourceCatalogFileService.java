package com.ragagent.storage.fileserve;

import com.ragagent.contract.storage.fileserve.FileContentService;
import com.ragagent.contract.storage.fileserve.OpenedFile;
import com.ragagent.contract.storage.fileserve.WritableFileContentService;
import com.ragagent.platform.security.Hashes;
import com.ragagent.storage.service.ResourceCatalogService;
import java.io.IOException;
import java.time.Duration;

/**
 * {@code resource://} 装饰器：GetFile 先把手柄换成物理路径；GetFileURL 对手柄在外部 URL
 * 在位时派生 {@code /r/} 令牌；写面落盘后登记资源目录（失败回删物理文件）。
 */
final class ResourceCatalogFileService implements WritableFileContentService {

    private final FileContentService inner;
    private final ResourceCatalogService catalog;

    ResourceCatalogFileService(FileContentService inner, ResourceCatalogService catalog) {
        this.inner = inner;
        this.catalog = catalog;
    }

    @Override
    public OpenedFile getFile(String filePath) throws IOException {
        ResourceCatalogService.ResolvedPath resolved = catalog.resolvePath(filePath);
        if (resolved.error()) {
            throw new IOException("resource not found");
        }
        return inner.getFile(resolved.physicalPath());
    }

    @Override
    public String getFileURL(String filePath) throws IOException {
        ResourceCatalogService.ResolvedPath resolved = catalog.resolvePath(filePath);
        if (resolved.error()) {
            throw new IllegalStateException("resource not found");
        }
        String physical = resolved.physicalPath();
        boolean isResource = resolved.resource() != null;
        String externalURL = StorageFileResolver.env("APP_EXTERNAL_URL").replaceAll("/+$", "");
        if (!externalURL.isEmpty()) {
            // 不只是 resource://：历史/规范的 local://… 也要签成能力链接，
            // 否则 IM（飞书/企微）拿到的是 local://，下载不到图只能退化成文件名。
            var token = catalog.createAccessGrant(filePath, Duration.ofHours(2));
            if (token.isPresent()) {
                return externalURL + "/r/" + token.get();
            }
            if (isResource) {
                throw new IllegalStateException("failed to allocate resource access token");
            }
        }
        return inner.getFileURL(physical);
    }

    /**
     * 物理落盘 → SHA-256 内容哈希 → 资源注册（失败回删物理文件）→ 返回 resource:// 手柄。
     */
    @Override
    public String saveBytes(byte[] data, long tenantId, String fileName, boolean temp)
            throws IOException {
        String physical;
        if (inner instanceof WritableFileContentService writable) {
            physical = writable.saveBytes(data, tenantId, fileName, temp);
        } else {
            throw new IOException("storage provider does not support saving bytes");
        }
        String hash = Hashes.sha256Hex(data);
        try {
            String kind = resourceKind(fileName);
            String mimeType = probeMimeType(fileName);
            return catalog.register(tenantId, physical, new ResourceCatalogService.ResourceRegistration(
                    kind, mimeType, baseName(fileName), data.length, hash, temp));
        } catch (RuntimeException e) {
            if (inner instanceof WritableFileContentService w) {
                try {
                    w.deleteFile(physical);
                } catch (IOException ignored) {
                    // register 失败尽力回删物理文件
                }
            }
            throw new IOException("register stored resource: " + e.getMessage(), e);
        }
    }

    /** 物理删除 + 资源软删。 */
    @Override
    public void deleteFile(String filePath) throws IOException {
        ResourceCatalogService.ResolvedPath resolved = catalog.resolvePath(filePath);
        if (resolved.error()) {
            throw new IOException("resource not found");
        }
        if (!(inner instanceof WritableFileContentService writable)) {
            throw new IOException("storage provider does not support deleting files");
        }
        writable.deleteFile(resolved.physicalPath());
        if (resolved.resource() != null) {
            try {
                catalog.markDeleted(filePath);
            } catch (RuntimeException e) {
                throw new IOException("mark resource deleted: " + e.getMessage(), e);
            }
        }
    }

    /** mime 前缀 → image/audio/video，否则 file。 */
    private static String resourceKind(String name) {
        String mimeType = probeMimeType(name);
        if (mimeType.startsWith("image/")) {
            return "image";
        }
        if (mimeType.startsWith("audio/")) {
            return "audio";
        }
        if (mimeType.startsWith("video/")) {
            return "video";
        }
        return "file";
    }

    /** mime.TypeByExtension 的有界版：内置表 + Files 探测，未知 → ""。 */
    private static String probeMimeType(String name) {
        String ext = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        int dot = ext.lastIndexOf('.');
        ext = dot < 0 ? "" : ext.substring(dot);
        String builtIn = switch (ext) {
            case ".html", ".htm" -> "text/html; charset=utf-8";
            case ".css" -> "text/css; charset=utf-8";
            case ".js", ".mjs" -> "text/javascript; charset=utf-8";
            case ".json" -> "application/json";
            case ".pdf" -> "application/pdf";
            case ".txt" -> "text/plain; charset=utf-8";
            case ".md", ".markdown" -> "text/markdown; charset=utf-8";
            case ".svg" -> "image/svg+xml";
            case ".png" -> "image/png";
            case ".jpg", ".jpeg" -> "image/jpeg";
            case ".gif" -> "image/gif";
            case ".webp" -> "image/webp";
            case ".avif" -> "image/avif";
            case ".wasm" -> "application/wasm";
            case ".xml" -> "text/xml; charset=utf-8";
            default -> null;
        };
        if (builtIn != null) {
            return builtIn;
        }
        try {
            String probed = java.nio.file.Files.probeContentType(java.nio.file.Path.of("f" + ext));
            return probed == null ? "" : probed;
        } catch (IOException e) {
            return "";
        }
    }

    private static String baseName(String name) {
        String n = name == null ? "" : name;
        int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        return slash < 0 ? n : n.substring(slash + 1);
    }
}
