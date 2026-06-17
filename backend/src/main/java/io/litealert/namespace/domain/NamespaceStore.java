package io.litealert.namespace.domain;

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

@Slf4j
@Component
@RequiredArgsConstructor
public class NamespaceStore {

    public static final String FILE = "namespaces.json";

    private final FileStore fileStore;

    private final Map<String, Namespace> byId = new ConcurrentHashMap<>();
    private final Map<String, String> idByName = new ConcurrentHashMap<>();

    @PostConstruct
    void load() {
        List<Namespace> all = fileStore.readJson(FILE,
                new TypeReference<List<Namespace>>() {}, new ArrayList<>());
        for (Namespace n : all) {
            byId.put(n.getId(), n);
            idByName.put(n.getName().toLowerCase(), n.getId());
        }
        log.info("loaded {} namespaces", byId.size());
    }

    public Optional<Namespace> findById(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public Optional<Namespace> findByName(String name) {
        if (name == null) return Optional.empty();
        String id = idByName.get(name.toLowerCase());
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    public List<Namespace> findAll() {
        return new ArrayList<>(byId.values());
    }

    public List<Namespace> findByOwner(String ownerId) {
        return byId.values().stream()
                .filter(n -> ownerId.equals(n.getOwnerId()))
                .toList();
    }

    public synchronized Namespace save(Namespace n) {
        byId.put(n.getId(), n);
        idByName.put(n.getName().toLowerCase(), n.getId());
        flush();
        return n;
    }

    public synchronized void delete(String id) {
        Namespace removed = byId.remove(id);
        if (removed != null) {
            idByName.remove(removed.getName().toLowerCase());
            flush();
        }
    }

    private void flush() {
        fileStore.writeJson(FILE, new ArrayList<>(byId.values()));
    }
}
