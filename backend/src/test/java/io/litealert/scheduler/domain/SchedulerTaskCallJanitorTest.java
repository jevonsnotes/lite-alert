package io.litealert.scheduler.domain;

import io.litealert.admin.settings.SystemSettings;
import io.litealert.admin.settings.SystemSettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the scheduled-task call-record janitor reads {@code schedulerCallRetention}
 * on every sweep and deletes records older than the cutoff. Mirrors the contract of
 * {@code NotifyDeliveryJanitorTest}.
 */
class SchedulerTaskCallJanitorTest {

    @Test
    void readsRetentionOnEverySweep() {
        SchedulerTaskCallStore store = newStoreWithTwoRows();
        SystemSettingsService settings = mock(SystemSettingsService.class);
        SystemSettings seven = new SystemSettings();
        seven.setSchedulerCallRetention(new SystemSettings.Span(7, SystemSettings.Unit.DAYS));
        when(settings.current()).thenReturn(seven);

        SchedulerTaskCallJanitor janitor = new SchedulerTaskCallJanitor(store, settings);
        janitor.sweep(LocalDate.of(2026, 6, 19));

        // 7-day cutoff from 2026-06-19 -> 2026-06-13; the older row (2026-06-01) is purged, the recent one kept
        long remaining = store.countByTasks(java.util.Set.of("st_old", "st_new"), null, null);
        assertThat(remaining).isEqualTo(1L);
    }

    @Test
    void usesLatestRetentionWhenConfigChanges() {
        SchedulerTaskCallStore store = newStoreWithTwoRows();
        SystemSettingsService settings = mock(SystemSettingsService.class);
        SystemSettings wide = new SystemSettings();
        wide.setSchedulerCallRetention(new SystemSettings.Span(365, SystemSettings.Unit.DAYS));
        when(settings.current()).thenReturn(wide);

        SchedulerTaskCallJanitor janitor = new SchedulerTaskCallJanitor(store, settings);
        janitor.sweep(LocalDate.of(2026, 6, 19));

        // 365-day cutoff keeps both rows
        long remaining = store.countByTasks(java.util.Set.of("st_old", "st_new"), null, null);
        assertThat(remaining).isEqualTo(2L);
    }

    private SchedulerTaskCallStore newStoreWithTwoRows() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:sched_call_janitor_test;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("drop table if exists la_scheduler_task_call");
        jdbc.execute("create table la_scheduler_task_call (" +
                "id varchar(64) primary key, task_id varchar(64), triggered_at timestamp, protocol varchar(8), " +
                "method varchar(16), url varchar(1024), tcp_target varchar(256), http_status int, tcp_ok boolean, " +
                "duration_ms bigint, success boolean, assertion_passed boolean, " +
                "error_message clob, response_excerpt clob, created_at timestamp)");
        SchedulerTaskCallStore store = new SchedulerTaskCallStore(jdbc);
        store.insert(SchedulerTaskCall.builder()
                .id("c_old").taskId("st_old").triggeredAt(Instant.parse("2026-06-01T10:00:00Z"))
                .protocol("API").httpStatus(200).durationMs(5L).status(SchedulerTaskCall.Status.SUCCESS)
                .createdAt(Instant.parse("2026-06-01T10:00:00Z")).build());
        store.insert(SchedulerTaskCall.builder()
                .id("c_new").taskId("st_new").triggeredAt(Instant.parse("2026-06-17T10:00:00Z"))
                .protocol("API").httpStatus(200).durationMs(5L).status(SchedulerTaskCall.Status.SUCCESS)
                .createdAt(Instant.parse("2026-06-17T10:00:00Z")).build());
        return store;
    }
}
