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
    private Span dashboardDefaultTrend = new Span(14, Unit.DAYS);
    private RateLimitConfig rateLimit = new RateLimitConfig();
    private List<String> payloadMaskingSensitiveWords = defaultSensitiveWords();

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
}
