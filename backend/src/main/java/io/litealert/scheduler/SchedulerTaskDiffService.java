package io.litealert.scheduler;

import io.litealert.scheduler.domain.ApiTaskConfig;
import io.litealert.scheduler.domain.TaskConfig;
import io.litealert.scheduler.domain.SchedulerTask;
import io.litealert.scheduler.domain.SchedulerTaskType;
import io.litealert.scheduler.domain.TcpTaskConfig;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes the difference between a task's draft config and its published config (design D1/D2).
 *
 * <p>Normalization (D2): empty collections and null are treated as equivalent so that an untouched
 * draft does not falsely report "pending changes" (e.g. {@code headers: []} vs {@code headers: null}).
 *
 * <p>{@link #hasPendingChanges} is a cheap boolean used by list views; {@link #diff} returns the
 * structured per-field breakdown used by the diff view.
 */
@Component
public class SchedulerTaskDiffService {

    /** True when draft differs from published (a never-published task with a non-empty draft = true). */
    public boolean hasPendingChanges(SchedulerTask task) {
        return !diff(task).diffs().isEmpty();
    }

    public DiffResult diff(SchedulerTask task) {
        List<DiffEntry> entries = new ArrayList<>();
        TaskConfig draftCfg = task.getDraftConfig();
        TaskConfig publishedCfg = task.getPublishedConfig();
        boolean isTcp = task.getTaskType() == SchedulerTaskType.TCP;
        if (publishedCfg == null) {
            // never published: if draft is effectively empty too -> no change; otherwise everything is pending
            if (!isEmptyConfig(task.getTaskType(), draftCfg)) {
                entries.add(new DiffEntry("config", null, summarize(task.getTaskType(), draftCfg), ChangeType.ADDED));
            }
            return new DiffResult(!entries.isEmpty(), entries);
        }
        // task-level scalars: draft = current task row, published = snapshot in publishedConfig.meta.
        // Tasks published before the meta snapshot existed have meta=null -> skip scalar comparison
        // (no published snapshot to compare against) rather than falsely flag every scalar as changed.
        TaskConfig.Meta pm = publishedCfg.getMeta();
        if (pm != null) {
            compareValue(entries, "name", task.getName(), pm.getName());
            compareValue(entries, "description", task.getDescription(), pm.getDescription());
            compareValue(entries, "cron", task.getCron(), pm.getCron());
            compareNotifyBindings(entries, task.getNotifyConfigIds(), pm.getNotifyConfigIds());
        }
        if (isTcp) {
            compareTcpConfig(entries, (TcpTaskConfig) draftCfg, (TcpTaskConfig) publishedCfg);
        } else {
            compareApiConfig(entries, (ApiTaskConfig) draftCfg, (ApiTaskConfig) publishedCfg);
        }
        return new DiffResult(!entries.isEmpty(), entries);
    }

    /** API config body comparison (design D6). */
    private void compareApiConfig(List<DiffEntry> entries, ApiTaskConfig draft, ApiTaskConfig published) {
        compareValue(entries, "method", draft.getMethod(), published.getMethod());
        compareValue(entries, "url", draft.getUrl(), published.getUrl());
        compareTimeouts(entries, draft.getTimeouts(), published.getTimeouts());
        compareList(entries, "headers", draft.getHeaders(), published.getHeaders(), ApiTaskConfig.Header::getName, ApiTaskConfig.Header::getValue);
        if (draft.getBody() == null && published.getBody() == null) {
            // no body on either side
        } else {
            compareBody(entries, draft.getBody(), published.getBody());
        }
        if (draft.getAssertion() == null && published.getAssertion() == null) {
            // no assertion on either side
        } else {
            compareAssertion(entries, draft.getAssertion(), published.getAssertion());
        }
    }

    /** TCP config comparison (design D6): host/port/timeouts only. */
    private void compareTcpConfig(List<DiffEntry> entries, TcpTaskConfig draft, TcpTaskConfig published) {
        compareValue(entries, "host", draft.getHost(), published.getHost());
        comparePort(entries, "port", draft.getPort(), published.getPort());
        compareTimeouts(entries, draft.getTimeouts(), published.getTimeouts());
    }

    private void comparePort(List<DiffEntry> out, String field, Integer draft, Integer published) {
        if (java.util.Objects.equals(draft, published)) return;
        out.add(new DiffEntry(field, String.valueOf(published), String.valueOf(draft),
                published == null && draft != null ? ChangeType.ADDED
                        : draft == null ? ChangeType.REMOVED : ChangeType.CHANGED));
    }

    private boolean isEmptyConfig(SchedulerTaskType type, TaskConfig c) {
        if (c == null) return true;
        if (type == SchedulerTaskType.TCP && c instanceof TcpTaskConfig tcp) {
            return isBlank(tcp.getHost()) && tcp.getPort() == null;
        }
        ApiTaskConfig a = c instanceof ApiTaskConfig ac ? ac : null;
        if (a == null) return true;
        if (notBlank(a.getMethod()) || notBlank(a.getUrl())) return false;
        if (hasItems(a.getHeaders())) return false;
        if (a.getBody() != null && a.getBody().getType() != null
                && a.getBody().getType() != ApiTaskConfig.Body.Type.NONE) return false;
        if (a.getBody() != null && (notBlank(a.getBody().getRawText()) || hasItems(a.getBody().getFields()))) return false;
        if (a.getAssertion() != null && hasItems(a.getAssertion().getConditions())) return false;
        return true;
    }

    private void compareValue(List<DiffEntry> out, String field, String draft, String published) {
        if (norm(draft).equals(norm(published))) return;
        out.add(new DiffEntry(field, published, draft,
                isBlank(published) && !isBlank(draft) ? ChangeType.ADDED
                        : isBlank(draft) ? ChangeType.REMOVED : ChangeType.CHANGED));
    }

    private void compareBody(List<DiffEntry> out, ApiTaskConfig.Body draft, ApiTaskConfig.Body published) {
        ApiTaskConfig.Body d = draft == null ? newBodyNone() : draft;
        ApiTaskConfig.Body p = published == null ? newBodyNone() : published;
        compareEnum(out, "body.type", d.getType(), p.getType());
        compareEnum(out, "body.rawType", d.getRawType(), p.getRawType());
        compareValue(out, "body.rawText", d.getRawText(), p.getRawText());
        compareList(out, "body.fields", d.getFields(), p.getFields(),
                ApiTaskConfig.FormField::getName, ApiTaskConfig.FormField::getValue);
    }

    private void compareAssertion(List<DiffEntry> out, ApiTaskConfig.Assertion draft, ApiTaskConfig.Assertion published) {
        ApiTaskConfig.Assertion d = draft == null ? newAssertionEmpty() : draft;
        ApiTaskConfig.Assertion p = published == null ? newAssertionEmpty() : published;
        compareEnum(out, "assertion.logic", d.getLogic(), p.getLogic());
        compareList(out, "assertion.conditions", d.getConditions(), p.getConditions(),
                c -> c.getPath() + "::" + c.getOperator(), c -> c.getExpected());
    }

    /** Compare notify-config id bindings (draft row vs published meta snapshot) as set membership. */
    private void compareNotifyBindings(List<DiffEntry> out, List<String> draft, List<String> published) {
        java.util.Set<String> d = toSet(draft);
        java.util.Set<String> p = toSet(published);
        for (String id : d) {
            if (!p.contains(id)) out.add(new DiffEntry("notifyConfigIds[" + id + "]", null, id, ChangeType.ADDED));
        }
        for (String id : p) {
            if (!d.contains(id)) out.add(new DiffEntry("notifyConfigIds[" + id + "]", id, null, ChangeType.REMOVED));
        }
    }

    private java.util.Set<String> toSet(List<String> list) {
        java.util.Set<String> s = new java.util.LinkedHashSet<>();
        if (list != null) {
            for (String x : list) if (x != null && !x.isBlank()) s.add(x);
        }
        return s;
    }

    private <T, K> void compareList(List<DiffEntry> out, String field,
                                    List<T> draft, List<T> published,
                                    java.util.function.Function<T, String> keyFn,
                                    java.util.function.Function<T, String> valueFn) {
        Map<String, String> dMap = toMap(draft, keyFn, valueFn);
        Map<String, String> pMap = toMap(published, keyFn, valueFn);
        for (Map.Entry<String, String> e : dMap.entrySet()) {
            String k = e.getKey();
            if (!pMap.containsKey(k)) {
                out.add(new DiffEntry(field + "[" + k + "]", null, e.getValue(), ChangeType.ADDED));
            } else if (!norm(pMap.get(k)).equals(norm(e.getValue()))) {
                out.add(new DiffEntry(field + "[" + k + "]", pMap.get(k), e.getValue(), ChangeType.CHANGED));
            }
        }
        for (Map.Entry<String, String> e : pMap.entrySet()) {
            if (!dMap.containsKey(e.getKey())) {
                out.add(new DiffEntry(field + "[" + e.getKey() + "]", e.getValue(), null, ChangeType.REMOVED));
            }
        }
    }

    private <T> void compareEnum(List<DiffEntry> out, String field, T draft, T published) {
        boolean changed = !java.util.Objects.equals(normEnum(draft), normEnum(published));
        if (changed) {
            out.add(new DiffEntry(field, String.valueOf(published), String.valueOf(draft), ChangeType.CHANGED));
        }
    }

    /** Compare per-task timeouts (null fields fall back to defaults; only flag non-default diffs). */
    private void compareTimeouts(List<DiffEntry> out, TaskConfig.Timeouts draft, TaskConfig.Timeouts published) {
        TaskConfig.Timeouts d = draft == null ? new TaskConfig.Timeouts() : draft;
        TaskConfig.Timeouts p = published == null ? new TaskConfig.Timeouts() : published;
        compareTimeout(out, "timeouts.connect", d.getConnect(), p.getConnect(), TaskConfig.Timeouts.DEFAULT_CONNECT);
        compareTimeout(out, "timeouts.read", d.getRead(), p.getRead(), TaskConfig.Timeouts.DEFAULT_READ);
        compareTimeout(out, "timeouts.write", d.getWrite(), p.getWrite(), TaskConfig.Timeouts.DEFAULT_WRITE);
    }

    private void compareTimeout(List<DiffEntry> out, String field, Integer draft, Integer published, int def) {
        int dv = draft == null ? def : draft;
        int pv = published == null ? def : published;
        if (dv != pv) {
            out.add(new DiffEntry(field, String.valueOf(pv), String.valueOf(dv), ChangeType.CHANGED));
        }
    }

    private <T, K> Map<String, String> toMap(List<T> list,
                                             java.util.function.Function<T, String> keyFn,
                                             java.util.function.Function<T, String> valueFn) {
        Map<String, String> m = new LinkedHashMap<>();
        if (list == null) return m;
        for (T item : list) {
            if (item == null) continue;
            String k = keyFn.apply(item);
            if (k == null || k.isBlank()) continue;
            m.put(k, valueFn.apply(item));
        }
        return m;
    }

    private String summarize(SchedulerTaskType type, TaskConfig c) {
        if (c == null) return "";
        if (type == SchedulerTaskType.TCP && c instanceof TcpTaskConfig tcp) {
            StringBuilder sb = new StringBuilder();
            if (notBlank(tcp.getHost())) sb.append(tcp.getHost());
            if (tcp.getPort() != null) sb.append(':').append(tcp.getPort());
            return sb.toString();
        }
        ApiTaskConfig a = c instanceof ApiTaskConfig ac ? ac : null;
        if (a == null) return "";
        StringBuilder sb = new StringBuilder();
        if (notBlank(a.getMethod())) sb.append(a.getMethod()).append(' ');
        if (notBlank(a.getUrl())) sb.append(a.getUrl());
        return sb.toString().trim();
    }

    // ---- normalization helpers ----
    private String norm(String s) { return s == null ? "" : s; }
    private boolean isBlank(String s) { return s == null || s.isBlank(); }
    private boolean notBlank(String s) { return !isBlank(s); }
    private boolean hasItems(Collection<?> c) { return c != null && !c.isEmpty(); }
    private String normEnum(Object o) { return o == null ? "" : o.toString(); }
    private ApiTaskConfig.Body newBodyNone() {
        ApiTaskConfig.Body b = new ApiTaskConfig.Body();
        b.setType(ApiTaskConfig.Body.Type.NONE);
        return b;
    }
    private ApiTaskConfig.Assertion newAssertionEmpty() {
        ApiTaskConfig.Assertion a = new ApiTaskConfig.Assertion();
        a.setConditions(List.of());
        return a;
    }

    public enum ChangeType { ADDED, REMOVED, CHANGED }

    public record DiffEntry(String field, String oldValue, String newValue, ChangeType changeType) {}

    public record DiffResult(boolean hasPendingChanges, List<DiffEntry> diffs) {}
}
