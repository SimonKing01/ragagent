package com.ragagent.knowledge.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 上传时 fileName 里编码的目标目录要拆出来，否则目录只会挂在标题上。 */
class KnowledgePathsUploadPathTest {

    @Test
    void splitsFolderAndFileName() {
        KnowledgePaths.UploadPath p = KnowledgePaths.splitUploadPath("2026/合同/a.pdf");

        assertThat(p.folderPath()).isEqualTo("2026/合同");
        assertThat(p.fileName()).isEqualTo("a.pdf");
    }

    @Test
    void plainFileNameHasNoFolder() {
        KnowledgePaths.UploadPath p = KnowledgePaths.splitUploadPath("a.pdf");

        assertThat(p.folderPath()).isEmpty();
        assertThat(p.fileName()).isEqualTo("a.pdf");
    }

    @Test
    void normalizesFolderSegments() {
        KnowledgePaths.UploadPath p = KnowledgePaths.splitUploadPath(" /合同// 2026 / a.pdf");

        assertThat(p.folderPath()).isEqualTo("合同/2026");
        assertThat(p.fileName()).isEqualTo("a.pdf");
    }

    @Test
    void acceptsWindowsSeparators() {
        KnowledgePaths.UploadPath p = KnowledgePaths.splitUploadPath("dir\\sub\\a.pdf");

        assertThat(p.folderPath()).isEqualTo("dir/sub");
        assertThat(p.fileName()).isEqualTo("a.pdf");
    }

    @Test
    void trailingSlashFallsBackToPlaceholder() {
        KnowledgePaths.UploadPath p = KnowledgePaths.splitUploadPath("dir/");

        assertThat(p.folderPath()).isEqualTo("dir");
        assertThat(p.fileName()).isEqualTo("untitled");
    }

    @Test
    void nullTolerated() {
        KnowledgePaths.UploadPath p = KnowledgePaths.splitUploadPath(null);

        assertThat(p.folderPath()).isEmpty();
        assertThat(p.fileName()).isEqualTo("untitled");
    }

    @Test
    void titleFallsBackToFileNameWhenDisplayNameIsTheRawPath() {
        KnowledgePaths.UploadIdentity id =
                KnowledgePaths.resolveUploadIdentity("2026/合同/a.pdf", "2026/合同/a.pdf");

        assertThat(id.folderPath()).isEqualTo("2026/合同");
        assertThat(id.fileName()).isEqualTo("a.pdf");
        assertThat(id.title()).isEqualTo("a.pdf");
    }

    @Test
    void explicitDisplayNameIsKept() {
        KnowledgePaths.UploadIdentity id =
                KnowledgePaths.resolveUploadIdentity("2026/a.pdf", "采购合同 2026");

        assertThat(id.title()).isEqualTo("采购合同 2026");
    }
}
