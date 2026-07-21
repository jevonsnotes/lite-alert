package io.litealert.scheduler.domain;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class SchedulerTaskCallStore {

    private static final int EXCERPT_MAX = 2000;

    private final JdbcTemplate jdbc;

    public void insert(SchedulerTaskCall c) {
        jdbc.update("insert into la_scheduler_task_call(id, task_id, triggered_at, protocol, method, url, " +
                        "tcp_target, http_status, tcp_ok, duration_ms, success, assertion_passed, error_message, " +
                        "response_excerpt, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                c.getId(), c.getTaskId(), ts(c.getTriggeredAt()), c.getProtocol(), c.getMethod(), c.getUrl(),
                c.getTcpTarget(), c.getHttpStatus(), c.getTcpOk(),
                c.getDurationMs(), c.getStatus() == SchedulerTaskCall.Status.SUCCESS, assertion(c), truncate(c.getErrorMessage()),
                truncate(mask(c.getResponseExcerpt())), ts(c.getCreatedAt() == null ? Instant.now() : c.getCreatedAt()));
    }

    public Optional<SchedulerTaskCall> findById(String id) {
        return jdbc.query("select * from la_scheduler_task_call where id = ?", this::map, id).stream().findFirst();
    }

    public List<SchedulerTaskCall> findByTask(String taskId, Instant from, Instant to, int limit) {
        StringBuilder sql = new StringBuilder("select * from la_scheduler_task_call where task_id = ?");
        List<Object> args = new java.util.ArrayList<>();
        args.add(taskId);
        if (from != null) { sql.append(" and triggered_at >= ?"); args.add(ts(from)); }
        if (to != null) { sql.append(" and triggered_at <= ?"); args.add(ts(to)); }
        sql.append(" order by triggered_at desc limit ?");
        args.add(limit);
        return jdbc.query(sql.toString(), this::map, args.toArray());
    }

    /** Paged query across a set of tasks. Returns page items; total via {@link #countByTasks}. */
    public List<SchedulerTaskCall> findPage(java.util.Set<String> taskIds, Instant from, Instant to,
                                            Boolean success, int page, int size) {
        if (taskIds == null || taskIds.isEmpty()) return List.of();
        StringBuilder sql = new StringBuilder("select * from la_scheduler_task_call where task_id in (");
        List<Object> args = new java.util.ArrayList<>();
        appendInClause(sql, args, taskIds);
        sql.append(")");
        if (from != null) { sql.append(" and triggered_at >= ?"); args.add(ts(from)); }
        if (to != null) { sql.append(" and triggered_at <= ?"); args.add(ts(to)); }
        if (success != null) { sql.append(" and success = ?"); args.add(success); }
        sql.append(" order by triggered_at desc limit ? offset ?");
        args.add(size);
        args.add((long) (page - 1) * size);
        return jdbc.query(sql.toString(), this::map, args.toArray());
    }

    public long countByTask(String taskId, Instant from, Instant to) {
        return aggregate("count(*)", taskId, from, to);
    }

    /** Query calls across a set of tasks (visible to the caller). Empty set → empty result. */
    public List<SchedulerTaskCall> findByTasks(java.util.Set<String> taskIds, Instant from, Instant to, int limit) {
        if (taskIds == null || taskIds.isEmpty()) return List.of();
        StringBuilder sql = new StringBuilder("select * from la_scheduler_task_call where task_id in (");
        List<Object> args = new java.util.ArrayList<>();
        appendInClause(sql, args, taskIds);
        sql.append(")");
        if (from != null) { sql.append(" and triggered_at >= ?"); args.add(ts(from)); }
        if (to != null) { sql.append(" and triggered_at <= ?"); args.add(ts(to)); }
        sql.append(" order by triggered_at desc limit ?");
        args.add(limit);
        return jdbc.query(sql.toString(), this::map, args.toArray());
    }

    public long countByTasks(java.util.Set<String> taskIds, Instant from, Instant to) {
        return countWhereIn(taskIds, from, to, "");
    }

    public long countSuccessByTasks(java.util.Set<String> taskIds, Instant from, Instant to) {
        return countWhereIn(taskIds, from, to, " and success = true");
    }

    private long countWhereIn(java.util.Set<String> taskIds, Instant from, Instant to, String extra) {
        if (taskIds == null || taskIds.isEmpty()) return 0;
        StringBuilder sql = new StringBuilder("select count(*) from la_scheduler_task_call where task_id in (");
        List<Object> args = new java.util.ArrayList<>();
        appendInClause(sql, args, taskIds);
        sql.append(")");
        if (from != null) { sql.append(" and triggered_at >= ?"); args.add(ts(from)); }
        if (to != null) { sql.append(" and triggered_at <= ?"); args.add(ts(to)); }
        sql.append(extra);
        Long v = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return v == null ? 0 : v;
    }

    private void appendInClause(StringBuilder sql, List<Object> args, java.util.Set<String> ids) {
        int i = 0;
        for (String id : ids) {
            if (i++ > 0) sql.append(",");
            sql.append("?");
            args.add(id);
        }
    }

    public long countSuccessByTask(String taskId, Instant from, Instant to) {
        return aggregate("count(*)", taskId, from, to, " and success = true");
    }

    /**
     * Aggregated call counts per day for the dashboard trend chart. Date bucketing is done in Java
     * (not via SQL dialect-specific date functions) so it works across H2/MySQL/PostgreSQL/etc.
     */
    public List<Map<String, Object>> dailyTrend(Instant from, Instant to) {
        StringBuilder sql = new StringBuilder("select triggered_at, success from la_scheduler_task_call where 1=1");
        List<Object> args = new java.util.ArrayList<>();
        appendRange(sql, args, from, to);
        sql.append(" order by triggered_at");
        List<Map<String, Object>> rows = jdbc.query(sql.toString(),
                (rs, rowNum) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("ts", instant(rs.getTimestamp("triggered_at")));
                    m.put("success", rs.getBoolean("success"));
                    return m;
                },
                args.toArray());
        java.util.Map<String, long[]> bucketed = new java.util.TreeMap<>();
        for (Map<String, Object> r : rows) {
            Instant ts = (Instant) r.get("ts");
            boolean success = (boolean) r.get("success");
            String bucket = ts.atZone(java.time.ZoneId.systemDefault())
                    .toLocalDate().toString(); // yyyy-MM-dd
            long[] counts = bucketed.computeIfAbsent(bucket, k -> new long[2]);
            counts[success ? 0 : 1]++;
        }
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        bucketed.forEach((bucket, counts) -> {
            out.add(trendRow(bucket, true, counts[0]));
            out.add(trendRow(bucket, false, counts[1]));
        });
        return out;
    }

    private Map<String, Object> trendRow(String bucket, boolean success, long count) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bucket", bucket);
        m.put("success", success);
        m.put("count", count);
        return m;
    }

    public Map<String, Long> totals(Instant from, Instant to) {
        long total = countWhere(from, to, "");
        long success = countWhere(from, to, " and success = true");
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("total", total);
        m.put("success", success);
        return m;
    }

    /** count(*) with an optional time window (null bounds = unbounded) and extra SQL fragment. */
    private long countWhere(Instant from, Instant to, String extra) {
        StringBuilder sql = new StringBuilder("select count(*) from la_scheduler_task_call where 1=1");
        List<Object> args = new java.util.ArrayList<>();
        appendRange(sql, args, from, to);
        sql.append(extra);
        Long v = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return v == null ? 0 : v;
    }

    private void appendRange(StringBuilder sql, List<Object> args, Instant from, Instant to) {
        if (from != null) { sql.append(" and triggered_at >= ?"); args.add(ts(from)); }
        if (to != null) { sql.append(" and triggered_at <= ?"); args.add(ts(to)); }
    }

    private long aggregate(String expr, String taskId, Instant from, Instant to) {
        return aggregate(expr, taskId, from, to, "");
    }

    private long aggregate(String expr, String taskId, Instant from, Instant to, String extra) {
        StringBuilder sql = new StringBuilder("select ").append(expr)
                .append(" from la_scheduler_task_call where task_id = ?");
        List<Object> args = new java.util.ArrayList<>();
        args.add(taskId);
        if (from != null) { sql.append(" and triggered_at >= ?"); args.add(ts(from)); }
        if (to != null) { sql.append(" and triggered_at <= ?"); args.add(ts(to)); }
        sql.append(extra);
        Long v = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return v == null ? 0 : v;
    }

    private SchedulerTaskCall map(ResultSet rs, int rowNum) throws SQLException {
        Boolean assertionPassed = rs.getObject("assertion_passed") == null ? null : rs.getBoolean("assertion_passed");
        return SchedulerTaskCall.builder()
                .id(rs.getString("id"))
                .taskId(rs.getString("task_id"))
                .triggeredAt(instant(rs.getTimestamp("triggered_at")))
                .protocol(rs.getString("protocol"))
                .method(rs.getString("method"))
                .url(rs.getString("url"))
                .tcpTarget(rs.getString("tcp_target"))
                .httpStatus((Integer) rs.getObject("http_status"))
                .tcpOk(rs.getObject("tcp_ok") == null ? null : rs.getBoolean("tcp_ok"))
                .durationMs(rs.getObject("duration_ms") == null ? null : rs.getLong("duration_ms"))
                .status(rs.getBoolean("success") ? SchedulerTaskCall.Status.SUCCESS : SchedulerTaskCall.Status.FAIL)
                .assertionPassed(assertionPassed)
                .errorMessage(rs.getString("error_message"))
                .responseExcerpt(rs.getString("response_excerpt"))
                .createdAt(instant(rs.getTimestamp("created_at")))
                .build();
    }

    private Boolean assertion(SchedulerTaskCall c) { return c.getAssertionPassed(); }

    private String truncate(String s) {
        if (s == null) return null;
        return s.length() > EXCERPT_MAX ? s.substring(0, EXCERPT_MAX) : s;
    }

    /** Light-weight masking for the persisted excerpt (keys/tokens). */
    private String mask(String s) {
        if (s == null) return null;
        return s.replaceAll("(?i)(\"(?:password|passwd|secret|token|authorization|apikey|api_key)\"\\s*:\\s*)\"[^\"]*\"",
                "$1\"***\"");
    }

    private Timestamp ts(Instant instant) { return instant == null ? null : Timestamp.from(instant); }
    private Instant instant(Timestamp ts) { return ts == null ? null : ts.toInstant(); }
}
