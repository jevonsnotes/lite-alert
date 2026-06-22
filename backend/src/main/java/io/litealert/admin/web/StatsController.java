package io.litealert.admin.web;

import io.litealert.admin.settings.SystemSettings;
import io.litealert.admin.settings.SystemSettingsService;
import io.litealert.apikey.domain.ApiKey;
import io.litealert.apikey.domain.ApiKeyStore;
import io.litealert.auth.CurrentUser;
import io.litealert.auth.permission.PermissionService;
import io.litealert.auth.permission.Permissions;
import io.litealert.topic.domain.Topic;
import io.litealert.topic.domain.TopicStore;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/stats")
@RequiredArgsConstructor
public class StatsController {

    private final PermissionService permissionService;
    private final SystemSettingsService settingsService;
    private final JdbcTemplate jdbc;
    private final TopicStore topicStore;
    private final ApiKeyStore apiKeyStore;
    private final CurrentUser currentUser;

    @GetMapping("/daily")
    public Map<String, Object> daily(
            @RequestParam(required = false) Integer value,
            @RequestParam(required = false) String unit,
            @RequestParam(required = false, defaultValue = "OVERALL") String dimension,
            @RequestParam(required = false) String topicId,
            @RequestParam(required = false) String apiKeyId) {

        permissionService.require(Permissions.STATS_VIEW);
        boolean canViewAll = permissionService.has(Permissions.STATS_VIEW_ALL);
        String myId = permissionService.has(Permissions.STATS_VIEW) ? currentUser.idOrThrow() : null;

        Dimension dim = resolveDimension(dimension);
        Window window = window(value, unit);
        Map<String, Counters> buckets = new LinkedHashMap<>();
        for (int i = 0; i < window.days(); i++) {
            buckets.put(window.from().plusDays(i).toString(), new Counters());
        }

        for (Map<String, Object> obj : auditRows(window)) {
            String day = ((Timestamp) obj.get("ts")).toInstant().atZone(ZoneId.systemDefault()).toLocalDate().toString();
            Counters c = buckets.get(day);
            if (c == null || !matchesDimension(obj, dim, topicId, apiKeyId)) continue;
            if (!canViewAll && !isVisibleTo(obj, myId)) continue;
            add(c, (String) obj.get("type"));
        }

        Map<String, Object> r = result(buckets);
        r.put("from", window.from().toString());
        r.put("to", window.today().toString());
        r.put("span", Map.of("value", window.span().getValue(), "unit", window.span().getUnit().name()));
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
            @RequestParam(required = false, defaultValue = "10") Integer limit,
            @RequestParam(required = false) String topicId,
            @RequestParam(required = false) String apiKeyId) {

        permissionService.require(Permissions.STATS_VIEW);
        boolean canViewAll = permissionService.has(Permissions.STATS_VIEW_ALL);
        String myId = permissionService.has(Permissions.STATS_VIEW) ? currentUser.idOrThrow() : null;

        Dimension dim = resolveDimension(dimension);
        if (dim == Dimension.OVERALL) dim = Dimension.TOPIC;
        Window window = window(value, unit);
        String selectedId = selectedRankingId(dim, topicId, apiKeyId);
        Map<String, Counters> counters = new HashMap<>();
        if (selectedId != null) counters.put(selectedId, new Counters());
        for (Map<String, Object> obj : auditRows(window)) {
            Object key = dim == Dimension.TOPIC ? obj.get("topicId") : obj.get("apiKeyId");
            if (!(key instanceof String s) || s.isBlank()) continue;
            if (selectedId != null && !selectedId.equals(s)) continue;
            if (!canViewAll && !statsVisibleTo(obj, myId)) continue;
            Counters c = counters.computeIfAbsent(s, ignored -> new Counters());
            add(c, (String) obj.get("type"));
        }

        int cap = selectedId == null ? Math.max(1, Math.min(limit == null ? 10 : limit, 50)) : 1;
        List<Map.Entry<String, Counters>> top = counters.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Counters>>comparingLong(e -> e.getValue().accepted).reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(cap)
                .toList();

        Map<String, Object> r = new LinkedHashMap<>();
        Dimension labelDimension = dim;
        r.put("dimension", dim.name());
        r.put("from", window.from().toString());
        r.put("to", window.today().toString());
        r.put("labels", top.stream().map(e -> labelFor(labelDimension, e.getKey())).toList());
        r.put("accepted", top.stream().map(e -> e.getValue().accepted).toList());
        r.put("sent", top.stream().map(e -> e.getValue().sent).toList());
        r.put("failed", top.stream().map(e -> e.getValue().failed).toList());
        return r;
    }

