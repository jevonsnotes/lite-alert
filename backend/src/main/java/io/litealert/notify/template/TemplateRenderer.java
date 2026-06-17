package io.litealert.notify.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import io.litealert.common.template.TemplateFunctions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders a Mustache template with inline JSONPath and function support:
 *
 * <h3>Inline JSONPath: {@code {{$.path.to.field}}}</h3>
 * Any variable name starting with {@code $} is treated as a JSONPath
 * expression. This is the preferred, most readable form:
 * <pre>
 *   订单 {{$.orderId}} 已支付 (买家：{{$.user.name}})
 * </pre>
 *
 * <h3>Functions: {@code {{#md5}}$.id + $.ts{{/md5}}}</h3>
 * <ul>
 *   <li>{{#md5}}…{{/md5}} — MD5 hash</li>
 *   <li>{{#sha256}}…{{/sha256}} — SHA-256 hash</li>
 *   <li>{{#upper}}…{{/upper}} — upper-case</li>
 *   <li>{{#lower}}…{{/lower}} — lower-case</li>
 *   <li>{{#trim}}…{{/trim}} — trim whitespace</li>
 *   <li>{{#substr}}…{{/substr}} — substring (startIndex|length)</li>
 *   <li>{{#base64}}…{{/base64}} — Base64 encode</li>
 * </ul>
 *
 * <p>Top-level fields of the payload are also exposed as plain
 * {@code {{name}}} variables for the simple case where users don't need
 * JSONPath at all. System variables (namespace / topic / traceId / etc.)
 * are merged into the same scope.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TemplateRenderer {

    /** Matches {{  $.something  }} — whitespace-tolerant. */
    private static final Pattern INLINE_JP = Pattern.compile("\\{\\{\\s*(\\$[\\w.*\\[\\]()\\[\\]\"'@,\\s:!?/+=|~^<>-]+)\\s*\\}\\}");

    private final ObjectMapper objectMapper;

    private final MustacheFactory mustache = new DefaultMustacheFactory();

    private final Configuration jsonPathConfig = Configuration.builder()
            .jsonProvider(new JacksonJsonNodeJsonProvider())
            .mappingProvider(new JacksonMappingProvider())
            .options(Option.SUPPRESS_EXCEPTIONS, Option.DEFAULT_PATH_LEAF_TO_NULL)
            .build();

    /** Renders {@code template} against {@code payload} and {@code system}. */
    public String render(String template, JsonNode payload, Map<String, Object> system) {
        if (template == null || template.isEmpty()) return "";
        try {
            // Phase 1: inline {{$.path}} → resolve JSONPath, replace with value.
            String resolved = resolveInlineJsonPath(template, payload);
            // Phase 2: apply functions ({{#md5}}…{{/md5}}, {{#upper}}…{{/upper}}, etc.).
            resolved = TemplateFunctions.applyFunctions(resolved,
                    expr -> expr.startsWith("$") ? evaluateJsonPath(expr, payload) : expr);
            // Phase 3: compile and render Mustache (handles {{name}}, etc.).
            Mustache m = mustache.compile(new StringReader(resolved), "tmpl");
            StringWriter sw = new StringWriter();
            m.execute(sw, scope(payload, system)).flush();
            return sw.toString();
        } catch (Exception e) {
            log.warn("template render failed: {}", e.getMessage());
            return template;
        }
    }

    /** Replace all {{  $.something  }} occurrences with their JSONPath values. */
    private String resolveInlineJsonPath(String template, JsonNode payload) {
        Matcher m = INLINE_JP.matcher(template);
        if (!m.find()) return template;
        m.reset();
        StringBuilder sb = new StringBuilder();
        int pos = 0;
        while (m.find()) {
            sb.append(template, pos, m.start());
            String path = m.group(1);
            sb.append(evaluateJsonPath(path, payload));
            pos = m.end();
        }
        sb.append(template.substring(pos));
        return sb.toString();
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
            log.debug("inline jsonPath({}) failed: {}", path, e.getMessage());
            return "";
        }
    }

    private Map<String, Object> scope(JsonNode payload, Map<String, Object> system) {
        Map<String, Object> scope = new HashMap<>();
        if (system != null) scope.putAll(system);
        // Also promote payload top-level fields for convenience: {{title}} = {{$.title}}
        // Both forms are supported in Mustache templates; {{$.path}} is preferred for
        // clarity. Payload fields never override system variables.
        if (payload != null && payload.isObject()) {
            payload.fieldNames().forEachRemaining(field -> {
                if (scope.containsKey(field)) return; // system var wins
                JsonNode v = payload.get(field);
                scope.putIfAbsent(field, v.isValueNode() ? v.asText() : jsonToText(v));
            });
        }
        return scope;
    }

    private String jsonToText(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return String.valueOf(node);
        }
    }

    /**
     * Returns a list of available system + dynamic variables for the UI to display.
     */
    public List<Map<String, String>> availableVariables() {
        return TemplateFunctions.availableVariables();
    }
}
