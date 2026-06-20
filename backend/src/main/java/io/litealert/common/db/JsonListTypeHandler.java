package io.litealert.common.db;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * MyBatis TypeHandler that maps List<String> to/from a JSON column.
 * Uses the shared storeObjectMapper so serialization is consistent
 * with the existing DbJson helper.
 */
@Component
public class JsonListTypeHandler extends BaseTypeHandler<List<String>> {

    private static ObjectMapper mapper;

    public static void setObjectMapper(@Qualifier("storeObjectMapper") ObjectMapper om) {
        JsonListTypeHandler.mapper = om;
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<String> parameter, JdbcType jdbcType) throws SQLException {
        try {
            ps.setString(i, mapper().writeValueAsString(parameter));
        } catch (Exception e) {
            throw new SQLException("json serialize failed", e);
        }
    }

    @Override
    public List<String> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        String json = rs.getString(columnName);
        return parse(json);
    }

    @Override
    public List<String> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        String json = rs.getString(columnIndex);
        return parse(json);
    }

    @Override
    public List<String> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        String json = cs.getString(columnIndex);
        return parse(json);
    }

    private List<String> parse(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return mapper().readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            throw new RuntimeException("json parse failed", e);
        }
    }

    private static ObjectMapper mapper() {
        if (mapper == null) {
            // fallback for non-Spring bootstrap paths (rare)
            mapper = new ObjectMapper();
        }
        return mapper;
    }
}
