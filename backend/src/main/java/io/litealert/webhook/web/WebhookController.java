package io.litealert.webhook.web;

import com.fasterxml.jackson.databind.JsonNode;
import io.litealert.webhook.WebhookService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/webhook")
@RequiredArgsConstructor
public class WebhookController {

    private final WebhookService service;

    @PostMapping("/{namespace}/{topic}")
    public ResponseEntity<Map<String, Object>> receive(
            @PathVariable String namespace,
            @PathVariable String topic,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "key", required = false) String queryKey,
            @RequestBody(required = false) JsonNode body,
            HttpServletRequest req) {

        Map<String, Object> ack = service.handle(
                namespace, topic, authorization, queryKey, body, clientIp(req));
        return ResponseEntity.ok(ack);
    }

    private String clientIp(HttpServletRequest req) {
        String forwarded = req.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return req.getRemoteAddr();
    }
}
