package io.litealert.topic;

import io.litealert.auth.CurrentUser;
import io.litealert.common.audit.AuditLogger;
import io.litealert.common.config.LiteAlertProperties;
import io.litealert.namespace.NamespaceService;
import io.litealert.topic.domain.Topic;
import io.litealert.topic.domain.TopicStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TopicServiceTest {

    private TopicStore store;
    private AuditLogger audit;
    private TopicService service;

    @BeforeEach
    void setUp() {
        store = mock(TopicStore.class);
        NamespaceService namespaceService = mock(NamespaceService.class);
        CurrentUser currentUser = mock(CurrentUser.class);
        audit = mock(AuditLogger.class);
        LiteAlertProperties props = new LiteAlertProperties();
        when(currentUser.idOrThrow()).thenReturn("u_1");
        when(currentUser.isAdmin()).thenReturn(false);
        when(store.save(any(Topic.class))).thenAnswer(invocation -> invocation.getArgument(0));
        service = new TopicService(store, namespaceService, currentUser, audit, props);
    }

    @Test
    void enableWritesAuditEvent() {
        Topic topic = Topic.builder()
                .id("t_1")
                .namespaceId("ns_1")
                .name("paid")
                .ownerId("u_1")
                .status(Topic.Status.DISABLED)
                .build();
        when(store.findById("t_1")).thenReturn(Optional.of(topic));

        service.enable("t_1");

        verify(audit).log(eq("topic.enable"), any());
    }
}
