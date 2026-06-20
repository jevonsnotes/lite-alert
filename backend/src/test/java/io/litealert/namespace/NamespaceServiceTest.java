package io.litealert.namespace;

import io.litealert.auth.CurrentUser;
import io.litealert.auth.permission.PermissionService;
import io.litealert.common.audit.AuditLogger;
import io.litealert.common.error.BusinessException;
import io.litealert.common.error.ErrorCode;
import io.litealert.namespace.domain.Namespace;
import io.litealert.namespace.domain.NamespaceStore;
import io.litealert.notify.domain.Subscription;
import io.litealert.notify.domain.SubscriptionStore;
import io.litealert.topic.domain.Topic;
import io.litealert.topic.domain.TopicStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NamespaceServiceTest {

    private NamespaceStore store;
    private TopicStore topicStore;
    private SubscriptionStore subscriptionStore;
    private AuditLogger audit;
    private NamespaceService service;

    @BeforeEach
    void setUp() {
        store = mock(NamespaceStore.class);
        topicStore = mock(TopicStore.class);
        subscriptionStore = mock(SubscriptionStore.class);
        CurrentUser currentUser = mock(CurrentUser.class);
        PermissionService permissionService = mock(PermissionService.class);
        audit = mock(AuditLogger.class);
        when(currentUser.idOrThrow()).thenReturn("u_1");
        when(store.save(any(Namespace.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(topicStore.save(any(Topic.class))).thenAnswer(invocation -> invocation.getArgument(0));
        service = new NamespaceService(store, topicStore, subscriptionStore, currentUser, audit, permissionService);
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

    @Test
    void renamesNamespaceAndSynchronizesTopicNamespaceName() {
        Namespace ns = Namespace.builder()
                .id("ns_1")
                .name("orders")
                .ownerId("u_1")
                .status(Namespace.Status.ACTIVE)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
        Topic topic = Topic.builder()
                .id("t_1")
                .namespaceId("ns_1")
                .namespaceName("orders")
                .name("paid")
                .ownerId("u_1")
                .status(Topic.Status.PUBLISHED)
                .build();
        when(store.findById("ns_1")).thenReturn(Optional.of(ns));
        when(store.findByName("orders_new")).thenReturn(Optional.empty());
        when(topicStore.findByNamespace("ns_1")).thenReturn(List.of(topic));

        Namespace updated = service.update("ns_1", "orders_new", "new desc");

        assertThat(updated.getName()).isEqualTo("orders_new");
        assertThat(updated.getDescription()).isEqualTo("new desc");
        assertThat(topic.getNamespaceName()).isEqualTo("orders_new");
        verify(topicStore).save(topic);
        verify(audit).log(eq("namespace.rename"), any());
    }

    @Test
    void rejectsDuplicateNamespaceNameWhenRenaming() {
        Namespace ns = Namespace.builder()
                .id("ns_1")
                .name("orders")
                .ownerId("u_1")
                .status(Namespace.Status.ACTIVE)
                .build();
        Namespace other = Namespace.builder()
                .id("ns_2")
                .name("orders_new")
                .ownerId("u_1")
                .status(Namespace.Status.ACTIVE)
                .build();
        when(store.findById("ns_1")).thenReturn(Optional.of(ns));
        when(store.findByName("orders_new")).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.update("ns_1", "orders_new", null))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.NAMESPACE_NAME_TAKEN);
    }

    @Test
    void copiesNamespaceTopicsAndSubscriptionsWithSelectedStatusMode() {
        Namespace ns = Namespace.builder()
                .id("ns_1")
                .name("orders")
                .description("source")
                .ownerId("u_1")
                .status(Namespace.Status.ACTIVE)
                .build();
        Topic published = Topic.builder()
                .id("t_1")
                .namespaceId("ns_1")
                .namespaceName("orders")
                .name("paid")
                .ownerId("u_1")
                .status(Topic.Status.PUBLISHED)
                .auth(new Topic.Auth())
                .build();
        Topic disabled = Topic.builder()
                .id("t_2")
                .namespaceId("ns_1")
                .namespaceName("orders")
                .name("failed")
                .ownerId("u_1")
                .status(Topic.Status.DISABLED)
                .auth(new Topic.Auth())
                .build();
        when(store.findById("ns_1")).thenReturn(Optional.of(ns));
        when(store.findByName("orders_copy")).thenReturn(Optional.empty());
        when(topicStore.findByNamespace("ns_1")).thenReturn(List.of(published, disabled));
        when(subscriptionStore.getOrEmpty("t_1")).thenReturn(Subscription.builder().topicId("t_1").contactIds(List.of("c_1")).build());
        when(subscriptionStore.getOrEmpty("t_2")).thenReturn(Subscription.builder().topicId("t_2").contactIds(List.of("c_2", "c_3")).build());

        Namespace copy = service.copy("ns_1", "orders_copy", "copy", true);

        assertThat(copy.getId()).isNotEqualTo("ns_1");
        assertThat(copy.getName()).isEqualTo("orders_copy");
        ArgumentCaptor<Topic> topicCaptor = ArgumentCaptor.forClass(Topic.class);
        verify(topicStore, org.mockito.Mockito.times(2)).save(topicCaptor.capture());
        assertThat(topicCaptor.getAllValues())
                .extracting(Topic::getName)
                .containsExactly("paid", "failed");
        assertThat(topicCaptor.getAllValues())
                .allSatisfy(t -> {
                    assertThat(t.getNamespaceId()).isEqualTo(copy.getId());
                    assertThat(t.getNamespaceName()).isEqualTo("orders_copy");
                    assertThat(t.getStatus()).isEqualTo(Topic.Status.DRAFT);
                });
        verify(subscriptionStore).save(topicCaptor.getAllValues().get(0).getId(), List.of("c_1"));
        verify(subscriptionStore).save(topicCaptor.getAllValues().get(1).getId(), List.of("c_2", "c_3"));
        verify(audit).log(eq("namespace.copy"), any());
    }
}

