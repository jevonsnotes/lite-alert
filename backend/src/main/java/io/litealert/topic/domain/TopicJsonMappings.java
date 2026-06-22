package io.litealert.topic.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.litealert.common.db.DbJson;
import io.litealert.notify.domain.NotifyTarget;

import java.util.*;

/**
 * Manual JSON mapping helpers for Topic fields. Kept as utility methods
 * rather than MyBatis TypeHandlers because the fields are too heterogeneous
 * (Auth, JsonNode) and a single TypeHandler approach caused arg-type-mismatch
 * errors at runtime.
 *
 * <p>Template and scope mappings are now handled relationally by
 * {@link TopicChannelTemplateStore} and {@link ApiKeyScopeStore}.
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
}
