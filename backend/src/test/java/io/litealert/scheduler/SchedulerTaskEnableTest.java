package io.litealert.scheduler;

import io.litealert.auth.CurrentUser;
import io.litealert.auth.permission.PermissionService;
import io.litealert.auth.permission.Permissions;
import io.litealert.common.audit.AuditLogger;
import io.litealert.scheduler.domain.ApiTaskConfig;
import io.litealert.scheduler.domain.SchedulerTask;
import io.litealert.scheduler.domain.SchedulerTaskStore;
import io.litealert.scheduler.domain.SchedulerTaskType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression for the disable→enable bug: reschedule must run AFTER the store save so the engine
 * re-reads the now-PUBLISHED row (otherwise isSchedulable() sees a stale DISABLED row and skips).
 */
class SchedulerTaskEnableTest {

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
        service = new SchedulerTaskService(store, currentUser, permissions, engine, audit, notifyConfigStore);
        when(currentUser.idOrThrow()).thenReturn("u_1");
    }

    private SchedulerTask disabled() {
        // disabled = published lifecycle but enabled=false (status no longer has DISABLED)
        return SchedulerTask.builder()
                .id("st_1").ownerId("u_1").name("p").taskType(SchedulerTaskType.API)
                .cron("0 */5 * * * *").enabled(false).status(SchedulerTask.Status.PUBLISHED)
                .draftConfig(new ApiTaskConfig())
                .publishedConfig(new ApiTaskConfig())
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build();
    }

    @Test
    void enablingReschedulesAfterSave() {
        SchedulerTask t = disabled();
        when(store.findById("st_1")).thenReturn(Optional.of(t));
        // store.save returns the same mutated object, then reschedule re-reads it
        when(store.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.setEnabled("st_1", true);

        ArgumentCaptor<SchedulerTask> saved = ArgumentCaptor.forClass(SchedulerTask.class);
        verify(store).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(SchedulerTask.Status.PUBLISHED);
        assertThat(saved.getValue().isEnabled()).isTrue();
        // reschedule called exactly once, AFTER save (order enforced by call sequence)
        verify(engine, times(1)).reschedule("st_1");
    }

    @Test
    void disablingUnschedulesAfterSave() {
        SchedulerTask t = disabled();
        t.setStatus(SchedulerTask.Status.PUBLISHED);
        t.setEnabled(true);
        when(store.findById("st_1")).thenReturn(Optional.of(t));
        when(store.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.setEnabled("st_1", false);
        verify(engine, times(1)).unschedule("st_1");
        verify(engine, times(0)).reschedule(any());
    }

    @Test
    void publishReenablesPreviouslyDisabledTask() {
        // a task that was disabled: status=PUBLISHED (lifecycle), enabled=false
        SchedulerTask t = disabled();
        t.setEnabled(false);
        when(store.findById("st_1")).thenReturn(Optional.of(t));
        when(store.save(any())).thenAnswer(i -> i.getArgument(0));

        service.publish("st_1");

        // publish resets enabled=true so isSchedulable() is true and engine reschedules
        assertThat(t.getStatus()).isEqualTo(SchedulerTask.Status.PUBLISHED);
        assertThat(t.isEnabled()).isTrue();
        assertThat(t.isSchedulable()).isTrue();
        verify(engine, times(1)).reschedule("st_1");
    }
}
