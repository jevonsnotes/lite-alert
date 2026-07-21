package io.litealert.scheduler;

import io.litealert.auth.CurrentUser;
import io.litealert.auth.permission.PermissionService;
import io.litealert.auth.permission.Permissions;
import io.litealert.common.audit.AuditLogger;
import io.litealert.common.error.BusinessException;
import io.litealert.common.error.ErrorCode;
import io.litealert.common.util.IdGenerator;
import io.litealert.scheduler.domain.ApiTaskConfig;
import io.litealert.scheduler.domain.SchedulerTask;
import io.litealert.scheduler.domain.SchedulerTaskStore;
import io.litealert.scheduler.domain.SchedulerTaskType;
import io.litealert.scheduler.domain.TaskConfig;
import io.litealert.scheduler.domain.TcpTaskConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Write side of the scheduled-task lifecycle (specs {@code scheduled-task-management}).
 * Editing only mutates the draft config; {@link #publish} promotes draft → published and tells
 * the {@link SchedulerEngine} to reschedule. The engine always runs the published config.
 */
@Service
@RequiredArgsConstructor
public class SchedulerTaskService {

    private final SchedulerTaskStore store;
    private final SchedulerTaskTypeClassifier typeClassifier = new SchedulerTaskTypeClassifier();
    private final CurrentUser currentUser;
    private final PermissionService permissionService;
    private final SchedulerEngine engine;
    private final AuditLogger audit;
    private final io.litealert.scheduler.domain.SchedulerNotifyConfigStore notifyConfigStore;

    public List<SchedulerTask> listMine() {
        if (permissionService.has(Permissions.SCHEDULER_TASK_VIEW_ALL)) return store.findAll();
        return store.findByOwner(currentUser.idOrThrow());
    }

    /** Ids of tasks whose calls the caller may read (CALL_VIEW_ALL → all, else own tasks only). */
    public java.util.Set<String> visibleTaskIdsForCalls() {
        List<SchedulerTask> tasks = permissionService.has(Permissions.SCHEDULER_CALL_VIEW_ALL)
                ? store.findAll() : store.findByOwner(currentUser.idOrThrow());
        return tasks.stream().map(SchedulerTask::getId).collect(java.util.stream.Collectors.toSet());
    }

    public SchedulerTask getOrThrow(String id) {
        SchedulerTask t = store.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "scheduled task not found"));
        if (!t.getOwnerId().equals(currentUser.idOrThrow())
                && !permissionService.has(Permissions.SCHEDULER_TASK_VIEW_ALL)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return t;
    }

    /** Ownership/visibility check for call-record access: uses the CALL_VIEW_ALL scope. */
    public SchedulerTask getOrThrowForCall(String id) {
        SchedulerTask t = store.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "scheduled task not found"));
        if (!t.getOwnerId().equals(currentUser.idOrThrow())
                && !permissionService.has(Permissions.SCHEDULER_CALL_VIEW_ALL)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return t;
    }

    public SchedulerTask create(CreateRequest req) {
        permissionService.require(Permissions.SCHEDULER_TASK_MANAGE);
        validateCron(req.cron());
        SchedulerTaskType type = typeClassifier.classify(req.taskType());
        java.util.List<String> notifyIds = validateNotifyConfigs(req.notifyConfigIds());
        SchedulerTask t = SchedulerTask.builder()
                .id(IdGenerator.entityId("st"))
                .ownerId(currentUser.idOrThrow())
                .name(req.name())
                .description(req.description())
                .taskType(type)
                .cron(req.cron())
                .enabled(true)
                .status(SchedulerTask.Status.DRAFT)
                .notifyConfigIds(notifyIds)
                .draftConfig(resolveDraftConfig(type, req.config()))
                .publishedConfig(null)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        store.save(t);
        audit.log("scheduler.task.create", Map.of("actor", t.getOwnerId(), "taskId", t.getId()));
        return t;
    }

    /**
     * Pick the draft config: if the request supplied one matching the type, validate it (TCP host/port)
     * and use it; otherwise seed an empty config of the right subclass. The returned config always
     * matches {@code type} — a mismatched config type is ignored (task type is authoritative).
     */
    private TaskConfig resolveDraftConfig(SchedulerTaskType type, TaskConfig supplied) {
        if (supplied != null && supplied.type() != null
                && supplied.type().equals(type.name())) {
            validateConfig(type, supplied);
            return supplied;
        }
        return type == SchedulerTaskType.TCP ? new TcpTaskConfig() : new ApiTaskConfig();
    }

    /** Type-specific config validation. API has no required fields here; TCP requires host + port 1-65535. */
    private void validateConfig(SchedulerTaskType type, TaskConfig config) {
        if (type == SchedulerTaskType.TCP && config instanceof TcpTaskConfig tcp) {
            if (tcp.getHost() == null || tcp.getHost().isBlank()) {
                throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "tcp task host is required");
            }
            if (tcp.getPort() == null || tcp.getPort() < 1 || tcp.getPort() > 65535) {
                throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "tcp task port must be 1-65535");
            }
        }
    }

    /** Save edits to the draft only. Does NOT touch the scheduler or the published config. */
    public SchedulerTask saveDraft(String id, SaveDraftRequest req) {
        permissionService.require(Permissions.SCHEDULER_TASK_MANAGE);
        SchedulerTask t = getOrThrow(id);
        if (req.name() != null) t.setName(req.name());
        if (req.description() != null) t.setDescription(req.description());
        if (req.cron() != null) {
            validateCron(req.cron());
            t.setCron(req.cron());
        }
        // taskType is immutable after creation (design D7): ignore any taskType in the request,
        // and ignore a config whose discriminator doesn't match the task's type.
        if (req.config() != null && req.config().type() != null
                && req.config().type().equals(t.getTaskType() == null ? null : t.getTaskType().name())) {
            validateConfig(t.getTaskType(), req.config());
            t.setDraftConfig(req.config());
        }
        if (req.enabled() != null) t.setEnabled(req.enabled());
        if (req.notifyConfigIds() != null) t.setNotifyConfigIds(validateNotifyConfigs(req.notifyConfigIds()));
        t.setUpdatedAt(Instant.now());
        store.save(t);
        audit.log("scheduler.task.saveDraft", Map.of("actor", currentUser.idOrThrow(), "taskId", id));
        return t;
    }

    /** Ensure every selected notify-config id belongs to the current user; drop others. */
    private java.util.List<String> validateNotifyConfigs(java.util.List<String> ids) {
        if (ids == null || ids.isEmpty()) return new java.util.ArrayList<>();
        java.util.Set<String> mine = notifyConfigStore == null ? java.util.Set.of()
                : notifyConfigStore.findByOwner(currentUser.idOrThrow()).stream()
                        .map(c -> c.getId()).collect(java.util.stream.Collectors.toSet());
        return ids.stream().filter(mine::contains).distinct().toList();
    }

    /** Promote draft → published; engine reschedules from the new published config. */
    public SchedulerTask publish(String id) {
        permissionService.require(Permissions.SCHEDULER_TASK_PUBLISH);
        SchedulerTask t = getOrThrow(id);
        validateCron(t.getCron());
        TaskConfig published = t.getDraftConfig() == null ? emptyConfig(t.getTaskType()) : t.getDraftConfig();
        // snapshot task-level scalars so the diff view can show name/description/cron changes and
        // the engine can read the authoritative published cron; notify bindings follow the published track too.
        TaskConfig.Meta meta = new TaskConfig.Meta(t.getName(), t.getDescription(), t.getCron());
        meta.setNotifyConfigIds(t.getNotifyConfigIds() == null ? List.of() : new java.util.ArrayList<>(t.getNotifyConfigIds()));
        published.setMeta(meta);
        t.setPublishedConfig(published);
        t.setStatus(SchedulerTask.Status.PUBLISHED);
        t.setEnabled(true);  // publishing (re)enables; a previously-disabled task must run after publish
        t.setPublishedAt(Instant.now());
        t.setUpdatedAt(Instant.now());
        store.save(t);
        engine.reschedule(id);
        audit.log("scheduler.task.publish", Map.of("actor", currentUser.idOrThrow(), "taskId", id));
        return t;
    }

    /** Empty config of the subclass matching the task type. */
    private TaskConfig emptyConfig(SchedulerTaskType type) {
        return type == SchedulerTaskType.TCP ? new TcpTaskConfig() : new ApiTaskConfig();
    }

    public SchedulerTask setEnabled(String id, boolean enabled) {
        permissionService.require(Permissions.SCHEDULER_TASK_MANAGE);
        SchedulerTask t = getOrThrow(id);
        t.setEnabled(enabled);
        // enabled is the sole on/off gate; status (DRAFT/PUBLISHED) reflects lifecycle only and is
        // NOT changed by enable/disable (no DISABLED status now that the list separates the two).
        t.setUpdatedAt(Instant.now());
        store.save(t);
        if (!enabled) {
            engine.unschedule(id);
        } else if (t.isSchedulable()) {
            engine.reschedule(id);
        }
        return t;
    }

    public void delete(String id) {
        permissionService.require(Permissions.SCHEDULER_TASK_MANAGE);
        SchedulerTask t = getOrThrow(id);
        engine.unschedule(id);
        store.delete(id);
        audit.log("scheduler.task.delete", Map.of("actor", currentUser.idOrThrow(), "taskId", id));
    }

    private void validateCron(String cron) {
        if (cron == null || cron.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "cron expression is required");
        }
        try {
            CronExpression.parse(cron);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "invalid cron expression: " + e.getMessage());
        }
    }

    public record CreateRequest(String name, String description, String taskType, String cron, TaskConfig config, java.util.List<String> notifyConfigIds) {}

    public record SaveDraftRequest(String name, String description, String cron, Boolean enabled, TaskConfig config, java.util.List<String> notifyConfigIds) {}
}
