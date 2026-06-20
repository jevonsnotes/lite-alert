package io.litealert.notify.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.litealert.admin.settings.SystemSettings;
import io.litealert.admin.settings.SystemSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class PayloadMasker {

    private final ObjectMapper objectMapper;
    private final SystemSettingsService settingsService;

    public String view(String payload, boolean canReadFull) {
        if (canReadFull) return payload;
        try {
            JsonNode node = objectMapper.readTree(payload);
            Set<String> sensitive = getSensitiveWords();
            JsonNode masked = mask(node.deepCopy(), sensitive);
            return objectMapper.writeValueAsString(masked);
        } catch (Exception e) {
            return "***masked***";
        }
    }

    private Set<String> getSensitiveWords() {
        return settingsService.current().getPayloadMaskingSensitiveWords().stream()
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    private JsonNode mask(JsonNode node, Set<String> sensitive) {
        if (node instanceof ObjectNode obj) {
            obj.fieldNames().forEachRemaining(name -> {
                if (sensitive.contains(name.toLowerCase(Locale.ROOT))) {
                    obj.put(name, "***");
                } else {
                    mask(obj.get(name), sensitive);
                }
            });
        } else if (node != null && node.isArray()) {
            node.forEach(n -> mask(n, sensitive));
        }
        return node;
    }
}
