package com.ragagent.platform.web.error;

import com.ragagent.contract.error.AppError;
import com.ragagent.contract.error.BizException;
import com.ragagent.contract.error.ErrorCode;
import com.ragagent.platform.web.ApiResponse;
import com.ragagent.platform.web.ApiResultSupport;
import com.ragagent.platform.web.PageParams;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 统一错误形态：{@code {"code": N, "message": "...", "data": details}}。
 * 未标注 {@code @ApiResult} 的存量控制器由 {@code ApiResultSupport} 分派为
 * {@code {"error": {code, message, details}}}——清单只许减（见 check-api-envelope）。
 *
 * <p>分派依据是 {@link ApiResultSupport#isActive} 读的请求属性（由 {@code ApiResultInterceptor}
 * 在门禁之前打标）。有一种形态**无论哪种控制器都保持原样**：未映射路径的 404
 * {@code text/plain "404 page not found"} —— 它没有 Handler，打不上标。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Object> handleBiz(BizException ex, HttpServletRequest req) {
        return respond(ex.appError(), req);
    }

    /**
     * Spring 6.1 对未映射路径抛 NoResourceFoundException（落到 handleOther 会变 500）。
     * 对照 gin 默认 404："404 page not found"（text/plain）。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<String> handleNoResource(NoResourceFoundException ex) {
        return ResponseEntity.status(404)
                .contentType(MediaType.TEXT_PLAIN)
                .body("404 page not found");
    }


    // ── 参数校验异常 → 400 信封（details 为字段级中文文案，多条 "\n" 连接）──

    /** 请求体约束校验失败（@Valid DTO）与 @ModelAttribute 绑定失败。 */
    @ExceptionHandler(org.springframework.validation.BindException.class)
    public ResponseEntity<Object> handleBind(org.springframework.validation.BindException ex,
            HttpServletRequest req) {
        boolean pagination = ex.getTarget() instanceof PageParams
                || ex.getBindingResult().getFieldErrors().stream()
                        .allMatch(fe -> "page".equals(fe.getField()) || "pageSize".equals(fe.getField())
                                || "page_size".equals(fe.getField()));
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (org.springframework.validation.FieldError fe : ex.getBindingResult().getFieldErrors()) {
            String msg = translateConstraint(fe);
            // 校验注解已给出 "field: 说明" 完整文案时直接使用（字段名取线格式键名）
            lines.add(msg.matches("^[a-z][A-Za-z0-9_]*: .*") ? msg : fe.getField() + ": " + msg);
        }
        if (lines.isEmpty()) {
            lines.add("请求参数不合法");
        }
        java.util.Collections.sort(lines); // 校验器不保证字段顺序，多行 details 取字典序（确定性）
        AppError e = new AppError(ErrorCode.BAD_REQUEST.value(),
                pagination ? "分页参数不合法" : "请求参数不合法",
                String.join("\n", lines), 400);
        return respond(e, req);
    }

    /** 方法级参数校验失败（Spring 6.1 内建方法校验，如 @ModelAttribute record 上的约束）。 */
    @ExceptionHandler(org.springframework.web.method.annotation.HandlerMethodValidationException.class)
    public ResponseEntity<Object> handleMethodValidation(
            org.springframework.web.method.annotation.HandlerMethodValidationException ex,
            HttpServletRequest req) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        ex.getAllValidationResults().forEach(r ->
                r.getResolvableErrors().forEach(err -> {
                    String msg = translateMessage(err.getDefaultMessage());
                    lines.add(msg.matches("^[a-z][A-Za-z0-9_]*: .*") ? msg : extractField(err) + ": " + msg);
                }));
        java.util.Collections.sort(lines); // 与 handleBind 同款：多行 details 取字典序（确定性）
        if (lines.isEmpty()) {
            lines.add("请求参数不合法");
        }
        AppError e = new AppError(ErrorCode.BAD_REQUEST.value(), "请求参数不合法",
                String.join("\n", lines), 400);
        return respond(e, req);
    }

    /** 单个 query 变量类型不匹配（如 page=abc）。 */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Object> handleTypeMismatch(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex,
            HttpServletRequest req) {
        String name = ex.getName();
        boolean pagination = "page".equals(name) || "page_size".equals(name);
        AppError e = new AppError(ErrorCode.BAD_REQUEST.value(),
                pagination ? "分页参数不合法" : "请求参数不合法",
                name + ": 类型不正确", 400);
        return respond(e, req);
    }

    /** 请求体不可读：空体 / 畸形 JSON / 字段类型错。 */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Object> handleNotReadable(
            org.springframework.http.converter.HttpMessageNotReadableException ex,
            HttpServletRequest req) {
        String details = "请求体格式不正确";
        Throwable cause = ex.getCause();
        if (ex.getMessage() != null && ex.getMessage().contains("Required request body")) {
            details = "请求体不能为空";
        } else if (cause instanceof com.fasterxml.jackson.databind.exc.MismatchedInputException mie
                && !mie.getPath().isEmpty()) {
            String field = mie.getPath().get(mie.getPath().size() - 1).getFieldName();
            if (field != null) {
                details = field + ": 类型不正确";
            }
        }
        AppError e = new AppError(ErrorCode.BAD_REQUEST.value(), "请求参数不合法", details, 400);
        return respond(e, req);
    }

    /** 约束违规文案的中文归一：自定义消息原样，框架默认英文文案统一为固定中文措辞。 */
    private String translateConstraint(org.springframework.validation.FieldError fe) {
        String msg = fe.getDefaultMessage();
        if (msg == null) {
            return "不合法";
        }
        if (msg.contains("type mismatch") || msg.contains("Failed to convert")
                || msg.startsWith("Failed to convert")) {
            return "类型不正确";
        }
        return translateMessage(msg);
    }

    private String translateMessage(String msg) {
        if (msg == null) {
            return "不合法";
        }
        if (msg.contains("characters") && !msg.startsWith("size must be")) {
            return msg; // 自定义消息（如长度说明）原样保留
        }
        if (msg.equals("must not be blank") || msg.equals("must not be null") || msg.equals("must not be empty")) {
            return "不能为空";
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("must be greater than or equal to (\\d+)").matcher(msg);
        if (m.find()) {
            return "必须不小于 " + m.group(1);
        }
        m = java.util.regex.Pattern.compile("must be less than or equal to (\\d+)").matcher(msg);
        if (m.find()) {
            return "必须不大于 " + m.group(1);
        }
        m = java.util.regex.Pattern.compile("size must be between (\\d+) and (\\d+)").matcher(msg);
        if (m.find()) {
            return "长度必须在 " + m.group(1) + "-" + m.group(2) + " 之间";
        }
        if (msg.startsWith("must match")) {
            return "格式不正确";
        }
        return msg;
    }

    private String extractField(org.springframework.context.MessageSourceResolvable err) {
        Object[] args = err.getArguments();
        if (args != null) {
            for (Object a : args) {
                if (a instanceof jakarta.validation.ConstraintViolation<?> cv
                        && cv.getPropertyPath() != null) {
                    String s = cv.getPropertyPath().toString();
                    int idx = s.lastIndexOf('.');
                    return idx < 0 ? s : s.substring(idx + 1);
                }
            }
        }
        String[] codes = err.getCodes();
        return codes != null && codes.length > 0 ? codes[codes.length - 1] : "参数";
    }

    /**
     * 方法不允许（如 GET 打到仅 POST 的路由）。
     *
     * <p>不落 {@link #handleOther} 的 catch-all——那会回 <b>500</b> 并打 ERROR 日志，而语义上
     * 这是客户端错误 ⇒ 405 + 通用段码 {@code METHOD_NOT_ALLOWED}，
     * 信封分派同样走 {@link #respond}。</p>
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Object> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest req) {
        return respond(new AppError(ErrorCode.METHOD_NOT_ALLOWED.value(), "Method Not Allowed", null, 405), req);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleOther(Exception ex, HttpServletRequest req) {
        log.error("unhandled exception", ex);
        AppError e = new AppError(ErrorCode.INTERNAL_SERVER.value(), "Internal server error", null, 500);
        return respond(e, req);
    }

    /**
     * 错误体形态分派：{@code @ApiResult} 路由 → 统一外壳
     * {@code {code, message, data}}（data 承载 details）；其余路由 → 存量信封形态。
     */
    private ResponseEntity<Object> respond(AppError e, HttpServletRequest req) {
        Object body = ApiResultSupport.isActive(req)
                ? ApiResponse.fail(e.code(), e.message(), e.details())
                : errorBody(e);
        return ResponseEntity.status(e.httpCode()).body(body);
    }


    /**
     * 错误体 = {@code {"error":{"code","message","details"}}}（键序按契约标准声明序；
     * details 可为 null——可空字段显式输出）。
     */
    private Map<String, Object> errorBody(AppError e) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", e.code());
        error.put("message", e.message());
        error.put("details", e.details());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        return body;
    }
}
