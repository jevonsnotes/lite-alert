package io.litealert.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import io.litealert.apikey.domain.ApiKey;
import io.litealert.apikey.domain.ApiKeyStore;
import io.litealert.common.audit.AuditLogger;
import io.litealert.common.config.LiteAlertProperties;
import io.litealert.common.error.BusinessException;
import io.litealert.common.error.ErrorCode;
import io.litealert.common.web.TraceIdHolder;
import io.litealert.notify.NotifyDispatcher;
import io.litealert.namespace.domain.Namespace;
import io.litealert.namespace.domain.NamespaceStore;
import io.litealert.topic.domain.Topic;
import io.litealert.topic.domain.TopicStore;
import io.litealert.transform.JsonSchemaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Webhook ingest pipeline:
 *   topic lookup → IP allowlist → ApiKey auth → rate limit
 *   → body size → schema validation → transform → notify dispatch.
 *
 * <p>Every exit path — accepted or rejected — produces exactly one audit
 * event so the call-records UI can render the full picture.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookService {

    private final TopicStore topicStore;
    private final NamespaceStore namespaceStore;
    private final ApiKeyStore apiKeyStore;
    private final ApiKeyAuthenticator authenticator;
    private final IpAllowlist ipAllowlist;
    private final RateLimiter rateLimiter;
    private final JsonSchemaService schemaService;
    private final NotifyDispatcher dispatcher;
    private final AuditLogger audit;
    private final LiteAlertProperties props;

    public Map<String, Object> handle(String namespace, String topicName,
                                      String authorization, JsonNode body, String remoteIp) {
        // Best-effort context: every audit line carries these so the UI can
        // filter "show me all attempts on topic X" regardless of outcome.
        Map<String, Object> ctxAttrs = new LinkedHashMap<>();
        ctxAttrs.put("namespace", namespace);
        ctxAttrs.put("topicName", topicName);
        ctxAttrs.put("remoteIp", remoteIp);

        Topic topic = null;
        ApiKey usedKey = null;

        try {
            topic = topicStore.findForWebhook(namespace, topicName).orElseThrow(() ->
                    new BusinessException(ErrorCode.NOT_FOUND, "topic not found"));
            ctxAttrs.put("topicId", topic.getId());

            Namespace ns = namespaceStore.findById(topic.getNamespaceId()).orElse(null);
            if (ns != null && ns.getStatus() == Namespace.Status.DISABLED) {
                ctxAttrs.put("namespaceId", ns.getId());
                throw new BusinessException(ErrorCode.NAMESPACE_DISABLED);
            }

            if (topic.getStatus() == Topic.Status.DRAFT) {
                throw new BusinessException(ErrorCode.TOPIC_NOT_PUBLISHED);
            }
            if (topic.getStatus() == Topic.Status.DISABLED) {
                throw new BusinessException(ErrorCode.TOPIC_DISABLED);
            }

            // 1) IP allowlist
            if (!ipAllowlist.isAllowed(topic.getAuth().getIpWhitelist(), remoteIp)) {
                throw new BusinessException(ErrorCode.IP_NOT_ALLOWED);
            }

            // 2) auth
            if (topic.getAuth().getMode() == Topic.AuthMode.API_KEY) {
                usedKey = authenticator.authenticate(authorization, topic, remoteIp);
                apiKeyStore.save(usedKey);
                ctxAttrs.put("apiKeyId", usedKey.getId());
            }
            ctxAttrs.put("authMode", topic.getAuth().getMode().name());

            // 3) rate limit
            if (!rateLimiter.allowTopic(topic)) {
                throw new BusinessException(ErrorCode.RATE_LIMITED, "topic rate limit exceeded");
            }
            if (!rateLimiter.allowIp(topic, remoteIp)) {
                throw new BusinessException(ErrorCode.RATE_LIMITED, "ip rate limit exceeded");
            }

            // 4) body size
            int max = topic.getAuth().getMode() == Topic.AuthMode.NONE
                    ? props.getWebhook().getPublicMaxBodySize()
                    : props.getWebhook().getMaxBodySize();
            if (body != null && body.toString().getBytes().length > max) {
                throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE);
            }

            // 5) schema validation
            schemaService.validate(topic.getInboundFormat(), body);

            // 6) audit accept + async dispatch.
            // Note: payload transform is no longer applied globally here; it's
            // a WEBHOOK-channel concern, run per-target inside the dispatcher.
            audit.log("webhook.accepted", ctxAttrs);

            var nctx = NotifyDispatcher.NotifyContext.of(topic,
                    TraceIdHolder.current(), body);
            dispatcher.dispatchAsync(nctx);

            return Map.of(
                    "traceId", TraceIdHolder.current() == null ? "" : TraceIdHolder.current(),
                    "accepted", true);

        } catch (BusinessException e) {
            // expected rejection — log structured + rethrow for the standard error envelope
            ctxAttrs.put("code", e.getCode().name());
            ctxAttrs.put("status", e.getCode().getStatus());
            ctxAttrs.put("message", e.getMessage());
            audit.log("webhook.rejected", ctxAttrs);
            throw e;
        } catch (RuntimeException e) {
            // unexpected — still record so the user sees the failure on the UI
            log.error("webhook handling crashed", e);
            ctxAttrs.put("code", "INTERNAL_ERROR");
            ctxAttrs.put("status", 500);
            ctxAttrs.put("message", String.valueOf(e.getMessage()));
            audit.log("webhook.rejected", ctxAttrs);
            throw e;
        }
    }
}
