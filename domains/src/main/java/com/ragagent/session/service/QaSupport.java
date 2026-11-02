package com.ragagent.session.service;

import com.ragagent.contract.agent.AgentLookup;
import com.ragagent.contract.agent.SteerSink;
import com.ragagent.contract.event.EventBusInterface;
import com.ragagent.contract.model.ResponseType;
import com.ragagent.contract.session.MentionedItem;
import com.ragagent.contract.session.MessageAttachment;
import com.ragagent.contract.session.SuggestionAttribution;
import com.ragagent.platform.access.TenantContextSnapshot;
import com.ragagent.platform.storage.support.StreamRewriter;
import com.ragagent.platform.stream.StreamEvent;
import com.ragagent.session.domain.conversation.Session;
import com.ragagent.session.domain.message.Message;
import com.ragagent.session.dto.QaRequests.MentionedItemRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * QA 请求上下文与共享辅助。
 */
public final class QaSupport {

    private QaSupport() {}

    /** 附件上传的单请求数量 / 总字节上限。 */
    public static final int MAX_ATTACHMENT_UPLOADS_PER_REQUEST = 5;
    public static final long MAX_ATTACHMENT_UPLOAD_TOTAL_BYTES = 100L * 1024 * 1024;

    /** QA 执行模式。 */
    public enum QaMode {
        /** KnowledgeQA pipeline (RAG / pure chat) */
        NORMAL,
        /** Agent engine with tool calling */
        AGENT
    }

    /**
     * 一轮 QA 的可变上下文。
     *
     * <p><b>线程归属</b>：请求输入字段在 {@code executeQA} 之前写完、之后只读；轮次状态
     * （{@code userMessageID}/{@code assistantMessage}/{@code userCreatedAt}）由落库簇在
     * 启动异步 runner 之前写完；附件解析结果由 runner 线程独占写。跨线程传递优先用
     * 不可变快照（如输入条状态快照），不要直接把本对象交给新线程。</p>
     */
    // QA 请求上下文
    public static final class QaRequestContext {
        public String sessionId = "";
        public String requestId = "";
        /** Wall-clock time the handler started processing the request */
        public java.time.Instant receivedAt = java.time.Instant.now();
        public String query = "";
        public Session session;
        /** 已解析的 agent 视图（id/config/租户）；null = 未指定或未解析到 */
        public AgentLookup.AgentView agentRow;
        /** agent 的 config 树（ensureDefaults 后的 ObjectNode；装配面载荷为纯 JDK 值，边界经 JsonMappers 归一）。 */
        public com.fasterxml.jackson.databind.node.ObjectNode agentConfig;
        public Message assistantMessage;
        public List<String> knowledgeBaseIds = new ArrayList<>();
        public List<String> knowledgeIds = new ArrayList<>();
        public List<TagScope> tagScopes = new ArrayList<>();
        public List<String> tagIds = new ArrayList<>();
        public List<String> mcpServiceIds = new ArrayList<>();
        public List<String> skillNames = new ArrayList<>();
        public String summaryModelId = "";
        public boolean webSearchEnabled;
        public List<MentionedItem> mentionedItems = new ArrayList<>();
        /** when using shared agent, tenant ID for model/KB/MCP resolution; 0 = use context tenant */
        public long effectiveTenantId;
        public List<QaRequestsImage> images = new ArrayList<>();
        /** Created user message ID (populated after createUserMessage) */
        public String userMessageID = "";
        /** Persisted user message timestamp, echoed on agent_query */
        public java.time.OffsetDateTime userCreatedAt;
        /** Source channel: "web", "api", "im", etc. */
        public String channel = "";
        /** Processed base64 file attachments (legacy inline uploads) */
        public List<MessageAttachment> attachments = new ArrayList<>();
        /** Pre-uploaded session-scoped document IDs, resolved after SSE starts */
        public List<String> attachmentIDs = new ArrayList<>();
        /** Metadata-only view of attachmentIDs for the persisted user message */
        public List<MessageAttachment> attachmentMetas = new ArrayList<>();
        public SuggestionAttribution suggestionAttribution;
        /**
         * turns internal storage references in the outbound stream into directly
         * loadable URLs when the caller asks for {@code resource_urls=public}.
         * Disabled (a pass-through) in the default handle mode.
         */
        public StreamRewriter resourceRewriter;

