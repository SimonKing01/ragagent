package com.ragagent.session.controller.sse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.contract.model.StreamResponse;
import com.ragagent.contract.session.SseSink;
import com.ragagent.session.service.sse.SseFrameSink;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

/**
 * 把一个 {@link StreamResponse} 写成 gin 那条 SSE 帧——**逐字节**。
 *
 * <h2>为什么不能直接用 Spring 的 {@code SseEmitter}</h2>
 * <p>{@code SseEmitter} 自己拼帧，与既定线格式不同（它在冒号后加空格、不做 HTML 转义）。
 * 本项目要求响应逐字节一致，所以帧由本类手工拼。</p>
 *
 * <h2>SSE 帧的逐字节形态（既定契约）</h2>
 * <p>对 {@code StreamResponse} 载荷：</p>
 * <pre>
 *   "event:message\n"        ← 冒号后**没有空格**
 *   "data:" + &lt;JSON&gt; + "\n"
 *   再补一个 "\n"
 * </pre>
 * <p>合起来就是 {@code event:message\ndata:<json>\n\n}。</p>
 *
 * <h2>⚠️ 两个只看 Java 直觉会写错的地方</h2>
 * <ol>
 *   <li><b>JSON 的转义规则</b>：默认开
 *       HTML 转义——{@code < > &} 会被写成 {@code \u003c} / {@code \u003e} / {@code \u0026}。
 *       这条规则现在由 {@code config.JacksonConfig} <b>全局</b>装在应用统一的 mapper 上
 *       （原先只有 SSE 这一条路径特批），所以本类直接用注入的那个 mapper 即可。
 *       <b>不要</b>在这里再挂一个私有的 mapper——那正是"同一段文本在不同响应路径上
 *       给出不同字节"的来源。</li>
 *   <li><b>Content-Type 会被覆盖</b>：SSE 渲染器会<b>无条件</b>把 Content-Type 改写成
 *       {@code text/event-stream;charset=utf-8}——即 {@code setSSEHeaders} 设的
 *       {@code text/event-stream} <b>不是</b>线上的最终值。
 *       本类照做（{@link #applyRenderedContentType}），否则头就不一致了。
 *       注意 {@code Cache-Control} 是"没有才设"，所以 {@code no-cache} 保持不变。</li>
 * </ol>
 */
@Component
public class SseFrameWriter implements SseFrameSink {

    /**
     * SSE 渲染最终写出的 Content-Type（渲染器无条件覆盖，见类注释）。
     */
    public static final String RENDERED_CONTENT_TYPE = "text/event-stream;charset=utf-8";

    /** 事件名固定为 {@code message}。 */
    public static final String EVENT_NAME = "message";

    private final ObjectMapper mapper;

    public SseFrameWriter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 把 Content-Type 覆盖成渲染器的既定值。
     *
     * <p>必须在**任何正文写出之前**调用。</p>
     */
    public static void applyRenderedContentType(HttpServletResponse response) {
        response.setHeader("Content-Type", RENDERED_CONTENT_TYPE);
    }

    /**
     * 写一帧并 flush。
     *
     * <p>序列化用的是应用统一的 mapper（其他转义规则见类注释）。序列化失败在写出任何字节前直接抛出，
     * 由调用方按"写失败"处理（关流）。</p>
     */
    public void write(HttpServletResponse response, StreamResponse payload) throws IOException {
        response.getOutputStream().write(frameOf(payload).getBytes(StandardCharsets.UTF_8));
        response.getOutputStream().flush();
    }

    /** 组帧（序列化 + {@code event:}/{@code data:} 行）——写出口无关（servlet 直写与 SseSink 共用）。 */
    @Override
    public String frameOf(StreamResponse payload) throws IOException {
        String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IOException("failed to marshal stream response: " + e.getMessage(), e);
        }

        StringBuilder frame = new StringBuilder(json.length() + 32);
        frame.append("event:").append(EVENT_NAME).append('\n');
        frame.append("data:").append(json).append('\n').append('\n');
        return frame.toString();
    }

    /** 写一帧到 {@link SseSink}（embed/QA 会话流的出口）。 */
    @Override
    public void write(SseSink sink, StreamResponse payload) throws IOException {
        sink.writeFrame(frameOf(payload));
    }
}
