package com.ragagent.datasource.service;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.contract.error.AppError;
import com.ragagent.contract.error.BizException;
import com.ragagent.contract.error.ErrorCode;
import com.ragagent.contract.knowledge.KnowledgeBaseRowView;
import com.ragagent.contract.knowledge.KnowledgeCatalogPort;
import com.ragagent.contract.knowledge.KnowledgeIngest;
import com.ragagent.contract.knowledge.KnowledgeParseStatus;
import com.ragagent.contract.knowledge.KnowledgeRowView;
import com.ragagent.contract.knowledge.error.DuplicateKnowledgeException;
import com.ragagent.platform.http.UrlBytesFetcher;
import com.ragagent.platform.persistence.DatabaseDialects;
import com.ragagent.platform.security.SsrfGuard;
import com.ragagent.platform.web.JsonMappers;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/**
 * {@link KnowledgeBridge} 的生产实现：直接用 knowledge 模块的
 * {@code KnowledgeMapper} / {@code KnowledgeBaseMapper} / {@code LocalStorageService}
 * 与进程内处理队列。
 *
 * <h2>为什么不复用 {@code KnowledgeService}</h2>
 * <p>它的每个读写方法都从 {@code TenantContext} 取租户（{@code getKnowledge} 还会顺手做
 * API-Key 的 KB 白名单校验），而同步跑在后台线程上——那里<b>没有</b>请求上下文，
 * 也没有"给一次调用指定租户"的重载，所以这里按同样的 SQL 语义实现了一遍
 * <b>显式传租户</b>的版本。</p>
 *
 * <h2>方言分叉：jsonb 上的 {@code ->>}</h2>
 * <p>两条查询都要在 SQL 里做 {@code metadata->>'key' = ?}（这么写才能吃到 PG 的
 * 表达式索引）。</p>
 * <ul>
 *   <li><b>PG</b>：原样下推到 SQL。</li>
 *   <li><b>H2</b>：项目测试库把 jsonb 一律承载成 {@code VARCHAR}（{@code TestSchema}
 *       的既有约定），没有 {@code ->>} 运算符 → 退化成"取本租户本知识库的未删行，
 *       在 Java 里解析 metadata 精确匹配"。分支的开关方式与
 *       {@code session.mapper.SessionRepository} 的 {@code ILIKE}/{@code NULLS LAST}
 *       一致（探测 JDBC 产品名，探测失败按 H2 走）。</li>
 * </ul>
 *
 * <h2>落库语义清单</h2>
 * <ol>
 *   <li><b>插入钩子</b>：知识行的 UUID 与
 *       {@code custom_metadata='{}'}——这里显式赋值（与 {@code KnowledgeService} 一致）。</li>
 *   <li><b>关联预加载</b>：无（{@code tags} 非表字段）。</li>
 *   <li><b>软删除</b>：所有查询显式带 {@code deleted_at IS NULL}；
 *       {@code hardDelete*} 是物理 DELETE。</li>
 *   <li><b>默认排序</b>：本类不引入排序（查询契约也没有排序）。</li>
 *   <li><b>唯一索引/外键</b>：无。</li>
 *   <li><b>自动时间戳</b>：CREATE 时显式写 {@code created_at}/{@code updated_at}。</li>
 * </ol>
 */
@Component
public class MapperKnowledgeBridge implements KnowledgeBridge {

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    /** A3-3 尾批：租户感知文件存储（本地契约不变；云 provider 租户落对象存储）。 */
    private final KnowledgeCatalogPort knowledgeCatalog;
    /** 摄取面（契约端口）：幂等锚 / 文件名推导 / 任务号 / 处理队列投递。 */
    private final KnowledgeIngest knowledgeIngest;
    /** SSRF 守卫：同步线程没有 controller 兜底，URL 抓取前必须自己校验。 */
    private final SsrfGuard ssrfGuard;
    private final boolean postgres;

    public MapperKnowledgeBridge(KnowledgeCatalogPort knowledgeCatalog,
                                 KnowledgeIngest knowledgeIngest,
                                 SsrfGuard ssrfGuard,
                                 DataSource dataSource) {
        this.knowledgeCatalog = knowledgeCatalog;
        this.knowledgeIngest = knowledgeIngest;
        this.ssrfGuard = ssrfGuard;
        this.postgres = DatabaseDialects.isPostgres(dataSource);
    }

    // ── 知识库 ───────────────────────────────────────────────────────────────

    /**
     * 只按 id 查 + {@code deleted_at IS NULL}，
     * <b>没有</b>租户条件——租户归属由调用方比对。
     */
    @Override
    public KnowledgeBaseRowView findKnowledgeBase(String kbId) {
        if (kbId == null || kbId.isEmpty()) {
        }
        return knowledgeCatalog.findKnowledgeBaseRow(kbId);
    }

