package io.litealert.scheduler.web;

import io.litealert.auth.permission.PermissionService;
import io.litealert.auth.permission.Permissions;
import io.litealert.common.error.BusinessException;
import io.litealert.common.error.ErrorCode;
import io.litealert.scheduler.SchedulerTaskDiffService;
import io.litealert.scheduler.SchedulerTaskService;
import io.litealert.scheduler.domain.ApiTaskConfig;
import io.litealert.scheduler.domain.SchedulerTask;
import io.litealert.scheduler.domain.SchedulerTaskType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SchedulerTaskControllerDiffTest {

    private SchedulerTaskService service;
    private PermissionService permissions;
    private SchedulerTaskDiffService diffService;
    private SchedulerTaskController controller;

    @BeforeEach
    void setUp() {
        service = mock(SchedulerTaskService.class);
        permissions = mock(PermissionService.class);
        diffService = new SchedulerTaskDiffService(); // real, deterministic
        controller = new SchedulerTaskController(service, permissions, diffService);
    }

    private SchedulerTask published(String url) {
        ApiTaskConfig c = new ApiTaskConfig();
        c.setMethod("GET"); c.setUrl(url);
        return SchedulerTask.builder()
                .id("st_1").ownerId("u_1").name("p").taskType(SchedulerTaskType.API)
                .cron("0 */5 * * * *").enabled(true).status(SchedulerTask.Status.PUBLISHED)
                .draftConfig(c).publishedConfig(c).build();
    }

    @Test
    void getEndpointExposesHasPendingChangesFalseWhenEqual() {
        when(service.getOrThrow("st_1")).thenReturn(published("https://a.example"));
        Map<String, Object> v = controller.get("st_1");
        assertThat(v.get("hasPendingChanges")).isEqualTo(false);
    }

    @Test
    void diffEndpointRequiresViewPermission() {
        SchedulerTask t = published("https://a.example");
        when(service.getOrThrow("st_1")).thenReturn(t);
        Map<String, Object> r = controller.diff("st_1");
        org.mockito.Mockito.verify(permissions).require(Permissions.SCHEDULER_TASK_VIEW);
        assertThat(r.get("hasPendingChanges")).isEqualTo(false);
        assertThat((List<?>) r.get("diffs")).isEmpty();
    }

    @Test
    void diffEndpointReturnsStructuredEntriesWhenChanged() {
        SchedulerTask t = published("https://old.example");
        ApiTaskConfig draft = new ApiTaskConfig();
        draft.setMethod("GET"); draft.setUrl("https://new.example");
        t.setDraftConfig(draft);
        when(service.getOrThrow("st_1")).thenReturn(t);

        Map<String, Object> r = controller.diff("st_1");
        assertThat(r.get("hasPendingChanges")).isEqualTo(true);
        List<Map<String, Object>> diffs = (List<Map<String, Object>>) r.get("diffs");
        assertThat(diffs).anySatisfy(e -> {
            assertThat(e.get("field")).isEqualTo("url");
            assertThat(e.get("changeType")).isEqualTo("CHANGED");
        });
    }

    @Test
    void diffEndpointHonorsVisibilityFromService() {
        // service.getOrThrow throws FORBIDDEN for non-owned/non-view-all → controller propagates
        when(service.getOrThrow("st_x")).thenThrow(new BusinessException(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> controller.diff("st_x")).isInstanceOf(BusinessException.class);
    }
}
