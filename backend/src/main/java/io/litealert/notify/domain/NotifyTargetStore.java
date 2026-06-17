package io.litealert.notify.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import io.litealert.common.storage.FileStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-user notify target store. Same on-disk layout as the previous
 * EmailContactStore ({@code contacts/{userId}.json}) — reuses the file so
 * existing data migrates transparently. Records that lack a {@code type}
 * are interpreted as legacy EMAIL contacts.
 */
@Component
@RequiredArgsConstructor
public class NotifyTargetStore {

    private final FileStore fileStore;

    private final Map<String, List<NotifyTarget>> byUser = new ConcurrentHashMap<>();
    private final Map<String, NotifyTarget> byId = new ConcurrentHashMap<>();

    private List<NotifyTarget> loadFor(String userId) {
        return byUser.computeIfAbsent(userId, u -> {
            List<NotifyTarget> shard = fileStore.readJson(
                    "contacts/" + u + ".json",
                    new TypeReference<List<NotifyTarget>>() {}, new ArrayList<>());
            // backfill type for legacy records
            for (NotifyTarget t : shard) {
                if (t.getType() == null) t.setType(NotifyTarget.Type.EMAIL);
                byId.put(t.getId(), t);
            }
            return new ArrayList<>(shard);
        });
    }

    public List<NotifyTarget> findByUser(String userId) {
        return new ArrayList<>(loadFor(userId));
    }

    public Optional<NotifyTarget> findById(String id) {
        // Make sure shard is loaded — id index populates lazily through loadFor.
        if (!byId.containsKey(id)) {
            for (List<NotifyTarget> shard : byUser.values()) {
                for (NotifyTarget t : shard) byId.putIfAbsent(t.getId(), t);
            }
        }
        return Optional.ofNullable(byId.get(id));
    }

    public synchronized NotifyTarget save(NotifyTarget t) {
        List<NotifyTarget> shard = loadFor(t.getUserId());
        shard.removeIf(x -> x.getId().equals(t.getId()));
        shard.add(t);
        byUser.put(t.getUserId(), shard);
        byId.put(t.getId(), t);
        flush(t.getUserId());
        return t;
    }

    public synchronized void delete(String id) {
        NotifyTarget t = byId.remove(id);
        if (t == null) return;
        List<NotifyTarget> shard = byUser.get(t.getUserId());
        if (shard != null) shard.removeIf(x -> x.getId().equals(id));
        flush(t.getUserId());
    }

    private void flush(String userId) {
        List<NotifyTarget> shard = byUser.get(userId);
        if (shard == null || shard.isEmpty()) {
            fileStore.delete("contacts/" + userId + ".json");
        } else {
            fileStore.writeJson("contacts/" + userId + ".json", shard);
        }
    }
}
