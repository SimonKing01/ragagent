package com.ragagent.wiki.service.ingest;

import com.ragagent.contract.knowledge.ChunkView;
import com.ragagent.contract.knowledge.KnowledgeSpanPort;
import com.ragagent.contract.knowledge.KnowledgeView;
import com.ragagent.contract.model.LlmChatClient;
import com.ragagent.contract.wiki.ExtractedItem;
import com.ragagent.contract.wiki.SlugUpdate;
import com.ragagent.contract.wiki.WikiImageMarkup;
import com.ragagent.contract.wiki.WikiLanguageSupport;
import com.ragagent.wiki.domain.ingest.WikiIngestConstants;
import com.ragagent.wiki.domain.ingest.WikiIngestPayload;
import com.ragagent.wiki.domain.ingest.WikiPendingOp;
import com.ragagent.wiki.prompt.WikiPrompts;
import com.ragagent.wiki.service.ingest.support.DocIngestResult;
import com.ragagent.wiki.service.ingest.support.WikiBatchContext;
import com.ragagent.wiki.service.ingest.support.WikiIngestPreviews;
import com.ragagent.wiki.service.page.NewSlugFromCitation;
import com.ragagent.wiki.service.page.WikiTextUtils;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * wiki 摄取 Map 阶段协作者:单文档 → 页面草图的 LLM 映射与文档标题解析。
 *
 * <p>持有 {@link WikiIngestBatchHandler} 回引以访问其依赖字段;本类不得独立实例化。</p>
 */
final class WikiIngestMapPhase {

    private static final Logger log = LoggerFactory.getLogger(WikiIngestMapPhase.class);


    private final WikiIngestBatchHandler handler;

    WikiIngestMapPhase(WikiIngestBatchHandler handler) {
        this.handler = handler;
    }



