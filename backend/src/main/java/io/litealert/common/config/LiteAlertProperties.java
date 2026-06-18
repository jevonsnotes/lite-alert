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
    private final Bootstrap bootstrap = new Bootstrap();

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
        private int publicMaxBodySize = 32 * 1024;
        private boolean allowUserPublicTopic = false;
        private final RateLimit rateLimit = new RateLimit();

        @Data
        public static class RateLimit {
            private int perTopicPerMinute = 60;
            private int perContactPerHour = 30;
            private int publicPerMinute = 30;
            private int publicPerIpPerMinute = 10;
        }
    }

    @Data
    public static class Bootstrap {
        private final Admin admin = new Admin();

        @Data
        public static class Admin {
            @NotBlank private String username;
            /** BCrypt hash, optionally wrapped with Jasypt {@code ENC(...)}. */
            @NotBlank private String password;
        }
    }
}
