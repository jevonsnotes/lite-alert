package io.litealert.admin.web;

import io.litealert.auth.domain.User;
import io.litealert.common.audit.AuditLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "lite-alert.data-dir=${java.io.tmpdir}/lite-alert-stats-test",
        "lite-alert.jwt.secret=01234567890123456789012345678901",
        "lite-alert.apikey.pepper=01234567890123456789012345678901",
        "lite-alert.bootstrap.admin.username=admin",
        "lite-alert.bootstrap.admin.password=$2a$10$abcdefghijklmnopqrstuu"
})
class StatsControllerTest {

    @Autowired
    private StatsController controller;

    @Autowired
    private AuditLogger auditLogger;

    @Test
    void dailyFiltersAcceptedByTopicAndApiKey() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "n/a", io.litealert.auth.CurrentUser.authoritiesFor(User.Role.ADMIN)));

        LocalDate today = LocalDate.now();
        Path file = auditLogger.fileFor(today);
        Files.createDirectories(file.getParent());
        Files.write(file, List.of(
                line("webhook.accepted", "t_1", "ak_1"),
                line("webhook.accepted", "t_1", "ak_2"),
                line("webhook.accepted", "t_2", "ak_1"),
                line("webhook.accepted", "t_public", null)
        ), StandardCharsets.UTF_8);

        Map<String, Object> byTopic = controller.daily(1, "DAYS", "TOPIC", "t_1", null);
        Map<String, Object> byKey = controller.daily(1, "DAYS", "APIKEY", null, "ak_1");

        assertThat((List<Long>) byTopic.get("accepted")).containsExactly(2L);
        assertThat((List<Long>) byKey.get("accepted")).containsExactly(2L);
        assertThat(byTopic.get("dimension")).isEqualTo("TOPIC");
        assertThat(byKey.get("dimension")).isEqualTo("APIKEY");
    }

    @Test
    void rankingReturnsTopTopicsAndApiKeysByAcceptedCount() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "n/a", io.litealert.auth.CurrentUser.authoritiesFor(User.Role.ADMIN)));

        LocalDate today = LocalDate.now();
        Path file = auditLogger.fileFor(today);
        Files.createDirectories(file.getParent());
        Files.write(file, List.of(
                line("webhook.accepted", "t_1", "ak_1"),
                line("webhook.accepted", "t_1", "ak_1"),
                line("webhook.accepted", "t_2", "ak_2"),
                line("webhook.accepted", "t_public", null)
        ), StandardCharsets.UTF_8);

        Map<String, Object> topics = controller.ranking(1, "DAYS", "TOPIC", 10);
        Map<String, Object> apiKeys = controller.ranking(1, "DAYS", "APIKEY", 10);

        assertThat((List<String>) topics.get("labels")).containsExactly("t_1", "t_2", "t_public");
        assertThat((List<Long>) topics.get("accepted")).containsExactly(2L, 1L, 1L);
        assertThat((List<String>) apiKeys.get("labels")).containsExactly("ak_1", "ak_2");
        assertThat((List<Long>) apiKeys.get("accepted")).containsExactly(2L, 1L);
    }

    private String line(String type, String topicId, String apiKeyId) {
        StringBuilder b = new StringBuilder("{\"ts\":\"")
                .append(LocalDate.now()).append("T12:00:00Z\",\"type\":\"").append(type).append("\"")
                .append(",\"topicId\":\"").append(topicId).append("\"");
        if (apiKeyId != null) b.append(",\"apiKeyId\":\"").append(apiKeyId).append("\"");
        return b.append("}").toString();
    }
}
