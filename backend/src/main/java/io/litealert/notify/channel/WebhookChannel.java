package io.litealert.notify.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.litealert.notify.domain.NotifyTarget;
import io.litealert.topic.domain.Topic;
import io.litealert.transform.TransformService;
import io.litealert.transform.WebhookTemplateEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Generic outbound HTTP webhook. Body is composed by running the topic's
 * WEBHOOK channel template through the {@link WebhookTemplateEngine}:
 *
 * <ol>
 *   <li>Parse the outputTemplate as JSON (may contain {{var}} placeholders)</li>
 *   <li>Resolve system + dynamic variables in every string leaf</li>
 *   <li>Apply mapping rows to overwrite fields with payload values</li>
 * </ol>
 *
 * <p>If the WEBHOOK template has no outputTemplate / transform disabled,
 * the inbound payload is forwarded as-is.
 */
@Slf4j
@Component
public class WebhookChannel implements NotifyChannel {

    private final WebhookHttpClient http;
    private final TransformService transformService;
    private final WebhookTemplateEngine templateEngine;
    private final ObjectMapper mapper;

    public WebhookChannel(ObjectMapper mapper, TransformService transformService,
                          WebhookTemplateEngine templateEngine) {
        this.http = new WebhookHttpClient(mapper);
        this.mapper = mapper;
        this.transformService = transformService;
        this.templateEngine = templateEngine;
    }

    @Override
    public NotifyTarget.Type type() { return NotifyTarget.Type.WEBHOOK; }

    @Override
    public void send(NotifyTarget target, Topic.ChannelTemplate template,
                     String renderedSubject, String renderedBody,
                     JsonNode payload, Map<String, String> systemVars) throws Exception {

        Topic.Transform transform = template == null ? null : template.getTransform();

        // If transform is disabled or missing, forward payload as-is.
        if (transform == null || !transform.isEnabled()) {
            Object json = mapper.convertValue(payload, Object.class);
            http.postJson(target.getEndpoint(), json);
            return;
        }

        // New path: output-template-based rendering.
        JsonNode tpl = template.getOutputTemplate();
        if (tpl != null) {
            JsonNode out = templateEngine.render(tpl, payload, transform.getMappings(), systemVars);
            Object json = mapper.convertValue(out, Object.class);
            http.postJson(target.getEndpoint(), json);
            return;
        }

        // Legacy fallback: run through the old TransformService (field-to-object mapping).
        TransformService.TransformResult tr = transformService.apply(transform, payload);
        Object json = mapper.convertValue(tr.output(), Object.class);
        http.postJson(target.getEndpoint(), json);
    }
}
