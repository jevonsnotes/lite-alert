package io.litealert.auth;

import io.litealert.auth.domain.User;
import io.litealert.auth.domain.UserStore;
import io.litealert.common.audit.AuditLogger;
import io.litealert.common.error.BusinessException;
import io.litealert.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Login / lockout. Lockout is keyed by username (case-insensitive) and lives
 * in memory only — by design, since persistence would add file IO to every
 * failed login and the lock window is short (15 min).
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final int MAX_FAILS = 5;
    private static final long LOCK_WINDOW_MS = 15 * 60 * 1_000L;

    private final UserStore userStore;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditLogger audit;

    private final Map<String, FailRecord> fails = new ConcurrentHashMap<>();

    public LoginResult login(String username, String rawPassword) {
        String key = username == null ? "" : username.toLowerCase();
        FailRecord rec = fails.get(key);
        if (rec != null && rec.lockedUntil > System.currentTimeMillis()) {
            audit("login.locked", username, null);
            throw new BusinessException(ErrorCode.ACCOUNT_LOCKED);
        }
        User u = userStore.findByUsername(username).orElse(null);
        if (u == null || !passwordEncoder.matches(rawPassword, u.getPasswordHash())) {
            recordFailure(key);
            audit("login.failed", username, null);
            throw new BusinessException(ErrorCode.INVALID_CREDENTIAL);
        }
        if (!u.isEnabled()) {
            audit("login.disabled", username, u.getId());
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        }
        fails.remove(key);
        u.setLastLoginAt(Instant.now());
        userStore.save(u);
        audit("login.success", username, u.getId());
        return new LoginResult(jwtService.issue(u),
                Instant.now().plusSeconds(jwtService.ttlSeconds()),
                u);
    }

    private void recordFailure(String key) {
        FailRecord rec = fails.computeIfAbsent(key, k -> new FailRecord());
        rec.count++;
        rec.lastTs = System.currentTimeMillis();
        if (rec.count >= MAX_FAILS) {
            rec.lockedUntil = System.currentTimeMillis() + LOCK_WINDOW_MS;
            rec.count = 0;
        }
    }

    private void audit(String type, String username, String userId) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("username", username);
        if (userId != null) attrs.put("userId", userId);
        audit.log(type, attrs);
    }

    public record LoginResult(String token, Instant expiresAt, User user) {}

    static class FailRecord {
        int count;
        long lastTs;
        long lockedUntil;
    }
}
