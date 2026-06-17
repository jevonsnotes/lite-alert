package io.litealert.apikey.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import io.litealert.common.storage.FileStore;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * ApiKey store: one file per user, two in-memory indexes:
 * <ul>
 *   <li>{@code byPrefix}: O(1) lookup during webhook auth.</li>
 *   <li>{@code byOwner}: list view in the UI.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyStore {

    private final FileStore fileStore;

    private final Map<String, ApiKey> byId = new ConcurrentHashMap<>();
    private final Map<String, ApiKey> byPrefix = new ConcurrentHashMap<>();
    private final Map<String, List<String>> idByOwner = new ConcurrentHashMap<>();

    @PostConstruct
    void load() {
        Path dir = fileStore.resolve("apikeys");
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".json"))
             .forEach(this::loadShard);
        } catch (Exception e) {
            throw new IllegalStateException("apikey load failed", e);
        }
        log.info("loaded {} api keys", byId.size());
    }

    private void loadShard(Path p) {
        String relative = "apikeys/" + p.getFileName().toString();
        List<ApiKey> keys = fileStore.readJson(relative,
                new TypeReference<List<ApiKey>>() {}, new ArrayList<>());
        for (ApiKey k : keys) index(k);
    }

    private void index(ApiKey k) {
        byId.put(k.getId(), k);
        if (k.getPrefix() != null) byPrefix.put(k.getPrefix(), k);
        List<String> ids = idByOwner.computeIfAbsent(k.getOwnerId(), o -> new java.util.ArrayList<>());
        ids.removeIf(k.getId()::equals);
        ids.add(k.getId());
    }

    public Optional<ApiKey> findById(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public Optional<ApiKey> findByPrefix(String prefix) {
        return Optional.ofNullable(byPrefix.get(prefix));
    }

    public List<ApiKey> findByOwner(String ownerId) {
        List<String> ids = idByOwner.getOrDefault(ownerId, List.of());
        return ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
    }

    public synchronized ApiKey save(ApiKey k) {
        ApiKey old = byId.get(k.getId());
        if (old != null) {
            if (old.getPrefix() != null) byPrefix.remove(old.getPrefix());
            List<String> oldOwnerIds = idByOwner.get(old.getOwnerId());
            if (oldOwnerIds != null) oldOwnerIds.removeIf(k.getId()::equals);
        }
        index(k);
        flushOwner(k.getOwnerId());
        return k;
    }

    public synchronized void delete(String id) {
        ApiKey removed = byId.remove(id);
        if (removed == null) return;
        if (removed.getPrefix() != null) byPrefix.remove(removed.getPrefix());
        List<String> ids = idByOwner.get(removed.getOwnerId());
        if (ids != null) ids.remove(id);
        flushOwner(removed.getOwnerId());
    }

    private void flushOwner(String ownerId) {
        List<ApiKey> shard = byId.values().stream()
                .filter(k -> ownerId.equals(k.getOwnerId()))
                .collect(Collectors.toList());
        if (shard.isEmpty()) {
            fileStore.delete("apikeys/" + ownerId + ".json");
        } else {
            fileStore.writeJson("apikeys/" + ownerId + ".json", shard);
        }
    }
}
