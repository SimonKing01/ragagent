package com.ragagent.knowledge.service.chunk;


import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.contract.graph.EntityExtractionResult;
import com.ragagent.contract.graph.GraphData;
import com.ragagent.contract.graph.GraphNode;
import com.ragagent.contract.graph.GraphRelation;
import com.ragagent.contract.graph.NameSpace;
import com.ragagent.contract.model.EntityExtractionPort;
import com.ragagent.contract.model.LlmChatClient;
import com.ragagent.contract.model.ModelRuntime;
import com.ragagent.contract.retrieval.RetrievalObsRows;
import com.ragagent.contract.retrieval.RetrieveGraphRepository;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.ChunkExtractConsumer;
import com.ragagent.knowledge.domain.ExtractChunkPayload;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.domain.KnowledgeProcessingSpan;
import com.ragagent.knowledge.domain.span.SpanTracker;
import com.ragagent.knowledge.repository.ChunkRepository;
import com.ragagent.knowledge.repository.KnowledgeBaseRepository;
import com.ragagent.knowledge.repository.KnowledgeRepository;
import com.ragagent.knowledge.runtime.KnowledgeTrace;
import com.ragagent.knowledge.support.SubtaskDrain;
import com.ragagent.platform.access.TenantScopes;
import com.ragagent.platform.prompt.ExtractPrompts;
import com.ragagent.platform.prompt.PipelineConfig;
import com.ragagent.platform.prompt.PromptInstructions;
import com.ragagent.wiki.service.DefaultWikiKnowledgeFinalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 分块图抽取任务：
 * 每个文本/OCR 分块一次 LLM 抽取 → 实体/关系写入图库 → 释放父知识的 finalizing 槽。
 * <ol>
 *   <li><b>supersede 跳过</b>：更新的 attempt 已取代本次 → 在开 span/计数前直接返回
 *       （该分块已被新 attempt 的清理删掉，递减会误耗新计数）；</li>
 *   <li>开 {@code postprocess.graph.chunk[i]} 子 span（父 = 本 attempt 的 postprocess 阶段；
 *       载荷缺 knowledge_id/attempt 的旧任务静默跳过）；</li>
 *   <li>取消/删除短路（{@code deleting}/{@code cancelled} → 记 {@code skipped} 返回）；</li>
 *   <li>取分块 → 记内容形态（{@code chunk_chars}/{@code chunk_preview}）→ 取 KB；</li>
 *   <li>抽取配置闸门（{@code extract_config.enabled=false} → {@code skipped=extract_disabled}）；</li>
 *   <li>按配置组装模板（原模板 + CustomInstructions / Tags / Examples）→ 抽取；</li>
 *   <li>重取分块（抽取期间可能消失 → {@code skipped=chunk_disappeared}）→
 *       {@code node.chunks=[chunk_id]} → {@code AddGraph}；</li>
 *   <li>记账 {@code nodes_added}/{@code relations_added} + 前两个样例；</li>
 *   <li>终态（无论成功、跳过还是失败）在 finally 里<b>释放一个 finalizing 槽</b>
 *       。</li>
 * </ol>
 */
@Service
public class ChunkExtractService implements ChunkExtractConsumer {

    private static final Logger log = LoggerFactory.getLogger(ChunkExtractService.class);

    /** 任务观测的 span/根名（{@code 任务队列.<type>}）。 */
    public static final String TASK_TYPE_CHUNK_EXTRACT = "chunk:extract";

    static final int CHUNK_PREVIEW_RUNES = 200;

    private final ModelRuntime modelRuntimeAssembler;
    private final ChunkRepository chunkRepository;
    private final KnowledgeRepository knowledgeRepository;
    private final KnowledgeBaseRepository kbRepository;
    private final SpanTracker spanTracker;
    private final RetrieveGraphRepository graphRepository;
    private final DefaultWikiKnowledgeFinalizer finalizer;
    private final ExtractPrompts extractPrompts;
    private final EntityExtractionPort entityExtraction;

