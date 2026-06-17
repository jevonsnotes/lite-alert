package io.litealert.webhook;

import io.litealert.apikey.ApiKeyHasher;
import io.litealert.apikey.domain.ApiKey;
import io.litealert.apikey.domain.ApiKeyStore;
import io.litealert.common.error.BusinessException;
import io.litealert.common.error.ErrorCode;
import io.litealert.topic.domain.Topic;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Verifies an incoming webhook against an ApiKey.
 *
 * <p>Returns the matched key on success; throws a {@link BusinessException}
 * with the precise sub-code on failure (so callers can distinguish
 * INVALID / EXPIRED / REVOKED / SCOPE_NOT_ALLOWED).
 */
@Service
@RequiredArgsConstructor
public class ApiKeyAuthenticator {

    private static final int MAX_FAILS = 10;
    private static final long LOCK_MS = 5 * 60 * 1_000L;

    private final ApiKeyStore store;
    private final ApiKeyHasher hasher;

    private final Map<String, FailRecord> failures = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> usageCounters = new ConcurrentHashMap<>();

    public ApiKey authenticate(String authorization, Topic topic, String remoteIp) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new BusinessException(ErrorCode.API_KEY_INVALID, "missing bearer token");
        }
        String token = authorization.substring(7).trim();
        if (token.isEmpty() || token.length() < 8) {
            throw new BusinessException(ErrorCode.API_KEY_INVALID);
        }
        String prefix = token.substring(0, 8);

        // ip + prefix-based lockout
        String lockKey = remoteIp + "|" + prefix;
        FailRecord rec = failures.get(lockKey);
        if (rec != null && rec.lockedUntil > System.currentTimeMillis()) {
            throw new BusinessException(ErrorCode.API_KEY_INVALID, "temporarily blocked");
        }

        ApiKey k = store.findByPrefix(prefix).orElse(null);
        if (k == null) {
            recordFailure(lockKey);
            throw new BusinessException(ErrorCode.API_KEY_INVALID);
        }
        if (!hasher.matches(token, k.getKeyHash())) {
            recordFailure(lockKey);
            throw new BusinessException(ErrorCode.API_KEY_INVALID);
        }
        if (k.getStatus() == ApiKey.Status.REVOKED) {
            throw new BusinessException(ErrorCode.API_KEY_REVOKED);
        }
        Instant now = Instant.now();
        if (k.getValidFrom() != null && now.isBefore(k.getValidFrom())) {
            throw new BusinessException(ErrorCode.API_KEY_NOT_YET_ACTIVE);
        }
        if (k.getValidUntil() != null && now.isAfter(k.getValidUntil())) {
            throw new BusinessException(ErrorCode.API_KEY_EXPIRED);
        }
        if (!coversTopic(k, topic)) {
            throw new BusinessException(ErrorCode.SCOPE_NOT_ALLOWED);
        }
        // success — reset failure counter and bump usage stats
        failures.remove(lockKey);
        usageCounters.computeIfAbsent(k.getId(), id -> new AtomicLong()).incrementAndGet();
        k.setLastUsedAt(now);
        k.setUsageCount(k.getUsageCount() + 1);
        return k;
    }

    private boolean coversTopic(ApiKey k, Topic topic) {
        for (ApiKey.Scope s : k.getScopes()) {
            if (s.getType() == ApiKey.ScopeType.TOPIC && topic.getId().equals(s.getId())) return true;
            if (s.getType() == ApiKey.ScopeType.NAMESPACE
                    && topic.getNamespaceId().equals(s.getId())) return true;
        }
        return false;
    }

    private void recordFailure(String key) {
        FailRecord rec = failures.computeIfAbsent(key, k -> new FailRecord());
        rec.count++;
        if (rec.count >= MAX_FAILS) {
            rec.lockedUntil = System.currentTimeMillis() + LOCK_MS;
            rec.count = 0;
        }
    }

    static class FailRecord {
        int count;
        long lockedUntil;
    }
}
