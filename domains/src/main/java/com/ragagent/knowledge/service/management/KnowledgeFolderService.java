package com.ragagent.knowledge.service.management;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.contract.error.AppError;
import com.ragagent.contract.error.BizException;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.domain.KnowledgeTenants;
import com.ragagent.knowledge.repository.KnowledgeBaseRepository;
import com.ragagent.knowledge.repository.KnowledgeRepository;
import com.ragagent.knowledge.service.KnowledgeAccessHelper;
import com.ragagent.knowledge.service.access.ChunkAccessGuard;
import com.ragagent.knowledge.support.KnowledgePaths;
import com.ragagent.platform.security.InputSanitizer;
import com.ragagent.platform.web.JsonMappers;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识文件夹树与文件夹移动 / 重命名：树的增删改查、移动/重命名的路径重写与冲突校验。
 * <p>门面 helper（requireKb/findKb/tenantId/getKnowledgeBatch）下沉在
 * {@link KnowledgeAccessHelper}，直接依赖不回注门面（M2 解环）；搬移中防线统一走
 * {@link ChunkAccessGuard#rejectMovingKnowledge}（批量面同样复用）
 * （KnowledgeBatchOpsService）复用。</p>
 */
@Service
public class KnowledgeFolderService {

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    private final KnowledgeRepository knowledgeRepository;
    private final KnowledgeBaseRepository kbRepository;
    private final KnowledgeAccessHelper access;

    public KnowledgeFolderService(KnowledgeRepository knowledgeRepository,
                                  KnowledgeBaseRepository kbRepository,
                                  KnowledgeAccessHelper access) {
        this.knowledgeRepository = knowledgeRepository;
        this.kbRepository = kbRepository;
        this.access = access;
    }


    /**
     * rootDocumentCount/totalDocumentCount/folders。
     * 计数只排除 parse_status='deleting'（draft 计入）+ 软删行；中间空目录会被
     */
    public JsonNode folderTree(String kbId) {
        access.requireKb(kbId);
        // GROUP BY folder_path（Java 侧取列后内存聚合，
        // 语义一致：tenant+kb+parse_status<>'deleting'+deleted_at IS NULL）
        List<Knowledge> docs = knowledgeRepository.listFolderPathsForTree(
                KnowledgeTenants.tenantId(), kbId);
        Map<String, Long> counts = new TreeMap<>();
        for (Knowledge d : docs) {
            counts.merge(d.getFolderPath() == null ? "" : d.getFolderPath(), 1L, Long::sum);
        }

        long rootCount = 0;
        long totalCount = 0;
        Map<String, ObjectNode> nodes = new TreeMap<>();
        Map<String, List<ObjectNode>> children = new TreeMap<>();
        ArrayNode top = MAPPER.createArrayNode();
        for (Map.Entry<String, Long> e : counts.entrySet()) {
            long count = e.getValue();
            totalCount += count;
            String path = KnowledgePaths.normalizeKnowledgeFolderPath(e.getKey());
            if (path.isEmpty()) {
                rootCount += count;
                continue;
            }
            ObjectNode node = ensureFolderNode(path, nodes, children, top);
            node.put("documentCount", node.path("documentCount").asLong() + count);
        }
        // 深度优先回卷 totalCount：深路径先算，父级累加子树
        List<String> paths = new ArrayList<>(nodes.keySet());
        paths.sort((a, b) -> {
            int da = countChar(a, '/'), db = countChar(b, '/');
            return da != db ? Integer.compare(db, da) : a.compareTo(b);
        });
        for (String path : paths) {
            ObjectNode node = nodes.get(path);
            long total = node.path("documentCount").asLong() + children
                    .getOrDefault(path, List.of()).stream()
                    .mapToLong(c -> c.path("totalCount").asLong()).sum();
            node.put("totalCount", total);
        }
        // 空 children 整键缺席
        for (Map.Entry<String, List<ObjectNode>> e : children.entrySet()) {
            List<ObjectNode> list = e.getValue();
            list.sort(byNameLower);
            nodes.get(e.getKey()).set("children", MAPPER.createArrayNode().addAll(list));
        }
        List<ObjectNode> topList = new ArrayList<>();
        top.forEach(n -> topList.add((ObjectNode) n));
        topList.sort(byNameLower);
        top.removeAll();
        topList.forEach(top::add);
        ObjectNode tree = MAPPER.createObjectNode();
        tree.put("rootDocumentCount", rootCount);
        tree.put("totalDocumentCount", totalCount);
        tree.set("folders", top);
        return tree;
    }

    private static final Comparator<ObjectNode> byNameLower =
            Comparator.comparing(n -> n.path("name").asText("").toLowerCase(Locale.ROOT));

    /** 顶级挂 Folders，子级挂 parent.Children。 */
    private static ObjectNode ensureFolderNode(String path, Map<String, ObjectNode> nodes,
                                               Map<String, List<ObjectNode>> children, ArrayNode top) {
        ObjectNode existing = nodes.get(path);
        if (existing != null) {
            return existing;
        }
        ObjectNode node = MAPPER.createObjectNode();
        node.put("path", path);
        node.put("name", folderName(path));
        node.put("documentCount", 0);
        node.put("totalCount", 0);
        nodes.put(path, node);
        String parent = folderParent(path);
        if (parent.isEmpty()) {
            top.add(node);
        } else {
            ensureFolderNode(parent, nodes, children, top);
            children.computeIfAbsent(parent, k -> new ArrayList<>()).add(node);
        }
        return node;
    }

    private static String folderName(String path) {
        if (path.isEmpty()) {
            return "";
        }
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    private static String folderParent(String path) {
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(0, idx) : "";
    }

    private static int countChar(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }





    // ── 文件夹移动 / 重命名 ──────────────────────────────────────

    /**
     * 调用前 handler 已做
     * Java 保留同序（writeResourceIDs 空 id → 400；跨 KB → 403 "knowledge outside target KB"）。
     * @return affected 行数（UpdateKnowledgeFolderPath 的 RowsAffected）
     */
    @Transactional
    public long moveKnowledgeToFolder(String kbId, List<String> ids, String folderPath) {
        if (ids == null || ids.isEmpty()) {
            throw BizException.badRequest("knowledge_ids cannot be empty");
        }
        String normalized = normalizeTargetFolderPath(folderPath);
        List<Knowledge> rows = loadKnowledgeWriteBatch(ids, kbId);
        List<String> checkedIds = new ArrayList<>(rows.size());
        for (Knowledge row : rows) {
            if (!row.getKnowledgeBaseId().equals(kbId)) {
                throw BizException.forbidden("knowledge outside target KB");
            }
            checkedIds.add(row.getId());
        }
        long tenantId = rows.get(0).getTenantId();
        return knowledgeRepository.updateFolderPath(tenantId, kbId, checkedIds, normalized,
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    /**
     * source/target 规范化、同路径短路面、
     * 不能移进自身子目录、KB 缺失 → 404。@return affected 行数。
     */
    @Transactional
    public long renameKnowledgeFolder(String kbId, String from, String to) {
        String source = KnowledgePaths.normalizeKnowledgeFolderPath(from);
        if (source.isEmpty()) {
            throw BizException.badRequest("源文件夹路径不能为空");
        }
        String target = normalizeTargetFolderPath(to);
        if (target.isEmpty()) {
            throw BizException.badRequest("目标文件夹路径不能为空");
        }
        if (target.equals(source)) {
            return 0;
        }
        if (target.startsWith(source + "/")) {
            throw BizException.badRequest("不能将文件夹移动到它自己的子目录下");
        }
        KnowledgeBase kb = kbRepository.findByIdOnly(kbId);
        if (kb == null || !kb.getId().equals(kbId)) {
            throw BizException.notFound("knowledge base not found");
        }
        // 行级重写（folder_path = source 或 source+"/%"），
        List<Knowledge> rows = knowledgeRepository.listByFolderPathPrefix(
                kb.getTenantId(), kbId, source);
        if (rows.isEmpty()) {
            return 0;
        }
        Map<String, List<String>> byTarget = new TreeMap<>();
        for (Knowledge row : rows) {
            String suffix = row.getFolderPath().startsWith(source)
                    ? row.getFolderPath().substring(source.length()) : row.getFolderPath();
            byTarget.computeIfAbsent(KnowledgePaths.normalizeKnowledgeFolderPath(target + suffix), k -> new ArrayList<>())
                    .add(row.getId());
        }
        long affected = 0;
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (Map.Entry<String, List<String>> e : byTarget.entrySet()) {
            affected += knowledgeRepository.updateFolderPath(
                    kb.getTenantId(), kbId, e.getValue(), e.getKey(), now);
        }
        return affected;
    }

    /** trim → ValidateInput（非法 → 1010）→ Normalize。 */
    private static String normalizeTargetFolderPath(String folderPath) {
        String trimmed = folderPath == null ? "" : folderPath.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String safe = InputSanitizer.validateInput(trimmed);
        if (safe == null) {
            throw new BizException(AppError.validation("文件夹路径包含非法字符"));
        }
        return KnowledgePaths.normalizeKnowledgeFolderPath(safe);
    }

    /**
     * 逐 id 校验存在性、
     * moving 状态、KB 绑定与 <b>requireKBWrite 授权</b>；缺行 → 404 "knowledge not
     * found"（小写，契约样例锁定）；行落在授权 KB 之外 → 403 "无权修改该知识库"
     * @param grantedKbId 当前请求已授权的那个 KB（kb_id 路径 = 显式 kb_id；无 kb_id 路径 =
     *                    首条 knowledge 的 KB；单行 loadKnowledgeWrite 的调用方传 null）
     */
    public List<Knowledge> loadKnowledgeWriteBatch(List<String> ids, String grantedKbId) {
        List<String> cleaned = new ArrayList<>(ids.size());
        Set<String> seen = new HashSet<>();
        for (String id : ids) {
            if (id == null || id.trim().isEmpty()) {
                throw BizException.badRequest("resource ID cannot be empty");
            }
            if (seen.add(id)) {
                cleaned.add(id);
            }
        }
        List<Knowledge> rows = access.getKnowledgeBatch(KnowledgeTenants.tenantId(), cleaned);
        Map<String, Knowledge> byId = new HashMap<>();
        for (Knowledge row : rows) {
            byId.put(row.getId(), row);
        }
        List<Knowledge> result = new ArrayList<>(cleaned.size());
        Set<String> checkedKbs = new HashSet<>();
        for (String id : cleaned) {
            Knowledge row = byId.get(id);
            if (row == null) {
                throw BizException.notFound("knowledge not found");
            }
            ChunkAccessGuard.rejectMovingKnowledge(row);
            if (checkedKbs.add(row.getKnowledgeBaseId())) {
                // knowledgeWriteKB：KB 行与 (id, tenant) 绑定一致，否则 403
                KnowledgeBase kb = access.findKb(row.getKnowledgeBaseId());
                if (kb == null || !kb.getTenantId().equals(row.getTenantId())) {
                    throw BizException.forbidden("knowledge does not belong to its knowledge base");
                }
                // requireKBWrite：grant 只覆盖授权 KB（org-share 分支未实现）
                if (grantedKbId == null || !grantedKbId.equals(row.getKnowledgeBaseId())) {
                    throw BizException.forbidden("无权修改该知识库");
                }
            }
            result.add(row);
        }
        return result;
    }

    public Knowledge loadKnowledgeWrite(String id) {
        Knowledge k = knowledgeRepository.findById(KnowledgeTenants.tenantId(), id);
        if (k == null) {
            throw BizException.notFound("knowledge not found");
        }
        ChunkAccessGuard.rejectMovingKnowledge(k);
        KnowledgeBase kb = kbRepository.findById(k.getTenantId(), k.getKnowledgeBaseId());
        if (kb == null || !kb.getId().equals(k.getKnowledgeBaseId())
                || !kb.getTenantId().equals(k.getTenantId())) {
            throw BizException.forbidden("knowledge does not belong to its knowledge base");
        }
        return k;
    }

}
