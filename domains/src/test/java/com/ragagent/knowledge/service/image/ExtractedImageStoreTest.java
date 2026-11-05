package com.ragagent.knowledge.service.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ragagent.contract.storage.ResourceCatalogPort;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.repository.ChunkRepository;
import com.ragagent.knowledge.storage.TenantFileStorage;
import com.ragagent.platform.knowledge.ImageRefRewriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 抽取图片：落 exports 区（按租户存储后端）→ 登记资源 → 绑定知识 → 正文改写成 provider 引用；
 * 克隆按引用搬运、重解析/删除按引用回收。
 */
class ExtractedImageStoreTest {

    private final TenantFileStorage fileStorage = mock(TenantFileStorage.class);
    private final ChunkRepository chunkRepository = mock(ChunkRepository.class);
    private final List<String> registered = new ArrayList<>();
    private final List<String> bound = new ArrayList<>();
    private final List<String> markedDeleted = new ArrayList<>();
    private int fileSeq;
    private int handleSeq;

    private final ResourceCatalogPort catalog = new ResourceCatalogPort() {
        @Override
        public Optional<ResourceView> resolve(String reference) {
            return Optional.empty();
        }

        @Override
        public void bind(String reference, String ownerType, String ownerId, String relation) {
            bound.add(ownerType + ":" + ownerId + ":" + relation + ":" + reference);
        }

        @Override
        public String register(long tenantId, String physicalPath, ResourceMeta meta) {
            registered.add(tenantId + "|" + physicalPath + "|" + meta.kind() + "|" + meta.mimeType());
            return "resource://h" + (++handleSeq);
        }

        @Override
        public void markDeletedByLocation(long tenantId, String physicalPath) {
            markedDeleted.add(physicalPath);
        }
    };

    private final ExtractedImageStore store =
            new ExtractedImageStore(fileStorage, catalog, chunkRepository, mock(RemoteImageRefs.class));

    @BeforeEach
    void stubSaves() {
        fileSeq = 0;
        handleSeq = 0;
        when(fileStorage.saveExportBytes(anyLong(), anyString(), any(byte[].class)))
                .thenAnswer(invocation -> "local://1/exports/img" + (++fileSeq) + ".jpg");
    }

    @Test
    void storesImagesBindsToKnowledgeAndRewritesMarkdown() {
        Knowledge k = knowledge("k1", "pdf");
        ImageRefRewriter.RefRow image = new ImageRefRewriter.RefRow(
                "images/p3_img1.jpg", "p3_img1.jpg", "image/jpeg", pngBytes(200, 150));
        ImageRefRewriter.RefRow icon = new ImageRefRewriter.RefRow(
                "images/icon.png", "icon.png", "image/png", pngBytes(48, 48));

        String markdown = store.storeAndRewrite(k,
                List.of(image, icon), "![a](images/p3_img1.jpg)\n![icon](images/icon.png)");

        // 命中图改写为 exports 区引用；小图标（两轴 < 64）过滤并保留原引用
        assertThat(markdown).isEqualTo("![a](local://1/exports/img1.jpg)\n![icon](images/icon.png)");
        assertThat(registered).anyMatch(v -> v.startsWith("1|local://1/exports/img1.jpg|image|image/jpeg"));
        assertThat(bound).containsExactly("knowledge:k1:attachment:resource://h1");
    }

    @Test
    void imageSourceDocumentKeepsSmallImages() {
        Knowledge k = knowledge("k2", "png");
        ImageRefRewriter.RefRow small = new ImageRefRewriter.RefRow(
                "images/tiny.png", "tiny.png", "image/png", pngBytes(32, 32));

        String markdown = store.storeAndRewrite(k, List.of(small), "![tiny](images/tiny.png)");

        assertThat(markdown).isEqualTo("![tiny](local://1/exports/img1.jpg)");
        assertThat(bound).containsExactly("knowledge:k2:attachment:resource://h1");
    }

    @Test
    void noImageRefsOrTenantLeaveMarkdownUntouched() {
        assertThat(store.storeAndRewrite(knowledge("k3", "pdf"), List.of(), "# 标题"))
                .isEqualTo("# 标题");
        assertThat(store.storeAndRewrite(knowledge("k3", "pdf"), null, "# 标题"))
                .isEqualTo("# 标题");

        Knowledge noTenant = knowledge("k4", "pdf");
        noTenant.setTenantId(null);
        ImageRefRewriter.RefRow image = new ImageRefRewriter.RefRow(
                "images/a.png", "a.png", "image/png", pngBytes(200, 150));
        assertThat(store.storeAndRewrite(noTenant, List.of(image), "![a](images/a.png)"))
                .isEqualTo("![a](images/a.png)");
        assertThat(registered).isEmpty();
    }

