package com.ragagent.knowledge.service.image;

import com.ragagent.contract.storage.ResourceCatalogPort;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.repository.ChunkRepository;
import com.ragagent.knowledge.storage.TenantFileStorage;
import com.ragagent.platform.knowledge.ChunkSearchUtil;
import com.ragagent.platform.knowledge.ImageRefRewriter;
import com.ragagent.platform.text.ContentTypeByFilename;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 解析抽取图片的落点：写租户存储后端的 {@code exports} 区并登记资源、绑定属主，
 * 正文里留 provider 形引用（{@code local://} / {@code minio://} 等）。
 *
 * <p>知识库文件代理要求「exports 命名空间 + 绑定到本篇知识」双条件，缺一则取图 403；
 * 落点与聊天链一致（同一 exports 命名空间），克隆与清理按正文引用即可搬运/回收。</p>
 */
@Component
public class ExtractedImageStore {

    private static final Logger log = LoggerFactory.getLogger(ExtractedImageStore.class);

    /**
     * 整行图片引用：目标部分用贪心 {@code .+} 取到行末最后一个 {@code )}，
     * 因此文件名里的半角括号（{@code 埃安(AION)x.jpg}）不会把链接截断。
     */
    private static final Pattern IMAGE_LINE = Pattern.compile(
            "(?m)^(\\s*!\\[[^\\]]*\\]\\()(.+)(\\)\\s*)$");

    /** 是否为相对引用（既无 {@code scheme://} 也不以 {@code /} 开头）。 */
    private static final Pattern ABSOLUTE_REF =
            Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*://.*");

    /** 抽取图片的落点标记：只处理 exports 区的引用（用户手写的外链不搬运、不回收）。 */
    private static final String EXPORTS_SEGMENT = "/exports/";

    private final TenantFileStorage fileStorage;
    private final ResourceCatalogPort resourceCatalog;
    private final ChunkRepository chunkRepository;
    private final RemoteImageRefs remoteImageRefs;

    public ExtractedImageStore(TenantFileStorage fileStorage, ResourceCatalogPort resourceCatalog,
            ChunkRepository chunkRepository, RemoteImageRefs remoteImageRefs) {
        this.fileStorage = fileStorage;
        this.resourceCatalog = resourceCatalog;
        this.chunkRepository = chunkRepository;
        this.remoteImageRefs = remoteImageRefs;
    }

    /** 落盘 + 绑定 + 改写正文引用；返回改写后的 markdown。 */
    public String storeAndRewrite(Knowledge k, List<ImageRefRewriter.RefRow> rows, String markdown) {
        // 网页正文里的外链图片不在 docreader imageRefs 里，导入时补下载（失败保留原链接）
        List<ImageRefRewriter.RefRow> merged = new ArrayList<>(
                rows == null ? List.of() : rows);
        merged.addAll(remoteImageRefs.collect(markdown, k.getSource()));
        long tenantId = k.getTenantId() == null ? 0L : k.getTenantId();
        if (tenantId == 0) {
            log.debug("skip storing extracted images: knowledge {} has no tenant", k.getId());
            return markdown;
        }
        if (merged.isEmpty()) {
            // 解析器没给 imageRefs：图片类型文档仍要用源文件兜底改写相对引用
            return rewriteSelfImageRefs(k, tenantId, markdown);
        }
        // 来源文件本身是图片格式时保留原图（不做小图标过滤）
        boolean filterIcons = !ContentTypeByFilename.isImageExt(k.getFileType());
        ImageRefRewriter.Result result = ImageRefRewriter.rewrite(markdown, merged, filterIcons,
                (data, fileName) -> storeOne(tenantId, k.getId(), data, fileName,
                        mimeTypeOf(fileName)));
        if (!result.stored().isEmpty()) {
            log.info("[ImageStore] stored {} extracted images for knowledge {}",
                    result.stored().size(), k.getId());
        }
        // 直接上传的图片：解析器可能只给相对引用（名字含括号时链接还会被截断），
        // 这里用源文件本身兜底——落 exports 并换成可服务引用，保证前端能显示、OCR 能识别。
        return rewriteSelfImageRefs(k, tenantId, result.markdown());
    }

    /** 把图片类型文档正文里的相对图片引用换成「源文件落 exports 后」的引用。 */
    private String rewriteSelfImageRefs(Knowledge k, long tenantId, String markdown) {
        if (markdown == null || markdown.isEmpty()
                || !ContentTypeByFilename.isImageExt(k.getFileType())) {
            return markdown;
        }
        Matcher m = IMAGE_LINE.matcher(markdown);
        StringBuilder out = new StringBuilder();
        String servingRef = null;
        while (m.find()) {
            String target = m.group(2).trim();
            if (target.isEmpty() || target.startsWith("/") || ABSOLUTE_REF.matcher(target).matches()) {
                continue;
            }
            if (servingRef == null) {
                try {
                    byte[] self = fileStorage.read(tenantId, k.getFilePath());
                    servingRef = storeOne(tenantId, k.getId(), self, k.getFileName(),
                            mimeTypeOf(k.getFileName()));
                } catch (RuntimeException e) {
                    log.warn("failed to store source image for knowledge {}", k.getId(), e);
                }
                if (servingRef == null || servingRef.isEmpty()) {
                    return markdown;
                }
                log.info("[ImageStore] rewrote source image ref for knowledge {}", k.getId());
            }
            m.appendReplacement(out, Matcher.quoteReplacement(
                    m.group(1) + servingRef + ")"));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * 克隆/跨库搬运：把正文引用的抽取图片复制到目标知识（跨租户安全），返回改写后的正文。
     *
     * <p>逐图读原字节 → 写目标租户 exports → 登记并绑定到新文档；单图失败保留原引用。</p>
     */
    public String copyImagesTo(long sourceTenantId, String content,
            long targetTenantId, String targetKnowledgeId) {
        if (content == null || content.isEmpty() || targetTenantId == 0) {
            return content;
        }
        Set<String> refs = ChunkSearchUtil.imageURLsInContent(content);
        if (refs.isEmpty()) {
            return content;
        }
        Map<String, String> copies = new HashMap<>();
        String out = content;
        for (String ref : refs) {
            if (!isExtractedImage(ref)) {
                continue;
            }
            String copied = copies.get(ref);
            if (copied == null) {
                try {
                    byte[] data = fileStorage.read(sourceTenantId, ref);
                    String fileName = fileNameOf(ref);
                    copied = storeOne(targetTenantId, targetKnowledgeId, data, fileName,
                            mimeTypeOf(fileName));
                    copies.put(ref, copied);
                } catch (RuntimeException e) {
                    log.warn("copy extracted image {} failed", ref, e);
                    continue;
                }
            }
            out = out.replace(ref, copied);
        }
        return out;
    }

    /** 回收某篇知识当前分块引用的抽取图片（重解析 / 删除前调用）。 */
    public void releaseFor(long tenantId, String knowledgeId) {
        if (tenantId == 0 || knowledgeId == null || knowledgeId.isEmpty()) {
            return;
        }
        List<String> contents;
        try {
            contents = chunkRepository.listActiveByKnowledgeId(tenantId, knowledgeId)
                    .stream().map(Chunk::getContent).toList();
        } catch (RuntimeException e) {
            log.warn("load chunks for image release failed: knowledge={}", knowledgeId, e);
            return;
        }
        releaseExtractedImages(tenantId, contents);
    }

    /** 回收这些正文引用的抽取图片：删物理文件 + 资源行软删；幂等、单张失败只告警。 */
    public void releaseExtractedImages(long tenantId, List<String> contents) {
        if (contents == null || contents.isEmpty()) {
            return;
        }
        Set<String> refs = new LinkedHashSet<>();
        for (String content : contents) {
            refs.addAll(ChunkSearchUtil.imageURLsInContent(content));
        }
        for (String ref : refs) {
            if (!isExtractedImage(ref)) {
                continue;
            }
            try {
                fileStorage.deleteExportBytes(tenantId, ref);
                resourceCatalog.markDeletedByLocation(tenantId, ref);
            } catch (RuntimeException e) {
                log.warn("release extracted image {} failed", ref, e);
            }
        }
    }

    /** 单张落盘 + 登记 + 绑定，返回正文引用（provider 形态）。 */
    private String storeOne(long tenantId, String knowledgeId, byte[] data,
            String fileName, String mimeType) {
        String ref = fileStorage.saveExportBytes(tenantId, fileName, data);
        String handle = resourceCatalog.register(tenantId, ref, new ResourceCatalogPort.ResourceMeta(
                "image", mimeType, fileName, data.length, "", false));
        resourceCatalog.bind(handle, "knowledge", knowledgeId, "attachment");
        return ref;
    }

    /** 只认 exports 区的引用（本地与云同一命名空间）。 */
    private static boolean isExtractedImage(String ref) {
        return ref != null && ref.contains(EXPORTS_SEGMENT);
    }

    /** 取引用末段作为文件名（查询串忽略）。 */
    private static String fileNameOf(String ref) {
        String path = ref == null ? "" : ref;
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String mimeTypeOf(String fileName) {
        return ContentTypeByFilename.getByExt(extOf(fileName));
    }

    private static String extOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot);
    }
}