    /**
     * 对单篇文档跑完整 Map 阶段
     * ——取消守卫、分块重建、Pass 0 抽取（含早期版本回落）、摘要与引用并行、引用回填、
     * 身份重认领、新旧页面调和。
     *
     * @return {@code result == null && updates == null} 表示该文档在某个终态、不可重试的
     *         状态下被<b>跳过</b>（知识已删 / 无 chunk / 文本不足）
     * @throws RuntimeException 可重试的失败（调用方记入 failedOps）
     */
    public WikiIngestBatchHandler.MapResult mapOneDocument(LlmChatClient chatModel,
                                    WikiIngestPayload payload,
                                    WikiPendingOp op,
                                    WikiBatchContext batchCtx) {
        long docStartedAt = System.currentTimeMillis();
        String knowledgeId = op.getKnowledgeId();
        String lang = WikiLanguageSupport.resolveLanguageName(op.getLanguage());
        String kbId = payload.knowledgeBaseId();

        // 页级 span：沿 LatestAttempt →
        // postprocess stage 找父 span，在其下开 postprocess.wiki。找不到父 → null，
        // 后续 helper 全部 no-op（best-effort，追踪绝不阻断批次）。
        KnowledgeSpanPort.SpanHandle wikiSpan = handler.spans.beginWikiSubspan(knowledgeId,
                Map.of("language", lang, "knowledge_base_id", kbId));

        // 守卫 ingest/delete 竞争：用户在任务排队期间（wikiIngestDelay = 30 秒）或更早
        // 阶段在途期间删掉了文档，我们<b>绝不</b>能继续做 LLM 抽取——那会建出 source_refs
        // 指向幽灵 knowledge ID 的 wiki 页面，永远无法经 wiki_read_source_doc 到达。
        if (handler.ingestService.isKnowledgeGone(kbId, knowledgeId)) {
            log.info("wiki ingest: knowledge {} has been deleted, skip map", knowledgeId);
            handler.spans.skipSpan(wikiSpan, "knowledge_deleted");
            return new WikiIngestBatchHandler.MapResult(null, null);
        }

        DocumentContent doc = loadDocumentContent(payload, knowledgeId, wikiSpan);
        if (doc == null) {
            return new WikiIngestBatchHandler.MapResult(null, null);
        }
        List<ChunkView> chunks = doc.chunks();
        String content = doc.content();
        String docTitle = doc.docTitle();

        // 引用来源引用。<b>刻意</b>只用 knowledge ID（不用 docTitle，后者通常是上传文件名），
        // 这样文件名不会泄漏进下游 LLM prompt 可能读到的引用字符串里。
        String sourceRef = knowledgeId;
        Set<String> oldPageSlugs = handler.ingestService.getExistingPageSlugsForKnowledge(kbId, knowledgeId);

        Extraction extraction = extractCandidatesWithFallback(
                chatModel, kbId, content, lang, oldPageSlugs, batchCtx, wikiSpan, knowledgeId);
        List<ExtractedItem> extractedEntities = extraction.entities();
        List<ExtractedItem> extractedConcepts = extraction.concepts();
        Map<String, ExtractedItem> slugItems = extraction.slugItems();
        boolean pass0Failed = extraction.pass0Failed();
        List<String> summaryExtractedPages = new ArrayList<>(slugItems.keySet());
        SlugListing slug = buildSlugListing(slugItems, knowledgeId);
        String summarySlug = slug.summarySlug();

        ClassifyResult classified = classifyAndMerge(chatModel, chunks, content, lang,
                extractedEntities, extractedConcepts, slugItems, summaryExtractedPages,
                summarySlug, slug.listing(), pass0Failed, batchCtx, wikiSpan, knowledgeId, kbId);
        extractedEntities = classified.entities();
        extractedConcepts = classified.concepts();
        slugItems = classified.slugItems();
        List<DocIngestResult.PageRef> extractedPages = classified.extractedPages();
        Set<String> citedChunkSet = classified.citedChunkSet();
        int uncited = classified.uncited();

        UpdateResult updateResult = buildUpdates(extractedEntities, extractedConcepts,
                extractedPages, classified.summaryContent(), summarySlug, docTitle, knowledgeId,
                sourceRef, lang, oldPageSlugs, content,
                batchCtx, classified.summaryErr(), wikiSpan);
        List<SlugUpdate> updates = updateResult.updates();
        String docSummary = updateResult.docSummary();
        String docSummaryLine = updateResult.docSummaryLine();
        int reparseOverlap = updateResult.reparseOverlap();
        int staleCount = updateResult.staleCount();

        log.info("wiki ingest: mapped knowledge {} title={} candidates={} chunks={} batches={} "
                        + "cited_chunks={} uncited_slugs={} new_slugs={} updates={} reparse_slugs={} "
                        + "stale_slugs={} pass0_fallback={} elapsed={}ms",
                knowledgeId, WikiTextUtils.previewText(docTitle, 80),
                slugItems.size(), chunks.size(), classified.batchCount(), citedChunkSet.size(),
                uncited, classified.newSlugCount(), updates.size(), reparseOverlap, staleCount,
                pass0Failed, System.currentTimeMillis() - docStartedAt);

        // Map 阶段的指标挂到 postprocess.wiki span 的 output 上，但<b>不</b>在这里 EndSpan
        // ——批次驱动方会让这个 span 一直开到 reduce + 索引重建 + 交叉链接注入 + 页面发布
        // 全部结束，再在文档页面全部写出后关闭它。
        Map<String, Object> mapStats = new LinkedHashMap<>();
        mapStats.put("doc_title", WikiTextUtils.previewText(docTitle, 120));
        mapStats.put("chunks", chunks.size());
        mapStats.put("candidate_slugs", slugItems.size());
        mapStats.put("cited_chunks", citedChunkSet.size());
        mapStats.put("uncited_slugs", uncited);
        mapStats.put("new_slugs", classified.newSlugCount());
        mapStats.put("updates", updates.size());
        mapStats.put("reparse_slugs", reparseOverlap);
        mapStats.put("stale_slugs", staleCount);
        mapStats.put("extracted_pages", extractedPages.size());
        mapStats.put("summary_chars", docSummary.codePointCount(0, docSummary.length()));
        mapStats.put("pass0_fallback", pass0Failed);
        mapStats.put("classify_batches", classified.batchCount());
        mapStats.put("summary_preview", WikiTextUtils.previewText(docSummaryLine, 160));

        DocIngestResult result = new DocIngestResult(knowledgeId);
        result.setDocTitle(docTitle);
        result.setSummary(docSummaryLine);
        result.setPages(extractedPages);
        result.setMapStats(mapStats);
        result.setWikiSpan(wikiSpan);
        return new WikiIngestBatchHandler.MapResult(result, updates);
    }

