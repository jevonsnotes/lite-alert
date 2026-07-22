package io.litealert.scheduler.web;

import io.litealert.auth.permission.PermissionService;
import io.litealert.common.error.BusinessException;
import io.litealert.scheduler.SchedulerTaskService;
import io.litealert.scheduler.domain.SchedulerTask;
import io.litealert.scheduler.domain.SchedulerTaskCall;
import io.litealert.scheduler.domain.SchedulerTaskCallStore;
import io.litealert.scheduler.domain.SchedulerTaskType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the call-record view exposes the TCP fields (protocol/tcpTarget/tcpOk) added in
 * add-tcp-task-type. Regression guard for the bug where these were omitted from toView, causing the
 * frontend to fall back to "API" for TCP calls.
 */
class SchedulerTaskCallControllerViewTest {

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
        // ownership check passes
        when(taskService.getOrThrowForCall(any())).thenReturn(SchedulerTask.builder()
                .id("st_tcp").ownerId("u_1").build());
    }

    @Test
    void tcpCallViewExposesTcpFields() {
        SchedulerTaskCall call = SchedulerTaskCall.builder()
                .id("stc_1").taskId("st_tcp").triggeredAt(Instant.now())
                .protocol("TCP").tcpTarget("localhost:18793").tcpOk(true)
                .durationMs(12L).status(SchedulerTaskCall.Status.SUCCESS)
                .responseExcerpt("connected to localhost:18793 in 12ms")
                .createdAt(Instant.now()).build();
        when(callStore.findById("stc_1")).thenReturn(Optional.of(call));

        Map<String, Object> v = controller.callDetail("stc_1");

        assertThat(v.get("protocol")).isEqualTo("TCP");
        assertThat(v.get("tcpTarget")).isEqualTo("localhost:18793");
        assertThat(v.get("tcpOk")).isEqualTo(true);
        assertThat(v.get("responseExcerpt")).asString().contains("connected to");
        // API-only fields are null for TCP
        assertThat(v.get("method")).isNull();
        assertThat(v.get("url")).isNull();
        assertThat(v.get("httpStatus")).isNull();
    }

    @Test
    void apiCallViewDefaultsProtocolToApiWhenMissing() {
        // legacy rows written before the protocol column existed should still render as API
        SchedulerTaskCall call = SchedulerTaskCall.builder()
                .id("stc_2").taskId("st_api").triggeredAt(Instant.now())
                .protocol(null).method("GET").url("https://x.example")
                .httpStatus(200).durationMs(5L).status(SchedulerTaskCall.Status.SUCCESS)
                .createdAt(Instant.now()).build();
        when(callStore.findById("stc_2")).thenReturn(Optional.of(call));

        Map<String, Object> v = controller.callDetail("stc_2");

        assertThat(v.get("protocol")).isEqualTo("API");
        assertThat(v.get("method")).isEqualTo("GET");
        assertThat(v.get("tcpTarget")).isNull();
        assertThat(v.get("tcpOk")).isNull();
    }

    @Test
    void callDetailEnforcesVisibility() {
        when(callStore.findById("nope")).thenReturn(Optional.empty());
        org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
                () -> controller.callDetail("nope"));
    }
}
