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
import java.util.LinkedHashMap;
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
    public void load() {
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
        var rlAudit = new LinkedHashMap<String, Object>();
        rlAudit.put("perTopicPerMinute", incoming.getRateLimit().getPerTopicPerMinute());
        rlAudit.put("perApiKeyPerMinute", incoming.getRateLimit().getPerApiKeyPerMinute());
        rlAudit.put("perIpPerMinute", incoming.getRateLimit().getPerIpPerMinute());
        rlAudit.put("syncTimeoutSeconds", incoming.getSyncTimeoutSeconds());
        SystemSettings.TaskTargetGuardConfig tg = incoming.getTaskTargetGuard();
        var tgAudit = new LinkedHashMap<String, Object>();
        tgAudit.put("enabled", tg.isEnabled());
        tgAudit.put("blockedCidrCount", tg.getBlockedCidrs() == null ? 0 : tg.getBlockedCidrs().size());
        tgAudit.put("allowedCidrCount", tg.getAllowedCidrs() == null ? 0 : tg.getAllowedCidrs().size());
        tgAudit.put("blockedDomainCount", tg.getBlockedDomains() == null ? 0 : tg.getBlockedDomains().size());
        audit.log("system.settings.update", Map.of(
                "actor", actor,
                "auditRetention", spanToMap(incoming.getAuditRetention()),
                "deliveryRetention", spanToMap(incoming.getDeliveryRetention()),
                "schedulerCallRetention", spanToMap(incoming.getSchedulerCallRetention()),
                "dashboardDefaultTrend", spanToMap(incoming.getDashboardDefaultTrend()),
                "rateLimit", rlAudit,
                "taskTargetGuard", tgAudit));
        return incoming;
    }

    void normalize(SystemSettings s) {
        if (s.getAuditRetention() == null) s.setAuditRetention(new SystemSettings.Span(90, SystemSettings.Unit.DAYS));
        if (s.getDeliveryRetention() == null) s.setDeliveryRetention(new SystemSettings.Span(90, SystemSettings.Unit.DAYS));
        if (s.getSchedulerCallRetention() == null) s.setSchedulerCallRetention(new SystemSettings.Span(90, SystemSettings.Unit.DAYS));
        if (s.getDashboardDefaultTrend() == null) s.setDashboardDefaultTrend(new SystemSettings.Span(14, SystemSettings.Unit.DAYS));
        if (s.getRateLimit() == null) s.setRateLimit(SystemSettings.RateLimitConfig.builder().build());
        if (s.getPayloadMaskingSensitiveWords() == null) s.setPayloadMaskingSensitiveWords(SystemSettings.defaultSensitiveWords());
        if (s.getSyncTimeoutSeconds() == null || s.getSyncTimeoutSeconds() < 0) s.setSyncTimeoutSeconds(30);
        if (s.getTaskTargetGuard() == null) s.setTaskTargetGuard(new SystemSettings.TaskTargetGuardConfig());
        normalizeTaskTargetGuard(s.getTaskTargetGuard());
        SystemSettings.RateLimitConfig rl = s.getRateLimit();
        if (rl.getPerTopicPerMinute() < 1) rl.setPerTopicPerMinute(60);
        if (rl.getPerApiKeyPerMinute() < 1) rl.setPerApiKeyPerMinute(200);
        if (rl.getPerIpPerMinute() < 1) rl.setPerIpPerMinute(30);
        clampSpan(s.getAuditRetention(), 1, 3650);
        clampSpan(s.getDeliveryRetention(), 1, 3650);
        clampSpan(s.getSchedulerCallRetention(), 1, 3650);
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

    /** Validate CIDR/domain entries: drop invalid ones with a warning, dedupe and trim. */
    private void normalizeTaskTargetGuard(SystemSettings.TaskTargetGuardConfig g) {
        g.setBlockedCidrs(sanitizeCidrs(g.getBlockedCidrs(), SystemSettings.defaultBlockedCidrs()));
        g.setAllowedCidrs(sanitizeCidrs(g.getAllowedCidrs(), new java.util.ArrayList<>()));
        g.setBlockedDomains(sanitizeDomains(g.getBlockedDomains()));
    }

    private java.util.List<String> sanitizeCidrs(java.util.List<String> raw, java.util.List<String> fallback) {
        if (raw == null || raw.isEmpty()) return new java.util.ArrayList<>(fallback);
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String c : raw) {
            if (c == null) continue;
            String t = c.trim();
            if (t.isEmpty()) continue;
            if (io.litealert.scheduler.Cidr.isValid(t)) {
                out.add(t);
            } else {
                log.warn("dropping invalid task-target-guard cidr: {}", t);
            }
        }
        return new java.util.ArrayList<>(out);
    }

    private java.util.List<String> sanitizeDomains(java.util.List<String> raw) {
        if (raw == null) return new java.util.ArrayList<>();
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String d : raw) {
            if (d == null) continue;
            String t = d.trim();
            while (t.startsWith(".")) t = t.substring(1);
            if (t.isEmpty()) continue;
            if (io.litealert.scheduler.DomainSuffix.isValid(t)) {
                out.add(t);
            } else {
                log.warn("dropping invalid task-target-guard domain suffix: {}", d);
            }
        }
        return new java.util.ArrayList<>(out);
    }
}