    /** 单篇文档的内容载荷；三处终态跳过统一以 {@code null} 表达。 */
    record DocumentContent(List<ChunkView> chunks, String content, String docTitle) {
    }

    /** Pass 0 抽取结果；{@code pass0Failed=true} 表示走的是单次抽取回落路径。 */
    record Extraction(List<ExtractedItem> entities, List<ExtractedItem> concepts,
                      Map<String, ExtractedItem> slugItems, boolean pass0Failed) {
    }

    /** Summary 的 wiki-link 输入：摘要页 slug + 逐行 slug 清单。 */
    record SlugListing(String summarySlug, String listing) {
    }

    /**
     * Pass 0 候选 slug 抽取 + 单次抽取回落。
     *
     * <p>回落路径是<b>有意的降级</b>：Pass 0 失败时文档仍要能被摄取，只是没有 chunk 级引用。
     * 两条路径都失败才上抛（可重试）。</p>
     */
    Extraction extractCandidatesWithFallback(LlmChatClient chatModel, String kbId, String content,
            String lang, Set<String> oldPageSlugs, WikiBatchContext batchCtx,
            KnowledgeSpanPort.SpanHandle wikiSpan, String knowledgeId) {
        log.info("wiki ingest: pass 0 — extracting candidate slugs for {}", knowledgeId);
        Map<String, Object> extractInput = new LinkedHashMap<>();
        extractInput.put("content_chars", content.codePointCount(0, content.length()));
        extractInput.put("old_pages", oldPageSlugs == null ? 0 : oldPageSlugs.size());
        KnowledgeSpanPort.SpanHandle extractSpan = handler.spans.beginSubSpan(wikiSpan,
                "postprocess.wiki.extract", extractInput);

        List<ExtractedItem> entities;
        List<ExtractedItem> concepts;
        Map<String, ExtractedItem> slugItems;
        boolean pass0Failed = false;
        try {
            WikiIngestCitePipeline.CandidateSlugs candidates = handler.citePipeline.extractCandidateSlugs(
                    chatModel, kbId, content, lang, oldPageSlugs, batchCtx);
            entities = candidates.entities();
            concepts = candidates.concepts();
            slugItems = candidates.slugItems();
        } catch (RuntimeException e) {
            log.warn("wiki ingest: pass 0 failed for {} () — falling back to single-pass extractor",
                    knowledgeId, e);
            pass0Failed = true;
            try {
                WikiIngestCitePipeline.CandidateSlugs fallback =
                        handler.citePipeline.extractEntitiesAndConceptsNoUpsert(
                                chatModel, kbId, content, lang, oldPageSlugs, batchCtx);
                entities = fallback.entities();
                concepts = fallback.concepts();
                slugItems = fallback.slugItems();
            } catch (RuntimeException e2) {
                log.warn("wiki ingest: single-pass fallback also failed for {}", knowledgeId, e2);
                handler.spans.failSpan(extractSpan, "EXTRACT_FAILED", e2.getMessage(), e2);
                handler.spans.failSpan(wikiSpan, "EXTRACT_FAILED", e2.getMessage(), e2);
                throw e2;
            }
        }

        Map<String, Object> extractOut = new LinkedHashMap<>();
        extractOut.put("entities", entities.size());
        extractOut.put("concepts", concepts.size());
        extractOut.put("pass0_fallback", pass0Failed);
        extractOut.put("entities_preview", WikiIngestPreviews.previewExtractedItems(entities, 8));
        extractOut.put("concepts_preview", WikiIngestPreviews.previewExtractedItems(concepts, 8));
        handler.spans.endSpan(extractSpan, extractOut);
        return new Extraction(entities, concepts, slugItems, pass0Failed);
    }

