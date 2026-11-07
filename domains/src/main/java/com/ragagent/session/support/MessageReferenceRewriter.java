package com.ragagent.session.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.contract.agent.AgentStep;
import com.ragagent.contract.agent.AgentToolCall;
import com.ragagent.contract.session.MessageImage;
import com.ragagent.platform.storage.support.Rewriter;
import com.ragagent.platform.web.JsonMappers;
import com.ragagent.session.domain.message.Message;
import java.util.List;

/**
 * 消息历史响应的存储引用重写。
 *
 * <p> 从 {@link Rewriter} 搬来：这段代码操作的是**会话实体**（{@code Message}/
 * {@code MessageImage}/{@code AgentStep}），留在存储域会让 {@code storage} 反向依赖 {@code session}
 * （即 {@code session ⇄ storage} 环）。URL 重写内核仍在 {@link Rewriter}，本类只做"消息 → 字段级重写"的编排：
 * 因此依赖方向是"会话 → 存储"，单向。</p>
 *
 * <p>生产调用方：{@code MessageController} 的消息列表端点（{@code rewriter.rewriteMessagesResponse}）。
 * 行为有 {@code MessageReferenceRewriterTest} 覆盖。</p>
 */
public class MessageReferenceRewriter {

    private final Rewriter rewriter;

    public MessageReferenceRewriter(Rewriter rewriter) {
        this.rewriter = rewriter;
    }


    /**
     * {@code cloneMessages} 用的 mapper——**与 HTTP 那个不是同一个**，也刻意不是。
     *
     * <p>深拷贝走的是 JSON 序列化/反序列化往返
     * （拷贝嵌套列表与 agent steps，且与源列表解耦），
     * 不是产出响应字节。所以只需要能把 {@code Message} 完整读回来即可，
     * 时间格式无所谓——但 {@code JavaTimeModule} 不能少，
     * 否则 {@code created_at} 这类字段直接抛 {@code InvalidDefinitionException}。</p>
     */
    private static final ObjectMapper CLONE_MAPPER = JsonMappers.lenient();

