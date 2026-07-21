package io.litealert.scheduler.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import io.litealert.common.db.JacksonTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A scheduled task. Holds a draft config and a published config as two JSON columns
 * (design D2): editing only touches the draft; the scheduler only runs the published
 * config. A task with a null {@code publishedConfig} has never been published and is
 * never scheduled.
 *
 * <p>{@link #status} mirrors {@code Topic.Status}: DRAFT (never published or reverted),
 * PUBLISHED (scheduler runs it), DISABLED (paused, config retained).
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Table("la_scheduler_task")
public class SchedulerTask {

    public enum Status { DRAFT, PUBLISHED, DISABLED }

    @Id(keyType = KeyType.None)
    private String id;

    @Column(value = "owner_id")
    private String ownerId;

    @Column
    private String name;

    @Column
    private String description;

    /** {@link SchedulerTaskType#name()} — persisted so future types don't break rows. */
    @Column(value = "task_type")
    private SchedulerTaskType taskType;

    /** Cron expression. Redundant copy of published config's cron for quick lookup. */
    @Column
    private String cron;

    /** Soft on/off flag (status is the authoritative scheduler gate). */
    @Column
    private boolean enabled = true;

    /** Draft-track notify-config bindings (published track lives in publishedConfig.meta). */
    @Builder.Default
    @Column(value = "notify_config_ids_json", typeHandler = JacksonTypeHandler.class)
    private java.util.List<String> notifyConfigIds = new java.util.ArrayList<>();

    @Column
    private Status status;

    @Builder.Default
    @Column(value = "draft_config_json", typeHandler = JacksonTypeHandler.class)
    private TaskConfig draftConfig = new ApiTaskConfig();

    /** Null = never published → not scheduled. */
    @Column(value = "published_config_json", typeHandler = JacksonTypeHandler.class)
    private TaskConfig publishedConfig;

    @Column(value = "published_at")
    private Instant publishedAt;

    @Column(value = "created_at")
    private Instant createdAt;

    @Column(value = "updated_at")
    private Instant updatedAt;

    /** True when this task has a published config and should be schedulable. */
    @JsonIgnore
    public boolean isSchedulable() {
        return status == Status.PUBLISHED && enabled && publishedConfig != null;
    }
}
