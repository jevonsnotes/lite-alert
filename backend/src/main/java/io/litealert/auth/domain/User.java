package io.litealert.auth.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.litealert.common.crypto.Encrypted;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class User {

    public enum Role { ADMIN, USER }
    public enum Permission { DELIVERY_PAYLOAD_READ }

    private String id;
    private String username;

    /**
     * BCrypt hash of the password, then Jasypt-encrypted at rest.
     * Never returned to the API layer.
     */
    @Encrypted
    private String passwordHash;

    private Role role;
    @Builder.Default
    private Set<Permission> permissions = new LinkedHashSet<>();
    private boolean enabled;

    private Instant createdAt;
    private String createdBy;
    private Instant updatedAt;
    private Instant lastLoginAt;

    public boolean hasPermission(Permission permission) {
        return permissions != null && permissions.contains(permission);
    }
}
