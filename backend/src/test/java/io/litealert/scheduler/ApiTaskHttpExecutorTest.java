package io.litealert.scheduler;

import io.litealert.scheduler.domain.ApiTaskConfig;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApiTaskHttpExecutorTest {

    private final ApiTaskHttpExecutor executor = new ApiTaskHttpExecutor();

    private ApiTaskConfig config(String method, String url) {
        ApiTaskConfig c = new ApiTaskConfig();
        c.setMethod(method);
        c.setUrl(url);
        return c;
    }

    private ApiTaskConfig.Body rawBody(ApiTaskConfig.Body.RawType rawType, String text) {
        ApiTaskConfig.Body b = new ApiTaskConfig.Body();
        b.setType(ApiTaskConfig.Body.Type.RAW);
        b.setRawType(rawType);
        b.setRawText(text);
        return b;
    }

    private ApiTaskConfig.Body fieldsBody(ApiTaskConfig.Body.Type type, ApiTaskConfig.FormField... fields) {
        ApiTaskConfig.Body b = new ApiTaskConfig.Body();
        b.setType(type);
        b.setFields(List.of(fields));
        return b;
    }

    @Test
    void rawJsonAutoContentType() {
        ApiTaskConfig c = config("POST", "https://x.example");
        c.setBody(rawBody(ApiTaskConfig.Body.RawType.JSON, "{\"a\":1}"));
        assertThat(executor.autoContentType(c)).startsWith("application/json");
    }

    @Test
    void rawXmlAutoContentType() {
        ApiTaskConfig c = config("POST", "https://x.example");
        c.setBody(rawBody(ApiTaskConfig.Body.RawType.XML, "<a/>"));
        assertThat(executor.autoContentType(c)).startsWith("application/xml");
    }

    @Test
    void rawTextAutoContentType() {
        ApiTaskConfig c = config("POST", "https://x.example");
        c.setBody(rawBody(ApiTaskConfig.Body.RawType.TEXT, "hello"));
        assertThat(executor.autoContentType(c)).startsWith("text/plain");
    }

    @Test
    void formDataAutoContentTypeHasBoundary() {
        ApiTaskConfig c = config("POST", "https://x.example");
        c.setBody(fieldsBody(ApiTaskConfig.Body.Type.FORM_DATA,
                new ApiTaskConfig.FormField("k", "v")));
        String ct = executor.autoContentType(c);
        assertThat(ct).startsWith("multipart/form-data; boundary=");
    }

    @Test
    void urlEncodedAutoContentType() {
        ApiTaskConfig c = config("POST", "https://x.example");
        c.setBody(fieldsBody(ApiTaskConfig.Body.Type.URL_ENCODED,
                new ApiTaskConfig.FormField("k", "v")));
        assertThat(executor.autoContentType(c)).isEqualTo("application/x-www-form-urlencoded");
    }

    @Test
    void userExplicitContentTypeWins() {
        ApiTaskConfig c = config("POST", "https://x.example");
        c.setBody(rawBody(ApiTaskConfig.Body.RawType.JSON, "{}"));
        c.setHeaders(List.of(new ApiTaskConfig.Header("Content-Type", "application/vnd.custom+json")));
        assertThat(executor.autoContentType(c)).isEqualTo("application/vnd.custom+json");
    }

    @Test
    void noneBodyHasNoContentType() {
        ApiTaskConfig c = config("GET", "https://x.example");
        c.setBody(new ApiTaskConfig.Body()); // NONE
        assertThat(executor.autoContentType(c)).isNull();
    }

    @Test
    void buildRequestUsesConfiguredMethodAndHeaders() {
        ApiTaskConfig c = config("PUT", "https://x.example/api");
        c.setHeaders(List.of(new ApiTaskConfig.Header("Authorization", "Bearer xxx")));
        c.setBody(rawBody(ApiTaskConfig.Body.RawType.JSON, "{\"a\":1}"));
        HttpRequest req = executor.buildRequest(c);

        assertThat(req.method()).isEqualTo("PUT");
        assertThat(req.headers().firstValue("authorization").orElse(""))
                .isEqualTo("Bearer xxx");
        assertThat(req.headers().firstValue("content-type").orElse(""))
                .startsWith("application/json");
    }

    @Test
    void buildRequestGetHasNoBody() {
        ApiTaskConfig c = config("GET", "https://x.example");
        HttpRequest req = executor.buildRequest(c);
        assertThat(req.method()).isEqualTo("GET");
        assertThat(req.bodyPublisher().isPresent()).isFalse();
    }
}
