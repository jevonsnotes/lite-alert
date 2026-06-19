package io.litealert.notify.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.litealert.common.audit.AuditLogger;
import io.litealert.notify.channel.NotifyChannelRegistry;
import io.litealert.notify.domain.NotifyTargetStore;
import io.litealert.notify.template.TemplateRenderer;
import io.litealert.topic.domain.TopicStore;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotifyDeliveryWorkerTest {

    @Test
    void tickSkipsWhenDeliveryTableIsNotReady() {
        NotifyDeliveryStore store = mock(NotifyDeliveryStore.class);
        when(store.tableReady()).thenReturn(false);
        NotifyDeliveryWorker worker = new NotifyDeliveryWorker(
                store,
                mock(TopicStore.class),
                mock(NotifyTargetStore.class),
                mock(NotifyChannelRegistry.class),
                mock(AuditLogger.class),
                new ObjectMapper(),
                mock(TemplateRenderer.class));

        assertThatCode(worker::tick).doesNotThrowAnyException();
    }
}
