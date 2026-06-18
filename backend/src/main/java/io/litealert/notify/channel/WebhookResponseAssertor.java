package io.litealert.notify.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import io.litealert.topic.domain.Topic;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

@Component
public class WebhookResponseAssertor {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Configuration jsonPathConfig = Configuration.builder()
            .jsonProvider(new JacksonJsonNodeJsonProvider())
            .mappingProvider(new JacksonMappingProvider())
            .options(Option.SUPPRESS_EXCEPTIONS, Option.DEFAULT_PATH_LEAF_TO_NULL)
            .build();

    public Result check(Topic.WebhookResponseCheck check, int status, String contentType, String body) {
        if (status < 200 || status >= 300) {
            return new Result(false, null, null, "HTTP " + status, status, body);
        }
        if (check == null || !check.isEnabled()) {
            return new Result(true, null, null, null, status, body);
        }
        if (check.getSuccessPath() == null || check.getSuccessPath().isBlank()) {
            return new Result(true, null, null, null, status, body);
        }
        BodyKind kind = detect(check.getBodyType(), contentType, body);
        if (kind == BodyKind.UNKNOWN) {
            return new Result(false, check.getSuccessPath(), check.getSuccessValue(), "无法识别响应体格式", status, body);
        }
        String actual = extract(kind, body, check.getSuccessPath());
        boolean ok = compare(actual, check.getSuccessValue(), check.getOperator());
        if (ok) {
            return new Result(true, check.getSuccessPath(), actual, null, status, body);
        }
        String msg = check.getMessagePath() == null || check.getMessagePath().isBlank()
                ? null
                : extract(kind, body, check.getMessagePath());
        if (msg == null || msg.isBlank()) {
            msg = "响应断言失败：" + check.getSuccessPath() + "=" + actual
                    + "，期望 " + check.getOperator() + " " + check.getSuccessValue();
        }
        return new Result(false, check.getSuccessPath(), actual, msg, status, body);
    }

    private BodyKind detect(Topic.WebhookResponseCheck.BodyType configured, String contentType, String body) {
        if (configured == Topic.WebhookResponseCheck.BodyType.JSON) return BodyKind.JSON;
        if (configured == Topic.WebhookResponseCheck.BodyType.XML) return BodyKind.XML;
        String ct = contentType == null ? "" : contentType.toLowerCase();
        if (ct.contains("json")) return BodyKind.JSON;
        if (ct.contains("xml")) return BodyKind.XML;
        String trimmed = body == null ? "" : body.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return BodyKind.JSON;
        if (trimmed.startsWith("<")) return BodyKind.XML;
        return BodyKind.UNKNOWN;
    }

    private String extract(BodyKind kind, String body, String path) {
        try {
            if (kind == BodyKind.JSON) {
                JsonNode node = JsonPath.using(jsonPathConfig).parse(mapper.readTree(body)).read(path);
                if (node == null || node.isNull()) return null;
                return node.isValueNode() ? node.asText() : node.toString();
            }
            Document doc = parseXml(body);
            Object value = XPathFactory.newInstance().newXPath().evaluate(path, doc, XPathConstants.STRING);
            return value == null ? null : String.valueOf(value);
        } catch (Exception e) {
            return null;
        }
    }

    private Document parseXml(String body) throws Exception {
        var f = DocumentBuilderFactory.newInstance();
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(String.valueOf(body).getBytes(StandardCharsets.UTF_8)));
    }

    private boolean compare(String actual, String expected, Topic.WebhookResponseCheck.Operator op) {
        op = op == null ? Topic.WebhookResponseCheck.Operator.EQ : op;
        if (op == Topic.WebhookResponseCheck.Operator.EXISTS) return actual != null && !actual.isBlank();
        if (actual == null) return false;
        expected = expected == null ? "" : expected;
        return switch (op) {
            case EQ -> actual.equals(expected);
            case NE -> !actual.equals(expected);
            case CONTAINS -> actual.contains(expected);
            case REGEX -> Pattern.compile(expected).matcher(actual).find();
            case GT -> toDouble(actual) > toDouble(expected);
            case LT -> toDouble(actual) < toDouble(expected);
            case EXISTS -> actual != null && !actual.isBlank();
        };
    }

    private double toDouble(String value) {
        try { return Double.parseDouble(value); }
        catch (Exception e) { return Double.NaN; }
    }

    enum BodyKind { JSON, XML, UNKNOWN }

    public record Result(
            boolean success,
            String path,
            String actual,
            String message,
            int httpStatus,
            String body
    ) {}
}
