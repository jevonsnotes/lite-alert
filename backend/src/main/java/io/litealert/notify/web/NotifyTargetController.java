package io.litealert.notify.web;

import io.litealert.notify.NotifyTargetService;
import io.litealert.notify.domain.NotifyTarget;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Notify target CRUD. The path stays at {@code /api/contacts} for
 * backwards compatibility with anything that linked to it earlier.
 */
@RestController
@RequestMapping("/api/contacts")
@RequiredArgsConstructor
public class NotifyTargetController {

    private final NotifyTargetService service;

    @GetMapping
    public List<Map<String, Object>> list() {
        return service.listMine().stream().map(this::view).toList();
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody CreateRequest req) {
        NotifyTarget.Type type = req.type() == null
                ? NotifyTarget.Type.EMAIL
                : NotifyTarget.Type.valueOf(req.type());
        return view(service.create(type, req.label(), req.endpoint(), req.secret()));
    }

    @PatchMapping("/{id}")
    public Map<String, Object> update(@PathVariable String id, @RequestBody UpdateRequest req) {
        return view(service.update(id, req.label(), req.enabled(), req.endpoint(), req.secret()));
    }

    @DeleteMapping("/{id}")
    public Map<String, String> delete(@PathVariable String id) {
        service.delete(id);
        return Map.of("status", "deleted");
    }

    private Map<String, Object> view(NotifyTarget t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("userId", t.getUserId());
        m.put("type", t.getType() == null ? "EMAIL" : t.getType().name());
        m.put("label", t.getLabel() == null ? "" : t.getLabel());
        // endpoint shown plain so the user can verify what they typed; the
        // caller's UI is responsible for masking long URLs / email locals.
        m.put("endpoint", t.getEndpoint());
        m.put("hasSecret", t.getSecret() != null && !t.getSecret().isBlank());
        m.put("enabled", t.isEnabled());
        m.put("createdAt", t.getCreatedAt() == null ? "" : t.getCreatedAt().toString());
        return m;
    }

    public record CreateRequest(
            String type,
            String label,
            @NotBlank String endpoint,
            String secret) {}

    public record UpdateRequest(
            String label,
            Boolean enabled,
            String endpoint,
            String secret) {}
}
