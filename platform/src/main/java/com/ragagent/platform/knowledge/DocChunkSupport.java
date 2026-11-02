package com.ragagent.platform.knowledge;

import com.ragagent.contract.agent.SearchAuth;
import com.ragagent.contract.knowledge.ChunkFacts;
import com.ragagent.platform.web.JsonMappers;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * wiki_read_source_doc / get_document_info / list_knowledge_chunks 三工具共享的
 * 接缝与图片富化（chunk 内容富化、图片 markdown 组装、批量图片信息收集的消费侧）。
 *
 * <p>自 {@code agent-engine} 的 {@code agent.tool} 迁入——
 * 它与 {@code ImageInfoMatchUtil}/{@code ChunkSearchUtil} 同族：**对契约形状做技术性组装**
 * （markdown 片段、metadata 映射、图片信息解析），不含业务判定，故归 platform.knowledge。</p>
 *
 * <p>原嵌套 record {@code ImageInfoRow}/{@code KnowledgeInfoRow} 随之改名 *Row（platform 面
 * 不得出现 View/Port/Request/Response/Payload/Facts 这类业务形状命名—— 红旗口径）。</p>
 *
 * <p>seam：</p>
 * <ul>
 *   <li>{@link KnowledgeInfoReader}：按 ID 取知识 + 取知识标签。</li>
 *   <li>{@link PagedChunks}：按知识分页取 chunk
 *       （text/faq 类型 + enabled 过滤已在服务端语义内；收窄为整页返回）。</li>
 *   <li>{@link ImageInfoCollector}：批量收集图片信息
 *       （返回 parent chunk ID → 合并后 ImageInfo JSON 串；null 收集器 = 跳过富化）。
 *       其内部合并实现属基础设施侧。</li>
 * </ul>
 */
public final class DocChunkSupport {

    private DocChunkSupport() {
    }

    /** knowledge 文档富视图（跨模块被用字段）。 */
    public record KnowledgeInfoRow(String id, long tenantId, String knowledgeBaseId, String title,
            String description, String type, String source, String fileName, String fileType,
            long fileSize, String parseStatus, Map<String, Object> metadata) {
    }

    /**
     * 知识读取接缝（按 ID 取知识 / 取标签）。
     * 返回 null = empty result（记入失败文案）。
     * 不继承 {@code contract.agent.SearchAuth.KnowledgeScopeReader}（record 无子类型关系），
     * 传给 SearchAuth 时用 {@link #asScopeReader} 适配。
     */
    public interface KnowledgeInfoReader {
        KnowledgeInfoRow byIdOnly(String knowledgeId);

        Map<String, List<SearchAuth.TagView>> fetchTags(List<String> knowledgeIds);
    }

    /** KnowledgeInfoReader → SearchAuth.KnowledgeScopeReader 适配（授权路径用）。 */
    public static SearchAuth.KnowledgeScopeReader asScopeReader(KnowledgeInfoReader reader) {
        return new SearchAuth.KnowledgeScopeReader() {
            @Override
            public SearchAuth.KnowledgeView byIdOnly(String knowledgeId) {
                KnowledgeInfoRow k = reader == null ? null : reader.byIdOnly(knowledgeId);
                if (k == null) {
                    return null;
                }
                return new SearchAuth.KnowledgeView(k.id(), k.knowledgeBaseId(), k.title(), k.fileName());
            }

            @Override
            public Map<String, List<SearchAuth.TagView>> fetchTags(List<String> knowledgeIds) {
                return reader == null ? null : reader.fetchTags(knowledgeIds);
            }
        };
    }

    /** 一页 chunk 结果。 */
    public record ChunkPage(List<ChunkFacts> chunks, long total) {
    }

    /** 分页取 chunk 接缝（为 null = 服务不可用）。 */
    public interface PagedChunks {
        ChunkPage listPaged(long tenantId, String knowledgeId, int page, int pageSize);

        /**
         * 精确范围：{@code offset} 是块起点，不做页对齐。
         *
         * <p>默认实现退化为按页取整（{@code offset/limit+1}）；生产实现直接下推
         * offset/limit，避免调用方写 offset=12/limit=5 却从第 10 块开始。</p>
         */
        default ChunkPage listRange(long tenantId, String knowledgeId, int offset, int limit) {
            int size = Math.max(limit, 1);
            return listPaged(tenantId, knowledgeId, Math.max(offset, 0) / size + 1, size);
        }
    }

    /** 批量收集图片信息接缝。 */
    public interface ImageInfoCollector {
        Map<String, String> collect(long tenantId, List<String> chunkIds);
    }

    // ── ImageInfo ──

    /** 图片信息三字段：URL/Caption/OCRText。 */
    public record ImageInfoRow(String url, String caption, String ocrText) {
    }

    /** 独立 ObjectMapper（主源码不可依赖测试侧的 RecordingSupport）。 */
    private static final class ImageJson {
        static final com.fasterxml.jackson.databind.ObjectMapper PLAIN =
                JsonMappers.lenient();

        private ImageJson() {
        }
    }

