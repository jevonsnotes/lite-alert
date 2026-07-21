package io.litealert.common.template;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateFunctionsTest {

    private String renderFunctions(String template, java.util.function.Function<String, String> resolvePath) {
        return TemplateFunctions.applyFunctions(template, resolvePath);
    }

    @Test
    void jsonEscapesDoubleQuotesAndBackslashes() {
        String result = renderFunctions("{{@json($.response)}}", path -> "<html attr=\"x\" k=\"v\">");
        assertThat(result).isEqualTo("<html attr=\\\"x\\\" k=\\\"v\\\">");
    }

    @Test
    void jsonEscapesNewlinesAndControlChars() {
        String result = renderFunctions("{{@json($.msg)}}", path -> "line1\nline2\tend");
        assertThat(result).isEqualTo("line1\\nline2\\tend");
    }

    @Test
    void jsonMakesValueSafeToEmbedInJsonString() {
        String result = renderFunctions("{\"text\":\"{{@json($.response)}}\"}", path -> "a\"b");
        assertThat(result).isEqualTo("{\"text\":\"a\\\"b\"}");
    }

    @Test
    void jsonEscapeSectionSyntaxAlsoWorks() {
        String result = renderFunctions("{{#json}}$.response{{/json}}", path -> "a\"b");
        assertThat(result).isEqualTo("a\\\"b");
    }
}
