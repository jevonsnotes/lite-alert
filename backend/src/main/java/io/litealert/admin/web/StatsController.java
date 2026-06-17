package io.litealert.admin.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.litealert.admin.settings.SystemSettings;
import io.litealert.admin.settings.SystemSettingsService;
import io.litealert.auth.CurrentUser;
import io.litealert.common.audit.AuditLogger;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Aggregates the audit log into daily buckets for the dashboard chart.
 *
 * <p>The window is whatever the caller passes via {@code value} + {@code unit}
 * (1–3650 days). With no params, the configured
 * {@link SystemSettings#getDashboardDefaultTrend} applies.
 *
 * <p>For lite-alert's expected scale (≤ a few thousand events/day) the
 * naive scan-and-count works fine; if it ever doesn't, swap to a small
 * background materializer that writes daily snapshot files.
 */
@RestController
@RequestMapping("/api/admin/stats")
@RequiredArgsConstructor
public class StatsController {

    private final CurrentUser currentUser;
    private final SystemSettingsService settingsService;
    private final AuditLogger auditLogger;

    @Qualifier("storeObjectMapper")
    private final ObjectMapper mapper;

    @GetMapping("/daily")
    public Map<String, Object> daily(
            @RequestParam(required = false) Integer value,
            @RequestParam(required = false) String unit,
            @RequestParam(required = false, defaultValue = "OVERALL") String dimension,
            @RequestParam(required = false) String topicId,
            @RequestParam(required = false) String apiKeyId) throws IOException {

        currentUser.requireAdmin();

        Dimension dim = resolveDimension(dimension);
        SystemSettings.Span span = resolveSpan(value, unit);
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = ZonedDateTime.now(zone).toLocalDate();
        LocalDate from = span.cutoff(today);
        // cap days to keep the response shape sane on huge ranges
        int days = (int) Math.min(3650, today.toEpochDay() - from.toEpochDay() + 1);
        if (days > 365) {
            // collapse anything beyond a year to ≤ 365 day-points; the chart
            // becomes weekly/monthly buckets in a future iteration. For now
            // we just clamp the from-date.
            from = today.minusDays(364);
            days = 365;
        }

        Map<String, Counters> buckets = new LinkedHashMap<>();
        for (int i = 0; i < days; i++) {
            buckets.put(from.plusDays(i).toString(), new Counters());
        }

        for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) {
            Path file = auditLogger.fileFor(d);
            if (!Files.exists(file)) continue;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> obj = mapper.readValue(line, Map.class);
                    String ts = (String) obj.get("ts");
                    String type = (String) obj.get("type");
                    if (ts == null || type == null) continue;
                    LocalDate day;
                    try {
                        day = ZonedDateTime.parse(ts).withZoneSameInstant(zone).toLocalDate();
                    } catch (DateTimeParseException e) {
                        continue;
                    }
                    Counters c = buckets.get(day.toString());
                    if (c == null || !matchesDimension(obj, dim, topicId, apiKeyId)) continue;
                    if ("webhook.accepted".equals(type)) c.accepted++;
                    else if ("notify.sent".equals(type)) c.sent++;
                    else if ("notify.failed".equals(type) || "notify.give_up".equals(type)) c.failed++;
                } catch (Exception ignored) {}
            }
        }

        Map<String, Object> r = result(buckets);
        r.put("from", from.toString());
        r.put("to", today.toString());
        r.put("span", Map.of("value", span.getValue(), "unit", span.getUnit().name()));
        r.put("dimension", dim.name());
        if (topicId != null && !topicId.isBlank()) r.put("topicId", topicId);
        if (apiKeyId != null && !apiKeyId.isBlank()) r.put("apiKeyId", apiKeyId);
        return r;
    }

    @GetMapping("/ranking")
    public Map<String, Object> ranking(
            @RequestParam(required = false) Integer value,
            @RequestParam(required = false) String unit,
            @RequestParam(required = false, defaultValue = "TOPIC") String dimension,
            @RequestParam(required = false, defaultValue = "10") Integer limit) throws IOException {

        currentUser.requireAdmin();

        Dimension dim = resolveDimension(dimension);
        if (dim == Dimension.OVERALL) dim = Dimension.TOPIC;
        final Dimension rankingDimension = dim;
        SystemSettings.Span span = resolveSpan(value, unit);
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = ZonedDateTime.now(zone).toLocalDate();
        LocalDate from = span.cutoff(today);
        if (today.toEpochDay() - from.toEpochDay() + 1 > 365) {
            from = today.minusDays(364);
        }

        Map<String, Counters> counters = new HashMap<>();
        forEachAuditEvent(from, today, zone, obj -> {
            Object key = rankingDimension == Dimension.TOPIC ? obj.get("topicId") : obj.get("apiKeyId");
            if (!(key instanceof String s) || s.isBlank()) return;
            Counters c = counters.computeIfAbsent(s, ignored -> new Counters());
            String type = (String) obj.get("type");
            if ("webhook.accepted".equals(type)) c.accepted++;
            else if ("notify.sent".equals(type)) c.sent++;
            else if ("notify.failed".equals(type) || "notify.give_up".equals(type)) c.failed++;
        });

        int cap = Math.max(1, Math.min(limit == null ? 10 : limit, 50));
        List<Map.Entry<String, Counters>> top = counters.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Counters>>comparingLong(e -> e.getValue().accepted).reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(cap)
                .toList();

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("dimension", dim.name());
        r.put("from", from.toString());
        r.put("to", today.toString());
        r.put("labels", top.stream().map(Map.Entry::getKey).toList());
        r.put("accepted", top.stream().map(e -> e.getValue().accepted).toList());
        r.put("sent", top.stream().map(e -> e.getValue().sent).toList());
        r.put("failed", top.stream().map(e -> e.getValue().failed).toList());
        return r;
    }

    private interface AuditEventConsumer {
        void accept(Map<String, Object> obj);
    }

    private void forEachAuditEvent(LocalDate from, LocalDate today, ZoneId zone,
                                   AuditEventConsumer consumer) throws IOException {
        for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) {
            Path file = auditLogger.fileFor(d);
            if (!Files.exists(file)) continue;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> obj = mapper.readValue(line, Map.class);
                    String ts = (String) obj.get("ts");
                    String type = (String) obj.get("type");
                    if (ts == null || type == null) continue;
                    try {
                        ZonedDateTime.parse(ts).withZoneSameInstant(zone).toLocalDate();
                    } catch (DateTimeParseException e) {
                        continue;
                    }
                    consumer.accept(obj);
                } catch (Exception ignored) {}
            }
        }
    }

    private Dimension resolveDimension(String raw) {
        try {
            return raw == null ? Dimension.OVERALL : Dimension.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            return Dimension.OVERALL;
        }
    }

    private boolean matchesDimension(Map<String, Object> obj, Dimension dimension,
                                     String topicId, String apiKeyId) {
        return switch (dimension) {
            case OVERALL -> true;
            case TOPIC -> topicId == null || topicId.isBlank() || topicId.equals(obj.get("topicId"));
            case APIKEY -> {
                Object eventApiKeyId = obj.get("apiKeyId");
                if (!(eventApiKeyId instanceof String s) || s.isBlank()) yield false;
                yield apiKeyId == null || apiKeyId.isBlank() || apiKeyId.equals(s);
            }
        };
    }

    private SystemSettings.Span resolveSpan(Integer value, String unitStr) {
        SystemSettings.Span def = settingsService.current().getDashboardDefaultTrend();
        if (value == null && (unitStr == null || unitStr.isBlank())) return def;
        SystemSettings.Unit u;
        try {
            u = unitStr == null ? def.getUnit() : SystemSettings.Unit.valueOf(unitStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            u = def.getUnit();
        }
        int v = value == null ? def.getValue() : Math.max(1, value);
        return new SystemSettings.Span(v, u);
    }

    private Map<String, Object> result(Map<String, Counters> buckets) {
        List<String> labels = List.copyOf(buckets.keySet());
        List<Long> accepted = buckets.values().stream().map(c -> c.accepted).toList();
        List<Long> sent = buckets.values().stream().map(c -> c.sent).toList();
        List<Long> failed = buckets.values().stream().map(c -> c.failed).toList();
        Map<String, Object> r = new HashMap<>();
        r.put("labels", labels);
        r.put("accepted", accepted);
        r.put("sent", sent);
        r.put("failed", failed);
        return r;
    }

    enum Dimension { OVERALL, TOPIC, APIKEY }

    static class Counters {
        long accepted;
        long sent;
        long failed;
    }
}
