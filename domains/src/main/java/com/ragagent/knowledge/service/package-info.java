/**
 * 知识库用例层（顶层残部）。按能力的职责地图（队列/客户端/存储/守卫已各自分包）：
 * <ul>
 * <li><b>访问原语</b>：{@code KnowledgeAccessHelper}（租户内读行 + API Key 作用域校验，被门面与
 * 若干子服务共用；搬入 {@code service.management} 会与"门面 → 摘要服务"
 * 组成 {@code enrichment ⇄ management} 环，故暂留本层）；</li>
 * <li><b>支撑</b>：{@code HousekeepingService}（定时清理）+ {@code EmbeddingModelAdapter}
 * （嵌入模型适配）——目标树**本就留在本层**；{@code DuplicateKnowledgeException}
 * 为本层业务异常（放 {@code domain} 会新造环）。</li>
 * </ul>
 *
 * <p><b>子面</b>：{@code management/}（门面与知识库、文件夹、文件、解析生命周期）、
 * {@code query/}（检索与 span）、{@code faq/}（导入编排 / 条目读写 / 索引形态）、
 * {@code chunk/}（分块编辑 / 抽取 / 读取）、{@code enrichment/}（知识摘要与问题生成）。</p>
 */
package com.ragagent.knowledge.service;
