package io.litealert.admin.stats;

import io.litealert.auth.permission.PermissionService;
import io.litealert.auth.permission.Permissions;
import io.litealert.topic.domain.Topic;
import io.litealert.topic.domain.TopicStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/stats")
public class StatsControllerExt {

    private final StatsService statsService;
    private final TopicStore topicStore;
    private final PermissionService permissionService;

    public StatsControllerExt(StatsService statsService, TopicStore topicStore, PermissionService permissionService) {
        this.statsService = statsService;
        this.topicStore = topicStore;
        this.permissionService = permissionService;
    }

    @GetMapping("/topic")
    public Map<String, StatsService.TopicSummary> topicStats(@RequestParam String topicId) {
        permissionService.require(Permissions.STATS_VIEW);
        List<String> ids = List.of(topicId.split(","));
        return statsService.topicSummary(ids);
    }

    @GetMapping("/namespace")
    public Map<String, Object> namespaceStats(@RequestParam String namespaceId) {
        permissionService.require(Permissions.STATS_VIEW);
        List<Topic> topics = topicStore.findByNamespace(namespaceId);
        List<String> ids = topics.stream().map(Topic::getId).collect(Collectors.toList());
        Map<String, StatsService.TopicSummary> summaries = statsService.topicSummary(ids);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("namespaceId", namespaceId);
        long accepted = 0, sent = 0, failed = 0, pending = 0, retryWait = 0;
        for (StatsService.TopicSummary s : summaries.values()) {
            accepted += s.getAccepted();
            sent += s.getSent();
            failed += s.getFailed();
            pending += s.getPending();
            retryWait += s.getRetryWait();
        }
        result.put("topicCount", topics.size());
        result.put("accepted", accepted);
        result.put("sent", sent);
        result.put("failed", failed);
        result.put("pending", pending);
        result.put("retryWait", retryWait);
        return result;
    }
}