    /**
     * 构造 Summary 的 wiki-link 输入。
     *
     * <p>摘要 slug 由 knowledge ID 派生，而非 {@code docTitle}（通常是上传文件名）——
     * 文件名式 slug 会在下游 LLM prompt 的交叉链接语境里暴露文件名。</p>
     */
    SlugListing buildSlugListing(Map<String, ExtractedItem> slugItems, String knowledgeId) {
        StringBuilder listing = new StringBuilder();
        for (Map.Entry<String, ExtractedItem> entry : slugItems.entrySet()) {
            String slug = entry.getKey();
            ExtractedItem item = entry.getValue();
            listing.append("- [[").append(slug).append("]]");
            if (item != null) {
                listing.append(" = ").append(item.getName());
                if (!item.getAliases().isEmpty()) {
                    listing.append(" (Aliases: ").append(String.join(", ", item.getAliases())).append(")");
                }
            }
            listing.append('\n');
        }
        return new SlugListing("summary/" + WikiTextUtils.slugify(knowledgeId), listing.toString());
    }

    /**
     * 加载本篇文档的正文：chunk 列表 → 富文本重建 → 截断 → 文本充分性守卫。
     *
     * <p><b>三处返回 {@code null} 的终态</b>（不可重试，调用方直接跳过）：知识已删 /
     * 无 chunk / 去图片标记后文本不足。每处都先 {@code skipSpan} 留 trace 证据。</p>
     */
    DocumentContent loadDocumentContent(WikiIngestPayload payload, String knowledgeId,
            KnowledgeSpanPort.SpanHandle wikiSpan) {
        List<ChunkView> chunks;
        try {
            chunks = handler.listTextChunksByKnowledgeId(payload.tenantId(), knowledgeId);
        } catch (Exception e) {
            handler.spans.failSpan(wikiSpan, "LIST_CHUNKS_FAILED", e.getMessage(), e);
            throw new IllegalStateException("get chunks: " + e.getMessage(), e);
        }
        if (chunks.isEmpty()) {
            log.info("wiki ingest: document {} has no chunks, skip", knowledgeId);
            handler.spans.skipSpan(wikiSpan, "no_chunks");
            return null;
        }

        String content = handler.ingestService.reconstructEnrichedContent(chunks, payload.tenantId());
        int rawRuneCount = content.codePointCount(0, content.length());
        if (rawRuneCount > WikiIngestConstants.MAX_CONTENT_FOR_WIKI) {
            content = content.substring(0,
                    content.offsetByCodePoints(0, WikiIngestConstants.MAX_CONTENT_FOR_WIKI));
        }
        log.info("wiki ingest: doc {} chunks={} content_len(raw={},truncated={})",
                knowledgeId, chunks.size(), rawRuneCount, content.codePointCount(0, content.length()));

        // 没有真实文本时拒绝跑 LLM 抽取——扫描版 PDF 的页面被转成图片、VLM OCR 又没产出，
        // 只剩图片标记时 LLM 会编造实体与概念。
        if (!WikiImageMarkup.hasSufficientTextContent(content)) {
            log.warn("wiki ingest: doc {} has insufficient text content after stripping image markup "
                    + "(raw_len={}), skipping LLM extraction", knowledgeId, rawRuneCount);
            handler.spans.skipSpan(wikiSpan, "insufficient_text_content");
            return null;
        }

        return new DocumentContent(chunks, content, resolveDocTitle(knowledgeId, chunks));
    }

