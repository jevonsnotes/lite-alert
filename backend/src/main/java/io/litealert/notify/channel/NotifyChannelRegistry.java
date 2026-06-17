package io.litealert.notify.channel;

import io.litealert.notify.domain.NotifyTarget;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the right {@link NotifyChannel} for a given target type. Built
 * from every {@link NotifyChannel} bean Spring discovers, so adding a new
 * channel is a one-class change.
 */
@Component
public class NotifyChannelRegistry {

    private final Map<NotifyTarget.Type, NotifyChannel> byType =
            new EnumMap<>(NotifyTarget.Type.class);

    public NotifyChannelRegistry(List<NotifyChannel> channels) {
        for (NotifyChannel c : channels) byType.put(c.type(), c);
    }

    public NotifyChannel resolve(NotifyTarget.Type type) {
        NotifyChannel c = byType.get(type);
        if (c == null) {
            throw new IllegalStateException("no channel registered for " + type);
        }
        return c;
    }

    public boolean supports(NotifyTarget.Type type) {
        return byType.containsKey(type);
    }
}
