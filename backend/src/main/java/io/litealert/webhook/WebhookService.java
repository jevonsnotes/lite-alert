package io.litealert.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import io.litealert.apikey.domain.ApiKey;
import io.litealert.apikey.domain.ApiKeyStore;
import io.litealert.common.audit.AuditLogger;
import io.litealert.common.config.LiteAlertProperties;
import io.litealert.common.error.BusinessException;
import io.litealert.common.error.ErrorCode;
import io.litealert.common.web.TraceIdHolder;
import io.litealert.admin.settings.SystemSettingsService;
import io.litealert.notify.delivery.NotifyDeliveryService;
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
 *   → body size → schema validation → notify dispatch.
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
    private final NotifyDeliveryService deliveryService;
    private final AuditLogger audit;
    private final LiteAlertProperties props;
    private final SystemSettingsService settingsService;

    public Map<String, Object> handle(String namespace, String topicName,
                                      String authorization, String queryKey, JsonNode body, String remoteIp) {
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
            String presentedKey = topic.getAuth().getKeyLocation() == Topic.KeyLocation.QUERY ? queryKey : authorization;
            usedKey = authenticator.authenticate(presentedKey, topic, remoteIp);
            apiKeyStore.save(usedKey);
            ctxAttrs.put("apiKeyId", usedKey.getId());
            ctxAttrs.put("authMode", Topic.AuthMode.API_KEY.name());
            ctxAttrs.put("keyLocation", topic.getAuth().getKeyLocation().name());

            // 3) rate limit — three layers: IP → ApiKey → Topic
            if (!rateLimiter.allowIp(remoteIp)) {
                throw new BusinessException(ErrorCode.RATE_LIMITED, "ip rate limit exceeded");
            }
            if (usedKey != null && !rateLimiter.allowApiKeyWithOverride(usedKey.getId(), usedKey.getRateLimitPerMinute())) {
                throw new BusinessException(ErrorCode.RATE_LIMITED, "api key rate limit exceeded");
            }
            if (!rateLimiter.allowTopic(topic)) {
                throw new BusinessException(ErrorCode.RATE_LIMITED, "topic rate limit exceeded");
            }

            // 4) body size
            int max = props.getWebhook().getMaxBodySize();
            if (body != null && body.toString().getBytes().length > max) {
                throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE);
            }

            // 5) schema validation
            schemaService.validate(topic.getInboundFormat(), body);

            String traceId = TraceIdHolder.current();

            // 6) sync vs async dispatch
            if (topic.isSync()) {
                return deliverSync(topic, traceId, body, ctxAttrs);
            }

            // async: persist deliveries, return immediately
            int deliveryCount = deliveryService.createDeliveries(topic, traceId, body);
            audit.log("webhook.accepted", ctxAttrs);
            return deliveryService.response(0, "accepted", traceId, deliveryCount, 0, 0, false);

        } catch (BusinessException e) {
            ctxAttrs.put("code", e.getCode().name());
            ctxAttrs.put("status", e.getCode().getStatus());
            ctxAttrs.put("message", e.getMessage());
            audit.log("webhook.rejected", ctxAttrs);
            throw e;
        } catch (RuntimeException e) {
            log.error("webhook handling crashed", e);
            ctxAttrs.put("code", "INTERNAL_ERROR");
            ctxAttrs.put("status", 500);
            ctxAttrs.put("message", String.valueOf(e.getMessage()));
            audit.log("webhook.rejected", ctxAttrs);
            throw e;
        }
    }

    private Map<String, Object> deliverSync(Topic topic, String traceId, JsonNode body, Map<String, Object> ctxAttrs) {
        // Resolve effective timeout
        Integer timeout = topic.getSyncTimeout();
        if (timeout == null) {
            timeout = settingsService.current().getSyncTimeoutSeconds();
        }
        int effectiveTimeout = timeout == null ? 30 : timeout;

        NotifyDeliveryService.SyncResult sr = deliveryService.syncDeliver(topic, traceId, body, effectiveTimeout);
        ctxAttrs.put("topicId", topic.getId());
        audit.log("webhook.accepted", ctxAttrs);

        if (sr.timedOut()) {
            return deliveryService.response(2, "sync delivery timed out", traceId, sr.total(), sr.sent(), sr.failed(), true);
        }
        if (sr.sent() == 0 && sr.failed() > 0) {
            return deliveryService.response(3, "all deliveries failed", traceId, sr.total(), 0, sr.failed(), false);
        }
        if (sr.failed() > 0) {
            return deliveryService.response(1, sr.failed() + "/" + sr.total() + " failed", traceId, sr.total(), sr.sent(), sr.failed(), false);
        }
        return deliveryService.response(0, "all deliveries sent successfully", traceId, sr.total(), sr.sent(), 0, false);
    }
}
