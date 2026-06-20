package io.litealert.notify.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.litealert.admin.settings.SystemSettings;
import io.litealert.admin.settings.SystemSettingsService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PayloadMaskerTest {

    @Test
    void returnsFullPayloadOnlyWhenCallerCanReadFullPayload() throws Exception {
        SystemSettingsService settings = mock(SystemSettingsService.class);
        when(settings.current()).thenReturn(new SystemSettings());
        PayloadMasker masker = new PayloadMasker(new ObjectMapper(), settings);
        String payload = "{\"username\":\"demo\",\"password\":\"secret\",\"nested\":{\"token\":\"abc\"}}";

        assertThat(masker.view(payload, false))
                .contains("\"password\":\"***\"")
                .contains("\"token\":\"***\"")
                .doesNotContain("secret")
                .doesNotContain("abc");
        assertThat(masker.view(payload, true)).contains("secret").contains("abc");
    }
}
