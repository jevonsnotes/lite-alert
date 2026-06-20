package io.litealert.auth.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import io.litealert.common.crypto.Encrypted;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Table("la_user")
public class User {

    @Id(keyType = KeyType.None)
    private String id;

    @Column
    private String username;

    /**
     * BCrypt hash of the password, then Jasypt-encrypted at rest.
     * Never returned to the API layer.
     */
    @Encrypted
    @Column(value = "password_hash")
    private String passwordHash;

    @Column
    private boolean enabled;

    @Column(value = "created_at")
    private Instant createdAt;

    @Column(value = "created_by")
    private String createdBy;

    @Column(value = "updated_at")
    private Instant updatedAt;

    @Column(value = "last_login_at")
    private Instant lastLoginAt;
}
