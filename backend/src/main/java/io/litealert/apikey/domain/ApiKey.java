package io.litealert.apikey.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
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

    /**
     * Scopes stored relationally in {@code la_api_key_scope}.  Marked
     * {@code ignore = true} so MyBatis-Flex does not map it; Jackson still
     * serializes it for API responses.
     */
    @Builder.Default
    @Column(ignore = true)
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

    /**
     * Populate the in-memory {@link #scopes} list from relational rows.
     * Called by {@link ApiKeyStore} after loading from the database.
     */
    public void assembleScopes(List<ApiKeyScope> rows) {
        if (rows == null || rows.isEmpty()) {
            this.scopes = new ArrayList<>();
            return;
        }
        List<Scope> list = new ArrayList<>(rows.size());
        for (ApiKeyScope r : rows) {
            Scope s = new Scope();
            try {
                s.setType(ScopeType.valueOf(r.getScopeType()));
            } catch (IllegalArgumentException e) {
                continue; // skip unknown types
            }
            s.setId(r.getScopeId());
            list.add(s);
        }
        this.scopes = list;
    }

    /**
     * Convert the in-memory {@link #scopes} list into relational rows for
     * persistence.  Called by {@link ApiKeyStore} before saving.
     */
    public List<ApiKeyScope> disassembleScopes() {
        if (scopes == null || scopes.isEmpty()) return List.of();
        List<ApiKeyScope> rows = new ArrayList<>(scopes.size());
        for (Scope s : scopes) {
            ApiKeyScope row = ApiKeyScope.builder()
                    .scopeType(s.getType().name())
                    .scopeId(s.getId())
                    .build();
            rows.add(row);
        }
        return rows;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Scope {
        private ScopeType type;
        private String id;                 // namespaceId or topicId
    }
}
