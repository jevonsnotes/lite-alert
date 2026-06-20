package io.litealert.common.audit;

import io.litealert.common.db.DbJson;
import io.litealert.common.web.TraceIdHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/** Best-effort append-only audit logger backed by {@code la_audit_log}. */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditLogger {

    /** Kept for older helper code/tests that still format audit dates. */
    public static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final JdbcTemplate jdbc;
    private final DbJson json;

    /** Legacy no-op path helpers retained until all old file tooling is removed. */
    public Path dir() { return Path.of("audit"); }
    public Path fileFor(LocalDate date) { return dir().resolve(date.format(FILE_DATE) + ".log"); }

    public void log(String type, Map<String, Object> attrs) {
        try {
            Map<String, Object> clean = new LinkedHashMap<>();
            String actor = null;
            String traceId = TraceIdHolder.current();
            if (attrs != null) {
                for (var e : attrs.entrySet()) {
                    String k = e.getKey();
                    if ("ts".equals(k) || "type".equals(k)) continue;
                    if ("traceId".equals(k)) {
                        if (traceId == null && e.getValue() != null) traceId = String.valueOf(e.getValue());
                        continue;
                    }
                    if ("actor".equals(k) && e.getValue() != null) actor = String.valueOf(e.getValue());
                    clean.put(k, e.getValue());
                }
            }
            jdbc.update("insert into la_audit_log(ts, type, actor, trace_id, attrs_json) values (?, ?, ?, ?, ?)",
                    Timestamp.from(Instant.now()),
                    type,
                    actor,
                    traceId,
                    json.write(clean));
        } catch (Exception e) {
            log.warn("audit write failed: type={}", type, e);
        }
    }
}
