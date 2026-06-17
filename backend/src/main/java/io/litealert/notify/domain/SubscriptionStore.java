package io.litealert.notify.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import io.litealert.common.storage.FileStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class SubscriptionStore {

    private final FileStore fileStore;

    private final Map<String, Subscription> byTopic = new ConcurrentHashMap<>();

    public Subscription getOrEmpty(String topicId) {
        return byTopic.computeIfAbsent(topicId, t -> {
            Subscription stored = fileStore.readJson(
                    "subscriptions/" + t + ".json",
                    new TypeReference<Subscription>() {}, null);
            return stored != null ? stored
                    : Subscription.builder().topicId(t).contactIds(new java.util.ArrayList<>()).build();
        });
    }

    public synchronized Subscription save(String topicId, List<String> contactIds) {
        Subscription s = Subscription.builder()
                .topicId(topicId)
                .contactIds(contactIds == null ? new java.util.ArrayList<>() : contactIds)
                .updatedAt(Instant.now())
                .build();
        byTopic.put(topicId, s);
        fileStore.writeJson("subscriptions/" + topicId + ".json", s);
        return s;
    }

    public synchronized void delete(String topicId) {
        byTopic.remove(topicId);
        fileStore.delete("subscriptions/" + topicId + ".json");
    }

    public Optional<Subscription> find(String topicId) {
        return Optional.of(getOrEmpty(topicId));
    }
}
