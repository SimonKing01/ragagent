package com.ragagent.session.service.sse;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.agent.fixtures.ContractJson;
import com.ragagent.contract.model.ResponseType;
import com.ragagent.contract.model.StreamResponse;
import com.ragagent.contract.retrieval.SearchResult;
import com.ragagent.platform.stream.StreamEvent;
import com.ragagent.platform.web.JsonMappers;
import com.ragagent.session.controller.sse.SseContract;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * {@link StreamResponseBuilder} 的逐字节契约测试。
 *
 * <h2>期望值钉住的线格式</h2>
 * <p>钉住的不只是"字段有没有"，而是<b>键序</b>与<b>零值取舍</b>——
 * 正是本项目最容易漂移的两处：</p>
 * <ul>
 *   <li>{@code StreamResponse} / {@code SearchResult} 按 <b>struct 声明序</b>；</li>
 *   <li>{@code data} 与 {@code data.references[].*} 是 <b>map，按键字母序</b>；
 *       同一个引用在两处出现时键序<b>本来就不同</b>（{@code knowledge_references}
 *       是重建后的 struct，{@code data.references} 是原样的 map）。</li>
 * </ul>
 */
class StreamResponseBuilderTest {

    /** 与线上一致：默认 mapper（本类型不含时间字段，无需 JacksonConfig 的 OffsetDateTime 覆盖）。 */
    // 契约载荷（ToolResult/ResponseType 等）的线格式由 mixin 模块承载（B268-A②）
    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    private static String write(StreamResponse response) throws Exception {
        return MAPPER.writeValueAsString(response);
    }

    // ── 场景 B：references 事件从 Redis 回放（data 里是退化后的 map） ──────────

    @Test
    void rebuildsReferencesFromRedisRoundTrippedMaps() throws Exception {
        StreamEvent evt = new StreamEvent("evt-1", ResponseType.REFERENCES, "", false);
        evt.setData(Map.of("references", List.of(redisRoundTrippedRef())));

        // 键序不属契约（不再字节序钉死）：knowledge_references 是 SearchResult 的序列化
        // （Java 字段名即键名）；data 是缓存映射直通，保持库内键名与未知键。
        String json = write(StreamResponseBuilder.build(evt, "req-1"));
        assertThat(json).contains("\"id\":\"req-1\",\"responseType\":\"references\"");
        assertThat(json).contains("\"knowledgeReferences\":[{\"id\":\"chunk-1\",\"content\":\"hello\"");
        assertThat(json).contains("\"knowledgeId\":\"kb-1\"");
        assertThat(json).contains("\"chunkIndex\":3");
        assertThat(json).contains("\"knowledgeTitle\":\"t\"");
        assertThat(json).contains("\"startAt\":10,\"endAt\":20");
        assertThat(json).contains("\"matchType\":0");
        assertThat(json).contains("\"subChunkId\":null");
        assertThat(json).contains("\"metadata\":{\"lang\":\"zh\"}");
        assertThat(json).contains("\"knowledgeFilename\":\"a.md\",\"knowledgeSource\":\"file\"");
        assertThat(json).contains("\"knowledgeBaseId\":\"kb-1\"");
        // data 直通：库内 snake 键 + 未知键原样带出
        // 重建结果**自身**（不是 data 直通）必须真的带上这些字段——
        // 若读者按错的键形状读，这里会全空（B135b1 修复的正是这个静默缺陷）
        JsonNode rebuilt = MAPPER.readTree(json).path("knowledgeReferences").path(0);
        assertThat(rebuilt.path("knowledgeId").asText()).isEqualTo("kb-1");
        assertThat(rebuilt.path("chunkIndex").asInt()).isEqualTo(3);
        assertThat(rebuilt.path("knowledgeTitle").asText()).isEqualTo("t");
        assertThat(rebuilt.path("startAt").asInt()).isEqualTo(10);
        assertThat(rebuilt.path("endAt").asInt()).isEqualTo(20);
        assertThat(rebuilt.path("knowledgeFilename").asText()).isEqualTo("a.md");
        assertThat(rebuilt.path("knowledgeSource").asText()).isEqualTo("file");
        assertThat(json).contains("\"data\":{\"references\":[");
        assertThat(json).contains("\"chunkIndex\":3");
        assertThat(json).contains("\"extra_unknown_key\":\"ignored\"");
    }

