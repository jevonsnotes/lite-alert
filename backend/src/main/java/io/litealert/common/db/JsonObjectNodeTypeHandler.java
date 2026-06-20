package io.litealert.common.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.springframework.beans.factory.annotation.Qualifier;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * MyBatis TypeHandler that deserializes a JSON string to an Object of given type.
 * Used for Topic.transform_json and notify_template_json.
 */
public class JsonObjectNodeTypeHandler<T> extends BaseTypeHandler<T> {

    private static ObjectMapper mapper;
    private final Class<T> targetType;

    // Required no-arg constructor for MyBatis
    @SuppressWarnings("unchecked")
    public JsonObjectNodeTypeHandler() {
        this.targetType = null;
    }

    public JsonObjectNodeTypeHandler(Class<T> targetType) {
        this.targetType = targetType;
    }

    public static void setObjectMapper(@Qualifier("storeObjectMapper") ObjectMapper om) {
        JsonObjectNodeTypeHandler.mapper = om;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void setNonNullParameter(PreparedStatement ps, int i, T parameter, JdbcType jdbcType) throws SQLException {
        try {
            ps.setString(i, mapper().writeValueAsString(parameter));
        } catch (Exception e) {
            throw new SQLException("json serialize failed", e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public T getNullableResult(ResultSet rs, String columnName) throws SQLException {
        String json = rs.getString(columnName);
        return parse(json, targetType);
    }

    @Override
    @SuppressWarnings("unchecked")
    public T getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        String json = rs.getString(columnIndex);
        return parse(json, targetType);
    }

    @Override
    @SuppressWarnings("unchecked")
    public T getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        String json = cs.getString(columnIndex);
        return parse(json, targetType);
    }

    private static <V> V parse(String json, Class<V> clazz) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper().readValue(json, clazz);
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
