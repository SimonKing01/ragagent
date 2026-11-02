package com.ragagent.rerank;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.contract.model.RankResult;
import com.ragagent.platform.web.ProviderJson;

/**
 * {@link RankResult} 的 JSON 编解码（契约面持纯形状，解析/序列化留实现侧）。
 *
 * <p>序列化形如 {@code {"index":N,"document":{"text":"..."},"relevance_score":X}}；
 * 解析宽容：{@code document} 可字符串可对象，分数 {@code relevance_score} 优先、
 * 缺失回落 {@code score}、都没有为 0。</p>
 */
public final class RankResultCodec {

    private RankResultCodec() {
    }

    /** 反序列化：relevance_score 优先，score 回落。 */
    public static RankResult parse(JsonNode node) {
        if (node == null) {
            return null;
        }
        RankResult r = new RankResult();
        r.setIndex(node.path("index").asInt());
        JsonNode doc = node.path("document");
        if (doc.isTextual()) {
            r.getDocument().setText(doc.asText());
        } else if (doc.isObject()) {
            r.getDocument().setText(doc.path("text").asText(""));
        } else if (doc.isMissingNode() || doc.isNull()) {
            // 字段缺失时 document 保持零值 ""
        }
        JsonNode rel = node.path("relevance_score");
        JsonNode score = node.path("score");
        if (rel.isNumber()) {
            r.setRelevanceScore(rel.asDouble());
        } else if (score.isNumber()) {
            r.setRelevanceScore(score.asDouble());
        }
        return r;
    }

    /** 序列化（index/document/relevance_score 声明序，恒输出）。 */
    public static String marshal(RankResult result) {
        var node = ProviderJson.object();
        node.put("index", result.getIndex());
        node.putObject("document").put("text", result.getDocument().getText());
        node.put("relevance_score", result.getRelevanceScore());
        return new String(ProviderJson.marshal(node), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 文档信息序列化（恒输出 {@code {"text":"..."}}）。 */
    public static String marshalDocument(RankResult.DocumentInfo doc) {
        var node = ProviderJson.object();
        node.put("text", doc.getText());
        return new String(ProviderJson.marshal(node), java.nio.charset.StandardCharsets.UTF_8);
    }
}
