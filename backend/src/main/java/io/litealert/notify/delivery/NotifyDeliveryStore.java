package io.litealert.notify.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import io.litealert.common.db.DbJson;
import io.litealert.notify.domain.NotifyTarget;
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
public class NotifyDeliveryStore {

    private final JdbcTemplate jdbc;
    private final DbJson json;

    public boolean tableReady() {
        try {
            Integer count = jdbc.queryForObject(
                    "select count(*) from information_schema.tables where upper(table_name) = 'LA_NOTIFY_DELIVERY'",
                    Integer.class);
            return count != null && count > 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    public synchronized NotifyDelivery save(NotifyDelivery d) {
        boolean exists = findById(d.getId()).isPresent();
        if (exists) {
            jdbc.update("update la_notify_delivery set trace_id=?, topic_id=?, target_id=?, channel=?, payload_json=?, status=?, attempt=?, max_attempts=?, next_retry_at=?, locked_by=?, locked_at=?, last_error=?, created_at=?, updated_at=?, finished_at=? where id=?",
                    d.getTraceId(), d.getTopicId(), d.getTargetId(), channel(d), d.getPayloadJson(), status(d),
                    d.getAttempt(), d.getMaxAttempts(), ts(d.getNextRetryAt()), d.getLockedBy(), ts(d.getLockedAt()),
                    d.getLastError(), ts(d.getCreatedAt()), ts(d.getUpdatedAt()), ts(d.getFinishedAt()), d.getId());
        } else {
            jdbc.update("insert into la_notify_delivery(id, trace_id, topic_id, target_id, channel, payload_json, status, attempt, max_attempts, next_retry_at, locked_by, locked_at, last_error, created_at, updated_at, finished_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    d.getId(), d.getTraceId(), d.getTopicId(), d.getTargetId(), channel(d), d.getPayloadJson(), status(d),
                    d.getAttempt(), d.getMaxAttempts(), ts(d.getNextRetryAt()), d.getLockedBy(), ts(d.getLockedAt()),
                    d.getLastError(), ts(d.getCreatedAt()), ts(d.getUpdatedAt()), ts(d.getFinishedAt()));
        }
        return d;
    }

    public Optional<NotifyDelivery> findById(String id) {
        return jdbc.query("select * from la_notify_delivery where id = ?", this::map, id).stream().findFirst();
    }

    public List<NotifyDelivery> findDue(Instant now, int limit) {
        return jdbc.query("select * from la_notify_delivery where status in ('PENDING','RETRY_WAIT') and next_retry_at <= ? order by next_retry_at asc, created_at asc limit ?",
                this::map, ts(now), limit);
    }

    public List<NotifyDelivery> findRecent(int limit) {
        return jdbc.query("select * from la_notify_delivery order by created_at desc limit ?", this::map, limit);
    }

    public boolean claim(String id, String workerId, Instant lockedAt, Instant now) {
        int updated = jdbc.update("update la_notify_delivery set status='SENDING', locked_by=?, locked_at=?, updated_at=? where id=? and status in ('PENDING','RETRY_WAIT') and next_retry_at <= ?",
                workerId, ts(lockedAt), ts(now), id, ts(now));
        return updated == 1;
    }

    public void markSent(String id, Instant now) {
        jdbc.update("update la_notify_delivery set status='SENT', locked_by=null, locked_at=null, last_error=null, updated_at=?, finished_at=? where id=?",
                ts(now), ts(now), id);
    }

    public void markRetry(String id, int attempt, Instant nextRetryAt, String error, Instant now) {
        jdbc.update("update la_notify_delivery set status='RETRY_WAIT', attempt=?, next_retry_at=?, locked_by=null, locked_at=null, last_error=?, updated_at=? where id=?",
                attempt, ts(nextRetryAt), error, ts(now), id);
    }

    public void markGiveUp(String id, int attempt, String error, Instant now) {
        jdbc.update("update la_notify_delivery set status='GIVE_UP', attempt=?, locked_by=null, locked_at=null, last_error=?, updated_at=?, finished_at=? where id=?",
                attempt, error, ts(now), ts(now), id);
    }

    public void markCancelled(String id, String error, Instant now) {
        jdbc.update("update la_notify_delivery set status='CANCELLED', locked_by=null, locked_at=null, last_error=?, updated_at=?, finished_at=? where id=?",
                error, ts(now), ts(now), id);
    }

    public int recoverStuck(Instant cutoff, Instant now) {
        return jdbc.update("update la_notify_delivery set status='RETRY_WAIT', locked_by=null, locked_at=null, next_retry_at=?, updated_at=? where status='SENDING' and locked_at < ? and attempt < max_attempts",
                ts(now), ts(now), ts(cutoff));
    }

    public int deleteFinishedBefore(Instant cutoff) {
        return jdbc.update("delete from la_notify_delivery where status in ('SENT','GIVE_UP','CANCELLED') and finished_at < ?", ts(cutoff));
    }

    public JsonNode payload(NotifyDelivery d) {
        return json.read(d.getPayloadJson(), JsonNode.class);
    }

    private NotifyDelivery map(ResultSet rs, int rowNum) throws java.sql.SQLException {
        return NotifyDelivery.builder()
                .id(rs.getString("id"))
                .traceId(rs.getString("trace_id"))
                .topicId(rs.getString("topic_id"))
                .targetId(rs.getString("target_id"))
                .channel(NotifyTarget.Type.valueOf(rs.getString("channel")))
                .payloadJson(rs.getString("payload_json"))
                .status(NotifyDelivery.Status.valueOf(rs.getString("status")))
                .attempt(rs.getInt("attempt"))
                .maxAttempts(rs.getInt("max_attempts"))
                .nextRetryAt(instant(rs.getTimestamp("next_retry_at")))
                .lockedBy(rs.getString("locked_by"))
                .lockedAt(instant(rs.getTimestamp("locked_at")))
                .lastError(rs.getString("last_error"))
                .createdAt(instant(rs.getTimestamp("created_at")))
                .updatedAt(instant(rs.getTimestamp("updated_at")))
                .finishedAt(instant(rs.getTimestamp("finished_at")))
                .build();
    }

    private String channel(NotifyDelivery d) { return d.getChannel() == null ? null : d.getChannel().name(); }
    private String status(NotifyDelivery d) { return d.getStatus() == null ? null : d.getStatus().name(); }
    private Timestamp ts(Instant instant) { return instant == null ? null : Timestamp.from(instant); }
    private Instant instant(Timestamp ts) { return ts == null ? null : ts.toInstant(); }
}
