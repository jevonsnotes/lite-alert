package io.litealert.admin.settings;

import com.fasterxml.jackson.core.type.TypeReference;
import io.litealert.common.audit.AuditLogger;
import io.litealert.common.error.BusinessException;
import io.litealert.common.error.ErrorCode;
import io.litealert.common.storage.FileStore;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single source of truth for runtime-tunable settings. Loaded from
 * {@code system-settings.json} on boot; absent file → defaults.
 *
 * <p>Other beans (AuditLogger janitor, AuditController, StatsController, …)
 * read via {@link #current()} so a save here is immediately visible
 * everywhere without restarts.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemSettingsService {

    public static final String FILE = "system-settings.json";

    private final FileStore fileStore;
    private final AuditLogger audit;

    private final AtomicReference<SystemSettings> ref =
            new AtomicReference<>(new SystemSettings());

    @PostConstruct
    void load() {
        SystemSettings stored = fileStore.readJson(FILE,
                new TypeReference<>() {}, null);
        if (stored != null) {
            normalize(stored);
            ref.set(stored);
            log.info("loaded system settings from {}", FILE);
        }
    }

    public SystemSettings current() {
        return ref.get();
    }

    public synchronized SystemSettings save(SystemSettings incoming, String actor) {
        if (incoming == null) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "settings body required");
        }
        normalize(incoming);
        fileStore.writeJson(FILE, incoming);
        ref.set(incoming);
        audit.log("system.settings.update", Map.of(
                "actor", actor,
                "auditRetention", spanToMap(incoming.getAuditRetention()),
                "dashboardDefaultTrend", spanToMap(incoming.getDashboardDefaultTrend())));
        return incoming;
    }

    private void normalize(SystemSettings s) {
        if (s.getAuditRetention() == null) s.setAuditRetention(new SystemSettings.Span(90, SystemSettings.Unit.DAYS));
        if (s.getDashboardDefaultTrend() == null) s.setDashboardDefaultTrend(new SystemSettings.Span(14, SystemSettings.Unit.DAYS));
        clampSpan(s.getAuditRetention(), 1, 3650);
        clampSpan(s.getDashboardDefaultTrend(), 1, 365);
    }

    private void clampSpan(SystemSettings.Span span, int minDays, int maxDays) {
        if (span.getUnit() == null) span.setUnit(SystemSettings.Unit.DAYS);
        if (span.getValue() < 1) span.setValue(1);
        // upper bound only matters as a sanity cap so we don't accumulate
        // 100 years of audit files because somebody fat-fingered a value.
        int approx = span.approxDays();
        if (approx > maxDays) {
            // shrink while preserving unit when reasonable
            span.setValue(switch (span.getUnit()) {
                case DAYS   -> maxDays;
                case MONTHS -> Math.max(1, maxDays / 30);
                case YEARS  -> Math.max(1, maxDays / 365);
            });
        }
        if (approx < minDays) span.setValue(minDays);
    }

    private Map<String, Object> spanToMap(SystemSettings.Span s) {
        if (s == null) return Map.of();
        return Map.of("value", s.getValue(),
                "unit", s.getUnit() == null ? "DAYS" : s.getUnit().name());
    }
}
