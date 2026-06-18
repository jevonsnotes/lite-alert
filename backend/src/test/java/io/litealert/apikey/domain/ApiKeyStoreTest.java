package io.litealert.apikey.domain;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "lite-alert.database.type=h2",
        "spring.datasource.url=jdbc:h2:mem:apikey_store_test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "lite-alert.jwt.secret=01234567890123456789012345678901",
        "lite-alert.apikey.pepper=01234567890123456789012345678901",
        "lite-alert.bootstrap.admin.username=admin",
        "lite-alert.bootstrap.admin.password=admin123"
})
class ApiKeyStoreTest {

    @Autowired
    private ApiKeyStore store;

    @Test
    void savingSameKeyRepeatedlyKeepsSingleOwnerRecord() {
        ApiKey key = ApiKey.builder()
                .id("ak_1")
                .ownerId("u_1")
                .name("prod")
                .prefix("la_old1")
                .keyHash("hash")
                .status(ApiKey.Status.ACTIVE)
                .build();

        store.save(key);
        key.setPrefix("la_new1");
        store.save(key);
        key.setUsageCount(12);
        store.save(key);

        assertThat(store.findByOwner("u_1"))
                .extracting(ApiKey::getId)
                .containsExactly("ak_1");
        assertThat(store.findById("ak_1").orElseThrow().getUsageCount()).isEqualTo(12);
    }
}
