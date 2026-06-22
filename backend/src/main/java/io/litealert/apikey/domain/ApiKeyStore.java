package io.litealert.apikey.domain;

import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class ApiKeyStore {

    private final ApiKeyMapper mapper;
    private final ApiKeyScopeStore scopeStore;

    public Optional<ApiKey> findById(String id) {
        ApiKey k = mapper.selectOneById(id);
        if (k != null) {
            k.assembleScopes(scopeStore.findByApiKeyId(id));
        }
        return k == null ? Optional.empty() : Optional.of(k);
    }

    public Optional<ApiKey> findByPrefix(String prefix) {
        QueryWrapper qw = QueryWrapper.create()
                .where("prefix = ?", prefix);
        ApiKey k = mapper.selectOneByQuery(qw);
        if (k != null) {
            k.assembleScopes(scopeStore.findByApiKeyId(k.getId()));
        }
        return Optional.ofNullable(k);
    }

    public List<ApiKey> findByOwner(String ownerId) {
        QueryWrapper qw = QueryWrapper.create()
                .where("owner_id = ?", ownerId)
                .orderBy("created_at desc, name asc");
        List<ApiKey> keys = mapper.selectListByQuery(qw);
        assembleScopesForKeys(keys);
        return keys;
    }

    public List<ApiKey> findAll() {
        QueryWrapper qw = QueryWrapper.create()
                .orderBy("created_at desc, name asc");
        List<ApiKey> keys = mapper.selectListByQuery(qw);
        assembleScopesForKeys(keys);
        return keys;
    }

    private void assembleScopesForKeys(List<ApiKey> keys) {
        if (keys.isEmpty()) return;
        Set<String> ids = keys.stream().map(ApiKey::getId).collect(Collectors.toSet());
        Map<String, List<ApiKeyScope>> byKey = scopeStore.findByApiKeyIds(ids);
        for (ApiKey k : keys) {
            k.assembleScopes(byKey.getOrDefault(k.getId(), List.of()));
        }
    }

    public synchronized ApiKey save(ApiKey k) {
        if (findByIdWithoutScopes(k.getId()).isPresent()) {
            mapper.update(k);
        } else {
            mapper.insert(k);
        }
        scopeStore.saveForApiKey(k.getId(), k.disassembleScopes());
        return k;
    }

    /** Internal find that does NOT assemble scopes — used by save() to avoid infinite recursion. */
    private Optional<ApiKey> findByIdWithoutScopes(String id) {
        ApiKey k = mapper.selectOneById(id);
        return k == null ? Optional.empty() : Optional.of(k);
    }

    public synchronized void delete(String id) {
        scopeStore.deleteByApiKeyId(id);
        mapper.deleteById(id);
    }
}
