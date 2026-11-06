package com.ragagent.session.rag.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.contract.retrieval.ChunkTypes;
import com.ragagent.contract.retrieval.RetrievalObsRows;
import com.ragagent.contract.retrieval.SearchResult;
import com.ragagent.platform.prompt.AgentPromptPlaceholders;
import com.ragagent.platform.prompt.MessageAttachmentsPrompt;
import com.ragagent.platform.security.InputSanitizer;
import com.ragagent.platform.web.JsonMappers;
import com.ragagent.session.rag.PipelineEventType;
import com.ragagent.session.rag.PipelineLog;
import com.ragagent.session.rag.PipelineMessageStore;
import com.ragagent.session.rag.PipelineState;
import com.ragagent.session.rag.support.ReferencesSupport;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * INTO_CHAT_MESSAGE 阶段插件：
 * 检索结果 → 用户消息上下文。
 *
 * <h2>编排</h2>
 * <ul>
 *   <li>FAQ 优先：faq 与文档分组；高分 FAQ（≥ FAQDirectAnswerThreshold）打
 *       {@code match="exact"} 标记；文档头 {@code <documents>} 列出每份知识
 *       （title/description/metadata 做 HTML 五字符转义）。</li>
 *   <li>查询经 InputSanitizer.validateInput 校验，非法 →
 *       TEMPLATE_EXECUTE + "user query contains invalid content"；非法改写回落原查询。</li>
 *   <li>无检索意图：模板渲染只注入 query/language（contexts 空）；图片描述仅在
 *       模型不支持视觉时以 "[用户上传图片内容]" 前缀追加；引用上下文与附件提示词随后。</li>
 *   <li>渲染后的 UserContent 异步持久化（UpdateMessageRenderedContent）。</li>
 * </ul>
 */
public final class PluginIntoChatMessage implements Plugin {

    private final PipelineMessageStore messageService;

    public PluginIntoChatMessage(PipelineMessageStore messageService) {
        this.messageService = messageService;
    }

    @Override
    public PipelineEventType[] activationEvents() {
        return new PipelineEventType[] {PipelineEventType.INTO_CHAT_MESSAGE};
    }

    @Override
    public PluginError onEvent(PipelineEventType eventType, PipelineState chatManage, Plugin.Chain next) {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("sessionId", chatManage.getSessionId());
        in.put("merge_result_cnt", chatManage.getMerged().getResults() == null ? 0 : chatManage.getMerged().getResults().size());
        in.put("template_len", chatManage.getSummaryConfig().getContextTemplate().length());
        PipelineLog.info("IntoChatMessage", "input", in);

        List<SearchResult> mergeResult = chatManage.getMerged().getResults() == null
                ? new ArrayList<>() : chatManage.getMerged().getResults();

        // FAQ 优先时的分组
        List<SearchResult> faqResults = new ArrayList<>();
        List<SearchResult> docResults = new ArrayList<>();
        boolean hasHighConfidenceFAQ = false;

        if (chatManage.isFaqPriorityEnabled()) {
            for (SearchResult result : mergeResult) {
                if (ChunkTypes.FAQ.equals(result.getChunkType())) {
                    faqResults.add(result);
                    if (result.getScore() >= chatManage.getFaqDirectAnswerThreshold() && !hasHighConfidenceFAQ) {
                        hasHighConfidenceFAQ = true;
                        Map<String, Object> f = new LinkedHashMap<>();
                        f.put("chunk_id", result.getId());
                        f.put("score", RetrievalObsRows.formatScore4(result.getScore()));
                        f.put("threshold", chatManage.getFaqDirectAnswerThreshold());
                        PipelineLog.info("IntoChatMessage", "high_confidence_faq", f);
                    }
                } else {
                    docResults.add(result);
                }
            }
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("faq_count", faqResults.size());
            f.put("doc_count", docResults.size());
            f.put("has_high_confidence", hasHighConfidenceFAQ);
            PipelineLog.info("IntoChatMessage", "faq_separation", f);
        }

        // 查询安全性校验
        String safeQuery = InputSanitizer.validateInput(chatManage.getQuery());
        if (safeQuery == null) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("sessionId", chatManage.getSessionId());
            PipelineLog.warn("IntoChatMessage", "invalid_query", f);
            return PluginError.TEMPLATE_EXECUTE.withError(
                    new RuntimeException("user query contains invalid content"));
        }

