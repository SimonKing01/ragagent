package com.ragagent.session.controller;

import com.ragagent.contract.error.AppError;
import com.ragagent.contract.error.BizException;
import com.ragagent.contract.knowledge.rule.ParserEngineRules;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.platform.security.LogSanitizer;
import com.ragagent.platform.text.ContentTypeByFilename;
import com.ragagent.platform.web.ApiResponse;
import com.ragagent.platform.web.ApiResult;
import com.ragagent.platform.web.JsonMappers;
import com.ragagent.session.attachment.TemporaryDocumentService;
import com.ragagent.session.conversation.SessionService;
import com.ragagent.session.dto.TemporaryDocumentResponse;
import com.ragagent.session.service.AgentResolver;
import com.ragagent.session.support.SessionNotFoundException;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

/**
 * 会话附件（临时文档）HTTP 层。
 *
 * 响应形态（裸对象，无 {"data","success"} 信封）：上传 202 裸 doc
 * （status=uploaded，解析异步）；列表/详情 200 裸对象/裸数组（详情查不到
 * 404 "Attachment not found"）；预览是文件字节流（filetransport 语义）；
 * 删除 200 + {@code ApiResponse} 外壳（幂等）。表单字段名＝Java 参数名（camelCase）。
 *
 * owner 范围：上传/删除改会话内容 → 严格 owner 范围；
 * 列表/详情/预览是读 → 读可见性。
 */
@RestController
@ApiResult
@Tag(name = "Session")
public class TemporaryDocumentController {

    private static final Logger log = LoggerFactory.getLogger(TemporaryDocumentController.class);

    private final SessionService sessionService;
    private final TemporaryDocumentService temporaryDocuments;
    /** agent 解析（共享优先、source==0 才回落 own）。 */
    private final AgentResolver agentResolver;

    public TemporaryDocumentController(SessionService sessionService,
                                       TemporaryDocumentService temporaryDocuments,
                                       AgentResolver agentResolver) {
        this.sessionService = sessionService;
        this.temporaryDocuments = temporaryDocuments;
        this.agentResolver = agentResolver;
    }

