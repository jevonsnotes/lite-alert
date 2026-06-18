package io.litealert.notify.domain;

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
public class NotifyTargetStore {

    private final JdbcTemplate jdbc;

    public List<NotifyTarget> findByUser(String userId) {
        return jdbc.query("select * from la_notify_target where user_id = ? order by created_at desc, label asc", this::map, userId);
    }

    public Optional<NotifyTarget> findById(String id) {
        return jdbc.query("select * from la_notify_target where id = ?", this::map, id).stream().findFirst();
    }

    public synchronized NotifyTarget save(NotifyTarget t) {
        boolean exists = findById(t.getId()).isPresent();
        if (exists) {
            jdbc.update("update la_notify_target set user_id=?, label=?, type=?, endpoint=?, secret=?, enabled=?, created_at=? where id=?",
                    t.getUserId(), t.getLabel(), typeName(t), t.getEndpoint(), t.getSecret(), t.isEnabled(), ts(t.getCreatedAt()), t.getId());
        } else {
            jdbc.update("insert into la_notify_target(id, user_id, label, type, endpoint, secret, enabled, created_at) values (?, ?, ?, ?, ?, ?, ?, ?)",
                    t.getId(), t.getUserId(), t.getLabel(), typeName(t), t.getEndpoint(), t.getSecret(), t.isEnabled(), ts(t.getCreatedAt()));
        }
        return t;
    }

    public synchronized void delete(String id) {
        jdbc.update("delete from la_notify_target where id = ?", id);
    }

    private String typeName(NotifyTarget t) {
        return (t.getType() == null ? NotifyTarget.Type.EMAIL : t.getType()).name();
    }

    private NotifyTarget map(ResultSet rs, int rowNum) throws java.sql.SQLException {
        return NotifyTarget.builder()
                .id(rs.getString("id"))
                .userId(rs.getString("user_id"))
                .label(rs.getString("label"))
                .type(NotifyTarget.Type.valueOf(rs.getString("type")))
                .endpoint(rs.getString("endpoint"))
                .secret(rs.getString("secret"))
                .enabled(rs.getBoolean("enabled"))
                .createdAt(instant(rs.getTimestamp("created_at")))
                .build();
    }

    private Timestamp ts(Instant instant) { return instant == null ? null : Timestamp.from(instant); }
    private Instant instant(Timestamp ts) { return ts == null ? null : ts.toInstant(); }
}
