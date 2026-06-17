package io.litealert.common.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.litealert.common.config.LiteAlertProperties;
import io.litealert.common.web.TraceIdHolder;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Append-only JSON-lines audit log. One event per line, files rolled DAILY
 * under {@code <dataDir>/audit/yyyy-MM-dd.log}.
 *
 * <p>The shared {@code storeObjectMapper} pretty-prints for human-readable
 * entity files, but JSON-lines requires single-line records — we override
 * that with a dedicated writer.
 *
 * <p>Best-effort: a failed audit write must never abort the business flow,
 * so all IO errors are logged and swallowed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditLogger {

    /** Pattern AuditController and the janitor both expect; check before changing. */
    public static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final LiteAlertProperties props;

    @Qualifier("storeObjectMapper")
    private final ObjectMapper mapper;

    private Path dir;
    private ObjectWriter writer;

    @PostConstruct
    void init() throws IOException {
        this.dir = Path.of(props.getDataDir(), "audit").toAbsolutePath().normalize();
        Files.createDirectories(dir);
        this.writer = mapper.writer().without(SerializationFeature.INDENT_OUTPUT);
    }

    public Path dir() { return dir; }

    /** Resolves the daily file for an arbitrary local date. Public so the
     *  query side can build a list of file paths to read for a date range. */
    public Path fileFor(LocalDate date) {
        return dir.resolve(date.format(FILE_DATE) + ".log");
    }

    public void log(String type, Map<String, Object> attrs) {
        try {
            Map<String, Object> line = new LinkedHashMap<>();
            if (attrs != null) {
                for (var e : attrs.entrySet()) {
                    String k = e.getKey();
                    if ("ts".equals(k) || "type".equals(k) || "traceId".equals(k)) continue;
                    line.put(k, e.getValue());
                }
            }
            line.put("ts", Instant.now().toString());
            line.put("type", type);
            String trace = TraceIdHolder.current();
            if (trace != null) line.put("traceId", trace);

            Path file = fileFor(LocalDate.now(ZoneId.systemDefault()));
            String json;
            try {
                json = writer.writeValueAsString(line);
            } catch (JsonProcessingException e) {
                log.warn("audit serialize failed: type={}", type, e);
                return;
            }
            byte[] bytes = (json + "\n").getBytes(StandardCharsets.UTF_8);
            synchronized (this) {
                Files.write(file, bytes,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            }
        } catch (Exception e) {
            log.warn("audit write failed: type={}", type, e);
        }
    }
}
