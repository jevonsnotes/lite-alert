package io.litealert;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        "lite-alert.database.type=h2",
        "spring.datasource.url=jdbc:h2:mem:app_test_unique;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "lite-alert.jwt.secret=01234567890123456789012345678901",
        "lite-alert.apikey.pepper=01234567890123456789012345678901"
})
class LiteAlertApplicationTests {

    @Test
    void contextLoads() {
        // sanity check: Spring context boots with default config
    }
}
