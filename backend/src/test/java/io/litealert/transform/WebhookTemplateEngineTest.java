package io.litealert.transform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookTemplateEngineTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final WebhookTemplateEngine engine = new WebhookTemplateEngine(mapper);

    @Test
    void renderXmlConvertsJsonObjectPlaceholderToXmlNodes() throws Exception {
        var payload = mapper.readTree("""
                {
                  "title": "服务 <异常>",
                  "buyer": { "name": "张三", "vip": true },
                  "items": [ { "sku": "A1", "qty": 2 }, { "sku": "B2", "qty": 1 } ]
                }
                """);

        String xml = engine.renderXml("""
                <alert>
                  <title>{{$.title}}</title>
                  {{$.buyer}}
                  <items>{{$.items}}</items>
                </alert>
                """, payload, null, Map.of("topic", "paid"));

        assertThat(xml).contains("<title>服务 &lt;异常&gt;</title>");
        assertThat(xml).contains("<buyer><name>张三</name><vip>true</vip></buyer>");
        assertThat(xml).contains("<items><item><sku>A1</sku><qty>2</qty></item><item><sku>B2</sku><qty>1</qty></item></items>");
    }

    @Test
    void renderXmlAppliesMappingsOverPlaceholders() throws Exception {
        var payload = mapper.readTree("""
                { "title": "告警", "level": "critical" }
                """);

        var mappings = List.of(
                new io.litealert.topic.domain.Topic.Transform.Mapping("$.title", "title", "string", true, null),
                new io.litealert.topic.domain.Topic.Transform.Mapping("$.level", "level", "string", true, null)
        );

        String xml = engine.renderXml("""
                <alert><title>{{title}}</title><level>{{level}}</level></alert>
                """, payload, mappings, Map.of());

        assertThat(xml.trim()).isEqualTo("<alert><title>告警</title><level>critical</level></alert>");
    }
}
