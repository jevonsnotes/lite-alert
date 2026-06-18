package io.litealert.topic.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import io.litealert.common.db.DbJson;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class TopicStore {

    private final JdbcTemplate jdbc;
    private final DbJson json;

    public Optional<Topic> findById(String id) {
        return jdbc.query("select * from la_topic where id = ?", this::map, id).stream().findFirst();
    }

    public Optional<Topic> findByNamespaceAndName(String namespaceId, String name) {
        return jdbc.query("select * from la_topic where namespace_id = ? and lower(name) = lower(?)", this::map, namespaceId, name)
                .stream().findFirst();
    }

    public Optional<Topic> findForWebhook(String namespaceName, String topicName) {
        return jdbc.query("select * from la_topic where lower(namespace_name) = lower(?) and lower(name) = lower(?)", this::map, namespaceName, topicName)
                .stream().findFirst();
    }

    public List<Topic> findByNamespace(String namespaceId) {
        return jdbc.query("select * from la_topic where namespace_id = ? order by created_at desc, name asc", this::map, namespaceId);
    }

    public List<Topic> findByOwner(String ownerId) {
        return jdbc.query("select * from la_topic where owner_id = ? order by created_at desc, name asc", this::map, ownerId);
    }

    public List<Topic> findAll() {
        return jdbc.query("select * from la_topic order by created_at desc, name asc", this::map);
    }

    public synchronized Topic save(Topic t) {
        boolean exists = findById(t.getId()).isPresent();
        if (exists) {
            jdbc.update("update la_topic set namespace_id=?, namespace_name=?, name=?, description=?, owner_id=?, status=?, auth_json=?, inbound_format_json=?, templates_json=?, transform_json=?, notify_template_json=?, created_at=?, updated_at=?, published_at=? where id=?",
                    args(t));
        } else {
            jdbc.update("insert into la_topic(id, namespace_id, namespace_name, name, description, owner_id, status, auth_json, inbound_format_json, templates_json, transform_json, notify_template_json, created_at, updated_at, published_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    insertArgs(t));
        }
        return t;
    }

    public synchronized void delete(String id) {
        jdbc.update("delete from la_topic where id = ?", id);
    }

    public synchronized void deleteByNamespace(String namespaceId) {
        jdbc.update("delete from la_topic where namespace_id = ?", namespaceId);
    }

    private Object[] insertArgs(Topic t) {
        return new Object[] { t.getId(), t.getNamespaceId(), t.getNamespaceName(), t.getName(), t.getDescription(),
                t.getOwnerId(), t.getStatus().name(), json.write(t.getAuth()), json.write(t.getInboundFormat()),
                json.write(t.getTemplates()), json.write(t.getTransform()), json.write(t.getNotifyTemplate()),
                ts(t.getCreatedAt()), ts(t.getUpdatedAt()), ts(t.getPublishedAt()) };
    }

    private Object[] args(Topic t) {
        return new Object[] { t.getNamespaceId(), t.getNamespaceName(), t.getName(), t.getDescription(),
                t.getOwnerId(), t.getStatus().name(), json.write(t.getAuth()), json.write(t.getInboundFormat()),
                json.write(t.getTemplates()), json.write(t.getTransform()), json.write(t.getNotifyTemplate()),
                ts(t.getCreatedAt()), ts(t.getUpdatedAt()), ts(t.getPublishedAt()), t.getId() };
    }

    private Topic map(ResultSet rs, int rowNum) throws java.sql.SQLException {
        return Topic.builder()
                .id(rs.getString("id"))
                .namespaceId(rs.getString("namespace_id"))
                .namespaceName(rs.getString("namespace_name"))
                .name(rs.getString("name"))
                .description(rs.getString("description"))
                .ownerId(rs.getString("owner_id"))
                .status(Topic.Status.valueOf(rs.getString("status")))
                .auth(json.read(rs.getString("auth_json"), Topic.Auth.class))
                .inboundFormat(json.read(rs.getString("inbound_format_json"), com.fasterxml.jackson.databind.JsonNode.class))
                .templates(json.read(rs.getString("templates_json"), new TypeReference<java.util.Map<io.litealert.notify.domain.NotifyTarget.Type, Topic.ChannelTemplate>>() {}, new java.util.EnumMap<>(io.litealert.notify.domain.NotifyTarget.Type.class)))
                .transform(json.read(rs.getString("transform_json"), Topic.Transform.class))
                .notifyTemplate(json.read(rs.getString("notify_template_json"), Topic.NotifyTemplate.class))
                .createdAt(instant(rs.getTimestamp("created_at")))
                .updatedAt(instant(rs.getTimestamp("updated_at")))
                .publishedAt(instant(rs.getTimestamp("published_at")))
                .build();
    }

    private Timestamp ts(Instant instant) { return instant == null ? null : Timestamp.from(instant); }
    private Instant instant(Timestamp ts) { return ts == null ? null : ts.toInstant(); }
}