    // ── 查询 ─────────────────────────────────────────────────────────────────

    @Override
    public KnowledgeRowView findByDataSourceExternalId(long tenantId, String kbId, String dataSourceId,
                                                String externalId) {
        if (postgres) {
            return knowledgeCatalog.findKnowledgeByExternalId(tenantId, kbId,
                    dataSourceId, externalId);
        }
        for (KnowledgeRowView k : listLive(tenantId, kbId)) {
            Map<String, String> md = readMetadata(k);
            if (dataSourceId.equals(md.get("datasourceId")) && externalId.equals(md.get("externalId"))) {
                return k;
            }
        }
        return null;
    }

    @Override
    public List<KnowledgeRowView> findByMetadataKeyPrefix(long tenantId, String kbId, String key, String prefix) {
        List<KnowledgeRowView> out = new ArrayList<>();
        if (key == null || key.isEmpty() || prefix == null || prefix.isEmpty()) {
            return out;
        }
        if (postgres) {
            return knowledgeCatalog.listKnowledgeByMetadataPrefix(tenantId, kbId, key, prefix);
        }
        for (KnowledgeRowView k : listLive(tenantId, kbId)) {
            String value = readMetadata(k).get(key);
            if (value != null && value.startsWith(prefix)) {
                out.add(k);
            }
        }
        return out;
    }

    // ── 写入 ─────────────────────────────────────────────────────────────────

    /**
     * 文件写入的<b>最小闭环</b>：重复内容检测 →
     * 落 Storage → 落 knowledges 行 → 交给处理队列。
     *
     * <p>见 {@link KnowledgeBridge} 的"实现边界"：文件名安全校验、多模态/问题生成配置、
     * 标签关系、按 KB 选存储引擎、处理任务载荷形态都不在这里。</p>
     */
    @Override
    public KnowledgeRowView createFromFile(long tenantId, String kbId, byte[] content, String fileName,
                                    Map<String, String> metadata, List<String> tagIds, String channel) {
        KnowledgeBaseRowView kb = findKnowledgeBase(kbId);
        if (kb == null) {
            throw new BizException(
                    AppError.notFound("knowledge base not found"));
        }
        String safeName = fileName == null || fileName.isEmpty() ? "untitled" : fileName;
        return insertFileRow(tenantId, kb, content, safeName, safeName, null, metadata, channel);
    }

    /**
 * URL 写入：**同步抓取后与文件同形**。
 *
 * <p>历史缺口：原实现只写 {@code source=url} + {@code filePath=""} 就入队，指望处理队列
 * 下载——但 worker 对非 manual 只读 {@code filePath}，空路径必被存储层拒绝（这条链是死的）。
 * 现在：SSRF 校验 → 抓取（{@link UrlBytesFetcher}，含大小上限）→ 与
 * {@link #createFromFile} 同一条落行闭环（hash / 落 Storage / 入队），行形态与普通文件一致。</p>
 *
 * <p>⚠️ <b>返回的行没有 datasource 的 metadata</b>——调用方在"新建"
 * 分支上再补一次（重复分支复用已有行，不该被重新打标）。</p>
 */
    @Override
    public KnowledgeRowView createFromUrl(long tenantId, String kbId, String url, String fileName,
                                   String title, List<String> tagIds, String channel) {
        KnowledgeBaseRowView kb = findKnowledgeBase(kbId);
        if (kb == null) {
            throw new BizException(
                    AppError.notFound("knowledge base not found"));
        }
        byte[] content = UrlBytesFetcher.fetch(url, ssrfGuard::validateURLForSSRF);
        String name = fileName != null && !fileName.isEmpty()
                ? fileName
                : knowledgeIngest.fileNameFromUrl(url);
        String rowTitle = title != null && !title.isEmpty() ? title : name;
        return insertFileRow(tenantId, kb, content, name, rowTitle, url, null, channel);
    }

    /**
     * 文件/URL 共用的落行闭环：重复内容检测 → 落 Storage → 落 knowledges 行 → 交给处理队列。
     *
     * @param source URL 型条目写原始 URL；文件型传 {@code null}（不写 source）
     */
    private KnowledgeRowView insertFileRow(long tenantId, KnowledgeBaseRowView kb, byte[] content, String fileName,
                                    String rowTitle, String source, Map<String, String> metadata,
                                    String channel) {
        String fileType = fileTypeOf(fileName);
        String hash = knowledgeIngest.contentHash(content);

        // 重复检测：同库同哈希同类型即重复
        KnowledgeRowView dup = knowledgeCatalog.findKnowledgeByHash(kb.getId(), hash, fileType);
        if (dup != null) {
            throw new DuplicateKnowledgeException(
                    dup, ErrorCode.KNOWLEDGE_DUPLICATE_FILE,
                    "文件已存在（相同内容）");
        }

        KnowledgeRowView k = newKnowledge(tenantId, kb, rowTitle, fileType, channel);
        k.setFileName(fileName);
        if (source != null) {
            k.setSource(source);
        }
        k.setFileSize((long) content.length);
        k.setFileHash(hash);
        k.setFilePath(knowledgeCatalog.saveKnowledgeFile(tenantId, k.getId(), fileName, content));
        k.setMetadata(metadataNode(metadata));
        knowledgeCatalog.insertKnowledge(k);
        knowledgeIngest.enqueueProcessing(k.getId());
        return k;
    }

