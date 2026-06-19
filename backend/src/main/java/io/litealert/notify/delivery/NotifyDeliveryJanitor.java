package io.litealert.notify.delivery;

import io.litealert.admin.settings.SystemSettings;
import io.litealert.admin.settings.SystemSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotifyDeliveryJanitor {

    private final NotifyDeliveryStore store;
    private final SystemSettingsService settings;

    @Scheduled(cron = "0 23 3 * * *", zone = "Asia/Shanghai")
    public void runDaily() {
        sweep(LocalDate.now(ZoneId.systemDefault()));
    }

    public void sweep(LocalDate today) {
        SystemSettings.Span retention = settings.current().getDeliveryRetention();
        int removed = store.deleteFinishedBefore(retention.cutoff(today).atStartOfDay(ZoneId.systemDefault()).toInstant());
        log.info("notify delivery janitor swept; removed={}", removed);
    }
}
