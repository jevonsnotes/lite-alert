package io.litealert.admin.settings;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SystemSettings {

    private Span auditRetention = new Span(90, Unit.DAYS);
    private Span deliveryRetention = new Span(90, Unit.DAYS);
    /** Retention for scheduled-task call records (la_scheduler_task_call). Default 90 days. */
    private Span schedulerCallRetention = new Span(90, Unit.DAYS);
    private Span dashboardDefaultTrend = new Span(14, Unit.DAYS);
    private RateLimitConfig rateLimit = new RateLimitConfig();
    private List<String> payloadMaskingSensitiveWords = defaultSensitiveWords();
    private Integer syncTimeoutSeconds = 30;
    /** Outbound target guard for scheduled tasks (API/TCP). Default off (permit all). */
    private TaskTargetGuardConfig taskTargetGuard = new TaskTargetGuardConfig();

    public static List<String> defaultSensitiveWords() {
        return List.of("password", "passwd", "pwd", "token", "secret", "authorization",
                "apikey", "api_key", "access_key", "private_key", "credential");
    }

    public enum Unit { DAYS, MONTHS, YEARS }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Span {
        private int value;
        private Unit unit;
        public LocalDate cutoff(LocalDate today) {
            int v = Math.max(1, value);
            return switch (unit == null ? Unit.DAYS : unit) {
                case DAYS -> today.minusDays(v - 1L);
                case MONTHS -> today.minusMonths(v).plusDays(1);
                case YEARS -> today.minusYears(v).plusDays(1);
            };
        }
        public int approxDays() {
            int v = Math.max(1, value);
            return switch (unit == null ? Unit.DAYS : unit) {
                case DAYS -> v;
                case MONTHS -> v * 30;
                case YEARS -> v * 365;
            };
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class RateLimitConfig {
        @Builder.Default
        private int perTopicPerMinute = 60;
        @Builder.Default
        private int perApiKeyPerMinute = 200;
        @Builder.Default
        private int perIpPerMinute = 30;
    }

    /**
     * Outbound target guard config for scheduled tasks (API + TCP). When {@code enabled} is true,
     * targets whose resolved IP matches any {@code blockedCidrs} (and not an {@code allowedCidrs})
     * are rejected; {@code blockedDomains} are hostname-suffix matches (e.g. {@code .internal.corp}).
     * Default is disabled (permit all). CIDR/domain entries are validated on save; invalid entries
     * are dropped with a warning rather than blocking the save.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaskTargetGuardConfig {
        private boolean enabled = false;
        /** CIDR blocks to reject (e.g. 10.0.0.0/8). Defaults cover private/loopback/link-local/zero. */
        private List<String> blockedCidrs = defaultBlockedCidrs();
        /** CIDR blocks to always permit (overrides blockedCidrs). */
        private List<String> allowedCidrs = new java.util.ArrayList<>();
        /** Hostname suffix rules to reject (matched against the unresolved host, case-insensitive). */
        private List<String> blockedDomains = new java.util.ArrayList<>();
    }

    /** Built-in blocked CIDRs: IPv4 private/loopback/link-local/zero + IPv6 loopback/ULA/link-local/unspecified. */
    public static List<String> defaultBlockedCidrs() {
        return new java.util.ArrayList<>(List.of(
                // IPv4
                "10.0.0.0/8",
                "172.16.0.0/12",
                "192.168.0.0/16",
                "127.0.0.0/8",
                "169.254.0.0/16",
                "0.0.0.0/8",
                // IPv6
                "::1/128",        // loopback
                "fc00::/7",       // unique local
                "fe80::/10",      // link-local
                "::/128"));       // unspecified
    }
}
