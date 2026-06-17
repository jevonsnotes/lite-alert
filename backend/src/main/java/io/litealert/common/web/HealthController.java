package io.litealert.common.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Minimal health endpoint used during M0 to verify the scaffold is alive.
 * Will be superseded by a richer admin/health endpoint in later milestones.
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "service", "lite-alert",
                "time", Instant.now().toString()
        );
    }
}