    /** 生产构造器（Spring 装配；模板来源用 vendored extract 配置）。 */
    @Autowired
    public ChunkExtractService(ModelRuntime modelRuntimeAssembler,
                               ChunkRepository chunkRepository,
                               KnowledgeRepository knowledgeRepository,
                               KnowledgeBaseRepository kbRepository,
                               SpanTracker spanTracker,
                               RetrieveGraphRepository graphRepository,
                               DefaultWikiKnowledgeFinalizer finalizer,
            EntityExtractionPort entityExtraction) {
        this(modelRuntimeAssembler, chunkRepository, knowledgeRepository, kbRepository, spanTracker,
                graphRepository, finalizer, new ExtractPrompts(), entityExtraction);
    }

    /** 测试可注入模板来源。 */
    ChunkExtractService(ModelRuntime modelRuntimeAssembler,
                        ChunkRepository chunkRepository,
                        KnowledgeRepository knowledgeRepository,
                        KnowledgeBaseRepository kbRepository,
                        SpanTracker spanTracker,
                        RetrieveGraphRepository graphRepository,
                        DefaultWikiKnowledgeFinalizer finalizer,
                        ExtractPrompts extractPrompts,
                        EntityExtractionPort entityExtraction) {
        this.modelRuntimeAssembler = modelRuntimeAssembler;
        this.chunkRepository = chunkRepository;
        this.knowledgeRepository = knowledgeRepository;
        this.kbRepository = kbRepository;
        this.spanTracker = spanTracker;
        this.graphRepository = graphRepository;
        this.finalizer = finalizer;
        this.extractPrompts = extractPrompts;
        this.entityExtraction = entityExtraction;
    }

    /** 队列入口：JSON 载荷 → 任务作用域→ 处理（默认按终态处理）。 */
    public void handleJson(String payloadJson) {
        handleJson(payloadJson, true);
    }

    /**
 * @param terminal 本次是否是该任务的**最后一次**尝试（队列侧按 {@code attempt > MAX_RETRY} 传入）。
 * 与 {@link com.ragagent.knowledge.service.enrichment.QuestionGenerationService QuestionGenerationService} 同款契约：失败且还会重试时**不**递减
 * 槽位——否则重试链/重复投递会把槽位扣穿、父知识在富化落库前就提前完成。
 */
    public void handleJson(String payloadJson, boolean terminal) {
        ExtractChunkPayload p = ExtractChunkPayload.fromJson(payloadJson);
        // 富化跑在队列虚拟线程上：没有 HTTP Filter 填 TenantContext，而模型解析按租户可见性
        // 过滤（payload 已显式携带租户）⇒ 这里进入作用域，别让自建模型查不到。
        try (TenantScopes.TenantScope ignoredScope = TenantScopes.enter(p.tenantId());
                KnowledgeTrace.Scope scope = KnowledgeTrace.startTask(TASK_TYPE_CHUNK_EXTRACT, p.tracing(),
                Map.of("chunk_id", p.chunkId(), "knowledge_id", p.knowledgeId()),
                KnowledgeTrace.previewPayload(payloadJson))) {
            handle(p, terminal);
        }
    }

    public void handle(ExtractChunkPayload p) {
        handle(p, true);
    }

