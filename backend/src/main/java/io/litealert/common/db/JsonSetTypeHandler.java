package io.litealert.common.db;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * MyBatis TypeHandler that maps Set<String> to/from a JSON column.
 */
public class JsonSetTypeHandler extends BaseTypeHandler<Set<String>> {

    private static ObjectMapper mapper;

    public static void setObjectMapper(ObjectMapper om) {
        JsonSetTypeHandler.mapper = om;
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Set<String> parameter, JdbcType jdbcType) throws SQLException {
        try {
            ps.setString(i, mapper().writeValueAsString(List.copyOf(parameter)));
        } catch (Exception e) {
            throw new SQLException("json serialize failed", e);
        }
    }

    @Override
    public Set<String> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public Set<String> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public Set<String> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private Set<String> parse(String json) {
        if (json == null || json.isBlank()) return new LinkedHashSet<>();
        try {
            List<String> list = mapper().readValue(json, new TypeReference<List<String>>() {});
            return new LinkedHashSet<>(list);
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
