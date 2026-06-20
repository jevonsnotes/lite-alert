package io.litealert.admin.web;

import com.fasterxml.jackson.core.type.TypeReference;
import io.litealert.apikey.domain.ApiKeyStore;
import io.litealert.auth.CurrentUser;
import io.litealert.auth.domain.UserStore;
import io.litealert.auth.permission.PermissionService;
import io.litealert.auth.permission.Permissions;
import io.litealert.common.db.DbJson;
import io.litealert.namespace.domain.NamespaceStore;
import io.litealert.topic.domain.TopicStore;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    private final CurrentUser currentUser;
    private final TopicStore topicStore;
    private final NamespaceStore namespaceStore;
    private final ApiKeyStore apiKeyStore;
    private final UserStore userStore;
    private final JdbcTemplate jdbc;
    private final DbJson json;
    private final PermissionService permissionService;

    @GetMapping
    public Map<String, Object> tail(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String topicId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "all") String field) {

        permissionService.require(Permissions.AUDIT_VIEW);
        if (size < 1) size = 1;
        if (size > 500) size = 500;
        if (page < 0) page = 0;

        LocalDate today = LocalDate.now();
        LocalDate fromDate = parseDate(from, today);
        LocalDate toDate = parseDate(to, today);
        if (fromDate.isAfter(toDate)) { LocalDate t = fromDate; fromDate = toDate; toDate = t; }

        String needle = q == null || q.isBlank() ? null : q.trim().toLowerCase();
        SearchField scope = SearchField.parse(field);
        boolean canViewAll = permissionService.has(Permissions.AUDIT_VIEW_ALL);
        String myId = currentUser.idOrThrow();

        List<Map<String, Object>> all = jdbc.query(
                "select id, ts, type, actor, trace_id, attrs_json from la_audit_log where ts >= ? and ts <= ? order by ts desc",
                (rs, rowNum) -> {
                    Map<String, Object> evt = json.read(rs.getString("attrs_json"), new TypeReference<Map<String, Object>>() {}, new LinkedHashMap<>());
                    evt.put("id", rs.getLong("id"));
                    evt.put("ts", rs.getTimestamp("ts").toInstant().toString());
                    evt.put("type", rs.getString("type"));
                    if (rs.getString("actor") != null) evt.put("actor", rs.getString("actor"));
                    if (rs.getString("trace_id") != null) evt.put("traceId", rs.getString("trace_id"));
                    return evt;
                }, Timestamp.valueOf(fromDate.atStartOfDay()), Timestamp.valueOf(toDate.plusDays(1).atStartOfDay().minusNanos(1)));

        List<Map<String, Object>> hits = all.stream()
                .filter(evt -> type == null || type.equals(evt.get("type")))
                .filter(evt -> topicId == null || topicId.equals(evt.get("topicId")))
                .filter(evt -> canViewAll || visibleTo(evt, myId))
                .peek(this::enrich)
                .filter(evt -> needle == null || matchesQuery(evt, needle, scope))
                .toList();

        int total = hits.size();
        int start = Math.min(page * size, total);
        int end = Math.min(start + size, total);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("from", fromDate.toString());
        stats.put("to", toDate.toString());
        stats.put("matched", total);
        stats.put("visible", total);
        stats.put("canViewAll", canViewAll);
        stats.put("userId", myId);

        return Map.of(
                "items", start >= end ? List.of() : hits.subList(start, end),
                "stats", stats,
                "page", page,
                "size", size,
                "total", total,
                "pageCount", total == 0 ? 0 : (total + size - 1) / size);
    }

    private LocalDate parseDate(String s, LocalDate fallback) {
        if (s == null || s.isBlank()) return fallback;
        try { return LocalDate.parse(s); }
        catch (Exception e) { return fallback; }
    }

    private boolean visibleTo(Map<String, Object> evt, String userId) {
        Object actor = evt.get("actor");
        if (actor != null && userId.equals(actor)) return true;
        Object owner = evt.get("apiKeyOwner");
        if (owner != null && userId.equals(owner)) return true;
        Object tid = evt.get("topicId");
        if (tid instanceof String s) return topicStore.findById(s).map(t -> userId.equals(t.getOwnerId())).orElse(false);
        return false;
    }

    private void enrich(Map<String, Object> evt) {
        Object tid = evt.get("topicId");
        if (tid instanceof String s) {
            topicStore.findById(s).ifPresent(t -> {
                evt.put("_topic", labelMap(t.getId(), t.getName()));
                if (t.getNamespaceId() != null) namespaceStore.findById(t.getNamespaceId()).ifPresent(ns -> evt.put("_namespace", labelMap(ns.getId(), ns.getName())));
            });
        }
        Object nsId = evt.get("namespaceId");
        if (nsId instanceof String s && !evt.containsKey("_namespace")) namespaceStore.findById(s).ifPresent(ns -> evt.put("_namespace", labelMap(ns.getId(), ns.getName())));
        Object akId = evt.get("apiKeyId");
        if (akId instanceof String s) apiKeyStore.findById(s).ifPresent(k -> {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("id", k.getId()); v.put("name", k.getName()); v.put("prefix", k.getPrefix()); evt.put("_apiKey", v);
        });
        Object actor = evt.get("actor");
        if (actor instanceof String s) userStore.findById(s).ifPresent(u -> evt.put("_actor", labelMap(u.getId(), u.getUsername())));
    }

    private Map<String, Object> labelMap(String id, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id); m.put("name", name); return m;
    }

    private boolean matchesQuery(Map<String, Object> evt, String needle, SearchField scope) {
        return switch (scope) {
            case TOPIC -> containsCI(evt.get("topicId"), needle) || containsCI(evt.get("topicName"), needle) || containsLabel(evt.get("_topic"), needle);
            case NAMESPACE -> containsCI(evt.get("namespace"), needle) || containsCI(evt.get("namespaceId"), needle) || containsLabel(evt.get("_namespace"), needle);
            case APIKEY -> containsCI(evt.get("apiKeyId"), needle) || containsLabel(evt.get("_apiKey"), needle);
            case ACTOR -> containsCI(evt.get("actor"), needle) || containsLabel(evt.get("_actor"), needle);
            case TRACE -> containsCI(evt.get("traceId"), needle);
            case IP -> containsCI(evt.get("remoteIp"), needle);
            case CODE -> containsCI(evt.get("code"), needle) || containsCI(evt.get("message"), needle);
            default -> containsCI(evt.get("type"), needle) || containsCI(evt.get("code"), needle) || containsCI(evt.get("message"), needle)
                    || containsCI(evt.get("traceId"), needle) || containsCI(evt.get("topicId"), needle) || containsCI(evt.get("topicName"), needle)
                    || containsCI(evt.get("namespace"), needle) || containsCI(evt.get("apiKeyId"), needle) || containsCI(evt.get("remoteIp"), needle)
                    || containsCI(evt.get("actor"), needle) || containsLabel(evt.get("_topic"), needle) || containsLabel(evt.get("_namespace"), needle)
                    || containsLabel(evt.get("_apiKey"), needle) || containsLabel(evt.get("_actor"), needle);
        };
    }

    private boolean containsCI(Object v, String needle) { return v != null && Objects.toString(v).toLowerCase().contains(needle); }
    @SuppressWarnings("unchecked")
    private boolean containsLabel(Object v, String needle) {
        if (!(v instanceof Map<?, ?> m)) return false;
        Map<String, Object> mm = (Map<String, Object>) m;
        return containsCI(mm.get("id"), needle) || containsCI(mm.get("name"), needle) || containsCI(mm.get("prefix"), needle);
    }

    private enum SearchField {
        ALL, TOPIC, NAMESPACE, APIKEY, ACTOR, TRACE, IP, CODE;
        static SearchField parse(String s) {
            if (s == null) return ALL;
            try { return valueOf(s.trim().toUpperCase()); }
            catch (IllegalArgumentException e) { return ALL; }
        }
    }
}