    @Test
    void copyImagesToRewritesReferencesAndBindsTarget() {
        when(fileStorage.read(1L, "local://1/exports/src.jpg")).thenReturn(pngBytes(200, 150));

        String content = store.copyImagesTo(1L,
                "![a](local://1/exports/src.jpg) ![ext](https://example.com/x.png)",
                2L, "k9");

        // 抽取图片搬到目标租户新文档；外链不动
        assertThat(content).contains("local://1/exports/img1.jpg").contains("https://example.com/x.png");
        assertThat(bound).containsExactly("knowledge:k9:attachment:resource://h1");
    }

    @Test
    void copyFailureKeepsOriginalReference() {
        when(fileStorage.read(eq(1L), anyString())).thenThrow(new IllegalStateException("gone"));

        String content = store.copyImagesTo(1L, "![a](local://1/exports/src.jpg)", 2L, "k9");

        assertThat(content).isEqualTo("![a](local://1/exports/src.jpg)");
        assertThat(bound).isEmpty();
    }

    @Test
    void releaseDeletesExportsFilesAndResourceRows() {
        store.releaseExtractedImages(1L, List.of(
                "![a](local://1/exports/a.jpg) ![b](minio://bucket/1/exports/b.jpg)",
                "![c](https://example.com/c.png)"));

        verify(fileStorage).deleteExportBytes(1L, "local://1/exports/a.jpg");
        verify(fileStorage).deleteExportBytes(1L, "minio://bucket/1/exports/b.jpg");
        verify(fileStorage, never()).deleteExportBytes(anyLong(), eq("https://example.com/c.png"));
        assertThat(markedDeleted).containsExactlyInAnyOrder(
                "local://1/exports/a.jpg", "minio://bucket/1/exports/b.jpg");
    }

    @Test
    void releaseForScansKnowledgeChunks() {
        Chunk c = new Chunk();
        c.setContent("![a](local://1/exports/a.jpg)");
        when(chunkRepository.listActiveByKnowledgeId(1L, "k1")).thenReturn(List.of(c));

        store.releaseFor(1L, "k1");

        verify(fileStorage).deleteExportBytes(1L, "local://1/exports/a.jpg");
    }

    private static Knowledge knowledge(String id, String fileType) {
        Knowledge k = new Knowledge();
        k.setId(id);
        k.setTenantId(1L);
        k.setFileType(fileType);
        return k;
    }


    @Test
    void imageSourceWithoutRefsRewritesRelativeLinkWithSourceBytes() {
        Knowledge k = knowledge("k5", "jpg");
        k.setFileName("埃安(AION)i60图片卖点08.jpg");
        k.setFilePath("1/k5/埃安(AION)i60图片卖点08.jpg");
        when(fileStorage.read(1L, k.getFilePath())).thenReturn(pngBytes(200, 150));

        String markdown = store.storeAndRewrite(k, List.of(),
                "![埃安(AION)i60图片卖点08.jpg](images/埃安(AION)i60图片卖点08.jpg)");

        // 解析器没给 imageRefs，也要把相对引用换成可服务的 exports 引用
        assertThat(markdown).isEqualTo("![埃安(AION)i60图片卖点08.jpg](local://1/exports/img1.jpg)");
        assertThat(bound).containsExactly("knowledge:k5:attachment:resource://h1");
    }

    @Test
    void servedRefsAreNotRewrittenTwice() {
        Knowledge k = knowledge("k6", "jpg");
        k.setFilePath("1/k6/a.jpg");

        String markdown = store.storeAndRewrite(k, List.of(), "![a](local://1/exports/a.jpg)");

        assertThat(markdown).isEqualTo("![a](local://1/exports/a.jpg)");
        assertThat(registered).isEmpty();
    }

    @Test
    void unreadableSourceKeepsRelativeLink() {
        Knowledge k = knowledge("k7", "jpg");
        k.setFilePath("1/k7/a.jpg");
        when(fileStorage.read(1L, k.getFilePath())).thenThrow(new IllegalStateException("missing"));

        String markdown = store.storeAndRewrite(k, List.of(), "![a](images/a.jpg)");

        assertThat(markdown).isEqualTo("![a](images/a.jpg)");
    }

    /** 生成可解码的 PNG（图标过滤需要真实尺寸）。 */
    private static byte[] pngBytes(int width, int height) {
        java.awt.image.BufferedImage image =
                new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
        try (java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            javax.imageio.ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
