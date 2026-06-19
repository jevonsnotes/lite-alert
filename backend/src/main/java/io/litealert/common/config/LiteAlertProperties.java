package io.litealert.common.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Strongly typed view of {@code lite-alert.*} properties in application.yml.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "lite-alert")
public class LiteAlertProperties {

    /** Root data directory (file storage). */
    @NotBlank
    private String dataDir;

    private final Jwt jwt = new Jwt();
    private final Database database = new Database();
    private final ApiKey apikey = new ApiKey();
    private final Webhook webhook = new Webhook();

    @Data
    public static class Jwt {
        @NotBlank private String secret;
        private long ttlSeconds = 28_800;
    }

    @Data
    public static class Database {
        private String type = "h2";
    }

    @Data
    public static class ApiKey {
        @NotBlank private String pepper;
    }

    @Data
    public static class Webhook {
        private int maxBodySize = 64 * 1024;
    }
}
