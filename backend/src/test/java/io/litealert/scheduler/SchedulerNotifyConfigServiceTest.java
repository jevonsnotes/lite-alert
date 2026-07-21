package io.litealert.scheduler;

import io.litealert.auth.CurrentUser;
import io.litealert.auth.permission.PermissionService;
import io.litealert.auth.permission.Permissions;
import io.litealert.common.audit.AuditLogger;
import io.litealert.common.error.BusinessException;
import io.litealert.scheduler.domain.SchedulerNotifyConfig;
import io.litealert.scheduler.domain.SchedulerNotifyConfigStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SchedulerNotifyConfigServiceTest {

    private SchedulerNotifyConfigStore store;
    private CurrentUser currentUser;
    private PermissionService permissions;
    private SchedulerNotifyConfigService service;

    @BeforeEach
    void setUp() {
        store = mock(SchedulerNotifyConfigStore.class);
        currentUser = mock(CurrentUser.class);
        permissions = mock(PermissionService.class);
        AuditLogger audit = mock(AuditLogger.class);
        service = new SchedulerNotifyConfigService(store, currentUser, permissions, audit);
        when(currentUser.idOrThrow()).thenReturn("u_me");
    }

    private SchedulerNotifyConfig cfg(String id, String owner) {
        return SchedulerNotifyConfig.builder().id(id).ownerId(owner).name("n")
                .method("POST").url("https://x").triggerOn(SchedulerNotifyConfig.TriggerOn.FAIL).build();
    }

    @Test
    void createRequiresManagePermission() {
        when(store.save(org.mockito.ArgumentMatchers.any())).thenAnswer(i -> i.getArgument(0));
        SchedulerNotifyConfigService.CreateRequest req = new SchedulerNotifyConfigService.CreateRequest(
                "n", "POST", "https://x", null, "{}", SchedulerNotifyConfig.TriggerOn.FAIL);
        service.create(req);
        org.mockito.Mockito.verify(permissions).require(Permissions.SCHEDULER_NOTIFY_MANAGE);
    }

    @Test
    void createRejectsMissingFields() {
        SchedulerNotifyConfigService.CreateRequest req = new SchedulerNotifyConfigService.CreateRequest(
                "", "POST", "https://x", null, "{}", SchedulerNotifyConfig.TriggerOn.FAIL);
        assertThatThrownBy(() -> service.create(req)).isInstanceOf(BusinessException.class);
    }

    @Test
    void getOrThrowForbidsOtherOwnersConfig() {
        when(store.findById("sn_x")).thenReturn(Optional.of(cfg("sn_x", "u_other")));
        assertThatThrownBy(() -> service.getOrThrow("sn_x")).isInstanceOf(BusinessException.class);
    }

    @Test
    void deleteRequiresManageAndOwnership() {
        when(store.findById("sn_1")).thenReturn(Optional.of(cfg("sn_1", "u_me")));
        service.delete("sn_1");
        org.mockito.Mockito.verify(permissions).require(Permissions.SCHEDULER_NOTIFY_MANAGE);
    }

    @Test
    void disableSetsEnabledFalseAndPersists() {
        SchedulerNotifyConfig c = cfg("sn_1", "u_me");
        when(store.findById("sn_1")).thenReturn(Optional.of(c));
        when(store.save(org.mockito.ArgumentMatchers.any())).thenAnswer(i -> i.getArgument(0));
        service.setEnabled("sn_1", false);
        assertThat(c.isEnabled()).isFalse();
        org.mockito.Mockito.verify(permissions).require(Permissions.SCHEDULER_NOTIFY_MANAGE);
    }

    @Test
    void disableForbiddenForOtherOwner() {
        when(store.findById("sn_x")).thenReturn(Optional.of(cfg("sn_x", "u_other")));
        assertThatThrownBy(() -> service.setEnabled("sn_x", false)).isInstanceOf(BusinessException.class);
    }

    @Test
    void maskedUrlSubmissionDoesNotClobberRealUrl() {
        SchedulerNotifyConfig c = cfg("sn_1", "u_me");
        c.setUrl("https://oapi.dingtalk.com/robot/send?access_token=SECRET123");
        when(store.findById("sn_1")).thenReturn(Optional.of(c));
        when(store.save(org.mockito.ArgumentMatchers.any())).thenAnswer(i -> i.getArgument(0));

        // user edited only the name; form still holds the masked url "...?***"
        service.update("sn_1", new SchedulerNotifyConfigService.UpdateRequest(
                "new-name", null, "https://oapi.dingtalk.com/robot/send?***",
                null, null, null, null));

        // real URL preserved (not overwritten with the mask)
        assertThat(c.getUrl()).isEqualTo("https://oapi.dingtalk.com/robot/send?access_token=SECRET123");
        assertThat(c.getName()).isEqualTo("new-name");
    }

    @Test
    void realUrlSubmissionUpdatesUrl() {
        SchedulerNotifyConfig c = cfg("sn_1", "u_me");
        c.setUrl("https://old.example");
        when(store.findById("sn_1")).thenReturn(Optional.of(c));
        when(store.save(org.mockito.ArgumentMatchers.any())).thenAnswer(i -> i.getArgument(0));

        service.update("sn_1", new SchedulerNotifyConfigService.UpdateRequest(
                null, null, "https://new.example?token=abc", null, null, null, null));
        assertThat(c.getUrl()).isEqualTo("https://new.example?token=abc");
    }
}