        /** bridges the engine's round-boundary drain to the steer sub-list（agent runs only） */
        public SteerSinkBridge steerSink;

        /** request snapshot for the input-bar state（persistLastRequestState） */
        public boolean reqAgentEnabled;
        public String reqAgentID = "";

        /** server-started follow-up runs（steer backlog）：事件进 StreamManager 但不写 SSE */
        public boolean skipSSE;
        /** appended to this run's steer sub-list before the run is published as live */
        public List<StreamEvent> steerCarryOver;

        /** agent 快答/工具开关等派生量（从 config 树取） */
        public boolean agentModeEnabled;

        /** 把上下文装配成引擎入参 {@link QaRequest}。 */
        public QaRequest buildQaRequest() {
            ImageExtraction ext = extractImageUrlsAndOcrText(images);
            QaRequest req = new QaRequest();
            req.session = session;
            req.query = query;
            req.assistantMessageId = assistantMessage == null ? "" : assistantMessage.getId();
            req.summaryModelId = summaryModelId;
            req.agentRow = agentRow;
            req.agentConfig = agentConfig;
            req.knowledgeBaseIds = knowledgeBaseIds;
            req.knowledgeIds = knowledgeIds;
            req.tagScopes = tagScopes;
            req.mcpServiceIds = mcpServiceIds;
            req.skillNames = skillNames;
            req.imageUrls = ext.urls;
            req.imageDescription = ext.ocrText;
            req.userMessageId = userMessageID;
            req.webSearchEnabled = webSearchEnabled;
            req.attachments = attachments;
            req.steerSink = steerSink;
            return req;
        }
    }

    /** qa 请求里一张图片的轻量视图。 */
    public static final class QaRequestsImage {
        public String data = "";
        public String url = "";
        public String caption = "";
    }

    /** 图片 URL 列表与 OCR/caption 文本的二元返回。 */
    public static final class ImageExtraction {
        public List<String> urls = new ArrayList<>();
        public String ocrText = "";
    }

    /** 知识库 + 标签范围。 */
    public static final class TagScope {
        public String knowledgeBaseId = "";
        public List<String> tagIds = new ArrayList<>();

        public TagScope() {}

        public TagScope(String knowledgeBaseId, List<String> tagIds) {
            this.knowledgeBaseId = knowledgeBaseId;
            this.tagIds = tagIds == null ? new ArrayList<>() : tagIds;
        }
    }

    // SSE 流上下文
    public static final class SseStreamContext {
        public EventBusInterface eventBus;
        /** 虚拟线程上显式传租户的快照 */
        public TenantContextSnapshot tenantSnapshot;
        public volatile boolean cancelled;
        /** 执行体线程（STOP 时 interrupt，让其尽快到达取消点） */
        public volatile Thread runner;
        public Message assistantMessage;
        public SteerSinkBridge steerSink;
        /** 本轮终态单赢家：三个收尾点（推送/续传/事件总线）共用。 */
        public final TurnLifecycle lifecycle = new TurnLifecycle();
        /** 本轮 assistant 行的唯一写入锁（事件桥/最终答复/快答时间线共用）。 */
        public final TurnMessageGate messageGate = new TurnMessageGate();
        /** SetLiveRun failed → executeQA must not start the engine */
        public boolean liveRunFailed;
        public String liveRunErr = "";
        /** liveRunErr 是否为 live-run-already-exists（决定 409 vs 503） */
        public boolean liveRunExists;
    }

    // 纯函数辅助族

    /** 抽取图片 URL 与 caption 文本。 */
    public static ImageExtraction extractImageUrlsAndOcrText(List<QaRequestsImage> images) {
        ImageExtraction out = new ImageExtraction();
        if (images == null) {
            return out;
        }
        StringBuilder ocr = new StringBuilder();
        boolean first = true;
        for (QaRequestsImage img : images) {
            if (img.url != null && !img.url.isEmpty()) {
                out.urls.add(img.url);
            }
            if (img.caption != null && !img.caption.isEmpty()) {
                if (!first) {
                    ocr.append("\n\n");
                }
                first = false;
                ocr.append(img.caption);
            }
        }
        out.ocrText = ocr.toString();
        return out;
    }

