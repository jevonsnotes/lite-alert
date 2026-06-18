package io.litealert.admin.settings;

import com.fasterxml.jackson.core.type.TypeReference;
import io.litealert.common.audit.AuditLogger;
import io.litealert.common.db.DbJson;
import io.litealert.common.error.BusinessException;
import io.litealert.common.error.ErrorCode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class SystemSettingsService {

    private static final String ID = "default";

    private final JdbcTemplate jdbc;
    private final DbJson json;
    private final AuditLogger audit;

    private final AtomicReference<SystemSettings> ref =
            new AtomicReference<>(new SystemSettings());

    @PostConstruct
    void load() {
        try {
            String stored = jdbc.query("select settings_json from la_system_settings where id = ?",
                    rs -> rs.next() ? rs.getString(1) : null, ID);
            if (stored != null) {
                SystemSettings settings = json.read(stored, new TypeReference<>() {}, new SystemSettings());
                normalize(settings);
                ref.set(settings);
                log.info("loaded system settings from database");
            }
        } catch (Exception ignored) {
            // DatabaseInitializer may not have run yet in early context setup; defaults are safe.
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
        boolean exists = Boolean.TRUE.equals(jdbc.query("select count(*) from la_system_settings where id = ?",
                rs -> rs.next() && rs.getInt(1) > 0, ID));
        if (exists) {
            jdbc.update("update la_system_settings set settings_json=?, updated_at=? where id=?",
                    json.write(incoming), Timestamp.from(Instant.now()), ID);
        } else {
            jdbc.update("insert into la_system_settings(id, settings_json, updated_at) values (?, ?, ?)",
                    ID, json.write(incoming), Timestamp.from(Instant.now()));
        }
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
        int approx = span.approxDays();
        if (approx > maxDays) {
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
