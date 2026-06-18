package io.litealert.notify.channel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Tiny JSON HTTP client shared by chat-bot channels. Kept here to avoid
 * pulling in a heavier WebClient stack and to keep the call shape uniform
 * (POST + JSON body + read status code).
 */
@RequiredArgsConstructor
public final class WebhookHttpClient {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final ObjectMapper mapper;

    public void postJson(String url, Object payload) throws Exception {
        byte[] body;
        try {
            body = mapper.writeValueAsBytes(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("payload is not JSON-serializable", e);
        }
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        int status = res.statusCode();
        if (status < 200 || status >= 300) {
            throw new RuntimeException("webhook responded " + status + ": " + res.body());
        }
        // Some chat APIs always return 200 but signal failure in the body —
        // the per-channel impl is expected to inspect when needed.
    }

    public void postXml(String url, String payload) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/xml; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(payload == null ? "" : payload,
                        java.nio.charset.StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        int status = res.statusCode();
        if (status < 200 || status >= 300) {
            throw new RuntimeException("webhook responded " + status + ": " + res.body());
        }
    }
}