    /** 请求里的提及项 → 实体列表。 */
    public static List<MentionedItem> convertMentionedItems(List<MentionedItemRequest> items) {
        List<MentionedItem> out = new ArrayList<>();
        if (items == null) {
            return out;
        }
        for (MentionedItemRequest item : items) {
            MentionedItem m = new MentionedItem();
            m.setId(orEmpty(item.id));
            m.setName(orEmpty(item.name));
            m.setType(orEmpty(item.type));
            m.setKbType(orEmpty(item.kbType));
            m.setKbId(orEmpty(item.kbId));
            m.setKbName(orEmpty(item.kbName));
            m.setServiceId(orEmpty(item.serviceId));
            m.setSkillName(orEmpty(item.skillName));
            out.add(m);
        }
        return out;
    }

    /** 提及的标签项 → 按 KB 分组的 tag 范围。 */
    public static List<TagScope> tagScopesFromMentionedItems(List<MentionedItemRequest> items) {
        Map<String, TagScope> scopesByKb = new LinkedHashMap<>();
        if (items == null) {
            return new ArrayList<>();
        }
        for (MentionedItemRequest item : items) {
            if (!"tag".equals(item.type) || isEmpty(item.kbId) || isEmpty(item.id)) {
                continue;
            }
            TagScope scope = scopesByKb.get(item.kbId);
            if (scope == null) {
                scope = new TagScope(item.kbId, new ArrayList<>());
                scopesByKb.put(item.kbId, scope);
            }
            if (!scope.tagIds.contains(item.id)) {
                scope.tagIds.add(item.id);
            }
        }
        return new ArrayList<>(scopesByKb.values());
    }

    /** 找出不在任何范围内（孤儿）的 tag id。 */
    public static List<String> orphanTagIdsForScope(List<String> tagIds, List<TagScope> scopes) {
        Set<String> scoped = new LinkedHashSet<>();
        if (scopes != null) {
            for (TagScope s : scopes) {
                scoped.addAll(s.tagIds);
            }
        }
        List<String> orphans = new ArrayList<>();
        if (tagIds != null) {
            for (String id : tagIds) {
                if (!isEmpty(id) && !scoped.contains(id)) {
                    orphans.add(id);
                }
            }
        }
        return orphans;
    }

    /** 校验孤儿 tag id 集合为空；返回首个错误文案。 */
    public static String validateUnscopedTagIds(List<String> orphan, List<String> kbIds) {
        if (orphan.isEmpty()) {
            return null;
        }
        if (kbIds == null || kbIds.isEmpty()) {
            return "tags without a knowledge base scope must be mentioned with a knowledge base";
        }
        return null;
    }

    /** 合并范围、散 tag id 与 KB 集合。 */
    public static List<TagScope> mergeTagScopesFromRequestIds(
            List<TagScope> scopes, List<String> tagIds, List<String> kbIds) {
        List<TagScope> merged = new ArrayList<>();
        if (scopes != null) {
            for (TagScope s : scopes) {
                merged.add(new TagScope(s.knowledgeBaseId, new ArrayList<>(s.tagIds)));
            }
        }
        if (tagIds == null || tagIds.isEmpty()) {
            return merged;
        }
        // A request tag ID without a scope attaches to a single-KB scope when the
        // request names exactly one knowledge base.
        if (kbIds != null && kbIds.size() == 1 && !isEmpty(kbIds.get(0))) {
            String kbId = kbIds.get(0);
            TagScope scope = null;
            for (TagScope s : merged) {
                if (kbId.equals(s.knowledgeBaseId)) {
                    scope = s;
                    break;
                }
            }
            if (scope == null) {
                scope = new TagScope(kbId, new ArrayList<>());
                merged.add(scope);
            }
            for (String tagId : tagIds) {
                if (!isEmpty(tagId) && !scope.tagIds.contains(tagId)) {
                    scope.tagIds.add(tagId);
                }
            }
        }
        return merged;
    }