    /**
     * 与 {@code SearchResult} 序列化后（＝{@code data.references[]} 与 Redis 回放）同形的输入——注意故意用<b>乱序</b>的
     * {@code LinkedHashMap}，并混入一个不认识的键，验证两件事：
     * 输出键序与插入序无关（按键名排序），未知键照旧回显在 {@code data} 里。
     */
    private static Map<String, Object> redisRoundTrippedRef() {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("knowledgeId", "kb-1");
        ref.put("id", "chunk-1");
        ref.put("chunkIndex", 3);
        ref.put("content", "hello");
        ref.put("knowledgeTitle", "t");
        ref.put("startAt", 10);
        ref.put("endAt", 20);
        ref.put("seq", 2);
        ref.put("score", 0.75);
        ref.put("chunkType", "text");
        ref.put("parentChunkId", "");
        ref.put("imageInfo", "");
        ref.put("knowledgeFilename", "a.md");
        ref.put("knowledgeSource", "file");
        ref.put("knowledgeDescription", "");
        ref.put("knowledgeBaseId", "kb-1");
        ref.put("metadata", Map.of("lang", "zh"));
        ref.put("extra_unknown_key", "ignored");
        return ref;
    }

    // ── 场景 A：agent_query 提取会话/消息 ID ────────────────────────────────

    @Test
    void extractsSessionAndAssistantMessageIdForAgentQuery() throws Exception {
        StreamEvent evt = new StreamEvent("evt-2", ResponseType.AGENT_QUERY, "", true);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("assistantMessageId", "msg-1");
        data.put("sessionId", "sess-1");
        evt.setData(data);

        assertThat(ContractJson.deep(write(StreamResponseBuilder.build(evt, "req-2")))).isEqualTo(ContractJson.deep(
                "{\"id\":\"req-2\",\"responseType\":\"agentQuery\",\"content\":\"\",\"done\":true,"
                        + "\"sessionId\":\"sess-1\",\"assistantMessageId\":\"msg-1\","
                        + "\"data\":{\"assistantMessageId\":\"msg-1\",\"sessionId\":\"sess-1\"}}"));
    }

    /** 非 agent_query 事件即便带了这两个键也<b>不</b>提取（只在 agent_query 分支里取）。 */
    @Test
    void doesNotExtractIdsForOtherResponseTypes() throws Exception {
        StreamEvent evt = new StreamEvent("evt-6", ResponseType.ANSWER, "hi", false);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", "sess-9");
        data.put("assistantMessageId", "m9");
        data.put("eventId", "e1");
        evt.setData(data);

        assertThat(ContractJson.deep(write(StreamResponseBuilder.build(evt, "req-6")))).isEqualTo(ContractJson.deep(
                "{\"id\":\"req-6\",\"responseType\":\"answer\",\"content\":\"hi\",\"done\":false,"
                        + "\"data\":{\"assistantMessageId\":\"m9\",\"eventId\":\"e1\","
                        + "\"sessionId\":\"sess-9\"}}"));
    }

    // ── 场景 C/D/E：references 数据不成立时的三种退路 ───────────────────────

    /** data 里没有 {@code references} 键 → 不设该字段（键整颗省略），data 原样带出。 */
    @Test
    void leavesReferencesUnsetWhenKeyAbsent() throws Exception {
        StreamEvent evt = new StreamEvent("evt-3", ResponseType.REFERENCES, "", false);
        evt.setData(Map.of("foo", "bar"));

        assertThat(ContractJson.deep(write(StreamResponseBuilder.build(evt, "req-3")))).isEqualTo(ContractJson.deep(
                "{\"id\":\"req-3\",\"responseType\":\"references\",\"content\":\"\",\"done\":false,"
                        + "\"data\":{\"foo\":\"bar\"}}"));
    }

    /**
     * 元素不是 map → 逐个跳过。空列表也让整个键消失——
     * 所以输出与"没有引用"完全一样。
     */
    @Test
    void skipsNonMapReferenceElementsAndOmitsTheEmptyList() throws Exception {
        StreamEvent evt = new StreamEvent("evt-4", ResponseType.REFERENCES, "", false);
        evt.setData(Map.of("references", List.of("not-a-map", 42)));

        assertThat(ContractJson.deep(write(StreamResponseBuilder.build(evt, "req-4")))).isEqualTo(ContractJson.deep(
                "{\"id\":\"req-4\",\"responseType\":\"references\",\"content\":\"\",\"done\":false,"
                        + "\"data\":{\"references\":[\"not-a-map\",42]}}"));
    }

