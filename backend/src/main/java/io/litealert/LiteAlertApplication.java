package io.litealert;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

/**
 * Lite-Alert: lightweight, file-encrypted, all-in-one notification service.
 *
 * <p>Frontend (Vue 3 + Element Plus) is bundled into {@code resources/static}
 * by the Maven build, so a single JAR serves both the management UI and the
 * webhook receiving endpoints.
 *
 * <p>MyBatis-Flex MapperScan is configured here to scan only the domain
 * sub-packages, avoiding overlap with the starter's own auto-configuration.
 */
@SpringBootApplication(exclude = { UserDetailsServiceAutoConfiguration.class })
@MapperScan("io.litealert.*.domain")
public class LiteAlertApplication {

    public static void main(String[] args) {
        SpringApplication.run(LiteAlertApplication.class, args);
    }
}

