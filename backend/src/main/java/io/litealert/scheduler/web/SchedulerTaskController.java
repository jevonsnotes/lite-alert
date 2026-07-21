package io.litealert.scheduler.web;

import io.litealert.auth.permission.PermissionService;
import io.litealert.auth.permission.Permissions;
import io.litealert.scheduler.SchedulerTaskService;
import io.litealert.scheduler.domain.SchedulerTask;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/scheduler/tasks")
@RequiredArgsConstructor
public class SchedulerTaskController {

    private final SchedulerTaskService service;
    private final PermissionService permissionService;
    private final io.litealert.scheduler.SchedulerTaskDiffService diffService;

    @GetMapping
    public List<Map<String, Object>> list() {
        permissionService.require(Permissions.SCHEDULER_TASK_VIEW);
        return service.listMine().stream().map(this::toView).toList();
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        permissionService.require(Permissions.SCHEDULER_TASK_VIEW);
        return toView(service.getOrThrow(id));
    }

    /** Draft-vs-published diff for the diff view / publish preview. */
    @GetMapping("/{id}/diff")
    public Map<String, Object> diff(@PathVariable String id) {
        permissionService.require(Permissions.SCHEDULER_TASK_VIEW);
        io.litealert.scheduler.domain.SchedulerTask t = service.getOrThrow(id);
        io.litealert.scheduler.SchedulerTaskDiffService.DiffResult result = diffService.diff(t);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hasPendingChanges", result.hasPendingChanges());
        m.put("diffs", result.diffs().stream().map(this::toDiffView).toList());
        return m;
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody SchedulerTaskService.CreateRequest req) {
        return toView(service.create(req));
    }

    @PatchMapping("/{id}")
    public Map<String, Object> saveDraft(@PathVariable String id,
                                         @RequestBody SchedulerTaskService.SaveDraftRequest req) {
        return toView(service.saveDraft(id, req));
    }

    @PostMapping("/{id}/publish")
    public Map<String, Object> publish(@PathVariable String id) {
        return toView(service.publish(id));
    }

    @PostMapping("/{id}/enable")
    public Map<String, Object> enable(@PathVariable String id) {
        return toView(service.setEnabled(id, true));
    }

    @PostMapping("/{id}/disable")
    public Map<String, Object> disable(@PathVariable String id) {
        return toView(service.setEnabled(id, false));
    }

    @DeleteMapping("/{id}")
    public Map<String, String> delete(@PathVariable String id) {
        service.delete(id);
        return Map.of("status", "deleted");
    }

    private Map<String, Object> toView(SchedulerTask t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("ownerId", t.getOwnerId());
        m.put("name", t.getName());
        m.put("description", t.getDescription());
        m.put("taskType", t.getTaskType() == null ? null : t.getTaskType().name());
        m.put("cron", t.getCron());
        m.put("enabled", t.isEnabled());
        m.put("status", t.getStatus() == null ? null : t.getStatus().name());
        m.put("hasPendingChanges", diffService.hasPendingChanges(t));
        m.put("notifyConfigIds", t.getNotifyConfigIds());
        m.put("draftConfig", t.getDraftConfig());
        m.put("publishedConfig", t.getPublishedConfig());
        m.put("publishedAt", t.getPublishedAt() == null ? null : t.getPublishedAt().toString());
        m.put("createdAt", t.getCreatedAt() == null ? null : t.getCreatedAt().toString());
        m.put("updatedAt", t.getUpdatedAt() == null ? null : t.getUpdatedAt().toString());
        return m;
    }

    private Map<String, Object> toDiffView(io.litealert.scheduler.SchedulerTaskDiffService.DiffEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("field", e.field());
        m.put("oldValue", e.oldValue());
        m.put("newValue", e.newValue());
        m.put("changeType", e.changeType() == null ? null : e.changeType().name());
        return m;
    }
}
