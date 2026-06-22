package io.litealert.common.db;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.litealert.auth.permission.Permissions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import java.util.LinkedHashSet;
import java.util.Set;

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

    @Autowired
    private ObjectMapper objectMapper;

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

    @Test
    void initializesBuiltinRolesWithExpectedPermissions() throws Exception {
        Set<String> superAdminPermissions = permissionsOf("r_super_admin");
        Set<String> normalUserPermissions = permissionsOf("r_normal_user");

        assertThat(superAdminPermissions).containsExactlyInAnyOrderElementsOf(Permissions.ALL);
        assertThat(normalUserPermissions)
                .contains("DASHBOARD_VIEW", "STATS_VIEW", "NAMESPACE_VIEW", "TOPIC_VIEW", "APIKEY_VIEW", "CONTACT_VIEW", "AUDIT_VIEW")
                .doesNotContain("STATS_VIEW_ALL", "NAMESPACE_VIEW_ALL", "TOPIC_VIEW_ALL", "APIKEY_VIEW_ALL", "CONTACT_VIEW_ALL", "AUDIT_VIEW_ALL")
                .doesNotContain("USER_VIEW", "USER_CREATE", "USER_UPDATE", "USER_DELETE")
                .doesNotContain("ROLE_VIEW", "ROLE_CREATE", "ROLE_UPDATE", "ROLE_DELETE")
                .doesNotContain("SYSTEM_HEALTH_VIEW", "SYSTEM_SETTINGS_VIEW", "SYSTEM_SETTINGS_UPDATE")
                .doesNotContain("MAIL_CONFIG_VIEW", "MAIL_CONFIG_UPDATE", "SMTP_TEST");
        assertThat(normalUserPermissions).containsExactlyInAnyOrderElementsOf(
                Permissions.ALL.stream()
                        .filter(p -> !p.endsWith("_ALL"))
                        .filter(p -> !p.startsWith("USER_"))
                        .filter(p -> !p.startsWith("ROLE_"))
                        .filter(p -> !p.startsWith("SYSTEM_"))
                        .filter(p -> !p.startsWith("MAIL_CONFIG_"))
                        .filter(p -> !p.equals("SMTP_TEST"))
                        .toList());
    }

    @Test
    void assignsSuperAdminRoleToDefaultAdmin() {
        Integer assignmentCount = jdbcTemplate.queryForObject(
                "select count(*) from la_user_role where user_id = 'u_admin' and role_id = 'r_super_admin'",
                Integer.class);

        assertThat(assignmentCount).isEqualTo(1);
    }

    private Set<String> permissionsOf(String roleId) throws Exception {
        String json = jdbcTemplate.queryForObject(
                "select permissions_json from la_role where id = ?",
                String.class,
                roleId);
        return objectMapper.readValue(json, new TypeReference<LinkedHashSet<String>>() {});
    }
}
