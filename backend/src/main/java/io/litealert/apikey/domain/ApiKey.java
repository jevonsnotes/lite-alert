package io.litealert.apikey.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import io.litealert.common.db.JsonScopesTypeHandler;
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
@Table("la_api_key")
public class ApiKey {

    public enum Status { ACTIVE, REVOKED }

    public enum ScopeType { TOPIC, NAMESPACE }

    @Id(keyType = KeyType.None)
    private String id;

    @Column(value = "owner_id")
    private String ownerId;

    @Column
    private String name;

    /** First 8-char of the printable key, kept in plaintext for indexing/UI display. */
    @Column
    private String prefix;

    /**
     * HMAC-SHA-256(pepper, fullKey) hex. Server NEVER stores the original key.
     * Verifying a presented key recomputes the HMAC and compares constant-time.
     */
    @Column(value = "key_hash")
    private String keyHash;

    @Column(value = "valid_from")
    private Instant validFrom;

    @Column(value = "valid_until")
    private Instant validUntil;            // null → never expires

    @Builder.Default
    @Column(value = "scopes_json", typeHandler = JsonScopesTypeHandler.class)
    private List<Scope> scopes = new ArrayList<>();

    @Column
    private Status status;

    @Column(value = "created_at")
    private Instant createdAt;

    @Column(value = "last_used_at")
    private Instant lastUsedAt;

    @Column(value = "usage_count")
    private long usageCount;

    @Column(value = "rotate_count")
    private long rotateCount;

    @Column(value = "rate_limit_per_minute")
    private Integer rateLimitPerMinute;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Scope {
        private ScopeType type;
        private String id;                 // namespaceId or topicId
    }
}
