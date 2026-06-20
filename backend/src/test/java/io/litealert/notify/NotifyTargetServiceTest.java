package io.litealert.notify;

import io.litealert.auth.CurrentUser;
import io.litealert.auth.permission.PermissionService;
import io.litealert.common.audit.AuditLogger;
import io.litealert.common.error.BusinessException;
import io.litealert.notify.domain.NotifyTarget;
import io.litealert.notify.domain.NotifyTargetStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotifyTargetServiceTest {

    private NotifyTargetStore store;
    private AuditLogger audit;
    private NotifyTargetService service;

    @BeforeEach
    void setUp() {
        store = mock(NotifyTargetStore.class);
        CurrentUser currentUser = mock(CurrentUser.class);
        PermissionService permissionService = mock(PermissionService.class);
        audit = mock(AuditLogger.class);
        when(currentUser.idOrThrow()).thenReturn("u_1");
        when(store.save(any(NotifyTarget.class))).thenAnswer(invocation -> invocation.getArgument(0));
        service = new NotifyTargetService(store, currentUser, audit, permissionService);
    }

    @Test
    void updateRejectsDuplicateEndpointWithinSameTypeAndUser() {
        NotifyTarget target = target("c_1", NotifyTarget.Type.EMAIL, "old@example.com");
        NotifyTarget duplicate = target("c_2", NotifyTarget.Type.EMAIL, "new@example.com");
        when(store.findById("c_1")).thenReturn(Optional.of(target));
        when(store.findByUser("u_1")).thenReturn(List.of(target, duplicate));

        assertThatThrownBy(() -> service.update("c_1", "Ops", null, "new@example.com", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("same endpoint");
    }

    @Test
    void updateWritesAuditEvent() {
        NotifyTarget target = target("c_1", NotifyTarget.Type.WEBHOOK, "https://old.example/hook");
        when(store.findById("c_1")).thenReturn(Optional.of(target));
        when(store.findByUser("u_1")).thenReturn(List.of(target));

        service.update("c_1", "Ops Hook", true, "https://new.example/hook", "Bearer token");

        verify(audit).log(eq("target.update"), any());
    }

    private NotifyTarget target(String id, NotifyTarget.Type type, String endpoint) {
        return NotifyTarget.builder()
                .id(id)
                .userId("u_1")
                .type(type)
                .label("target")
                .endpoint(endpoint)
                .enabled(true)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
    }
}
