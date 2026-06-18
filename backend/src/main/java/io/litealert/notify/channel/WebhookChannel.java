package io.litealert.notify.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.litealert.notify.domain.NotifyTarget;
import io.litealert.topic.domain.Topic;
import io.litealert.transform.TransformService;
import io.litealert.transform.WebhookTemplateEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class WebhookChannel implements NotifyChannel {

    private final WebhookHttpClient http;
    private final TransformService transformService;
    private final WebhookTemplateEngine templateEngine;
    private final WebhookResponseAssertor responseAssertor;
    private final ObjectMapper mapper;

    public WebhookChannel(ObjectMapper mapper, TransformService transformService,
                          WebhookTemplateEngine templateEngine,
                          WebhookResponseAssertor responseAssertor) {
        this.http = new WebhookHttpClient(mapper);
        this.mapper = mapper;
        this.transformService = transformService;
        this.templateEngine = templateEngine;
        this.responseAssertor = responseAssertor;
    }

    @Override
    public NotifyTarget.Type type() { return NotifyTarget.Type.WEBHOOK; }

    @Override
    public void send(NotifyTarget target, Topic.ChannelTemplate template,
                     String renderedSubject, String renderedBody,
                     JsonNode payload, Map<String, String> systemVars) throws Exception {

        Topic.Transform transform = template == null ? null : template.getTransform();
        WebhookHttpClient.Response response;

        if (transform == null || !transform.isEnabled()) {
            Object json = mapper.convertValue(payload, Object.class);
            response = http.postJson(target.getEndpoint(), json);
            assertResponse(template, response);
            return;
        }

        if ("XML".equalsIgnoreCase(template.getOutputFormat())) {
            String xml = templateEngine.renderXml(template.getOutputXmlTemplate(), payload,
                    transform.getMappings(), systemVars);
            response = http.postXml(target.getEndpoint(), xml);
            assertResponse(template, response);
            return;
        }

        JsonNode tpl = template.getOutputTemplate();
        if (tpl != null) {
            JsonNode out = templateEngine.render(tpl, payload, transform.getMappings(), systemVars);
            Object json = mapper.convertValue(out, Object.class);
            response = http.postJson(target.getEndpoint(), json);
            assertResponse(template, response);
            return;
        }

        TransformService.TransformResult tr = transformService.apply(transform, payload);
        Object json = mapper.convertValue(tr.output(), Object.class);
        response = http.postJson(target.getEndpoint(), json);
        assertResponse(template, response);
    }

    private void assertResponse(Topic.ChannelTemplate template, WebhookHttpClient.Response response) {
        Topic.WebhookResponseCheck check = template == null ? null : template.getResponseCheck();
        WebhookResponseAssertor.Result result = responseAssertor.check(check,
                response.status(), response.contentType(), response.body());
        if (!result.success()) {
            throw new RuntimeException(result.message());
        }
    }
}
