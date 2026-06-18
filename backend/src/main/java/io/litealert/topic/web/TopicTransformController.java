package io.litealert.topic.web;

import com.fasterxml.jackson.databind.JsonNode;
import io.litealert.notify.domain.NotifyTarget;
import io.litealert.notify.template.TemplateRenderer;
import io.litealert.topic.TopicService;
import io.litealert.topic.domain.Topic;
import io.litealert.transform.JsonSchemaService;
import io.litealert.transform.TransformService;
import io.litealert.transform.WebhookTemplateEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dry-run endpoints used by the Topic-detail UI:
 * <ul>
 *   <li>{@code POST /template/dry-run?channel=WEBHOOK} — preview the
 *       rendered webhook outbound JSON (outputTemplate + variable resolution
 *       + mapping overwrite).</li>
 *   <li>{@code POST /template/dry-run?channel=EMAIL} — preview the rendered
 *       subject + body for any non-webhook channel.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/topics")
@RequiredArgsConstructor
public class TopicTransformController {

    private final TopicService topicService;
    private final JsonSchemaService schemaService;
    private final TransformService transformService;
    private final TemplateRenderer renderer;
    private final WebhookTemplateEngine webhookEngine;

    @PostMapping("/{id}/template/dry-run")
    public Map<String, Object> dryRun(
            @PathVariable String id,
            @RequestParam String channel,
            @RequestBody JsonNode payload) {
        Topic t = topicService.getOrThrow(id);
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            schemaService.validate(t.getInboundFormat(), payload);
            result.put("schemaOk", true);
        } catch (Exception e) {
            result.put("schemaOk", false);
            result.put("schemaError", e.getMessage());
            return result;
        }
        NotifyTarget.Type type = parseType(channel);
        Topic.ChannelTemplate ch = t.templateFor(type);

        if (type == NotifyTarget.Type.WEBHOOK) {
            // Webhook: full template rendering.
            Topic.Transform transform = ch == null ? null : ch.getTransform();
            Map<String, String> sysVars = buildSystemVars(t);
            if (ch != null && "XML".equalsIgnoreCase(ch.getOutputFormat())) {
                result.put("outputFormat", "XML");
                result.put("outputXml", webhookEngine.renderXml(ch.getOutputXmlTemplate(), payload, sysVars));
                return result;
            }
            JsonNode tpl = ch == null ? null : ch.getOutputTemplate();
            JsonNode out;
            if (tpl != null && transform != null && transform.isEnabled()) {
                out = webhookEngine.render(tpl, payload, transform.getMappings(), sysVars);
            } else if (tpl != null) {
                // Template only — just resolve variables, no mapping overwrite.
                out = webhookEngine.render(tpl, payload, null, sysVars);
            } else {
                // No template — just forward payload.
                out = payload;
            }
            result.put("output", out);
            // Also include mapping traces if available.
            if (transform != null && transform.getMappings() != null) {
                TransformService.TransformResult tr = transformService.apply(transform, payload);
                result.put("traces", tr.traces());
            }
            return result;
        }

        // Non-webhook: render subject + body via Mustache.
        Map<String, Object> system = new HashMap<>();
        system.put("namespace", t.getNamespaceName());
        system.put("topic", t.getName());
        system.put("traceId", "tr_dryrun");
        system.put("receivedAt", Instant.now().toString());
        result.put("subject", renderer.render(ch == null ? null : ch.getSubject(), payload, system));
        result.put("body", renderer.render(ch == null ? null : ch.getBody(), payload, system));
        return result;
    }

    private Map<String, String> buildSystemVars(Topic t) {
        Map<String, String> m = new HashMap<>();
        m.put("namespace", t.getNamespaceName());
        m.put("topic", t.getName());
        m.put("traceId", "tr_dryrun");
        m.put("receivedAt", Instant.now().toString());
        return m;
    }

    private NotifyTarget.Type parseType(String s) {
        try { return NotifyTarget.Type.valueOf(s.trim().toUpperCase()); }
        catch (Exception e) { return NotifyTarget.Type.EMAIL; }
    }
}
