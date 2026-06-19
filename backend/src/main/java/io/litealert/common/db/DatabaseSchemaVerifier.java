package io.litealert.common.db;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@Order(0)
@RequiredArgsConstructor
public class DatabaseSchemaVerifier implements ApplicationRunner {

    private static final List<String> REQUIRED_TABLES = List.of("LA_USER", "LA_NOTIFY_DELIVERY");

    private final JdbcTemplate jdbc;

    @Override
    public void run(ApplicationArguments args) {
        List<String> missing = REQUIRED_TABLES.stream()
                .filter(table -> !tableExists(table))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("database schema is not initialized; missing tables " + missing
                    + ". Flyway migrations did not run. Reload Maven dependencies / rebuild the application and check spring.flyway.enabled and spring.flyway.locations.");
        }
        log.info("database schema verified; required tables present={}", REQUIRED_TABLES);
    }

    private boolean tableExists(String tableName) {
        try {
            Integer count = jdbc.queryForObject(
                    "select count(*) from information_schema.tables where upper(table_name) = upper(?)",
                    Integer.class,
                    tableName);
            return count != null && count > 0;
        } catch (Exception ignored) {
            return false;
        }
    }
}
