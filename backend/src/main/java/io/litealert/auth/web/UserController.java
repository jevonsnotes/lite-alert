package io.litealert.auth.web;

import io.litealert.auth.CurrentUser;
import io.litealert.auth.PasswordPolicy;
import io.litealert.auth.domain.User;
import io.litealert.auth.domain.UserStore;
import io.litealert.common.audit.AuditLogger;
import io.litealert.common.error.BusinessException;
import io.litealert.common.error.ErrorCode;
import io.litealert.common.util.IdGenerator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * ADMIN-only user management. Path is allowlisted as ROLE_ADMIN in
 * {@link io.litealert.auth.SecurityConfig}.
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserStore userStore;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final CurrentUser currentUser;
    private final AuditLogger audit;

    @GetMapping
    public List<Map<String, Object>> list() {
        return userStore.findAll().stream()
                .map(AuthController::profile)
                .toList();
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody CreateUserRequest req) {
        if (userStore.existsByUsername(req.username())) {
            throw new BusinessException(ErrorCode.CONFLICT, "username already taken");
        }
        passwordPolicy.check(req.username(), req.password());
        User.Role role = req.role() == null ? User.Role.USER : User.Role.valueOf(req.role());
        User u = User.builder()
                .id(IdGenerator.userId())
                .username(req.username())
                .passwordHash(passwordEncoder.encode(req.password()))
                .role(role)
                .enabled(true)
                .createdAt(Instant.now())
                .createdBy(currentUser.idOrThrow())
                .build();
        userStore.save(u);
        audit.log("user.create", Map.of(
                "actor", currentUser.idOrThrow(),
                "userId", u.getId(),
                "username", u.getUsername(),
                "role", u.getRole().name()));
        return AuthController.profile(u);
    }

    @PatchMapping("/{id}")
    public Map<String, Object> update(@PathVariable String id, @RequestBody UpdateUserRequest req) {
        User u = userStore.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "user not found"));
        if (req.enabled() != null) u.setEnabled(req.enabled());
        if (req.role() != null) u.setRole(User.Role.valueOf(req.role()));
        if (req.password() != null && !req.password().isBlank()) {
            passwordPolicy.check(u.getUsername(), req.password());
            u.setPasswordHash(passwordEncoder.encode(req.password()));
        }
        u.setUpdatedAt(Instant.now());
        userStore.save(u);
        audit.log("user.update", Map.of(
                "actor", currentUser.idOrThrow(),
                "userId", id));
        return AuthController.profile(u);
    }

    @DeleteMapping("/{id}")
    public Map<String, String> delete(@PathVariable String id) {
        if (id.equals(currentUser.idOrThrow())) {
            throw new BusinessException(ErrorCode.CONFLICT, "cannot delete yourself");
        }
        userStore.findById(id).orElseThrow(() ->
                new BusinessException(ErrorCode.NOT_FOUND, "user not found"));
        userStore.delete(id);
        audit.log("user.delete", Map.of(
                "actor", currentUser.idOrThrow(),
                "userId", id));
        return Map.of("status", "deleted");
    }

    public record CreateUserRequest(
            @NotBlank @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9_-]{2,31}$") String username,
            @NotBlank String password,
            String role
    ) {}

    public record UpdateUserRequest(
            String role,
            Boolean enabled,
            String password
    ) {}
}
