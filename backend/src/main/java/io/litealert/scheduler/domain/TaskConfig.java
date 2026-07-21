package io.litealert.scheduler.domain;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

import java.util.List;

/**
 * Polymorphic base for scheduled-task config (design D1). The {@code type} discriminator routes
 * JSON to the right subclass so a single {@code draft_config_json} / {@code published_config_json}
 * column can hold an {@link ApiTaskConfig} or a {@link TcpTaskConfig}.
 *
 * <p>{@link Meta} (publish snapshot of name/description/cron/notifyConfigIds) and {@link Timeouts}
 * are hoisted to the base so both task types share the publish-snapshot invariant (engine reads
 * the authoritative cron from {@code publishedConfig.meta}) and per-task timeouts.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ApiTaskConfig.class, name = "API"),
        @JsonSubTypes.Type(value = TcpTaskConfig.class, name = "TCP")
})
public abstract class TaskConfig {

    /** Publish-time snapshot of task-level scalars + notify bindings (see {@link Meta}). */
    private Meta meta;
    /** Per-task timeouts. Null field → defaults; 0 = no limit. */
    private Timeouts timeouts;

    public Meta getMeta() { return meta; }
    public void setMeta(Meta meta) { this.meta = meta; }
    public Timeouts getTimeouts() { return timeouts; }
    public void setTimeouts(Timeouts timeouts) { this.timeouts = timeouts; }

    /**
     * Publish-time snapshot of task-level scalars (name/description/cron) and the published-track
     * notify-config bindings. Lives on {@link TaskConfig} so every task type carries it; the engine
     * reads the authoritative cron and notify bindings from here.
     */
    public static class Meta {
        private String name;
        private String description;
        private String cron;
        /** Notify-config ids bound to this task at publish time (published track). */
        private List<String> notifyConfigIds;

        public Meta() {}
        public Meta(String name, String description, String cron) {
            this.name = name; this.description = description; this.cron = cron;
        }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getCron() { return cron; }
        public void setCron(String cron) { this.cron = cron; }
        public List<String> getNotifyConfigIds() { return notifyConfigIds; }
        public void setNotifyConfigIds(List<String> notifyConfigIds) { this.notifyConfigIds = notifyConfigIds; }
    }

    /** Per-task timeouts in seconds. 0 = no limit; null field falls back to defaults. */
    public static class Timeouts {
        public static final int DEFAULT_CONNECT = 5;
        public static final int DEFAULT_READ = 30;
        public static final int DEFAULT_WRITE = 30;

        private Integer connect;
        private Integer read;
        private Integer write;

        public Timeouts() {}
        public Timeouts(Integer connect, Integer read, Integer write) {
            this.connect = connect; this.read = read; this.write = write;
        }
        /** Effective connect timeout seconds (null/0->default, but 0 means no limit). */
        public int effectiveConnect() { return connect == null ? DEFAULT_CONNECT : connect; }
        public int effectiveRead() { return read == null ? DEFAULT_READ : read; }
        public int effectiveWrite() { return write == null ? DEFAULT_WRITE : write; }
        public Integer getConnect() { return connect; }
        public void setConnect(Integer connect) { this.connect = connect; }
        public Integer getRead() { return read; }
        public void setRead(Integer read) { this.read = read; }
        public Integer getWrite() { return write; }
        public void setWrite(Integer write) { this.write = write; }
    }

    /** Discriminator value (e.g. "API"/"TCP"), sourced from the {@link JsonTypeName} on subclasses. */
    public final String type() {
        JsonTypeName t = getClass().getAnnotation(JsonTypeName.class);
        return t == null ? null : t.value();
    }
}
