package io.litealert.topic;

import io.litealert.auth.CurrentUser;
import io.litealert.auth.permission.PermissionService;
import io.litealert.common.audit.AuditLogger;
import io.litealert.common.error.BusinessException;
import io.litealert.common.error.ErrorCode;
import io.litealert.namespace.NamespaceService;
import io.litealert.notify.domain.Subscription;
import io.litealert.notify.domain.SubscriptionStore;
import io.litealert.topic.domain.Topic;
import io.litealert.topic.domain.TopicStore;
import io.litealert.topic.domain.TopicChannelTemplateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TopicServiceTest {

    private TopicStore store;
    private SubscriptionStore subscriptionStore;
    private TopicChannelTemplateStore templateStore;
    private AuditLogger audit;
    private TopicService service;

    @BeforeEach
    void setUp() {
        store = mock(TopicStore.class);
        subscriptionStore = mock(SubscriptionStore.class);
        templateStore = mock(TopicChannelTemplateStore.class);
        NamespaceService namespaceService = mock(NamespaceService.class);
        CurrentUser currentUser = mock(CurrentUser.class);
        PermissionService permissionService = mock(PermissionService.class);
        audit = mock(AuditLogger.class);
        when(currentUser.idOrThrow()).thenReturn("u_1");
        when(store.save(any(Topic.class))).thenAnswer(invocation -> invocation.getArgument(0));
        service = new TopicService(store, namespaceService, subscriptionStore, templateStore, currentUser, audit, permissionService);
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

    @Test
    void renamesDraftTopicAndRejectsDuplicateName() {
        Topic topic = Topic.builder()
                .id("t_1")
                .namespaceId("ns_1")
                .namespaceName("orders")
                .name("paid")
                .ownerId("u_1")
                .status(Topic.Status.DRAFT)
                .build();
        when(store.findById("t_1")).thenReturn(Optional.of(topic));
        when(store.findByNamespaceAndName("ns_1", "paid_new")).thenReturn(Optional.empty());

        Topic updated = service.update("t_1", new TopicService.UpdateRequest(
                "paid_new", "new desc", null, null, null, null, null));

        assertThat(updated.getName()).isEqualTo("paid_new");
        assertThat(updated.getDescription()).isEqualTo("new desc");
        verify(audit).log(eq("topic.rename"), any());
    }

    @Test
    void rejectsRenamingPublishedTopic() {
        Topic topic = Topic.builder()
                .id("t_1")
                .namespaceId("ns_1")
                .name("paid")
                .ownerId("u_1")
                .status(Topic.Status.PUBLISHED)
                .build();
        when(store.findById("t_1")).thenReturn(Optional.of(topic));

        assertThatThrownBy(() -> service.update("t_1", new TopicService.UpdateRequest(
                "paid_new", null, null, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    void rejectsRenamingDisabledTopic() {
        Topic topic = Topic.builder()
                .id("t_1")
                .namespaceId("ns_1")
                .name("paid")
                .ownerId("u_1")
                .status(Topic.Status.DISABLED)
                .build();
        when(store.findById("t_1")).thenReturn(Optional.of(topic));

        assertThatThrownBy(() -> service.update("t_1", new TopicService.UpdateRequest(
                "paid_new", null, null, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    void copiesTopicConfigurationSubscriptionAndStatusMode() {
        Topic source = Topic.builder()
                .id("t_1")
                .namespaceId("ns_1")
                .namespaceName("orders")
                .name("paid")
                .description("source")
                .ownerId("u_1")
                .status(Topic.Status.PUBLISHED)
                .auth(new Topic.Auth())
                .build();
        when(store.findById("t_1")).thenReturn(Optional.of(source));
        when(store.findByNamespaceAndName("ns_1", "paid_copy")).thenReturn(Optional.empty());
        when(subscriptionStore.getOrEmpty("t_1")).thenReturn(Subscription.builder().topicId("t_1").contactIds(List.of("c_1", "c_2")).build());

        Topic copy = service.copy("t_1", "paid_copy", "copy", true);

        assertThat(copy.getId()).isNotEqualTo("t_1");
        assertThat(copy.getNamespaceId()).isEqualTo("ns_1");
        assertThat(copy.getName()).isEqualTo("paid_copy");
        assertThat(copy.getDescription()).isEqualTo("copy");
        assertThat(copy.getStatus()).isEqualTo(Topic.Status.DRAFT);
        ArgumentCaptor<Topic> topicCaptor = ArgumentCaptor.forClass(Topic.class);
        verify(store).save(topicCaptor.capture());
        assertThat(topicCaptor.getValue().getPublishedAt()).isNull();
        verify(subscriptionStore).save(copy.getId(), List.of("c_1", "c_2"));
        verify(audit).log(eq("topic.copy"), any());
    }
}

