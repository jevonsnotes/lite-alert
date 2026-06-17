package io.litealert.common.template;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared template variable and function resolver used by both
 * {@link io.litealert.notify.template.TemplateRenderer} (email/chat)
 * and {@link io.litealert.transform.WebhookTemplateEngine} (webhook).
 *
 * <h3>Supported variables</h3>
 * <pre>
 * {{namespace}}    {{topic}}       {{traceId}}
 * {{receivedAt}}   {{rawJson}}     {{uuid}}     {{timestamp}}
 * {{date}}         {{dateFull}}    {{dateUtc}}
 * </pre>
 *
 * <h3>Supported functions</h3>
 * <pre>
 * {{#md5}}$.orderId + $.userId{{/md5}}     MD5 of concatenated values
 * {{#sha256}}$.orderId + $.ts{{/sha256}}   SHA-256 of concatenated values
 * {{#upper}}$.name{{/upper}}               Upper-case
 * {{#lower}}$.name{{/lower}}               Lower-case
 * {{#trim}}$.name{{/trim}}                 Trim whitespace
 * {{#substr}}$.name|0|5{{/substr}}         Substring (startIndex|length)
 * {{#base64}}$.name{{/base64}}             Base64 encode
 * </pre>
 *
 * <p>Function bodies are simple expressions: JSONPath expressions (starting
 * with {@code $}) resolve against the payload; plain text is used literally;
 * {@code +} concatenates values.
 */
public final class TemplateFunctions {

    private TemplateFunctions() {}

    // ---- Dynamic variables ----

    private static final ThreadLocal<SimpleDateFormat> DATE_FMT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd"));
    private static final ThreadLocal<SimpleDateFormat> DATE_FULL_FMT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"));

    public static String resolveDynamicVar(String name) {
        String key = name.toLowerCase();
        switch (key) {
            case "uuid":        return UUID.randomUUID().toString().replace("-", "");
            case "timestamp":
            case "now":         return String.valueOf(System.currentTimeMillis());
            case "date":        return DATE_FMT.get().format(new Date());
            case "datefull":
            case "datetime":    return DATE_FULL_FMT.get().format(new Date());
            case "dateutc":     return new Date().toInstant().toString();
            default:            return null;
        }
    }

    // ---- Function rendering ----

    /** New syntax: {{@func(arg1, arg2, ...)}} */
    private static final Pattern FUNC_CALL_AT =
            Pattern.compile("\\{\\{\\s*@([a-zA-Z]\\w*)\\s*\\(([^)]*)\\)\\s*\\}\\}");

    /** Legacy syntax: {{#func}}body{{/func}} */
    private static final Pattern FUNC_CALL_SECTION =
            Pattern.compile("\\{\\{\\s*#(md5|sha256|upper|lower|trim|substr|base64)\\s*\\}\\}(.*?)\\{\\{\\s*/\\1\\s*\\}\\}",
                    Pattern.DOTALL);

    /**
     * Processes function calls in the template.
     * Tries new {{@func(...)}} first, then falls back to {{#func}}...{{/func}}.
     */
    public static String applyFunctions(String template,
                                         java.util.function.Function<String, String> resolvePath) {
        String result = applyAtFunctions(template, resolvePath);
        result = applySectionFunctions(result, resolvePath);
        return result;
    }

    private static String applyAtFunctions(String template,
                                            java.util.function.Function<String, String> resolvePath) {
        Matcher m = FUNC_CALL_AT.matcher(template);
        if (!m.find()) return template;
        m.reset();
        StringBuilder sb = new StringBuilder();
        int pos = 0;
        while (m.find()) {
            sb.append(template, pos, m.start());
            String func = m.group(1);
            String args = m.group(2);
            sb.append(applyFunction(func, args, resolvePath));
            pos = m.end();
        }
        sb.append(template.substring(pos));
        return sb.toString();
    }

    private static String applySectionFunctions(String template,
                                                 java.util.function.Function<String, String> resolvePath) {
        Matcher m = FUNC_CALL_SECTION.matcher(template);
        if (!m.find()) return template;
        m.reset();
        StringBuilder sb = new StringBuilder();
        int pos = 0;
        while (m.find()) {
            sb.append(template, pos, m.start());
            String func = m.group(1);
            String body = m.group(2);
            sb.append(applyFunction(func, body, resolvePath));
            pos = m.end();
        }
        sb.append(template.substring(pos));
        return sb.toString();
    }

    private static String applyFunction(String func, String body,
                                         java.util.function.Function<String, String> resolvePath) {
        // Evaluate the body expression: concatenate all parts.
        String raw = evaluateExpr(body, resolvePath);
        if (raw == null) raw = "";
        switch (func.toLowerCase()) {
            case "md5":     return hexDigest("MD5", raw);
            case "sha256":  return hexDigest("SHA-256", raw);
            case "upper":   return raw.toUpperCase();
            case "lower":   return raw.toLowerCase();
            case "trim":    return raw.trim();
            case "substr": {
                // {{#substr}}$.name|0|5{{/substr}}
                String[] parts = body.split("\\|");
                String val = parts.length > 0 ? evaluateExpr(parts[0], resolvePath) : "";
                if (val == null) val = "";
                int start = parts.length > 1 ? parseInt(parts[1]) : 0;
                int len = parts.length > 2 ? parseInt(parts[2]) : val.length();
                if (start < 0) start = 0;
                if (start > val.length()) return "";
                return val.substring(start, Math.min(start + len, val.length()));
            }
            case "base64":
                return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
            default:
                return raw;
        }
    }

    /**
     * Evaluates a simple expression: JSONPath parts (starting with $) are
     * resolved via {@code resolvePath}; other text is used literally; {@code +}
     * is a concatenation separator.
     */
    private static String evaluateExpr(String expr,
                                        java.util.function.Function<String, String> resolvePath) {
        StringBuilder sb = new StringBuilder();
        // Split on '+' but only when not inside a JSONPath.
        int i = 0;
        while (i < expr.length()) {
            if (expr.charAt(i) == '$') {
                // Read until whitespace or + or end
                int start = i;
                while (i < expr.length() && !Character.isWhitespace(expr.charAt(i))
                        && expr.charAt(i) != '+') {
                    i++;
                }
                String path = expr.substring(start, i);
                sb.append(resolvePath.apply(path));
            } else if (expr.charAt(i) == '+') {
                i++; // skip the +
            } else if (Character.isWhitespace(expr.charAt(i))) {
                // Check if next non-whitespace is $ → treat as concatenation
                int j = i;
                while (j < expr.length() && Character.isWhitespace(expr.charAt(j))) j++;
                if (j < expr.length() && expr.charAt(j) == '$') {
                    i = j; // will be handled by $ branch
                } else {
                    i++;
                }
            } else {
                // Literal text — read until $ or + or end
                int start = i;
                while (i < expr.length() && expr.charAt(i) != '$' && expr.charAt(i) != '+') {
                    i++;
                }
                sb.append(expr.substring(start, i));
            }
        }
        return sb.toString();
    }

    private static String hexDigest(String algo, String input) {
        try {
            MessageDigest md = MessageDigest.getInstance(algo);
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                String h = Integer.toHexString(b & 0xFF);
                if (h.length() == 1) hex.append('0');
                hex.append(h);
            }
            return hex.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    // ---- Variable list for the UI ----

    public static List<Map<String, String>> availableVariables() {
        List<Map<String, String>> vars = new ArrayList<>();
        // System variables
        vars.add(Map.of("group", "系统变量", "name", "namespace",  "desc", "命名空间名称"));
        vars.add(Map.of("group", "系统变量", "name", "topic",      "desc", "Topic 名称"));
        vars.add(Map.of("group", "系统变量", "name", "traceId",    "desc", "本次调用追踪 ID"));
        vars.add(Map.of("group", "系统变量", "name", "receivedAt", "desc", "受理时间（ISO-8601）"));
        vars.add(Map.of("group", "系统变量", "name", "rawJson",    "desc", "入站报文 JSON 字符串"));
        // Dynamic variables
        vars.add(Map.of("group", "动态变量", "name", "uuid",       "desc", "每次请求生成新 UUID（无连字符）"));
        vars.add(Map.of("group", "动态变量", "name", "timestamp",  "desc", "当前毫秒时间戳"));
        vars.add(Map.of("group", "动态变量", "name", "date",       "desc", "当前日期 yyyy-MM-dd"));
        vars.add(Map.of("group", "动态变量", "name", "dateFull",   "desc", "当前日期时间 yyyy-MM-dd HH:mm:ss"));
        vars.add(Map.of("group", "动态变量", "name", "dateUtc",    "desc", "当前 UTC 时间 ISO-8601"));
        // Payload access
        vars.add(Map.of("group", "入站报文", "name", "$.path",     "desc", "内联 JSONPath，如 $.user.name、$.items[0].sku"));
        // Functions
        vars.add(Map.of("group", "函数", "name", "@md5",       "desc", "MD5 哈希，如 {{@md5($.id + $.ts)}}"));
        vars.add(Map.of("group", "函数", "name", "@sha256",    "desc", "SHA-256 哈希"));
        vars.add(Map.of("group", "函数", "name", "@upper",     "desc", "转大写，如 {{@upper($.name)}}"));
        vars.add(Map.of("group", "函数", "name", "@lower",     "desc", "转小写"));
        vars.add(Map.of("group", "函数", "name", "@trim",      "desc", "去除首尾空格"));
        vars.add(Map.of("group", "函数", "name", "@substr",    "desc", "截取子串，如 {{@substr($.name|0|5)}}"));
        vars.add(Map.of("group", "函数", "name", "@base64",    "desc", "Base64 编码"));
        return vars;
    }
}
