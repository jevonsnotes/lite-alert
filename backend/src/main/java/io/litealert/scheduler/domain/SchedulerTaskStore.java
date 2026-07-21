package io.litealert.scheduler.domain;

import io.litealert.common.db.DbJson;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class SchedulerTaskStore {

    private final JdbcTemplate jdbc;
    private final DbJson json;

    public boolean tableReady() {
        try {
            Integer count = jdbc.queryForObject(
                    "select count(*) from information_schema.tables where upper(table_name) = 'LA_SCHEDULER_TASK'",
                    Integer.class);
            return count != null && count > 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    public synchronized SchedulerTask save(SchedulerTask t) {
        if (findById(t.getId()).isPresent()) {
            jdbc.update("update la_scheduler_task set owner_id=?, name=?, description=?, task_type=?, cron=?, " +
                            "enabled=?, status=?, draft_config_json=?, published_config_json=?, published_at=?, notify_config_ids_json=?, updated_at=? where id=?",
                    t.getOwnerId(), t.getName(), t.getDescription(), type(t), t.getCron(),
                    t.isEnabled(), status(t), json.write(t.getDraftConfig()), json.write(t.getPublishedConfig()),
                    ts(t.getPublishedAt()), json.write(t.getNotifyConfigIds()), ts(Instant.now()), t.getId());
        } else {
            Instant now = Instant.now();
            if (t.getCreatedAt() == null) t.setCreatedAt(now);
            if (t.getUpdatedAt() == null) t.setUpdatedAt(now);
            jdbc.update("insert into la_scheduler_task(id, owner_id, name, description, task_type, cron, " +
                            "enabled, status, draft_config_json, published_config_json, published_at, notify_config_ids_json, created_at, updated_at) " +
                            "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    t.getId(), t.getOwnerId(), t.getName(), t.getDescription(), type(t), t.getCron(),
                    t.isEnabled(), status(t), json.write(t.getDraftConfig()), json.write(t.getPublishedConfig()),
                    ts(t.getPublishedAt()), json.write(t.getNotifyConfigIds()), ts(t.getCreatedAt()), ts(t.getUpdatedAt()));
        }
        return t;
    }

    public Optional<SchedulerTask> findById(String id) {
        return jdbc.query("select * from la_scheduler_task where id = ?", this::map, id).stream().findFirst();
    }

    public List<SchedulerTask> findByOwner(String ownerId) {
        return jdbc.query("select * from la_scheduler_task where owner_id = ? order by created_at desc", this::map, ownerId);
    }

    public List<SchedulerTask> findAll() {
        return jdbc.query("select * from la_scheduler_task order by created_at desc", this::map);
    }

    /** Tasks the scheduler should run at startup. */
    public List<SchedulerTask> findSchedulable() {
        return jdbc.query("select * from la_scheduler_task where status = 'PUBLISHED' and enabled = true " +
                "and published_config_json is not null", this::map);
    }

    public void delete(String id) {
        jdbc.update("delete from la_scheduler_task_call where task_id = ?", id);
        jdbc.update("delete from la_scheduler_task where id = ?", id);
    }

    private SchedulerTask map(ResultSet rs, int rowNum) throws SQLException {
        return SchedulerTask.builder()
                .id(rs.getString("id"))
                .ownerId(rs.getString("owner_id"))
                .name(rs.getString("name"))
                .description(rs.getString("description"))
                .taskType(parseType(rs.getString("task_type")))
                .cron(rs.getString("cron"))
                .enabled(rs.getBoolean("enabled"))
                .status(SchedulerTask.Status.valueOf(rs.getString("status")))
                .draftConfig(readConfig(rs.getString("draft_config_json")))
                .publishedConfig(readConfig(rs.getString("published_config_json")))
                .notifyConfigIds(readStringList(rs.getString("notify_config_ids_json")))
                .publishedAt(instant(rs.getTimestamp("published_at")))
                .createdAt(instant(rs.getTimestamp("created_at")))
                .updatedAt(instant(rs.getTimestamp("updated_at")))
                .build();
    }

    private SchedulerTaskType parseType(String s) {
        try { return s == null ? null : SchedulerTaskType.valueOf(s); }
        catch (IllegalArgumentException e) { return null; }
    }

    /**
     * Read a polymorphic {@link TaskConfig} JSON column. On any deserialization failure (unknown
     * discriminator, malformed JSON, legacy rows without a {@code type} field) the store returns
     * {@code null} and logs a warning rather than throwing — so a single bad row never breaks
     * startup schedule recovery or list queries (design D1 risk mitigation).
     */
    private TaskConfig readConfig(String jsonStr) {
        if (jsonStr == null || jsonStr.isBlank()) return null;
        try {
            return json.read(jsonStr, TaskConfig.class);
        } catch (Exception e) {
            log.warn("failed to read scheduler task config json, falling back to null: {}", e.getMessage());
            return null;
        }
    }

    private java.util.List<String> readStringList(String jsonStr) {
        if (jsonStr == null || jsonStr.isBlank()) return new java.util.ArrayList<>();
        return json.read(jsonStr, new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>() {}, new java.util.ArrayList<>());
    }

    private String type(SchedulerTask t) { return t.getTaskType() == null ? null : t.getTaskType().name(); }
    private String status(SchedulerTask t) { return t.getStatus() == null ? null : t.getStatus().name(); }
    private Timestamp ts(Instant instant) { return instant == null ? null : Timestamp.from(instant); }
    private Instant instant(Timestamp ts) { return ts == null ? null : ts.toInstant(); }
}
