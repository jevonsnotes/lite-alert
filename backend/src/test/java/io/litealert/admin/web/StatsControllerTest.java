package io.litealert.admin.web;

import io.litealert.auth.domain.User;
import io.litealert.common.audit.AuditLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "lite-alert.database.type=h2",
        "spring.datasource.url=jdbc:h2:mem:stats_test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "lite-alert.jwt.secret=01234567890123456789012345678901",
        "lite-alert.apikey.pepper=01234567890123456789012345678901",
        "lite-alert.bootstrap.admin.username=admin",
        "lite-alert.bootstrap.admin.password=admin123"
})
class StatsControllerTest {

    @Autowired
    private StatsController controller;

    @Autowired
    private AuditLogger auditLogger;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "n/a", io.litealert.auth.CurrentUser.authoritiesFor(User.Role.ADMIN)));
        jdbc.update("delete from la_audit_log");
        jdbc.update("delete from la_api_key");
        jdbc.update("delete from la_topic");
        jdbc.update("delete from la_namespace");
    }

    @Test
    void dailyFiltersAcceptedByTopicAndApiKey() {
        log("webhook.accepted", "t_1", "ak_1");
        log("webhook.accepted", "t_1", "ak_2");
        log("webhook.accepted", "t_2", "ak_1");
        log("webhook.accepted", "t_public", null);

        Map<String, Object> byTopic = controller.daily(1, "DAYS", "TOPIC", "t_1", null);
        Map<String, Object> byKey = controller.daily(1, "DAYS", "APIKEY", null, "ak_1");

        assertThat((List<Long>) byTopic.get("accepted")).containsExactly(2L);
        assertThat((List<Long>) byKey.get("accepted")).containsExactly(2L);
        assertThat(byTopic.get("dimension")).isEqualTo("TOPIC");
        assertThat(byKey.get("dimension")).isEqualTo("APIKEY");
    }

    @Test
    void rankingReturnsTopTopicsAndApiKeysByAcceptedCount() {
        log("webhook.accepted", "t_1", "ak_1");
        log("webhook.accepted", "t_1", "ak_1");
        log("webhook.accepted", "t_2", "ak_2");
        log("webhook.accepted", "t_public", null);

        Map<String, Object> topics = controller.ranking(1, "DAYS", "TOPIC", 10, null, null);
        Map<String, Object> apiKeys = controller.ranking(1, "DAYS", "APIKEY", 10, null, null);

        assertThat((List<String>) topics.get("labels")).containsExactly("t_1", "t_2", "t_public");
        assertThat((List<Long>) topics.get("accepted")).containsExactly(2L, 1L, 1L);
        assertThat((List<String>) apiKeys.get("labels")).containsExactly("ak_1", "ak_2");
        assertThat((List<Long>) apiKeys.get("accepted")).containsExactly(2L, 1L);
    }

    @Test
    void rankingReturnsReadableTopicLabelsAndFallsBackToIdWhenTopicMissing() {
        jdbc.update("insert into la_namespace(id, name, owner_id, status, created_at) values ('ns_1', 'orders', 'u_admin', 'ACTIVE', current_timestamp)");
        jdbc.update("insert into la_topic(id, namespace_id, namespace_name, name, owner_id, status, created_at) values ('t_1', 'ns_1', 'orders', 'paid', 'u_admin', 'PUBLISHED', current_timestamp)");
        log("webhook.accepted", "t_1", "ak_1");
        log("webhook.accepted", "t_missing", "ak_2");

        Map<String, Object> topics = controller.ranking(1, "DAYS", "TOPIC", 10, null, null);

        assertThat((List<String>) topics.get("labels")).containsExactly("orders/paid", "t_missing");
    }

    @Test
    void rankingCanReturnSelectedTopicOutsideTopListWithZeroCounters() {
        jdbc.update("insert into la_namespace(id, name, owner_id, status, created_at) values ('ns_1', 'orders', 'u_admin', 'ACTIVE', current_timestamp)");
        jdbc.update("insert into la_topic(id, namespace_id, namespace_name, name, owner_id, status, created_at) values ('t_1', 'ns_1', 'orders', 'paid', 'u_admin', 'PUBLISHED', current_timestamp)");
        for (int i = 0; i < 12; i++) log("webhook.accepted", "t_top_" + i, "ak_top");

        Map<String, Object> topics = controller.ranking(1, "DAYS", "TOPIC", 10, "t_1", null);

        assertThat((List<String>) topics.get("labels")).containsExactly("orders/paid");
        assertThat((List<Long>) topics.get("accepted")).containsExactly(0L);
        assertThat((List<Long>) topics.get("sent")).containsExactly(0L);
        assertThat((List<Long>) topics.get("failed")).containsExactly(0L);
    }

    @Test
    void rankingCanReturnSelectedApiKeyOutsideTopListWithZeroCounters() {
        jdbc.update("insert into la_api_key(id, owner_id, name, prefix, key_hash, scopes_json, status, created_at, usage_count, rotate_count) values ('ak_1', 'u_admin', 'orders-key', 'LAK00001', 'hash', '[]', 'ACTIVE', current_timestamp, 0, 0)");
        for (int i = 0; i < 12; i++) log("webhook.accepted", "t_top", "ak_top_" + i);

        Map<String, Object> apiKeys = controller.ranking(1, "DAYS", "APIKEY", 10, null, "ak_1");

        assertThat((List<String>) apiKeys.get("labels")).containsExactly("orders-key (LAK00001••••)");
        assertThat((List<Long>) apiKeys.get("accepted")).containsExactly(0L);
        assertThat((List<Long>) apiKeys.get("sent")).containsExactly(0L);
        assertThat((List<Long>) apiKeys.get("failed")).containsExactly(0L);
    }

    private void log(String type, String topicId, String apiKeyId) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("topicId", topicId);
        if (apiKeyId != null) attrs.put("apiKeyId", apiKeyId);
        auditLogger.log(type, attrs);
    }
}
