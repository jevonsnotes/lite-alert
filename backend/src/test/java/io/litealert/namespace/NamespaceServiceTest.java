package io.litealert.namespace;

import io.litealert.auth.CurrentUser;
import io.litealert.common.audit.AuditLogger;
import io.litealert.namespace.domain.Namespace;
import io.litealert.namespace.domain.NamespaceStore;
import io.litealert.topic.domain.TopicStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NamespaceServiceTest {

    private NamespaceStore store;
    private AuditLogger audit;
    private NamespaceService service;

    @BeforeEach
    void setUp() {
        store = mock(NamespaceStore.class);
        TopicStore topicStore = mock(TopicStore.class);
        CurrentUser currentUser = mock(CurrentUser.class);
        audit = mock(AuditLogger.class);
        when(currentUser.idOrThrow()).thenReturn("u_1");
        when(currentUser.isAdmin()).thenReturn(false);
        when(store.save(any(Namespace.class))).thenAnswer(invocation -> invocation.getArgument(0));
        service = new NamespaceService(store, topicStore, currentUser, audit);
    }

    @Test
    void disableAndEnableNamespaceKeepsTopicStatusIndependentAndWritesAudit() {
        Namespace ns = Namespace.builder()
                .id("ns_1")
                .name("orders")
                .ownerId("u_1")
                .status(Namespace.Status.ACTIVE)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
        when(store.findById("ns_1")).thenReturn(Optional.of(ns));

        Namespace disabled = service.disable("ns_1");
        assertThat(disabled.getStatus()).isEqualTo(Namespace.Status.DISABLED);
        assertThat(disabled.getDisabledAt()).isNotNull();
        assertThat(disabled.getDisabledBy()).isEqualTo("u_1");
        verify(audit).log(eq("namespace.disable"), any());

        Namespace enabled = service.enable("ns_1");
        assertThat(enabled.getStatus()).isEqualTo(Namespace.Status.ACTIVE);
        assertThat(enabled.getDisabledAt()).isNull();
        assertThat(enabled.getDisabledBy()).isNull();
        verify(audit).log(eq("namespace.enable"), any());
    }
}
