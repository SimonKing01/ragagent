package com.ragagent.knowledge.service.chunk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.contract.error.BizException;
import com.ragagent.contract.knowledge.DocumentChunkMetadata;
import com.ragagent.contract.knowledge.GeneratedQuestion;
import com.ragagent.contract.model.ChatMessage;
import com.ragagent.contract.model.ChatOptions;
import com.ragagent.contract.model.ChatResponse;
import com.ragagent.contract.model.Embedder;
import com.ragagent.contract.model.LlmChatClient;
import com.ragagent.contract.model.ModelRuntime;
import com.ragagent.contract.retrieval.IndexEngine;
import com.ragagent.contract.retrieval.IndexEngineResolver;
import com.ragagent.contract.retrieval.TenantRetrievalDefault;
import com.ragagent.contract.tenant.TenantConfigLookup;
import com.ragagent.contract.wiki.WikiLanguageSupport;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.ChunkQuestionPort;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.index.ChunkVectorIndexer;
import com.ragagent.knowledge.repository.ChunkRepository;
import com.ragagent.knowledge.repository.KnowledgeBaseRepository;
import com.ragagent.knowledge.service.access.ChunkAccessGuard;
import com.ragagent.knowledge.support.ChunkNotFoundException;
import com.ragagent.knowledge.support.ChunkRevisionConflictException;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.platform.config.RetrievalDriverProperties;
import com.ragagent.platform.knowledge.ChunkSearchUtil;
import com.ragagent.platform.prompt.AgentPromptPlaceholders;
import com.ragagent.platform.prompt.PromptInstructions;
import com.ragagent.platform.settings.ConversationProperties;
import com.ragagent.platform.text.DegenerateText;
import com.ragagent.platform.web.JsonMappers;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * chunk 生成问题面：生成问题的 upsert/删除/重生（LLM 生成 + 邻块上下文拼装 + metadata 落库）。
 * 写路径守卫经 {@link ChunkAccessGuard}，索引进 {@link ChunkVectorIndexer}。
 */
@Service
public class ChunkQuestionService implements ChunkQuestionPort {

    private static final Logger log = LoggerFactory.getLogger(ChunkQuestionService.class);

    private final ChunkRepository chunkRepository;
    private final KnowledgeBaseRepository kbRepository;
    private final ChunkVectorIndexer chunkVectorIndexer;
    private final ModelRuntime modelRuntimeAssembler;
    private final IndexEngineResolver indexEngineResolver;
    private final TenantConfigLookup tenantConfigLookup;
    private final ConversationProperties conversationProps;
    private final ChunkAccessGuard guard;
    /** RETRIEVE_DRIVER（属性绑定）。 */
    private final RetrievalDriverProperties driverProperties;

    public ChunkQuestionService(ChunkRepository chunkRepository,
                                KnowledgeBaseRepository kbRepository,
                                ChunkVectorIndexer chunkVectorIndexer,
                                ModelRuntime modelRuntimeAssembler,
                                IndexEngineResolver indexEngineResolver,
                                TenantConfigLookup tenantConfigLookup,
                                ConversationProperties conversationProps,
                                ChunkAccessGuard guard,
                                RetrievalDriverProperties driverProperties) {
        this.chunkRepository = chunkRepository;
        this.kbRepository = kbRepository;
        this.chunkVectorIndexer = chunkVectorIndexer;
        this.modelRuntimeAssembler = modelRuntimeAssembler;
        this.indexEngineResolver = indexEngineResolver;
        this.tenantConfigLookup = tenantConfigLookup;
        this.conversationProps = conversationProps;
        this.guard = guard;
        this.driverProperties = driverProperties;
    }

    /** 元数据可能含未知键（历史行/新增字段）→ 宽松读。 */
    private static final ObjectMapper META_MAPPER = JsonMappers.lenient();
    private static final String CHUNK_TYPE_TEXT = "text";

    private static long mustTenantId() {
        Long tid = TenantContext.currentTenantId();
        if (tid == null) {
            throw new IllegalStateException("tenant id is not in context");
        }
        return tid;
    }


