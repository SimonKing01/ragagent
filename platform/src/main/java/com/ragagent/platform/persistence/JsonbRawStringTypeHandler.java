package com.ragagent.platform.persistence;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

/**
 * jsonb 列 ↔ 原始 JSON 文本（String）直通 TypeHandler。
 *
 * <p>写路径 {@code setObject(Types.OTHER)}（setString 会被 PG 拒绝）；
 * 读路径原样 getString——键序重排交给响应层，不做规范化模拟。</p>
 */
@MappedTypes(String.class)
// 归位说明：位于 L1 平台持久化面（与 NaiveOffsetDateTimeTypeHandler 同处）——
// im / embedchannel / agentmanagement 三方共用。ArchUnit 的基线注释
// 早已把「下沉到使用方域或提到 common」写为收敛方向；实体只留 @TableField 注解读引用。
public class JsonbRawStringTypeHandler extends BaseTypeHandler<String> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setObject(i, parameter, Types.OTHER);
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return rs.getString(columnName);
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return rs.getString(columnIndex);
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return cs.getString(columnIndex);
    }
}
