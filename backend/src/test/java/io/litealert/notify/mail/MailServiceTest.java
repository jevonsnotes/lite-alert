package io.litealert.notify.mail;

import io.litealert.common.audit.AuditLogger;
import io.litealert.common.db.DbJson;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@SpringBootTest
@TestPropertySource(properties = {
        "lite-alert.database.type=h2",
        "spring.datasource.url=jdbc:h2:mem:mail_service_test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "lite-alert.jwt.secret=01234567890123456789012345678901",
        "lite-alert.apikey.pepper=01234567890123456789012345678901",
        "lite-alert.bootstrap.admin.username=admin",
        "lite-alert.bootstrap.admin.password=admin123"
})
class MailServiceTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DbJson json;

    @Test
    void saveNormalizesFromAddressToAuthorizedUsername() {
        MailService service = new MailService(jdbc, json, mock(AuditLogger.class));
        MailConfig saved = service.save(MailConfig.builder()
                .host("smtp.example.com")
                .port(465)
                .username("notice@example.com")
                .password("secret")
                .ssl(true)
                .fromAddress("lite-alert@example.com")
                .fromName("Lite Alert")
                .build(), "u_admin");

        assertThat(saved.getFromAddress()).isEqualTo("notice@example.com");
    }
}
