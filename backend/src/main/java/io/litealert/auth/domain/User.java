package io.litealert.auth.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
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
public class User {

    public enum Role { ADMIN, USER }

    private String id;
    private String username;

    /**
     * BCrypt hash of the password, then Jasypt-encrypted at rest.
     * Never returned to the API layer.
     */
    @Encrypted
    private String passwordHash;

    private Role role;
    private boolean enabled;

    private Instant createdAt;
    private String createdBy;
    private Instant updatedAt;
    private Instant lastLoginAt;
}
