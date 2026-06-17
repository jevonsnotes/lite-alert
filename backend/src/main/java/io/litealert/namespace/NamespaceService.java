package io.litealert.namespace;

import io.litealert.auth.CurrentUser;
import io.litealert.common.audit.AuditLogger;
import io.litealert.common.error.BusinessException;
import io.litealert.common.error.ErrorCode;
import io.litealert.common.util.IdGenerator;
import io.litealert.namespace.domain.Namespace;
import io.litealert.namespace.domain.NamespaceStore;
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
    private final CurrentUser currentUser;
    private final AuditLogger audit;

    public List<Namespace> listVisible() {
        if (currentUser.isAdmin()) return store.findAll();
        return store.findByOwner(currentUser.idOrThrow());
    }

    public Namespace getOrThrow(String id) {
        Namespace n = store.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "namespace not found"));
        if (!currentUser.isAdmin() && !n.getOwnerId().equals(currentUser.idOrThrow())) {
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
        Namespace n = getOrThrow(id);
        n.setDescription(description);
        n.setUpdatedAt(Instant.now());
        store.save(n);
        return n;
    }

    public void delete(String id) {
        Namespace n = getOrThrow(id);
        boolean hasPublished = topicStore.findByNamespace(id).stream()
                .anyMatch(t -> t.getStatus() == Topic.Status.PUBLISHED);
        if (hasPublished) {
            throw new BusinessException(ErrorCode.NAMESPACE_NOT_EMPTY);
        }
        // also drop draft/disabled topics under this namespace
        topicStore.deleteByNamespace(id);
        store.delete(id);
        audit.log("namespace.delete", Map.of(
                "actor", currentUser.idOrThrow(),
                "namespaceId", id));
    }
}