        // 意图化无检索路径：模板渲染只注 runtime 元数据
        if (!chatManage.needsRetrieval()) {
            String userContent = safeQuery;
            String rewrite = chatManage.getQueryPlan().getRewriteQuery().trim();
            if (!rewrite.isEmpty()) {
                String safeRewrite = InputSanitizer.validateInput(rewrite);
                if (safeRewrite != null) {
                    userContent = safeRewrite;
                } else {
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("sessionId", chatManage.getSessionId());
                    PipelineLog.warn("IntoChatMessage", "invalid_rewrite_query_fallback", f);
                }
            }
            if (!chatManage.getQueryPlan().getImageDescription().isEmpty() && !chatManage.isChatModelSupportsVision()) {
                userContent += "\n\n[用户上传图片内容]\n" + chatManage.getQueryPlan().getImageDescription();
            }
            if (!chatManage.getQueryPlan().getQuotedContext().isEmpty()) {
                userContent += "\n\n" + chatManage.getQueryPlan().getQuotedContext();
            }
            if (chatManage.getAttachments() != null && !chatManage.getAttachments().isEmpty()) {
                userContent += MessageAttachmentsPrompt.build(chatManage.getAttachments());
            }

            String tpl = chatManage.getSummaryConfig().getContextTemplate();
            if (!tpl.isEmpty()) {
                Map<String, String> vals = new LinkedHashMap<>();
                vals.put("query", userContent);
                vals.put("contexts", "");
                vals.put("language", chatManage.getLanguage());
                chatManage.getAnswer().setUserContent(
                        AgentPromptPlaceholders.renderPromptPlaceholders(tpl, vals));
            } else {
                chatManage.getAnswer().setUserContent(userContent);
            }

            Map<String, Object> f = new LinkedHashMap<>();
            f.put("sessionId", chatManage.getSessionId());
            f.put("user_content_len", chatManage.getAnswer().getUserContent().length());
            f.put("has_template", !chatManage.getSummaryConfig().getContextTemplate().isEmpty());
            PipelineLog.info("IntoChatMessage", "no_search_with_template", f);
            return next.next();
        }

        StringBuilder contextsBuilder = new StringBuilder();

        // 唯一文档元数据（每份知识一次）
        List<SearchResult> allResults = new ArrayList<>(mergeResult);
        if (chatManage.isFaqPriorityEnabled() && !faqResults.isEmpty()) {
            allResults = new ArrayList<>(faqResults);
            allResults.addAll(docResults);
        }
        String docHeader = buildDocumentHeader(allResults);
        if (!docHeader.isEmpty()) {
            contextsBuilder.append(docHeader).append("\n");
        }

        if (chatManage.isFaqPriorityEnabled() && !faqResults.isEmpty()) {
            contextsBuilder.append("<source type=\"faq\" priority=\"high\">\n");
            for (int i = 0; i < faqResults.size(); i++) {
                SearchResult result = faqResults.get(i);
                String passage = ReferencesSupport.getEnrichedPassageForChat(result);
                if (hasHighConfidenceFAQ && i == 0) {
                    contextsBuilder.append(String.format(
                            "<context id=\"FAQ-%d\" match=\"exact\">%s</context>\n", i + 1, passage));
                } else {
                    contextsBuilder.append(String.format(
                            "<context id=\"FAQ-%d\">%s</context>\n", i + 1, passage));
                }
            }
            contextsBuilder.append("</source>\n");

            if (!docResults.isEmpty()) {
                contextsBuilder.append("<source type=\"document\" priority=\"supplementary\">\n");
                for (int i = 0; i < docResults.size(); i++) {
                    SearchResult result = docResults.get(i);
                    String passage = ReferencesSupport.getEnrichedPassageForChat(result);
                    contextsBuilder.append(String.format(
                            "<context id=\"DOC-%d\">%s</context>\n", i + 1, passage));
                }
                contextsBuilder.append("</source>");
            }
        } else {
            for (int i = 0; i < mergeResult.size(); i++) {
                SearchResult result = mergeResult.get(i);
                String passage = ReferencesSupport.getEnrichedPassageForChat(result);
                if (i > 0) {
                    contextsBuilder.append("\n");
                }
                contextsBuilder.append(String.format(
                        "<context id=\"%d\">%s</context>", i + 1, passage));
            }
        }

