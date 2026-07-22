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

    /** Delete call records older than {@code cutoff}. Used by the retention janitor. Returns rows removed. */
    public int deleteBefore(Instant cutoff) {
        return jdbc.update("delete from la_scheduler_task_call where triggered_at < ?", ts(cutoff));
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
     * Calls-scoped daily trend: per-day success/fail counts across a set of visible task ids.
     * Empty set -> empty list. Date bucketing is done in Java (cross-dialect safe), mirroring
     * {@link #dailyTrend(Instant, Instant)} but filtered to {@code taskIds}.
     */
    public List<Map<String, Object>> dailyTrend(java.util.Set<String> taskIds, Instant from, Instant to) {
        if (taskIds == null || taskIds.isEmpty()) return List.of();
        StringBuilder sql = new StringBuilder("select triggered_at, success from la_scheduler_task_call where 1=1");
        List<Object> args = new java.util.ArrayList<>();
        sql.append(" and task_id in (");
        appendInClause(sql, args, taskIds);
        sql.append(")");
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

    /** Totals scoped to a set of visible task ids (calls-scoped stats). Empty set -> zero values. */
    public Map<String, Long> totals(java.util.Set<String> taskIds, Instant from, Instant to) {
        long total = countWhereIn(taskIds, from, to, "");
        long success = countWhereIn(taskIds, from, to, " and success = true");
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("total", total);
        m.put("success", success);
        return m;
    }

    /**
     * Multi-dimension breakdown for the sankey chart: cross-tabulated call counts across 6
     * dimensions (owner / taskName / taskType / status / assertion / result), scoped to a set of
     * visible task ids. Joins {@code la_scheduler_task} (name/owner_id/task_type) and
     * {@code la_user} (username). Labels for status/assertion/result are mapped in Java so the SQL
     * stays cross-dialect (mirrors {@link #dailyTrend} Java-bucketing approach).
     *
     * <p>Returns two views: {@code rows} (grouped 6-dim counts, with task names beyond {@code limit}
     * folded into "其他") and {@code taskTotals} (per-task total counts, desc, untruncated).
     * Empty {@code taskIds} -> empty result.
     */
    public Map<String, Object> breakdown(java.util.Set<String> taskIds, Instant from, Instant to, int limit) {
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("rows", List.of());
        empty.put("taskTotals", List.of());
        empty.put("taskCount", 0);
        if (taskIds == null || taskIds.isEmpty()) return empty;

        // 1) per-task totals (name + count), desc -> drives Top X selection
        StringBuilder totalsSql = new StringBuilder(
                "select t.name as task_name, count(*) as cnt from la_scheduler_task_call c " +
                        "join la_scheduler_task t on t.id = c.task_id where c.task_id in (");
        List<Object> totalsArgs = new java.util.ArrayList<>();
        appendInClause(totalsSql, totalsArgs, taskIds);
        totalsSql.append(")");
        appendRangeAlias(totalsSql, totalsArgs, from, to);
        totalsSql.append(" group by t.name order by cnt desc, t.name");
        List<Map<String, Object>> taskTotals = jdbc.query(totalsSql.toString(),
                (rs, rowNum) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("taskName", rs.getString("task_name"));
                    m.put("count", rs.getLong("cnt"));
                    return m;
                }, totalsArgs.toArray());

        // Top X task names; everything else folds into "其他"
        int cap = Math.max(1, limit);
        java.util.Set<String> topNames = new java.util.LinkedHashSet<>();
        for (int i = 0; i < Math.min(cap, taskTotals.size()); i++) {
            topNames.add((String) taskTotals.get(i).get("taskName"));
        }

        // 2) 6-dim cross distribution. We fetch raw dimensions (joined) and map status/assertion/result
        //    labels in Java. Task name already folded to "其他" here for the rows payload.
        StringBuilder sql = new StringBuilder(
                "select u.username as owner, t.name as task_name, t.task_type as task_type, " +
                        "c.protocol as protocol, c.http_status as http_status, c.tcp_ok as tcp_ok, " +
                        "c.assertion_passed as assertion_passed, c.success as success, count(*) as cnt " +
                        "from la_scheduler_task_call c " +
                        "join la_scheduler_task t on t.id = c.task_id " +
                        "left join la_user u on u.id = t.owner_id " +
                        "where c.task_id in (");
        List<Object> args = new java.util.ArrayList<>();
        appendInClause(sql, args, taskIds);
        sql.append(")");
        appendRangeAlias(sql, args, from, to);
        sql.append(" group by u.username, t.name, t.task_type, c.protocol, c.http_status, c.tcp_ok, " +
                "c.assertion_passed, c.success order by cnt desc");
        List<Map<String, Object>> rows = jdbc.query(sql.toString(),
                (rs, rowNum) -> {
                    String taskName = rs.getString("task_name");
                    String protocol = rs.getString("protocol");
                    Integer httpStatus = (Integer) rs.getObject("http_status");
                    Boolean tcpOk = rs.getObject("tcp_ok") == null ? null : rs.getBoolean("tcp_ok");
                    Boolean assertionPassed = rs.getObject("assertion_passed") == null ? null : rs.getBoolean("assertion_passed");
                    boolean success = rs.getBoolean("success");
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("owner", rs.getString("owner"));
                    m.put("taskName", topNames.contains(taskName) ? taskName : "其他");
                    m.put("taskType", rs.getString("task_type"));
                    m.put("status", statusLabel(protocol, httpStatus, tcpOk));
                    m.put("assertion", assertionLabel(assertionPassed));
                    m.put("result", success ? "成功" : "失败");
                    m.put("count", rs.getLong("cnt"));
                    return m;
                }, args.toArray());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("taskTotals", taskTotals);
        out.put("taskCount", taskTotals.size());
        return out;
    }

    private String statusLabel(String protocol, Integer httpStatus, Boolean tcpOk) {
        if ("TCP".equalsIgnoreCase(protocol)) {
            if (tcpOk == null) return "未连接";
            return tcpOk ? "已连接" : "未连接";
        }
        // API
        if (httpStatus == null) return "无响应";
        return String.valueOf(httpStatus);
    }

    private String assertionLabel(Boolean assertionPassed) {
        if (assertionPassed == null) return "无断言";
        return assertionPassed ? "通过" : "失败";
    }

    /** Range filter aliased to the call table {@code c} (used inside joined breakdown queries). */
    private void appendRangeAlias(StringBuilder sql, List<Object> args, Instant from, Instant to) {
        if (from != null) { sql.append(" and c.triggered_at >= ?"); args.add(ts(from)); }
        if (to != null) { sql.append(" and c.triggered_at <= ?"); args.add(ts(to)); }
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
