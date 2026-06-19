package io.litealert.auth.web;

import io.litealert.auth.AuthService;
import io.litealert.auth.CurrentUser;
import io.litealert.auth.domain.User;
import io.litealert.auth.permission.PermissionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final CurrentUser currentUser;
    private final PermissionService permissionService;

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest req) {
        AuthService.LoginResult r = authService.login(req.username(), req.password());
        return Map.of(
                "token", r.token(),
                "expiresAt", r.expiresAt().toString(),
                "user", profileWithPermissions(r.user()));
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        User me = currentUser.getOrThrow();
        return profileWithPermissions(me);
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    private Map<String, Object> profileWithPermissions(User u) {
        Map<String, Object> m = new java.util.LinkedHashMap<>(profile(u));
        m.put("permissions", permissionService.permissions(u.getId()).stream().toList());
        return m;
    }

    static Map<String, Object> profile(User u) {
        return Map.of(
                "id", u.getId(),
                "username", u.getUsername(),
                "role", u.getRole().name(),
                "permissions", u.getPermissions() == null ? java.util.List.of() : u.getPermissions().stream().map(Enum::name).toList(),
                "enabled", u.isEnabled(),
                "createdAt", u.getCreatedAt() != null ? u.getCreatedAt().toString() : Instant.EPOCH.toString());
    }
}
