package com.ragagent.platform.fixtures;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Go 录制金片资源加载器：JSON 外置到 {@code /golden/<bundle>.json}，按附带的
 * {@code .sha256} 校验后按 key 取原文。
 *
 * <p>测试常量类仍保留原字段名（IDE 可跳、java 编译期可查），只是值来自资源文件；
 * 手改资源会因 SHA-256 不匹配而立即失败。</p>
 */
public final class GoldenResources {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, Map<String, String>> CACHE = new ConcurrentHashMap<>();

    private GoldenResources() {
    }

    /** 取一条录制原文；bundle/key 不存在或哈希不匹配时 fail-fast。 */
    public static String get(String bundle, String key) {
        Map<String, String> values = CACHE.computeIfAbsent(bundle, GoldenResources::load);
        String value = values.get(key);
        if (value == null) {
            throw new IllegalArgumentException("golden key not found: " + bundle + "/" + key);
        }
        return value;
    }

    private static Map<String, String> load(String bundle) {
        String path = "/golden/" + bundle + ".json";
        byte[] bytes = read(path);
        String expected = new String(read(path + ".sha256"), StandardCharsets.UTF_8).trim();
        String actual = sha256(bytes);
        if (!actual.equals(expected)) {
            throw new IllegalStateException(
                    "golden sha256 mismatch: " + path + " expected=" + expected + " actual=" + actual);
        }
        try {
            return MAPPER.readValue(bytes, new TypeReference<Map<String, String>>() { });
        } catch (IOException e) {
            throw new IllegalStateException("failed to parse golden bundle: " + path, e);
        }
    }

    private static byte[] read(String path) {
        try (InputStream in = GoldenResources.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("golden resource not found: " + path);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read golden resource: " + path, e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
