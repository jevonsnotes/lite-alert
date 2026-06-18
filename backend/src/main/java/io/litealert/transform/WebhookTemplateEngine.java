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
import org.w3c.dom.*;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Template-based transform for the WEBHOOK channel.
 *
 * <p>Supports both JSON and XML output templates.
 *
 * <p>For JSON: User provides a full JSON "output template" (e.g.
 * {@code {"title": "订单 {{orderId}}", "level": "{{level}}"}}). Each row in
 * the mapping table says "take this JSONPath from the inbound payload and
 * write it into this template field" (via dotted target path).
 *
 * <p>For XML: User provides an XML template string. Placeholders use the same
 * {{var}} syntax. Mapping rows create XML elements or replace {{to}} placeholders.
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
     * Renders a JSON webhook template against a payload and system variables.
     */
    public JsonNode render(JsonNode template, JsonNode payload,
                           List<Topic.Transform.Mapping> mappings,
                           Map<String, String> system) {
        if (template == null) return payload;
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
                    if (m.isRequired()) log.debug("webhook transform: required path {} not found", m.getFrom());
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

    /**
     * Renders an XML webhook template against a payload and system variables.
     *
     * Rendering order:
     * 1. Resolve {{$.path}} and {{var}} placeholders in text nodes.
     *    JSONPath on objects/arrays converts to XML node fragments.
     * 2. Apply mapping rows — replace {{to}} placeholders or create XML elements.
     */
    public String renderXml(String template, JsonNode payload,
                           List<Topic.Transform.Mapping> mappings,
                           Map<String, String> system) {
        if (template == null || template.isBlank()) return "";
        try {
            String wrapped = "<__wr__>" + template + "</__wr__>";
            Document doc = parseXml(wrapped);

            // Phase 1: resolve variables in text nodes.
            resolveXmlTextNodes(doc.getDocumentElement(), system, payload);

            // Phase 2: apply mapping rows.
            if (mappings != null) {
                for (Topic.Transform.Mapping m : mappings) {
                    applyXmlMapping(doc.getDocumentElement(), m, payload);
                }
            }

            String xml = serializeXml(doc);
            int start = xml.indexOf('>');
            int end = xml.lastIndexOf("</__wr__>");
            if (start >= 0 && end > start) {
                return xml.substring(start + 1, end).trim();
            }
            return xml.trim();
        } catch (Exception e) {
            log.error("xml template render failed: {}", e.getMessage());
            return "<error>" + escapeXml(e.getMessage()) + "</error>";
        }
    }

    private Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        DocumentBuilder b = f.newDocumentBuilder();
        return b.parse(new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private String serializeXml(Document doc) throws Exception {
        Transformer t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        java.io.StringWriter sw = new java.io.StringWriter();
        t.transform(new DOMSource(doc), new StreamResult(sw));
        return sw.toString();
    }

    private void resolveXmlTextNodes(Node node, Map<String, String> system, JsonNode payload) {
        if (node.getNodeType() == Node.TEXT_NODE) {
            String text = node.getTextContent();
            text = TemplateFunctions.applyFunctions(text,
                    expr -> resolveXmlSingleRaw(expr, system, payload));
            Matcher m = VAR.matcher(text);
            if (m.find()) {
                m.reset();
                StringBuilder sb = new StringBuilder();
                int pos = 0;
                while (m.find()) {
                    sb.append(text, pos, m.start());
                    String varName = m.group(1);
                    String resolved = resolveXmlSingleRaw(varName, system, payload);
                    if (resolved.startsWith("<") && resolved.contains("</")) {
                        Node parent = node.getParentNode();
                        if (sb.length() > 0) {
                            parent.insertBefore(node.getOwnerDocument().createTextNode(sb.toString()), node);
                        }
                        String frag = "<__f__>" + resolved + "</__f__>";
                        try {
                            Document fragDoc = parseXml(frag);
                            NodeList children = fragDoc.getDocumentElement().getChildNodes();
                            for (int i = 0; i < children.getLength(); i++) {
                                Node imported = node.getOwnerDocument().importNode(children.item(i), true);
                                parent.insertBefore(imported, node);
                            }
                        } catch (Exception e) {
                            parent.insertBefore(node.getOwnerDocument().createTextNode(resolved), node);
                        }
                        node.setTextContent("");
                        return;
                    } else {
                        sb.append(resolved);
                    }
                    pos = m.end();
                }
                sb.append(text.substring(pos));
                node.setTextContent(sb.toString());
            }
        } else if (node.getNodeType() == Node.ELEMENT_NODE) {
            List<Node> snapshot = new ArrayList<>();
            NodeList children = node.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) snapshot.add(children.item(i));
            for (Node child : snapshot) resolveXmlTextNodes(child, system, payload);
        }
    }

    private void applyXmlMapping(Element root, Topic.Transform.Mapping m, JsonNode payload) {
        if (m.getFrom() == null || m.getTo() == null || m.getTo().isBlank()) return;
        Object raw;
        try {
            raw = JsonPath.using(jsonPathConfig).parse(payload).read(m.getFrom());
        } catch (PathNotFoundException e) {
            if (m.isRequired()) log.debug("webhook xml transform: required path {} not found", m.getFrom());
            return;
        }
        String value;
        if (raw instanceof JsonNode n && n.isValueNode()) {
            value = n.asText();
        } else if (raw != null) {
            value = String.valueOf(raw);
        } else if (m.getDefaultValue() != null) {
            value = String.valueOf(m.getDefaultValue());
        } else {
            return;
        }
        String escapedValue = escapeXml(value);
        String placeholder = "{{" + m.getTo() + "}}";

        // First: try replacing existing {{to}} placeholder in the whole tree.
        if (replacePlaceholderInText(root, placeholder, escapedValue)) return;

        // Second: create the dotted path as XML elements under root.
        String[] parts = m.getTo().split("\\.");
        Element cur = root;
        for (int i = 0; i < parts.length; i++) {
            String seg = sanitizeXmlName(parts[i]);
            boolean last = i == parts.length - 1;
            Element existing = findFirstChild(cur, seg);
            if (last) {
                if (existing == null) {
                    Element el = cur.getOwnerDocument().createElement(seg);
                    el.setTextContent(value);
                    cur.appendChild(el);
                } else {
                    existing.setTextContent(value);
                }
            } else {
                if (existing == null) {
                    Element el = cur.getOwnerDocument().createElement(seg);
                    cur.appendChild(el);
                    cur = el;
                } else {
                    cur = existing;
                }
            }
        }
    }

    private boolean replacePlaceholderInText(Node node, String placeholder, String value) {
        if (node.getNodeType() == Node.TEXT_NODE) {
            String text = node.getTextContent();
            if (text.contains(placeholder)) {
                node.setTextContent(text.replace(placeholder, value));
                return true;
            }
            return false;
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (replacePlaceholderInText(children.item(i), placeholder, value)) return true;
        }
        return false;
    }

    private Element findFirstChild(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node c = children.item(i);
            if (c.getNodeType() == Node.ELEMENT_NODE && name.equals(c.getNodeName())) {
                return (Element) c;
            }
        }
        return null;
    }

    private String resolveXmlSingle(String name, Map<String, String> system, JsonNode payload) {
        return resolveXmlSingleRaw(name, system, payload);
    }

    /**
     * Resolve a single placeholder. Returns XML-safe text (unescaped) or XML fragments.
     * The DOM serializer handles escaping for text nodes.
     */
    private String resolveXmlSingleRaw(String name, Map<String, String> system, JsonNode payload) {
        String dynamic = TemplateFunctions.resolveDynamicVar(name);
        if (dynamic != null) return dynamic;
        if (name.startsWith("$")) {
            JsonNode node = evaluateJsonPathNode(name, payload);
            if (node == null || node.isNull()) return "";
            if (node.isValueNode()) return node.asText();
            if (node.isArray()) return xmlArray(node);
            return xmlElement(xmlNameFromPath(name), node);
        }
        String v = system.get(name);
        if (v != null) return v;
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
        if (s.isBlank() || (!Character.isLetter(s.charAt(0)) && s.charAt(0) != '_')) s = "n_" + s;
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
            text = TemplateFunctions.applyFunctions(text,
                    expr -> resolveSingle(expr, system, payload));
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
        String dynamic = TemplateFunctions.resolveDynamicVar(name);
        if (dynamic != null) return dynamic;
        if (name.startsWith("$")) {
            return evaluateJsonPath(name, payload);
        }
        String v = system.get(name);
        if (v != null) return v;
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
     * Writes {@code value} into {@code root} at a dotted target path.
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
