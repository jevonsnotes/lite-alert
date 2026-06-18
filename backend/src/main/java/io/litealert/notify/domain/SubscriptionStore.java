package io.litealert.notify.domain;

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
public class SubscriptionStore {

    private final JdbcTemplate jdbc;
    private final DbJson json;

    public Subscription getOrEmpty(String topicId) {
        return find(topicId).orElseGet(() -> Subscription.builder()
                .topicId(topicId)
                .contactIds(new java.util.ArrayList<>())
                .build());
    }

    public synchronized Subscription save(String topicId, List<String> contactIds) {
        Subscription s = Subscription.builder()
                .topicId(topicId)
                .contactIds(contactIds == null ? new java.util.ArrayList<>() : contactIds)
                .updatedAt(Instant.now())
                .build();
        if (find(topicId).isPresent()) {
            jdbc.update("update la_subscription set contact_ids_json=?, updated_at=? where topic_id=?",
                    json.write(s.getContactIds()), ts(s.getUpdatedAt()), topicId);
        } else {
            jdbc.update("insert into la_subscription(topic_id, contact_ids_json, updated_at) values (?, ?, ?)",
                    topicId, json.write(s.getContactIds()), ts(s.getUpdatedAt()));
        }
        return s;
    }

    public synchronized void delete(String topicId) {
        jdbc.update("delete from la_subscription where topic_id = ?", topicId);
    }

    public Optional<Subscription> find(String topicId) {
        return jdbc.query("select * from la_subscription where topic_id = ?", this::map, topicId).stream().findFirst();
    }

    private Subscription map(ResultSet rs, int rowNum) throws java.sql.SQLException {
        return Subscription.builder()
                .topicId(rs.getString("topic_id"))
                .contactIds(json.read(rs.getString("contact_ids_json"), new TypeReference<java.util.List<String>>() {}, new java.util.ArrayList<>()))
                .updatedAt(instant(rs.getTimestamp("updated_at")))
                .build();
    }

    private Timestamp ts(Instant instant) { return instant == null ? null : Timestamp.from(instant); }
    private Instant instant(Timestamp ts) { return ts == null ? null : ts.toInstant(); }
}
