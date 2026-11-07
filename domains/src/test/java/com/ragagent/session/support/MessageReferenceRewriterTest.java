
package com.ragagent.session.support;


import static org.assertj.core.api.Assertions.assertThat;

import com.ragagent.contract.agent.AgentStep;
import com.ragagent.contract.agent.AgentToolCall;
import com.ragagent.contract.retrieval.SearchResult;
import com.ragagent.contract.session.MessageImage;
import com.ragagent.contract.storage.support.FileUrlPort;
import com.ragagent.contract.storage.support.Mode;
import com.ragagent.contract.tool.ToolResult;
import com.ragagent.platform.storage.support.Rewriter;
import com.ragagent.session.domain.message.Message;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 消息历史响应的存储引用重写（MessageReferenceRewriter）。
 *
 * <p>2026-09-30 从 storage/support/RewriterTest 搬来：被测代码搬到了会话侧（消 session ⇄ storage 环），
 * 用例随之搬家，断言逐字保留。</p>
 */
class MessageReferenceRewriterTest {

    /** 把 URL 重写内核包成消息级重写器。 */
    private static MessageReferenceRewriter wrap(Rewriter rewriter) {
        return new MessageReferenceRewriter(rewriter);
    }

    /** 固定解析：任何输入都返回同一 URL（与原 storage 侧用例的 StubFileService 等价）。 */
    private static final class StubFileService implements FileUrlPort {
        private final String url;

        StubFileService(String url) {
            this.url = url;
        }

        @Override
        public String getFileURL(String filePath) {
            return url;
        }
    }

    private static Rewriter stubRewriter(String url) {
        StubFileService svc = new StubFileService(url);
        return Rewriter.forRequest(Mode.PUBLIC, ref -> svc);
    }

    @Test
    void rewriteMessagesCoversEveryFieldGoTouches() {
        MessageReferenceRewriter w = wrap(stubRewriter("https://cdn.example.com/x.png"));
        Message message = fullMessage();
        List<Message> messages = new ArrayList<>();
        messages.add(null);
        messages.add(message);

        w.rewriteMessages(messages);

        assertThat(message.getContent()).isEqualTo("answer ![fig](https://cdn.example.com/x.png)");
        assertThat(message.getImages().get(0).getUrl()).isEqualTo("https://cdn.example.com/x.png");
        assertThat(message.getImages().get(0).getCaption())
                .isEqualTo("shows ![inline](https://cdn.example.com/x.png)");
        assertThat(message.getKnowledgeReferences().get(0).getContent())
                .isEqualTo("chunk ![c](https://cdn.example.com/x.png)");
        assertThat(message.getKnowledgeReferences().get(0).getImageInfo())
                .isEqualTo("[{\"url\":\"https://cdn.example.com/x.png\"}]");
        assertThat(message.getAgentSteps().get(0).getThought())
                .isEqualTo("looking at ![t](https://cdn.example.com/x.png)");
        assertThat(message.getAgentSteps().get(0).getToolCalls().get(0).getReflection())
                .isEqualTo("saw ![r](https://cdn.example.com/x.png)");
        assertThat(message.getAgentSteps().get(0).getToolCalls().get(0).getResult().getOutput())
                .isEqualTo("chart ![o](https://cdn.example.com/x.png)");
    }

    @Test
    void rewriteMessagesDisabledLeavesHandles() {
        MessageReferenceRewriter w = wrap(new Rewriter(null, "TEST"));
        Message message = new Message();
        message.setContent("![a](resource://xifDo7NTSL300Lp1goVutw)");

        w.rewriteMessages(new ArrayList<>(List.of(message)));

        assertThat(message.getContent()).isEqualTo("![a](resource://xifDo7NTSL300Lp1goVutw)");
    }

