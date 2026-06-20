package io.litealert.topic.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.litealert.common.db.DbJson;
import io.litealert.notify.domain.NotifyTarget;

import java.util.*;

/**
 * Manual JSON mapping helpers for Topic fields. Kept as utility methods
 * rather than MyBatis TypeHandlers because the fields are too heterogeneous
 * (Auth, JsonNode, EnumMap<T, ChannelTemplate>, Transform, NotifyTemplate)
 * and a single TypeHandler approach caused arg-type-mismatch errors at runtime.
 */
public final class TopicJsonMappings {

    private TopicJsonMappings() {}

    public static Topic.Auth readAuth(DbJson json, String value) {
        return json.read(value, Topic.Auth.class);
    }

    public static String writeAuth(DbJson json, Topic.Auth auth) {
        return json.write(auth);
    }

    public static JsonNode readInboundFormat(DbJson json, String value) {
        return json.read(value, JsonNode.class);
    }

    public static String writeInboundFormat(DbJson json, JsonNode node) {
        return json.write(node);
    }

    public static Map<NotifyTarget.Type, Topic.ChannelTemplate> readTemplates(DbJson json, String value) {
        if (value == null || value.isBlank()) return new EnumMap<>(NotifyTarget.Type.class);
        Map<String, Topic.ChannelTemplate> raw = json.read(value,
                new TypeReference<Map<String, Topic.ChannelTemplate>>() {}, new HashMap<>());
        Map<NotifyTarget.Type, Topic.ChannelTemplate> result = new EnumMap<>(NotifyTarget.Type.class);
        for (Map.Entry<String, Topic.ChannelTemplate> e : raw.entrySet()) {
            try {
                result.put(NotifyTarget.Type.valueOf(e.getKey()), e.getValue());
            } catch (IllegalArgumentException ignored) {
                // skip unknown types from future versions
            }
        }
        return result;
    }

    public static String writeTemplates(DbJson json, Map<NotifyTarget.Type, Topic.ChannelTemplate> templates) {
        if (templates == null || templates.isEmpty()) return null;
        Map<String, Topic.ChannelTemplate> raw = new LinkedHashMap<>();
        for (Map.Entry<NotifyTarget.Type, Topic.ChannelTemplate> e : templates.entrySet()) {
            raw.put(e.getKey().name(), e.getValue());
        }
        return json.write(raw);
    }

    public static Topic.Transform readTransform(DbJson json, String value) {
        return json.read(value, Topic.Transform.class);
    }

    public static String writeTransform(DbJson json, Topic.Transform transform) {
        return json.write(transform);
    }

    public static Topic.NotifyTemplate readNotifyTemplate(DbJson json, String value) {
        return json.read(value, Topic.NotifyTemplate.class);
    }

    public static String writeNotifyTemplate(DbJson json, Topic.NotifyTemplate notifyTemplate) {
        return json.write(notifyTemplate);
    }
}
