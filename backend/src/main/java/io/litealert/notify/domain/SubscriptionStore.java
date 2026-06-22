package io.litealert.notify.domain;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Adapter that keeps the existing subscription API contract intact while
 * delegating persistence to the new relational {@link TopicContactStore}.
 *
 * <p>Consumers ({@code NotifyDispatcher}, {@code TopicService},
 * {@code SubscriptionController}) continue to call {@code getOrEmpty},
 * {@code save}, and {@code delete} without modification.
 */
@Component
@RequiredArgsConstructor
public class SubscriptionStore {

    private final TopicContactStore contactStore;

    public Subscription getOrEmpty(String topicId) {
        return Subscription.builder()
                .topicId(topicId)
                .contactIds(contactStore.findContactIdsByTopicId(topicId))
                .build();
    }

    public Subscription save(String topicId, List<String> contactIds) {
        contactStore.saveForTopic(topicId, contactIds);
        return Subscription.builder()
                .topicId(topicId)
                .contactIds(contactIds == null ? List.of() : contactIds)
                .build();
    }

    public void delete(String topicId) {
        contactStore.deleteByTopicId(topicId);
    }
}
