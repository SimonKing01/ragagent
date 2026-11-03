package com.ragagent.contract.model;

import java.util.List;

/**
 * 文档重排客户端接口。
 */
public interface Reranker {

    /** 按与 query 的相关性重排 documents。 */
    List<RankResult> rerank(String query, List<String> documents);

    String getModelName();

    String getModelId();
}
