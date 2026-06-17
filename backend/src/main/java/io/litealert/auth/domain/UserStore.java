package io.litealert.auth.domain;

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

/**
 * In-memory + file-backed user store. Single source of truth for auth.
 *
 * <p>File: {@code users.json} — the entire list, since we expect at most
 * a few dozen users in the lifetime of an instance.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserStore {

    public static final String FILE = "users.json";

    private final FileStore fileStore;

    private final Map<String, User> byId = new ConcurrentHashMap<>();
    private final Map<String, String> idByUsername = new ConcurrentHashMap<>();

    @PostConstruct
    void load() {
        List<User> all = fileStore.readJson(FILE, new TypeReference<List<User>>() {}, new ArrayList<>());
        for (User u : all) {
            byId.put(u.getId(), u);
            idByUsername.put(u.getUsername().toLowerCase(), u.getId());
        }
        log.info("loaded {} users", byId.size());
    }

    public Optional<User> findById(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public Optional<User> findByUsername(String username) {
        if (username == null) return Optional.empty();
        String id = idByUsername.get(username.toLowerCase());
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    public List<User> findAll() {
        return new ArrayList<>(byId.values());
    }

    public boolean existsByUsername(String username) {
        return findByUsername(username).isPresent();
    }

    public synchronized User save(User u) {
        byId.put(u.getId(), u);
        idByUsername.put(u.getUsername().toLowerCase(), u.getId());
        flush();
        return u;
    }

    public synchronized void delete(String id) {
        User removed = byId.remove(id);
        if (removed != null) {
            idByUsername.remove(removed.getUsername().toLowerCase());
            flush();
        }
    }

    public boolean hasAnyAdmin() {
        return byId.values().stream().anyMatch(u -> u.getRole() == User.Role.ADMIN);
    }

    private void flush() {
        fileStore.writeJson(FILE, new ArrayList<>(byId.values()));
    }
}
