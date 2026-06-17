package io.litealert.common.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Activates {@link LiteAlertProperties} so {@code lite-alert.*} keys
 * are bound and validated at startup.
 */
@Configuration
@EnableConfigurationProperties(LiteAlertProperties.class)
public class PropertiesConfig {
}
