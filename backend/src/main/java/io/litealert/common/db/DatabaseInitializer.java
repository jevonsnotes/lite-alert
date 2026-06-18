package io.litealert.common.db;

import io.litealert.auth.domain.User;
import io.litealert.common.config.LiteAlertProperties;
import io.litealert.common.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;

/** Initializes the relational schema when a fresh database is detected. */
@Slf4j
@Component
@Order(0)
@RequiredArgsConstructor
public class DatabaseInitializer implements ApplicationRunner {

    private static final int SCHEMA_VERSION = 1;

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;
    private final LiteAlertProperties props;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (schemaExists()) return;

        String type = normalizeType(props.getDatabase().getType());
        ClassPathResource schema = new ClassPathResource("db/schema-" + type + ".sql");
        if (!schema.exists()) {
            throw new IllegalStateException("database schema not found for type: " + type);
        }
        try (Connection c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, schema);
        }
        jdbcTemplate.update("insert into la_schema_version(version, initialized_at) values (?, ?)",
                SCHEMA_VERSION, Timestamp.from(Instant.now()));
        bootstrapAdmin();
        log.info("initialized database schema type={} version={}", type, SCHEMA_VERSION);
    }

    private boolean schemaExists() {
        try {
            Integer count = jdbcTemplate.queryForObject("select count(*) from la_schema_version", Integer.class);
            return count != null && count > 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void bootstrapAdmin() {
        String username = props.getBootstrap().getAdmin().getUsername();
        Integer exists = jdbcTemplate.queryForObject(
                "select count(*) from la_user where username = ?", Integer.class, username);
        if (exists != null && exists > 0) return;
        jdbcTemplate.update("insert into la_user(id, username, password_hash, role, enabled, created_at, created_by) values (?, ?, ?, ?, ?, ?, ?)",
                IdGenerator.userId(),
                username,
                passwordEncoder.encode(md5(props.getBootstrap().getAdmin().getPassword())),
                User.Role.ADMIN.name(),
                true,
                Timestamp.from(Instant.now()),
                "system");
    }

    private String normalizeType(String raw) {
        String type = raw == null || raw.isBlank() ? "h2" : raw.trim().toLowerCase();
        if ("postgres".equals(type)) return "postgresql";
        if ("oceanbase".equals(type) || "oceandb".equals(type)) return "oceanbase";
        return type;
    }

    private String md5(String raw) {
        return raw != null && raw.matches("^[a-fA-F0-9]{32}$")
                ? raw.toLowerCase()
                : DigestUtils.md5DigestAsHex(String.valueOf(raw).getBytes(StandardCharsets.UTF_8));
    }
}
