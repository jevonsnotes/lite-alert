package io.litealert.apikey.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiKey {

    public enum Status { ACTIVE, REVOKED }

    public enum ScopeType { TOPIC, NAMESPACE }

    private String id;
    private String ownerId;
    private String name;

    /** First 8-char of the printable key, kept in plaintext for indexing/UI display. */
    private String prefix;

    /**
     * HMAC-SHA-256(pepper, fullKey) hex. Server NEVER stores the original key.
     * Verifying a presented key recomputes the HMAC and compares constant-time.
     */
    private String keyHash;

    private Instant validFrom;
    private Instant validUntil;            // null → never expires

    @Builder.Default
    private List<Scope> scopes = new ArrayList<>();

    private Status status;

    private Instant createdAt;
    private Instant lastUsedAt;
    private long usageCount;
    private long rotateCount;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Scope {
        private ScopeType type;
        private String id;                 // namespaceId or topicId
    }
}