    @Test
    void rewriteMessagesResponseDoesNotMutateOriginals() {
        MessageReferenceRewriter w = wrap(stubRewriter("https://cdn.example.com/x.png"));
        Message original = fullMessage();
        List<Message> messages = new ArrayList<>(List.of(original));

        List<Message> out = w.rewriteMessagesResponse(messages);

        assertThat(out).hasSize(1);
        assertThat(out.get(0)).isNotSameAs(original);
        assertThat(original.getContent()).isEqualTo("answer ![fig](resource://xifDo7NTSL300Lp1goVutw)");
        assertThat(out.get(0).getContent()).isEqualTo("answer ![fig](https://cdn.example.com/x.png)");
        assertThat(original.getKnowledgeReferences().get(0).getContent())
                .isEqualTo("chunk ![c](resource://xifDo7NTSL300Lp1goVutw)");
        assertThat(out.get(0).getKnowledgeReferences().get(0).getContent())
                .isEqualTo("chunk ![c](https://cdn.example.com/x.png)");
        // agent steps 也必须解耦：改写不得污染缓存的原始消息
        assertThat(original.getAgentSteps().get(0).getThought())
                .isEqualTo("looking at ![t](resource://xifDo7NTSL300Lp1goVutw)");
    }

    @Test
    void rewriteMessagesResponseDisabledOrEmptyReturnsInput() {
        assertThat(wrap(new Rewriter(null, "TEST")).rewriteMessagesResponse(List.of())).isEmpty();
        assertThat(wrap(stubRewriter("https://x/y.png")).rewriteMessagesResponse(null)).isNull();
        List<Message> messages = List.of(fullMessage());
        assertThat(wrap(new Rewriter(null, "TEST")).rewriteMessagesResponse(messages)).isSameAs(messages);
    }

    /** 一条把每个被重写的字段都填上的助手消息。 */
    /** 构造带 <media> 工具输出的消息；content 里的封面/播放路径由调用方给（可故意写错）。 */
    private static Message messageWithMedia(String content) {
        ToolResult toolResult = new ToolResult();
        toolResult.setOutput("""
                <document id="d1" kb="b1" title="aion">
                <media type="video">
                <poster>local://11/exports/cover-839ef624-018f-4096-8437-2b47afd242b7_509098126147583.jpg</poster>
                <play>local://11/exports/aion-i60_509098283386166.mp4</play>
                </media>
                </document>
                """);
        AgentToolCall call = new AgentToolCall();
        call.setResult(toolResult);
        AgentStep step = new AgentStep();
        step.setToolCalls(List.of(call));

        Message message = new Message();
        message.setContent(content);
        message.setAgentSteps(new ArrayList<>(List.of(step)));
        return message;
    }

    private static AgentToolCall callWith(ToolResult result) {
        AgentToolCall call = new AgentToolCall();
        call.setResult(result);
        return call;
    }

    @Test
    void correctsMangledPosterFromToolOutput() {
        Message message = messageWithMedia(
                "看这个 [play video](local://11/exports/aion-i60_509098283386166.mp4 "
                        + "\"poster=local://11/exports/cover-d1_509098126147583.jpg\")");

        String fixed = MessageReferenceRewriter.correctMediaRefs(message);

        assertThat(fixed).contains("cover-839ef624-018f-4096-8437-2b47afd242b7_509098126147583.jpg");
        assertThat(fixed).doesNotContain("cover-d1_509098126147583.jpg");
    }

    @Test
    void correctsPosterFromMetadataLines() {
        // 媒体分块的新格式：元信息行而不是 <media> XML
        ToolResult toolResult = new ToolResult();
        toolResult.setOutput("""
                - file: aion i60.mp4
                - duration: 00:36
                - poster: local://11/exports/cover-839ef624-018f-4096-8437-2b47afd242b7_509098126147583.jpg (internal ref)
                - media: local://11/exports/aion-i60_509098283386166.mp4 (internal ref)
                """);
        AgentToolCall call = new AgentToolCall();
        call.setResult(toolResult);
        AgentStep step = new AgentStep();
        step.setToolCalls(List.of(call));

        Message message = new Message();
        message.setContent("[play video](local://11/exports/aion-i60_509098283386166.mp4 "
                + "\"poster=local://11/exports/cover-d1_509098126147583.jpg\")");
        message.setAgentSteps(new ArrayList<>(List.of(step)));

        String fixed = MessageReferenceRewriter.correctMediaRefs(message);

        assertThat(fixed).contains("cover-839ef624-018f-4096-8437-2b47afd242b7_509098126147583.jpg");
        assertThat(fixed).doesNotContain("cover-d1_");
    }

