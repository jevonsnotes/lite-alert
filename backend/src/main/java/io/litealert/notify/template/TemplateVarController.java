package io.litealert.notify.template;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Exposes available template variables for the UI.
 */
@RestController
@RequestMapping("/api/template-vars")
@RequiredArgsConstructor
public class TemplateVarController {

    private final TemplateRenderer renderer;

    @GetMapping
    public List<Map<String, String>> list() {
        return renderer.availableVariables();
    }
}
