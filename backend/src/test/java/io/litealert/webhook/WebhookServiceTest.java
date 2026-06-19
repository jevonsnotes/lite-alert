package io.litealert.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.litealert.apikey.domain.ApiKeyStore;
import io.litealert.common.audit.AuditLogger;
import io.litealert.common.config.LiteAlertProperties;
import io.litealert.common.error.BusinessException;
import io.litealert.common.error.ErrorCode;
import io.litealert.namespace.domain.Namespace;
import io.litealert.namespace.domain.NamespaceStore;
import io.litealert.notify.delivery.NotifyDeliveryService;
import io.litealert.topic.domain.Topic;
import io.litealert.topic.domain.TopicStore;
import io.litealert.transform.JsonSchemaService;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebhookServiceTest {

    @Test
    void rejectsWebhookWhenNamespaceIsDisabled() {
        TopicStore topicStore = mock(TopicStore.class);
        NamespaceStore namespaceStore = mock(NamespaceStore.class);
        Topic topic = Topic.builder()
                .id("t_1")
                .namespaceId("ns_1")
                .namespaceName("orders")
                .name("paid")
                .status(Topic.Status.PUBLISHED)
                .auth(new Topic.Auth(Topic.AuthMode.NONE, java.util.List.of(), null))
                .build();
        Namespace ns = Namespace.builder()
                .id("ns_1")
                .name("orders")
                .status(Namespace.Status.DISABLED)
                .build();
        when(topicStore.findForWebhook("orders", "paid")).thenReturn(Optional.of(topic));
        when(namespaceStore.findById("ns_1")).thenReturn(Optional.of(ns));

        WebhookService service = new WebhookService(
                topicStore,
                namespaceStore,
                mock(ApiKeyStore.class),
                mock(ApiKeyAuthenticator.class),
                mock(IpAllowlist.class),
                mock(RateLimiter.class),
                mock(JsonSchemaService.class),
                mock(NotifyDeliveryService.class),
                mock(AuditLogger.class),
                new LiteAlertProperties());

        assertThatThrownBy(() -> service.handle("orders", "paid", null, null,
                new ObjectMapper().createObjectNode(), "127.0.0.1"))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.NAMESPACE_DISABLED);
    }
}
