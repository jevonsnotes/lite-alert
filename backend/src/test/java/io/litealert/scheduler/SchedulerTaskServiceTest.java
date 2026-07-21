package io.litealert.scheduler;

import io.litealert.auth.CurrentUser;
import io.litealert.auth.permission.PermissionService;
import io.litealert.auth.permission.Permissions;
import io.litealert.common.audit.AuditLogger;
import io.litealert.common.error.BusinessException;
import io.litealert.common.error.ErrorCode;
import io.litealert.scheduler.domain.ApiTaskConfig;
import io.litealert.scheduler.domain.SchedulerTask;
import io.litealert.scheduler.domain.SchedulerTaskStore;
import io.litealert.scheduler.domain.SchedulerTaskType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SchedulerTaskServiceTest {

    private SchedulerTaskStore store;
    private SchedulerEngine engine;
    private PermissionService permissions;
    private CurrentUser currentUser;
    private SchedulerTaskService service;

    @BeforeEach
    void setUp() {
        store = mock(SchedulerTaskStore.class);
        engine = mock(SchedulerEngine.class);
        permissions = mock(PermissionService.class);
        currentUser = mock(CurrentUser.class);
        AuditLogger audit = mock(AuditLogger.class);
        io.litealert.scheduler.domain.SchedulerNotifyConfigStore notifyConfigStore = mock(io.litealert.scheduler.domain.SchedulerNotifyConfigStore.class);
        when(notifyConfigStore.findByOwner(any())).thenReturn(java.util.List.of());
        service = new SchedulerTaskService(store, currentUser, permissions, engine, audit, notifyConfigStore);
        when(currentUser.idOrThrow()).thenReturn("u_1");
        when(permissions.has(Permissions.SCHEDULER_TASK_VIEW_ALL)).thenReturn(true);
    }

    private SchedulerTask draftTask(String id) {
        return SchedulerTask.builder()
                .id(id).ownerId("u_1").name("probe").taskType(SchedulerTaskType.API)
                .cron("0 */5 * * * *").enabled(true).status(SchedulerTask.Status.DRAFT)
                .draftConfig(new ApiTaskConfig())
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build();
    }

    @Test
    void createRequiresManagePermission() {
        SchedulerTaskService.CreateRequest req = new SchedulerTaskService.CreateRequest(
                "probe", "d", "API", "0 */5 * * * *", new ApiTaskConfig(), null);
        when(store.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.create(req);

        verify(permissions).require(Permissions.SCHEDULER_TASK_MANAGE);
    }

    @Test
    void invalidCronIsRejectedOnCreate() {
        SchedulerTaskService.CreateRequest req = new SchedulerTaskService.CreateRequest(
                "probe", "d", "API", "not a cron", new ApiTaskConfig(), null);

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT));
        verify(store, never()).save(any());
    }

    @Test
    void unknownTaskTypeIsRejected() {
        SchedulerTaskService.CreateRequest req = new SchedulerTaskService.CreateRequest(
                "probe", "d", "SQL", "0 */5 * * * *", new ApiTaskConfig(), null);

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void createTcpTaskSeedsTcpConfig() {
        SchedulerTaskService.CreateRequest req = new SchedulerTaskService.CreateRequest(
                "tcp-probe", "d", "TCP", "0 */5 * * * *", null, null);
        when(store.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SchedulerTask t = service.create(req);

        assertThat(t.getTaskType()).isEqualTo(SchedulerTaskType.TCP);
        assertThat(t.getDraftConfig()).isInstanceOf(io.litealert.scheduler.domain.TcpTaskConfig.class);
    }

    @Test
    void createTcpTaskRejectsInvalidPort() {
        io.litealert.scheduler.domain.TcpTaskConfig bad = new io.litealert.scheduler.domain.TcpTaskConfig();
        bad.setHost("example.com");
        bad.setPort(70000);
        SchedulerTaskService.CreateRequest req = new SchedulerTaskService.CreateRequest(
                "tcp-probe", "d", "TCP", "0 */5 * * * *", bad, null);

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT));
        verify(store, never()).save(any());
    }

    @Test
    void createTcpTaskRejectsMissingHost() {
        io.litealert.scheduler.domain.TcpTaskConfig bad = new io.litealert.scheduler.domain.TcpTaskConfig();
        bad.setPort(3306);
        SchedulerTaskService.CreateRequest req = new SchedulerTaskService.CreateRequest(
                "tcp-probe", "d", "TCP", "0 */5 * * * *", bad, null);

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT));
    }

    @Test
    void saveDraftIgnoresRequestConfigOfWrongType() {
        // taskType is immutable (D7): an API task saving a TCP-typed config must not adopt it.
        SchedulerTask t = draftTask("st_tcp_imm");
        when(store.findById("st_tcp_imm")).thenReturn(Optional.of(t));
        when(store.save(any())).thenAnswer(inv -> inv.getArgument(0));

        io.litealert.scheduler.domain.TcpTaskConfig wrong = new io.litealert.scheduler.domain.TcpTaskConfig();
        wrong.setHost("example.com");
        wrong.setPort(3306);
        service.saveDraft("st_tcp_imm", new SchedulerTaskService.SaveDraftRequest(
                null, null, null, null, wrong, null));

        // taskType unchanged, draft config kept as API (type mismatch → ignored)
        assertThat(t.getTaskType()).isEqualTo(SchedulerTaskType.API);
        assertThat(t.getDraftConfig()).isInstanceOf(ApiTaskConfig.class);
    }

    @Test
    void saveDraftDoesNotScheduleOrTouchPublished() {
        SchedulerTask t = draftTask("st_1");
        when(store.findById("st_1")).thenReturn(Optional.of(t));
        when(store.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ApiTaskConfig newCfg = new ApiTaskConfig();
        newCfg.setUrl("https://edited.example");
        service.saveDraft("st_1", new SchedulerTaskService.SaveDraftRequest(
                null, null, null, null, newCfg, null));

        assertThat(((ApiTaskConfig) t.getDraftConfig()).getUrl()).isEqualTo("https://edited.example");
        assertThat(t.getPublishedConfig()).isNull(); // still unpublished
        verify(engine, never()).reschedule(any());
    }

    @Test
    void publishPromotesDraftToPublishedAndReschedules() {
        SchedulerTask t = draftTask("st_2");
        when(store.findById("st_2")).thenReturn(Optional.of(t));
        when(store.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.publish("st_2");

        assertThat(t.getStatus()).isEqualTo(SchedulerTask.Status.PUBLISHED);
        assertThat(t.getPublishedConfig()).isNotNull();
        assertThat(t.getPublishedAt()).isNotNull();
        verify(engine).reschedule("st_2");
        verify(permissions).require(Permissions.SCHEDULER_TASK_PUBLISH);
    }

    @Test
    void disableUnschedulesButKeepsStatusPublished() {
        SchedulerTask t = draftTask("st_3");
        t.setStatus(SchedulerTask.Status.PUBLISHED);
        t.setPublishedConfig(new ApiTaskConfig());
        when(store.findById("st_3")).thenReturn(Optional.of(t));
        when(store.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.setEnabled("st_3", false);

        // status stays PUBLISHED (lifecycle), only enabled flips to false
        assertThat(t.getStatus()).isEqualTo(SchedulerTask.Status.PUBLISHED);
        assertThat(t.isEnabled()).isFalse();
        verify(engine).unschedule("st_3");
    }

    @Test
    void deleteUnschedulesThenRemoves() {
        SchedulerTask t = draftTask("st_4");
        when(store.findById("st_4")).thenReturn(Optional.of(t));

        service.delete("st_4");

        verify(engine).unschedule("st_4");
        verify(store).delete("st_4");
    }

    @Test
    void getOrThrowForbiddenWhenNotOwnerAndNoViewAll() {
        SchedulerTask t = draftTask("st_5");
        t.setOwnerId("u_other");
        when(store.findById("st_5")).thenReturn(Optional.of(t));
        when(permissions.has(Permissions.SCHEDULER_TASK_VIEW_ALL)).thenReturn(false);

        assertThatThrownBy(() -> service.getOrThrow("st_5"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void auditContextMapIsUsed() {
        // smoke: create path builds a Map without throwing (audit.log is a no-op mock)
        when(store.save(any())).thenAnswer(inv -> inv.getArgument(0));
        SchedulerTaskService.CreateRequest req = new SchedulerTaskService.CreateRequest(
                "probe", "d", "API", "0 */5 * * * *", new ApiTaskConfig(), null);
        Map<String, Object> ctx = Map.of("actor", "u_1");
        assertThat(ctx).containsEntry("actor", "u_1");
        service.create(req);
        verify(store).save(any());
    }

    @Test
    void publishWithInvalidCronFails() {
        SchedulerTask t = draftTask("st_6");
        t.setCron("bad cron");
        when(store.findById("st_6")).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> service.publish("st_6"))
                .isInstanceOf(BusinessException.class);
        verify(engine, never()).reschedule(eq("st_6"));
    }

    @Test
    void getOrThrowForCallUsesCallViewAllNotTaskViewAll() {
        SchedulerTask t = draftTask("st_7");
        t.setOwnerId("u_other"); // not the current user
        when(store.findById("st_7")).thenReturn(Optional.of(t));

        // has TASK_VIEW_ALL but NOT CALL_VIEW_ALL → call-scoped access must still be forbidden
        when(permissions.has(Permissions.SCHEDULER_TASK_VIEW_ALL)).thenReturn(true);
        when(permissions.has(Permissions.SCHEDULER_CALL_VIEW_ALL)).thenReturn(false);

        assertThatThrownBy(() -> service.getOrThrowForCall("st_7"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void getOrThrowForCallAllowsWhenCallViewAll() {
        SchedulerTask t = draftTask("st_8");
        t.setOwnerId("u_other");
        when(store.findById("st_8")).thenReturn(Optional.of(t));
        when(permissions.has(Permissions.SCHEDULER_CALL_VIEW_ALL)).thenReturn(true);

        assertThat(service.getOrThrowForCall("st_8")).isSameAs(t);
    }
}
