package io.litealert.scheduler.domain;

import io.litealert.common.db.DbJson;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class SchedulerNotifyConfigStore {

    private final JdbcTemplate jdbc;
    private final DbJson json;

    public boolean tableReady() {
        try {
            Integer count = jdbc.queryForObject(
                    "select count(*) from information_schema.tables where upper(table_name) = 'LA_SCHEDULER_NOTIFY_CONFIG'",
                    Integer.class);
            return count != null && count > 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    public synchronized SchedulerNotifyConfig save(SchedulerNotifyConfig c) {
        if (findById(c.getId()).isPresent()) {
            jdbc.update("update la_scheduler_notify_config set owner_id=?, name=?, method=?, url=?, " +
                            "headers_json=?, body_template=?, trigger_on=?, enabled=?, updated_at=? where id=?",
                    c.getOwnerId(), c.getName(), c.getMethod(), c.getUrl(),
                    json.write(c.getHeaders()), c.getBodyTemplate(), trigger(c), c.isEnabled(),
                    Timestamp.from(Instant.now()), c.getId());
        } else {
            Instant now = Instant.now();
            if (c.getCreatedAt() == null) c.setCreatedAt(now);
            if (c.getUpdatedAt() == null) c.setUpdatedAt(now);
            jdbc.update("insert into la_scheduler_notify_config(id, owner_id, name, method, url, " +
                            "headers_json, body_template, trigger_on, enabled, created_at, updated_at) " +
                            "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    c.getId(), c.getOwnerId(), c.getName(), c.getMethod(), c.getUrl(),
                    json.write(c.getHeaders()), c.getBodyTemplate(), trigger(c), c.isEnabled(),
                    Timestamp.from(c.getCreatedAt()), Timestamp.from(c.getUpdatedAt()));
        }
        return c;
    }

    public Optional<SchedulerNotifyConfig> findById(String id) {
        return jdbc.query("select * from la_scheduler_notify_config where id = ?", this::map, id).stream().findFirst();
    }

    public List<SchedulerNotifyConfig> findByOwner(String ownerId) {
        return jdbc.query("select * from la_scheduler_notify_config where owner_id = ? order by created_at desc", this::map, ownerId);
    }

    public List<SchedulerNotifyConfig> findByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        String in = String.join(",", ids.stream().map(i -> "?").toList());
        return jdbc.query("select * from la_scheduler_notify_config where id in (" + in + ")", this::map, ids.toArray());
    }

    public void delete(String id) {
        jdbc.update("delete from la_scheduler_notify_config where id = ?", id);
    }

    private SchedulerNotifyConfig map(ResultSet rs, int rowNum) throws SQLException {
        return SchedulerNotifyConfig.builder()
                .id(rs.getString("id"))
                .ownerId(rs.getString("owner_id"))
                .name(rs.getString("name"))
                .method(rs.getString("method"))
                .url(rs.getString("url"))
                .headers(json.read(rs.getString("headers_json"),
                        new com.fasterxml.jackson.core.type.TypeReference<List<SchedulerNotifyConfig.Header>>() {},
                        List.of()))
                .bodyTemplate(rs.getString("body_template"))
                .triggerOn(parseTrigger(rs.getString("trigger_on")))
                .enabled(rs.getBoolean("enabled"))
                .createdAt(instant(rs.getTimestamp("created_at")))
                .updatedAt(instant(rs.getTimestamp("updated_at")))
                .build();
    }

    private SchedulerNotifyConfig.TriggerOn parseTrigger(String s) {
        try { return s == null ? null : SchedulerNotifyConfig.TriggerOn.valueOf(s); }
        catch (IllegalArgumentException e) { return SchedulerNotifyConfig.TriggerOn.FAIL; }
    }

    private String trigger(SchedulerNotifyConfig c) {
        return c.getTriggerOn() == null ? SchedulerNotifyConfig.TriggerOn.FAIL.name() : c.getTriggerOn().name();
    }

    private Instant instant(Timestamp ts) { return ts == null ? null : ts.toInstant(); }
}
