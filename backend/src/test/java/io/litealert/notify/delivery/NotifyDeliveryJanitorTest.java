package io.litealert.notify.delivery;

import io.litealert.admin.settings.SystemSettings;
import io.litealert.admin.settings.SystemSettingsService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotifyDeliveryJanitorTest {

    @Test
    void readsDeliveryRetentionOnEverySweep() {
        NotifyDeliveryStore store = mock(NotifyDeliveryStore.class);
        SystemSettingsService settings = mock(SystemSettingsService.class);
        SystemSettings first = new SystemSettings();
        first.setDeliveryRetention(new SystemSettings.Span(7, SystemSettings.Unit.DAYS));
        SystemSettings second = new SystemSettings();
        second.setDeliveryRetention(new SystemSettings.Span(30, SystemSettings.Unit.DAYS));
        when(settings.current()).thenReturn(first, second);
        AtomicInteger removed = new AtomicInteger();
        when(store.deleteFinishedBefore(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            removed.incrementAndGet();
            return 1;
        });

        NotifyDeliveryJanitor janitor = new NotifyDeliveryJanitor(store, settings);
        janitor.sweep(LocalDate.of(2026, 6, 19));
        janitor.sweep(LocalDate.of(2026, 6, 19));

        assertThat(removed).hasValue(2);
        org.mockito.Mockito.verify(settings, org.mockito.Mockito.times(2)).current();
    }
}