        chatManage.getAnswer().setRenderedContexts(contextsBuilder.toString());

        Map<String, String> vals = new LinkedHashMap<>();
        vals.put("query", safeQuery);
        vals.put("contexts", chatManage.getAnswer().getRenderedContexts());
        vals.put("language", chatManage.getLanguage());
        String userContent = AgentPromptPlaceholders.renderPromptPlaceholders(
                chatManage.getSummaryConfig().getContextTemplate(), vals);

        if (!chatManage.getQueryPlan().getImageDescription().isEmpty() && !chatManage.isChatModelSupportsVision()) {
            userContent += "\n\n[用户上传图片内容]\n" + chatManage.getQueryPlan().getImageDescription();
        }
        if (!chatManage.getQueryPlan().getQuotedContext().isEmpty()) {
            userContent += "\n\n" + chatManage.getQueryPlan().getQuotedContext();
        }
        if (chatManage.getAttachments() != null && !chatManage.getAttachments().isEmpty()) {
            userContent += MessageAttachmentsPrompt.build(chatManage.getAttachments());
        }

        chatManage.getAnswer().setUserContent(userContent);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", chatManage.getSessionId());
        out.put("user_content_len", chatManage.getAnswer().getUserContent().length());
        out.put("faq_priority", chatManage.isFaqPriorityEnabled());
        out.put("intent", chatManage.getQueryPlan().getIntent());
        out.put("image_description", chatManage.getQueryPlan().getImageDescription());
        out.put("chat_model_supports_vision", chatManage.isChatModelSupportsVision());
        PipelineLog.info("IntoChatMessage", "output", out);

