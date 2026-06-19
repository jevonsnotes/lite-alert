package io.litealert.common.db;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "lite-alert.database.type=h2",
        "spring.datasource.url=jdbc:h2:mem:litealert_init_test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "lite-alert.jwt.secret=01234567890123456789012345678901",
        "lite-alert.apikey.pepper=01234567890123456789012345678901"
})
class DatabaseInitializerTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void initializesCoreTablesAndDefaultAdmin() {
        Integer tableCount = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where table_name = 'LA_USER'",
                Integer.class);
        Integer adminCount = jdbcTemplate.queryForObject(
                "select count(*) from la_user where username = 'admin'",
                Integer.class);
        String adminHash = jdbcTemplate.queryForObject(
                "select password_hash from la_user where username = 'admin'",
                String.class);

        assertThat(tableCount).isEqualTo(1);
        assertThat(adminCount).isEqualTo(1);
        assertThat(passwordEncoder.matches("0192023a7bbd73250516f069df18b500", adminHash)).isTrue();
    }
}
