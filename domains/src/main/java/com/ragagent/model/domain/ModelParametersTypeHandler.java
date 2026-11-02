package com.ragagent.model.domain;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.contract.model.ModelParameters;
import com.ragagent.platform.security.CryptoService;
import com.ragagent.platform.web.JsonMappers;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

/**
 * ModelParameters 的 jsonb TypeHandler：
 * - setNonNullParameter：拷贝 → api_key/app_secret AES-256-GCM 加密（key 缺失时明文落库）
 *   → Jackson 序列化 → setString
 * - getNullableResult：反序列化 → api_key/app_secret 宽容解密
 *   （解密失败置空并记日志，行可加载）
 */
@MappedTypes(ModelParameters.class)
public class ModelParametersTypeHandler extends BaseTypeHandler<ModelParameters> {

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    private final CryptoService cryptoService;

    public ModelParametersTypeHandler() {
        this(new CryptoService());
    }

    public ModelParametersTypeHandler(CryptoService cryptoService) {
        this.cryptoService = cryptoService;
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, ModelParameters parameter, JdbcType jdbcType)
            throws SQLException {
        ModelParameters cp = parameter.copy();
        byte[] key = cryptoService.getAESKey();
        if (key != null) {
            if (!cp.getApiKey().isEmpty()) {
                cp.setApiKey(cryptoService.encryptAESGCM(cp.getApiKey(), key));
            }
            if (!cp.getAppSecret().isEmpty()) {
                cp.setAppSecret(cryptoService.encryptAESGCM(cp.getAppSecret(), key));
            }
        }
        try {
            // PG jsonb：setObject(OTHER) 让服务端按列类型强转（对照 PgJsonTypeHandler 说明）
            ps.setObject(i, MAPPER.writeValueAsString(cp), java.sql.Types.OTHER);
        } catch (Exception e) {
            throw new SQLException("serialize model parameters failed", e);
        }
    }

    @Override
    public ModelParameters getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public ModelParameters getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public ModelParameters getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private ModelParameters parse(String json) throws SQLException {
        if (json == null || json.isEmpty()) {
            return new ModelParameters();
        }
        try {
            ModelParameters params = MAPPER.readValue(json, ModelParameters.class);
            // 宽容解密：密钥缺失/轮换 → 置空（"credential not configured"），行照常加载
            // （与 DataSource/APIPrincipal/WebSearch 各 Scan 路径同口径：置空即信号，不记日志）
            CryptoService.LenientResult apiKey = cryptoService.decryptStoredSecretLenient(params.getApiKey());
            params.setApiKey(apiKey.ok() ? apiKey.plaintext() : "");
            CryptoService.LenientResult appSecret = cryptoService.decryptStoredSecretLenient(params.getAppSecret());
            params.setAppSecret(appSecret.ok() ? appSecret.plaintext() : "");
            return params;
        } catch (Exception e) {
            throw new SQLException("deserialize model parameters failed", e);
        }
    }
}
