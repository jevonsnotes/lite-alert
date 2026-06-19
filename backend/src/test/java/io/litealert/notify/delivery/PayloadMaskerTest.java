package io.litealert.notify.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.litealert.auth.domain.User;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PayloadMaskerTest {

    @Test
    void returnsFullPayloadForAdminOrPermissionHolderAndMaskedPayloadForOthers() throws Exception {
        PayloadMasker masker = new PayloadMasker(new ObjectMapper());
        String payload = "{\"username\":\"demo\",\"password\":\"secret\",\"nested\":{\"token\":\"abc\"}}";
        User admin = User.builder().role(User.Role.ADMIN).permissions(Set.of()).build();
        User viewer = User.builder().role(User.Role.USER).permissions(Set.of(User.Permission.DELIVERY_PAYLOAD_READ)).build();
        User normal = User.builder().role(User.Role.USER).permissions(Set.of()).build();

        assertThat(masker.view(payload, admin)).contains("secret").contains("abc");
        assertThat(masker.view(payload, viewer)).contains("secret").contains("abc");
        assertThat(masker.view(payload, normal))
                .contains("\"password\":\"***\"")
                .contains("\"token\":\"***\"")
                .doesNotContain("secret")
                .doesNotContain("abc");
    }
}
