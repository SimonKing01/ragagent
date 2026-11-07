package com.ragagent.im.service;

import com.ragagent.im.runtime.ImFormat;
import com.ragagent.im.runtime.ImRedisKeys;
import com.ragagent.im.runtime.ImRedisStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 消息入口的两道闸门：**去重**（同一 messageId 只处理一次）与**限流**（按渠道+用户+会话的滑动窗口配额）。
 *
 * <p>自 {@link ImTurnOrchestrator} 原样外提（逐字搬迁，仅改依赖取用方式）。两者同属
 * 「入口闸门」这一关注点：都在 {@code runHandleMessage} 的最前面拦住不该进管线的消息，
 * 都优先走 Redis 面、故障时回落进程内形态——与停止链路、渠道生命周期无关。</p>
 *
 * <p>依赖面只收三项 + 一个随迁常量：Redis 面（{@code null} = 单实例形态）、窗口秒数、配额上限、
 * 去重 TTL。两个进程内回落表（{@code processedMsgs}/{@code rateWindows}）本簇独占，
 * **随之搬家**——门面不再持有（这与停止链路不同：那里 `inflight` 表被协作者共享，
 * 只能传引用）。</p>
 */
final class ImInboundGuardOps {

    private static final Logger log = LoggerFactory.getLogger(ImInboundGuardOps.class);

    /** 消息去重标记的 TTL。 */
    private static final int DEDUP_TTL_SECONDS = 300;

    private final ImRedisStore redisStore;
    private final int rateLimitWindowSec;
    private final int rateLimitMax;

    /** 去重（进程内分支）：messageId → epoch 秒。 */
    private final Map<String, Long> processedMsgs = new ConcurrentHashMap<>();
    /** 内容级兜底去重：同一人 5 秒内重复发送同一句只答一次（有的平台重推会换 id）。 */
    private static final long CONTENT_DEDUP_WINDOW_MS = 5_000;
    private final Map<String, Long> recentContent = new ConcurrentHashMap<>();

    /**
     * 内容级兜底：{@code platform|channel|user|chat|content} 在 5 秒内出现两次即视为重复。
     *
     * <p>覆盖「同一条消息被平台重推、但 messageId 变了」的情形（飞书换 id 的重推、
     * 客户端连点两次发送）。只拦极短窗口内的完全相同内容，正常连发两句一样的话不受影响。</p>
     */
    boolean isDuplicateContent(String key) {
        long now = System.currentTimeMillis();
        Long prev = recentContent.putIfAbsent(key, now);
        if (prev != null && now - prev < CONTENT_DEDUP_WINDOW_MS) {
            return true;
        }
        recentContent.put(key, now);
        if (recentContent.size() > 5_000) {
            recentContent.entrySet().removeIf(e -> now - e.getValue() > CONTENT_DEDUP_WINDOW_MS);
        }
        return false;
    }

    /** 限流的本地滑动窗口（Redis 故障时回落）。 */
    private final Map<String, List<Long>> rateWindows = new ConcurrentHashMap<>();

    ImInboundGuardOps(ImRedisStore redisStore, int rateLimitWindowSec, int rateLimitMax) {
        this.redisStore = redisStore;
        this.rateLimitWindowSec = rateLimitWindowSec;
        this.rateLimitMax = rateLimitMax;
    }

    /**
     * 同一 messageId 只处理一次：Redis 接入时走跨实例 SETNX（故障 fail-closed——
     * 宁可丢一条可重发的，也不重复跑一轮 LLM）；未接入时回落到进程内 map。
     */
    boolean isDuplicate(String messageId) {
        if (redisStore != null) {
            Boolean first = redisStore.setIfAbsent(ImRedisKeys.DEDUP_PREFIX + messageId, "1",
                    DEDUP_TTL_SECONDS);
            if (first == null) {
                log.error("[IM] Redis dedup failed (fail-closed, message dropped): {}", messageId);
                return true;
            }
            return !first;
        }
        long now = System.currentTimeMillis();
        Long prev = processedMsgs.putIfAbsent(messageId, now);
        if (prev != null) {
            return true;
        }
        if (processedMsgs.size() > 10_000) {
            processedMsgs.entrySet().removeIf(e -> now - e.getValue() > 300_000);
        }
        return false;
    }

    boolean rateLimitAllow(String key) {
        if (redisStore != null) {
            Boolean allowed = redisStore.rateLimitAllow(ImRedisKeys.RATE_LIMIT_PREFIX + key,
                    rateLimitWindowSec, rateLimitMax);
            if (allowed != null) {
                return allowed;
            }
            // Redis 故障 → 回落本地滑窗
        }
        long now = System.currentTimeMillis();
        long windowMs = rateLimitWindowSec * 1000L;
        List<Long> hits = rateWindows.computeIfAbsent(key,
                k -> java.util.Collections.synchronizedList(new ArrayList<>()));
        synchronized (hits) {
            hits.removeIf(t -> now - t > windowMs);
            if (hits.size() >= rateLimitMax) {
                return false;
            }
            hits.add(now);
            return true;
        }
    }

    static String makeRateKey(String channelId, String userId, String chatId, String threadId) {
        return "rl:" + ImFormat.makeUserKey(channelId, userId, chatId, threadId);
    }
}