    private String selectedRankingId(Dimension dimension, String topicId, String apiKeyId) {
        String raw = dimension == Dimension.TOPIC ? topicId : apiKeyId;
        return raw == null || raw.isBlank() ? null : raw;
    }

    private String labelFor(Dimension dimension, String id) {
        if (dimension == Dimension.TOPIC) {
            return topicStore.findById(id)
                    .map(this::topicLabel)
                    .orElse(id);
        }
        if (dimension == Dimension.APIKEY) {
            return apiKeyStore.findById(id)
                    .map(this::apiKeyLabel)
                    .orElse(id);
        }
        return id;
    }

    private String apiKeyLabel(ApiKey apiKey) {
        String name = apiKey.getName();
        String prefix = apiKey.getPrefix();
        if (name == null || name.isBlank()) return prefix == null || prefix.isBlank() ? apiKey.getId() : prefix + "••••";
        if (prefix == null || prefix.isBlank()) return name;
        return name + " (" + prefix + "••••)";
    }

    private String topicLabel(Topic topic) {
        String namespace = topic.getNamespaceName();
        if (namespace == null || namespace.isBlank()) return topic.getName();
        return namespace + "/" + topic.getName();
    }

    private List<Map<String, Object>> auditRows(Window window) {
        return jdbc.query("select ts, type, topic_id, api_key_id from la_audit_log where ts >= ? and ts <= ? order by ts asc",
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("ts", rs.getTimestamp("ts"));
                    row.put("type", rs.getString("type"));
                    row.put("topicId", rs.getString("topic_id"));
                    row.put("apiKeyId", rs.getString("api_key_id"));
                    return row;
                }, Timestamp.valueOf(window.from().atStartOfDay()), Timestamp.valueOf(window.today().plusDays(1).atStartOfDay().minusNanos(1)));
    }

    private void add(Counters c, String type) {
        if ("webhook.accepted".equals(type)) c.accepted++;
        else if ("notify.sent".equals(type)) c.sent++;
        else if ("notify.failed".equals(type) || "notify.give_up".equals(type)) c.failed++;
    }

    private Window window(Integer value, String unitStr) {
        SystemSettings.Span span = resolveSpan(value, unitStr);
        LocalDate today = ZonedDateTime.now(ZoneId.systemDefault()).toLocalDate();
        LocalDate from = span.cutoff(today);
        int days = (int) Math.min(3650, today.toEpochDay() - from.toEpochDay() + 1);
        if (days > 365) {
            from = today.minusDays(364);
            days = 365;
        }
        return new Window(span, today, from, days);
    }

    private Dimension resolveDimension(String raw) {
        try { return raw == null ? Dimension.OVERALL : Dimension.valueOf(raw.toUpperCase()); }
        catch (IllegalArgumentException e) { return Dimension.OVERALL; }
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

    private boolean isVisibleTo(Map<String, Object> obj, String userId) {
        return statsVisibleTo(obj, userId);
    }

    private boolean statsVisibleTo(Map<String, Object> obj, String userId) {
        Object actor = obj.get("actor");
        if (actor != null && userId.equals(actor)) return true;
        Object owner = obj.get("apiKeyOwner");
        if (owner != null && userId.equals(owner)) return true;
        Object tid = obj.get("topicId");
        if (tid instanceof String s) return topicStore.findById(s).map(t -> userId.equals(t.getOwnerId())).orElse(false);
        return false;
    }

    private SystemSettings.Span resolveSpan(Integer value, String unitStr) {
        SystemSettings.Span span = settingsService.current().getDashboardDefaultTrend();
        if (value == null && (unitStr == null || unitStr.isBlank())) return span;
        SystemSettings.Unit u;
        try { u = unitStr == null ? span.getUnit() : SystemSettings.Unit.valueOf(unitStr.toUpperCase()); }
        catch (IllegalArgumentException e) { u = span.getUnit(); }
        int v = value == null ? span.getValue() : Math.max(1, value);
        return new SystemSettings.Span(v, u);
    }

    private Map<String, Object> result(Map<String, Counters> buckets) {
        List<String> labels = List.copyOf(buckets.keySet());
        Map<String, Object> r = new HashMap<>();
        r.put("labels", labels);
        r.put("accepted", buckets.values().stream().map(c -> c.accepted).toList());
        r.put("sent", buckets.values().stream().map(c -> c.sent).toList());
        r.put("failed", buckets.values().stream().map(c -> c.failed).toList());
        return r;
    }

    enum Dimension { OVERALL, TOPIC, APIKEY }
    record Window(SystemSettings.Span span, LocalDate today, LocalDate from, int days) {}
    static class Counters { long accepted; long sent; long failed; }
}
