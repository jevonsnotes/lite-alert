package io.litealert.apikey.domain;

import io.litealert.common.storage.FileStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ApiKeyStoreTest {

    @Test
    void savingSameKeyRepeatedlyKeepsSingleOwnerIndexEntry() {
        FileStore fileStore = mock(FileStore.class);
        ApiKeyStore store = new ApiKeyStore(fileStore);
        ApiKey key = ApiKey.builder()
                .id("ak_1")
                .ownerId("u_1")
                .name("prod")
                .prefix("la_old1")
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
    }
}
