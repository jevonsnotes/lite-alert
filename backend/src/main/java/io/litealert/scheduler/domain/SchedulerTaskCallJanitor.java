package io.litealert.scheduler.domain;

import io.litealert.admin.settings.SystemSettings;
import io.litealert.admin.settings.SystemSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Removes scheduled-task call records older than
 * {@code SystemSettings.schedulerCallRetention}. Mirrors {@code AuditJanitor}:
 * a daily cron plus a one-shot sweep shortly after startup.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchedulerTaskCallJanitor {

    private final SchedulerTaskCallStore store;
    private final SystemSettingsService settings;

    @Scheduled(cron = "0 29 3 * * *", zone = "Asia/Shanghai")
    public void runDaily() {
        try { sweep(LocalDate.now(ZoneId.systemDefault())); }
        catch (Exception e) { log.warn("scheduler call janitor sweep failed", e); }
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = Long.MAX_VALUE)
    public void runOnce() {
        try { sweep(LocalDate.now(ZoneId.systemDefault())); }
        catch (Exception e) { log.warn("scheduler call janitor initial sweep failed", e); }
    }

    void sweep(LocalDate today) {
        SystemSettings.Span retention = settings.current().getSchedulerCallRetention();
        int removed = store.deleteBefore(retention.cutoff(today).atStartOfDay(ZoneId.systemDefault()).toInstant());
        log.info("scheduler call janitor swept; cutoff={} removed={}", retention.cutoff(today), removed);
    }
}
