package io.litealert.scheduler.web;

import io.litealert.auth.permission.PermissionService;
import io.litealert.auth.permission.Permissions;
import io.litealert.scheduler.SchedulerTaskService;
import io.litealert.scheduler.domain.SchedulerTaskCallStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the dashboard {@code /scheduler/stats} endpoint is scoped to the caller's visible
 * tasks (calls-scoped), not global: it requires {@code SCHEDULER_CALL_VIEW} and filters via
 * {@code SchedulerTaskService.visibleTaskIdsForCalls()}.
 */
class SchedulerStatsControllerTest {

    private SchedulerTaskCallStore callStore;
    private SchedulerTaskService taskService;
    private PermissionService permissions;
    private SchedulerTaskCallController controller;

    @BeforeEach
    void setUp() {
        callStore = mock(SchedulerTaskCallStore.class);
        taskService = mock(SchedulerTaskService.class);
        permissions = mock(PermissionService.class);
        controller = new SchedulerTaskCallController(callStore, taskService, permissions);
    }

    @Test
    void statsRequiresCallViewPermission() {
        when(taskService.visibleTaskIdsForCalls()).thenReturn(Set.of());
        controller.stats("2026-01-01T00:00:00Z", "2026-01-31T23:59:59Z");
        verify(permissions).require(Permissions.SCHEDULER_CALL_VIEW);
    }

    @Test
    void statsScopedToVisibleTasksMineOnly() {
        // user sees only their own tasks -> store queried with that exact set
        Set<String> mine = Set.of("st_mine_1", "st_mine_2");
        when(taskService.visibleTaskIdsForCalls()).thenReturn(mine);
        when(callStore.totals(eq(mine), any(), any())).thenReturn(Map.of("total", 5L, "success", 3L));
        when(callStore.dailyTrend(eq(mine), any(), any())).thenReturn(java.util.List.of());

        Map<String, Object> r = controller.stats(null, null);

        assertThat(r.get("total")).isEqualTo(5L);
        assertThat(r.get("success")).isEqualTo(3L);
        assertThat(r.get("fail")).isEqualTo(2L);
        assertThat(r.get("successRate")).isEqualTo(0.6);
        verify(callStore).totals(eq(mine), any(), any());
        verify(callStore).dailyTrend(eq(mine), any(), any());
    }

    @Test
    void statsScopedToVisibleTasksAllWhenCallViewAll() {
        // admin sees all tasks -> store queried with the full visible set
        Set<String> all = Set.of("st_a", "st_b", "st_c");
        when(taskService.visibleTaskIdsForCalls()).thenReturn(all);
        when(callStore.totals(eq(all), any(), any())).thenReturn(Map.of("total", 10L, "success", 9L));
        when(callStore.dailyTrend(eq(all), any(), any())).thenReturn(java.util.List.of(
                Map.of("bucket", "2026-07-22", "success", true, "count", 9)));

        Map<String, Object> r = controller.stats("2026-07-01T00:00:00Z", "2026-07-22T00:00:00Z");

        assertThat(r.get("total")).isEqualTo(10L);
        assertThat(r.get("successRate")).isEqualTo(0.9);
        verify(callStore).totals(eq(all), eq(Instant.parse("2026-07-01T00:00:00Z")), eq(Instant.parse("2026-07-22T00:00:00Z")));
    }

    @Test
    void statsReturnsZeroWhenNoVisibleTasks() {
        // user with no tasks at all -> zero values, no store aggregation call beyond the empty check
        when(taskService.visibleTaskIdsForCalls()).thenReturn(Set.of());

        Map<String, Object> r = controller.stats(null, null);

        assertThat(r.get("total")).isEqualTo(0L);
        assertThat(r.get("success")).isEqualTo(0L);
        assertThat(r.get("fail")).isEqualTo(0L);
        assertThat(r.get("successRate")).isEqualTo(0.0);
        assertThat(r.get("trend")).isEqualTo(java.util.List.of());
    }

    @Test
    void breakdownRequiresCallViewAndScopesToVisibleTasks() {
        Set<String> mine = Set.of("st_mine_1");
        when(taskService.visibleTaskIdsForCalls()).thenReturn(mine);
        Map<String, Object> breakdownResult = Map.of("rows", java.util.List.of(), "taskTotals", java.util.List.of(), "taskCount", 0);
        when(callStore.breakdown(eq(mine), any(), any(), eq(10))).thenReturn(breakdownResult);

        Map<String, Object> r = controller.breakdown("2026-07-01T00:00:00Z", "2026-07-22T00:00:00Z", 10);

        verify(permissions).require(Permissions.SCHEDULER_CALL_VIEW);
        verify(callStore).breakdown(eq(mine), eq(Instant.parse("2026-07-01T00:00:00Z")), eq(Instant.parse("2026-07-22T00:00:00Z")), eq(10));
        assertThat(r.get("taskCount")).isEqualTo(0);
    }

    @Test
    void breakdownCapsLimitTo100AndFloorsTo1() {
        when(taskService.visibleTaskIdsForCalls()).thenReturn(Set.of("st_1"));
        when(callStore.breakdown(any(), any(), any(), anyInt())).thenReturn(
                Map.of("rows", java.util.List.of(), "taskTotals", java.util.List.of(), "taskCount", 0));

        controller.breakdown(null, null, 9999);
        verify(callStore).breakdown(any(), any(), any(), eq(100));

        controller.breakdown(null, null, 0);
        verify(callStore).breakdown(any(), any(), any(), eq(1));
    }

    @Test
    void breakdownEmptyVisibleTasksReturnsZeroPayload() {
        when(taskService.visibleTaskIdsForCalls()).thenReturn(Set.of());
        // store.breakdown on empty set returns zeros, but verify the controller still delegates
        when(callStore.breakdown(eq(Set.of()), any(), any(), anyInt())).thenReturn(
                Map.of("rows", java.util.List.of(), "taskTotals", java.util.List.of(), "taskCount", 0));

        Map<String, Object> r = controller.breakdown(null, null, 10);

        assertThat(r.get("rows")).isEqualTo(java.util.List.of());
        assertThat(r.get("taskTotals")).isEqualTo(java.util.List.of());
        assertThat(r.get("taskCount")).isEqualTo(0);
    }
}
