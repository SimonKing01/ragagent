package com.ragagent.knowledge.domain;


/**
 * IndexingStrategy。
 * 注意 Scan 语义：DB NULL → {@link #defaultStrategy()}（vector+keyword=true）。
 */
public class KnowledgeBaseIndexingStrategy {

    private boolean vectorEnabled;
    private boolean keywordEnabled;
    private boolean wikiEnabled;
    private boolean graphEnabled;

    public static KnowledgeBaseIndexingStrategy defaultStrategy() {
        KnowledgeBaseIndexingStrategy s = new KnowledgeBaseIndexingStrategy();
        s.vectorEnabled = true;
        s.keywordEnabled = true;
        return s;
    }

    public boolean isVectorEnabled() { return vectorEnabled; }
    public void setVectorEnabled(boolean v) { vectorEnabled = v; }
    public boolean isKeywordEnabled() { return keywordEnabled; }
    public void setKeywordEnabled(boolean v) { keywordEnabled = v; }
    public boolean isWikiEnabled() { return wikiEnabled; }
    public void setWikiEnabled(boolean v) { wikiEnabled = v; }
    public boolean isGraphEnabled() { return graphEnabled; }
    public void setGraphEnabled(boolean v) { graphEnabled = v; }


    public boolean hasAnyIndexing() {
        return vectorEnabled || keywordEnabled || wikiEnabled || graphEnabled;
    }

    /** 全 false（新建请求体未传 strategy 时的判定依据）。**非 getter 命名** ⇒ 不参与序列化
     * （等价于原 {@code @JsonIgnore isZero()}；Jackson 默认只识别 get/is 命名的访问器）。 */
    public boolean allIndexingDisabled() {
        return !vectorEnabled && !keywordEnabled && !wikiEnabled && !graphEnabled;
    }
}
