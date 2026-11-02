package com.ragagent.wiki.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.contract.audit.WikiActivityAudit;
import com.ragagent.contract.knowledge.KnowledgeBaseLookup;
import com.ragagent.platform.web.ApiResult;
import com.ragagent.wiki.service.page.WikiLintService;
import com.ragagent.wiki.service.page.WikiPageService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Wiki 页面的 HTTP 层。
 *
 * <p><b>响应形态（逐端点保持原样，不要"统一"它们）</b>：</p>
 * <ol>
 *   <li><b>实体直出</b>：直接序列化领域对象，
 *       键序 = 领域字段声明序（Jackson 默认序；已摘键序注解，键序天然跟随声明），
 *       <b>没有</b> {@code {data,success}} 信封。wiki 层几乎所有端点都是这个形态。</li>
 *   <li><b>raw JSON map 直出</b>：{@code {"message":...}}（UpdateIssueStatus / RebuildLinks）、
 *       {@code {"fixed":N,"message":...}}（AutoFix）、{@code {"pages":[...]}}（SearchPages）、
 *       {@code {"current_version":N,"error":...}}（UpdatePage 的乐观锁冲突）。
 *       raw JSON map 按<b>键字母序</b>输出，用 LinkedHashMap 按字母序插入保证。</li>
 *   <li><b>裸数组</b>：ListIssues 的响应就是问题数组本身。</li>
 *   <li><b>handler 直接写的错误</b>：单键 map，形态是 {@code {"error":"..."}}，与全局错误信封
 *       {@code {"success":false,"error":{code,details,message}}} <b>不同</b>。
 *       21 个端点里除"KB 访问被拒"外全部走这一形态（见下）。</li>
 *   <li><b>守卫写的错误</b>：KB 访问拒绝走全局错误处理，形态是
 *       {@code {"success":false,"error":{...}}}。
 *       Java 侧对应 {@link com.ragagent.contract.error.BizException}，由 GlobalExceptionHandler 统一渲染。</li>
 * </ol>
 *
 * <p><b>⚠️ 错误文案的前缀</b>：KB 校验失败写出去的错误文案带
 * {@code "error code: %d, error message: %s"} 前缀，<b>不是</b>裸消息。
 * 例：KB 未启用 wiki 时客户端收到的是
 * {@code {"error":"error code: 400, error message: Wiki feature is not enabled for this knowledge base"}}
 * 且 HTTP 状态是 <b>400</b>。Java 的 {@code BizException.getMessage()} 恰好是同一格式
 * （见 {@link com.ragagent.contract.error.BizException#BizException}），因此这里复用同一文案函数。</p>
 *
 * <p><b>守卫矩阵</b>（角色下限在 WebConfig 里注册）：</p>
 * <pre>
 * 读端点：Viewer+ 角色 + KB 读权限  （同租户可读；跨租户经 org-share / shared-agent 只读授予）
 * 写端点：创建者本人或 Admin+（否则 403）+ KB 写权限
 * </pre>
 * <p>Java 的 {@code RbacInterceptor} 只能表达"角色下限"，无法表达 KB 访问的
 * "own / org-shared / via shared agent" 解析，故 <b>KB 访问与所有权判定由 {@link WikiKbAccessGuard} 承接</b>，
 * 角色下限仍由 WebConfig 注册的规则负责。跨空间授予路径本仓不实现——非本租户一律拒绝。</p>
 *
 * <p><b>⚠️ 通配 slug</b>：catch-all 路径参数捕获值<b>带前导 "/"</b>，
 * 取用前要先剥掉再去首尾空白清洗，见 {@code getSlugParam}。</p>
 *
 * <p><b>实现边界</b>：</p>
 * <ul>
 *   <li>图谱的"熟悉知识"叠加层（FamiliarKnowledgeIDs）无对应模块 → 该字段恒为 null。</li>
 *   <li>审计埋点由 {@link WikiActivityAudit} 接缝承接；实现 bean
 *       （{@code com.ragagent.audit.service.WikiActivityAuditRecorder}）由审计模块提供，
 *       缺失时才退化为 debug 日志。</li>
 *   <li>空结果统一走 DTO/服务层既有的"空列表"归一
 *       （ListIssues / SearchPages / ListPages 的空结果）。</li>
 *   <li>请求体 JSON 语法错误用 Jackson 的消息。</li>
 *   <li><b>写路径规则</b>：跨租户 creator 查不到 → 透传，org-share 的
 *       Editor 角色可写；共享 agent 分支对 Editor 不可达。同租户写仍走创建者/Admin+。</li>
 * </ul>
 *
 */
@RestController
@ApiResult
@RequestMapping("/api/v1/knowledgebase/{kbId}/wiki")
@Tag(name = "Wiki")
public class WikiPageController {

    private final WikiKbAccessGuard kbGuard;
    private final WikiFolderOps folderOps;
    private final WikiPageOps pageOps;
    private final WikiStatsOps statsOps;
    private final WikiMaintenanceOps maintenanceOps;

    public WikiPageController(WikiPageService wikiService,
                              WikiLintService lintService,
                              KnowledgeBaseLookup kbLookup,
                              ObjectMapper json,
                              ObjectProvider<WikiActivityAudit> activityAudit) {
        this.kbGuard = new WikiKbAccessGuard(kbLookup);
        this.pageOps = new WikiPageOps(wikiService, kbGuard,
                new WikiActivityRecorder(activityAudit), json);
        this.folderOps = new WikiFolderOps(wikiService, kbGuard, json);
        this.statsOps = new WikiStatsOps(wikiService, kbGuard);
        this.maintenanceOps = new WikiMaintenanceOps(wikiService, lintService, kbGuard, json);
    }

    // ── 页面 CRUD ──

    @GetMapping("/pages")
    public ResponseEntity<?> listPages(@PathVariable("kbId") String kbId, HttpServletRequest request) {
        return pageOps.listPages(kbId, request);
    }

    @PostMapping("/pages")
    public ResponseEntity<?> createPage(@PathVariable("kbId") String kbId,
                                        @RequestBody(required = false) String rawBody) {
        return pageOps.createPage(kbId, rawBody);
    }

    @GetMapping("/pages/{*slug}")
    public ResponseEntity<?> getPage(@PathVariable("kbId") String kbId,
                                     @PathVariable(value = "slug", required = false) String slugParam) {
        return pageOps.getPage(kbId, slugParam);
    }

    @PutMapping("/pages/{*slug}")
    public ResponseEntity<?> updatePage(@PathVariable("kbId") String kbId,
                                        @PathVariable(value = "slug", required = false) String slugParam,
                                        @RequestBody(required = false) String rawBody) {
        return pageOps.updatePage(kbId, slugParam, rawBody);
    }

    @DeleteMapping("/pages/{*slug}")
    public ResponseEntity<?> deletePage(@PathVariable("kbId") String kbId,
                                        @PathVariable(value = "slug", required = false) String slugParam) {
        return pageOps.deletePage(kbId, slugParam);
    }

    // ── 修订历史 ──

    @GetMapping("/revisions/{*slug}")
    public ResponseEntity<?> listRevisions(@PathVariable("kbId") String kbId,
                                           @PathVariable(value = "slug", required = false) String slugParam,
                                           HttpServletRequest request) {
        return pageOps.listRevisions(kbId, slugParam, request);
    }

    @PostMapping("/revert")
    public ResponseEntity<?> revertPage(@PathVariable("kbId") String kbId,
                                        @RequestBody(required = false) String rawBody) {
        return pageOps.revertPage(kbId, rawBody);
    }

    // ── 文件夹树 ──

    @GetMapping("/folders")
    public ResponseEntity<?> listFolders(@PathVariable("kbId") String kbId, HttpServletRequest request) {
        return folderOps.listFolders(kbId, request);
    }

    @PostMapping("/folders")
    public ResponseEntity<?> createFolder(@PathVariable("kbId") String kbId,
                                          @RequestBody(required = false) String rawBody) {
        return folderOps.createFolder(kbId, rawBody);
    }

    @PutMapping("/folders/{folderId}")
    public ResponseEntity<?> updateFolder(@PathVariable("kbId") String kbId,
                                          @PathVariable("folderId") String folderIdParam,
                                          @RequestBody(required = false) String rawBody) {
        return folderOps.updateFolder(kbId, folderIdParam, rawBody);
    }

    @DeleteMapping("/folders/{folderId}")
    public ResponseEntity<?> deleteFolder(@PathVariable("kbId") String kbId,
                                          @PathVariable("folderId") String folderIdParam) {
        return folderOps.deleteFolder(kbId, folderIdParam);
    }

    @PutMapping("/move-page")
    public ResponseEntity<?> movePage(@PathVariable("kbId") String kbId,
                                      @RequestBody(required = false) String rawBody) {
        return folderOps.movePage(kbId, rawBody);
    }

    // ── 特殊页 ──

    @GetMapping("/index")
    public ResponseEntity<?> getIndex(@PathVariable("kbId") String kbId, HttpServletRequest request) {
        return statsOps.getIndex(kbId, request);
    }

    // ── 图谱 / 统计 ──

    @GetMapping("/graph")
    public ResponseEntity<?> getGraph(@PathVariable("kbId") String kbId, HttpServletRequest request) {
        return statsOps.getGraph(kbId, request);
    }

    @GetMapping("/stats")
    public ResponseEntity<?> getStats(@PathVariable("kbId") String kbId) {
        return statsOps.getStats(kbId);
    }

    @GetMapping("/search")
    public ResponseEntity<?> searchPages(@PathVariable("kbId") String kbId, HttpServletRequest request) {
        return statsOps.searchPages(kbId, request);
    }

    // ── 检索 / 维护 ──

    @PostMapping("/rebuild-links")
    public ResponseEntity<?> rebuildLinks(@PathVariable("kbId") String kbId) {
        return maintenanceOps.rebuildLinks(kbId);
    }

    @GetMapping("/lint")
    public ResponseEntity<?> lint(@PathVariable("kbId") String kbId) {
        return maintenanceOps.lint(kbId);
    }

    @PostMapping("/auto-fix")
    public ResponseEntity<?> autoFix(@PathVariable("kbId") String kbId) {
        return maintenanceOps.autoFix(kbId);
    }

    // ── 问题 ──

    @GetMapping("/issues")
    public ResponseEntity<?> listIssues(@PathVariable("kbId") String kbId, HttpServletRequest request) {
        return maintenanceOps.listIssues(kbId, request);
    }

    @PutMapping("/issues/{issueId}/status")
    public ResponseEntity<?> updateIssueStatus(@PathVariable("kbId") String kbId,
                                               @PathVariable("issueId") String issueIdParam,
                                               @RequestBody(required = false) String rawBody) {
        return maintenanceOps.updateIssueStatus(kbId, issueIdParam, rawBody);
    }

    // ── 守卫 / 校验 ──
}
