package com.ragagent.datasource.connector.rss;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.datasource.domain.DataSourceConfig;
import com.ragagent.datasource.domain.connector.ConnectorException;
import com.ragagent.platform.text.UnicodeText;
import com.ragagent.platform.web.JsonMappers;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RSS 连接器的私有配置（解析 + feed 地址/请求头的存储形状）。
 *
 * <h2>两处存放位置，以及"谁覆盖谁"</h2>
 * <p>{@code feedUrls} 是<b>非密钥</b>配置，住在
 * {@code DataSourceConfig.Settings}（UI 上改它不需要换凭据）；{@code authHeaders}
 * 可能带密钥，住在 {@code Credentials}（落库前会被 AES 加密）。
 * <b>settings 里的值优先</b>（只在非空时覆盖 Credentials 里的同名项）。</p>
 *
 * <p>历史上有过"把 feed 地址误存进 Credentials"的写入路径，现由
 * {@link DataSourceConfig#stripNonSecretCredentials} 在落库前清掉 ⇒ 本类不再需要
 * 任何"老位置回显"逻辑（原 {@code enrichRssFeedUrlsInSettings} 已随之删除）。</p>
 *
 * <h2>为什么反序列化要用"不许标量强转"的 mapper</h2>
 * <p>{@code {"feedUrls": 12}} 必须报错，不能静默强转成 {@code "12"}——
 * 数字/布尔塞进字符串字段是配置错误，静默强转会把它变成"看起来合法的坏配置"
 * （Jackson 默认会强转，这里显式关掉）。</p>
 */
final class RssConfig {

    private static final ObjectMapper MAPPER = buildMapper();

    private static ObjectMapper buildMapper() {
        // 键名＝Java 字段名（feedUrls / authHeaders）——不设命名策略。
        // 数字/布尔不得静默强转成字符串（策略在 JsonMappers.lenientStrictScalars()）。
        return JsonMappers.lenientStrictScalars();
    }

    /** {@code feedUrls}：换行或逗号分隔的 feed 地址列表。 */
    private String feedUrls = "";

    /**
     * {@code authHeaders}：换行分隔的 {@code "Name: Value"} 自定义请求头，
     * <b>只作用于 feed 抓取</b>，绝不发给第三方文章页。
     */
    private String authHeaders;

    RssConfig() {
    }

    RssConfig(String feedUrls, String authHeaders) {
        this.feedUrls = feedUrls == null ? "" : feedUrls;
        this.authHeaders = authHeaders;
    }

    String getFeedUrls() {
        return feedUrls;
    }

    public void setFeedUrls(String v) {
        feedUrls = v == null ? "" : v;
    }

    String getAuthHeaders() {
        return authHeaders;
    }

    public void setAuthHeaders(String v) {
        authHeaders = v;
    }

    // ── 解析 ────────────────────────────────────────────────────────────────

    /**
     * 从 {@link DataSourceConfig} 解出 RSS 私有配置。
     *
     * <p>四条分支：</p>
     * <ol>
     *   <li>{@code config == null} → {@link ConnectorException.InvalidConfig}{@code ("config is nil")}
     *       （文本 = {@code "invalid configuration: config is nil"}）；</li>
     *   <li>Credentials 解不出 {@link RssConfig} → 普通 {@code ConnectorException}
     *       （前缀 {@code "parse rss credentials: "}，<b>不是</b>哨兵包装，
     *       所以调用方认不出类型）；</li>
     *   <li>Settings 里的 {@code feedUrls} 非空 → 覆盖 Credentials 里的；</li>
     *   <li>最终列表为空 → {@link ConnectorException.InvalidCredentials}{@code ("feedUrls is required")}。</li>
     * </ol>
     */
    static RssConfig parse(DataSourceConfig config) {
        if (config == null) {
            throw new ConnectorException.InvalidConfig("config is nil");
        }
        RssConfig cfg;
        try {
            JsonNode credentials = MAPPER.valueToTree(
                    config.getCredentials() == null ? Map.of() : config.getCredentials());
            cfg = MAPPER.treeToValue(credentials, RssConfig.class);
        } catch (Exception e) {
            throw new ConnectorException("parse rss credentials: " + e.getMessage(), e);
        }
        if (cfg == null) {
            cfg = new RssConfig();
        }
        String fromSettings = feedUrlsFromSettings(config.getSettings());
        if (!fromSettings.isEmpty()) {
            cfg.feedUrls = fromSettings;
        }
        if (cfg.feedUrlList().isEmpty()) {
            throw new ConnectorException.InvalidCredentials("feedUrls is required");
        }
        return cfg;
    }

    /**
     * 从 settings 里抠出 {@code feedUrls}。
     *
     * <p>非字符串（数字 / 布尔 / 对象）一律当成"没配"，<b>不报错</b>——
     * 这与 {@link #parse} 里 Credentials 的严格反序列化不同（settings 是宽松来源）。</p>
     */
    static String feedUrlsFromSettings(Map<String, Object> settings) {
        if (settings == null || settings.isEmpty()) {
            return "";
        }
        Object raw = settings.get("feedUrls");
        if (!(raw instanceof String s)) {
            return "";
        }
        return UnicodeText.trim(s);
    }

    /**
     * 按换行 / 回车 / 逗号切分，逐项去空白，
     * 去重（<b>保留首次出现顺序</b>），丢掉空项。
     *
     * <p>连续分隔符只算一次、首尾的分隔符直接忽略——
     * {@code "\n\n"} 得到的是空列表。</p>
     */
    List<String> feedUrlList() {
        List<String> out = new ArrayList<>();
        if (feedUrls == null || feedUrls.isEmpty()) {
            return out;
        }
        Set<String> seen = new LinkedHashSet<>();
        int i = 0;
        int n = feedUrls.length();
        while (i < n) {
            char c = feedUrls.charAt(i);
            if (c == '\n' || c == '\r' || c == ',') {
                i++;
                continue;
            }
            int start = i;
            while (i < n) {
                char d = feedUrls.charAt(i);
                if (d == '\n' || d == '\r' || d == ',') {
                    break;
                }
                i++;
            }
            String u = UnicodeText.trim(feedUrls.substring(start, i));
            if (u.isEmpty()) {
                continue;
            }
            if (seen.add(u)) {
                out.add(u);
            }
        }
        return out;
    }

    /**
     * 把换行分隔的 {@code "Name: Value"} 块解析成 map。
     *
     * <p>跳过条件：空行跳过；<b>找不到冒号、或冒号在第 0 位</b>跳过
     * （{@code idx <= 0}）；name 去空白后为空跳过（其实已被 {@code idx <= 0} 覆盖）。
     * 同名后者覆盖前者。最终 map 为空时返回 {@code null}。</p>
     */
    Map<String, String> parseHeaders() {
        if (authHeaders == null || UnicodeText.trim(authHeaders).isEmpty()) {
            return null;
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (String rawLine : authHeaders.split("\n", -1)) {
            String line = UnicodeText.trim(rawLine);
            if (line.isEmpty()) {
                continue;
            }
            int idx = line.indexOf(':');
            if (idx <= 0) {
                continue;
            }
            String name = UnicodeText.trim(line.substring(0, idx));
            String value = UnicodeText.trim(line.substring(idx + 1));
            if (name.isEmpty()) {
                continue;
            }
            headers.put(name, value);
        }
        if (headers.isEmpty()) {
            return null;
        }
        return headers;
    }
}
