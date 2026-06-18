package io.litealert.notify.channel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@RequiredArgsConstructor
public final class WebhookHttpClient {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final ObjectMapper mapper;

    public Response postJson(String url, Object payload) throws Exception {
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
        checkStatus(res);
        return response(res);
    }

    public Response postXml(String url, String payload) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/xml; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(payload == null ? "" : payload,
                        java.nio.charset.StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        checkStatus(res);
        return response(res);
    }

    private void checkStatus(HttpResponse<String> res) {
        int status = res.statusCode();
        if (status < 200 || status >= 300) {
            throw new RuntimeException("webhook responded " + status + ": " + res.body());
        }
    }

    private Response response(HttpResponse<String> res) {
        return new Response(res.statusCode(),
                res.headers().firstValue("Content-Type").orElse(""),
                res.body());
    }

    public record Response(int status, String contentType, String body) {}
}
