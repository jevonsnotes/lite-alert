package io.litealert.auth.web;

import io.litealert.auth.AuthService;
import io.litealert.auth.CurrentUser;
import io.litealert.auth.domain.User;
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

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest req) {
        AuthService.LoginResult r = authService.login(req.username(), req.password());
        return Map.of(
                "token", r.token(),
                "expiresAt", r.expiresAt().toString(),
                "user", profile(r.user()));
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        User me = currentUser.getOrThrow();
        return profile(me);
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    static Map<String, Object> profile(User u) {
        return Map.of(
                "id", u.getId(),
                "username", u.getUsername(),
                "role", u.getRole().name(),
                "enabled", u.isEnabled(),
                "createdAt", u.getCreatedAt() != null ? u.getCreatedAt().toString() : Instant.EPOCH.toString());
    }
}
