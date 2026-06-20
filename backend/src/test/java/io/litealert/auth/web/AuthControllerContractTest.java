package io.litealert.auth.web;

import io.litealert.auth.domain.User;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuthControllerContractTest {

    @Test
    void profileDoesNotExposeBaseRole() {
        User user = User.builder()
                .id("u_1")
                .username("alice")
                .enabled(true)
                .createdAt(Instant.EPOCH)
                .build();

        Map<String, Object> profile = AuthController.profile(user);

        assertThat(profile)
                .containsEntry("id", "u_1")
                .containsEntry("username", "alice")
                .containsEntry("enabled", true)
                .doesNotContainKey("role");
    }
}
