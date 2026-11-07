package com.ragagent.im.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ragagent.im.runtime.ImFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ImInboundGuardOps} 的语义钉子：自 {@code ImTurnOrchestrator} 外提时逐字搬迁，
 * 这里把「单实例回落形态」的口径固化成断言——去重（同一 messageID 只处理一次）与
 * 限流（窗口内配额）在**未接 Redis** 时走进程内形态（该分支此前无直接覆盖）。
 *
 * <p>Redis 分支的 SETNX / 计数语义由 {@code ImRedisStoreTest} 覆盖（那里测的是
 * Redis 侧同名方法），本类只钉门面侧的编排口径。</p>
 */
class ImInboundGuardOpsTest {

    /** 单实例形态：{@code redisStore == null}。 */
    private static ImInboundGuardOps singleInstance(int windowSec, int max) {
        return new ImInboundGuardOps(null, windowSec, max);
    }

    @Test
    @DisplayName("去重（单实例）：同一 messageID 第二次即判重，不同 id 互不影响")
    void duplicateOnlyForSameMessageId() {
        ImInboundGuardOps ops = singleInstance(60, 10);

        assertFalse(ops.isDuplicate("m-1"), "首次出现不算重复");
        assertTrue(ops.isDuplicate("m-1"), "同一 messageID 第二次应判重");
        assertFalse(ops.isDuplicate("m-2"), "不同 messageID 不受前一条影响");
    }

    @Test
    @DisplayName("内容级兜底：同一人同内容 5 秒内只放行一次，换内容不受影响")
    void duplicateContentWithinShortWindow() {
        ImInboundGuardOps ops = singleInstance(60, 10);
        String key = "feishu|ch1|u1|c1|你好";

        assertFalse(ops.isDuplicateContent(key), "首次出现不算重复");
        assertTrue(ops.isDuplicateContent(key), "5 秒内同内容应判重");
        assertFalse(ops.isDuplicateContent("feishu|ch1|u1|c1|在吗"), "内容不同不拦");
    }

    @Test
    @DisplayName("限流（单实例）：窗口内配额用尽即拒，且与 key 绑定")
    void rateLimitWithinWindow() {
        ImInboundGuardOps ops = singleInstance(60, 2);

        assertTrue(ops.rateLimitAllow("ch1:u1"), "第 1 次在配额内");
        assertTrue(ops.rateLimitAllow("ch1:u1"), "第 2 次在配额内");
        assertFalse(ops.rateLimitAllow("ch1:u1"), "第 3 次超配额应拒");
        assertTrue(ops.rateLimitAllow("ch1:u2"), "别的用户/会话有独立配额");
    }

    @Test
    @DisplayName("限流 key 口径：rl: 前缀 + 与 ImFormat.makeUserKey 同构（thread 形态带 threadId）")
    void rateKeyFollowsUserKeyConvention() {
        assertEquals("rl:" + ImFormat.makeUserKey("ch1", "u1", "c1", ""),
                ImInboundGuardOps.makeRateKey("ch1", "u1", "c1", ""));
        assertEquals("rl:ch1:u1:c1:t1", ImInboundGuardOps.makeRateKey("ch1", "u1", "c1", "t1"),
                "thread 会话的限流键应含 threadId");
    }

    @Test
    @DisplayName("配额按上限值判定（max=1 时第二次即拒）")
    void rateLimitRespectsMax() {
        ImInboundGuardOps ops = singleInstance(60, 1);

        assertTrue(ops.rateLimitAllow("k"), "首次允许");
        assertFalse(ops.rateLimitAllow("k"), "max=1 时第二次即拒");
    }
}
