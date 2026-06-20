package io.litealert.topic.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.litealert.notify.domain.NotifyTarget;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.Map;

/**
 * TypeHandler for Topic templates: JSON ↔ Map&lt;NotifyTarget.Type, ChannelTemplate&gt;.
 * Uses Jackson directly because the generic type information would be lost
 * through the normal JacksonTypeHandler mechanism.
 */
public class JsonChannelTemplatesTypeHandler extends BaseTypeHandler<Map<NotifyTarget.Type, Topic.ChannelTemplate>> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i,
                                     Map<NotifyTarget.Type, Topic.ChannelTemplate> parameter,
                                     JdbcType jdbcType) throws SQLException {
        try {
            // Convert enum keys to strings for JSON storage
            Map<String, Topic.ChannelTemplate> raw = new java.util.LinkedHashMap<>();
            for (Map.Entry<NotifyTarget.Type, Topic.ChannelTemplate> e : parameter.entrySet()) {
                raw.put(e.getKey().name(), e.getValue());
            }
            ps.setString(i, MAPPER.writeValueAsString(raw));
        } catch (Exception e) {
            throw new SQLException("Failed to serialize templates", e);
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
            Map<String, Topic.ChannelTemplate> raw = MAPPER.readValue(json,
                    new TypeReference<Map<String, Topic.ChannelTemplate>>() {});
            Map<NotifyTarget.Type, Topic.ChannelTemplate> result = new EnumMap<>(NotifyTarget.Type.class);
            for (Map.Entry<String, Topic.ChannelTemplate> e : raw.entrySet()) {
                result.put(NotifyTarget.Type.valueOf(e.getKey()), e.getValue());
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize templates", e);
        }
    }
}
