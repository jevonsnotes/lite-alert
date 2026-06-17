package io.litealert.notify.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.litealert.notify.domain.NotifyTarget;
import io.litealert.topic.domain.Topic;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

@Slf4j
@Component
public class DingTalkChannel implements NotifyChannel {

    private final WebhookHttpClient http;

    public DingTalkChannel(ObjectMapper mapper) {
        this.http = new WebhookHttpClient(mapper);
    }

    @Override
    public NotifyTarget.Type type() { return NotifyTarget.Type.DINGTALK; }

    @Override
    public void send(NotifyTarget target, Topic.ChannelTemplate template,
                     String subject, String body, JsonNode payload,
                     Map<String, String> systemVars) throws Exception {
        String url = signedUrl(target.getEndpoint(), target.getSecret());
        Map<String, Object> p = Map.of(
                "msgtype", "markdown",
                "markdown", Map.of(
                        "title", subject,
                        "text", "### " + subject + "\n\n" + body));
        http.postJson(url, p);
    }

    private String signedUrl(String base, String secret) {
        if (secret == null || secret.isBlank()) return base;
        try {
            long ts = System.currentTimeMillis();
            String raw = ts + "\n" + secret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal(raw.getBytes(StandardCharsets.UTF_8));
            String signEnc = URLEncoder.encode(
                    Base64.getEncoder().encodeToString(sig), StandardCharsets.UTF_8);
            String sep = base.contains("?") ? "&" : "?";
            return base + sep + "timestamp=" + ts + "&sign=" + signEnc;
        } catch (Exception e) {
            log.warn("dingtalk sign failed, falling back: {}", e.getMessage());
            return base;
        }
    }
}
