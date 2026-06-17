package io.litealert.topic.web;

import io.litealert.topic.TopicService;
import io.litealert.topic.domain.Topic;
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

    @GetMapping
    public List<Topic> list(@RequestParam(required = false) String namespaceId) {
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
        return service.create(namespaceId, req);
    }

    @PatchMapping("/{id}")
    public Topic update(@PathVariable String id,
                        @RequestBody TopicService.UpdateRequest req) {
        return service.update(id, req);
    }

    @PostMapping("/{id}/publish")
    public Topic publish(@PathVariable String id) {
        return service.publish(id);
    }

    @PostMapping("/{id}/disable")
    public Topic disable(@PathVariable String id) {
        return service.disable(id);
    }

    @PostMapping("/{id}/enable")
    public Topic enable(@PathVariable String id) {
        return service.enable(id);
    }

    @DeleteMapping("/{id}")
    public Map<String, String> delete(@PathVariable String id) {
        service.delete(id);
        return Map.of("status", "deleted");
    }
}
