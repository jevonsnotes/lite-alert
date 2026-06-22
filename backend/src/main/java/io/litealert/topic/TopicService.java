package io.litealert.topic;

import com.fasterxml.jackson.databind.JsonNode;
import io.litealert.auth.CurrentUser;
import io.litealert.auth.permission.PermissionService;
import io.litealert.auth.permission.Permissions;
import io.litealert.common.audit.AuditLogger;
import io.litealert.common.error.BusinessException;
import io.litealert.common.error.ErrorCode;
import io.litealert.common.util.IdGenerator;
import io.litealert.namespace.NamespaceService;
import io.litealert.namespace.domain.Namespace;
import io.litealert.notify.domain.SubscriptionStore;
import io.litealert.topic.domain.Topic;
import io.litealert.topic.domain.TopicChannelTemplateStore;
import io.litealert.topic.domain.TopicStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class TopicService {

    public static final Pattern NAME_RE = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_-]{2,31}$");

    private final TopicStore store;
    private final NamespaceService namespaceService;
    private final SubscriptionStore subscriptionStore;
    private final TopicChannelTemplateStore templateStore;
    private final CurrentUser currentUser;
    private final AuditLogger audit;
    private final PermissionService permissionService;

    public List<Topic> listByNamespace(String namespaceId) {
        namespaceService.getOrThrow(namespaceId); // ownership check
        return store.findByNamespace(namespaceId);
    }

    public List<Topic> listMine() {
        if (permissionService.has(Permissions.TOPIC_VIEW_ALL)) return store.findAll();
        return store.findByOwner(currentUser.idOrThrow());
    }

    public Topic getOrThrow(String id) {
        Topic t = store.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "topic not found"));
        if (!t.getOwnerId().equals(currentUser.idOrThrow()) && !permissionService.has(Permissions.TOPIC_VIEW_ALL)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return t;
    }

    public Topic create(String namespaceId, CreateRequest req) {
        Namespace ns = namespaceService.getOrThrow(namespaceId);
        if (!NAME_RE.matcher(req.name()).matches()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "topic name must match " + NAME_RE.pattern());
        }
        if (store.findByNamespaceAndName(namespaceId, req.name()).isPresent()) {
            throw new BusinessException(ErrorCode.TOPIC_NAME_TAKEN);
        }
        Topic.AuthMode mode = Topic.AuthMode.API_KEY;
        Topic.Auth auth = new Topic.Auth(mode,
                req.ipWhitelist() == null ? new ArrayList<>() : req.ipWhitelist(),
                null);
        auth.setKeyLocation(parseKeyLocation(req.keyLocation()));

        Topic t = Topic.builder()
                .id(IdGenerator.topicId())
                .namespaceId(namespaceId)
                .namespaceName(ns.getName())
                .name(req.name())
                .description(req.description())
                .ownerId(ns.getOwnerId())
                .status(Topic.Status.DRAFT)
                .sync(req.sync())
                .syncTimeout(req.syncTimeout())
                .auth(auth)
                .inboundFormat(req.inboundFormat())
                .createdAt(Instant.now())
                .build();
        store.save(t);
        audit.log("topic.create", Map.of(
                "actor", t.getOwnerId(),
                "topicId", t.getId(),
                "namespace", ns.getName(),
                "name", t.getName(),
                "authMode", mode.name()));
        return t;
    }

    public Topic update(String id, UpdateRequest req) {
        Topic t = getOrThrow(id);
        boolean published = t.getStatus() == Topic.Status.PUBLISHED;

        if (req.description() != null) t.setDescription(req.description());

        if (req.name() != null && !req.name().equals(t.getName())) {
            if (t.getStatus() != Topic.Status.DRAFT) {
                throw new BusinessException(ErrorCode.CONFLICT, "published or disabled topic name cannot be changed");
            }
            validateName(req.name());
            store.findByNamespaceAndName(t.getNamespaceId(), req.name())
                    .filter(existing -> !existing.getId().equals(id))
                    .ifPresent(existing -> { throw new BusinessException(ErrorCode.TOPIC_NAME_TAKEN); });
            String oldName = t.getName();
            t.setName(req.name());
            audit.log("topic.rename", Map.of(
                    "actor", currentUser.idOrThrow(),
                    "topicId", id,
                    "oldName", oldName,
                    "name", req.name()));
        }

        // Channel templates — fully relational now.
        if (req.templates() != null) {
            t.setTemplates(new java.util.EnumMap<>(req.templates()));
        }

        if (req.inboundFormat() != null) {
            if (published) throw new BusinessException(ErrorCode.SCHEMA_LOCKED);
            t.setInboundFormat(req.inboundFormat());
        }

        if (req.sync() != null) {
            t.setSync(req.sync());
        }
        if (req.syncTimeout() != null) {
            t.setSyncTimeout(req.syncTimeout());
        }
        if (req.auth() != null) {
            Topic.Auth next = req.auth();
            next.setMode(Topic.AuthMode.API_KEY);
            if (next.getKeyLocation() == null) next.setKeyLocation(Topic.KeyLocation.HEADER);
            t.setAuth(next);
        }
        t.setUpdatedAt(Instant.now());
        store.save(t);
        audit.log("topic.update", Map.of(
                "actor", currentUser.idOrThrow(),
                "topicId", t.getId()));
        return t;
    }

    public Topic publish(String id) {
        Topic t = getOrThrow(id);
        if (t.getInboundFormat() == null) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "inboundFormat is required to publish");
        }
        t.setStatus(Topic.Status.PUBLISHED);
        t.setPublishedAt(Instant.now());
        t.setUpdatedAt(Instant.now());
        store.save(t);
        audit.log("topic.publish", Map.of(
                "actor", currentUser.idOrThrow(),
                "topicId", t.getId()));
        return t;
    }

    public Topic disable(String id) {
        Topic t = getOrThrow(id);
        t.setStatus(Topic.Status.DISABLED);
        t.setUpdatedAt(Instant.now());
        store.save(t);
        audit.log("topic.disable", Map.of(
                "actor", currentUser.idOrThrow(),
                "topicId", t.getId()));
        return t;
    }

    public Topic enable(String id) {
        Topic t = getOrThrow(id);
        if (t.getStatus() != Topic.Status.DISABLED) {
            throw new BusinessException(ErrorCode.CONFLICT, "only DISABLED topics can be enabled");
        }
        t.setStatus(Topic.Status.PUBLISHED);
        t.setUpdatedAt(Instant.now());
        store.save(t);
        audit.log("topic.enable", Map.of(
                "actor", currentUser.idOrThrow(),
                "topicId", t.getId()));
        return t;
    }

    public void delete(String id) {
        Topic t = getOrThrow(id);
        if (t.getStatus() == Topic.Status.PUBLISHED) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "disable the topic before deleting");
        }
        store.delete(id);
        audit.log("topic.delete", Map.of(
                "actor", currentUser.idOrThrow(),
                "topicId", id));
    }

    public Topic copy(String id, String name, String description, boolean copyAsDraft) {
        Topic source = getOrThrow(id);
        validateName(name);
        store.findByNamespaceAndName(source.getNamespaceId(), name)
                .ifPresent(existing -> { throw new BusinessException(ErrorCode.TOPIC_NAME_TAKEN); });
        Topic copied = source.toBuilder()
                .id(IdGenerator.topicId())
                .name(name)
                .description(description)
                .status(copyAsDraft ? Topic.Status.DRAFT : source.getStatus())
                .createdAt(Instant.now())
                .updatedAt(null)
                .publishedAt(copyAsDraft ? null : source.getPublishedAt())
                .build();
        store.save(copied);
        templateStore.copy(id, copied.getId());
        subscriptionStore.save(copied.getId(), subscriptionStore.getOrEmpty(source.getId()).getContactIds());
        audit.log("topic.copy", Map.of(
                "actor", currentUser.idOrThrow(),
                "sourceTopicId", id,
                "topicId", copied.getId(),
                "name", copied.getName()));
        return copied;
    }

    private void validateName(String name) {
        if (name == null || !NAME_RE.matcher(name).matches()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "topic name must match " + NAME_RE.pattern());
        }
    }

    private Topic.KeyLocation parseKeyLocation(String value) {
        if (value == null || value.isBlank()) return Topic.KeyLocation.HEADER;
        return Topic.KeyLocation.valueOf(value);
    }

    public record CreateRequest(
            String name,
            String description,
            String authMode,
            String keyLocation,
            java.util.List<String> ipWhitelist,
            JsonNode inboundFormat,
            boolean sync,
            Integer syncTimeout
    ) {}

    public record UpdateRequest(
            String name,
            String description,
            Topic.Auth auth,
            JsonNode inboundFormat,
            java.util.Map<io.litealert.notify.domain.NotifyTarget.Type, Topic.ChannelTemplate> templates,
            Boolean sync,
            Integer syncTimeout
    ) {}
}
