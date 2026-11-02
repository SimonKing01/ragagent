package com.ragagent.session.qa;

import com.ragagent.contract.error.BizException;
import com.ragagent.platform.web.JsonBodyErrors;
import com.ragagent.platform.web.JsonMappers;
import com.ragagent.platform.web.RequestFields;
import com.ragagent.session.dto.QaRequests.AttachmentUpload;
import com.ragagent.session.dto.QaRequests.CreateKnowledgeQARequest;
import com.ragagent.session.dto.QaRequests.SearchKnowledgeRequest;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code KnowledgeQaController} 的**静态解析助手簇**：请求体绑定
 * （绑定错误文案逐字对齐）、绑定错误文案、附件上传的
 * 解码与校验、以及仅解析簇使用的列表助手。全部静态、参数化、零字段依赖。
 *
 * <p>共享项留控制器：{@code stringListOf}（附件解析也在用）、
 * {@code tenantServiceField}/{@code currentTenant}（{@code executeQA} 720 在用）。</p>
 */
public final class QaRequestBinder {

    private QaRequestBinder() {}

    static final com.fasterxml.jackson.databind.ObjectMapper BIND_JSON =
            JsonMappers.lenient();
    public static CreateKnowledgeQARequest bindQaRequest(String rawBody) {
        CreateKnowledgeQARequest r = parseOrBindError(rawBody, CreateKnowledgeQARequest.class);
        if (r.query == null || r.query.isEmpty()) {
            throw BizException.badRequest(bindingError("Query", "required"));
        }
        return r;
    }
    public static SearchKnowledgeRequest bindSearchRequest(String rawBody) {
        SearchKnowledgeRequest r = parseOrBindError(rawBody, SearchKnowledgeRequest.class);
        if (r.query == null || r.query.isEmpty()) {
            throw BizException.badRequest(bindingError("Query", "required"));
        }
        return r;
    }
    static <T> T parseOrBindError(String rawBody, Class<T> type) {
        // 空 body 用 Jackson 标准消息；其余错误留给下面的解析
        // try/catch（Jackson 原生消息）。
        if (rawBody == null || rawBody.isEmpty()) {
            throw BizException.badRequest(JsonBodyErrors.EMPTY_BODY);
        }
        try {
            return BIND_JSON.readValue(rawBody, type);
        } catch (Exception e) {
            // 字段级类型错误直接用 Jackson 原生消息。
            throw BizException.badRequest(e.getMessage());
        }
    }
    public static String bindingError(String field, String tag) {
        return RequestFields.message(field, tag);
    }
    public static List<String> appendAll(List<String> base, List<String> extra) {
        List<String> out = new ArrayList<>(base == null ? List.of() : base);
        out.addAll(extra == null ? List.of() : extra);
        return out;
    }
    /** 附件上传的解码与单文件/总量大小校验。 */
    public static void decodeAndValidateAttachmentUploads(List<AttachmentUpload> uploads,
            int maxCount, long maxFileBytes, long maxTotalBytes) {
        if (uploads.size() > maxCount) {
            throw new IllegalArgumentException(
                    "at most " + maxCount + " attachments are allowed per request");
        }
        long total = 0;
        int i = 1;
        for (AttachmentUpload upload : uploads) {
            byte[] data;
            try {
                data = Base64Support.decode(upload.data);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("attachment " + i + " decode failed: " + e.getMessage());
            }
            if (data.length > maxFileBytes) {
                throw new IllegalArgumentException(
                        "attachment " + i + " exceeds size limit of " + maxFileBytes + " bytes");
            }
            total += data.length;
            if (total > maxTotalBytes) {
                throw new IllegalArgumentException(
                        "attachments exceed total request limit of " + maxTotalBytes + " bytes");
            }
            i++;
        }
    }
}
