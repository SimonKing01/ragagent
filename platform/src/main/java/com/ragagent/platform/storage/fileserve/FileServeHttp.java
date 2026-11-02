package com.ragagent.platform.storage.fileserve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.platform.web.JsonMappers;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文件服务路由共用的响应小件（L1）：参数校验、错误信封、空体状态、NoRoute 形态。
 *
 * <p><b>归属</b>：这些方法原为存储域 {@code FileProxyService} / {@code FileProxyController} 的
 * 静态辅助，被知识库与消息/embed 三条文件路由共用（跨域复用底层件）⇒ 上移 L1，
 * 原静态方法保留为**同名委托**以免逐一改调用方。</p>
 *
 * <p><b>语义逐字不变</b>：错误信封 {@code {"error": msg}}、空体 4xx（
 * {@code setContentLength(0)}+flush 规避容器默认错误体）、gin NoRoute 形
 * {@code 404 page not found}（{@code text/plain}、字节写出）。</p>
 */
public final class FileServeHttp {

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    private FileServeHttp() {
    }

    /**
     * gin NoRoute 形态（供 GET-only 路由的 HEAD 处理复用）。
     */
    public static void writeGinNoRoute(HttpServletResponse response) throws IOException {
        response.setStatus(404);
        response.setContentLength("404 page not found".length());
        response.setContentType("text/plain");
        response.getOutputStream().write("404 page not found".getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * GET-only 路由对 HEAD 的响应（404 形态见 {@link #writeGinNoRoute}）。
     * 返回 true 表示已写响应。只设 Content-Type: text/plain（无 charset），
     * body 走字节流避免容器给 writer 附加编码后缀。
     */
    public static boolean notFoundForHead(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (!"HEAD".equals(request.getMethod())) {
            return false;
        }
        response.setContentLength("404 page not found".length());
        response.setContentType("text/plain");
        response.getOutputStream().write("404 page not found".getBytes(StandardCharsets.US_ASCII));
        return true;
    }

    /**
     * 只写状态码（**空体**，无 Content-Type）。
     * ⚠️ 只 setStatus 不够：Tomcat 的 ErrorReportValve 会在响应未提交且状态 ≥400 时
     * 补默认错误体（"404 Not Found" 字样，Spring Boot 的 showReport=false 形态）。
     * setContentLength(0)+flush 提交空响应后阀门跳过，保证响应体为空。
     */
    public static void plainStatus(HttpServletResponse response, int status) {
        response.setStatus(status);
        response.setContentLength(0);
        try {
            response.flushBuffer();
        } catch (IOException ignored) {
            // 连接已断，写不出去也无处可达
        }
    }

    /** 错误信封：{@code {"error": msg}}。 */
    public static void writeErrorJson(HttpServletResponse response, int status, String message)
            throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        writeJson(response, status, body);
    }

    /**
     * 响应写出：Content-Type {@code application/json; charset=utf-8}（带空格，
     * setHeader 原样写——同既有的容器经验），JSON 键按字母序
     * （调用方保证）。体走 UTF-8 字节（getWriter 会受容器默认编码影响）。
     */
    public static void writeJson(HttpServletResponse response, int status, Object body)
            throws IOException {
        response.setStatus(status);
        response.setHeader("Content-Type", "application/json; charset=utf-8");
        response.getOutputStream()
                .write(MAPPER.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * file_path 必填（trim 后）、禁 ".."。
     * 失败时 400 已写、返回 null。
     */
    public static String requireFilePathQuery(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String filePath = trimToEmpty(request.getParameter("file_path"));
        if (filePath.isEmpty()) {
            writeErrorJson(response, HttpServletResponse.SC_BAD_REQUEST,
                    "missing required parameter: file_path");
            return null;
        }
        if (filePath.contains("..")) {
            writeErrorJson(response, HttpServletResponse.SC_BAD_REQUEST, "invalid file path");
            return null;
        }
        return filePath;
    }

    private static String trimToEmpty(String s) {
        return s == null ? "" : s.trim();
    }
}
