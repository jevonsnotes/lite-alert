package io.litealert.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.litealert.auth.domain.User;
import io.litealert.common.config.LiteAlertProperties;
import io.litealert.common.error.BusinessException;
import io.litealert.common.error.ErrorCode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

/**
 * HS256 JWT issuer / parser. Stateless; revocation in M1 is handled by
 * forcing token rotation on logout (client drops it).
 */
@Service
@RequiredArgsConstructor
public class JwtService {

    private final LiteAlertProperties props;

    private SecretKey key;

    @PostConstruct
    void init() {
        byte[] secret = props.getJwt().getSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException(
                    "lite-alert.jwt.secret must be at least 32 chars; current length="
                            + secret.length);
        }
        this.key = Keys.hmacShaKeyFor(secret);
    }

    public String issue(User user) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(props.getJwt().getTtlSeconds());
        return Jwts.builder()
                .subject(user.getId())
                .claim("username", user.getUsername())
                .claim("role", user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        try {
            return Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
        } catch (io.jsonwebtoken.ExpiredJwtException e) {
            throw new BusinessException(ErrorCode.TOKEN_EXPIRED);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }
    }

    public Map<String, Object> describe(String token) {
        Claims c = parse(token);
        return Map.of(
                "sub", c.getSubject(),
                "username", c.get("username", String.class),
                "role", c.get("role", String.class),
                "exp", c.getExpiration().toInstant().toString());
    }

    public long ttlSeconds() {
        return props.getJwt().getTtlSeconds();
    }
}
