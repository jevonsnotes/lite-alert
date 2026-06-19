package io.litealert.apikey.web;

import io.litealert.apikey.ApiKeyService;
import io.litealert.apikey.domain.ApiKey;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/apikeys")
@RequiredArgsConstructor
public class ApiKeyController {

    private final ApiKeyService service;

    @GetMapping
    public List<Map<String, Object>> list() {
        return service.listMine().stream().map(this::toView).toList();
    }

    @GetMapping("/covering")
    public List<Map<String, Object>> covering(@RequestParam String topicId) {
        return service.findCovering(topicId).stream().map(this::toView).toList();
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody ApiKeyService.CreateRequest req) {
        ApiKeyService.CreateResult r = service.create(req);
        Map<String, Object> view = toView(r.apiKey());
        // unique field — caller MUST persist the plaintext now; never returned again
        view = new java.util.LinkedHashMap<>(view);
        view.put("fullKey", r.fullKey());
        return view;
    }

    @PatchMapping("/{id}")
    public Map<String, Object> update(@PathVariable String id,
                                      @RequestBody ApiKeyService.UpdateRequest req) {
        return toView(service.update(id, req));
    }

    @PostMapping("/{id}/revoke")
    public Map<String, Object> revoke(@PathVariable String id) {
        return toView(service.revoke(id));
    }

    @PostMapping("/{id}/rotate")
    public Map<String, Object> rotate(@PathVariable String id) {
        ApiKeyService.CreateResult r = service.rotate(id);
        Map<String, Object> view = new java.util.LinkedHashMap<>(toView(r.apiKey()));
        view.put("fullKey", r.fullKey());
        return view;
    }

    @DeleteMapping("/{id}")
    public Map<String, String> delete(@PathVariable String id) {
        service.delete(id);
        return Map.of("status", "deleted");
    }

    private Map<String, Object> toView(ApiKey k) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", k.getId());
        m.put("name", k.getName());
        m.put("prefix", k.getPrefix());
        m.put("scopes", k.getScopes());
        m.put("status", k.getStatus().name());
        m.put("validFrom", k.getValidFrom() == null ? null : k.getValidFrom().toString());
        m.put("validUntil", k.getValidUntil() == null ? null : k.getValidUntil().toString());
        m.put("createdAt", k.getCreatedAt() == null ? null : k.getCreatedAt().toString());
        m.put("lastUsedAt", k.getLastUsedAt() == null ? null : k.getLastUsedAt().toString());
        m.put("usageCount", k.getUsageCount());
        m.put("rotateCount", k.getRotateCount());
        m.put("rateLimitPerMinute", k.getRateLimitPerMinute());
        return m;
    }
}