    /** 解析 ImageInfo JSON 数组：失败或空 → null。 */
    public static List<ImageInfoRow> parseImageInfoList(String imageInfoJson) {
        if (imageInfoJson == null || imageInfoJson.isEmpty()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = ImageJson.PLAIN.readTree(imageInfoJson);
            if (!node.isArray() || node.isEmpty()) {
                return null;
            }
            List<ImageInfoRow> out = new ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode item : node) {
                String url = item.path("url").asText("");
                String originalUrl = item.path("originalUrl").asText("");
                if (url.isEmpty()) {
                    url = originalUrl;
                }
                out.add(new ImageInfoRow(url,
                        item.path("caption").asText(""), item.path("ocrText").asText("")));
            }
            return out;
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /** caption/OCR 的 blockquote 组装；空 → ""。 */
    public static String buildImageInfoMarkdownMetadata(ImageInfoRow img) {
        if (img == null) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        String caption = img.caption() == null ? "" : img.caption().trim();
        if (!caption.isEmpty()) {
            lines.add("**Image caption:** " + caption);
        }
        String ocr = img.ocrText() == null ? "" : img.ocrText().trim();
        if (!ocr.isEmpty()) {
            lines.add("**Image text (OCR):** " + ocr);
        }
        if (lines.isEmpty()) {
            return "";
        }
        return "> " + String.join("\n\n", lines).replace("\n", "\n> ");
    }

    /**
     * 图片 markdown 组装：URL 原样保留；alt = caption 按空白折叠，
     * 空 → "image"，反斜杠与方括号转义。
     */
    public static String buildImageInfoMarkdownWithURL(String url, ImageInfoRow img) {
        if (img == null) {
            return "";
        }
        url = url == null ? "" : url.trim();
        String metadata = buildImageInfoMarkdownMetadata(img);
        if (url.isEmpty()) {
            return metadata;
        }
        String alt = collapseWhitespace(img.caption());
        if (alt.isEmpty()) {
            alt = "image";
        }
        alt = alt.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]");
        String image = "![" + alt + "](" + url + ")";
        if (metadata.isEmpty()) {
            return image;
        }
        return image + "\n\n" + metadata;
    }

    /** 空白折叠为单空格。 */
    static String collapseWhitespace(String s) {
        if (s == null) {
            return "";
        }
        String trimmed = s.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean inWs = false;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (Character.isWhitespace(c)) {
                inWs = true;
            } else {
                if (inWs) {
                    sb.append(' ');
                    inWs = false;
                }
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 内容富化：content + 每个非空图片 markdown（各前置 "\n"）。 */
    public static String enrichChunkContent(ChunkFacts c) {
        String content = c.content() == null ? "" : c.content();
        String imageInfo = c.imageInfo();
        if (imageInfo != null && !imageInfo.isEmpty()) {
            List<ImageInfoRow> infos = parseImageInfoList(imageInfo);
            if (infos != null && !infos.isEmpty()) {
                StringBuilder imgBuilder = new StringBuilder();
                for (ImageInfoRow img : infos) {
                    String md = buildImageInfoMarkdownWithURL(img.url(), img);
                    if (!md.isEmpty()) {
                        imgBuilder.append('\n').append(md);
                    }
                }
                content += imgBuilder.toString();
            }
        }
        return content;
    }

    /**
     * 给 ImageInfo 为空的父 chunk 补图（已有非空的跳过）。
     * collector 为 null → 直接返回（服务不可用语义）。
     */
    public static void enrichChunkImageInfo(ImageInfoCollector collector, long tenantId, List<ChunkFacts> chunks) {
        if (chunks == null || chunks.isEmpty() || collector == null) {
            return;
        }
        List<String> ids = new ArrayList<>();
        for (ChunkFacts c : chunks) {
            String info = c.imageInfo();
            if ((info == null || info.isEmpty()) && c.id() != null && !c.id().isEmpty()) {
                ids.add(c.id());
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        Map<String, String> infoMap = collector.collect(tenantId, ids);
        if (infoMap == null || infoMap.isEmpty()) {
            return;
        }
        for (int i = 0; i < chunks.size(); i++) {
            ChunkFacts c = chunks.get(i);
            String info = c.imageInfo();
            if (info != null && !info.isEmpty()) {
                continue;
            }
            String merged = infoMap.get(c.id());
            if (merged != null && !merged.isEmpty()) {
                chunks.set(i, withImageInfo(c, merged));
            }
        }
    }

    /** 复制并替换 imageInfo（L1 载荷不可变，富化走"复制-替换"）。 */
    public static ChunkFacts withImageInfo(ChunkFacts c, String imageInfo) {
        return new ChunkFacts(c.id(), c.knowledgeId(), c.knowledgeBaseId(), c.content(), c.chunkType(),
                c.indexStatus(), c.enabled(), c.chunkIndex(), c.startAt(), c.endAt(), c.contentRevision(),
                c.parentChunkId(), c.preChunkId(), c.nextChunkId(), c.relationChunks(), c.metadata(),
                imageInfo);
    }

    /** metadata 视图：空 metadata → 空 map；非字符串值按标量形态转文本；解析失败 → null。 */
    public static Map<String, String> knowledgeMetadataMap(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : metadata.entrySet()) {
            out.put(e.getKey(), valueText(e.getValue()));
        }
        return out;
    }

    /** 常见标量的输出形态（metadata 值用；嵌套容器的序不保证）。 */
    public static String valueText(Object v) {
        if (v == null) {
            return "<nil>";
        }
        if (v instanceof String s) {
            return s;
        }
        if (v instanceof Boolean b) {
            return b.toString();
        }
        if (v instanceof Double d) {
            return Double.toString(d);
        }
        if (v instanceof Integer || v instanceof Long) {
            return v.toString();
        }
        return v.toString();
    }
}
