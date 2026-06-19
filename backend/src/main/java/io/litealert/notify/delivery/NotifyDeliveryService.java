package io.litealert.notify.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import io.litealert.common.audit.AuditLogger;
import io.litealert.notify.domain.NotifyTarget;
import io.litealert.notify.domain.NotifyTargetStore;
import io.litealert.notify.domain.SubscriptionStore;
import io.litealert.topic.domain.Topic;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class NotifyDeliveryService {

    private final SubscriptionStore subscriptionStore;
    private final NotifyTargetStore targetStore;
    private final NotifyDeliveryStore deliveryStore;
    private final AuditLogger audit;

    @Transactional
    public int createDeliveries(Topic topic, String traceId, JsonNode payload) {
        var sub = subscriptionStore.getOrEmpty(topic.getId());
        if (sub.getContactIds() == null || sub.getContactIds().isEmpty()) {
            audit.log("notify.no_subscribers", Map.of("topicId", topic.getId(), "traceId", traceId));
            return 0;
        }
        int count = 0;
        for (String targetId : sub.getContactIds()) {
            var target = targetStore.findById(targetId);
            if (target.isEmpty() || !target.get().isEnabled()) {
                audit.log("notify.cancelled", Map.of("topicId", topic.getId(), "targetId", targetId, "traceId", traceId,
                        "reason", target.isEmpty() ? "target not found" : "target disabled"));
                continue;
            }
            NotifyTarget t = target.get();
            NotifyDelivery d = NotifyDelivery.pending(traceId, topic.getId(), t.getId(), t.getType(), payload);
            deliveryStore.save(d);
            count++;
        }
        return count;
    }

    public Map<String, Object> acceptedResponse(String traceId, int deliveryCount) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accepted", true);
        out.put("traceId", traceId == null ? "" : traceId);
        out.put("deliveryCount", deliveryCount);
        if (deliveryCount == 0) out.put("message", "no subscribers");
        return out;
    }
}
