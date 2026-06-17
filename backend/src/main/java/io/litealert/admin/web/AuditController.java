package io.litealert.admin.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.litealert.apikey.domain.ApiKeyStore;
import io.litealert.auth.CurrentUser;
import io.litealert.auth.domain.UserStore;
import io.litealert.common.audit.AuditLogger;
import io.litealert.namespace.domain.NamespaceStore;
import io.litealert.topic.domain.TopicStore;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Tail / search the audit log across a date range, with pagination.
 *
 * <p>Files are daily-rotated (see {@link AuditLogger}). The handler walks
 * each day in {@code [from, to]} from newest → oldest, which matches the
 * UI's "most recent first" expectation and lets a query stop early once it
 * has filled a page.
 */
@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    private final CurrentUser currentUser;
    private final TopicStore topicStore;
    private final NamespaceStore namespaceStore;
    private final ApiKeyStore apiKeyStore;
    private final UserStore userStore;
    private final AuditLogger auditLogger;

    @Qualifier("storeObjectMapper")
    private final ObjectMapper mapper;

    @GetMapping
    public Map<String, Object> tail(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String from,         // yyyy-MM-dd
            @RequestParam(required = false) String to,           // yyyy-MM-dd
            @RequestParam(required = false) String topicId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "all") String field) throws IOException {

        if (size < 1) size = 1;
        if (size > 500) size = 500;
        if (page < 0) page = 0;

        LocalDate today = LocalDate.now();
        LocalDate fromDate = parseDate(from, today);
        LocalDate toDate = parseDate(to, today);
        if (fromDate.isAfter(toDate)) { LocalDate t = fromDate; fromDate = toDate; toDate = t; }

        String needle = q == null || q.isBlank() ? null : q.trim().toLowerCase();
        SearchField scope = SearchField.parse(field);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("from", fromDate.toString());
        stats.put("to", toDate.toString());
        stats.put("filesScanned", 0);
        stats.put("filesMissing", 0);
        stats.put("rawLines", 0);
        stats.put("parseFailed", 0);
        stats.put("matched", 0);
        stats.put("visible", 0);
        stats.put("isAdmin", currentUser.isAdmin());
        stats.put("userId", currentUser.idOrThrow());

        boolean isAdmin = currentUser.isAdmin();
        String myId = currentUser.idOrThrow();

        // Phase 1: gather every visible+matching event across the range.
        // A daily file is small (KBs–MBs); for typical retention windows
        // this comfortably fits in memory.
        List<Map<String, Object>> hits = new ArrayList<>();

        int filesScanned = 0, filesMissing = 0, rawLines = 0,
                parseFailed = 0, matched = 0, visible = 0;

        for (LocalDate d = toDate; !d.isBefore(fromDate); d = d.minusDays(1)) {
            Path file = auditLogger.fileFor(d);
            if (!Files.exists(file)) { filesMissing++; continue; }
            filesScanned++;
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            // newest-first within a day — a daily file is appended; reverse.
            Collections.reverse(lines);
            rawLines += lines.size();

            for (String line : lines) {
                if (line.isBlank()) continue;
                Map<String, Object> obj;
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parsed = mapper.readValue(line, Map.class);
                    obj = parsed;
                } catch (Exception e) {
                    parseFailed++;
                    continue;
                }
                if (type != null && !type.equals(obj.get("type"))) continue;
                if (topicId != null && !topicId.equals(obj.get("topicId"))) continue;
                matched++;
                if (!isAdmin && !visibleTo(obj, myId)) continue;

                enrich(obj);
                if (needle != null && !matchesQuery(obj, needle, scope)) continue;

                visible++;
                hits.add(obj);
            }
        }

        stats.put("filesScanned", filesScanned);
        stats.put("filesMissing", filesMissing);
        stats.put("rawLines", rawLines);
        stats.put("parseFailed", parseFailed);
        stats.put("matched", matched);
        stats.put("visible", visible);

        // Phase 2: paginate the assembled list.
        int total = hits.size();
        int pageCount = total == 0 ? 0 : (total + size - 1) / size;
        int startIdx = page * size;
        if (startIdx >= total) startIdx = total;
        int endIdx = Math.min(startIdx + size, total);
        List<Map<String, Object>> items = startIdx >= endIdx
                ? List.of()
                : hits.subList(startIdx, endIdx);

        return Map.of(
                "items", items,
                "stats", stats,
                "page", page,
                "size", size,
                "total", total,
                "pageCount", pageCount);
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
        if (tid instanceof String s) {
            return topicStore.findById(s)
                    .map(t -> userId.equals(t.getOwnerId()))
                    .orElse(false);
        }
        return false;
    }

    private void enrich(Map<String, Object> evt) {
        Object tid = evt.get("topicId");
        if (tid instanceof String s) {
            topicStore.findById(s).ifPresent(t -> {
                evt.put("_topic", labelMap(t.getId(), t.getName()));
                if (t.getNamespaceId() != null) {
                    namespaceStore.findById(t.getNamespaceId()).ifPresent(ns ->
                            evt.put("_namespace", labelMap(ns.getId(), ns.getName())));
                }
            });
        }
        Object nsId = evt.get("namespaceId");
        if (nsId instanceof String s && !evt.containsKey("_namespace")) {
            namespaceStore.findById(s).ifPresent(ns ->
                    evt.put("_namespace", labelMap(ns.getId(), ns.getName())));
        }
        Object akId = evt.get("apiKeyId");
        if (akId instanceof String s) {
            apiKeyStore.findById(s).ifPresent(k -> {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("id", k.getId());
                v.put("name", k.getName());
                v.put("prefix", k.getPrefix());
                evt.put("_apiKey", v);
            });
        }
        Object actor = evt.get("actor");
        if (actor instanceof String s) {
            userStore.findById(s).ifPresent(u ->
                    evt.put("_actor", labelMap(u.getId(), u.getUsername())));
        }
    }

    private Map<String, Object> labelMap(String id, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        return m;
    }

    private boolean matchesQuery(Map<String, Object> evt, String needle, SearchField scope) {
        switch (scope) {
            case ALL:
                if (containsCI(evt.get("type"), needle)) return true;
                if (containsCI(evt.get("code"), needle)) return true;
                if (containsCI(evt.get("message"), needle)) return true;
                if (containsCI(evt.get("traceId"), needle)) return true;
                if (containsCI(evt.get("topicId"), needle)) return true;
                if (containsCI(evt.get("topicName"), needle)) return true;
                if (containsCI(evt.get("namespace"), needle)) return true;
                if (containsCI(evt.get("apiKeyId"), needle)) return true;
                if (containsCI(evt.get("remoteIp"), needle)) return true;
                if (containsCI(evt.get("actor"), needle)) return true;
                if (containsLabel(evt.get("_topic"), needle)) return true;
                if (containsLabel(evt.get("_namespace"), needle)) return true;
                if (containsLabel(evt.get("_apiKey"), needle)) return true;
                if (containsLabel(evt.get("_actor"), needle)) return true;
                return false;
            case TOPIC:
                return containsCI(evt.get("topicId"), needle)
                        || containsCI(evt.get("topicName"), needle)
                        || containsLabel(evt.get("_topic"), needle);
            case NAMESPACE:
                return containsCI(evt.get("namespace"), needle)
                        || containsCI(evt.get("namespaceId"), needle)
                        || containsLabel(evt.get("_namespace"), needle);
            case APIKEY:
                return containsCI(evt.get("apiKeyId"), needle)
                        || containsLabel(evt.get("_apiKey"), needle);
            case ACTOR:
                return containsCI(evt.get("actor"), needle)
                        || containsLabel(evt.get("_actor"), needle);
            case TRACE:
                return containsCI(evt.get("traceId"), needle);
            case IP:
                return containsCI(evt.get("remoteIp"), needle);
            case CODE:
                return containsCI(evt.get("code"), needle)
                        || containsCI(evt.get("message"), needle);
            default:
                return false;
        }
    }

    private boolean containsCI(Object v, String needle) {
        return v != null && Objects.toString(v).toLowerCase().contains(needle);
    }

    @SuppressWarnings("unchecked")
    private boolean containsLabel(Object v, String needle) {
        if (!(v instanceof Map<?, ?> m)) return false;
        Map<String, Object> mm = (Map<String, Object>) m;
        return containsCI(mm.get("id"), needle)
                || containsCI(mm.get("name"), needle)
                || containsCI(mm.get("prefix"), needle);
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
