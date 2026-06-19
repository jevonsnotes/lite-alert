package io.litealert.apikey;

import io.litealert.apikey.domain.ApiKey;
import io.litealert.apikey.domain.ApiKeyStore;
import io.litealert.auth.CurrentUser;
import io.litealert.common.audit.AuditLogger;
import io.litealert.common.error.BusinessException;
import io.litealert.common.error.ErrorCode;
import io.litealert.common.util.IdGenerator;
import io.litealert.namespace.NamespaceService;
import io.litealert.topic.TopicService;
import io.litealert.topic.domain.Topic;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ApiKey lifecycle: create, revoke, delete, rotate (== revoke + create new).
 * Verification happens in the webhook auth layer; this service is the
 * write side.
 */
@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private static final String KEY_PREFIX = "la_";

    private final ApiKeyStore store;
    private final ApiKeyHasher hasher;
    private final CurrentUser currentUser;
    private final NamespaceService namespaceService;
    private final TopicService topicService;
    private final AuditLogger audit;

    public List<ApiKey> listMine() {
        return store.findByOwner(currentUser.idOrThrow());
    }

    public ApiKey getOrThrow(String id) {
        ApiKey k = store.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "api key not found"));
        if (!currentUser.isAdmin() && !k.getOwnerId().equals(currentUser.idOrThrow())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return k;
    }

    /** Creates a fresh key. Returns plaintext fullKey ONCE — caller must show it then drop it. */
    public CreateResult create(CreateRequest req) {
        validateScopes(req.scopes());
        validateValidity(req.validFrom(), req.validUntil());

        String fullKey = KEY_PREFIX + IdGenerator.urlSafeToken(32);
        String prefix = fullKey.substring(0, 8);
        String hash = hasher.hash(fullKey);

        ApiKey k = ApiKey.builder()
                .id(IdGenerator.apiKeyId())
                .ownerId(currentUser.idOrThrow())
                .name(req.name())
                .prefix(prefix)
                .keyHash(hash)
                .validFrom(req.validFrom() == null ? Instant.now() : req.validFrom())
                .validUntil(req.validUntil())
                .scopes(dedupScopes(req.scopes()))
                .status(ApiKey.Status.ACTIVE)
                .rateLimitPerMinute(req.rateLimitPerMinute())
                .createdAt(Instant.now())
                .build();
        store.save(k);
        audit.log("apikey.create", Map.of(
                "actor", k.getOwnerId(),
                "apiKeyId", k.getId(),
                "scopes", k.getScopes()));
        return new CreateResult(k, fullKey);
    }

    public ApiKey update(String id, UpdateRequest req) {
        ApiKey k = getOrThrow(id);
        if (k.getStatus() == ApiKey.Status.REVOKED) {
            throw new BusinessException(ErrorCode.APIKEY_REVOKED_FINAL);
        }
        if (req.name() != null) k.setName(req.name());
        if (req.rateLimitPerMinute() != null) k.setRateLimitPerMinute(req.rateLimitPerMinute());
        if (req.scopes() != null) {
            validateScopes(req.scopes());
            k.setScopes(dedupScopes(req.scopes()));
        }
        if (req.validUntil() != null || req.clearValidUntil()) {
            Instant from = k.getValidFrom() == null ? Instant.now() : k.getValidFrom();
            Instant until = req.clearValidUntil() ? null : req.validUntil();
            validateValidity(from, until);
            k.setValidUntil(until);
        }
        store.save(k);
        audit.log("apikey.update", Map.of(
                "actor", currentUser.idOrThrow(),
                "apiKeyId", id));
        return k;
    }

    public ApiKey revoke(String id) {
        ApiKey k = getOrThrow(id);
        if (k.getStatus() == ApiKey.Status.REVOKED) {
            throw new BusinessException(ErrorCode.APIKEY_REVOKED_FINAL);
        }
        k.setStatus(ApiKey.Status.REVOKED);
        store.save(k);
        audit.log("apikey.revoke", Map.of(
                "actor", currentUser.idOrThrow(),
                "apiKeyId", id));
        return k;
    }

    /**
     * Rotates the secret material for an existing active key while preserving
     * its editable metadata. The old plaintext key becomes invalid because the
     * stored prefix/hash pair is replaced; the new plaintext is returned once.
     */
    public CreateResult rotate(String id) {
        ApiKey k = getOrThrow(id);
        if (k.getStatus() == ApiKey.Status.REVOKED) {
            throw new BusinessException(ErrorCode.APIKEY_REVOKED_FINAL);
        }

        String fullKey = KEY_PREFIX + IdGenerator.urlSafeToken(32);
        k.setPrefix(fullKey.substring(0, 8));
        k.setKeyHash(hasher.hash(fullKey));
        k.setLastUsedAt(null);
        k.setUsageCount(0);
        k.setRotateCount(k.getRotateCount() + 1);
        store.save(k);
        audit.log("apikey.rotate", Map.of(
                "actor", currentUser.idOrThrow(),
                "apiKeyId", id));
        return new CreateResult(k, fullKey);
    }

    public void delete(String id) {
        ApiKey k = getOrThrow(id);
        boolean expired = k.getValidUntil() != null && k.getValidUntil().isBefore(Instant.now());
        if (k.getStatus() == ApiKey.Status.ACTIVE && !expired) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "revoke or wait for expiry before deleting");
        }
        store.delete(id);
        audit.log("apikey.delete", Map.of(
                "actor", currentUser.idOrThrow(),
                "apiKeyId", id));
    }

    public boolean coversTopic(ApiKey k, Topic topic) {
        for (ApiKey.Scope s : k.getScopes()) {
            if (s.getType() == ApiKey.ScopeType.TOPIC && topic.getId().equals(s.getId())) {
                return true;
            }
            if (s.getType() == ApiKey.ScopeType.NAMESPACE
                    && topic.getNamespaceId().equals(s.getId())) {
                return true;
            }
        }
        return false;
    }

    public List<ApiKey> findCovering(String topicId) {
        Topic t = topicService.getOrThrow(topicId);
        return store.findByOwner(t.getOwnerId()).stream()
                .filter(k -> k.getStatus() == ApiKey.Status.ACTIVE)
                .filter(k -> coversTopic(k, t))
                .toList();
    }

    private void validateScopes(List<ApiKey.Scope> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "at least one scope is required");
        }
        String me = currentUser.idOrThrow();
        for (ApiKey.Scope s : scopes) {
            if (s.getType() == ApiKey.ScopeType.NAMESPACE) {
                var ns = namespaceService.getOrThrow(s.getId());
                if (!currentUser.isAdmin() && !me.equals(ns.getOwnerId())) {
                    throw new BusinessException(ErrorCode.APIKEY_OWNED_BY_OTHER);
                }
            } else if (s.getType() == ApiKey.ScopeType.TOPIC) {
                Topic t = topicService.getOrThrow(s.getId());
                if (!currentUser.isAdmin() && !me.equals(t.getOwnerId())) {
                    throw new BusinessException(ErrorCode.APIKEY_OWNED_BY_OTHER);
                }
            } else {
                throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "unknown scope type");
            }
        }
    }

    private void validateValidity(Instant from, Instant until) {
        if (until == null) return;
        Instant base = from == null ? Instant.now() : from;
        if (!until.isAfter(base.plusSeconds(300))) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "validUntil must be at least 5 minutes after validFrom");
        }
    }

    private List<ApiKey.Scope> dedupScopes(List<ApiKey.Scope> raw) {
        Set<String> namespaceIds = new HashSet<>();
        for (ApiKey.Scope s : raw) {
            if (s.getType() == ApiKey.ScopeType.NAMESPACE) namespaceIds.add(s.getId());
        }
        List<ApiKey.Scope> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ApiKey.Scope s : raw) {
            String key = s.getType() + ":" + s.getId();
            if (seen.contains(key)) continue;
            // drop TOPIC scopes whose namespace is already covered
            if (s.getType() == ApiKey.ScopeType.TOPIC) {
                Topic t = topicService.getOrThrow(s.getId());
                if (namespaceIds.contains(t.getNamespaceId())) continue;
            }
            seen.add(key);
            out.add(s);
        }
        return out;
    }

    public record CreateRequest(
            String name,
            Instant validFrom,
            Instant validUntil,
            List<ApiKey.Scope> scopes,
            Integer rateLimitPerMinute
    ) {}

    public record UpdateRequest(
            String name,
            List<ApiKey.Scope> scopes,
            Instant validUntil,
            boolean clearValidUntil,
            Integer rateLimitPerMinute
    ) {}

    public record CreateResult(ApiKey apiKey, String fullKey) {}
}
