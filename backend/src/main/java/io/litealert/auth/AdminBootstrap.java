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
import org.springframework.security.crypto.bcrypt.BCrypt;

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

    @Bean
    public ApplicationRunner adminBootstrapRunner() {
        return args -> {
            if (userStore.hasAnyAdmin()) {
                log.info("admin already present, skip bootstrap");
                return;
            }
            String username = props.getBootstrap().getAdmin().getUsername();
            String hash = props.getBootstrap().getAdmin().getPassword();
            if (!hash.startsWith("$2")) {
                log.warn("bootstrap admin password does not look like a bcrypt hash; "
                        + "treating it as a raw password and hashing it now (dev only).");
                hash = BCrypt.hashpw(hash, BCrypt.gensalt(10));
            }
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
}
