/**
 * QA 轮的编排簇。
 *
 * <p>{@code QaRequestParser}（请求解析）/ {@code QaSseOrchestrator}（SSE 编排）/
 * {@code QaTurnExecutor}（整轮执行）/ {@code QaTurnFinalizer}（收尾落库），以及协作件
 * {@code QaRequestBinder}（请求体绑定）/ {@code QaAttachmentResolver}（附件解析）；
 * 共享小工具在 {@code QaTurnSupport}（含 {@code ParsedRequest} / {@code Base64Support}）。
 * 控制器只做协议适配，不再持有编排逻辑。</p>
 */
package com.ragagent.session.qa;
