package com.ragagent.im.service;

import com.ragagent.im.runtime.AdapterInterfaces.Adapter;
import com.ragagent.im.runtime.AdapterInterfaces.StreamSender;
import com.ragagent.im.runtime.ImFormat;
import com.ragagent.im.runtime.IncomingMessage;
import com.ragagent.im.runtime.ReplyMessage;
import com.ragagent.im.runtime.ThinkDisplay;
import com.ragagent.platform.storage.support.Rewriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * IM 出站内容整形与发送：XML/引用标签清洗、存储链接重写、最终答案兜底、
 * 流式整段回复与静默回复（发送失败只记日志，不反噬主流程）。
 */
final class ImOutboundFormatter {

    private static final Logger log = LoggerFactory.getLogger(ImOutboundFormatter.class);

    private final ImTurnOrchestrator service;

    ImOutboundFormatter(ImTurnOrchestrator service) {
        this.service = service;
    }

    /** 流式整段回复。 */
    void sendStreamReply(IncomingMessage msg, StreamSender streamer, String content)
            throws Exception {
        String streamId = streamer.startStream(msg);
        streamer.updateStreamContent(msg, streamId, content);
        streamer.finalizeStream(msg, streamId, content);
        streamer.endStream(msg, streamId);
    }

    // ── 出站内容整形 ─────────────────────────────────────────────────────

    String cleanIMContent(String content) {
        return cleanIMContent(content, false);
    }

    /**
     * 出站整形：先把存储引用换成公网 URL，再按渠道能力处理媒体。
     *
     * <p>视频/音频在 IM 一律剥除（平台不支持，留着就是点不开的死链）；图片在
     * 不支持 markdown 的渠道降级成可点链接。</p>
     */
    String cleanIMContent(String content, boolean rendersImages) {
        content = ImFormat.stripImageXMLTags(content);
        content = ImFormat.stripImCitationTags(content);
        if (service.storageResolver != null) {
            content = new Rewriter(service.storageResolver, "IM")
                    .rewrite(content);
        }
        // 卡片/富文本都不认 local:// 图片，留着会让整条消息发不出去
        content = ImFormat.stripNonHttpImages(content);
        content = ImFormat.stripAudioVideoLinks(content);
        if (!rendersImages) {
            content = ImFormat.degradeMarkdownImages(content);
        }
        return content;
    }


    String formatIMOutboundAnswerOrFallback(String raw) {
        return formatIMOutboundAnswerOrFallback(raw, false);
    }

    String formatIMOutboundAnswerOrFallback(String raw, boolean rendersImages) {
        String content = cleanIMContent(ThinkDisplay.formatIMDisplayContent(raw,
                ThinkDisplay.STREAM_DISPLAY_FINAL), rendersImages);
        if (content.strip().isEmpty()) {
            return ImFormat.IM_NO_ANSWER_FALLBACK;
        }
        return content;
    }


    void sendReplyQuiet(Adapter adapter, IncomingMessage msg, ReplyMessage reply) {
        try {
            adapter.sendReply(msg, reply);
        } catch (Exception e) {
            log.warn("[IM] Send reply failed", e);
        }
    }
}
