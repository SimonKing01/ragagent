package com.ragagent.knowledge.controller;

import com.ragagent.contract.error.AppError;
import com.ragagent.contract.error.BizException;
import com.ragagent.contract.storage.support.FileUrlPort;
import com.ragagent.knowledge.service.access.ChunkAccessGuard;
import com.ragagent.platform.storage.fileserve.FileProxyPort;
import com.ragagent.platform.storage.fileserve.FileServeHttp;
import com.ragagent.platform.web.ApiResult;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * newKBScopedFileServeHandlerWithResources + access.ResolveKBFile）。
 * <p>渲染知识库内容（chunk / wiki 页）内嵌图片用。与租户级 /files 不同，本路由
 * 消费 RequireKBAccess 的<b>精确 KB grant</b>：文件必须属于 KB 属主租户、在
 * {@code exports/} 命名空间下、且有该 KB 内存活文档的显式绑定
 * （IsReferencedByKnowledgeBase——文本提到 handle 不算所有权证据）。</p>
 * → AllowFileServeAPIKey → g.Viewer()（RbacInterceptor 规则）→ KBAccessRead
 * → ResolveKBFile → serveAuthorizedFile。</p>
 */
@RestController
@ApiResult
@Tag(name = "Knowledge")
public class KnowledgeBaseFileProxyController {

    private final ChunkAccessGuard kbGuard;
    private final FileProxyPort fileProxy;
    /** 进程级文件 URL 端口（契约）：local:// 引用在有 APP_EXTERNAL_URL 时会签成 /r/{token}。 */
    private final FileUrlPort fileUrlPort;

    public KnowledgeBaseFileProxyController(ChunkAccessGuard kbGuard, FileProxyPort fileProxy,
            FileUrlPort storageUrlDefaultFileService) {
        this.kbGuard = kbGuard;
        this.fileProxy = fileProxy;
        this.fileUrlPort = storageUrlDefaultFileService;
    }

    /** filePath → 可直接给播放器的 http(s) URL（未配置 APP_EXTERNAL_URL 时 400）。 */
    private Map<String, Object> mintSignedUrl(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            throw new BizException(AppError.badRequest("signed url unavailable"));
        }
        String url = fileUrlPort.getFileURL(filePath);
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            throw new BizException(AppError.badRequest("signed url unavailable"));
        }
        return Map.of("url", url, "expiresInSeconds", 7200);
    }

    /**
     * 给浏览器播放器用的签名 URL（{@code /r/{token}}，12 小时）。
     *
     * <p>视频/音频不再走 {@code /files} 下载成 blob：那样是整份下载、不能拖动进度条，
     * 而且预览与分块会各下载一次。签名 URL 支持 Range（206），同一文件在有效期内
     * token 恒定 → 浏览器缓存可直接复用。</p>
     */
    @GetMapping("/api/v1/knowledge-bases/{id}/files/signed-url")
    public Map<String, Object> signedUrl(@PathVariable("id") String id,
            @RequestParam("filePath") String filePath) {
        var kb = kbGuard.requireKbAccess(id);
        long tenantId = kb == null ? 0L : kb.getTenantId();
        String prefix = "local://" + tenantId + "/exports/";
        if (tenantId <= 0 || !filePath.startsWith(prefix)) {
            throw new BizException(AppError.forbidden("file does not belong to this knowledge base"));
        }
        return mintSignedUrl(filePath);
    }

    @RequestMapping(value = "/api/v1/knowledge-bases/{id}/files", method = RequestMethod.HEAD)
    public void filesHead(@PathVariable("id") String id, HttpServletResponse response)
            throws IOException {
        FileServeHttp.writeGinNoRoute(response);
    }

    @GetMapping("/api/v1/knowledge-bases/{id}/files")
    public void files(@PathVariable("id") String id, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        // AllowFileServeAPIKey 由 AllowFileServeAPIKeyInterceptor 承担（WebConfig 接线，
        // found" / 403 "Permission denied..." 信封（ChunkAccessGuard 内逐字实现）
        // 闸门异常**不**在此吞掉：它走全局信封（黄金契约 w5c-kbfiles-missing-kb.json 锁定形态）
        var kb = kbGuard.requireKbAccess(id);
        fileProxy.serveKbFile(kb == null ? null : kb.getTenantId(), id, request, response);
    }
}
