package io.litealert.admin.stats;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class StatsService {

    private final JdbcTemplate jdbc;

    public StatsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns per-topic summary stats: accepted, sent, failed, pending, retryWait.
     */
    public Map<String, TopicSummary> topicSummary(List<String> topicIds) {
        if (topicIds == null || topicIds.isEmpty()) return Map.of();

        Map<String, TopicSummary> stats = topicIds.stream().collect(
                Collectors.toMap(id -> id, TopicSummary::new));

        // Build IN clause with placeholders
        String inClause = String.join(",", topicIds.stream().map(id -> "?").toList());
        Object[] args = topicIds.toArray();

        // Audit counts: now uses indexed topic_id column + GROUP BY
        jdbc.query("select topic_id, type, count(*) as cnt from la_audit_log " +
                        "where topic_id in (" + inClause + ") " +
                        "and type in ('webhook.accepted','notify.sent','notify.failed','notify.give_up') " +
                        "group by topic_id, type",
                args, (rs, rowNum) -> {
                    String tid = rs.getString("topic_id");
                    String type = rs.getString("type");
                    long cnt = rs.getLong("cnt");
                    TopicSummary s = stats.computeIfAbsent(tid, TopicSummary::new);
                    if ("webhook.accepted".equals(type)) s.setAccepted(s.getAccepted() + cnt);
                    else if ("notify.sent".equals(type)) s.setSent(s.getSent() + cnt);
                    else if ("notify.failed".equals(type) || "notify.give_up".equals(type)) s.setFailed(s.getFailed() + cnt);
                    return 0;
                });

        // Delivery counts: topic_id is a direct column
        jdbc.query("select topic_id, status, count(*) as cnt from la_notify_delivery where topic_id in (" + inClause + ") group by topic_id, status",
                args, (rs, rowNum) -> {
                    String tid = rs.getString("topic_id");
                    String status = rs.getString("status");
                    long cnt = rs.getLong("cnt");
                    TopicSummary s = stats.computeIfAbsent(tid, TopicSummary::new);
                    if ("PENDING".equals(status)) s.setPending(s.getPending() + cnt);
                    else if ("RETRY_WAIT".equals(status)) s.setRetryWait(s.getRetryWait() + cnt);
                    return 0;
                });

        return stats;
    }

    public static class TopicSummary {
        private final String topicId;
        private long accepted;
        private long sent;
        private long failed;
        private long pending;
        private long retryWait;

        public TopicSummary(String topicId) { this.topicId = topicId; }

        public String getTopicId() { return topicId; }
        public long getAccepted() { return accepted; }
        public void setAccepted(long accepted) { this.accepted = accepted; }
        public long getSent() { return sent; }
        public void setSent(long sent) { this.sent = sent; }
        public long getFailed() { return failed; }
        public void setFailed(long failed) { this.failed = failed; }
        public long getPending() { return pending; }
        public void setPending(long pending) { this.pending = pending; }
        public long getRetryWait() { return retryWait; }
        public void setRetryWait(long retryWait) { this.retryWait = retryWait; }
    }
}
