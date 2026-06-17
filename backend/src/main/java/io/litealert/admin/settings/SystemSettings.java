package io.litealert.admin.settings;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Operator-tunable runtime settings, persisted to {@code system-settings.json}.
 * Defaults come from {@code lite-alert.*} in application.yml; runtime edits
 * win after that.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SystemSettings {

    /** How long to keep audit log files. */
    private Span auditRetention = new Span(90, Unit.DAYS);

    /** Initial trend-window the dashboard chart shows. */
    private Span dashboardDefaultTrend = new Span(14, Unit.DAYS);

    public enum Unit { DAYS, MONTHS, YEARS }

    /** Composable "last N units" duration. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Span {

        private int value;
        private Unit unit;

        /** Cutoff date when this span ends today (inclusive of today). */
        public LocalDate cutoff(LocalDate today) {
            int v = Math.max(1, value);
            return switch (unit == null ? Unit.DAYS : unit) {
                case DAYS   -> today.minusDays(v - 1L);
                case MONTHS -> today.minusMonths(v).plusDays(1);
                case YEARS  -> today.minusYears(v).plusDays(1);
            };
        }

        /** Approximate count of days for sizing day-grain charts. */
        public int approxDays() {
            int v = Math.max(1, value);
            return switch (unit == null ? Unit.DAYS : unit) {
                case DAYS   -> v;
                case MONTHS -> v * 30;
                case YEARS  -> v * 365;
            };
        }
    }
}
