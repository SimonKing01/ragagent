package com.ragagent.wiki;



import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ragagent.TestSchema;
import com.ragagent.agent.fixtures.ContractJson;
import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.domain.TenantMember;
import com.ragagent.auth.domain.User;
import com.ragagent.auth.mapper.TenantMapper;
import com.ragagent.auth.mapper.TenantMemberMapper;
import com.ragagent.auth.mapper.UserMapper;
import com.ragagent.contract.auth.UserPreferences;
import com.ragagent.fixtures.GoldenContract;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.domain.KnowledgeBaseIndexingStrategy;
import com.ragagent.knowledge.mapper.KnowledgeBaseMapper;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Wiki 契约测试。
 *
 * <p><b>Wiki 的载荷形态与知识库/MCP 都不同</b>，这是本测试的核心价值（统一外壳下
 * 逐端点钉住载荷形状）：
 * <ul>
 *   <li>页面 CRUD 的 data 是**裸实体**（直出实体，不再包一层容器）</li>
 *   <li>列表 data 是自定义分页结构
 *       {@code {"pages":[...],"total":N,"page":N,"pageSize":N,"totalPages":N}}</li>
 *   <li>错误统一走 AppError 信封
 *       {@code {"code":1003,"message":"Wiki page not found","data":null}}</li>
 *   <li>403 由路由守卫产生，同走统一外壳（code 1002 + 固定的 must-own 文案）</li>
 * </ul>
 *
 * golden 录自启用了 wiki_enabled 的真实 dev server，掩码 UUID 与时间戳。
 */
@SpringBootTest
@AutoConfigureMockMvc
class WikiContractTest {


    /** 统一外壳下钻：返回 data 的紧凑 JSON（序保持 ✓）；非外壳原样返回。 */
    private static String payload(String body) {
        try {
            var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            if (root.isObject() && root.has("code") && root.has("data")) {
                return root.get("data").toString();
            }
        } catch (Exception ignored) {
            // 非 JSON（或空体）⇒ 原样
        }
        return body;
    }
    private static final String BCRYPT = "$2a$10$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK"; // Passw0rd!
    private static final OffsetDateTime TS = OffsetDateTime.of(2026, 9, 18, 10, 0, 0, 123456000, ZoneOffset.ofHours(8));

    private static final Pattern TS_PATTERN = Pattern.compile(
            "\"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})\"");
    private static final Pattern UUID_KEY_PATTERN = Pattern.compile(
            "\"(id|knowledge_base_id|parent_id|last_editor_id)\":"
                    + "\"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\"");
    private static final Pattern UUID_ANY_PATTERN = Pattern.compile(
            "\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private TenantMapper tenantMapper;
    @Autowired
    private TenantMemberMapper memberMapper;
    @Autowired
    private KnowledgeBaseMapper kbMapper;

    private String kbId;

    @BeforeEach
    void seed() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);

        Tenant tenant = new Tenant();
        tenant.setId(10002L);
        tenant.setName("phase1-test-tenant");
        tenant.setStatus("active");
        tenantMapper.insert(tenant);

        insertUser("11111111-2222-3333-4444-555555555501", "phase1test", "java-phase1@weknora.test");
        insertUser("11111111-2222-3333-4444-555555555504", "phase1viewer", "java-phase1-viewer@weknora.test");
        insertMember("11111111-2222-3333-4444-555555555501", "admin");
        insertMember("11111111-2222-3333-4444-555555555504", "viewer");