    @Test
    void correctsMediaRefsEvenWhenUrlRewriterIsDisabled() {
        // 未配置 APP_EXTERNAL_URL 时 Rewriter 关闭，但媒体校正仍必须生效
        MessageReferenceRewriter disabled = wrap(Rewriter.forRequest(Mode.HANDLE, null));
        Message message = messageWithMedia(
                "![poster](local://11/exports/cover-d1_509098126147583.jpg)");

        String fixed = disabled.rewriteMessagesResponse(List.of(message)).get(0).getContent();

        assertThat(fixed).contains("cover-839ef624-018f-4096-8437-2b47afd242b7_509098126147583.jpg");
        assertThat(fixed).doesNotContain("cover-d1_");
    }

    @Test
    void correctsPosterFromStructuredData() {
        // 真实形态：媒体信息在 result.data.results[].content，而不是 output
        java.util.Map<String, Object> chunk = new java.util.LinkedHashMap<>();
        chunk.put("chunkId", "c1");
        chunk.put("content", "- poster: local://11/exports/cover-839ef624-018f-4096-8437-2b47afd242b7_509098126147583.jpg (internal ref)\n"
                + "- media: local://11/exports/aion-i60_509098283386166.mp4 (internal ref)");
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("results", List.of(chunk));

        ToolResult toolResult = new ToolResult();
        toolResult.setData(data);
        AgentToolCall call = new AgentToolCall();
        call.setResult(toolResult);
        AgentStep step = new AgentStep();
        step.setToolCalls(List.of(call));

        Message message = new Message();
        message.setContent("![poster](local://11/exports/cover-d1_509098126147583.jpg)");
        message.setAgentSteps(new ArrayList<>(List.of(step)));

        String fixed = MessageReferenceRewriter.correctMediaRefs(message);

        assertThat(fixed).contains("cover-839ef624-018f-4096-8437-2b47afd242b7_509098126147583.jpg");
        assertThat(fixed).doesNotContain("cover-d1_");
    }

    @Test
    void keepsValidMediaRefsUntouched() {
        String play = "local://11/exports/aion-i60_509098283386166.mp4";
        String poster = "local://11/exports/cover-839ef624-018f-4096-8437-2b47afd242b7_509098126147583.jpg";
        Message message = messageWithMedia("[play video](" + play + " \"poster=" + poster + "\")");

        assertThat(MessageReferenceRewriter.correctMediaRefs(message))
                .contains(play).contains(poster);
    }

    @Test
    void keepsRefsWhenCandidatesAreAmbiguous() {
        ToolResult first = new ToolResult();
        first.setOutput("<play>local://11/exports/a.mp4</play>");
        ToolResult second = new ToolResult();
        second.setOutput("<play>local://11/exports/b.mp4</play>");
        AgentStep step = new AgentStep();
        step.setToolCalls(List.of(callWith(first), callWith(second)));

        Message message = new Message();
        message.setContent("[play video](local://11/exports/broken.mp4)");
        message.setAgentSteps(new ArrayList<>(List.of(step)));

        // 两个候选时宁可不改，避免把 A 视频的链接换成 B
        assertThat(MessageReferenceRewriter.correctMediaRefs(message)).contains("broken.mp4");
    }

    private static Message fullMessage() {
        Message message = new Message();
        message.setContent("answer ![fig](resource://xifDo7NTSL300Lp1goVutw)");

        MessageImage image = new MessageImage();
        image.setUrl("resource://aaaabbbbccccddddeeeeff");
        image.setCaption("shows ![inline](minio://bucket/10000/exports/a.png)");
        message.setImages(new ArrayList<>(List.of(image)));

        SearchResult ref = new SearchResult();
        ref.setContent("chunk ![c](resource://xifDo7NTSL300Lp1goVutw)");
        ref.setImageInfo("[{\"url\":\"resource://xifDo7NTSL300Lp1goVutw\"}]");
        message.setKnowledgeReferences(new ArrayList<>(List.of(ref)));

        ToolResult toolResult = new ToolResult();
        toolResult.setOutput("chart ![o](resource://xifDo7NTSL300Lp1goVutw)");
        AgentToolCall call = new AgentToolCall();
        call.setReflection("saw ![r](resource://xifDo7NTSL300Lp1goVutw)");
        call.setResult(toolResult);

        AgentStep step = new AgentStep();
        step.setThought("looking at ![t](resource://xifDo7NTSL300Lp1goVutw)");
        step.setToolCalls(List.of(call));
        message.setAgentSteps(new ArrayList<>(List.of(step)));
        return message;
    }
}
