package io.litealert.notify.domain;

import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class SubscriptionStore {

    private final SubscriptionMapper mapper;

    public Subscription getOrEmpty(String topicId) {
        return find(topicId).orElseGet(() -> Subscription.builder()
                .topicId(topicId)
                .contactIds(new java.util.ArrayList<>())
                .build());
    }

    public synchronized Subscription save(String topicId, List<String> contactIds) {
        Subscription s = Subscription.builder()
                .topicId(topicId)
                .contactIds(contactIds == null ? new java.util.ArrayList<>() : contactIds)
                .updatedAt(Instant.now())
                .build();
        if (find(topicId).isPresent()) {
            mapper.update(s);
        } else {
            mapper.insert(s);
        }
        return s;
    }

    public synchronized void delete(String topicId) {
        mapper.deleteById(topicId);
    }

    public Optional<Subscription> find(String topicId) {
        QueryWrapper qw = QueryWrapper.create()
                .where("topic_id = ?", topicId);
        return Optional.ofNullable(mapper.selectOneByQuery(qw));
    }
}