    // ── 生成问题 ───────────────────────────────────────────────────────────

    /**
     * questionID 为空 =
     * 新建（服务端生成 UUID），否则就地更新既有问题并把 content_revision 钉到当前版本。
     * metadata 序列化失败文案是 Jackson 的（实现边界族）。
     */
    public GeneratedQuestion upsertGeneratedQuestion(String chunkId, String questionId, String question) {
        String trimmed = ChunkSearchUtil.trimSpace(question);
        if (trimmed.isEmpty()) {
            throw BizException.badRequest("question cannot be empty");
        }
        Chunk chunk;
        try {
            chunk = guard.writableChunk(chunkId);
        } catch (BizException e) {
            throw e;
        }
        DocumentChunkMetadata meta;
        try {
            meta = parseDocumentMetadata(chunk.getMetadata());
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse stored chunk metadata", e);
            throw BizException.internal("failed to read chunk metadata");
        }
        if (meta == null) {
            meta = new DocumentChunkMetadata();
        }
        int currentRevision = chunk.getContentRevision();
        List<GeneratedQuestion> questions = meta.getGeneratedQuestions();
        if (questionId == null || questionId.isEmpty()) {
            String newId = UUID.randomUUID().toString();
            if (questions == null) {
                questions = new ArrayList<>();
                meta.setGeneratedQuestions(questions);
            }
            questions.add(new GeneratedQuestion(newId, trimmed, currentRevision));
            questionId = newId;
        } else {
            boolean found = false;
            if (questions != null) {
                for (GeneratedQuestion gq : questions) {
                    if (questionId.equals(gq.getId())) {
                        gq.setQuestion(trimmed);
                        gq.setContentRevision(currentRevision);
                        found = true;
                        break;
                    }
                }
            }
            if (!found) {
                throw BizException.badRequest("question not found");
            }
        }
        try {
            chunk.setMetadata(writeDocumentMetadata(meta));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize chunk metadata", e);
            throw BizException.internal("failed to write chunk metadata");
        }
        try {
            chunkRepository.updateChunk(chunk);
        } catch (RuntimeException e) {
            log.warn("Failed to update chunk", e);
            throw BizException.internal("failed to persist chunk");
        }
        try {
            chunkVectorIndexer.syncChunkIndex(chunk);
        } catch (RuntimeException e) {
            log.warn("Failed to sync chunk index", e);
            throw BizException.internal("failed to sync chunk index");
        }
        for (GeneratedQuestion gq : meta.getGeneratedQuestions()) {
            if (questionId.equals(gq.getId())) {
                return gq;
            }
        }
        throw BizException.badRequest("question not found");
    }

