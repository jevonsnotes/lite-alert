package io.litealert.topic;

import com.fasterxml.jackson.databind.JsonNode;
import io.litealert.auth.CurrentUser;
import io.litealert.auth.domain.User;
import io.litealert.common.audit.AuditLogger;
import io.litealert.common.config.LiteAlertProperties;
import io.litealert.common.error.BusinessException;
import io.litealert.common.error.ErrorCode;
import io.litealert.common.util.IdGenerator;
import io.litealert.namespace.NamespaceService;
import io.litealert.namespace.domain.Namespace;
import io.litealert.topic.domain.Topic;
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
    private final CurrentUser currentUser;
    private final AuditLogger audit;
    private final LiteAlertProperties props;

    public List<Topic> listByNamespace(String namespaceId) {
        namespaceService.getOrThrow(namespaceId); // ownership check
        return store.findByNamespace(namespaceId);
    }

    public List<Topic> listMine() {
        if (currentUser.isAdmin()) return store.findAll();
        return store.findByOwner(currentUser.idOrThrow());
    }

    public Topic getOrThrow(String id) {
        Topic t = store.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "topic not found"));
        if (!currentUser.isAdmin() && !t.getOwnerId().equals(currentUser.idOrThrow())) {
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
        Topic.AuthMode mode = req.authMode() == null
                ? Topic.AuthMode.API_KEY
                : Topic.AuthMode.valueOf(req.authMode());
        if (mode == Topic.AuthMode.NONE) {
            assertCanUsePublicMode();
        }
        Topic.Auth auth = new Topic.Auth(mode,
                req.ipWhitelist() == null ? new ArrayList<>() : req.ipWhitelist(),
                null);

        Topic t = Topic.builder()
                .id(IdGenerator.topicId())
                .namespaceId(namespaceId)
                .namespaceName(ns.getName())
                .name(req.name())
                .description(req.description())
                .ownerId(ns.getOwnerId())
                .status(Topic.Status.DRAFT)
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

        // Channel templates (preferred path).
        if (req.templates() != null) {
            t.setTemplates(new java.util.EnumMap<>(req.templates()));
        }

        // Legacy fields fold into specific channels:
        //   notifyTemplate → EMAIL channel template
        //   transform      → WEBHOOK channel template's transform
        if (req.notifyTemplate() != null) {
            ensureTemplates(t);
            Topic.ChannelTemplate ch = t.getTemplates().computeIfAbsent(
                    io.litealert.notify.domain.NotifyTarget.Type.EMAIL,
                    k -> new Topic.ChannelTemplate());
            ch.setSubject(req.notifyTemplate().getSubject());
            ch.setBody(req.notifyTemplate().getBody());
        }
        if (req.transform() != null) {
            ensureTemplates(t);
            Topic.ChannelTemplate ch = t.getTemplates().computeIfAbsent(
                    io.litealert.notify.domain.NotifyTarget.Type.WEBHOOK,
                    k -> new Topic.ChannelTemplate());
            ch.setTransform(req.transform());
        }

        if (req.inboundFormat() != null) {
            if (published) throw new BusinessException(ErrorCode.SCHEMA_LOCKED);
            t.setInboundFormat(req.inboundFormat());
        }

        if (req.auth() != null) {
            Topic.AuthMode newMode = req.auth().getMode();
            if (newMode == Topic.AuthMode.NONE
                    && t.getAuth().getMode() != Topic.AuthMode.NONE) {
                assertCanUsePublicMode();
            }
            t.setAuth(req.auth());
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

    private void assertCanUsePublicMode() {
        if (currentUser.isAdmin()) return;
        if (props.getWebhook().isAllowUserPublicTopic()) return;
        throw new BusinessException(ErrorCode.FORBIDDEN,
                "creating public (NONE-auth) topics requires admin or "
                        + "lite-alert.webhook.allow-user-public-topic=true");
    }

    private void ensureTemplates(Topic t) {
        if (t.getTemplates() == null) {
            t.setTemplates(new java.util.EnumMap<>(io.litealert.notify.domain.NotifyTarget.Type.class));
        }
    }

    public record CreateRequest(
            String name,
            String description,
            String authMode,
            java.util.List<String> ipWhitelist,
            JsonNode inboundFormat
    ) {}

    public record UpdateRequest(
            String description,
            Topic.Auth auth,
            JsonNode inboundFormat,
            java.util.Map<io.litealert.notify.domain.NotifyTarget.Type, Topic.ChannelTemplate> templates,
            // legacy fields kept temporarily so the old UI doesn't 400; both
            // are folded into the EMAIL/WEBHOOK channel templates on save.
            Topic.Transform transform,
            Topic.NotifyTemplate notifyTemplate
    ) {}
}
