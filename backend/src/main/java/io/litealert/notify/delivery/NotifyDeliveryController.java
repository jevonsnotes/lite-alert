package io.litealert.notify.delivery;

import io.litealert.auth.CurrentUser;
import io.litealert.auth.domain.User;
import io.litealert.auth.permission.PermissionService;
import io.litealert.auth.permission.Permissions;
import io.litealert.common.error.BusinessException;
import io.litealert.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/deliveries")
@RequiredArgsConstructor
public class NotifyDeliveryController {

    private final NotifyDeliveryStore store;
    private final CurrentUser currentUser;
    private final PermissionService permissionService;
    private final PayloadMasker payloadMasker;

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "50") int size) {
        int limit = Math.max(1, Math.min(size, 500));
        User user = currentUser.getOrThrow();
        permissionService.require("DELIVERY_VIEW");
        return store.findRecent(limit).stream().map(d -> view(d, user, false)).toList();
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable String id) {
        User user = currentUser.getOrThrow();
        permissionService.require("DELIVERY_VIEW");
        NotifyDelivery d = store.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "delivery not found"));
        return view(d, user, true);
    }

    private Map<String, Object> view(NotifyDelivery d, User user, boolean includePayload) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("traceId", d.getTraceId());
        m.put("topicId", d.getTopicId());
        m.put("targetId", d.getTargetId());
        m.put("channel", d.getChannel() == null ? null : d.getChannel().name());
        m.put("status", d.getStatus() == null ? null : d.getStatus().name());
        m.put("attempt", d.getAttempt());
        m.put("nextRetryAt", d.getNextRetryAt() == null ? null : d.getNextRetryAt().toString());
        m.put("lastError", d.getLastError());
        m.put("createdAt", d.getCreatedAt() == null ? null : d.getCreatedAt().toString());
        m.put("finishedAt", d.getFinishedAt() == null ? null : d.getFinishedAt().toString());
        if (includePayload) m.put("payload", payloadMasker.view(d.getPayloadJson(), permissionService.has(Permissions.DELIVERY_PAYLOAD_READ)));
        return m;
    }
}
