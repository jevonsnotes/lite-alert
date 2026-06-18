package io.litealert.common.audit;

import io.litealert.admin.settings.SystemSettings;
import io.litealert.admin.settings.SystemSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;

/** Removes audit rows older than {@code SystemSettings.auditRetention}. */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableScheduling
public class AuditJanitor {

    private final JdbcTemplate jdbc;
    private final SystemSettingsService settings;

    @Scheduled(cron = "0 17 3 * * *", zone = "Asia/Shanghai")
    public void runDaily() {
        try { sweep(); }
        catch (Exception e) { log.warn("audit janitor sweep failed", e); }
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = Long.MAX_VALUE)
    public void runOnce() {
        try { sweep(); }
        catch (Exception e) { log.warn("audit janitor initial sweep failed", e); }
    }

    void sweep() {
        SystemSettings.Span retention = settings.current().getAuditRetention();
        LocalDate cutoff = retention.cutoff(LocalDate.now(ZoneId.systemDefault()));
        int removed = jdbc.update("delete from la_audit_log where ts < ?", Timestamp.valueOf(cutoff.atStartOfDay()));
        log.info("audit janitor swept; cutoff={} removed={}", cutoff, removed);
    }
}
