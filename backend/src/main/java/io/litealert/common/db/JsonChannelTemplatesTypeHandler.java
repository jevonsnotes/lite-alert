package io.litealert.common.db;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.litealert.notify.domain.NotifyTarget;
import io.litealert.topic.domain.Topic;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.springframework.beans.factory.annotation.Qualifier;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.Map;

/**
 * MyBatis TypeHandler for Topic templates JSON → Map<NotifyTarget.Type, Topic.ChannelTemplate>.
 */
public class JsonChannelTemplatesTypeHandler extends BaseTypeHandler<Map<NotifyTarget.Type, Topic.ChannelTemplate>> {

    private static ObjectMapper mapper;

    public static void setObjectMapper(@Qualifier("storeObjectMapper") ObjectMapper om) {
        JsonChannelTemplatesTypeHandler.mapper = om;
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i,
                                     Map<NotifyTarget.Type, Topic.ChannelTemplate> parameter,
                                     JdbcType jdbcType) throws SQLException {
        try {
            ps.setString(i, mapper().writeValueAsString(parameter));
        } catch (Exception e) {
            throw new SQLException("json serialize failed", e);
        }
    }

    @Override
    public Map<NotifyTarget.Type, Topic.ChannelTemplate> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public Map<NotifyTarget.Type, Topic.ChannelTemplate> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public Map<NotifyTarget.Type, Topic.ChannelTemplate> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private Map<NotifyTarget.Type, Topic.ChannelTemplate> parse(String json) {
        if (json == null || json.isBlank()) return new EnumMap<>(NotifyTarget.Type.class);
        try {
            Map<String, Topic.ChannelTemplate> raw = mapper().readValue(json,
                    new TypeReference<Map<String, Topic.ChannelTemplate>>() {});
            Map<NotifyTarget.Type, Topic.ChannelTemplate> result = new EnumMap<>(NotifyTarget.Type.class);
            for (Map.Entry<String, Topic.ChannelTemplate> e : raw.entrySet()) {
                result.put(NotifyTarget.Type.valueOf(e.getKey()), e.getValue());
            }
            return result;
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
