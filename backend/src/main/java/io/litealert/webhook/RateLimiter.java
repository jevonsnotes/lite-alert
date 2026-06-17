package io.litealert.webhook;

import io.litealert.common.config.LiteAlertProperties;
import io.litealert.topic.domain.Topic;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sliding-window counter, one minute granularity. Two scopes:
 * <ul>
 *   <li>per topic — across all callers</li>
 *   <li>per (topic, ip) — for NONE-mode topics</li>
 * </ul>
 *
 * <p>Buckets are keyed by epoch-minute so old buckets self-expire when no
 * one looks at them; we sweep the map opportunistically.
 */
@Component
public class RateLimiter {

    private final LiteAlertProperties props;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(LiteAlertProperties props) {
        this.props = props;
    }

    public boolean allowTopic(Topic topic) {
        int limit = effectivePerTopicLimit(topic);
        return increment("t:" + topic.getId(), limit);
    }

    public boolean allowIp(Topic topic, String ip) {
        if (topic.getAuth().getMode() != Topic.AuthMode.NONE) return true;
        int limit = effectivePerIpLimit(topic);
        return increment("ip:" + topic.getId() + ":" + ip, limit);
    }

    private int effectivePerTopicLimit(Topic t) {
        Integer override = t.getAuth().getRateLimit() == null
                ? null : t.getAuth().getRateLimit().getPerMinute();
        if (override != null) return override;
        return t.getAuth().getMode() == Topic.AuthMode.NONE
                ? props.getWebhook().getRateLimit().getPublicPerMinute()
                : props.getWebhook().getRateLimit().getPerTopicPerMinute();
    }

    private int effectivePerIpLimit(Topic t) {
        Integer override = t.getAuth().getRateLimit() == null
                ? null : t.getAuth().getRateLimit().getPerIp();
        if (override != null) return override;
        return props.getWebhook().getRateLimit().getPublicPerIpPerMinute();
    }

    private boolean increment(String key, int limit) {
        long minute = System.currentTimeMillis() / 60_000L;
        Bucket b = buckets.compute(key, (k, existing) -> {
            if (existing == null || existing.minute != minute) {
                return new Bucket(minute);
            }
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
