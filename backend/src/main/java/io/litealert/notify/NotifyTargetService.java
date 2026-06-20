package io.litealert.notify;

import io.litealert.auth.CurrentUser;
import io.litealert.auth.permission.PermissionService;
import io.litealert.auth.permission.Permissions;
import io.litealert.common.audit.AuditLogger;
import io.litealert.common.error.BusinessException;
import io.litealert.common.error.ErrorCode;
import io.litealert.common.util.IdGenerator;
import io.litealert.notify.domain.NotifyTarget;
import io.litealert.notify.domain.NotifyTargetStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class NotifyTargetService {

    private static final Pattern EMAIL = Pattern.compile(
            "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private final NotifyTargetStore store;
    private final CurrentUser currentUser;
    private final AuditLogger audit;
    private final PermissionService permissionService;

    public List<NotifyTarget> listMine() {
        if (permissionService.has(Permissions.CONTACT_VIEW_ALL)) return store.findAll();
        return store.findByUser(currentUser.idOrThrow());
    }

    public NotifyTarget getOrThrow(String id) {
        NotifyTarget t = store.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "target not found"));
        if (!t.getUserId().equals(currentUser.idOrThrow()) && !permissionService.has(Permissions.CONTACT_VIEW_ALL)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return t;
    }

    public NotifyTarget create(NotifyTarget.Type type, String label, String endpoint, String secret) {
        validateEndpoint(type, endpoint);
        String userId = currentUser.idOrThrow();
        ensureEndpointUnique(userId, null, type, endpoint);

        NotifyTarget created = NotifyTarget.builder()
                .id(IdGenerator.contactId())
                .userId(userId)
                .type(type)
                .label(label)
                .endpoint(endpoint)
                .secret(secret)
                .enabled(true)
                .createdAt(Instant.now())
                .build();
        store.save(created);
        audit.log("target.create", Map.of(
                "actor", userId,
                "targetId", created.getId(),
                "type", type.name()));
        return created;
    }

    public NotifyTarget update(String id, String label, Boolean enabled, String endpoint, String secret) {
        NotifyTarget t = getOrThrow(id);
        if (label != null) t.setLabel(label);
        if (enabled != null) t.setEnabled(enabled);
        if (endpoint != null && !endpoint.isBlank()) {
            validateEndpoint(t.getType(), endpoint);
            ensureEndpointUnique(t.getUserId(), t.getId(), t.getType(), endpoint);
            t.setEndpoint(endpoint);
        }
        if (secret != null) {
            t.setSecret(secret.isBlank() ? null : secret);
        }
        store.save(t);
        audit.log("target.update", Map.of(
                "actor", t.getUserId(),
                "targetId", id,
                "type", t.getType().name()));
        return t;
    }

    public void delete(String id) {
        NotifyTarget t = getOrThrow(id);
        store.delete(id);
        audit.log("target.delete", Map.of("actor", t.getUserId(), "targetId", id));
    }

    private void ensureEndpointUnique(String userId, String currentId, NotifyTarget.Type type, String endpoint) {
        for (NotifyTarget t : store.findByUser(userId)) {
            if (currentId != null && currentId.equals(t.getId())) continue;
            if (t.getType() == type && endpoint.equalsIgnoreCase(t.getEndpoint())) {
                throw new BusinessException(ErrorCode.CONFLICT,
                        "target with the same endpoint already exists");
            }
        }
    }

    private void validateEndpoint(NotifyTarget.Type type, String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "endpoint required");
        }
        switch (type) {
            case EMAIL -> {
                if (!EMAIL.matcher(endpoint).matches()) {
                    throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "invalid email address");
                }
            }
            case DINGTALK, FEISHU, WECOM, WEBHOOK -> {
                if (!endpoint.startsWith("https://") && !endpoint.startsWith("http://")) {
                    throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "endpoint must be an http(s) URL");
                }
            }
        }
    }
}
