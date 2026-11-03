package com.ragagent.session.qa;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ragagent.contract.tenant.TenantConfigLookup;
import com.ragagent.platform.stream.StreamManager;
import com.ragagent.session.conversation.MessageService;
import com.ragagent.session.domain.conversation.Session;
import com.ragagent.session.domain.message.Message;
import com.ragagent.session.service.QaSupport.QaRequestContext;
import com.ragagent.session.service.QaSupport.SseStreamContext;
import com.ragagent.session.service.SessionAgentQaService;
import com.ragagent.session.service.SessionKnowledgeQaService;
import com.ragagent.session.service.SteerRunCoordinator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * NORMAL 早段失败的收尾不变量：assistant 消息必须被提交为完成态。
 *
 * <p>回归背景：{@code finally} 一度只在 AGENT 模式收尾——NORMAL 的完成位完全依赖事件管线
 * 发出 done，而管线失败时它不会发，于是该行消息永久 {@code completed=false}。
 * 这条测试钉住"失败也要落完成位"。</p>
 */
class QaTurnExecutorNormalFailureTest {

    @Test
    @DisplayName("NORMAL 失败收尾把 assistant 消息交给 finalizer 提交完成")
    void normalFailureDelegatesCompletionToFinalizer() {
        QaTurnFinalizer finalizer = mock(QaTurnFinalizer.class);
        // runWithTenant 直接在当前线程执行，避免测试里起虚拟线程
        doAnswer(inv -> {
            Runnable body = inv.getArgument(1);
            body.run();
            return null;
        }).when(finalizer).runWithTenant(any(), any());

        @SuppressWarnings("unchecked")
        ObjectProvider<TenantConfigLookup> tenantProvider = mock(ObjectProvider.class);
        when(tenantProvider.getIfAvailable()).thenReturn(null);

        QaTurnExecutor executor = new QaTurnExecutor(
                mock(MessageService.class),
                mock(StreamManager.class),
                mock(SessionKnowledgeQaService.class),
                mock(SessionAgentQaService.class),
                mock(SteerRunCoordinator.class),
                mock(QaSseOrchestrator.class),
                finalizer,
                mock(QaAttachmentResolver.class),
                tenantProvider);

        Session session = new Session();
        session.setTenantId(10002L);
        QaRequestContext reqCtx = new QaRequestContext();
        reqCtx.sessionId = "s-1";
        reqCtx.query = "问题";
        reqCtx.userMessageId = "um-1";
        reqCtx.session = session;

        Message assistant = new Message();
        assistant.setId("am-1");
        assistant.setSessionId("s-1");
        SseStreamContext streamCtx = new SseStreamContext();
        streamCtx.assistantMessage = assistant;

        executor.finalizeNormalOnTerminal(reqCtx, streamCtx);

        verify(finalizer).completeAssistantMessage(
                same(assistant), eq("问题"), eq("um-1"), eq(10002L));
    }
}