    /**
     * 删除 chunk 的一条生成问题。除向量索引删除半边外，所有失败都包 400
     * （异常原文进文案）；向量索引删除失败只警告继续（问题未被索引过是常态）。
     */
    public void deleteGeneratedQuestion(String chunkId, String questionId) {
        log.info("Deleting generated question, chunk ID: {}, question ID: {}", chunkId, questionId);
        long tenantId = mustTenantId();

        // writableChunk 失败的文案（含 "error code: N, ..." 前缀）一并进入 400 文案
        Chunk chunk;
        try {
            chunk = guard.writableChunk(chunkId);
        } catch (RuntimeException e) {
            throw BizException.badRequest("failed to get chunk: " + e.getMessage());
        }

        // 2. metadata
        DocumentChunkMetadata meta;
        try {
            meta = parseDocumentMetadata(chunk.getMetadata());
        } catch (JsonProcessingException e) {
            throw BizException.badRequest("failed to parse chunk metadata: " + e.getMessage());
        }
        if (meta == null || meta.getGeneratedQuestions() == null || meta.getGeneratedQuestions().isEmpty()) {
            throw BizException.badRequest("no generated questions found for chunk " + chunkId);
        }

        // 3. 找问题
        int questionIndex = -1;
        List<GeneratedQuestion> questions = meta.getGeneratedQuestions();
        for (int i = 0; i < questions.size(); i++) {
            if (questionId != null && questionId.equals(questions.get(i).getId())) {
                questionIndex = i;
                break;
            }
        }
        if (questionIndex == -1) {
            throw BizException.badRequest("question with ID " + questionId + " not found in chunk " + chunkId);
        }

        // KB 缺失同样按 400 抛出（文案固定为 "knowledge base not found" 原文）
        KnowledgeBase kb = findKbRow(chunk.getKnowledgeBaseId());
        if (kb == null) {
            throw BizException.badRequest("failed to get knowledge base: knowledge base not found");
        }

        // 5. 删除该问题的向量索引。source_id 形如 {chunkId}-q{hash24}（短 ID 直拼）。
        String sourceId = ChunkSearchUtil.generatedQuestionSourceId(chunkId, questionId);
        IndexEngine engine;
        try {
            // 绑定 store 走归属校验 + 注册表解析；无绑定回落租户有效引擎——解析收在
            // retrieval-engine 的 IndexEngineGateway（本类只提供回落口径）。
            engine = indexEngineResolver.resolveFor(tenantId, kb.getVectorStoreId(),
                    tenantRetrievalDefault(tenantId));
        } catch (RuntimeException e) {
            throw BizException.badRequest("failed to create retrieve engine: " + e.getMessage());
        }
        //     嵌入模型解析失败（"model ID cannot be empty" / "model not found"）
        //     以原文冒出，handler 包 400。
        Embedder embeddingModel;
        try {
            embeddingModel = modelRuntimeAssembler.getEmbeddingModel(
                    kb.getEmbeddingModelId() == null ? "" : kb.getEmbeddingModelId());
        } catch (RuntimeException e) {
            throw BizException.badRequest("failed to get embedding model: " + e.getMessage());
        }
        //     向量索引删除失败（问题未被索引过）只警告继续，不阻断元数据更新。
        try {
            engine.deleteBySourceIdList(List.of(sourceId), embeddingModel.getDimensions(),
                    kb.getType());
        } catch (Exception e) {
            log.warn("Failed to delete vector index for question (may not exist)", e);
        }

        // 6. 从 metadata 移除
        List<GeneratedQuestion> remaining = new ArrayList<>(questions.size() - 1);
        for (int i = 0; i < questions.size(); i++) {
            if (i != questionIndex) {
                remaining.add(questions.get(i));
            }
        }

        // 7. 更新 chunk metadata
        meta.setGeneratedQuestions(remaining);
        try {
            chunk.setMetadata(writeDocumentMetadata(meta));
        } catch (JsonProcessingException e) {
            throw BizException.badRequest("failed to set chunk metadata: " + e.getMessage());
        }
        try {
            chunkRepository.updateChunk(chunk);
        } catch (RuntimeException e) {
            throw BizException.badRequest("failed to update chunk: " + e.getMessage());
        }
        log.info("Successfully deleted generated question {} from chunk {}", questionId, chunkId);
    }