    /**
     * 文档标题优先取知识行，取不到就回落到
     * 第一个非空 chunk 的首行（短于 200 字节时），并裁掉 markdown 的 {@code "# "} 前缀。
     */
    String resolveDocTitle(String knowledgeId, List<ChunkView> chunks) {
        KnowledgeView kn = handler.getKnowledgeByIdOnly(knowledgeId);
        if (kn != null && kn.title() != null && !kn.title().isEmpty()) {
            return kn.title();
        }
        for (ChunkView ch : chunks) {
            if (ch.getContent() == null || ch.getContent().isEmpty()) {
                continue;
            }
            int idx = ch.getContent().indexOf('\n');
            String firstLine = idx < 0 ? ch.getContent() : ch.getContent().substring(0, idx);
            if (!firstLine.isEmpty() && firstLine.length() < 200) {
                String trimmed = firstLine.trim();
                if (trimmed.startsWith("# ")) {
                    trimmed = trimmed.substring(2);
                }
                return trimmed;
            }
        }
        return knowledgeId;
    }
    private record UpdateResult(List<SlugUpdate> updates, String docSummary,
            String docSummaryLine, int reparseOverlap, int staleCount) {
    }
    private UpdateResult buildUpdates(List<ExtractedItem> extractedEntities,
            List<ExtractedItem> extractedConcepts, List<DocIngestResult.PageRef> extractedPages,
            String summaryContent, String summarySlug, String docTitle, String knowledgeId,
            String sourceRef, String lang, Set<String> oldPageSlugs, String content,
            WikiBatchContext batchCtx, RuntimeException summaryErr,
            KnowledgeSpanPort.SpanHandle wikiSpan) {
        List<SlugUpdate> updates = new ArrayList<>();
        // docSummaryLine 是用于简短日志/审计预览与 retract prompt 里 <document_added> 块的
        // 一句话标题。docSummary 是挂到每个 entity/concept 更新上的完整摘要正文，
        // 让编辑模型在 <source_context> 里拿到丰富的框定信息。
        String docSummaryLine;
        String docSummary;

        if (summaryErr != null) {
            // 摘要是被摄取文档的头号产物——没有摘要页的文档只算半摄取，而且会让
            // entity/concept 更新悬空、没有根可以链回索引。历史上这里只记日志就走，
            // 意味着一发瞬时 504 会永久丢掉该文档的摘要页。
            //
            // 这里返回错误会把该 op 送进 failedOps（见 ProcessWikiIngest 的 map 阶段循环），
            // requeueFailedOps 随后把它追加回待办列表，让下一批次重试。
            // generateWithTemplate 内部的退避重试已经在我们放弃之前耗尽了 LLM 自己的瞬时
            // 错误预算。
            log.error("wiki ingest: generate summary failed for {}, will requeue", knowledgeId, summaryErr);
            handler.spans.failSpan(wikiSpan, "SUMMARY_FAILED", summaryErr.getMessage(), summaryErr);
            throw new IllegalStateException("generate summary: " + summaryErr.getMessage(), summaryErr);
        }

        WikiTextUtils.SummaryLine parts = WikiTextUtils.splitSummaryLine(summaryContent);
        String sumLine = parts.summary();
        String sumBody = parts.content();
        if (sumBody.isEmpty()) {
            sumBody = summaryContent;
        }
        if (sumLine.isEmpty()) {
            sumLine = docTitle;
        }
        docSummaryLine = sumLine;
        docSummary = sumBody;
        if (docSummary.trim().isEmpty()) {
            docSummary = sumLine;
        }

        SlugUpdate summaryUpdate = new SlugUpdate(summarySlug, SlugUpdate.TYPE_SUMMARY);
        summaryUpdate.setDocTitle(docTitle);
        summaryUpdate.setKnowledgeId(knowledgeId);
        summaryUpdate.setSourceRef(sourceRef);
        summaryUpdate.setLanguage(lang);
        summaryUpdate.setSummaryLine(sumLine);
        summaryUpdate.setSummaryBody(sumBody);
        updates.add(summaryUpdate);
        extractedPages.add(new DocIngestResult.PageRef(summarySlug, docTitle));

        // Entities
        for (ExtractedItem item : extractedEntities) {
            if (item.getSlug().isEmpty()) {
                continue;
            }
            SlugUpdate u = new SlugUpdate(item.getSlug(), SlugUpdate.TYPE_ENTITY);
            u.setItem(item);
            u.setDocTitle(docTitle);
            u.setKnowledgeId(knowledgeId);
            u.setSourceRef(sourceRef);
            u.setLanguage(lang);
            u.setSourceChunks(item.getSourceChunks());
            u.setDocSummary(docSummary);
            updates.add(u);
        }

        // Concepts
        for (ExtractedItem item : extractedConcepts) {
            if (item.getSlug().isEmpty()) {
                continue;
            }
            SlugUpdate u = new SlugUpdate(item.getSlug(), SlugUpdate.TYPE_CONCEPT);
            u.setItem(item);
            u.setDocTitle(docTitle);
            u.setKnowledgeId(knowledgeId);
            u.setSourceRef(sourceRef);
            u.setLanguage(lang);
            u.setSourceChunks(item.getSourceChunks());
            u.setDocSummary(docSummary);
            updates.add(u);
        }

        // 调和旧页面集合与新的抽取结果。三种情形：
        //   (a) oldSlug ∉ new  → "retractStale"：文档不再提及该主题，剥掉它的引用
        //       （若这是唯一来源则可能删除页面）。用<b>新</b>正文作为撤回语境——如果 LLM
        //       找到匹配的事实就裁掉它们，否则这次撤回近似 no-op，这没问题。
        //   (b) oldSlug ∈ new 且是 entity/concept → reparse 换血：同时发出 "retract"
        //       （携带文档<b>上一版</b>摘要正文作为回落信号）与常规新增。reduce 阶段看到
        //       HasAdditions=1 + HasRetractions=1，WikiPageModifyUserPrompt 正确地告诉编辑
        //       模型一次性"删掉旧 K 段、加上新 K 段"——给出替换语义而不是"在旧 K 上追加新 K"。
        //   (c) oldSlug ∈ new 且是 summary 页 → 什么都不做（reduce 的 summary 分支会整体
        //       覆盖，多发一次 retract 只会是死重）。
        //
        // priorContribution 是文档的<b>最后一份</b>摘要正文，在此处懒取
        // （而不是预先装进批次上下文）。首次摄取时为空——那时 oldPageSlugs 也为空，
        // 因此永远不会咨询它。
        String priorContribution = batchCtx == null
                ? "" : batchCtx.summaryContentByKnowledgeId(knowledgeId);

        Set<String> newSlugSet = new LinkedHashSet<>();
        for (DocIngestResult.PageRef ref : extractedPages) {
            newSlugSet.add(ref.slug());
        }

        int reparseOverlap = 0;
        int staleCount = 0;
        if (oldPageSlugs != null) {
            for (String oldSlug : oldPageSlugs) {
                if (newSlugSet.contains(oldSlug)) {
                    // 跳过 summary slug——它们会被 summary 更新整体覆盖，多发一次 retract
                    // 在下游只会被丢弃。
                    if (oldSlug.startsWith("summary/")) {
                        continue;
                    }
                    reparseOverlap++;
                    updates.add(SlugUpdate.retract(oldSlug, knowledgeId, docTitle,
                            priorContribution, lang));
                    continue;
                }
                staleCount++;
                SlugUpdate u = new SlugUpdate(oldSlug, SlugUpdate.TYPE_RETRACT_STALE);
                u.setRetractDocContent(content);
                u.setDocTitle(docTitle);
                u.setKnowledgeId(knowledgeId);
                u.setLanguage(lang);
                updates.add(u);
            }
        }

        return new UpdateResult(updates, docSummary, docSummaryLine, reparseOverlap, staleCount);
    }


