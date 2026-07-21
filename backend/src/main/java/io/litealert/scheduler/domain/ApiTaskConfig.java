package io.litealert.scheduler.domain;

import com.fasterxml.jackson.annotation.JsonTypeName;

import java.util.List;

/**
 * Persisted configuration for an API-type scheduled task. Stored as JSON text in
 * {@code draft_config_json} / {@code published_config_json} on {@link SchedulerTask}.
 *
 * <p>Extends {@link TaskConfig} (design D1): {@link Meta} and {@link Timeouts} live on the base and
 * are shared with {@link TcpTaskConfig}. HTTP-specific fields (method/url/headers/body/assertion)
 * stay here. Mirrors design D3 (request building + auto content-type) and D4 (multi-condition
 * assertion with AND/OR logic).
 */
@JsonTypeName("API")
public class ApiTaskConfig extends TaskConfig {

    /** HTTP method: GET/POST/PUT/DELETE/PATCH/HEAD/OPTIONS. */
    private String method;
    /** Request URL. */
    private String url;
    /** Custom request headers (name -> value). User-provided Content-Type wins. */
    private List<Header> headers;
    /** Request body definition. */
    private Body body;
    /** Response-body assertion. May be null/empty → judge by HTTP status only. */
    private Assertion assertion;

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public List<Header> getHeaders() { return headers; }
    public void setHeaders(List<Header> headers) { this.headers = headers; }
    public Body getBody() { return body; }
    public void setBody(Body body) { this.body = body; }
    public Assertion getAssertion() { return assertion; }
    public void setAssertion(Assertion assertion) { this.assertion = assertion; }

    public static class Header {
        private String name;
        private String value;
        public Header() {}
        public Header(String name, String value) { this.name = name; this.value = value; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
    }

    /** Request body. {@code type=NONE} → no body, no auto Content-Type. */
    public static class Body {
        public enum Type { NONE, FORM_DATA, URL_ENCODED, RAW }
        public enum RawType { JSON, XML, TEXT }

        private Type type = Type.NONE;
        /** When type=RAW: json/xml/text. */
        private RawType rawType;
        /** When type=RAW: the raw text payload. */
        private String rawText;
        /** When type=FORM_DATA or URL_ENCODED: key/value entries. */
        private List<FormField> fields;

        public Type getType() { return type; }
        public void setType(Type type) { this.type = type == null ? Type.NONE : type; }
        public RawType getRawType() { return rawType; }
        public void setRawType(RawType rawType) { this.rawType = rawType; }
        public String getRawText() { return rawText; }
        public void setRawText(String rawText) { this.rawText = rawText; }
        public List<FormField> getFields() { return fields; }
        public void setFields(List<FormField> fields) { this.fields = fields; }
    }

    public static class FormField {
        private String name;
        private String value;
        public FormField() {}
        public FormField(String name, String value) { this.name = name; this.value = value; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
    }

    /** Multi-condition response assertion. */
    public static class Assertion {
        public enum Logic { AND, OR }
        private Logic logic = Logic.AND;
        private List<Condition> conditions;

        public Logic getLogic() { return logic; }
        public void setLogic(Logic logic) { this.logic = logic == null ? Logic.AND : logic; }
        public List<Condition> getConditions() { return conditions; }
        public void setConditions(List<Condition> conditions) { this.conditions = conditions; }
    }

    /** A single assertion condition: extract value at {@code path}, compare with {@code expected} via {@code operator}. */
    public static class Condition {
        public enum BodyType { AUTO, JSON, XML }
        public enum Operator { EQ, NE, CONTAINS, REGEX, GT, LT, EXISTS }

        /** JSONPath (JSON) or XPath (XML). */
        private String path;
        private Operator operator = Operator.EQ;
        private String expected;
        /** Body format hint; AUTO = detect from content-type / body prefix. */
        private BodyType bodyType = BodyType.AUTO;

        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public Operator getOperator() { return operator; }
        public void setOperator(Operator operator) { this.operator = operator == null ? Operator.EQ : operator; }
        public String getExpected() { return expected; }
        public void setExpected(String expected) { this.expected = expected; }
        public BodyType getBodyType() { return bodyType; }
        public void setBodyType(BodyType bodyType) { this.bodyType = bodyType == null ? BodyType.AUTO : bodyType; }
    }
}