    /** @param terminal 见 {@link #handleJson(String, boolean)}。 */
    public void handle(ExtractChunkPayload p, boolean terminal) {
        // 1) supersede 跳过
        if (spanTracker.isAttemptSuperseded(p.knowledgeId(), p.attempt())) {
            log.info("graph extract: attempt {} superseded for {}, skipping stale enrichment",
                    p.attempt(), p.knowledgeId());
            return;
        }

        // 2) postprocess.graph.chunk[i] 子 span
        SpanTracker.SpanHandle gSpan = null;
        if (!p.knowledgeId().isEmpty() && p.attempt() > 0) {
            SpanTracker.SpanHandle parent = spanTracker.lookupStage(p.knowledgeId(), p.attempt(),
                    KnowledgeProcessingSpan.STAGE_POST_PROCESS);
            if (parent != null) {
                Map<String, Object> input = new LinkedHashMap<>();
                input.put("chunk_id", p.chunkId());
                input.put("chunk_index", p.chunkIndex());
                input.put("model_id", p.modelId());
                gSpan = spanTracker.beginSubSpan(parent,
                        "postprocess.graph.chunk[" + p.chunkIndex() + "]",
                        KnowledgeProcessingSpan.KIND_SUB_SPAN, input);
            }
        }

        Map<String, Object> graphOut = new LinkedHashMap<>();
        String handleErr = null;
        boolean succeeded = false;
        try {
            handleErr = runExtract(p, graphOut);
            succeeded = true;
        } catch (RuntimeException e) {
            handleErr = e.getMessage() == null ? e.toString() : e.getMessage();
            throw e;
        } finally {
            // 3) 终态释放槽位（成功或最后一次尝试才递减——重试链只释放一次）
            if (succeeded || terminal) {
                SubtaskDrain.drain(p.knowledgeId(), "graph_chunk[" + p.chunkIndex() + "]",
                        () -> finalizer.finalizeWikiSubtask(p.knowledgeId()), log);
            }
            // 4) 子 span 收尾
            if (gSpan != null) {
                if (handleErr != null) {
                    spanTracker.failSpan(gSpan, "GRAPH_EXTRACT_FAILED", handleErr, null);
                } else {
                    spanTracker.endSpan(gSpan, graphOut);
                }
            }
        }
    }

    /** @return 失败消息（null = 成功或"按设计跳过"） */
    private String runExtract(ExtractChunkPayload p, Map<String, Object> graphOut) {
        // 取消/删除短路：图抽取是管线里最贵的富化 fan-out，取消即跳
        if (!p.knowledgeId().isEmpty()) {
            Knowledge k = knowledgeRepository.findByIdAny(p.knowledgeId());
            if (k != null && k.isAborted()) {
                log.info("graph extract: knowledge {} aborted ({}), skipping chunk {}",
                        p.knowledgeId(), k.getParseStatus(), p.chunkId());
                graphOut.put("skipped", "knowledge_" + k.getParseStatus());
                return null;
            }
        }

        Chunk chunk = chunkRepository.getChunkById(p.tenantId(), p.chunkId());
        if (chunk == null) {
            //：失败必须抛出——返回字符串会被队列当成成功 ack（既不重试也不按终态释放槽位）
            throw new IllegalStateException("failed to get chunk: " + p.chunkId());
        }
        // 内容形态：让 trace 回答"这次 LLM 调用看到了什么"
        graphOut.put("chunk_chars", chunk.getContent().codePointCount(0, chunk.getContent().length()));
        graphOut.put("chunk_preview", RetrievalObsRows.truncateRunes(chunk.getContent(), CHUNK_PREVIEW_RUNES));

        KnowledgeBase kb = kbRepository.findByIdAny(chunk.getKnowledgeBaseId());
        if (kb == null) {
            throw new IllegalStateException("failed to get knowledge base: " + chunk.getKnowledgeBaseId());
        }

        JsonNode extractCfg = kb.getExtractConfig();
        if (extractCfg == null || !extractCfg.path("enabled").asBoolean(false)) {
            log.warn("extract config not enabled");
            graphOut.put("skipped", "extract_disabled");
            return null;
        }

        LlmChatClient chatModel;
        try {
            chatModel = modelRuntimeAssembler.getChatModel(p.modelId());
        } catch (RuntimeException e) {
            throw new IllegalStateException("failed to get chat model: " + e.getMessage(), e);
        }

        EntityExtractionResult graph;
        try {
            graph = entityExtraction.extract(chatModel, buildTemplate(extractCfg),
                    chunk.getContent());
        } catch (RuntimeException e) {
            throw new IllegalStateException("failed to extract entities: " + e.getMessage(), e);
        }

        Chunk again = chunkRepository.getChunkById(p.tenantId(), p.chunkId());
        if (again == null) {
            // 抽取期间分块消失（新 attempt 清理）→ 不当失败，记 skipped 即返回
            log.debug("graph ignore chunk {}: chunk disappeared", p.chunkId());
            graphOut.put("skipped", "chunk_disappeared");
            return null;
        }

        List<GraphNode> nodes = graph.nodes() == null ? new ArrayList<>() : graph.nodes();
        List<GraphRelation> relations = graph.relations() == null
                ? new ArrayList<>() : graph.relations();
        for (GraphNode node : nodes) {
            node.setChunks(List.of(again.getId()));
        }
        try {
            graphRepository.addGraph(
                    new NameSpace(again.getKnowledgeBaseId(), again.getKnowledgeId()),
                    List.of(new GraphData(nodes, relations)));
        } catch (RuntimeException e) {
            throw new IllegalStateException("failed to add graph: " + e.getMessage(), e);
        }

        graphOut.put("nodes_added", nodes.size());
        graphOut.put("relations_added", relations.size());
        // 各取前两个样例（再多会撑爆 span 行；全图另可查询）
        if (!nodes.isEmpty()) {
            List<GraphNode> samples = nodes.size() > 2 ? nodes.subList(0, 2) : nodes;
            List<String> names = new ArrayList<>();
            for (GraphNode n : samples) {
                names.add(n.getName());
            }
            graphOut.put("sample_nodes", names);
        }
        if (!relations.isEmpty()) {
            List<GraphRelation> samples = relations.size() > 2
                    ? relations.subList(0, 2) : relations;
            List<String> rendered = new ArrayList<>();
            for (GraphRelation r : samples) {
                rendered.add(r.node1() + " --[" + r.type() + "]--> " + r.node2());
            }
            graphOut.put("sample_relations", rendered);
        }
        return null;
    }

