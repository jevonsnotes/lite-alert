package io.litealert.topic.web;

import io.litealert.admin.settings.SystemSettingsService;
import io.litealert.auth.permission.PermissionService;
import io.litealert.auth.permission.Permissions;
import io.litealert.topic.TopicService;
import io.litealert.topic.domain.Topic;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/topics")
@RequiredArgsConstructor
public class TopicController {

    private final TopicService service;
    private final PermissionService permissionService;
    private final SystemSettingsService settingsService;

    @GetMapping("/settings")
    public Map<String, Object> settings() {
        permissionService.require(Permissions.TOPIC_VIEW);
        var rateLimit = settingsService.current().getRateLimit();
        return Map.of("perTopicPerMinute", rateLimit.getPerTopicPerMinute());
    }

    @GetMapping
    public List<Topic> list(@RequestParam(required = false) String namespaceId) {
        permissionService.require(Permissions.TOPIC_VIEW);
        if (namespaceId == null) return service.listMine();
        return service.listByNamespace(namespaceId);
    }

    @GetMapping("/{id}")
    public Topic get(@PathVariable String id) {
        return service.getOrThrow(id);
    }

    @PostMapping
    public Topic create(@RequestParam String namespaceId,
                        @RequestBody TopicService.CreateRequest req) {
        permissionService.require(Permissions.TOPIC_CREATE);
        return service.create(namespaceId, req);
    }

    @PatchMapping("/{id}")
    public Topic update(@PathVariable String id,
                        @RequestBody TopicService.UpdateRequest req) {
        permissionService.require(Permissions.TOPIC_UPDATE);
        return service.update(id, req);
    }

    @PostMapping("/{id}/copy")
    public Topic copy(@PathVariable String id,
                      @RequestBody CopyRequest req) {
        permissionService.require(Permissions.TOPIC_CREATE);
        return service.copy(id, req.name(), req.description(), req.copyAsDraft());
    }

    @PostMapping("/{id}/publish")
    public Topic publish(@PathVariable String id) {
        permissionService.require(Permissions.TOPIC_PUBLISH);
        return service.publish(id);
    }

    @PostMapping("/{id}/disable")
    public Topic disable(@PathVariable String id) {
        permissionService.require(Permissions.TOPIC_DISABLE);
        return service.disable(id);
    }

    @PostMapping("/{id}/enable")
    public Topic enable(@PathVariable String id) {
        permissionService.require(Permissions.TOPIC_UPDATE);
        return service.enable(id);
    }

    @DeleteMapping("/{id}")
    public Map<String, String> delete(@PathVariable String id) {
        permissionService.require(Permissions.TOPIC_DELETE);
        service.delete(id);
        return Map.of("status", "deleted");
    }

    public record CopyRequest(@NotBlank String name, @Size(max = 200) String description, boolean copyAsDraft) {}
}
