package io.litealert.scheduler;

import io.litealert.scheduler.domain.ApiTaskConfig;
import io.litealert.scheduler.domain.TaskConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Executes an API-type scheduled task's HTTP request (design D3). Supports all methods and
 * four body types (none / form-data multipart / urlencoded / raw json|xml|text), injecting an
 * auto Content-Type unless the user already supplied one.
 *
 * <p>{@link #buildRequest} is split out and uses no network, so request construction — including
 * the auto Content-Type rules — is unit-tested without a live server.
 */
@Slf4j
@Component
public class ApiTaskHttpExecutor {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(TaskConfig.Timeouts.DEFAULT_CONNECT))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** Cache of per-connect-timeout clients (connect timeout is client-level in java.net.http). */
    private static final java.util.concurrent.ConcurrentHashMap<Integer, HttpClient> CLIENTS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static HttpClient clientFor(int connectSeconds) {
        if (connectSeconds <= 0) {
            return CLIENTS.computeIfAbsent(0, k -> HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER).build());
        }
        if (connectSeconds == TaskConfig.Timeouts.DEFAULT_CONNECT) return CLIENT;
        return CLIENTS.computeIfAbsent(connectSeconds, k -> HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(k))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    public Response execute(ApiTaskConfig config) throws Exception {
        TaskConfig.Timeouts t = config.getTimeouts();
        int connect = t == null ? TaskConfig.Timeouts.DEFAULT_CONNECT : t.effectiveConnect();
        int read = t == null ? TaskConfig.Timeouts.DEFAULT_READ : t.effectiveRead();
        int write = t == null ? TaskConfig.Timeouts.DEFAULT_WRITE : t.effectiveWrite();
        HttpClient client = clientFor(connect);
        HttpRequest request = buildRequest(config, read, write);
        HttpResponse<String> res = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Response(
                res.statusCode(),
                res.headers().firstValue("Content-Type").orElse(""),
                res.body());
    }

    /** Build the {@link HttpRequest} from config. Pure function — no network. */
    HttpRequest buildRequest(ApiTaskConfig config) {
        return buildRequest(config, TaskConfig.Timeouts.DEFAULT_READ, TaskConfig.Timeouts.DEFAULT_WRITE);
    }

    private HttpRequest buildRequest(ApiTaskConfig config, int readSeconds, int writeSeconds) {
        PreparedBody body = prepareBody(config.getBody());
        String method = normalizeMethod(config.getMethod());
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(config.getUrl()));
        int reqTimeout = Math.max(readSeconds, writeSeconds);
        if (reqTimeout > 0) b.timeout(Duration.ofSeconds(reqTimeout));

        // user headers first, then auto Content-Type only if user did not set it
        Map<String, String> headers = collectHeaders(config.getHeaders());
        if (!hasContentType(headers) && body.contentType != null) {
            headers.put("Content-Type", body.contentType);
        }
        headers.forEach(b::header);

        if (body.bodyPublisher != null) {
            b.method(method, body.bodyPublisher);
        } else {
            // no body → GET-style "method without body" (GET/DELETE/HEAD/OPTIONS) or empty-body methods
            switch (method) {
                case "GET", "HEAD" -> b.GET();
                case "DELETE" -> b.DELETE();
                case "OPTIONS" -> b.method("OPTIONS", HttpRequest.BodyPublishers.noBody());
                case "POST", "PUT", "PATCH" -> b.method(method, HttpRequest.BodyPublishers.noBody());
                default -> b.GET();
            }
        }
        return b.build();
    }

    /** Public so the auto Content-Type logic can be asserted in tests. */
    public String autoContentType(ApiTaskConfig config) {
        PreparedBody body = prepareBody(config.getBody());
        Map<String, String> headers = collectHeaders(config.getHeaders());
        if (hasContentType(headers)) {
            return userContentType(headers);
        }
        return body.contentType;
    }

    private PreparedBody prepareBody(ApiTaskConfig.Body body) {
        if (body == null || body.getType() == null || body.getType() == ApiTaskConfig.Body.Type.NONE) {
            return PreparedBody.none();
        }
        return switch (body.getType()) {
            case RAW -> prepareRaw(body);
            case URL_ENCODED -> prepareUrlEncoded(body);
            case FORM_DATA -> prepareFormData(body);
            case NONE -> PreparedBody.none();
        };
    }

    private PreparedBody prepareRaw(ApiTaskConfig.Body body) {
        String text = body.getRawText() == null ? "" : body.getRawText();
        ApiTaskConfig.Body.RawType raw = body.getRawType() == null ? ApiTaskConfig.Body.RawType.TEXT : body.getRawType();
        String ct = switch (raw) {
            case JSON -> "application/json; charset=utf-8";
            case XML -> "application/xml; charset=utf-8";
            case TEXT -> "text/plain; charset=utf-8";
        };
        return new PreparedBody(HttpRequest.BodyPublishers.ofString(text, StandardCharsets.UTF_8), ct);
    }

    private PreparedBody prepareUrlEncoded(ApiTaskConfig.Body body) {
        StringBuilder sb = new StringBuilder();
        appendFormFields(sb, body, "&", true);
        return new PreparedBody(
                HttpRequest.BodyPublishers.ofString(sb.toString(), StandardCharsets.UTF_8),
                "application/x-www-form-urlencoded");
    }

    private PreparedBody prepareFormData(ApiTaskConfig.Body body) {
        String boundary = "litealert-boundary-" + UUID.randomUUID().toString().replace("-", "");
        String crlf = "\r\n";
        StringBuilder sb = new StringBuilder();
        if (body.getFields() != null) {
            for (ApiTaskConfig.FormField f : body.getFields()) {
                if (f.getName() == null || f.getName().isEmpty()) continue;
                sb.append("--").append(boundary).append(crlf);
                sb.append("Content-Disposition: form-data; name=\"").append(f.getName()).append("\"").append(crlf);
                sb.append(crlf);
                sb.append(f.getValue() == null ? "" : f.getValue()).append(crlf);
            }
        }
        sb.append("--").append(boundary).append("--").append(crlf);
        return new PreparedBody(
                HttpRequest.BodyPublishers.ofString(sb.toString(), StandardCharsets.UTF_8),
                "multipart/form-data; boundary=" + boundary);
    }

    private void appendFormFields(StringBuilder sb, ApiTaskConfig.Body body, String sep, boolean urlEncode) {
        if (body.getFields() == null) return;
        List<ApiTaskConfig.FormField> fields = body.getFields();
        for (int i = 0; i < fields.size(); i++) {
            ApiTaskConfig.FormField f = fields.get(i);
            if (f.getName() == null || f.getName().isEmpty()) continue;
            if (i > 0) sb.append(sep);
            sb.append(urlEncode ? urlEncode(f.getName()) : f.getName());
            sb.append("=");
            sb.append(urlEncode ? urlEncode(f.getValue() == null ? "" : f.getValue()) : (f.getValue() == null ? "" : f.getValue()));
        }
    }

    private String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private Map<String, String> collectHeaders(List<ApiTaskConfig.Header> headers) {
        Map<String, String> map = new LinkedHashMap<>();
        if (headers == null) return map;
        List<String> seen = new ArrayList<>();
        for (ApiTaskConfig.Header h : headers) {
            if (h == null || h.getName() == null || h.getName().isBlank()) continue;
            String lower = h.getName().toLowerCase();
            seen.add(lower);
            map.put(lower, h.getValue());
        }
        return map;
    }

    private boolean hasContentType(Map<String, String> headers) {
        return headers.containsKey("content-type");
    }

    private String userContentType(Map<String, String> headers) {
        return headers.get("content-type");
    }

    private String normalizeMethod(String method) {
        if (method == null || method.isBlank()) return "GET";
        return method.trim().toUpperCase();
    }

    private record PreparedBody(HttpRequest.BodyPublisher bodyPublisher, String contentType) {
        static PreparedBody none() { return new PreparedBody(null, null); }
    }

    public record Response(int status, String contentType, String body) {}
}