    /**
     * 返回重写后的消息**副本**，
     * 调用方因此能保住原对象（例如来自 service 缓存的那些）。禁用时原样返回。
     */
    public List<Message> rewriteMessagesResponse(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }
        // 媒体校正与 URL 重写解耦：没有配置外链（rewriter 关闭）时也要跑，
        // 否则模型写错的封面/播放路径不会被修，前端一直 404；
        // 但真的没有要改的内容时保持原对象（调用方可能持有缓存实体）。
        if (!rewriter.enabled() && !needsMediaCorrection(messages)) {
            return messages;
        }
        List<Message> out = cloneMessages(messages);
        rewriteMessages(out);
        return out;
    }

    /**
     * 经 JSON 深拷贝，
     * 使嵌套列表与 agent steps 与源列表互不共享。
     *
     * <p>这次往返会**丢掉不参与 JSON 序列化的字段**
     * （{@code rendered_content} / {@code execution_context}）——
     * 它们本来就不出响应，所以不可见；但这是刻意的行为，别"顺手修好"。</p>
     */
    private static List<Message> cloneMessages(List<Message> messages) {
        try {
            String json = CLONE_MAPPER.writeValueAsString(messages);
            return CLONE_MAPPER.readValue(json, new TypeReference<List<Message>>() {});
        } catch (Exception e) {
            // 往返失败就退回原列表
            return messages;
        }
    }

    /**
     * 把消息历史响应里的存储引用换成
     * 客户端能直接加载的图片 URL。<b>就地改写</b>。
     */
    public void rewriteMessages(List<Message> messages) {
        if (messages == null) {
            return;
        }
        // 媒体校正与 URL 重写无关：即使没有配置外链（rewriter 关闭）也要跑，
        // 否则模型写错的封面路径会一直 404。
        for (Message message : messages) {
            if (message == null) {
                continue;
            }
            message.setContent(correctMediaRefs(message));
        }
        if (!rewriter.enabled()) {
            return;
        }
        for (Message message : messages) {
            if (message == null) {
                continue;
            }
            message.setContent(rewriter.rewrite(message.getContent()));
            if (message.getImages() != null) {
                for (MessageImage image : message.getImages()) {
                    image.setUrl(rewriter.rewriteRef(image.getUrl()));
                    image.setCaption(rewriter.rewrite(image.getCaption()));
                }
            }
            message.setKnowledgeReferences(rewriter.copyReferences(message.getKnowledgeReferences()));
            rewriteAgentSteps(message.getAgentSteps());
        }
    }

    /** 是否有消息的媒体引用需要校正（纯函数判定，不改动入参）。 */
    private static boolean needsMediaCorrection(List<Message> messages) {
        for (Message message : messages) {
            if (message == null) {
                continue;
            }
            String content = message.getContent();
            if (content != null && !content.equals(correctMediaRefs(message))) {
                return true;
            }
        }
        return false;
    }

    /** 媒体引用：{@code local://…/exports/xxx.mp4}（视频/音频）或图片。 */
    private static final java.util.regex.Pattern MEDIA_TOKEN =
            java.util.regex.Pattern.compile(
                    "(?:storage://[0-9A-Za-z_-]+/)?(?:local|resource|minio|s3|cos|tos|oss|obs|ks3)://"
                            + "[^\\s)\"'<>]+");

    private static final java.util.regex.Pattern POSTER_TAG =
            java.util.regex.Pattern.compile("(?is)<poster>([^<]+)</poster>");

    private static final java.util.regex.Pattern PLAY_TAG =
            java.util.regex.Pattern.compile("(?is)<play>([^<]+)</play>");

    /** 媒体分块的元信息行：{@code - poster: local://… (internal ref)}。 */
    private static final java.util.regex.Pattern POSTER_LINE =
            java.util.regex.Pattern.compile("(?mi)^\\s*[-*]\\s*poster:\\s*(\\S+)");

    /** 媒体分块的元信息行：{@code - media: local://… (internal ref)}。 */
    private static final java.util.regex.Pattern MEDIA_LINE =
            java.util.regex.Pattern.compile("(?mi)^\\s*[-*]\\s*media:\\s*(\\S+)");

    private static final java.util.regex.Pattern MEDIA_EXT = java.util.regex.Pattern.compile(
            "(?i).*\\.(?:mp4|mov|m4v|webm|mkv|avi|mp3|wav|m4a|flac|ogg)(?:[?#].*)?$");

    private static final java.util.regex.Pattern IMAGE_EXT = java.util.regex.Pattern.compile(
            "(?i).*\\.(?:jpg|jpeg|png|webp|gif|bmp)(?:[?#].*)?$");

    /**
     * 用检索工具输出里的**真实**媒体引用校正回答正文。
     *
     * <p>模型会照着分块里的 {@code poster=…} / 播放链接复述，但可能把文档句柄拼进文件名
     * （例如把 {@code cover-839e….jpg} 写成 {@code cover-d1_….jpg}），导致前端 404、封面丢失。
     * 这里以工具结果 {@code <media>} 块里的值为准：正文里同类型引用只要不是已知值，
     * 且该类型只有一个候选，就替换成真实值；候选不唯一（多个视频/图片）时保持原样，
     * 宁可不改也不猜错。</p>
     */
    static String correctMediaRefs(Message message) {
        String content = message == null ? null : message.getContent();
        if (content == null || content.isEmpty() || message.getAgentSteps() == null) {
            return content == null ? "" : content;
        }
        java.util.Set<String> mediaRefs = new java.util.LinkedHashSet<>();
        java.util.Set<String> posterRefs = new java.util.LinkedHashSet<>();
        for (AgentStep step : message.getAgentSteps()) {
            if (step == null || step.getToolCalls() == null) {
                continue;
            }
            for (AgentToolCall call : step.getToolCalls()) {
                if (call == null || call.getResult() == null) {
                    continue;
                }
                String output = call.getResult().getOutput();
                collect(output, POSTER_TAG, posterRefs);
                collect(output, POSTER_LINE, posterRefs);
                collect(output, PLAY_TAG, mediaRefs);
                collect(output, MEDIA_LINE, mediaRefs);
                // 工具结果的结构化 data 里也带分块正文（results[].content）
                collectFromObject(call.getResult().getData(), posterRefs, mediaRefs);
            }
        }
        if (mediaRefs.isEmpty() && posterRefs.isEmpty()) {
            return content;
        }
        java.util.regex.Matcher m = MEDIA_TOKEN.matcher(content);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String token = m.group();
            String replacement = token;
            if (MEDIA_EXT.matcher(token).matches()) {
                if (!mediaRefs.contains(token) && mediaRefs.size() == 1) {
                    replacement = mediaRefs.iterator().next();
                }
            } else if (IMAGE_EXT.matcher(token).matches()) {
                if (!posterRefs.contains(token) && posterRefs.size() == 1) {
                    replacement = posterRefs.iterator().next();
                }
            }
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 结构化结果里递归找字符串，按同样的标签/元信息行收集媒体引用。 */
    private static void collectFromObject(Object node, java.util.Set<String> posterRefs,
            java.util.Set<String> mediaRefs) {
        if (node == null) {
            return;
        }
        if (node instanceof String text) {
            collect(text, POSTER_TAG, posterRefs);
            collect(text, POSTER_LINE, posterRefs);
            collect(text, PLAY_TAG, mediaRefs);
            collect(text, MEDIA_LINE, mediaRefs);
            return;
        }
        if (node instanceof java.util.Map<?, ?> map) {
            for (Object value : map.values()) {
                collectFromObject(value, posterRefs, mediaRefs);
            }
            return;
        }
        if (node instanceof java.util.Collection<?> list) {
            for (Object value : list) {
                collectFromObject(value, posterRefs, mediaRefs);
            }
        }
    }

    private static void collect(String text, java.util.regex.Pattern tag, java.util.Set<String> out) {
        if (text == null || text.isEmpty()) {
            return;
        }
        java.util.regex.Matcher m = tag.matcher(text);
        while (m.find()) {
            String value = m.group(1).trim();
            if (!value.isEmpty()) {
                out.add(value);
            }
        }
    }

    /**
     * 覆盖推理轨迹——
     * 工具输出里嵌着检索到的图与生成的图表的 Markdown 图片。
     *
     * <p>只改这四个字段（{@code thought} / {@code reasoning_content} /
     * {@code tool_calls[].reflection} / {@code tool_calls[].result.output}），
     * {@code result.data} 之类**不动**——刻意只覆盖这四处。</p>
     */
    private void rewriteAgentSteps(List<AgentStep> steps) {
        if (steps == null) {
            return;
        }
        for (AgentStep step : steps) {
            if (step == null) {
                continue;
            }
            step.setThought(rewriter.rewrite(step.getThought()));
            step.setReasoningContent(rewriter.rewrite(step.getReasoningContent()));
            if (step.getToolCalls() == null) {
                continue;
            }
            for (AgentToolCall call : step.getToolCalls()) {
                if (call == null) {
                    continue;
                }
                call.setReflection(rewriter.rewrite(call.getReflection()));
                if (call.getResult() != null) {
                    call.getResult().setOutput(rewriter.rewrite(call.getResult().getOutput()));
                }
            }
        }
    }
}
