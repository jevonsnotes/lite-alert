package io.litealert.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.litealert.common.audit.AuditLogger;
import io.litealert.notify.channel.NotifyChannel;
import io.litealert.notify.channel.NotifyChannelRegistry;
import io.litealert.notify.domain.NotifyTarget;
import io.litealert.notify.domain.NotifyTargetStore;
import io.litealert.notify.domain.SubscriptionStore;
import io.litealert.notify.template.TemplateRenderer;
import io.litealert.topic.domain.Topic;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Pulls subscribed targets for a topic and dispatches one notification per
 * target through the appropriate {@link NotifyChannel}.
 *
 * <p>Per-target rendering: each target may have its own channel template
 * (subject/body Mustache, plus a {@code transform} for WEBHOOK) so the same
 * Topic can simultaneously feed an HTML email, a DingTalk markdown card,
 * and a downstream webhook with a custom JSON shape.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotifyDispatcher {

    private final SubscriptionStore subscriptionStore;
    private final NotifyTargetStore targetStore;
    private final NotifyChannelRegistry channels;
    private final RetryQueue retryQueue;
    private final AuditLogger audit;
    private final ObjectMapper objectMapper;
    private final TemplateRenderer renderer;

    @Async("notifyExecutor")
    public void dispatchAsync(NotifyContext ctx) {
        var sub = subscriptionStore.getOrEmpty(ctx.topic().getId());
        if (sub.getContactIds() == null || sub.getContactIds().isEmpty()) {
            audit.log("notify.no_subscribers", Map.of(
                    "topicId", ctx.topic().getId(),
                    "traceId", ctx.traceId()));
            return;
        }
        for (String tid : sub.getContactIds()) {
            Optional<NotifyTarget> t = targetStore.findById(tid);
            if (t.isEmpty() || !t.get().isEnabled()) continue;
            sendOne(ctx, t.get(), 0);
        }
    }

    private void sendOne(NotifyContext ctx, NotifyTarget target, int attempt) {
        if (target.getType() == null || !channels.supports(target.getType())) {
            audit.log("notify.skip_unsupported", Map.of(
                    "topicId", ctx.topic().getId(),
                    "targetId", target.getId(),
                    "channel", String.valueOf(target.getType())));
            return;
        }

        Topic.ChannelTemplate template = ctx.topic().templateFor(target.getType());
        Map<String, Object> system = systemVars(ctx);
        Map<String, String> systemStr = toStringMap(system);
        String subject = renderer.render(template == null ? null : template.getSubject(),
                ctx.payload(), system);
        if (subject == null || subject.isBlank()) {
            subject = "[" + ctx.topic().getNamespaceName() + "] " + ctx.topic().getName();
        }
        String body = renderer.render(template == null ? null : template.getBody(),
                ctx.payload(), system);

        try {
            channels.resolve(target.getType()).send(
                    target, template, subject, body, ctx.payload(), systemStr);
            audit.log("notify.sent", Map.of(
                    "topicId", ctx.topic().getId(),
                    "targetId", target.getId(),
                    "channel", target.getType().name(),
                    "traceId", ctx.traceId(),
                    "attempt", attempt));
        } catch (Exception e) {
            log.warn("send failed targetId={} channel={} attempt={}",
                    target.getId(), target.getType(), attempt, e);
            audit.log("notify.failed", Map.of(
                    "topicId", ctx.topic().getId(),
                    "targetId", target.getId(),
                    "channel", target.getType().name(),
                    "traceId", ctx.traceId(),
                    "attempt", attempt,
                    "error", String.valueOf(e.getMessage())));
            retryQueue.enqueue(new RetryQueue.RetryTask(ctx, target.getId(), attempt + 1));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> toStringMap(Map<String, Object> src) {
        Map<String, String> out = new HashMap<>();
        for (var e : src.entrySet()) {
            out.put(e.getKey(), e.getValue() == null ? "" : String.valueOf(e.getValue()));
        }
        return out;
    }

    /** Public so RetryQueue can invoke us back. */
    public void retrySend(NotifyContext ctx, String targetId, int attempt) {
        Optional<NotifyTarget> t = targetStore.findById(targetId);
        if (t.isEmpty()) {
            audit.log("notify.retry_drop", Map.of("targetId", targetId));
            return;
        }
        sendOne(ctx, t.get(), attempt);
    }

    private Map<String, Object> systemVars(NotifyContext ctx) {
        Map<String, Object> m = new HashMap<>();
        m.put("namespace", ctx.topic().getNamespaceName());
        m.put("topic", ctx.topic().getName());
        m.put("traceId", ctx.traceId());
        m.put("receivedAt", ctx.receivedAt().toString());
        try {
            m.put("rawJson", objectMapper.writeValueAsString(ctx.payload()));
        } catch (Exception ignored) {
            m.put("rawJson", "");
        }
        return m;
    }

    public record NotifyContext(
            Topic topic,
            String traceId,
            Instant receivedAt,
            JsonNode payload,
            Map<String, Object> meta
    ) {
        public static NotifyContext of(Topic t, String traceId, JsonNode payload) {
            return new NotifyContext(t, traceId, Instant.now(), payload, new LinkedHashMap<>());
        }
    }
}
