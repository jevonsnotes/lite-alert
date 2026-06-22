package io.litealert.apikey.domain;

import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class ApiKeyScopeStore {

    private final ApiKeyScopeMapper mapper;

    public List<ApiKeyScope> findByApiKeyId(String apiKeyId) {
        QueryWrapper qw = QueryWrapper.create()
                .where("api_key_id = ?", apiKeyId);
        return mapper.selectListByQuery(qw);
    }

    /** Batch-load scopes for many API keys at once to avoid N+1. */
    public Map<String, List<ApiKeyScope>> findByApiKeyIds(Collection<String> apiKeyIds) {
        if (apiKeyIds == null || apiKeyIds.isEmpty()) return Map.of();
        Object[] args = apiKeyIds.toArray();
        QueryWrapper qw = QueryWrapper.create()
                .where("api_key_id in (" + placeholders(args.length) + ")", args)
                .orderBy("api_key_id asc, scope_type asc, scope_id asc");
        List<ApiKeyScope> rows = mapper.selectListByQuery(qw);
        return rows.stream().collect(Collectors.groupingBy(ApiKeyScope::getApiKeyId, LinkedHashMap::new, Collectors.toList()));
    }

    /** Delete all existing scopes for the given API key and insert the new set. */
    public void saveForApiKey(String apiKeyId, List<ApiKeyScope> scopes) {
        deleteByApiKeyId(apiKeyId);
        if (scopes == null || scopes.isEmpty()) return;
        Instant now = Instant.now();
        for (ApiKeyScope s : scopes) {
            s.setApiKeyId(apiKeyId);
            s.setCreatedAt(now);
            mapper.insert(s);
        }
    }

    public void deleteByApiKeyId(String apiKeyId) {
        QueryWrapper qw = QueryWrapper.create()
                .where("api_key_id = ?", apiKeyId);
        mapper.deleteByQuery(qw);
    }

    private String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }
}
