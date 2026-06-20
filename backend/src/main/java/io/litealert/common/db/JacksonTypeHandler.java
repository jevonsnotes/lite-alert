package io.litealert.common.db;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Generic MyBatis-Flex Jackson TypeHandler.
 * Serializes/deserializes any Java object to/from a JSON string column.
 *
 * Usage: {@code @Column(value = "auth_json", typeHandler = JacksonTypeHandler.class)}
 *
 * The target type is resolved from the field/parameter class automatically.
 * For generic types like {@code Map<K,V>}, use a dedicated subclass instead.
 */
public class JacksonTypeHandler<T> extends BaseTypeHandler<T> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Class<T> type;

    /** Required no-arg constructor for MyBatis framework */
    @SuppressWarnings("unchecked")
    public JacksonTypeHandler() {
        this.type = null;
    }

    /** Constructor used when type is known at registration time */
    public JacksonTypeHandler(Class<T> type) {
        this.type = type;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void setNonNullParameter(PreparedStatement ps, int i, T parameter, JdbcType jdbcType) throws SQLException {
        try {
            ps.setString(i, MAPPER.writeValueAsString(parameter));
        } catch (Exception e) {
            throw new SQLException("Failed to serialize JSON field: " + parameter.getClass().getName(), e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public T getNullableResult(ResultSet rs, String columnName) throws SQLException {
        String json = rs.getString(columnName);
        return parse(json, type);
    }

    @Override
    @SuppressWarnings("unchecked")
    public T getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        String json = rs.getString(columnIndex);
        return parse(json, type);
    }

    @Override
    @SuppressWarnings("unchecked")
    public T getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        String json = cs.getString(columnIndex);
        return parse(json, type);
    }

    @SuppressWarnings("unchecked")
    private T parse(String json, Class<T> clazz) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, clazz);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize JSON field", e);
        }
    }
}
