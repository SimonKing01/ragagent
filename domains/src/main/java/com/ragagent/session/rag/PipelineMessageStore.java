package com.ragagent.session.rag;

import com.ragagent.session.dto.PipelineMessageImageView;
import com.ragagent.session.dto.PipelineMessageView;

import java.util.List;

/**
 * chat 管线的消息读写面：端口两端都是会话域内类型。
 */
public interface PipelineMessageStore {

    /** 找不到返回 null 或抛异常。 */
    PipelineMessageView getMessage(String sessionId, String messageId);

    List<PipelineMessageView> getRecentMessagesBySession(String sessionId, int limit);

    void updateMessageImages(String sessionId, String messageId,
            List<PipelineMessageImageView> images);

    void updateMessageRenderedContent(String sessionId, String messageId, String renderedContent);
}
