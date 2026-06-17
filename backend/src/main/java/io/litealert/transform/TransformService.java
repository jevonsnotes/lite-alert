package io.litealert.transform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import io.litealert.common.error.BusinessException;
import io.litealert.common.error.ErrorCode;
import io.litealert.topic.domain.Topic;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Field-mapping transform: read each source path with JSONPath, coerce to
 * the declared type, and place the result at the target key path inside a
 * fresh object.
 *
 * <p>Deliberately does ONE thing: build a flat / nested object whose keys
 * are decided by the mapping table. Users that want string interpolation
 * (e.g. "订单 {{orderId}} 已支付") do it in the notify template, where
 * Mustache is already available — keeping concerns separate.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransformService {

    private final ObjectMapper objectMapper;

    private final Configuration jsonPathConfig = Configuration.builder()
            .jsonProvider(new JacksonJsonNodeJsonProvider())
            .mappingProvider(new JacksonMappingProvider())
            .options(Option.SUPPRESS_EXCEPTIONS, Option.DEFAULT_PATH_LEAF_TO_NULL)
            .build();

    public TransformResult apply(Topic.Transform transform, JsonNode body) {
        if (transform == null || !transform.isEnabled()) {
            return new TransformResult(body, List.of());
        }
        ObjectNode result = objectMapper.createObjectNode();
        List<MappingTrace> traces = new ArrayList<>();

        List<Topic.Transform.Mapping> mappings = transform.getMappings();
        if (mappings != null) {
            for (Topic.Transform.Mapping m : mappings) {
                MappingTrace tr = applyOne(m, body, result);
                traces.add(tr);
                if (tr.failed && m.isRequired()) {
                    throw new BusinessException(ErrorCode.TRANSFORM_FAILED,
                            "required mapping '" + m.getFrom() + "' could not be resolved");
                }
            }
        }
        return new TransformResult(result, traces);
    }

    private MappingTrace applyOne(Topic.Transform.Mapping m, JsonNode body, ObjectNode result) {
        Object raw;
        try {
            raw = JsonPath.using(jsonPathConfig).parse(body).read(m.getFrom());
        } catch (Exception e) {
            log.debug("jsonpath eval failed: {} → {}", m.getFrom(), e.getMessage());
            applyDefault(m, result);
            return new MappingTrace(m.getFrom(), m.getTo(), false, true, "jsonpath error");
        }

        JsonNode value = raw instanceof JsonNode n ? n : objectMapper.valueToTree(raw);
        if (value == null || value.isNull()) {
            if (m.getDefaultValue() != null) {
                applyDefault(m, result);
                return new MappingTrace(m.getFrom(), m.getTo(), true, false, "used default");
            }
            return new MappingTrace(m.getFrom(), m.getTo(), false, true, "no value");
        }
        JsonNode coerced = coerce(value, m.getType());
        writePointer(result, m.getTo(), coerced);
        return new MappingTrace(m.getFrom(), m.getTo(), true, false, null);
    }

    private void applyDefault(Topic.Transform.Mapping m, ObjectNode result) {
        if (m.getDefaultValue() == null) return;
        JsonNode val = objectMapper.valueToTree(m.getDefaultValue());
        writePointer(result, m.getTo(), val);
    }

    private JsonNode coerce(JsonNode value, String type) {
        if (type == null || type.equalsIgnoreCase("json")) return value;
        switch (type.toLowerCase()) {
            case "string":
                return objectMapper.valueToTree(value.isTextual() ? value.textValue() : value.asText());
            case "number":
                if (value.isNumber()) return value;
                try {
                    return objectMapper.valueToTree(Double.parseDouble(value.asText()));
                } catch (NumberFormatException e) {
                    return objectMapper.nullNode();
                }
            case "boolean":
                return objectMapper.valueToTree(value.asBoolean());
            default:
                if (type.startsWith("array<")) {
                    String inner = type.substring("array<".length(), type.length() - 1);
                    ArrayNode arr = objectMapper.createArrayNode();
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
     * Writes {@code value} into {@code root} at a dotted path, creating
     * intermediate objects as needed. Array indices ({@code "tags[0]"}) are
     * supported in a minimal form.
     */
    private void writePointer(ObjectNode root, String dottedPath, JsonNode value) {
        if (dottedPath == null || dottedPath.isEmpty()) {
            // empty pointer means "merge into root if value is an object"
            if (value.isObject()) {
                value.fieldNames().forEachRemaining(f -> root.set(f, value.get(f)));
            }
            return;
        }
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
                    ArrayNode arr = cur.has(name) && cur.get(name).isArray()
                            ? (ArrayNode) cur.get(name)
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

    public record TransformResult(JsonNode output, List<MappingTrace> traces) {}

    public record MappingTrace(String from, String to,
                               boolean ok, boolean failed, String message) {}
}
