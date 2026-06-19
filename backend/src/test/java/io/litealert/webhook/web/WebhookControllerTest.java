package io.litealert.webhook.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.litealert.webhook.WebhookService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebhookControllerTest {

    @Test
    void receiveReturnsOkWithSummaryOnly() throws Exception {
        WebhookService service = mock(WebhookService.class);
        when(service.handle("orders", "paid", null, null, new ObjectMapper().readTree("{}"), "127.0.0.1"))
                .thenReturn(Map.of("accepted", true, "traceId", "tr_1", "deliveryCount", 2));
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn("127.0.0.1");

        ResponseEntity<Map<String, Object>> res = new WebhookController(service)
                .receive("orders", "paid", null, null, new ObjectMapper().readTree("{}"), req);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).containsEntry("accepted", true)
                .containsEntry("traceId", "tr_1")
                .containsEntry("deliveryCount", 2)
                .doesNotContainKey("deliveries");
    }
}