    /** 按提及类型抽 id 列表。 */
    public static List<String> mentionedIdsByType(List<MentionedItemRequest> items, String itemType) {
        List<String> out = new ArrayList<>();
        if (items == null) {
            return out;
        }
        for (MentionedItemRequest item : items) {
            if (itemType.equals(item.type) && !isEmpty(item.id)) {
                out.add(item.id);
            }
        }
        return out;
    }

    /** 请求字符串列表去重去空。 */
    public static List<String> dedupRequestStrings(List<String> values) {
        List<String> out = new ArrayList<>();
        if (values == null) {
            return out;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String v : values) {
            if (!isEmpty(v) && !seen.contains(v)) {
                seen.add(v);
                out.add(v);
            }
        }
        return out;
    }

    /** 合并 KB ids、知识 ids 与提及项成检索目标。 */
    public static KnowledgeTargets mergeKnowledgeTargets(
            List<String> requestKbIds, List<String> requestKnowledgeIds, List<MentionedItemRequest> mentionedItems) {
        Set<String> kbIdSet = new LinkedHashSet<>();
        List<String> kbIds = new ArrayList<>();
        for (String id : requestKbIds == null ? List.<String>of() : requestKbIds) {
            if (!isEmpty(id) && !kbIdSet.contains(id)) {
                kbIds.add(id);
                kbIdSet.add(id);
            }
        }
        Set<String> knowledgeIdSet = new LinkedHashSet<>();
        List<String> knowledgeIds = new ArrayList<>();
        for (String id : requestKnowledgeIds == null ? List.<String>of() : requestKnowledgeIds) {
            if (!isEmpty(id) && !knowledgeIdSet.contains(id)) {
                knowledgeIds.add(id);
                knowledgeIdSet.add(id);
            }
        }
        for (MentionedItemRequest item : mentionedItems == null ? List.<MentionedItemRequest>of() : mentionedItems) {
            if (isEmpty(item.id)) {
                continue;
            }
            switch (item.type) {
                case "kb" -> {
                    if (!kbIdSet.contains(item.id)) {
                        kbIds.add(item.id);
                        kbIdSet.add(item.id);
                    }
                }
                case "file" -> {
                    if (!knowledgeIdSet.contains(item.id)) {
                        knowledgeIds.add(item.id);
                        knowledgeIdSet.add(item.id);
                    }
                }
                default -> {}
            }
        }
        return new KnowledgeTargets(kbIds, knowledgeIds);
    }

    /** mergeKnowledgeTargets 的二元返回。 */
    public record KnowledgeTargets(List<String> kbIds, List<String> knowledgeIds) {}

