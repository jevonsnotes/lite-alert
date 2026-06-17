package io.litealert.common.audit;

import io.litealert.admin.settings.SystemSettings;
import io.litealert.admin.settings.SystemSettingsService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.stream.Stream;

/**
 * Removes audit files older than {@code SystemSettings.auditRetention}.
 * Runs once at boot (after a 60s grace period to let the admin tweak the
 * setting first if they noticed something off) and then daily at 03:17.
 *
 * <p>Off-hour scheduling and odd minute keep the cleanup from competing
 * with hourly cron tasks operators commonly run.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableScheduling
public class AuditJanitor {

    private final AuditLogger auditLogger;
    private final SystemSettingsService settings;

    @PostConstruct
    void primed() {
        // First sweep happens after the @Scheduled fires; logging here just
        // confirms the bean wired correctly during startup investigations.
        log.info("audit janitor armed; daily run at 03:17 local time");
    }

    @Scheduled(cron = "0 17 3 * * *", zone = "Asia/Shanghai")
    public void runDaily() {
        try {
            sweep();
        } catch (Exception e) {
            log.warn("audit janitor sweep failed", e);
        }
    }

    /** Boot-time sweep, slightly delayed so settings have a chance to load. */
    @Scheduled(initialDelay = 60_000, fixedDelay = Long.MAX_VALUE)
    public void runOnce() {
        try {
            sweep();
        } catch (Exception e) {
            log.warn("audit janitor initial sweep failed", e);
        }
    }

    void sweep() throws IOException {
        SystemSettings.Span retention = settings.current().getAuditRetention();
        Path dir = auditLogger.dir();
        if (!Files.isDirectory(dir)) return;
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        LocalDate cutoff = retention.cutoff(today);
        int kept = 0, removed = 0;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                String name = p.getFileName().toString();
                if (!name.endsWith(".log")) continue;
                LocalDate fileDate = parseFileDate(name);
                if (fileDate == null) continue;
                if (fileDate.isBefore(cutoff)) {
                    try { Files.deleteIfExists(p); removed++; }
                    catch (IOException e) { log.warn("delete failed: {}", p, e); }
                } else {
                    kept++;
                }
            }
        }
        log.info("audit janitor swept; cutoff={} kept={} removed={}", cutoff, kept, removed);
    }

    /** Returns the date encoded in {@code yyyy-MM-dd.log}, or null on mismatch. */
    private LocalDate parseFileDate(String name) {
        String stem = name.substring(0, name.length() - ".log".length());
        try {
            return LocalDate.parse(stem, AuditLogger.FILE_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
