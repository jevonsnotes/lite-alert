package io.litealert.scheduler;

import io.litealert.auth.CurrentUser;
import io.litealert.auth.permission.PermissionService;
import io.litealert.auth.permission.Permissions;
import io.litealert.common.audit.AuditLogger;
import io.litealert.common.error.BusinessException;
import io.litealert.common.error.ErrorCode;
import io.litealert.common.util.IdGenerator;
import io.litealert.scheduler.domain.SchedulerNotifyConfig;
import io.litealert.scheduler.domain.SchedulerNotifyConfigStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Lifecycle for owner-private notify configs (specs {@code scheduler-notify-config}).
 */
@Service
@RequiredArgsConstructor
public class SchedulerNotifyConfigService {

    private final SchedulerNotifyConfigStore store;
    private final CurrentUser currentUser;
    private final PermissionService permissionService;
    private final AuditLogger audit;

    public List<SchedulerNotifyConfig> listMine() {
        permissionService.require(Permissions.SCHEDULER_NOTIFY_VIEW);
        return store.findByOwner(currentUser.idOrThrow());
    }

    public SchedulerNotifyConfig getOrThrow(String id) {
        SchedulerNotifyConfig c = store.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "notify config not found"));
        if (!c.getOwnerId().equals(currentUser.idOrThrow())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return c;
    }

    public SchedulerNotifyConfig create(CreateRequest req) {
        permissionService.require(Permissions.SCHEDULER_NOTIFY_MANAGE);
        validate(req);
        SchedulerNotifyConfig c = SchedulerNotifyConfig.builder()
                .id(IdGenerator.entityId("sn"))
                .ownerId(currentUser.idOrThrow())
                .name(req.name())
                .method(req.method())
                .url(req.url())
                .headers(req.headers())
                .bodyTemplate(req.bodyTemplate())
                .triggerOn(req.triggerOn() == null ? SchedulerNotifyConfig.TriggerOn.FAIL : req.triggerOn())
                .enabled(true)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        store.save(c);
        audit.log("scheduler.notify.create", Map.of("actor", c.getOwnerId(), "notifyId", c.getId()));
        return c;
    }

    public SchedulerNotifyConfig update(String id, UpdateRequest req) {
        permissionService.require(Permissions.SCHEDULER_NOTIFY_MANAGE);
        SchedulerNotifyConfig c = getOrThrow(id);
        if (req.name() != null) c.setName(req.name());
        if (req.method() != null) c.setMethod(req.method());
        // skip url update when the submitted value is the masked form (e.g. "...?***") to avoid
        // clobbering the real URL when the user edited other fields without revealing the plaintext.
        if (req.url() != null && !isMaskedUrl(req.url())) c.setUrl(req.url());
        if (req.headers() != null) c.setHeaders(req.headers());
        if (req.bodyTemplate() != null) c.setBodyTemplate(req.bodyTemplate());
        if (req.triggerOn() != null) c.setTriggerOn(req.triggerOn());
        if (req.enabled() != null) c.setEnabled(req.enabled());
        validate(toCreate(c));
        c.setUpdatedAt(Instant.now());
        store.save(c);
        audit.log("scheduler.notify.update", Map.of("actor", currentUser.idOrThrow(), "notifyId", id));
        return c;
    }

    public void delete(String id) {
        permissionService.require(Permissions.SCHEDULER_NOTIFY_MANAGE);
        SchedulerNotifyConfig c = getOrThrow(id);
        store.delete(id);
        audit.log("scheduler.notify.delete", Map.of("actor", currentUser.idOrThrow(), "notifyId", id));
    }

    /** Enable/disable a notify config. Disabled configs are skipped at dispatch but keep task bindings. */
    public SchedulerNotifyConfig setEnabled(String id, boolean enabled) {
        permissionService.require(Permissions.SCHEDULER_NOTIFY_MANAGE);
        SchedulerNotifyConfig c = getOrThrow(id);
        c.setEnabled(enabled);
        c.setUpdatedAt(Instant.now());
        store.save(c);
        audit.log("scheduler.notify." + (enabled ? "enable" : "disable"),
                Map.of("actor", currentUser.idOrThrow(), "notifyId", id));
        return c;
    }

    private void validate(CreateRequest req) {
        if (req.name() == null || req.name().isBlank())
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "name is required");
        if (req.url() == null || req.url().isBlank())
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "url is required");
        if (req.method() == null || req.method().isBlank())
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "method is required");
    }

    /** A URL is "masked" (the masked display form) if its query segment is the mask sentinel. */
    private boolean isMaskedUrl(String url) {
        return url != null && url.endsWith("?***");
    }

    private CreateRequest toCreate(SchedulerNotifyConfig c) {
        return new CreateRequest(c.getName(), c.getMethod(), c.getUrl(), c.getHeaders(), c.getBodyTemplate(), c.getTriggerOn());
    }

    public record CreateRequest(String name, String method, String url,
                                List<SchedulerNotifyConfig.Header> headers,
                                String bodyTemplate, SchedulerNotifyConfig.TriggerOn triggerOn) {}

    public record UpdateRequest(String name, String method, String url,
                                List<SchedulerNotifyConfig.Header> headers,
                                String bodyTemplate, SchedulerNotifyConfig.TriggerOn triggerOn,
                                Boolean enabled) {}
}
