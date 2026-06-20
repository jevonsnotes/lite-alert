package io.litealert.common.db;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.litealert.apikey.domain.ApiKey;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * MyBatis TypeHandler for List<ApiKey.Scope> JSON serialization.
 */
public class JsonScopesTypeHandler extends BaseTypeHandler<List<ApiKey.Scope>> {

    private static ObjectMapper mapper;

    public static void setObjectMapper(ObjectMapper om) {
        JsonScopesTypeHandler.mapper = om;
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<ApiKey.Scope> parameter, JdbcType jdbcType) throws SQLException {
        try {
            ps.setString(i, mapper().writeValueAsString(parameter));
        } catch (Exception e) {
            throw new SQLException("json serialize failed", e);
        }
    }

    @Override
    public List<ApiKey.Scope> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public List<ApiKey.Scope> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public List<ApiKey.Scope> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private List<ApiKey.Scope> parse(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return mapper().readValue(json, new TypeReference<List<ApiKey.Scope>>() {});
        } catch (Exception e) {
            throw new RuntimeException("json parse failed", e);
        }
    }

    private static ObjectMapper mapper() {
        if (mapper == null) {
            mapper = new ObjectMapper();
        }
        return mapper;
    }
}
