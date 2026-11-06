/**
 * session 域控制器的 HTTP 端点（协议适配层）。
 *
 * <p>QA 轮的解析、SSE 编排、整轮执行与收尾在
 * {@code com.ragagent.session.qa}（{@code QaRequestParser} / {@code QaSseOrchestrator} /
 * {@code QaTurnExecutor} / {@code QaTurnFinalizer} + 协作件 {@code QaRequestBinder} /
 * {@code QaAttachmentResolver}）；本包只留路由与请求/响应壳
 * （{@code *Controller} / {@code *Request} / {@code *View}…）。
 * 编排协作件在 session.qa/support，controller 不持有静态编排助手。</p>
 */
package com.ragagent.session.controller;
