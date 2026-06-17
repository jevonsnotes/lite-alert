package io.litealert.common.util;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/**
 * Centralized id and random-token generation.
 *
 * <p>Two flavors of token:
 * <ul>
 *   <li>{@link #randomBytes(int)} for raw byte arrays (HMAC keys, etc.).</li>
 *   <li>{@link #urlSafeToken(int)} for human-printable api keys / webhook keys.</li>
 * </ul>
 */
public final class IdGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER =
            Base64.getUrlEncoder().withoutPadding();

    private IdGenerator() {}

    /** {@code u_xxxxxxxx} — unique id for users / namespaces / topics. */
    public static String entityId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    public static String userId()        { return entityId("u"); }
    public static String namespaceId()   { return entityId("ns"); }
    public static String topicId()       { return entityId("t"); }
    public static String contactId()     { return entityId("c"); }
    public static String apiKeyId()      { return entityId("ak"); }
    public static String subscriptionId(){ return entityId("sub"); }
    public static String traceId()       { return "tr_" + UUID.randomUUID().toString().replace("-", ""); }

    public static byte[] randomBytes(int len) {
        byte[] buf = new byte[len];
        RANDOM.nextBytes(buf);
        return buf;
    }

    public static String urlSafeToken(int byteLen) {
        return URL_ENCODER.encodeToString(randomBytes(byteLen));
    }
}
