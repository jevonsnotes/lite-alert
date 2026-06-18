package io.litealert.transform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.PathNotFoundException;
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import io.litealert.common.template.TemplateFunctions;
import io.litealert.topic.domain.Topic;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Template-based transform for the WEBHOOK channel.
 *
 * <p>User provides a full JSON "output template" (e.g.
 * {@code {"title": "订单 {{orderId}}", "level": "{{level}}"}}). Each row in
 * the mapping table says "take this JSONPath from the inbound payload and
 * write it into this template field" (via dotted target path).
 *
 * <p>Rendering order:
 * <ol>
 *   <li>Resolve variables in every string leaf:
 *       system vars, dynamic vars ({{uuid}}, {{timestamp}}, {{date}}, etc.),
 *       inline JSONPath ({{$.user.name}}), and function sections
 *       ({{#md5}}$.id + $.ts{{/md5}}, {{#upper}}$.name{{/upper}}, …)</li>
 *   <li>Apply mapping rows — overwrite template fields with values from the
 *       inbound payload.</li>
 * </ol>
 *
 * <p>Step 2 intentionally overwrites step 1 — if a mapping targets a field
 * that also contained a system variable, the payload value wins.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookTemplateEngine {

    /** Matches {{variableName}} — supports alnum, dot, underscore, and $. */
    private static final Pattern VAR = Pattern.compile("\\{\\{\\s*([\\w.$]+)\\s*\\}\\}");

    private final ObjectMapper objectMapper;

    private final Configuration jsonPathConfig = Configuration.builder()
            .jsonProvider(new JacksonJsonNodeJsonProvider())
            .mappingProvider(new JacksonMappingProvider())
            .options(Option.SUPPRESS_EXCEPTIONS, Option.DEFAULT_PATH_LEAF_TO_NULL)
            .build();

    /**
     * Renders the webhook template against a payload and system variables.
     *
     * @param template the user-provided outbound JSON (may contain {{var}} leaves)
     * @param payload  the validated inbound JSON
     * @param mappings field-mapping rows (from/to/type/…)
     * @param system   system variables (namespace, topic, traceId, receivedAt, rawJson)
     * @return the final JSON to POST to the webhook URL
     */
    public JsonNode render(JsonNode template, JsonNode payload,
                           List<Topic.Transform.Mapping> mappings,
                           Map<String, String> system) {
        if (template == null) {
            // no template → forward payload as-is
            return payload;
        }
        // Deep-copy so we don't mutate the stored template.
        JsonNode out = template.deepCopy();

        // Phase 1: resolve variables + functions in every string leaf.
        out = resolveVariables(out, system, payload);

        // Phase 2: apply mapping rows (overwrite).
        if (mappings != null) {
            for (Topic.Transform.Mapping m : mappings) {
                if (m.getFrom() == null || m.getTo() == null || m.getTo().isBlank()) continue;
                Object raw;
                try {
                    raw = JsonPath.using(jsonPathConfig).parse(payload).read(m.getFrom());
                } catch (PathNotFoundException e) {
                    if (m.isRequired()) {
                        log.debug("webhook transform: required path {} not found", m.getFrom());
                    }
                    continue;
                }
                JsonNode value = raw instanceof JsonNode n ? n : objectMapper.valueToTree(raw);
                if (value == null || value.isNull()) {
                    if (m.getDefaultValue() != null) {
                        value = objectMapper.valueToTree(m.getDefaultValue());
                    } else {
                        continue;
                    }
                }
                JsonNode coerced = coerce(value, m.getType());
                writeByPath((ObjectNode) out, m.getTo(), coerced);
            }
        }

        return out;
    }

    public String renderXml(String template, JsonNode payload,
                           List<Topic.Transform.Mapping> mappings,
                           Map<String, String> system) {
        if (template == null || template.isBlank()) return "";
        String text = TemplateFunctions.applyFunctions(template,
                expr -> resolveXmlSingle(expr, system, payload));
        Matcher m = VAR.matcher(text);
        StringBuilder sb = new StringBuilder();
        int pos = 0;
        while (m.find()) {
            sb.append(text, pos, m.start());
            sb.append(resolveXmlSingle(m.group(1), system, payload));
            pos = m.end();
        }
        sb.append(text.substring(pos));
        text = sb.toString();

        // Apply mapping rows: overwrite placeholder positions with payload values.
        if (mappings != null) {
            for (Topic.Transform.Mapping mapping : mappings) {
                if (mapping.getFrom() == null || mapping.getTo() == null || mapping.getTo().isBlank()) continue;
                Object raw;
                try {
                    raw = JsonPath.using(jsonPathConfig).parse(payload).read(mapping.getFrom());
                } catch (PathNotFoundException e) {
                    if (mapping.isRequired()) {
                        log.debug("webhook xml transform: required path {} not found", mapping.getFrom());
                    }
                    continue;
                }
                String value;
                if (raw instanceof JsonNode n && n.isValueNode()) {
                    value = escapeXml(n.asText());
                } else if (raw != null) {
                    value = escapeXml(String.valueOf(raw));
                } else if (mapping.getDefaultValue() != null) {
                    value = escapeXml(String.valueOf(mapping.getDefaultValue()));
                } else {
                    continue;
                }
                // Replace any {{to}} placeholder in the rendered XML.
                text = text.replace("{{" + mapping.getTo() + "}}", value);
            }
        }

        return text;
    }

    private String resolveXmlSingle(String name, Map<String, String> system, JsonNode payload) {
        String dynamic = TemplateFunctions.resolveDynamicVar(name);
        if (dynamic != null) return escapeXml(dynamic);
        if (name.startsWith("$")) {
            JsonNode node = evaluateJsonPathNode(name, payload);
            if (node == null || node.isNull()) return "";
            if (node.isValueNode()) return escapeXml(node.asText());
            if (node.isArray()) return xmlArray(node);
            return xmlElement(xmlNameFromPath(name), node);
        }
        String v = system.get(name);
        if (v != null) return escapeXml(v);
        return "{{" + name + "}}";
    }

    private JsonNode evaluateJsonPathNode(String path, JsonNode payload) {
        if (payload == null) return null;
        try {
            return JsonPath.using(jsonPathConfig).parse(payload).read(path);
        } catch (Exception e) {
            log.debug("webhook xml jsonPath({}) failed: {}", path, e.getMessage());
            return null;
        }
    }

    private String xmlNameFromPath(String path) {
        String cleaned = path.replaceAll("\\[[^]]*]", "");
        int dot = cleaned.lastIndexOf('.');
        String name = dot >= 0 ? cleaned.substring(dot + 1) : cleaned.replace("$", "root");
        return name.isBlank() || "$".equals(name) ? "root" : sanitizeXmlName(name);
    }

    private String xmlElement(String name, JsonNode node) {
        if (node == null || node.isNull()) return "<" + name + "/>";
        if (node.isValueNode()) return "<" + name + ">" + escapeXml(node.asText()) + "</" + name + ">";
        if (node.isArray()) return "<" + name + ">" + xmlArray(node) + "</" + name + ">";
        StringBuilder sb = new StringBuilder();
        sb.append('<').append(name).append('>');
        node.fields().forEachRemaining(e -> sb.append(xmlElement(sanitizeXmlName(e.getKey()), e.getValue())));
        sb.append("</").append(name).append('>');
        return sb.toString();
    }

    private String xmlArray(JsonNode arr) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode item : arr) sb.append(xmlElement("item", item));
        return sb.toString();
    }

    private String sanitizeXmlName(String raw) {
        String s = raw.replaceAll("[^A-Za-z0-9_.-]", "_");
        if (s.isBlank() || !Character.isLetter(s.charAt(0)) && s.charAt(0) != '_') s = "n_" + s;
        return s;
    }

    private String escapeXml(String raw) {
        return raw == null ? "" : raw
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    /**
     * Walks every string leaf of {@code node} and replaces {{var}} references.
     * Supports:
     * - System variables: {{namespace}}, {{topic}}, {{traceId}}, {{receivedAt}}, {{rawJson}}
     * - Dynamic variables: {{uuid}}, {{timestamp}}, {{date}}, {{dateFull}}, {{dateUtc}}
     * - Inline JSONPath: {{$.user.name}}, {{$.items[0].sku}}
     * - Dotted shorthand: {{user.name}} (resolved as $.user.name against payload)
     * - Functions: {{#md5}}$.id + $.ts{{/md5}}, {{#upper}}$.name{{/upper}}, etc.
     */
    private JsonNode resolveVariables(JsonNode node, Map<String, String> system, JsonNode payload) {
        if (node == null || node.isNull()) return node;
        if (node.isTextual()) {
            String text = node.textValue();
            // First: resolve function sections ({{#md5}}…{{/md5}}, etc.)
            text = TemplateFunctions.applyFunctions(text,
                    expr -> resolveSingle(expr, system, payload));
            // Then: resolve remaining {{var}} references.
            Matcher m = VAR.matcher(text);
            if (m.find()) {
                m.reset();
                StringBuilder sb = new StringBuilder();
                int pos = 0;
                while (m.find()) {
                    sb.append(text, pos, m.start());
                    String varName = m.group(1);
                    sb.append(resolveSingle(varName, system, payload));
                    pos = m.end();
                }
                sb.append(text.substring(pos));
                text = sb.toString();
            }
            return objectMapper.valueToTree(text);
        } else if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            obj.fieldNames().forEachRemaining(f ->
                    obj.set(f, resolveVariables(obj.get(f), system, payload)));
            return obj;
        } else if (node.isArray()) {
            com.fasterxml.jackson.databind.node.ArrayNode arr =
                    (com.fasterxml.jackson.databind.node.ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) {
                arr.set(i, resolveVariables(arr.get(i), system, payload));
            }
            return arr;
        }
        return node;
    }

    private String resolveSingle(String name, Map<String, String> system, JsonNode payload) {
        // Dynamic runtime variables.
        String dynamic = TemplateFunctions.resolveDynamicVar(name);
        if (dynamic != null) return dynamic;
        // Inline JSONPath: {{$.user.name}}
        if (name.startsWith("$")) {
            return evaluateJsonPath(name, payload);
        }
        // Pass-through system variable only — payload fields must use {{$.path}}.
        // This avoids collisions (e.g. a payload field named "traceId" overriding
        // the real traceId).
        String v = system.get(name);
        if (v != null) return v;
        // Unresolved — leave the placeholder so the user can spot it.
        return "{{" + name + "}}";
    }

    private String evaluateJsonPath(String path, JsonNode payload) {
        if (payload == null) return "";
        try {
            JsonNode node = JsonPath.using(jsonPathConfig).parse(payload).read(path);
            if (node == null || node.isNull()) return "";
            if (node.isValueNode()) return node.asText();
            try {
                return objectMapper.writeValueAsString(node);
            } catch (Exception e) {
                return node.toString();
            }
        } catch (Exception e) {
            log.debug("webhook var jsonPath({}) failed: {}", path, e.getMessage());
            return "";
        }
    }

    private JsonNode coerce(JsonNode value, String type) {
        if (type == null || type.isEmpty() || type.equalsIgnoreCase("json")) return value;
        switch (type.toLowerCase()) {
            case "string":
                return objectMapper.valueToTree(value.isTextual() ? value.textValue() : value.asText());
            case "number":
                if (value.isNumber()) return value;
                try { return objectMapper.valueToTree(Double.parseDouble(value.asText())); }
                catch (NumberFormatException e) { return value; }
            case "boolean":
                return objectMapper.valueToTree(value.asBoolean());
            default:
                if (type.startsWith("array<")) {
                    String inner = type.substring("array<".length(), type.length() - 1);
                    com.fasterxml.jackson.databind.node.ArrayNode arr = objectMapper.createArrayNode();
                    if (value.isArray()) {
                        for (JsonNode item : value) arr.add(coerce(item, inner));
                    } else {
                        arr.add(coerce(value, inner));
                    }
                    return arr;
                }
                return value;
        }
    }

    /**
     * Writes {@code value} into {@code root} at a dotted target path,
     * e.g. {@code "user.name"} → {@code root.user.name}.
     * Intermediate nodes are created as needed.
     */
    private void writeByPath(ObjectNode root, String dottedPath, JsonNode value) {
        if (dottedPath == null || dottedPath.isBlank()) return;
        String[] parts = dottedPath.split("\\.");
        ObjectNode cur = root;
        for (int i = 0; i < parts.length; i++) {
            String seg = parts[i];
            int bracket = seg.indexOf('[');
            String name = bracket < 0 ? seg : seg.substring(0, bracket);
            Integer index = bracket < 0 ? null
                    : Integer.parseInt(seg.substring(bracket + 1, seg.indexOf(']')));
            boolean last = i == parts.length - 1;
            if (last) {
                if (index == null) {
                    cur.set(name, value);
                } else {
                    com.fasterxml.jackson.databind.node.ArrayNode arr =
                            cur.has(name) && cur.get(name).isArray()
                            ? (com.fasterxml.jackson.databind.node.ArrayNode) cur.get(name)
                            : objectMapper.createArrayNode();
                    while (arr.size() <= index) arr.addNull();
                    arr.set(index, value);
                    cur.set(name, arr);
                }
            } else {
                JsonNode next = cur.get(name);
                if (next == null || !next.isObject()) {
                    next = objectMapper.createObjectNode();
                    cur.set(name, next);
                }
                cur = (ObjectNode) next;
            }
        }
    }
}
