package io.litealert.webhook;

import io.litealert.admin.settings.SystemSettings;
import io.litealert.admin.settings.SystemSettingsService;
import io.litealert.topic.domain.Topic;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class RateLimiter {

    private final SystemSettingsService settingsService;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(SystemSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    public boolean allowIp(String ip) {
        int limit = settingsService.current().getRateLimit().getPerIpPerMinute();
        return increment("ip:" + ip, limit);
    }

    public boolean allowApiKey(String apiKeyId) {
        int limit = settingsService.current().getRateLimit().getPerApiKeyPerMinute();
        return increment("ak:" + apiKeyId, limit);
    }

    public boolean allowApiKeyWithOverride(String apiKeyId, Integer overrideLimit) {
        int limit = overrideLimit != null ? overrideLimit : settingsService.current().getRateLimit().getPerApiKeyPerMinute();
        return increment("ak:" + apiKeyId, limit);
    }

    public boolean allowTopic(Topic topic) {
        int limit = effectivePerTopicLimit(topic);
        return increment("t:" + topic.getId(), limit);
    }

    private int effectivePerTopicLimit(Topic t) {
        Topic.RateLimit rl = t.getAuth().getRateLimit();
        if (rl != null && rl.getPerMinute() != null) return rl.getPerMinute();
        return settingsService.current().getRateLimit().getPerTopicPerMinute();
    }

    private boolean increment(String key, int limit) {
        long minute = System.currentTimeMillis() / 60_000L;
        Bucket b = buckets.compute(key, (k, existing) -> {
            if (existing == null || existing.minute != minute) return new Bucket(minute);
            return existing;
        });
        return b.count.incrementAndGet() <= limit;
    }

    static class Bucket {
        final long minute;
        final AtomicInteger count = new AtomicInteger();
        Bucket(long minute) { this.minute = minute; }
    }
}
