package io.litealert.scheduler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.litealert.notify.channel.WebhookResponseAssertor;
import io.litealert.notify.template.TemplateRenderer;
import io.litealert.scheduler.domain.ApiTaskConfig;
import io.litealert.scheduler.domain.SchedulerNotifyConfig;
import io.litealert.scheduler.domain.SchedulerTaskCall;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a notify config's body template against the task-execution context and sends it as a
 * raw-json outbound webhook (design D3/D4). Variables: built-in execution scalars + response body
 * accessible via {@code $.response.xxx} JSONPath.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchedulerNotifier {

    private final TemplateRenderer renderer;
    private final ApiTaskHttpExecutor httpExecutor;
    private final ObjectMapper objectMapper;

    /** Render the body template for the given execution context. Public for testing. */
    public String renderBody(SchedulerNotifyConfig cfg, RenderContext ctx) {
        if (cfg.getBodyTemplate() == null || cfg.getBodyTemplate().isBlank()) return "";
        // Notify bodies are machine-readable JSON; Mustache's default HTML-escaping would mangle
        // values containing =, ", <, &. Undo the few entities it emits.
        String rendered = renderer.render(cfg.getBodyTemplate(), responsePayload(ctx.responseBody), systemVars(cfg, ctx));
        return unescapeHtml(rendered);
    }

    private String unescapeHtml(String s) {
        if (s == null) return null;
        return s.replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&#61;", "=")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    /** Build the {@link ApiTaskConfig} to send (raw-json body + headers). Public for testing. */
    public ApiTaskConfig toSendConfig(SchedulerNotifyConfig cfg, RenderContext ctx) {
        ApiTaskConfig send = new ApiTaskConfig();
        send.setMethod(cfg.getMethod());
        send.setUrl(cfg.getUrl());
        send.setHeaders(toHeaders(cfg.getHeaders()));
        ApiTaskConfig.Body body = new ApiTaskConfig.Body();
        body.setType(ApiTaskConfig.Body.Type.RAW);
        body.setRawType(ApiTaskConfig.Body.RawType.JSON);
        body.setRawText(renderBody(cfg, ctx));
        send.setBody(body);
        return send;
    }

    /** Send one notification; returns true on success. Caller handles exceptions per-config. */
    public boolean send(SchedulerNotifyConfig cfg, RenderContext ctx) {
        try {
            ApiTaskConfig send = toSendConfig(cfg, ctx);
            httpExecutor.execute(send);
            return true;
        } catch (Exception e) {
            log.warn("notify send failed config={} url={} : {}", cfg.getId(), cfg.getUrl(), e.getMessage());
            return false;
        }
    }

    /** Whether this config should fire for the given execution outcome (design D5). */
    public boolean shouldFire(SchedulerNotifyConfig cfg, boolean taskSuccess) {
        if (!cfg.isEnabled()) return false;
        SchedulerNotifyConfig.TriggerOn t = cfg.getTriggerOn() == null ? SchedulerNotifyConfig.TriggerOn.FAIL : cfg.getTriggerOn();
        return switch (t) {
            case ALWAYS -> true;
            case SUCCESS -> taskSuccess;
            case FAIL -> !taskSuccess;
        };
    }

    private List<ApiTaskConfig.Header> toHeaders(List<SchedulerNotifyConfig.Header> src) {
        if (src == null) return List.of();
        return src.stream().map(h -> new ApiTaskConfig.Header(h.getName(), h.getValue())).toList();
    }

    private Map<String, Object> systemVars(SchedulerNotifyConfig cfg, RenderContext ctx) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taskName", nullSafe(ctx.taskName));
        m.put("taskId", nullSafe(ctx.taskId));
        m.put("protocol", nullSafe(ctx.protocol));
        m.put("status", ctx.success ? "SUCCESS" : "FAIL");
        m.put("httpStatus", ctx.httpStatus == null ? "" : String.valueOf(ctx.httpStatus));
        m.put("durationMs", String.valueOf(ctx.durationMs));
        m.put("error", nullSafe(ctx.error));
        m.put("triggeredAt", ctx.triggeredAt == null ? "" : ctx.triggeredAt.toString());
        m.put("assertionPassed", ctx.assertionPassed == null ? "" : String.valueOf(ctx.assertionPassed));
        return m;
    }

    /** Wrap the response body under a `response` key so {@code $.response.xxx} JSONPath works. */
    private JsonNode responsePayload(String responseBody) {
        ObjectNode root = objectMapper.createObjectNode();
        if (responseBody == null || responseBody.isBlank()) return root;
        try {
            root.set("response", objectMapper.readTree(responseBody));
        } catch (Exception e) {
            // non-JSON response → expose as plain text
            root.put("response", responseBody);
        }
        return root;
    }

    private String nullSafe(String s) { return s == null ? "" : s; }

    /** Execution context passed from the engine to the notifier. */
    public record RenderContext(String taskId, String taskName, String protocol, boolean success,
                                Integer httpStatus, long durationMs, String error,
                                Instant triggeredAt, Boolean assertionPassed,
                                String responseBody) {}
}