    /**
     * 确定性分支逐字对照；LLM 生成步降级见类注释第 3 条。
     */
    public List<GeneratedQuestion> regenerateChunkQuestions(String chunkId) {
        long tenantId = mustTenantId();
        Chunk chunk;
        try {
            chunk = chunkRepository.getChunkById(tenantId, chunkId);
        } catch (ChunkNotFoundException e) {
            throw BizException.badRequest("chunk not found");
        }
        if (!CHUNK_TYPE_TEXT.equals(chunk.getChunkType())) {
            throw BizException.badRequest("questions can only be generated for text chunks");
        }
        int generationRevision = chunk.getContentRevision();
        ChunkAccessGuard.KnowledgeWrite write;
        try {
            write = guard.loadKnowledgeWrite(chunk.getKnowledgeId());
        } catch (BizException e) {
            throw e;
        }
        Knowledge knowledge = write.knowledge();
        KnowledgeBase kb = write.kb();
        if (!knowledge.getKnowledgeBaseId().equals(chunk.getKnowledgeBaseId())
                || !Objects.equals(chunk.getTenantId(), knowledge.getTenantId())) {
            throw BizException.badRequest(
                    BizException.forbidden("chunk does not belong to its knowledge document").getMessage());
        }
        if (kb.getSummaryModelId() == null || kb.getSummaryModelId().isEmpty()) {
            throw BizException.badRequest("summary model is required for question generation");
        }
        // chat 模型解析失败统一包 400（"model not found" 等原文进文案）
        LlmChatClient chatModel;
        try {
            chatModel = modelRuntimeAssembler.getChatModel(kb.getSummaryModelId());
        } catch (RuntimeException e) {
            throw BizException.badRequest(e.getMessage() == null ? "model not found" : e.getMessage());
        }
        String prevContent = resolveNeighborContent(tenantId, chunk, chunk.getPreChunkId());
        String nextContent = resolveNeighborContent(tenantId, chunk, chunk.getNextChunkId());
        // 生成问题配置缺省：count=3、上限 10
        JsonNode qg = kb.getQuestionGenerationConfig();
        int questionCount = qg == null ? 0 : qg.path("questionCount").asInt(0);
        if (questionCount <= 0) {
            questionCount = 3;
        }
        if (questionCount > 10) {
            questionCount = 10;
        }
        String customInstructions = qg == null ? "" : qg.path("customInstructions").asText("");
        List<String> questions = generateQuestionsWithContext(
                chatModel, chunk.getContent(), prevContent, nextContent,
                knowledge.getTitle(), questionCount, customInstructions);
        // 重读 latestChunk，期间被编辑（revision 变化）则 409
        Chunk latest;
        try {
            latest = chunkRepository.getChunkById(tenantId, chunkId);
        } catch (ChunkNotFoundException e) {
            throw BizException.badRequest("chunk not found");
        }
        if (latest.getContentRevision() != generationRevision) {
            throw new ChunkRevisionConflictException();
        }
        chunk = latest;
        List<GeneratedQuestion> generated = buildGeneratedQuestions(questions, chunk);
        try {
            persistGeneratedQuestions(kb, chunk, generated);
        } catch (RuntimeException e) {
            log.warn("Failed to persist generated questions", e);
            throw BizException.internal("failed to persist generated questions");
        }
        log.info("Successfully regenerated {} questions for chunk {}", generated.size(), chunkId);
        return generated;
    }

