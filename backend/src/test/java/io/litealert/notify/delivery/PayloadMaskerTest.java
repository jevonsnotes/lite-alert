package io.litealert.notify.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.litealert.admin.settings.SystemSettings;
import io.litealert.admin.settings.SystemSettingsService;
import io.litealert.auth.domain.User;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PayloadMaskerTest {

    @Test
    void returnsFullPayloadOnlyForPermissionHolderAndMaskedPayloadForOthers() throws Exception {
        SystemSettingsService settings = mock(SystemSettingsService.class);
        when(settings.current()).thenReturn(new SystemSettings());
        PayloadMasker masker = new PayloadMasker(new ObjectMapper(), settings);
        String payload = "{\"username\":\"demo\",\"password\":\"secret\",\"nested\":{\"token\":\"abc\"}}";
        User adminWithoutPayloadPermission = User.builder().role(User.Role.ADMIN).permissions(Set.of()).build();
        User viewer = User.builder().role(User.Role.USER).permissions(Set.of(User.Permission.DELIVERY_PAYLOAD_READ)).build();
        User normal = User.builder().role(User.Role.USER).permissions(Set.of()).build();

        assertThat(masker.view(payload, adminWithoutPayloadPermission))
                .contains("\"password\":\"***\"")
                .contains("\"token\":\"***\"")
                .doesNotContain("secret")
                .doesNotContain("abc");
        assertThat(masker.view(payload, viewer)).contains("secret").contains("abc");
        assertThat(masker.view(payload, normal))
                .contains("\"password\":\"***\"")
                .contains("\"token\":\"***\"")
                .doesNotContain("secret")
                .doesNotContain("abc");
    }
}
