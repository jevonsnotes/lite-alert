package io.litealert.apikey;

import io.litealert.common.config.LiteAlertProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * Hashes ApiKey strings with HMAC-SHA-256 + the configured pepper.
 * The pepper lives only in the environment variable
 * {@code LITE_ALERT_APIKEY_PEPPER}; rotating it invalidates every stored key.
 */
@Component
@RequiredArgsConstructor
public class ApiKeyHasher {

    private static final String ALGO = "HmacSHA256";

    private final LiteAlertProperties props;

    private SecretKeySpec key;

    @PostConstruct
    void init() {
        byte[] secret = props.getApikey().getPepper().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 16) {
            throw new IllegalStateException(
                    "lite-alert.apikey.pepper must be at least 16 chars; got " + secret.length);
        }
        this.key = new SecretKeySpec(secret, ALGO);
    }

    public String hash(String fullKey) {
        try {
            Mac mac = Mac.getInstance(ALGO);
            mac.init(key);
            byte[] sig = mac.doFinal(fullKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sig);
        } catch (Exception e) {
            throw new IllegalStateException("hmac failed", e);
        }
    }

    public boolean matches(String fullKey, String storedHash) {
        if (fullKey == null || storedHash == null) return false;
        String computed = hash(fullKey);
        return constantTimeEquals(computed, storedHash);
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(x, y);
    }
}
