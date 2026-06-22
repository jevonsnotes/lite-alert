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

/**
 * Relational row for a single ApiKey scope, previously stored as a JSON array
 * in {@code la_api_key.scopes_json}.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Table("la_api_key_scope")
public class ApiKeyScope {

    @Id(keyType = KeyType.Auto)
    private Long id;

    @Column("api_key_id")
    private String apiKeyId;

    /** Normalized enum name: TOPIC, NAMESPACE. */
    @Column("scope_type")
    private String scopeType;

    @Column("scope_id")
    private String scopeId;

    @Column("created_at")
    private Instant createdAt;
}