        // wiki_enabled 的 KB（与 golden 夹具录制时的 KB 配置一致）
        kbId = "35ed3096-d9ee-4561-96f0-ee38eb559122";
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(kbId);
        kb.setName("golden-wiki-kb");
        kb.setDescription("phase4 wiki golden");
        kb.setType("document");
        kb.setTenantId(10002L);
        kb.setCreatorId("11111111-2222-3333-4444-555555555501");
        KnowledgeBaseIndexingStrategy idx =
                KnowledgeBaseIndexingStrategy.defaultStrategy();
        idx.setWikiEnabled(true);
        idx.setGraphEnabled(false);
        kb.setIndexingStrategy(idx);
        kb.setCreatedAt(TS);
        kb.setUpdatedAt(TS);
        kbMapper.insert(kb);
    }

    private void insertUser(String id, String username, String email) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(BCRYPT);
        user.setTenantId(10002L);
        user.setIsActive(true);
        user.setPreferences(new UserPreferences());
        user.setCreatedAt(TS);
        user.setUpdatedAt(TS);
        userMapper.insert(user);
    }

    private void insertMember(String userId, String role) {
        TenantMember member = new TenantMember();
        member.setUserId(userId);
        member.setTenantId(10002L);
        member.setRole(role);
        member.setStatus("active");
        member.setJoinedAt(TS);
        memberMapper.insert(member);
    }

    // ── 页面 CRUD（裸实体，无信封） ─────────────────────────────────────────

    @Test
    void pageCrudLifecycle() throws Exception {
        String token = login("java-phase1@weknora.test");
        String base = "/api/v1/knowledgebase/" + kbId + "/wiki";

        // 1. create → golden 掩码比对（钉住字段序与键名；data 直出裸实体）
        MvcResult created = mockMvc.perform(post(base + "/pages")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"slug\":\"golden-page\",\"title\":\"Golden Page\",\"pageType\":\"summary\","
                                + "\"content\":\"# Golden\\n\\nContent with a [[other-page]] link.\","
                                + "\"summary\":\"short summary\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String createdBody = created.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(goldenChecked("wiki-page-create.json", createdBody), mask(createdBody),
                "create 应与 golden 一致（掩码后；data 直出裸实体）");
        assertFalse(createdBody.contains("\"success\""), "wiki 响应无 success 键: " + createdBody);

        // 2. list → 自定义分页结构
        MvcResult list = mockMvc.perform(get(base + "/pages")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        String listBody = list.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(list.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("\"data\""),
                "list 应带统一外壳：" + listBody);
        listBody = payload(listBody);   // 外壳下钻
        for (String key : new String[]{"\"pages\"", "\"total\"", "\"page\"", "\"pageSize\"", "\"totalPages\""}) {
            assertTrue(listBody.contains(key), "list 应含分页键 " + key + ": " + listBody);
        }
        assertFalse(listBody.contains("\"code\""), "下钻后 listBody 应为载荷（无 code 键）: " + listBody);

        // 3. get → 200（载荷形态由 golden 与 WikiHttpContractTest 覆盖）
        mockMvc.perform(get(base + "/pages/golden-page")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // 4. 404 → 统一外壳错误体（逐字节）
        MvcResult gb1 = mockMvc.perform(get(base + "/pages/no-such-page")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
            .andReturn();
        assertGolden(gb1, "wiki-page-not-found.json");

        // 5. update → 掩码比对（含 version 自增）
        MvcResult updated = mockMvc.perform(put(base + "/pages/golden-page")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"title\":\"Golden Page Renamed\","
                                + "\"content\":\"# Golden\\n\\nUpdated content with [[other-page]].\","
                                + "\"summary\":\"updated\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String updatedBody = updated.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(goldenChecked("wiki-page-update.json", updatedBody), mask(updatedBody),
                "update 应与 golden 一致（掩码后，含 version=2）");

        // 6. revisions → {revisions,total,currentVersion}
        MvcResult revs = mockMvc.perform(get(base + "/revisions/golden-page")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        String revBody = revs.getResponse().getContentAsString(StandardCharsets.UTF_8);
        for (String key : new String[]{"\"revisions\"", "\"total\"", "\"currentVersion\""}) {
            assertTrue(revBody.contains(key), "revisions 应含 " + key + ": " + revBody);
        }
    }

    // ── 文件夹 ──────────────────────────────────────────────────────────────

    @Test
    void folderEndpoints() throws Exception {
        String token = login("java-phase1@weknora.test");
        String base = "/api/v1/knowledgebase/" + kbId + "/wiki";

        // 空列表 → 逐字节 golden（data = {"folders":[],"parentId":""}）
        MvcResult gb2 = mockMvc.perform(get(base + "/folders").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
            .andReturn();
        assertGolden(gb2, "wiki-folders-empty.json");

        // 创建 → 裸实体
        mockMvc.perform(post(base + "/folders")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"name\":\"Golden Folder\",\"parentId\":\"\"}"))
                .andExpect(status().isCreated());
    }

    // ── 聚合读端点（形态断言） ───────────────────────────────────────────────

    @Test
    void aggregateEndpoints() throws Exception {
        String token = login("java-phase1@weknora.test");
        String base = "/api/v1/knowledgebase/" + kbId + "/wiki";

        // index → {intro,version,groups:[{type,total,items}]}
        String idx = body(get(base + "/index"), token);
        for (String key : new String[]{"\"intro\"", "\"version\"", "\"groups\"", "\"type\"", "\"total\"", "\"items\""}) {
            assertTrue(idx.contains(key), "index 应含 " + key + ": " + idx);
        }

        // graph → {nodes:[{slug,title,pageType,linkCount}],edges,meta:{mode,total}}
        String graph = body(get(base + "/graph"), token);
        for (String key : new String[]{"\"nodes\"", "\"slug\"", "\"linkCount\"", "\"meta\"", "\"mode\""}) {
            assertTrue(graph.contains(key), "graph 应含 " + key + ": " + graph);
        }

        // stats → {totalPages,pagesByType,totalLinks,orphanCount,recentUpdates}
        String stats = body(get(base + "/stats"), token);
        for (String key : new String[]{"\"totalPages\"", "\"pagesByType\"", "\"totalLinks\"",
                "\"orphanCount\"", "\"recentUpdates\""}) {
            assertTrue(stats.contains(key), "stats 应含 " + key + ": " + stats);
        }

        // lint → {knowledgeBaseId,issues:[{type,severity,pageSlug,description}]}
        String lint = body(get(base + "/lint"), token);
        assertTrue(lint.contains("\"issues\"") && lint.contains("\"knowledgeBaseId\""),
                "lint 形态: " + lint);

        // issues → data 里是裸数组
        String issues = body(get(base + "/issues"), token);
        issues = payload(issues);   // 外壳下钻（裸数组断言看载荷）
        assertTrue(issues.trim().startsWith("["), "issues 应为裸数组: " + issues);
    }

    // ── 权限（路由守卫文案） ────────────────────────────────────────────

    @Test
    void viewerCannotCreatePage() throws Exception {
        String token = login("java-phase1-viewer@weknora.test");
        MvcResult gb3 = mockMvc.perform(post("/api/v1/knowledgebase/" + kbId + "/wiki/pages")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"slug\":\"v-page\",\"title\":\"V\"}"))
                .andExpect(status().isForbidden())
            .andReturn();
        assertGolden(gb3, "wiki-create-forbidden.json");
    }

    /**
     * 不存在/跨租户的 KB：**404 + AppError 信封**（实测如此，不是 403）。
     * 与"资源存在但无权"区分开——后者才是守卫式 403。
     */
    @Test
    void unknownKbIsNotFound() throws Exception {
        String token = login("java-phase1@weknora.test");
        MvcResult r = mockMvc.perform(get("/api/v1/knowledgebase/00000000-0000-0000-0000-000000000099/wiki/pages")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andReturn();
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("\"code\":1003") && body.contains("knowledge base not found"),
                "应为 AppError 信封 404: " + body);
    }

    // ── 工具 ─────────────────────────────────────────────────────────────

    private String body(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req,
                        String token) throws Exception {
        MvcResult r = mockMvc.perform(req.header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String login(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"email\":\"" + email + "\",\"password\":\"Passw0rd!\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        java.util.regex.Matcher m = Pattern.compile("\"token\":\"([^\"]+)\"").matcher(body);
        assertTrue(m.find(), "login 响应应含 token: " + body);
        return m.group(1);
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper GOLDEN_SEMANTIC_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** {@code -Dcontract.refresh=true} 时把掩码后的实际响应写回夹具。 */
    private static final boolean REFRESH_FIXTURES = Boolean.getBoolean("contract.refresh");

    private static String goldenChecked(String name, String actualBody) throws Exception {
        String masked = mask(actualBody);
        if (REFRESH_FIXTURES) {
            java.nio.file.Path file = com.ragagent.fixtures.ContractPaths.resolveForWrite(name);
            java.nio.file.Files.writeString(file, masked + "\n");
            return masked;
        }
        return mask(golden(name)).strip();
    }

    private static String golden(String name) throws Exception {
        // 语义比较：键序/HTML 转义归一后返回（非 JSON 文本原样），断言侧不变
        var resource = new org.springframework.core.io.ClassPathResource("contracts/" + name);
        if (!resource.exists()) {
            resource = new org.springframework.core.io.ClassPathResource("contracts/" + name + ".json");
        }
        String text = new String(resource.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        return ContractJson.semantic(GOLDEN_SEMANTIC_MAPPER, text);
    }


    /** 掩码：UUID（含任意位置的裸 UUID，如 recent_updates 里的 id）与时间戳 */
    // ── 金片对比（统一基建：语义归一 + strip + -Dcontract.refresh 重录） ──

    private static void assertGolden(org.springframework.test.web.servlet.MvcResult r,
            String name) throws Exception {
        GoldenContract.assertEquals("src/test/resources/contracts",
                name, WikiContractTest::mask,
                r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String mask(String s) {
        // 语义比较入口：键序/转义归一后再掩码
        s = ContractJson.semantic(s);
        String out = UUID_KEY_PATTERN.matcher(s).replaceAll("\"$1\":\"<id>\"");
        out = TS_PATTERN.matcher(out).replaceAll("\"<ts>\"");
        return UUID_ANY_PATTERN.matcher(out).replaceAll("<uuid>");
    }
}
