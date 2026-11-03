package com.ragagent.session.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ragagent.contract.session.SessionLastRequestState;
import com.ragagent.session.service.QaSupport.QaMode;
import com.ragagent.session.service.QaSupport.QaRequestContext;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 输入条状态快照：异步写库不得再读 {@link QaRequestContext} 的可变字段。 */
class QaTurnFinalizerSnapshotTest {

    private final QaTurnFinalizer finalizer =
            new QaTurnFinalizer(null, null, null, null, (com.ragagent.memory.api.MemoryAccess) null);

    @Test
    void snapshotKeepsValuesWhenContextKeepsMutating() {
        QaRequestContext ctx = new QaRequestContext();
        ctx.sessionId = "s1";
        ctx.reqAgentId = "a1";
        ctx.reqAgentEnabled = true;
        ctx.summaryModelId = "m1";
        ctx.knowledgeBaseIds = new ArrayList<>(List.of("kb1"));
        ctx.knowledgeIds = new ArrayList<>(List.of("k1"));
        ctx.tagIds = new ArrayList<>(List.of("t1"));
        ctx.mcpServiceIds = new ArrayList<>(List.of("mcp1"));
        ctx.skillNames = new ArrayList<>(List.of("sk1"));
        ctx.webSearchEnabled = true;

        SessionLastRequestState snapshot = finalizer.lastRequestStateSnapshot(ctx, QaMode.NORMAL);

        ctx.reqAgentId = "a2";
        ctx.reqAgentEnabled = false;
        ctx.summaryModelId = "m2";
        ctx.knowledgeBaseIds.add("kb2");
        ctx.knowledgeIds.clear();
        ctx.webSearchEnabled = false;

        assertThat(snapshot.getAgentId()).isEqualTo("a1");
        assertThat(snapshot.isAgentEnabled()).isTrue();
        assertThat(snapshot.getModelId()).isEqualTo("m1");
        assertThat(snapshot.getKnowledgeBaseIds()).containsExactly("kb1");
        assertThat(snapshot.getKnowledgeIds()).containsExactly("k1");
        assertThat(snapshot.getTagIds()).containsExactly("t1");
        assertThat(snapshot.getMcpServiceIds()).containsExactly("mcp1");
        assertThat(snapshot.getSkillNames()).containsExactly("sk1");
        assertThat(snapshot.isWebSearchEnabled()).isTrue();
    }

    @Test
    void snapshotListsAreImmutableCopies() {
        QaRequestContext ctx = new QaRequestContext();
        ctx.knowledgeBaseIds = new ArrayList<>(List.of("kb1"));

        SessionLastRequestState snapshot = finalizer.lastRequestStateSnapshot(ctx, QaMode.NORMAL);

        assertThatThrownBy(() -> snapshot.getKnowledgeBaseIds().add("kb2"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullListsBecomeEmpty() {
        QaRequestContext ctx = new QaRequestContext();
        ctx.knowledgeBaseIds = null;
        ctx.mentionedItems = null;

        SessionLastRequestState snapshot = finalizer.lastRequestStateSnapshot(ctx, QaMode.NORMAL);

        assertThat(snapshot.getKnowledgeBaseIds()).isEmpty();
        assertThat(snapshot.getMentionedItems()).isEmpty();
    }
}
