package com.ragagent.agenttools.wiki;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.contract.tool.BaseTool;
import com.ragagent.contract.tool.ToolDefinitions;
import com.ragagent.contract.tool.ToolRequest;
import com.ragagent.contract.tool.ToolResult;
import com.ragagent.platform.tool.ToolSchemas;
import com.ragagent.platform.web.JsonMappers;
import java.util.ArrayList;
import java.util.List;

/**
 * wiki_rename_page 工具。
 * 新 slug 建页 → 改写入链 → 删旧页；任一步失败都回滚+清理。
 */
public class WikiRenamePageTool extends BaseTool {

    private static final String SCHEMA_JSON = """
            {
            	"type": "object",
            	"properties": {
            		"slug": {
            			"type": "string",
            			"description": "The current slug of the Wiki page"
            		},
            		"newSlug": {
            			"type": "string",
            			"description": "The new slug for the page"
            		}
            	},
            	"required": ["slug", "newSlug"]
            }""";

    private static final String DESCRIPTION =
            "Rename a Wiki page's slug. Automatically cascades the new slug to all pages that linked to the old one.";

    private final WikiPages wikiPageService;
    private final List<String> kbIds;
    private final WikiRouteResolver routes;

    public WikiRenamePageTool(WikiPages wikiPageService, List<String> kbIds, WikiRouteResolver routes) {
        super(ToolDefinitions.TOOL_WIKI_RENAME_PAGE, DESCRIPTION, ToolSchemas.parse(SCHEMA_JSON));
        this.wikiPageService = wikiPageService;
        this.kbIds = kbIds;
        this.routes = routes != null ? routes : new WikiRouteResolver();
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        JsonNode args = JsonMappers.toJsonNode(request.args());
        if (kbIds == null || kbIds.isEmpty()) {
            return failure("No knowledge bases available for editing");
        }
        if (args.path("newSlug").asText("").isEmpty()) {
            return failure("new_slug is required");
        }
        String slug;
        String newSlug;
        try {
            slug = WikiSlugs.normalizeAndValidateWikiSlug(args.path("slug").asText(""));
            newSlug = WikiSlugs.normalizeAndValidateWikiSlug(args.path("newSlug").asText(""));
        } catch (IllegalArgumentException e) {
            return failure(e.getMessage());
        }
        if (newSlug.equals(slug)) {
            return failure("new_slug must be different from old slug");
        }

        PageView existingPage;
        String kbId;
        try {
            ResolvedPage resolved = WikiRouteResolver.resolveUniqueWikiPage(wikiPageService, slug, kbIds, routes);
            existingPage = resolved.page();
            kbId = resolved.kbId();
        } catch (RuntimeException e) {
            return failure("Failed to resolve page to rename: " + e.getMessage());
        }

        List<String> inLinks = new ArrayList<>(existingPage.inLinks());

        // 新 slug 建页（同内容）
        PageView newPage = existingPage.copy();
        newPage.setKnowledgeBaseId(kbId);
        newPage.setSlug(newSlug);
        try {
            wikiPageService.createPage(newPage, WikiContentRewrite.WIKI_EDIT_SOURCE_AGENT);
        } catch (RuntimeException e) {
            return failure("Failed to create renamed page: " + e.getMessage());
        }

        String finalSlug = slug;
        String finalNewSlug = newSlug;
        WikiContentRewrite rewrite = content -> {
            String updated = content.replace("[[" + finalSlug + "]]", "[[" + finalNewSlug + "]]");
            updated = updated.replace("[[" + finalSlug + "|", "[[" + finalNewSlug + "|");
            return new RewriteResult(updated, !updated.equals(content));
        };

        List<String> updatedSlugs = new ArrayList<>();
        List<AppliedChange> changes;
        try {
            changes = WikiContentRewrite.applyIncomingWikiContentRewrite(
                    wikiPageService, kbId, inLinks, WikiContentRewrite.WIKI_EDIT_SOURCE_AGENT, rewrite, updatedSlugs);
        } catch (WikiRewriteException rewriteErr) {
            String rollbackErr = null;
            try {
                WikiContentRewrite.rollbackWikiContentChanges(
                        wikiPageService, rewriteErr.changes(), WikiContentRewrite.WIKI_EDIT_SOURCE_AGENT);
            } catch (RuntimeException rb) {
                rollbackErr = rb.getMessage();
            }
            String cleanupErr = null;
            try {
                wikiPageService.deletePage(kbId, newSlug, WikiContentRewrite.WIKI_EDIT_SOURCE_AGENT);
            } catch (RuntimeException ce) {
                cleanupErr = ce.getMessage();
            }
            return failure("Rename aborted while updating incoming links: "
                    + WikiContentRewrite.joinWikiMutationErrors(rewriteErr.getMessage(), rollbackErr, cleanupErr));
        }
        int updatedCount = updatedSlugs.size();

        try {
            wikiPageService.deletePage(kbId, slug, WikiContentRewrite.WIKI_EDIT_SOURCE_AGENT);
        } catch (RuntimeException e) {
            String rollbackErr = null;
            try {
                WikiContentRewrite.rollbackWikiContentChanges(
                        wikiPageService, changes, WikiContentRewrite.WIKI_EDIT_SOURCE_AGENT);
            } catch (RuntimeException rb) {
                rollbackErr = rb.getMessage();
            }
            String cleanupErr = null;
            try {
                wikiPageService.deletePage(kbId, newSlug, WikiContentRewrite.WIKI_EDIT_SOURCE_AGENT);
            } catch (RuntimeException ce) {
                cleanupErr = ce.getMessage();
            }
            return failure("Rename aborted because the old page could not be deleted: "
                    + WikiContentRewrite.joinWikiMutationErrors(e.getMessage(), rollbackErr, cleanupErr));
        }
        routes.forget(slug, kbId);
        routes.remember(newSlug, kbId);

        wikiPageService.injectCrossLinks(kbId, List.of(newSlug));
        try {
            wikiPageService.rebuildIndexPage(kbId);
        } catch (RuntimeException ignored) {
            // 重建失败不影响改名结果
        }

        String outputMsg = String.format(
                "Successfully renamed page [[%s]] → [[%s]] and updated %d incoming links.", slug, newSlug, updatedCount);
        if (updatedCount > 0) {
            outputMsg += String.format("\n- Affected pages: %s", String.join(", ", updatedSlugs));
        }

        ToolResult r = new ToolResult();
        r.setSuccess(true);
        r.setOutput(outputMsg);
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("displayType", "wiki_rename_page");
        data.put("oldSlug", slug);
        data.put("newSlug", newSlug);
        data.put("title", existingPage.title());
        data.put("updatedCount", updatedCount);
        data.put("affectedPages", updatedSlugs);
        r.setData(data);
        return r;
    }

    private static ToolResult failure(String message) {
        ToolResult result = new ToolResult();
        result.setSuccess(false);
        result.setError(message);
        return result;
    }
}
