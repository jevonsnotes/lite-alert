package io.litealert.topic.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import io.litealert.common.storage.FileStore;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Topics live in {@code topics/{namespaceId}.json} (one file per namespace).
 * The whole set is loaded into memory at startup; subsequent writes
 * synchronously persist the affected namespace shard.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TopicStore {

    private final FileStore fileStore;

    private final Map<String, Topic> byId = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> byNamespaceAndName = new ConcurrentHashMap<>();

    /** {namespace}/{name} → topicId for hot webhook path lookup. */
    private final Map<String, String> webhookIndex = new ConcurrentHashMap<>();

    @PostConstruct
    void load() {
        java.nio.file.Path topicsDir = fileStore.resolve("topics");
        if (!java.nio.file.Files.exists(topicsDir)) return;
        try (var stream = java.nio.file.Files.list(topicsDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                  .forEach(this::loadShard);
        } catch (Exception e) {
            throw new IllegalStateException("topic shard load failed", e);
        }
        log.info("loaded {} topics", byId.size());
    }

    private void loadShard(java.nio.file.Path p) {
        String relative = "topics/" + p.getFileName().toString();
        List<Topic> shard = fileStore.readJson(relative,
                new TypeReference<List<Topic>>() {}, new ArrayList<>());
        for (Topic t : shard) {
            indexTopic(t);
        }
    }

    private void indexTopic(Topic t) {
        byId.put(t.getId(), t);
        byNamespaceAndName.computeIfAbsent(t.getNamespaceId(), k -> new ConcurrentHashMap<>())
                .put(t.getName().toLowerCase(), t.getId());
        if (t.getNamespaceName() != null) {
            webhookIndex.put(webhookKey(t.getNamespaceName(), t.getName()), t.getId());
        }
    }

    public Optional<Topic> findById(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public Optional<Topic> findByNamespaceAndName(String namespaceId, String name) {
        Map<String, String> m = byNamespaceAndName.get(namespaceId);
        if (m == null) return Optional.empty();
        String id = m.get(name.toLowerCase());
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    public Optional<Topic> findForWebhook(String namespaceName, String topicName) {
        String id = webhookIndex.get(webhookKey(namespaceName, topicName));
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    public List<Topic> findByNamespace(String namespaceId) {
        return byId.values().stream()
                .filter(t -> namespaceId.equals(t.getNamespaceId()))
                .toList();
    }

    public List<Topic> findByOwner(String ownerId) {
        return byId.values().stream()
                .filter(t -> ownerId.equals(t.getOwnerId()))
                .toList();
    }

    public List<Topic> findAll() {
        return new ArrayList<>(byId.values());
    }

    public synchronized Topic save(Topic t) {
        // remove old name index entry if name changed
        Topic prev = byId.get(t.getId());
        if (prev != null) {
            Map<String, String> nameIdx = byNamespaceAndName.get(prev.getNamespaceId());
            if (nameIdx != null) nameIdx.remove(prev.getName().toLowerCase());
            if (prev.getNamespaceName() != null) {
                webhookIndex.remove(webhookKey(prev.getNamespaceName(), prev.getName()));
            }
        }
        indexTopic(t);
        flushShard(t.getNamespaceId());
        return t;
    }

    public synchronized void delete(String id) {
        Topic removed = byId.remove(id);
        if (removed == null) return;
        Map<String, String> nameIdx = byNamespaceAndName.get(removed.getNamespaceId());
        if (nameIdx != null) nameIdx.remove(removed.getName().toLowerCase());
        if (removed.getNamespaceName() != null) {
            webhookIndex.remove(webhookKey(removed.getNamespaceName(), removed.getName()));
        }
        flushShard(removed.getNamespaceId());
    }

    public synchronized void deleteByNamespace(String namespaceId) {
        List<String> ids = byId.values().stream()
                .filter(t -> namespaceId.equals(t.getNamespaceId()))
                .map(Topic::getId).toList();
        for (String id : ids) {
            Topic t = byId.remove(id);
            if (t != null && t.getNamespaceName() != null) {
                webhookIndex.remove(webhookKey(t.getNamespaceName(), t.getName()));
            }
        }
        byNamespaceAndName.remove(namespaceId);
        fileStore.delete("topics/" + namespaceId + ".json");
    }

    private void flushShard(String namespaceId) {
        List<Topic> shard = byId.values().stream()
                .filter(t -> namespaceId.equals(t.getNamespaceId()))
                .collect(Collectors.toList());
        fileStore.writeJson("topics/" + namespaceId + ".json", shard);
    }

    private String webhookKey(String ns, String topic) {
        return ns.toLowerCase() + "/" + topic.toLowerCase();
    }
}
