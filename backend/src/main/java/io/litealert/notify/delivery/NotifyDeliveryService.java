package io.litealert.notify.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import io.litealert.common.audit.AuditLogger;
import io.litealert.common.util.IdGenerator;
import io.litealert.notify.channel.NotifyChannel;
import io.litealert.notify.channel.NotifyChannelRegistry;
import io.litealert.notify.domain.NotifyTarget;
import io.litealert.notify.domain.NotifyTargetStore;
import io.litealert.notify.domain.SubscriptionStore;
import io.litealert.notify.template.TemplateRenderer;
import io.litealert.topic.domain.Topic;
import io.litealert.topic.domain.TopicStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotifyDeliveryService {

    private final SubscriptionStore subscriptionStore;
    private final NotifyTargetStore targetStore;
    private final NotifyDeliveryStore deliveryStore;
    private final TopicStore topicStore;
    private final NotifyChannelRegistry channels;
    private final TemplateRenderer renderer;
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

    /**
     * Synchronous delivery — blocks until all subscribers receive or timeout.
     * Writes the delivery record AFTER send completes (with final status SENT/FAILED),
     * so the worker never picks up sync deliveries.
     * Returns a structured result with per-channel status.
     */
    public SyncResult syncDeliver(Topic topic, String traceId, JsonNode payload, int timeoutSeconds) {
        var sub = subscriptionStore.getOrEmpty(topic.getId());
        List<String> targets = sub.getContactIds();
        if (targets == null || targets.isEmpty()) {
            return new SyncResult(0, 0, 0, false, "no subscribers");
        }

        int total = targets.size();
        int sent = 0, failed = 0;
        String firstError = null;
        boolean timedOut = false;
        boolean noTimeout = (timeoutSeconds == 0);
        long deadline = noTimeout ? Long.MAX_VALUE : System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        Instant now = Instant.now();

        for (String targetId : new LinkedHashSet<>(targets)) {
            if (!noTimeout && System.nanoTime() > deadline) {
                timedOut = true;
                break;
            }

            var targetOpt = targetStore.findById(targetId);
            if (targetOpt.isEmpty() || !targetOpt.get().isEnabled()) {
                failed++;
                audit.log("notify.cancelled", Map.of("topicId", topic.getId(), "targetId", targetId, "traceId", traceId));
                continue;
            }
            NotifyTarget target = targetOpt.get();
            String deliveryId = IdGenerator.entityId("del");
            try {
                sendOneSync(traceId, topic, target, payload);
                sent++;

                // Insert delivery record with SENT status after success
                NotifyDelivery d = NotifyDelivery.builder()
                        .id(deliveryId)
                        .traceId(traceId)
                        .topicId(topic.getId())
                        .targetId(targetId)
                        .channel(target.getType())
                        .payloadJson(payload == null ? "null" : payload.toString())
                        .status(NotifyDelivery.Status.SENT)
                        .attempt(0)
                        .maxAttempts(5)
                        .nextRetryAt(now)
                        .createdAt(now)
                        .updatedAt(now)
                        .finishedAt(now)
                        .build();
                deliveryStore.save(d);

                audit.log("notify.sent", Map.of(
                        "deliveryId", deliveryId, "topicId", topic.getId(),
                        "targetId", targetId, "channel", target.getType().name(), "traceId", traceId));
            } catch (Exception e) {
                failed++;
                String error = sanitize(e.getMessage());
                if (firstError == null) firstError = error;
                log.warn("sync delivery failed targetId={}", targetId, e);

                // Insert delivery record with FAILED/GIVE_UP status after failure
                NotifyDelivery d = NotifyDelivery.builder()
                        .id(deliveryId)
                        .traceId(traceId)
                        .topicId(topic.getId())
                        .targetId(targetId)
                        .channel(target.getType())
                        .payloadJson(payload == null ? "null" : payload.toString())
                        .status(NotifyDelivery.Status.GIVE_UP)
                        .attempt(0)
                        .maxAttempts(5)
                        .nextRetryAt(now)
                        .lastError(error)
                        .createdAt(now)
                        .updatedAt(now)
                        .finishedAt(now)
                        .build();
                deliveryStore.save(d);

                audit.log("notify.failed", Map.of(
                        "deliveryId", deliveryId, "topicId", topic.getId(),
                        "targetId", targetId, "channel", target.getType().name(),
                        "traceId", traceId, "error", error));
            }
        }

        return new SyncResult(total, sent, failed, timedOut, firstError);
    }

    private void sendOneSync(String traceId, Topic topic, NotifyTarget target, JsonNode payload) throws Exception {
        Topic.ChannelTemplate template = topic.templateFor(target.getType());
        if (template == null || !channels.supports(target.getType())) {
            throw new IllegalStateException("unsupported channel or missing template: " + target.getType());
        }
        Map<String, Object> system = new java.util.HashMap<>();
        system.put("namespace", topic.getNamespaceName());
        system.put("topic", topic.getName());
        system.put("traceId", traceId);
        system.put("rawJson", payload.toString());

        String subject = renderer.render(template.getSubject(), payload, system);
        if (subject == null || subject.isBlank()) subject = "[" + topic.getNamespaceName() + "] " + topic.getName();
        String body = renderer.render(template.getBody(), payload, system);

        NotifyChannel channel = channels.resolve(target.getType());
        channel.send(target, template, subject, body, payload, toStringMap(system));
    }

    private Map<String, String> toStringMap(Map<String, Object> src) {
        Map<String, String> out = new java.util.HashMap<>();
        src.forEach((k, v) -> out.put(k, v == null ? "" : String.valueOf(v)));
        return out;
    }

    private String sanitize(String message) {
        if (message == null) return "";
        String s = message.replaceAll("(?i)(password|token|secret|authorization|apikey|api_key)=\\S+", "$1=***");
        return s.length() > 500 ? s.substring(0, 500) : s;
    }

    /**
     * Unified response format for both sync and async modes.
     */
    public static Map<String, Object> response(int code, String message, String traceId,
                                                int deliveryCount, int sentCount, int failedCount, boolean timeout) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", code);
        out.put("message", message);
        out.put("traceId", traceId == null ? "" : traceId);
        out.put("deliveryCount", deliveryCount);
        out.put("sentCount", sentCount);
        out.put("failedCount", failedCount);
        out.put("timeout", timeout);
        out.put("accepted", code == 0);
        return out;
    }

    public record SyncResult(int total, int sent, int failed, boolean timedOut, String firstError) {}
}