    private record ClassifyResult(List<ExtractedItem> entities, List<ExtractedItem> concepts,
            Map<String, ExtractedItem> slugItems, List<DocIngestResult.PageRef> extractedPages,
            Set<String> citedChunkSet, int uncited, String summaryContent,
            RuntimeException summaryErr, int batchCount, int newSlugCount) {
    }
    private ClassifyResult classifyAndMerge(LlmChatClient chatModel, List<ChunkView> chunks,
            String content, String lang, List<ExtractedItem> extractedEntities,
            List<ExtractedItem> extractedConcepts, Map<String, ExtractedItem> slugItems,
            List<String> summaryExtractedPages, String summarySlug, String slugListing,
            boolean pass0Failed, WikiBatchContext batchCtx,
            KnowledgeSpanPort.SpanHandle wikiSpan, String knowledgeId, String kbId) {
        // 摘要与分块分类在 Pass 0 输出给定的前提下互相独立——并行跑。
        // 摘要负责 wiki-link 注入；分类给每个候选 slug 挂上具体的 chunk ID。
        final String[] summaryContentHolder = { null };
        final RuntimeException[] summaryErrHolder = { null };
        @SuppressWarnings("unchecked")
        final Map<String, List<String>>[] citationsHolder = new Map[] { Map.of() };
        @SuppressWarnings("unchecked")
        final List<NewSlugFromCitation>[] newSlugsHolder = new List[] { List.of() };
        final int[] batchCountHolder = { 0 };

        Map<String, Object> summaryInput = new LinkedHashMap<>();
        summaryInput.put("content_chars", content.codePointCount(0, content.length()));
        summaryInput.put("extracted_slugs", summaryExtractedPages.size());
        KnowledgeSpanPort.SpanHandle summarySpan = handler.spans.beginSubSpan(wikiSpan,
                "postprocess.wiki.summary", summaryInput);
        Map<String, Object> classifyInput = new LinkedHashMap<>();
        classifyInput.put("chunks", chunks.size());
        classifyInput.put("candidates", extractedEntities.size() + extractedConcepts.size());
        // 两条调用在同一个 wikiSpan 父节点下并行跑——它们的子 span 在 trace 视图里会视觉
        // 重叠，这正确反映了它们的墙钟并发。
        KnowledgeSpanPort.SpanHandle classifySpan = pass0Failed
                ? null
                : handler.spans.beginSubSpan(wikiSpan, "postprocess.wiki.classify", classifyInput);

        final boolean pass0FailedFinal = pass0Failed;
        final String contentFinal = content;
        final List<ExtractedItem> entitiesFinal = extractedEntities;
        final List<ExtractedItem> conceptsFinal = extractedConcepts;
        final String slugListingFinal = slugListing;
        List<Runnable> parallel = new ArrayList<>(2);
        parallel.add(() -> {
            try {
                String generated = handler.ingestService.generateWithTemplate(chatModel,
                        WikiPrompts.WIKI_SUMMARY_PROMPT,
                        Map.of(
                                "Content", contentFinal,
                                "Language", lang == null ? "" : lang,
                                "ExtractedSlugs", slugListingFinal,
                                "CustomInstructions",
                                batchCtx == null ? "" : batchCtx.getContentInstructions(),
                                "InstructionScope", WikiBatchConstants.INSTRUCTION_SCOPE_CONTENT));
                summaryContentHolder[0] = generated;
                WikiTextUtils.SummaryLine parts = WikiTextUtils.splitSummaryLine(generated);
                Map<String, Object> summaryOut = new LinkedHashMap<>();
                summaryOut.put("chars", generated.codePointCount(0, generated.length()));
                summaryOut.put("summary_line", WikiTextUtils.previewText(parts.summary(), 160));
                summaryOut.put("body_preview", WikiTextUtils.previewText(parts.content(), 320));
                handler.spans.endSpan(summarySpan, summaryOut);
            } catch (RuntimeException e) {
                summaryErrHolder[0] = e;
                handler.spans.failSpan(summarySpan, "SUMMARY_FAILED", e.getMessage(), e);
            }
        });
        parallel.add(() -> {
            // Pass 0 走回落路径时跳过引用阶段——该路径输出本身已包含改写过的 Details，
            // 再做 chunk 引用是多余的，只会白花 LLM 调用。
            if (pass0FailedFinal) {
                citationsHolder[0] = new LinkedHashMap<>();
                return;
            }
            String candidatesXml = WikiIngestCitePipeline.renderCandidateSlugsXML(
                    entitiesFinal, conceptsFinal);
            WikiIngestCitePipeline.CitationResult citationResult = handler.citePipeline.classifyChunkCitations(
                    chatModel, candidatesXml, chunks, lang, batchCtx);
            citationsHolder[0] = citationResult.citations();
            newSlugsHolder[0] = citationResult.newSlugs();
            batchCountHolder[0] = citationResult.batchCount();
            Map<String, Object> classifyOut = new LinkedHashMap<>();
            classifyOut.put("cited_slugs", citationResult.citations().size());
            classifyOut.put("new_slugs", citationResult.newSlugs().size());
            classifyOut.put("batches", citationResult.batchCount());
            classifyOut.put("top_cited",
                    WikiIngestPreviews.topCitedSlugs(citationResult.citations(), 8));
            classifyOut.put("new_slugs_sample",
                    WikiIngestPreviews.previewNewSlugs(citationResult.newSlugs(), 8));
            handler.spans.endSpan(classifySpan, classifyOut);
        });
        WikiBatchSupport.fanOut(2, parallel);

        // 把引用合并回条目结构（不失败；没有引用的条目简单保留 Description+Details 回落）。
        WikiIngestCitePipeline.MergedCitations merged = WikiIngestCitePipeline.mergeCitationsIntoItems(
                extractedEntities, extractedConcepts, citationsHolder[0], newSlugsHolder[0]);
        extractedEntities = merged.entities();
        extractedConcepts = merged.concepts();
        int uncited = merged.uncited();
        WikiIngestDedupService.Identities reclaimed = handler.dedupService.reclaimExtractedIdentities(
                kbId, extractedEntities, extractedConcepts, batchCtx);
        extractedEntities = reclaimed.entities();
        extractedConcepts = reclaimed.concepts();

        // 重建 slugItems，让"没能挺过合并的陈旧条目"与"引用遍发现的崭新 slug"都反映到
        // summaryExtractedPages 的追踪里。
        slugItems = new LinkedHashMap<>();
        for (ExtractedItem item : extractedEntities) {
            if (!item.getSlug().isEmpty() && !item.getName().isEmpty()) {
                slugItems.put(item.getSlug(), item);
            }
        }
        for (ExtractedItem item : extractedConcepts) {
            if (!item.getSlug().isEmpty() && !item.getName().isEmpty()) {
                slugItems.put(item.getSlug(), item);
            }
        }

        // extractedPages 记录本文件物化出的每一个 wiki 页面（entities、concepts，加上下面
        // 追加的摘要页）。slug 用于链接/撤回记账；title 保留给 trace 输出与 finalize 处理用。
        List<DocIngestResult.PageRef> extractedPages = new ArrayList<>(slugItems.size() + 1);
        for (Map.Entry<String, ExtractedItem> e : slugItems.entrySet()) {
            String title = e.getValue().getName();
            if (title.isEmpty()) {
                title = e.getKey();
            }
            extractedPages.add(new DocIngestResult.PageRef(e.getKey(), title));
        }

        // 统计所有 slug 引用到的不同 chunk 数（用于日志）。
        Set<String> citedChunkSet = WikiIngestCitePipeline.citedChunkSet(citationsHolder[0]);

        return new ClassifyResult(extractedEntities, extractedConcepts, slugItems,
                extractedPages, citedChunkSet, uncited, summaryContentHolder[0],
                summaryErrHolder[0], batchCountHolder[0], newSlugsHolder[0].size());
    }


}
