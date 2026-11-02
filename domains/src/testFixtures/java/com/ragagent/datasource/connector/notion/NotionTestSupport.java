package com.ragagent.datasource.connector.notion;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.datasource.domain.DataSourceConfig;
import com.ragagent.datasource.domain.DataSourceConstants;
import com.ragagent.datasource.domain.FetchedItem;
import com.ragagent.datasource.domain.connector.ConnectorException;
import com.ragagent.datasource.domain.connector.ConnectorHttp;
import com.ragagent.platform.security.SsrfGuard;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Notion 测试的公共夹具。
 *
 * <h2>SSRF 白名单是进程级静态状态（必须还原）</h2>
 * <p>白名单取 {@code SSRF_WHITELIST=127.0.0.1,::1,localhost}。Java 进程内改不了 env，
 * 按 {@code FakeYuque} / {@code FeishuTestSupport} 的既有惯例：
 * {@code @BeforeAll} 调 {@link #allowLoopback()}，{@code @AfterAll} 调
 * {@link #restoreSsrf()}。</p>
 *
 * <h2>限流与退避必须被替换掉，否则测试变成慢测</h2>
 * <p>生产默认限流是 3 req/s、重试退避 1s/2s/4s。测试里一律注入
 * {@link NotionClient.RateLimiter#unlimited()} + 零退避 + 记账 Sleeper，
 * 于是没有一条用例真的在等墙钟。</p>
 */
final class NotionTestSupport {

    /** 放行本机回环的原始白名单（对应 {@code SSRF_WHITELIST}）。 */
    static final String LOOPBACK_WHITELIST = "127.0.0.1,::1,localhost";

    private NotionTestSupport() {
    }

    /** 进入本套件时的进程级白名单（SsrfGuard 是 static，改后必须按快照还原）。 */
    private static SsrfGuard.Whitelist whitelistSnapshot;

    static void allowLoopback() {
        whitelistSnapshot = SsrfGuard.snapshotWhitelist();
        SsrfGuard guard = new SsrfGuard();
        guard.reloadWhitelist(LOOPBACK_WHITELIST);
        ConnectorHttp.setSsrfGuard(guard);
    }

    static void restoreSsrf() {
        ConnectorHttp.setSsrfGuard(new SsrfGuard());
        if (whitelistSnapshot != null) {
            SsrfGuard.restoreWhitelist(whitelistSnapshot);
        } else {
            // 未配对调用（没走过 allowLoopback）时退回环境变量重建
            ConnectorHttp.ssrfGuard().reloadWhitelist(envWhitelistRaw());
        }
    }

    private static String envWhitelistRaw() {
        String primary = System.getenv("SSRF_WHITELIST");
        String extra = System.getenv("SSRF_WHITELIST_EXTRA");
        primary = primary == null ? "" : primary.trim();
        extra = extra == null ? "" : extra.trim();
        if (primary.isEmpty()) {
            return extra;
        }
        if (extra.isEmpty()) {
            return primary;
        }
        return primary + "," + extra;
    }

    // ── 配置 ──────────────────────────────────────────────────────────────

    /** 造一份标准的数据源配置。 */
    static DataSourceConfig config(String baseUrl, List<String> resourceIds) {
        return config("tok", baseUrl, resourceIds);
    }

    static DataSourceConfig config(String apiKey, String baseUrl, List<String> resourceIds) {
        DataSourceConfig c = new DataSourceConfig();
        c.setType(DataSourceConstants.CONNECTOR_TYPE_NOTION);
        Map<String, Object> credentials = new LinkedHashMap<>();
        credentials.put("apiKey", apiKey);
        c.setCredentials(credentials);
        c.setResourceIds(resourceIds == null ? new ArrayList<>() : new ArrayList<>(resourceIds));
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("baseUrl", baseUrl);
        c.setSettings(settings);
        return c;
    }

    // ── 连接器 / 客户端 ───────────────────────────────────────────────────

    /** 记录休眠时长的 Sleeper（断言退避次数与毫秒数用）。 */
    static final class RecordingSleeper implements NotionClient.Sleeper {
        final List<Long> slept = new ArrayList<>();

        @Override
        public void sleep(long millis) {
            slept.add(millis);
        }
    }

    /** 零退避 + 无限流 + 不真睡的 Sleeper。 */
    static NotionConnector fastConnector() {
        return new NotionConnector((token, baseUrl) -> NotionClient.forTesting(
                token, baseUrl, NotionClient.RateLimiter.unlimited(),
                NotionClient.Backoff.none(), millis -> { }));
    }

    static NotionConnector fastConnector(RecordingSleeper sleeper) {
        return new NotionConnector((token, baseUrl) -> NotionClient.forTesting(
                token, baseUrl, NotionClient.RateLimiter.unlimited(),
                NotionClient.Backoff.none(), sleeper));
    }

    static NotionClient fastClient(String baseUrl) {
        return fastClient("tok", baseUrl, null);
    }

    static NotionClient fastClient(String token, String baseUrl, RecordingSleeper sleeper) {
        return fastClient(token, baseUrl, sleeper, NotionClient.Backoff.none());
    }

    /**
     * 退避可指定的版本：想断言"退避序列是 1s/2s/4s"时传
     * {@link NotionClient.Backoff#exponentialSeconds()}——休眠仍被记账、
     * 不真等，所以用例零耗时。
     */
    static NotionClient fastClient(String token, String baseUrl, RecordingSleeper sleeper,
                                   NotionClient.Backoff backoff) {
        NotionClient.Sleeper s = sleeper == null ? millis -> { } : sleeper;
        return NotionClient.forTesting(token, baseUrl,
                NotionClient.RateLimiter.unlimited(), backoff, s);
    }

    // ── 小工具 ────────────────────────────────────────────────────────────

    static JsonNode json(String raw) {
        try {
            return NotionJson.MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new ConnectorException("bad test json: " + e.getMessage(), e);
        }
    }

    static String contentOf(FetchedItem item) {
        return item.getContent() == null
                ? ""
                : new String(item.getContent(), java.nio.charset.StandardCharsets.UTF_8);
    }
}
