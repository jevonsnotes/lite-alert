package io.litealert.namespace.web;

import io.litealert.namespace.NamespaceService;
import io.litealert.namespace.domain.Namespace;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/namespaces")
@RequiredArgsConstructor
public class NamespaceController {

    private final NamespaceService service;

    @GetMapping
    public List<Namespace> list() {
        return service.listVisible();
    }

    @GetMapping("/{id}")
    public Namespace get(@PathVariable String id) {
        return service.getOrThrow(id);
    }

    @PostMapping
    public Namespace create(@RequestBody CreateRequest req) {
        return service.create(req.name(), req.description());
    }

    @PatchMapping("/{id}")
    public Namespace update(@PathVariable String id, @RequestBody UpdateRequest req) {
        return service.updateDescription(id, req.description());
    }

    @DeleteMapping("/{id}")
    public Map<String, String> delete(@PathVariable String id) {
        service.delete(id);
        return Map.of("status", "deleted");
    }

    public record CreateRequest(@NotBlank String name, @Size(max = 200) String description) {}
    public record UpdateRequest(@Size(max = 200) String description) {}
}