    /**
     * worker 语义的"生成 + 落库 + 建索引"——导入后处理扇出的批任务走这里。
     * <p>与 {@link #regenerateChunkQuestions} 共用同一条落库路径，但**没有 handler 语义**：
     * 期间分块被编辑（revision 变化）时<b>跳过</b>而不是抛 409；单块 LLM 失败只告警并跳过、
     * 不中断整批。</p>
     * <p>模型解析失败与落库失败<b>上抛</b>——让队列按队列语义重试
     * 。</p>
     * @return 写入的问题数；0 = 跳过（内容空 / 生成失败 / revision 变化 / 分块已删）
     */
    @Override
    public int generateAndStoreQuestionsForWorker(KnowledgeBase kb, Knowledge knowledge, Chunk chunk,
            String prevContent, String nextContent, int questionCount, String customInstructions) {
        int generationRevision = chunk.getContentRevision();
        LlmChatClient chatModel;
        try {
            chatModel = modelRuntimeAssembler.getChatModel(kb.getSummaryModelId());
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    e.getMessage() == null ? "model not found" : e.getMessage(), e);
        }
        List<String> questions;
        try {
            questions = generateQuestionsWithContext(chatModel, chunk.getContent(), prevContent, nextContent,
                    knowledge.getTitle(), questionCount, customInstructions);
        } catch (RuntimeException e) {
            log.warn("Failed to generate questions for chunk {}", chunk.getId(), e);
            return 0;
        }
        if (questions.isEmpty()) {
            return 0;
        }
        Chunk latest;
        try {
            latest = chunkRepository.getChunkById(kb.getTenantId(), chunk.getId());
        } catch (RuntimeException e) {
            return 0;
        }
        if (latest.getContentRevision() != generationRevision) {
            // revision 变化 → 跳过（陈旧问题不落库），不报错
            log.info("Skipping stale generated questions for chunk {} (revision changed)", chunk.getId());
            return 0;
        }
        List<GeneratedQuestion> generated = buildGeneratedQuestions(questions, latest);
        try {
            persistGeneratedQuestions(kb, latest, generated);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    e.getMessage() == null ? "failed to store generated questions" : e.getMessage(), e);
        }
        return generated.size();
    }

    /** GeneratedQuestion 列表构建（id 新 UUID、revision 取当前）。 */
    private static List<GeneratedQuestion> buildGeneratedQuestions(List<String> questions, Chunk chunk) {
        List<GeneratedQuestion> generated = new ArrayList<>(questions.size());
        Integer questionRevision = chunk.getContentRevision();
        for (String question : questions) {
            generated.add(new GeneratedQuestion(UUID.randomUUID().toString(), question, questionRevision));
        }
        return generated;
    }

    /**
     * 语义（metadata 整写 + chunk 更新 + 向量同步三联动）：
     * 整体替换 metadata（仅 generatedQuestions 两键；空列表/0 由域类型注解省略）
     * → 落库 → 重建该分块向量索引。
     */
    private void persistGeneratedQuestions(KnowledgeBase kb, Chunk chunk, List<GeneratedQuestion> generated) {
        DocumentChunkMetadata meta = new DocumentChunkMetadata();
        meta.setGeneratedQuestions(generated);
        meta.setGeneratedQuestionsRevision(chunk.getContentRevision());
        try {
            chunk.setMetadata(writeDocumentMetadata(meta));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to set chunk metadata: " + e.getMessage(), e);
        }
        try {
            chunkRepository.updateChunk(chunk);
        } catch (RuntimeException e) {
            throw new IllegalStateException("failed to update chunk: " + e.getMessage(), e);
        }
        try {
            chunkVectorIndexer.updateChunkVector(kb.getId(), List.of(chunk));
        } catch (RuntimeException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /**
     * 邻居块必须与当前块同租户/同 KB/同文档，否则按空处理；
     * 读取失败同样按空。用于问题生成的 surrounding_context。
     */
    private String resolveNeighborContent(long tenantId, Chunk chunk, String neighborId) {
        if (neighborId == null || neighborId.isEmpty()) {
            return "";
        }
        Chunk neighbor;
        try {
            neighbor = chunkRepository.getChunkById(tenantId, neighborId);
        } catch (RuntimeException e) {
            return "";
        }
        if (!Objects.equals(neighbor.getTenantId(), chunk.getTenantId())
                || !Objects.equals(neighbor.getKnowledgeBaseId(), chunk.getKnowledgeBaseId())
                || !Objects.equals(neighbor.getKnowledgeId(), chunk.getKnowledgeId())) {
            return "";
        }
        return neighbor.getContent() == null ? "" : neighbor.getContent();
    }

    /**
     * * 1) prompt 取自 config.Conversation.GenerateQuestionsPrompt（空 → err 原文）；
     * 2) context 段：preceding/following 非空才拼 surrounding_context；
     * 3) 渲染 {{question_count}}/{{content}}/{{context}}/{{doc_name}}/{{language}}；
     * 4) 业务指引包裹（AppendCustomPromptInstructions label=question_generation）；
     * 5) chat（temperature 0.7 / max_tokens 512 / thinking=false，单条 user 消息）；
     * 6) 行解析：逐行 trim → 裁前缀符号 → trim → 非空且 &gt;5 字节才收，达到 count 即止。
     */
    private List<String> generateQuestionsWithContext(LlmChatClient chatModel, String content,
                                                      String prevContent, String nextContent,
                                                      String docName, int questionCount,
                                                      String customInstructions) {
        if (content == null || content.isEmpty() || questionCount <= 0) {
            return List.of();
        }
        String prompt = ChunkRepository.trimSpace(conversationProps.getGenerateQuestionsPrompt());
        if (prompt.isEmpty()) {
            throw BizException.badRequest("generate questions prompt not configured");
        }
        StringBuilder contextSection = new StringBuilder();
        if ((prevContent != null && !prevContent.isEmpty())
                || (nextContent != null && !nextContent.isEmpty())) {
            contextSection.append("<surrounding_context>\n");
            if (prevContent != null && !prevContent.isEmpty()) {
                contextSection.append("<preceding_content>\n").append(prevContent)
                        .append("\n\n</preceding_content>\n\n");
            }
            if (nextContent != null && !nextContent.isEmpty()) {
                contextSection.append("<following_content>\n").append(nextContent)
                        .append("\n\n</following_content>\n\n");
            }
            contextSection.append("</surrounding_context>\n\n");
        }
        prompt = AgentPromptPlaceholders.renderPromptPlaceholders(prompt, Map.of(
                "question_count", String.valueOf(questionCount),
                "content", content,
                "context", contextSection.toString(),
                "doc_name", docName == null ? "" : docName,
                "language", WikiLanguageSupport.languageNameFromContext()));
        prompt = PromptInstructions.appendCustomPromptInstructions(
                prompt, customInstructions, "question_generation");
        ChatOptions options = new ChatOptions();
        options.setTemperature(0.7);
        options.setMaxTokens(512);
        options.setThinking(Boolean.FALSE);
        ChatResponse response;
        try {
            response = chatModel.chat(List.of(ChatMessage.user(prompt)), options);
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Failed to generate questions", e);
            throw BizException.internal("failed to generate questions");
        }
        String text = response == null || response.getContent() == null ? "" : response.getContent();
        return parseGeneratedQuestions(text, questionCount);
    }

    /**
     * 逐行解析：
     * 逐行 trim → 裁前缀符号（{@code "0123456789.-*) "}）→ trim → 非空且 &gt;5 字节
     * → 非退化（{@link DegenerateText}：模型重复循环产出的串不落库）
     */
    static List<String> parseGeneratedQuestions(String content, int questionCount) {
        List<String> questions = new ArrayList<>(Math.max(questionCount, 0));
        if (content == null || questionCount <= 0) {
            return questions;
        }
        for (String raw : content.split("\n", -1)) {
            String line = ChunkRepository.trimSpace(raw);
            if (line.isEmpty()) {
                continue;
            }
            line = trimLeftCharSet(line, "0123456789.-*) ");
            line = ChunkRepository.trimSpace(line);
            if (!line.isEmpty() && line.getBytes(StandardCharsets.UTF_8).length > 5
                    && !DegenerateText.isDegenerate(line)) {
                questions.add(line);
                if (questions.size() >= questionCount) {
                    break;
                }
            }
        }
        return questions;
    }

    /** 裁掉开头属于字符集的字符。 */
    private static String trimLeftCharSet(String s, String cutset) {
        int i = 0;
        while (i < s.length() && cutset.indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        return s.substring(i);
    }


    private TenantRetrievalDefault tenantRetrievalDefault(long tenantId) {
        Object engines;
        try {
            engines = tenantConfigLookup.retrieverEngines(tenantId);
        } catch (RuntimeException e) {
            engines = null;
        }
        return new TenantRetrievalDefault(engines, driverProperties.driver());
    }

    /**
     * 租户缺或 0 →
     * 401 "workspace context unavailable"。
     */

    private KnowledgeBase findKbRow(String kbId) {
        return kbRepository.findByIdOnly(kbId);
    }


    private static DocumentChunkMetadata parseDocumentMetadata(JsonNode metadata) throws JsonProcessingException {
        if (metadata == null || metadata.isNull() || metadata.isMissingNode()) {
            return null;
        }
        return META_MAPPER.treeToValue(metadata, DocumentChunkMetadata.class);
    }

    /** 序列化形状由域类型上的注解锁定。 */

    private static JsonNode writeDocumentMetadata(DocumentChunkMetadata meta) throws JsonProcessingException {
        if (meta == null) {
            return null;
        }
        return META_MAPPER.valueToTree(meta);
    }

}
