package io.litealert.notify.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.litealert.notify.domain.NotifyTarget;
import io.litealert.topic.domain.Topic;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class WecomChannel implements NotifyChannel {

    private final WebhookHttpClient http;

    public WecomChannel(ObjectMapper mapper) {
        this.http = new WebhookHttpClient(mapper);
    }

    @Override
    public NotifyTarget.Type type() { return NotifyTarget.Type.WECOM; }

    @Override
    public void send(NotifyTarget target, Topic.ChannelTemplate template,
                     String subject, String body, JsonNode payload,
                     Map<String, String> systemVars) throws Exception {
        Map<String, Object> p = Map.of(
                "msgtype", "markdown",
                "markdown", Map.of(
                        "content", "## " + subject + "\n" + body));
        http.postJson(target.getEndpoint(), p);
    }
}