    /** {@code references} 是字符串 → 一条类型分支都不命中 → 同样不设该字段。 */
    @Test
    void leavesReferencesUnsetForUnrecognisedShape() throws Exception {
        StreamEvent evt = new StreamEvent("evt-5", ResponseType.REFERENCES, "", false);
        evt.setData(Map.of("references", "oops"));

        assertThat(ContractJson.deep(write(StreamResponseBuilder.build(evt, "req-5")))).isEqualTo(ContractJson.deep(
                "{\"id\":\"req-5\",\"responseType\":\"references\",\"content\":\"\",\"done\":false,"
                        + "\"data\":{\"references\":\"oops\"}}"));
    }

    // ── 活路径（不经 Redis）：直接就是 SearchResult 对象 ─────────────────────

    /**
     * 活路径下 {@code data["references"]} 已经是 {@code List<SearchResult>}，
     * <b>不做</b> map 重建——
     * 于是 {@code match_type} / {@code sub_chunk_id} / 各个原样透传的字段都保留原值。
     */
    @Test
    void passesThroughLiveSearchResultsWithoutRebuilding() throws Exception {
        SearchResult live = new SearchResult();
        live.setId("chunk-2");
        live.setContent("world");
        live.setKnowledgeId("kb-2");
        live.setMatchType(3);
        live.setScore(1.0);
        live.setSubChunkId(List.of("sub-1"));
        live.setChunkMetadata(MAPPER.readTree("{\"questions\":[\"q\"]}"));

        StreamEvent evt = new StreamEvent("evt-7", ResponseType.REFERENCES, "", false);
        evt.setData(Map.of("references", new ArrayList<>(List.of(live))));

        // 活对象直通：两侧都按 SearchResult 序列化（camelCase）
        String json = write(StreamResponseBuilder.build(evt, "req-7"));
        assertThat(json).contains("\"knowledgeReferences\":[{\"id\":\"chunk-2\",\"content\":\"world\"");
        assertThat(json).contains("\"knowledgeId\":\"kb-2\"");
        assertThat(json).contains("\"matchType\":3");
        assertThat(json).contains("\"subChunkId\":[\"sub-1\"]");
        assertThat(json).contains("\"chunkMetadata\":{\"questions\":[\"q\"]}");
        // score 是标准 Jackson 写法（1.0，不折叠成 1）
        assertThat(json).contains("\"score\":1.0,");
        assertThat(json).contains("\"data\":{\"references\":[");
        assertThat(json).contains("\"id\":\"chunk-2\"");
    }

    /** 空 {@code data} → 整键省略（不是输出 {@code "data":{}}）。 */
    @Test
    void omitsEmptyDataMap() throws Exception {
        StreamEvent evt = new StreamEvent("evt-8", ResponseType.ANSWER, "x", true);
        evt.setData(Map.of());

        assertThat(ContractJson.deep(write(StreamResponseBuilder.build(evt, "req-8")))).isEqualTo(ContractJson.deep(
                "{\"id\":\"req-8\",\"responseType\":\"answer\",\"content\":\"x\",\"done\":true}"));
    }

    // ── "不拷贝"语义 ─────────────────────────────────────────────────────────

    /** {@code data} 与事件是同一引用——下游改写会同时反映到事件上。 */
    @Test
    void sharesTheDataReferenceWithTheEvent() {
        StreamEvent evt = new StreamEvent("evt-8", ResponseType.ANSWER, "x", false);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("k", "v");
        evt.setData(data);

        StreamResponse response = StreamResponseBuilder.build(evt, "req-8");
        assertThat(response.getData()).isSameAs(data);
    }

    // ── SSE 头 ─────────────────────────────────────────────────────────────

    @Test
    void setsTheFourSseHeaders() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        SseContract.setSSEHeaders(response);

        assertThat(response.getHeader("Content-Type")).isEqualTo("text/event-stream");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-cache");
        assertThat(response.getHeader("Connection")).isEqualTo("keep-alive");
        assertThat(response.getHeader("X-Accel-Buffering")).isEqualTo("no");
    }

    /**
     * {@code setSSEHeaders} 是<b>覆盖</b>语义，
     * 调两次不应出现两个同名头。
     */
    @Test
    void sseHeadersAreOverwrittenNotAppended() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.addHeader("Cache-Control", "max-age=60");

        SseContract.setSSEHeaders(response);

        assertThat(response.getHeaders("Cache-Control")).containsExactly("no-cache");
    }

    /** {@code sendCompletionEvent} 是刻意的空实现——调用它不产生任何输出。 */
    @Test
    void sendCompletionEventIsDeliberatelyEmpty() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setContentType("text/event-stream");

        SseContract.sendCompletionEvent(response, "req-9");

        assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
