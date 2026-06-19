package io.litealert.admin.stats;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class StatsService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public StatsService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
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

        // Audit counts: topic_id is in attrs_json, not a direct column.
        // We query relevant audit rows and filter in Java.
        jdbc.query("select type, attrs_json from la_audit_log where type in ('webhook.accepted','notify.sent','notify.failed','notify.give_up')",
                rs -> {
                    String type = rs.getString("type");
                    String attrsJson = rs.getString("attrs_json");
                    String tid = extractTopicId(attrsJson);
                    if (tid != null && stats.containsKey(tid)) {
                        TopicSummary s = stats.get(tid);
                        if ("webhook.accepted".equals(type)) s.setAccepted(s.getAccepted() + 1);
                        else if ("notify.sent".equals(type)) s.setSent(s.getSent() + 1);
                        else if ("notify.failed".equals(type) || "notify.give_up".equals(type)) s.setFailed(s.getFailed() + 1);
                    }
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

    private String extractTopicId(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode tid = node.get("topicId");
            return tid == null ? null : tid.asText();
        } catch (Exception e) {
            return null;
        }
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