    /** 预上传附件 id 规范化：trim + 去重 + 截断。 */
    public static List<String> normalizeTemporaryAttachmentIds(List<String> ids, int max) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String id : ids == null ? List.<String>of() : ids) {
            String v = id == null ? "" : id.trim();
            if (v.isEmpty() || seen.contains(v)) {
                continue;
            }
            seen.add(v);
            out.add(v);
        }
        return out;
    }

    // steer 引擎侧共用的纯函数与常量

    public static final String STEER_DELIVERY_INJECT = "inject";
    public static final String STEER_DELIVERY_AFTER = "after";
    public static final String STEER_DATA_CONSUMED = "consumed";
    public static final String STEER_DATA_USER_MESSAGE_ID = "user_message_id";
    public static final int MAX_STEER_QUEUE_DEPTH = 10;
    public static final int MAX_STEER_QUERY_LENGTH = 10000;
    public static final int STEER_DRAIN_BATCH_LIMIT = 20;

    /** 解析 delivery；null = 非法。 */
    public static String parseSteerDelivery(String s) {
        String v = s == null ? "" : s.trim().toLowerCase();
        if (v.isEmpty() || STEER_DELIVERY_AFTER.equals(v)) {
            return STEER_DELIVERY_AFTER;
        }
        if (STEER_DELIVERY_INJECT.equals(v)) {
            return STEER_DELIVERY_INJECT;
        }
        return null;
    }

    /** 读事件的 delivery：非 after 一律按 inject。 */
    public static String steerDeliveryOfEvent(StreamEvent evt) {
        return evt.getData() != null && STEER_DELIVERY_AFTER.equals(evt.getData().get("delivery"))
                ? STEER_DELIVERY_AFTER
                : STEER_DELIVERY_INJECT;
    }

    /** 事件是否已消费。 */
    public static boolean steerEventConsumed(StreamEvent evt) {
        return evt.getData() != null && Boolean.TRUE.equals(evt.getData().get(STEER_DATA_CONSUMED));
    }

    /** 选未消费的 backlog 事件（可排除已注入 id）。 */
    public static List<StreamEvent> selectSteerBacklog(List<StreamEvent> events, Set<String> injectedIds) {
        List<StreamEvent> out = new ArrayList<>();
        for (StreamEvent evt : events) {
            if (steerEventConsumed(evt)) {
                continue;
            }
            if (injectedIds != null && injectedIds.contains(evt.getId())) {
                continue;
            }
            out.add(evt);
        }
        return out;
    }

    /** 构造 steer 事件。 */
    public static StreamEvent steerEvent(String id, String query, List<MentionedItem> mentionedItems, String channel) {
        StreamEvent evt = new StreamEvent();
        evt.setId(id);
        evt.setType(ResponseType.STEER);
        evt.setContent(query);
        evt.setDone(true);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("steerId", id);
        data.put("channel", channel);
        data.put("delivery", STEER_DELIVERY_INJECT);
        data.put("mentionedItems", mentionedItemsToRaw(mentionedItems));
        evt.setData(data);
        return evt;
    }

    /** 提及项 → 事件 data 的原始 map 形态。 */
    public static List<Object> mentionedItemsToRaw(List<MentionedItem> items) {
        List<Object> out = new ArrayList<>();
        if (items != null) {
            for (MentionedItem item : items) {
                Map<String, Object> raw = new LinkedHashMap<>();
                raw.put("id", item.getId());
                raw.put("name", item.getName());
                raw.put("type", item.getType());
                raw.put("kbType", item.getKbType());
                raw.put("kbId", item.getKbId());
                raw.put("kbName", item.getKbName());
                raw.put("serviceId", item.getServiceId());
                raw.put("skillName", item.getSkillName());
                out.add(raw);
            }
        }
        return out;
    }

    /** map 取 string：类型不符或缺席一律给 ""。 */
    public static String getString(Map<String, Object> m, String key) {
        if (m == null) {
            return "";
        }
        Object v = m.get(key);
        return v instanceof String s ? s : "";
    }

    public static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }

    public static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    // QARequest（service 面入参）
    public static final class QaRequest {
        public Session session;
        public String query = "";
        public String assistantMessageId = "";
        public String summaryModelId = "";
        public AgentLookup.AgentView agentRow;
        /** 未带实体的调用面（im）只传 agent id；服务入口经 {@code AgentResolver#resolveInto} 补 {@link #agentRow}。 */
        public String agentId = "";
        public com.fasterxml.jackson.databind.node.ObjectNode agentConfig;
        public List<String> knowledgeBaseIds = new ArrayList<>();
        public List<String> knowledgeIds = new ArrayList<>();
        public List<TagScope> tagScopes = new ArrayList<>();
        public List<String> mcpServiceIds = new ArrayList<>();
        public List<String> skillNames = new ArrayList<>();
        public List<String> imageUrls;
        public String imageDescription = "";
        public String userMessageId = "";
        public boolean webSearchEnabled;
        public String quotedContext = "";
        public List<MessageAttachment> attachments = new ArrayList<>();
        public SteerSink steerSink;
        /**
         * 用户停止（stop）的取消探针（贯穿 think/act/审批等待）：null=存活；
         * 非 null 时返回 null=未取消、非 null=取消错误原文——与引擎
         * {@code setCancellationSource(Supplier)} 的契约一致。
         */
        public java.util.function.Supplier<String> cancellationProbe;
    }
}
