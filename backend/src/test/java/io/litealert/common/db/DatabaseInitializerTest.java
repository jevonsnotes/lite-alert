package io.litealert.common.db;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "lite-alert.database.type=h2",
        "spring.datasource.url=jdbc:h2:mem:litealert_init_test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "lite-alert.jwt.secret=01234567890123456789012345678901",
        "lite-alert.apikey.pepper=01234567890123456789012345678901",
        "lite-alert.bootstrap.admin.username=admin",
        "lite-alert.bootstrap.admin.password=admin123"
})
class DatabaseInitializerTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void initializesCoreTablesAndDefaultAdmin() {
        Integer tableCount = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where table_name = 'LA_USER'",
                Integer.class);
        Integer adminCount = jdbcTemplate.queryForObject(
                "select count(*) from la_user where username = 'admin'",
                Integer.class);

        assertThat(tableCount).isEqualTo(1);
        assertThat(adminCount).isEqualTo(1);
    }
}
