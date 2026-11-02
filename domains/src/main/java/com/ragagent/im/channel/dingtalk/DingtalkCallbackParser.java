package com.ragagent.im.channel.dingtalk;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.im.runtime.ImTypes;
import com.ragagent.im.runtime.IncomingMessage;
import java.util.ArrayList;
import java.util.List;

/**
 * 钉钉回调解析：纯函数，无 HTTP / 无 Spring。
 *
 * <p>四段链固定为 <b>richText → file/picture → audio → text</b>；richText 取文本片段
 * （trim + 换行连接）与首图 downloadCode（原图优先回落预览码），多图时追加
 * “仅处理第一张”提示；audio 取 recognition 作为文本。</p>
 */
final class DingtalkCallbackParser {

    /** 群聊判据。 */
    static final String CONV_TYPE_GROUP = "2";

    private DingtalkCallbackParser() {
    }

    static IncomingMessage parseCallbackMessage(JsonNode msg) {
        String conversationType = msg.path("conversationType").asText("");
        boolean isGroup = CONV_TYPE_GROUP.equals(conversationType);
        String chatType = isGroup ? ImTypes.CHAT_TYPE_GROUP : ImTypes.CHAT_TYPE_DIRECT;
        String chatId = isGroup ? msg.path("conversationId").asText("") : "";

        String userId = msg.path("senderStaffId").asText("");
        if (userId.isEmpty()) {
            userId = msg.path("senderId").asText("");
        }
        String msgType = msg.path("msgtype").asText("");
        String msgId = msg.path("msgId").asText("");
        String robotCode = msg.path("robotCode").asText("");
        JsonNode content = msg.path("content");

        IncomingMessage incoming = new IncomingMessage();
        incoming.platform = ImTypes.PLATFORM_DINGTALK;
        incoming.userId = userId;
        incoming.userName = msg.path("senderNick").asText("");
        incoming.chatId = chatId;
        incoming.chatType = chatType;
        incoming.messageId = msgId;
        incoming.extra.put("session_webhook", msg.path("sessionWebhook").asText(""));
        incoming.extra.put("raw_msgtype", msgType);

        RichText rich = parseRichTextContent(msgType, content);
        if (rich != null) {
            applyRichText(incoming, rich, msgId, robotCode);
            return incoming;
        }
        FileContent file = parseFileContent(msgType, content);
        if (file != null) {
            incoming.messageType = file.messageType();
            incoming.fileName = defaultFileName(file.messageType(), file.fileName(), msgId);
            incoming.fileKey = file.downloadCode();
            incoming.extra.put("robot_code", robotCode);
            return incoming;
        }
        String audioText = parseAudioContent(msgType, content);
        if (audioText != null) {
            incoming.messageType = ImTypes.MESSAGE_TYPE_TEXT;
            incoming.content = audioText;
            return incoming;
        }
        incoming.messageType = ImTypes.MESSAGE_TYPE_TEXT;
        incoming.content = msg.path("text").path("content").asText("").trim();
        return incoming;
    }

    private static final class RichText {
        String text = "";
        String downloadCode = "";
        int pictureCount;
    }

    /** msgtype=richText 才认；否则 null。 */
    private static RichText parseRichTextContent(String msgType, JsonNode content) {
        if (msgType == null || !msgType.trim().equalsIgnoreCase("richText")) {
            return null;
        }
        if (content == null || content.isMissingNode() || content.isNull()) {
            return null;
        }
        JsonNode richText = content.path("richText");
        if (!richText.isArray()) {
            return null;
        }
        List<String> textParts = new ArrayList<>();
        RichText result = new RichText();
        for (JsonNode item : richText) {
            String text = item.path("text").asText("").trim();
            if (!text.isEmpty()) {
                textParts.add(text);
            }
            String code = pictureDownloadCode(item);
            if (code.isEmpty()) {
                continue;
            }
            result.pictureCount++;
            if (result.downloadCode.isEmpty()) {
                result.downloadCode = code;
            }
        }
        result.text = String.join("\n", textParts);
        return result;
    }

    /** type=picture 才认；原图码优先回落预览码。 */
    private static String pictureDownloadCode(JsonNode item) {
        if (!item.path("type").asText("").trim().equalsIgnoreCase("picture")) {
            return "";
        }
        String code = item.path("downloadCode").asText("");
        return code.isEmpty() ? item.path("pictureDownloadCode").asText("") : code;
    }

    /** msgtype=audio 且 recognition 非空。 */
    private static String parseAudioContent(String msgType, JsonNode content) {
        if (msgType == null || !msgType.trim().equalsIgnoreCase("audio")) {
            return null;
        }
        if (content == null || content.isMissingNode() || content.isNull()) {
            return null;
        }
        String text = content.path("recognition").asText("").trim();
        return text.isEmpty() ? null : text;
    }

    /** 文件/图片的解析结果。 */
    private record FileContent(String messageType, String fileName, String downloadCode) {
    }

    /** 只认 file/picture；图片不带 fileName（服务端补扩展名）。 */
    private static FileContent parseFileContent(String msgType, JsonNode content) {
        String messageType;
        switch (msgType == null ? "" : msgType) {
            case "file" -> messageType = ImTypes.MESSAGE_TYPE_FILE;
            case "picture" -> messageType = ImTypes.MESSAGE_TYPE_IMAGE;
            default -> {
                return null;
            }
        }
        String downloadCode = content == null ? "" : content.path("downloadCode").asText("");
        if (downloadCode.isEmpty()) {
            downloadCode = content == null ? "" : content.path("pictureDownloadCode").asText("");
        }
        if (downloadCode.isEmpty()) {
            return null;
        }
        String fileName = content == null ? "" : content.path("fileName").asText("");
        if (ImTypes.MESSAGE_TYPE_IMAGE.equals(messageType)) {
            fileName = "";
        }
        return new FileContent(messageType, fileName, downloadCode);
    }

    /** 有图 → 图片消息（取首图），否则文本；多图附提示。 */
    private static void applyRichText(IncomingMessage incoming, RichText rich, String msgId,
                                      String robotCode) {
        incoming.content = rich.text;
        incoming.messageType = rich.downloadCode.isEmpty()
                ? ImTypes.MESSAGE_TYPE_TEXT : ImTypes.MESSAGE_TYPE_IMAGE;

        if (rich.pictureCount == 0) {
            return;
        }
        incoming.extra.put("rich_text_picture_count", String.valueOf(rich.pictureCount));
        if (rich.downloadCode.isEmpty()) {
            return;
        }
        incoming.fileKey = rich.downloadCode;
        incoming.fileName = defaultFileName(ImTypes.MESSAGE_TYPE_IMAGE, "", msgId);
        incoming.extra.put("robot_code", robotCode);
        if (rich.pictureCount > 1) {
            incoming.content = appendDroppedPictureHint(incoming.content, rich.pictureCount);
        }
    }

    private static String appendDroppedPictureHint(String text, int pictureCount) {
        String hint = String.format("（该消息共 %d 张图片，当前仅处理第一张）", pictureCount);
        if (text == null || text.trim().isEmpty()) {
            return hint;
        }
        return text + "\n" + hint;
    }

    /** 图片用 {@code <msgId>.png}，其余回落 msgId。 */
    private static String defaultFileName(String messageType, String fileName, String msgId) {
        if (fileName != null && !fileName.isEmpty()) {
            return fileName;
        }
        if (ImTypes.MESSAGE_TYPE_IMAGE.equals(messageType)) {
            return msgId + ".png";
        }
        return msgId;
    }
}
