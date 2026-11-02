package com.ragagent.im.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.platform.security.CryptoService;
import com.ragagent.platform.web.JsonMappers;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code im_channels.credentials} 的加密 TypeHandler（密文包在 jsonb 里）。
 *
 * <p>为什么不是裸密文：列类型是 jsonb，{@code enc:v1:...} 不是合法 JSON ⇒ 包一层
 * {@code {"enc":"enc:v1:..."}}。读取时**只有 enc 一个键**才解密，其余形态原样返回。</p>
 *
 * <p>历史行是明文 JSON ⇒ 宽容读取、无需数据迁移；下一次写回（update/toggle）自动变成密文。
 * 密钥缺失或轮换导致解不开时返回 {@code {}} 并记日志 —— 绝不让密文冒充满值，也不让读取失败。</p>
 *
 * <p>与 {@code McpSecretTypeHandler} 同口径：不加 {@code @MappedTypes(String.class)}，
 * 避免被注册成全局 String handler 污染其他文本列。</p>
 */
public class ImCredentialsTypeHandler extends BaseTypeHandler<String> {

    private static final Logger log = LoggerFactory.getLogger(ImCredentialsTypeHandler.class);

    private static final String ENC = "enc";
    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    private final CryptoService cryptoService;

    public ImCredentialsTypeHandler() {
        this(new CryptoService());
    }

    public ImCredentialsTypeHandler(CryptoService cryptoService) {
        this.cryptoService = cryptoService;
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setObject(i, wrap(parameter), Types.OTHER);
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return unwrap(rs.getString(columnName));
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return unwrap(rs.getString(columnIndex));
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return unwrap(cs.getString(columnIndex));
    }

    /** 写：空/空对象/已是包装形态原样；否则加密后包成 {@code {"enc":...}}。 */
    String wrap(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) {
            return "{}";
        }
        if (isWrapped(s)) {
            return s;
        }
        byte[] key = cryptoService.getAESKey();
        if (key == null || key.length == 0) {
            // 无密钥降级为明文（与 MCP 加密列同口径：部署没配 SYSTEM_AES_KEY 时不假装加密）
            return s;
        }
        ObjectNode node = MAPPER.createObjectNode();
        node.put(ENC, cryptoService.encryptAESGCM(s, key));
        return node.toString();
    }

    /** 读：包装形态解密；历史明文原样；解不开返回空对象。 */
    String unwrap(String stored) {
        if (stored == null || stored.isEmpty() || !isWrapped(stored)) {
            return stored;
        }
        String cipher = readEnc(stored);
        if (cipher.isEmpty()) {
            return "{}";
        }
        CryptoService.LenientResult result = cryptoService.decryptStoredSecretLenient(cipher);
        if (!result.ok()) {
            log.warn("[crypto] im credentials: decrypt failed (SYSTEM_AES_KEY missing/rotated?), "
                    + "treating as unconfigured");
            return "{}";
        }
        return result.plaintext();
    }

    /** 是否 {@code {"enc": "..."}} 的单键包装（历史明文 JSON 不会满足）。 */
    static boolean isWrapped(String s) {
        if (!s.startsWith("{") || !s.endsWith("}")) {
            return false;
        }
        try {
            JsonNode node = MAPPER.readTree(s);
            return node.size() == 1 && node.has(ENC);
        } catch (java.io.IOException | RuntimeException e) {
            return false;
        }
    }

    private static String readEnc(String wrapped) {
        try {
            JsonNode node = MAPPER.readTree(wrapped).get(ENC);
            return node == null ? "" : node.asText();
        } catch (java.io.IOException | RuntimeException e) {
            return "";
        }
    }
}
