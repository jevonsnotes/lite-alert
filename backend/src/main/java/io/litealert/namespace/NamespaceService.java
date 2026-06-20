package io.litealert.namespace;

import io.litealert.auth.CurrentUser;
import io.litealert.auth.permission.PermissionService;
import io.litealert.auth.permission.Permissions;
import io.litealert.common.audit.AuditLogger;
import io.litealert.common.error.BusinessException;
import io.litealert.common.error.ErrorCode;
import io.litealert.common.util.IdGenerator;
import io.litealert.namespace.domain.Namespace;
import io.litealert.namespace.domain.NamespaceStore;
import io.litealert.notify.domain.SubscriptionStore;
import io.litealert.topic.domain.Topic;
import io.litealert.topic.domain.TopicStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class NamespaceService {

    public static final Pattern NAME_RE = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_-]{2,31}$");

    private final NamespaceStore store;
    private final TopicStore topicStore;
    private final SubscriptionStore subscriptionStore;
    private final CurrentUser currentUser;
    private final AuditLogger audit;
    private final PermissionService permissionService;

    public List<Namespace> listVisible() {
        if (permissionService.has(Permissions.NAMESPACE_VIEW_ALL)) return store.findAll();
        return store.findByOwner(currentUser.idOrThrow());
    }

    public Namespace getOrThrow(String id) {
        Namespace n = store.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "namespace not found"));
        if (!n.getOwnerId().equals(currentUser.idOrThrow()) && !permissionService.has(Permissions.NAMESPACE_VIEW_ALL)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return n;
    }

    public Namespace create(String name, String description) {
        if (!NAME_RE.matcher(name).matches()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "namespace name must match " + NAME_RE.pattern());
        }
        if (store.findByName(name).isPresent()) {
            throw new BusinessException(ErrorCode.NAMESPACE_NAME_TAKEN);
        }
        Namespace n = Namespace.builder()
                .id(IdGenerator.namespaceId())
                .name(name)
                .description(description)
                .ownerId(currentUser.idOrThrow())
                .status(Namespace.Status.ACTIVE)
                .createdAt(Instant.now())
                .build();
        store.save(n);
        audit.log("namespace.create", Map.of(
                "actor", n.getOwnerId(),
                "namespaceId", n.getId(),
                "name", name));
        return n;
    }

    public Namespace updateDescription(String id, String description) {
        return update(id, null, description);
    }

    public Namespace update(String id, String name, String description) {
        Namespace n = getOrThrow(id);
        boolean renamed = name != null && !name.equals(n.getName());
        if (renamed) {
            validateName(name);
            store.findByName(name)
                    .filter(existing -> !existing.getId().equals(id))
                    .ifPresent(existing -> { throw new BusinessException(ErrorCode.NAMESPACE_NAME_TAKEN); });
            String oldName = n.getName();
            n.setName(name);
            for (Topic t : topicStore.findByNamespace(id)) {
                t.setNamespaceName(name);
                t.setUpdatedAt(Instant.now());
                topicStore.save(t);
            }
            audit.log("namespace.rename", Map.of(
                    "actor", currentUser.idOrThrow(),
                    "namespaceId", id,
                    "oldName", oldName,
                    "name", name));
        }
        if (description != null) n.setDescription(description);
        n.setUpdatedAt(Instant.now());
        store.save(n);
        return n;
    }

    public Namespace copy(String id, String name, String description, boolean copyAsDraft) {
        Namespace source = getOrThrow(id);
        validateName(name);
        if (store.findByName(name).isPresent()) {
            throw new BusinessException(ErrorCode.NAMESPACE_NAME_TAKEN);
        }
        Namespace copied = Namespace.builder()
                .id(IdGenerator.namespaceId())
                .name(name)
                .description(description)
                .ownerId(currentUser.idOrThrow())
                .status(Namespace.Status.ACTIVE)
                .createdAt(Instant.now())
                .build();
        store.save(copied);
        for (Topic sourceTopic : topicStore.findByNamespace(source.getId())) {
            Topic copiedTopic = sourceTopic.toBuilder()
                    .id(IdGenerator.topicId())
                    .namespaceId(copied.getId())
                    .namespaceName(copied.getName())
                    .ownerId(copied.getOwnerId())
                    .status(copyAsDraft ? Topic.Status.DRAFT : sourceTopic.getStatus())
                    .createdAt(Instant.now())
                    .updatedAt(null)
                    .publishedAt(copyAsDraft ? null : sourceTopic.getPublishedAt())
                    .build();
            topicStore.save(copiedTopic);
            subscriptionStore.save(copiedTopic.getId(), subscriptionStore.getOrEmpty(sourceTopic.getId()).getContactIds());
        }
        audit.log("namespace.copy", Map.of(
                "actor", currentUser.idOrThrow(),
                "sourceNamespaceId", id,
                "namespaceId", copied.getId(),
                "name", copied.getName()));
        return copied;
    }

    private void validateName(String name) {
        if (name == null || !NAME_RE.matcher(name).matches()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "namespace name must match " + NAME_RE.pattern());
        }
    }

    public Namespace disable(String id) {
        Namespace n = getOrThrow(id);
        n.setStatus(Namespace.Status.DISABLED);
        n.setDisabledAt(Instant.now());
        n.setDisabledBy(currentUser.idOrThrow());
        n.setUpdatedAt(Instant.now());
        store.save(n);
        audit.log("namespace.disable", Map.of(
                "actor", currentUser.idOrThrow(),
                "namespaceId", id,
                "name", n.getName()));
        return n;
    }

    public Namespace enable(String id) {
        Namespace n = getOrThrow(id);
        n.setStatus(Namespace.Status.ACTIVE);
        n.setDisabledAt(null);
        n.setDisabledBy(null);
        n.setUpdatedAt(Instant.now());
        store.save(n);
        audit.log("namespace.enable", Map.of(
                "actor", currentUser.idOrThrow(),
                "namespaceId", id,
                "name", n.getName()));
        return n;
    }

    public void delete(String id) {
        Namespace n = getOrThrow(id);
        boolean hasPublished = topicStore.findByNamespace(id).stream()
                .anyMatch(t -> t.getStatus() == Topic.Status.PUBLISHED);
        if (hasPublished) {
            throw new BusinessException(ErrorCode.NAMESPACE_NOT_EMPTY);
        }
        topicStore.deleteByNamespace(id);
        store.delete(id);
        audit.log("namespace.delete", Map.of(
                "actor", currentUser.idOrThrow(),
                "namespaceId", id));
    }
}