        persistRenderedContent(chatManage);
        return next.next();
    }

    /** UserContent ≠ Query 时异步回写用户消息。 */
    private void persistRenderedContent(PipelineState chatManage) {
        if (chatManage.getRuntime().getUserMessageId().isEmpty() || chatManage.getAnswer().getUserContent().isEmpty()) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("sessionId", chatManage.getSessionId());
            f.put("userMessageId", chatManage.getRuntime().getUserMessageId());
            f.put("has_user_content", !chatManage.getAnswer().getUserContent().isEmpty());
            f.put("reason", "empty_id_or_content");
            PipelineLog.info("IntoChatMessage", "persist_rendered_content_skip", f);
            return;
        }
        if (chatManage.getAnswer().getUserContent().equals(chatManage.getQuery())) {
            return;
        }
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("sessionId", chatManage.getSessionId());
        f.put("userMessageId", chatManage.getRuntime().getUserMessageId());
        f.put("rendered_content_len", chatManage.getAnswer().getUserContent().length());
        PipelineLog.info("IntoChatMessage", "persist_rendered_content", f);
        final PipelineState cm = chatManage;
        Thread.ofVirtual().start(() -> {
            try {
                messageService.updateMessageRenderedContent(cm.getSessionId(),
                        cm.getRuntime().getUserMessageId(), cm.getAnswer().getUserContent());
            } catch (RuntimeException e) {
                Map<String, Object> w = new LinkedHashMap<>();
                w.put("sessionId", cm.getSessionId());
                w.put("userMessageId", cm.getRuntime().getUserMessageId());
                w.put("error", e.getMessage());
                PipelineLog.warn("IntoChatMessage", "persist_rendered_content_error", w);
            }
        });
    }

    /** &lt;documents&gt; 元数据头（title/desc/metadata 转义）。 */
    /**
     * 文档级媒体块：检索结果的 metadata 里带 {@code mediaRefs} 时，把它翻成模型能照抄的
     * {@code <media>} 段（poster / play 都是内部引用，前端可渲染）。
     *
     * <p>只有图片/视频/音频文档才有这段，且按知识条目去重，不会每个分块重复。</p>
     */
    private static final Set<String> VIDEO_TYPES =
            Set.of("mp4", "mov", "m4v", "webm", "mkv", "avi");
    private static final Set<String> AUDIO_TYPES =
            Set.of("mp3", "wav", "m4a", "flac", "ogg", "aac");

    static String mediaBlock(SearchResult r) {
        if (r.getMetadata() == null) {
            return "";
        }
        String raw = r.getMetadata().get("mediaRefs");
        if (raw == null || raw.isBlank()) {
            return "";
        }
        JsonNode node;
        try {
            node = JsonMappers.lenient().readTree(raw);
        } catch (IOException e) {
            return "";
        }
        if (node == null || !node.isObject()) {
            return "";
        }
        String poster = node.path("posterRef").asText("");
        String play = node.path("mediaRef").asText("");
        if (poster.isEmpty() && play.isEmpty()) {
            return "";
        }
        String kind = mediaKind(node.path("fileType").asText(""));
        StringBuilder b = new StringBuilder();
        b.append(String.format("<media type=\"%s\">\n", escapeHtml(kind)));
        if (!poster.isEmpty()) {
            b.append(String.format("<poster>%s</poster>\n", escapeHtml(poster)));
        }
        if (!play.isEmpty()) {
            b.append(String.format("<play>%s</play>\n", escapeHtml(play)));
        }
        b.append("  <usage>cite as ![poster](&lt;poster&gt;) and [play video](&lt;play&gt;) "
                + "(or [play audio](&lt;play&gt;)) when relevant</usage>\n");
        b.append("</media>\n");
        return b.toString();
    }

    /** 扩展名 → 媒体类型（video / audio / image）。 */
    static String mediaKind(String fileType) {
        String ext = fileType == null ? "" : fileType.toLowerCase(Locale.ROOT);
        if (VIDEO_TYPES.contains(ext)) {
            return "video";
        }
        if (AUDIO_TYPES.contains(ext)) {
            return "audio";
        }
        return "image";
    }

    public static String buildDocumentHeader(List<SearchResult> results) {
        record DocMeta(String title, String description, String metadata, String media) {}

        Map<String, Boolean> seen = new LinkedHashMap<>();
        List<DocMeta> docs = new ArrayList<>();

        for (SearchResult r : results) {
            if (r.getKnowledgeId().isEmpty()) {
                continue;
            }
            if (seen.containsKey(r.getKnowledgeId())) {
                continue;
            }
            seen.put(r.getKnowledgeId(), Boolean.TRUE);

            String title = r.getKnowledgeTitle();
            if (title.isEmpty()) {
                title = r.getKnowledgeFilename();
            }
            if (title.isEmpty()) {
                continue;
            }
            docs.add(new DocMeta(title,
                    r.getKnowledgeDescription() == null ? "" : r.getKnowledgeDescription(),
                    r.getKnowledgeCustomMetadata() == null ? "" : r.getKnowledgeCustomMetadata(),
                    mediaBlock(r)));
        }

        if (docs.isEmpty()) {
            return "";
        }

        StringBuilder b = new StringBuilder();
        b.append("<documents>\n");
        for (DocMeta d : docs) {
            b.append("<document>\n");
            b.append(String.format("<title>%s</title>\n", escapeHtml(d.title())));
            if (!d.description().isEmpty()) {
                b.append(String.format("<description>%s</description>\n", escapeHtml(d.description())));
            }
            if (!d.metadata().isEmpty()) {
                b.append(String.format("<metadata>%s</metadata>\n", escapeHtml(d.metadata())));
            }
            if (!d.media().isEmpty()) {
                b.append(d.media());
            }
            b.append("</document>\n");
        }
        b.append("</documents>");
        return b.toString();
    }

    /** HTML 五字符转义。 */
    private static String escapeHtml(String s) {
        return MessageAttachmentsPrompt.escapeHtml(s);
    }

}