    /**
     * 组装抽取模板：原 {@code extract_graph} 模板的 Description +
     * 载荷的 {@code customInstructions}（标签 {@code graph_extraction}）+
     * Tags + 单条 Example（{@code text}/{@code nodes}/{@code relations} 来自 KB 配置）。
     */
    PipelineConfig.PromptTemplateStructured buildTemplate(JsonNode cfg) {
        PipelineConfig.PromptTemplateStructured template = new PipelineConfig.PromptTemplateStructured();
        template.setDescription(PromptInstructions.appendCustomPromptInstructions(
                extractPrompts.extractGraph().getDescription(),
                text(cfg, "customInstructions"), "graph_extraction"));
        template.setTags(stringList(cfg, "tags"));

        PipelineConfig.PromptTemplateStructured.Example example =
                new PipelineConfig.PromptTemplateStructured.Example();
        example.setText(text(cfg, "text"));
        List<GraphNode> nodes = new ArrayList<>();
        for (JsonNode node : cfg.path("nodes")) {
            nodes.add(new GraphNode(text(node, "name"), null, null));
        }
        example.setNode(nodes);
        List<GraphRelation> relations = new ArrayList<>();
        for (JsonNode rel : cfg.path("relations")) {
            relations.add(new GraphRelation(
                    text(rel, "node1"), text(rel, "node2"), text(rel, "type")));
        }
        example.setRelation(relations);
        template.setExamples(List.of(example));
        return template;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? "" : v.asText("");
    }

    private static List<String> stringList(JsonNode node, String field) {
        List<String> out = new ArrayList<>();
        JsonNode arr = node == null ? null : node.get(field);
        if (arr != null && arr.isArray()) {
            for (JsonNode item : arr) {
                out.add(item.asText(""));
            }
        }
        return out;
    }
}
