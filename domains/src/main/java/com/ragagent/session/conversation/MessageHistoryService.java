package com.ragagent.session.conversation;

import com.ragagent.contract.error.AppError;
import com.ragagent.contract.error.BizException;
import com.ragagent.contract.storage.support.FileUrlPort;
import com.ragagent.contract.storage.support.Mode;
import com.ragagent.contract.storage.support.PublicModeForbiddenException;
import com.ragagent.contract.storage.support.ResourceModeException;
import com.ragagent.contract.storage.support.StorageBackendResolver;
import com.ragagent.contract.tenant.TenantConfigLookup;
import com.ragagent.contract.tenant.TenantConfigLookup.TenantStorageView;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.platform.security.LogSanitizer;
import com.ragagent.platform.storage.support.FileResolverFactory;
import com.ragagent.platform.storage.support.ResourceUrlMode;
import com.ragagent.platform.storage.support.Rewriter;
import com.ragagent.session.domain.message.Message;
import com.ragagent.session.support.MessageReferenceRewriter;
import com.ragagent.session.support.SessionNotFoundException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 消息历史读取用例：limit/beforeTime 解析、资源 URL 模式解析与字段级引用重写。
 *
 * <p>HTTP 端点（{@code MessageController}）与 embed 委托面
 * （{@code session.api.EmbedSessionAdapter}）共用本类——两个面语义恒一致；
 * 原先 embed 面直接回调控制器，方向倒挂（api → controller），已改经本层。</p>
 *
 * <p>语义门槛（金片钉死，逐字保留）：{@code limit} 非整数**容错**回落 20（不是 400）；
 * {@code beforeTime} 解析失败 → 400 固定中文文案；{@code resourceUrls} 非法 →
 * public 拒绝 403、其他坏值 400；会话不可见 → 404 "session not found"。</p>
 */
@Service
public class MessageHistoryService {

    private static final Logger log = LoggerFactory.getLogger(MessageHistoryService.class);

    private final MessageService messageService;
    private final FileUrlPort fileService;
    private final StorageBackendResolver storageBackendResolver;
    private final FileResolverFactory fileResolverFactory;
    private final TenantConfigLookup tenantService;

    public MessageHistoryService(MessageService messageService,
            ObjectProvider<FileUrlPort> fileService,
            ObjectProvider<StorageBackendResolver> storageBackendResolver,
            TenantConfigLookup tenantService,
            FileResolverFactory fileResolverFactory) {
        this.messageService = messageService;
        // 两个端口按 ObjectProvider 取（A3-3 起 StorageBackendResolver 有生产实现；
        // FileUrlPort 的进程级实现仍属装配项），缺 bean 时按未装配分支降级。
        this.fileService = fileService.getIfAvailable();
        this.storageBackendResolver = storageBackendResolver.getIfAvailable();
        this.tenantService = tenantService;
        this.fileResolverFactory = fileResolverFactory;
    }

    /**
     * 加载消息历史（recent 或 beforeTime 游标两路），返回已完成引用重写的消息列表。
     */
    public List<Message> loadMessages(String sessionId, String limit, String beforeTime,
            String resourceUrls) {
        String sid = LogSanitizer.sanitize(sessionId);

        Rewriter rewriter = resolveResourceRewriter(resourceUrls);

        int limitInt = 20;
        if (limit != null && !limit.isEmpty()) {
            try {
                limitInt = Integer.parseInt(LogSanitizer.sanitize(limit));
            } catch (NumberFormatException e) {
                log.warn("Invalid limit value, using default value 20, input: {}", limit);
            }
        }

        List<Message> messages;
        if (beforeTime == null || beforeTime.isEmpty()) {
            try {
                messages = messageService.getRecentMessages(sid, limitInt);
            } catch (SessionNotFoundException e) {
                log.debug("Session not found, ID: {}", sid);
                throw BizException.notFound(e.getMessage());
            } catch (RuntimeException e) {
                throw toInternal(e);
            }
        } else {
            OffsetDateTime before = parseBeforeTime(LogSanitizer.sanitize(beforeTime));
            try {
                messages = messageService.getMessagesBeforeTime(sid, before, limitInt);
            } catch (SessionNotFoundException e) {
                log.debug("Session not found, ID: {}", sid);
                throw BizException.notFound(e.getMessage());
            } catch (RuntimeException e) {
                throw toInternal(e);
            }
        }

        // 消息字段级重写在会话侧（MessageReferenceRewriter）：URL 重写内核在 storage，编排在 session，
        // 依赖方向保持「会话 → 存储」单向（原先 message 重写放在 storage 会反向依赖 session）。
        return new MessageReferenceRewriter(rewriter).rewriteMessagesResponse(messages);
    }

    private Rewriter resolveResourceRewriter(String resourceUrls) {
        Mode mode;
        try {
            mode = ResourceUrlMode.resolve(resourceUrls);
        } catch (PublicModeForbiddenException e) {
            log.warn("Rejected resource URL mode", e);
            throw BizException.forbidden(e.getMessage());
        } catch (ResourceModeException e) {
            log.warn("Rejected resource URL mode", e);
            throw BizException.badRequest(e.getMessage());
        }
        return Rewriter.forRequest(mode,
                fileResolverFactory.create(currentTenant(), fileService, storageBackendResolver));
    }

    /**
     * 读者租户实体（A3-3 接线）——Rewriter 用它解析"引用不带 provider
     * scheme 时的租户默认 provider"。
     */
    private TenantStorageView currentTenant() {
        Long tid = TenantContext.currentTenantId();
        try {
            return tid == null || tid <= 0 ? null : tenantService.storageView(tid);
        } catch (RuntimeException e) {
            log.warn("Failed to resolve tenant storage view, falling back to default", e);
            return null;
        }
    }

    /**
     * RFC3339 / RFC3339Nano 都收（{@link OffsetDateTime#parse} 覆盖两者），
     * 失败 → 400 固定文案。
     */
    private static OffsetDateTime parseBeforeTime(String raw) {
        if (raw == null || raw.isBlank()) {
            throw beforeTimeError();
        }
        try {
            return OffsetDateTime.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw beforeTimeError();
        }
    }

    /** 游标时间格式错的固定文案（RFC3339 / RFC3339Nano 都收）。 */
    private static BizException beforeTimeError() {
        return new BizException(
                AppError.badRequest("beforeTime: 必须是 RFC3339 时间"));
    }

    /**
     * 已带形态的业务错误必须原样透传（二次包装会把 "error code: N, error message: "
     * 前缀叠两层，见 docs/known-issues/02-wave-0-1.md 的记录）。
     */
    private static BizException toInternal(RuntimeException e) {
        if (e instanceof BizException biz) {
            return biz;
        }
        log.warn("unhandled exception mapped to internal error", e);
        return BizException.internal("internal error");
    }
}
