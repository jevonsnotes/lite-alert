package io.litealert.notify.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.litealert.notify.domain.NotifyTarget;
import io.litealert.topic.domain.Topic;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class FeishuChannel implements NotifyChannel {

    private final WebhookHttpClient http;

    public FeishuChannel(ObjectMapper mapper) {
        this.http = new WebhookHttpClient(mapper);
    }

    @Override
    public NotifyTarget.Type type() { return NotifyTarget.Type.FEISHU; }

    @Override
    public void send(NotifyTarget target, Topic.ChannelTemplate template,
                     String subject, String body, JsonNode payload,
                     Map<String, String> systemVars) throws Exception {
        Map<String, Object> p = Map.of(
                "msg_type", "interactive",
                "card", Map.of(
                        "header", Map.of(
                                "title", Map.of("tag", "plain_text", "content", subject),
                                "template", "blue"
                        ),
                        "elements", java.util.List.of(
                                Map.of("tag", "div",
                                        "text", Map.of("tag", "lark_md", "content", body))
                        )));
        http.postJson(target.getEndpoint(), p);
    }
}
