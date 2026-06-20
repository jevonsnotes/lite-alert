package io.litealert.common.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.litealert.topic.domain.Topic;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.springframework.beans.factory.annotation.Qualifier;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * MyBatis TypeHandler for Topic.Auth JSON serialization.
 */
public class JsonAuthTypeHandler extends BaseTypeHandler<Topic.Auth> {

    private static ObjectMapper mapper;

    public static void setObjectMapper(@Qualifier("storeObjectMapper") ObjectMapper om) {
        JsonAuthTypeHandler.mapper = om;
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Topic.Auth parameter, JdbcType jdbcType) throws SQLException {
        try {
            ps.setString(i, mapper().writeValueAsString(parameter));
        } catch (Exception e) {
            throw new SQLException("json serialize failed", e);
        }
    }

    @Override
    public Topic.Auth getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public Topic.Auth getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public Topic.Auth getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private Topic.Auth parse(String json) {
        if (json == null || json.isBlank()) return new Topic.Auth();
        try {
            return mapper().readValue(json, Topic.Auth.class);
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
