package io.litealert.notify.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.litealert.common.audit.AuditLogger;
import io.litealert.notify.channel.NotifyChannelRegistry;
import io.litealert.notify.domain.NotifyTarget;
import io.litealert.notify.domain.NotifyTargetStore;
import io.litealert.notify.template.TemplateRenderer;
import io.litealert.topic.domain.Topic;
import io.litealert.topic.domain.TopicStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotifyDeliveryWorker {

    private static final long[] BACKOFF_SECONDS = {60, 300, 1_800, 7_200, 21_600};

    private final NotifyDeliveryStore deliveryStore;
    private final TopicStore topicStore;
    private final NotifyTargetStore targetStore;
    private final NotifyChannelRegistry channels;
    private final AuditLogger audit;
    private final ObjectMapper objectMapper;
    private final TemplateRenderer renderer;

    @Value("${lite-alert.worker-id:${random.uuid}}")
    private String workerId;

    @Scheduled(fixedDelayString = "${lite-alert.delivery.scan-delay-ms:5000}", initialDelayString = "${lite-alert.delivery.initial-delay-ms:5000}")
    public void tick() {
        if (!deliveryStore.tableReady()) {
            log.warn("notify delivery table is not ready; skip worker tick");
            return;
        }
        Instant now = Instant.now();
        deliveryStore.recoverStuck(now.minusSeconds(600), now);
        for (NotifyDelivery d : deliveryStore.findDue(now, 50)) {
            if (deliveryStore.claim(d.getId(), workerId, now, now)) {
                deliveryStore.findById(d.getId()).ifPresent(this::send);
            }
        }
    }

    void send(NotifyDelivery d) {
        Instant now = Instant.now();
        var topic = topicStore.findById(d.getTopicId());
        var target = targetStore.findById(d.getTargetId());
        if (topic.isEmpty() || target.isEmpty() || !target.get().isEnabled()) {
            deliveryStore.markCancelled(d.getId(), "topic or target unavailable", now);
            audit.log("notify.cancelled", Map.of("deliveryId", d.getId(), "topicId", d.getTopicId(), "targetId", d.getTargetId()));
            return;
        }
        try {
            sendOne(d, topic.get(), target.get());
            deliveryStore.markSent(d.getId(), Instant.now());
            audit.log("notify.sent", Map.of(
                    "deliveryId", d.getId(),
                    "topicId", d.getTopicId(),
                    "targetId", d.getTargetId(),
                    "channel", d.getChannel().name(),
                    "traceId", safe(d.getTraceId()),
                    "attempt", d.getAttempt()));
        } catch (Exception e) {
            log.warn("delivery send failed deliveryId={} targetId={} attempt={}", d.getId(), d.getTargetId(), d.getAttempt(), e);
            int nextAttempt = d.getAttempt() + 1;
            String error = sanitize(e.getMessage());
            audit.log("notify.failed", Map.of(
                    "deliveryId", d.getId(),
                    "topicId", d.getTopicId(),
                    "targetId", d.getTargetId(),
                    "channel", d.getChannel().name(),
                    "traceId", safe(d.getTraceId()),
                    "attempt", nextAttempt,
                    "error", error));
            if (nextAttempt >= d.getMaxAttempts()) {
                deliveryStore.markGiveUp(d.getId(), nextAttempt, error, Instant.now());
                audit.log("notify.give_up", Map.of("deliveryId", d.getId(), "topicId", d.getTopicId(), "targetId", d.getTargetId(), "attempt", nextAttempt));
            } else {
                long delay = BACKOFF_SECONDS[Math.min(nextAttempt - 1, BACKOFF_SECONDS.length - 1)];
                deliveryStore.markRetry(d.getId(), nextAttempt, Instant.now().plusSeconds(delay), error, Instant.now());
            }
        }
    }

    private void sendOne(NotifyDelivery d, Topic topic, NotifyTarget target) throws Exception {
        if (target.getType() == null || !channels.supports(target.getType())) {
            throw new IllegalStateException("unsupported channel: " + target.getType());
        }
        Topic.ChannelTemplate template = topic.templateFor(target.getType());
        var payload = deliveryStore.payload(d);
        Map<String, Object> system = systemVars(d, topic);
        String subject = renderer.render(template == null ? null : template.getSubject(), payload, system);
        if (subject == null || subject.isBlank()) subject = "[" + topic.getNamespaceName() + "] " + topic.getName();
        String body = renderer.render(template == null ? null : template.getBody(), payload, system);
        channels.resolve(target.getType()).send(target, template, subject, body, payload, toStringMap(system));
    }

    private Map<String, Object> systemVars(NotifyDelivery d, Topic topic) {
        Map<String, Object> m = new HashMap<>();
        m.put("namespace", topic.getNamespaceName());
        m.put("topic", topic.getName());
        m.put("traceId", d.getTraceId());
        m.put("deliveryId", d.getId());
        m.put("receivedAt", d.getCreatedAt() == null ? "" : d.getCreatedAt().toString());
        m.put("rawJson", d.getPayloadJson());
        return m;
    }

    private Map<String, String> toStringMap(Map<String, Object> src) {
        Map<String, String> out = new HashMap<>();
        src.forEach((k, v) -> out.put(k, v == null ? "" : String.valueOf(v)));
        return out;
    }

    private String sanitize(String message) {
        if (message == null) return "";
        String s = message.replaceAll("(?i)(password|token|secret|authorization|apikey|api_key)=\\S+", "$1=***");
        return s.length() > 2000 ? s.substring(0, 2000) : s;
    }

    private String safe(String s) { return s == null ? "" : s; }
}
