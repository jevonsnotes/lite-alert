package io.litealert.auth.role;

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
public class Role {
    private String id;
    private String name;
    private String description;
    private boolean systemBuiltin;
    @Builder.Default
    private Set<String> permissions = new LinkedHashSet<>();
    private Instant createdAt;
    private Instant updatedAt;
}
