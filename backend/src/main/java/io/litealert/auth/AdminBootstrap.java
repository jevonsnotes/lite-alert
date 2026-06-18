package io.litealert.auth;

import io.litealert.auth.domain.User;
import io.litealert.auth.domain.UserStore;
import io.litealert.common.config.LiteAlertProperties;
import io.litealert.common.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Bootstraps the initial admin from {@code lite-alert.bootstrap.admin}
 * the first time the app sees an empty user store.
 *
 * <p>The {@code password} property is expected to be a BCrypt hash; in dev
 * profile we accept a plain hash, in prod it should be wrapped as
 * {@code ENC(...)} so the on-disk YAML never carries a usable hash.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class AdminBootstrap {

    private final LiteAlertProperties props;
    private final UserStore userStore;
    private final PasswordEncoder passwordEncoder;

    @Bean
    @Order(100)
    public ApplicationRunner adminBootstrapRunner() {
        return args -> {
            if (userStore.hasAnyAdmin()) {
                log.info("admin already present, skip bootstrap");
                return;
            }
            String username = props.getBootstrap().getAdmin().getUsername();
            String password = props.getBootstrap().getAdmin().getPassword();
            String hash = passwordEncoder.encode(md5(password));
            User admin = User.builder()
                    .id(IdGenerator.userId())
                    .username(username)
                    .passwordHash(hash)
                    .role(User.Role.ADMIN)
                    .enabled(true)
                    .createdAt(Instant.now())
                    .createdBy("system")
                    .build();
            userStore.save(admin);
            log.info("bootstrapped admin user '{}'", username);
        };
    }

    private String md5(String raw) {
        return raw != null && raw.matches("^[a-fA-F0-9]{32}$")
                ? raw.toLowerCase()
                : DigestUtils.md5DigestAsHex(String.valueOf(raw).getBytes(StandardCharsets.UTF_8));
    }
}