    @Override
    public void attachMetadata(KnowledgeRowView knowledge, Map<String, String> metadata) {
        knowledgeCatalog.updateKnowledgeMetadataRow(knowledge, metadata);
        knowledge.setMetadata(metadataNode(metadata));
    }

    // ── 删除 ─────────────────────────────────────────────────────────────────

    /** 软删知识行 + 软删分片 + 清文件。 */
    @Override
    public void softDelete(long tenantId, String knowledgeId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        knowledgeCatalog.softDeleteKnowledgeFull(knowledgeId, tenantId, now);
    }

    @Override
    public void softDeleteList(long tenantId, List<String> knowledgeIds) {
        if (knowledgeIds == null || knowledgeIds.isEmpty()) {
            return;
        }
        for (String id : knowledgeIds) {
            softDelete(tenantId, id);
        }
    }

    /** 物理删单条。 */
    @Override
    public void hardDelete(long tenantId, String knowledgeId) {
        knowledgeCatalog.hardDeleteKnowledge(knowledgeId, tenantId);
    }

    @Override
    public void hardDeleteList(long tenantId, List<String> knowledgeIds) {
        if (knowledgeIds == null || knowledgeIds.isEmpty()) {
            return;
        }
        knowledgeCatalog.hardDeleteKnowledgeBatch(tenantId, knowledgeIds);
    }

    // ── 内部 ─────────────────────────────────────────────────────────────────

    /** 非 PG 路径的候选集：本租户 + 本知识库 + 未软删。 */
    private List<KnowledgeRowView> listLive(long tenantId, String kbId) {
        return knowledgeCatalog.listKnowledgeLive(tenantId, kbId);
    }

    /** 从 {@code metadata} 列读出字符串 map。 */
    private static Map<String, String> readMetadata(KnowledgeRowView k) {
        Map<String, String> out = new LinkedHashMap<>();
        com.fasterxml.jackson.databind.JsonNode md = JsonMappers.toJsonNode(k.getMetadata());
        if (md == null || !md.isObject()) {
            return out;
        }
        md.fields().forEachRemaining(e -> {
            if (e.getValue() != null && e.getValue().isTextual()) {
                out.put(e.getKey(), e.getValue().asText());
            }
        });
        return out;
    }

    /** 把字符串 map 序列化成 {@code metadata} 列的 JSON（键按给定序写出、不排序）。 */
    private static com.fasterxml.jackson.databind.JsonNode metadataNode(Map<String, String> metadata) {
        ObjectNode node = MAPPER.createObjectNode();
        if (metadata != null) {
            for (Map.Entry<String, String> e : metadata.entrySet()) {
                node.put(e.getKey(), e.getValue() == null ? "" : e.getValue());
            }
        }
        return node;
    }

    /**
     * 构造一条新知识行（字段集与 {@code KnowledgeService.newKnowledge} 相同，但<b>显式传租户</b>）。
     */
    private static KnowledgeRowView newKnowledge(long tenantId, KnowledgeBaseRowView kb, String title,
                                          String fileType, String channel) {
        KnowledgeRowView k = new KnowledgeRowView();
        k.setId(UUID.randomUUID().toString());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        k.setCreatedAt(now);
        k.setUpdatedAt(now);
        k.setTenantId(tenantId);
        k.setKnowledgeBaseId(kb.getId());
        k.setType("file");
        k.setTitle(title == null ? "" : title);
        k.setFileName(title);
        k.setFileType(fileType);
        k.setParseStatus(KnowledgeParseStatus.PARSE_PENDING);
        k.setEnableStatus("disabled");
        k.setEmbeddingModelId(kb.getEmbeddingModelId());
        k.setChannel(channel);
        k.setFolderPath("");
        k.setCustomMetadata(MAPPER.createObjectNode());
        return k;
    }

    /** 取最后一个点之后的小写扩展名（无点则空串）。 */
    static String fileTypeOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** LIKE 转义：{@code \}、{@code %}、{@code _}。 */
}
