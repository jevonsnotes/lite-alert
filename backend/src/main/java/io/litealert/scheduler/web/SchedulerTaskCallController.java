package io.litealert.scheduler.web;

import io.litealert.auth.permission.PermissionService;
import io.litealert.auth.permission.Permissions;
import io.litealert.common.error.BusinessException;
import io.litealert.common.error.ErrorCode;
import io.litealert.scheduler.SchedulerTaskService;
import io.litealert.scheduler.domain.SchedulerTask;
import io.litealert.scheduler.domain.SchedulerTaskCall;
import io.litealert.scheduler.domain.SchedulerTaskCallStore;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/scheduler")
@RequiredArgsConstructor
public class SchedulerTaskCallController {

    private final SchedulerTaskCallStore callStore;
    private final SchedulerTaskService taskService;
    private final PermissionService permissionService;

    @GetMapping("/tasks/{taskId}/calls")
    public Map<String, Object> listCalls(@PathVariable String taskId,
                                         @RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to,
                                         @RequestParam(required = false) Boolean success,
                                         @RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        permissionService.require(Permissions.SCHEDULER_CALL_VIEW);
        taskService.getOrThrowForCall(taskId); // ownership / visibility check (CALL_VIEW_ALL scope)
        Instant fromInst = parseInstant(from);
        Instant toInst = parseInstant(to);
        int cappedSize = Math.min(Math.max(size, 1), 100);
        int page1 = Math.max(page, 1);
        List<SchedulerTaskCall> calls = callStore.findPage(java.util.Set.of(taskId), fromInst, toInst, success, page1, cappedSize);
        long total = callStore.countByTask(taskId, fromInst, toInst);
        long successCount = callStore.countSuccessByTask(taskId, fromInst, toInst);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", calls.stream().map(this::toView).toList());
        m.put("total", total);
        m.put("successCount", successCount);
        m.put("page", page1);
        m.put("size", cappedSize);
        return m;
    }

    /** Cross-task call query: omit taskId (or pass empty) to query all visible tasks' calls. Paged. */
    @GetMapping("/calls")
    public Map<String, Object> listAllCalls(@RequestParam(required = false) String taskId,
                                            @RequestParam(required = false) String from,
                                            @RequestParam(required = false) String to,
                                            @RequestParam(required = false) Boolean success,
                                            @RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        permissionService.require(Permissions.SCHEDULER_CALL_VIEW);
        Instant fromInst = parseInstant(from);
        Instant toInst = parseInstant(to);
        int cappedSize = Math.min(Math.max(size, 1), 100);
        int page1 = Math.max(page, 1);

        java.util.Set<String> taskIds;
        if (taskId != null && !taskId.isBlank()) {
            // single task: enforce visibility (ownership / CALL_VIEW_ALL)
            taskService.getOrThrowForCall(taskId);
            taskIds = java.util.Set.of(taskId);
        } else {
            // all visible tasks
            taskIds = taskService.visibleTaskIdsForCalls();
        }

        List<SchedulerTaskCall> calls = callStore.findPage(taskIds, fromInst, toInst, success, page1, cappedSize);
        long total = callStore.countByTasks(taskIds, fromInst, toInst);
        long successCount = callStore.countSuccessByTasks(taskIds, fromInst, toInst);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", calls.stream().map(this::toView).toList());
        m.put("total", total);
        m.put("successCount", successCount);
        m.put("page", page1);
        m.put("size", cappedSize);
        return m;
    }

    @GetMapping("/calls/{id}")
    public Map<String, Object> callDetail(@PathVariable String id) {
        permissionService.require(Permissions.SCHEDULER_CALL_VIEW);
        SchedulerTaskCall c = callStore.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "call record not found"));
        // ownership check via the owning task (CALL_VIEW_ALL scope)
        taskService.getOrThrowForCall(c.getTaskId());
        return toView(c);
    }

    /** Dashboard: aggregate call totals + success rate for a window, scoped to visible tasks. */
    @GetMapping("/stats")
    public Map<String, Object> stats(@RequestParam(required = false) String from,
                                     @RequestParam(required = false) String to) {
        permissionService.require(Permissions.SCHEDULER_CALL_VIEW);
        Instant fromInst = parseInstant(from);
        Instant toInst = parseInstant(to);
        java.util.Set<String> taskIds = taskService.visibleTaskIdsForCalls();
        Map<String, Long> totals = taskIds.isEmpty()
                ? java.util.Map.of("total", 0L, "success", 0L)
                : callStore.totals(taskIds, fromInst, toInst);
        long total = totals.getOrDefault("total", 0L);
        long success = totals.getOrDefault("success", 0L);
        double rate = total == 0 ? 0.0 : (double) success / total;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", total);
        m.put("success", success);
        m.put("fail", total - success);
        m.put("successRate", rate);
        m.put("trend", taskIds.isEmpty() ? List.of() : callStore.dailyTrend(taskIds, fromInst, toInst));
        return m;
    }

    private Map<String, Object> toView(SchedulerTaskCall c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("taskId", c.getTaskId());
        m.put("triggeredAt", c.getTriggeredAt() == null ? null : c.getTriggeredAt().toString());
        m.put("protocol", c.getProtocol() == null ? "API" : c.getProtocol());
        m.put("method", c.getMethod());
        m.put("url", c.getUrl());
        m.put("tcpTarget", c.getTcpTarget());
        m.put("tcpOk", c.getTcpOk());
        m.put("httpStatus", c.getHttpStatus());
        m.put("durationMs", c.getDurationMs());
        m.put("status", c.getStatus() == null ? null : c.getStatus().name());
        m.put("assertionPassed", c.getAssertionPassed());
        m.put("errorMessage", c.getErrorMessage());
        m.put("responseExcerpt", c.getResponseExcerpt());
        return m;
    }

    /** Dashboard: multi-dimension breakdown for the sankey chart, scoped to visible tasks. */
    @GetMapping("/stats/breakdown")
    public Map<String, Object> breakdown(@RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to,
                                         @RequestParam(defaultValue = "10") int limit) {
        permissionService.require(Permissions.SCHEDULER_CALL_VIEW);
        Instant fromInst = parseInstant(from);
        Instant toInst = parseInstant(to);
        int cappedLimit = Math.min(Math.max(limit, 1), 100);
        java.util.Set<String> taskIds = taskService.visibleTaskIdsForCalls();
        return callStore.breakdown(taskIds, fromInst, toInst, cappedLimit);
    }

    private Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        return Instant.parse(s);
    }
}