    /** 上传附件（202 受理，解析异步）。 */
    @PostMapping("/api/v1/sessions/{sessionId}/attachments")
    public ResponseEntity<TemporaryDocumentResponse> upload(
            @PathVariable("sessionId") String sessionId,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "agentId", required = false) String agentId,
            @RequestParam(value = "parserEngine", required = false) String parserEngine,
            jakarta.servlet.http.HttpServletRequest request) {
        String sid = LogSanitizer.sanitize(sessionId);
        // 请求不是 multipart 时固定原文拒收
        String contentType = request.getContentType() == null ? "" : request.getContentType();
        if (!contentType.toLowerCase(java.util.Locale.ROOT).startsWith("multipart/form-data")) {
            throw new BizException(AppError.badRequest(
                    "invalid attachment upload: request Content-Type isn't multipart/form-data"));
        }
        try {
            sessionService.getOwnedSession(sid);
        } catch (SessionNotFoundException e) {
            throw BizException.notFound("Session not found");
        } catch (RuntimeException e) {
            throw toInternal(e);
        }
        if (file == null) {
            throw new BizException(AppError.badRequest(
                    "invalid attachment upload: http: no such file"));
        }
        // 空 size 不在此拒——0 字节文件由 service 的
        // "file size must be between 1 byte and 50MB" 兜底
        // agent_source_tenant_id / 共享 agent 分支随空间分享裁撤：只解析自有 agent
        var resolved = agentResolver.resolve(agentId, 0);
        var agent = resolved.agent();
        String ext = extNoDot(file.getOriginalFilename());
        TemporaryDocumentService.CreateOptions options = TemporaryDocumentService.CreateOptions
                .empty().withParserEngine(parserEngine == null ? "" : parserEngine.strip());
        if (agent != null) {
            var cfg = AgentResolver.parseAgentConfig(agent);
            List<String> supported = stringListOf(cfg.get("supportedFileTypes"));
            if (!supported.isEmpty() && !containsFileType(supported, ext)) {
                throw new BizException(AppError.badRequest("file type is not supported by this agent"));
            }
            if (isAudioExtension(ext)) {
                if (!cfg.path("audioUploadEnabled").asBoolean(false)
                        || cfg.path("asrModelId").asText("").isEmpty()) {
                    throw new BizException(AppError.badRequest(
                            "audio upload is not enabled or no ASR model is configured"));
                }
                options = options.withAsrModelId(cfg.path("asrModelId").asText(""));
            }
            if (options.parserEngine().isEmpty() || "auto".equals(options.parserEngine())) {
                String engine = ParserEngineRules.resolve(
                        JsonMappers.toJavaValue(cfg.get("chatParserEngineRules")), ext);
                if (!engine.isEmpty()) {
                    options = options.withParserEngine(engine);
                }
            }
            if (cfg.path("imageUploadEnabled").asBoolean(false)
                    && !cfg.path("vlmModelId").asText("").isEmpty()) {
                options = options.withVlm(cfg.path("vlmModelId").asText(""),
                        cfg.path("attachmentImageUnderstanding").asBoolean(false),
                        cfg.path("attachmentOcrMaxPages").asInt(0));
            }
        }
        byte[] data;
        try {
            data = file.getBytes();
        } catch (Exception e) {
            throw new BizException(AppError.badRequest("failed to open attachment"));
        }
        try {
            var document = temporaryDocuments.create(currentTenantId(), sid,
                    file.getOriginalFilename(), file.getContentType(), file.getSize(), data,
                    options);
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(TemporaryDocumentResponse.from(document));
        } catch (IllegalArgumentException e) {
            throw new BizException(AppError.badRequest(e.getMessage()));
        } catch (RuntimeException e) {
            throw toInternal(e);
        }
    }

    /** 超过 multipart 上限：错误文案固定为 "http: request body too large"。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Object> bodyTooLarge() {
        throw new BizException(AppError.badRequest(
                "invalid attachment upload: http: request body too large"));
    }

    @GetMapping({"/api/v1/sessions/{id}/attachments", "/api/v1/sessions/{sessionId}/attachments"})
    public ResponseEntity<List<TemporaryDocumentResponse>> list(
            @PathVariable(value = "id", required = false) String id,
            @PathVariable(value = "sessionId", required = false) String sessionIdFallback) {
        String sid = sessionParam(id, sessionIdFallback);
        try {
            sessionService.getSession(sid);
        } catch (SessionNotFoundException e) {
            throw BizException.notFound("Session not found");
        } catch (RuntimeException e) {
            throw toInternal(e);
        }
        return ResponseEntity.ok(temporaryDocuments.list(currentTenantId(), sid).stream()
                .map(TemporaryDocumentResponse::from).toList());
    }

    @GetMapping({"/api/v1/sessions/{id}/attachments/{attachmentId}",
            "/api/v1/sessions/{sessionId}/attachments/{attachmentId}"})
    public ResponseEntity<TemporaryDocumentResponse> get(
            @PathVariable(value = "id", required = false) String id,
            @PathVariable(value = "sessionId", required = false) String sessionIdFallback,
            @PathVariable("attachmentId") String attachmentId) {
        String sid = sessionParam(id, sessionIdFallback);
        try {
            sessionService.getSession(sid);
        } catch (SessionNotFoundException e) {
            throw BizException.notFound("Session not found");
        } catch (RuntimeException e) {
            throw toInternal(e);
        }
        var document = temporaryDocuments.get(currentTenantId(), sid, attachmentId);
        if (document == null) {
            throw BizException.notFound("Attachment not found");
        }
        return ResponseEntity.ok(TemporaryDocumentResponse.from(document));
    }

    /**
     * 错误分支文案固定；成功路径的响应头按 filetransport 语义拼装。
     */
    @GetMapping({"/api/v1/sessions/{id}/attachments/{attachmentId}/preview",
            "/api/v1/sessions/{sessionId}/attachments/{attachmentId}/preview"})
    public void preview(
            @PathVariable(value = "id", required = false) String id,
            @PathVariable(value = "sessionId", required = false) String sessionIdFallback,
            @PathVariable("attachmentId") String attachmentId,
            jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        String sid = sessionParam(id, sessionIdFallback);
        try {
            sessionService.getSession(sid);
        } catch (SessionNotFoundException e) {
            throw BizException.notFound("Session not found");
        } catch (RuntimeException e) {
            throw toInternal(e);
        }
        String attachment = LogSanitizer.sanitize(attachmentId);
        if (attachment.isEmpty()) {
            throw new BizException(AppError.badRequest("Attachment ID cannot be empty"));
        }
        TemporaryDocumentService.OpenedFile opened;
        try {
            opened = temporaryDocuments.openFile(currentTenantId(), sid, attachment);
        } catch (TemporaryDocumentService.AttachmentNotFoundException e) {
            throw BizException.notFound("Attachment not found");
        } catch (RuntimeException e) {
            String message = String.valueOf(e.getMessage()).toLowerCase();
            if (message.contains("not found")) {
                throw BizException.notFound("Attachment not found");
            }
            log.error("Failed to retrieve attachment", e);
            throw BizException.internal("Failed to retrieve attachment");
        }
        // filetransport.Serve 语义：头用 servlet setHeader 原样写——
        // Spring/Tomcat 的 Content-Type 处理会规范化 "charset=" 前的空格（golden 实测差异）
        String fileName = opened.fileName();
        ContentTypeByFilename.Record safe =
                ContentTypeByFilename.safe(fileName);
        response.setStatus(jakarta.servlet.http.HttpServletResponse.SC_OK);
        response.setHeader("Content-Type", safe.contentType());
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Content-Disposition", disposition(fileName, safe.inline()));
        response.setHeader("Cache-Control", "private, no-store");
        response.setHeader("Accept-Ranges", "bytes");
        response.setContentLength(opened.data().length);
        response.getOutputStream().write(opened.data());
    }

    /** 删除附件：200 + {@code ApiResponse} 外壳，幂等。 */
    @DeleteMapping({"/api/v1/sessions/{id}/attachments/{attachmentId}",
            "/api/v1/sessions/{sessionId}/attachments/{attachmentId}"})
    public ApiResponse<Void> delete(
            @PathVariable(value = "id", required = false) String id,
            @PathVariable(value = "sessionId", required = false) String sessionIdFallback,
            @PathVariable("attachmentId") String attachmentId) {
        String sid = sessionParam(id, sessionIdFallback);
        try {
            sessionService.getOwnedSession(sid);
        } catch (SessionNotFoundException e) {
            throw BizException.notFound("Session not found");
        } catch (RuntimeException e) {
            throw toInternal(e);
        }
        temporaryDocuments.delete(currentTenantId(), sid, attachmentId);
        return ApiResponse.ok();   // 204 退役（空体与「外壳恒存在」冲突）
    }

    // ── 辅助 ──────────────────────────────

    /**
     * agent 门控与 options 组装：
     * supported_file_types 拒收 / 音频需 ASR / agent 级 parser engine 回落（显式 engine
     * 为空或 auto 时）/ VLM 图片理解选项。租户级 parser 规则由 parse worker 兜底。
     */
    /** 取小写扩展名（不带点）。 */
    private static String extNoDot(String fileName) {
        String name = fileName == null ? "" : fileName;
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    /** 音频扩展名（含 aac；与解析管线的 audioFormats 不是同一张表）。 */
    private static boolean isAudioExtension(String ext) {
        return switch (ext) {
            case "mp3", "wav", "m4a", "flac", "ogg", "aac" -> true;
            default -> false;
        };
    }

    /** 逐项去点小写后与 ext 比较。 */
    private static boolean containsFileType(List<String> supported, String ext) {
        for (String item : supported) {
            String normalized = item == null ? "" : item.trim().toLowerCase(java.util.Locale.ROOT);
            if (normalized.startsWith(".")) {
                normalized = normalized.substring(1);
            }
            if (normalized.equals(ext)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> stringListOf(com.fasterxml.jackson.databind.JsonNode arr) {
        List<String> out = new java.util.ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode n : arr) {
                if (n.isTextual()) {
                    out.add(n.asText());
                }
            }
        }
        return out;
    }

    private static String sessionParam(String id, String sessionIdFallback) {
        String value = id == null || id.isEmpty() ? sessionIdFallback : id;
        return LogSanitizer.sanitize(value == null ? "" : value);
    }

    private static long currentTenantId() {
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("types.TenantIDContextKey not set in context");
        }
        return tenantId;
    }

    private static BizException toInternal(RuntimeException e) {
        if (e instanceof BizException biz) {
            return biz;
        }
        log.warn("unhandled exception mapped to internal error", e);
        return BizException.internal("internal error");
    }

    /** Content-Disposition：ASCII 安全字符集外的名字加引号。 */
    private static String disposition(String fileName, boolean inline) {
        String base = fileName == null ? "" : fileName;
        String type = inline ? "inline" : "attachment";
        boolean simple = !base.isEmpty() && base.chars().allMatch(c ->
                c >= 0x21 && c <= 0x7e && c != '"' && c != '\\' && c != '(' && c != ')'
                        && c != '<' && c != '>' && c != '@' && c != ',' && c != ';'
                        && c != ':' && c != '/' && c != '[' && c != ']' && c != '?'
                        && c != '=' && c != '{' && c != '}');
        return simple ? type + "; filename=" + base : type + "; filename=\"" + base + "\"";
    }
}
