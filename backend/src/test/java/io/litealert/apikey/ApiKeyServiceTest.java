package io.litealert.apikey;

import io.litealert.apikey.domain.ApiKey;
import io.litealert.apikey.domain.ApiKeyStore;
import io.litealert.auth.CurrentUser;
import io.litealert.auth.permission.PermissionService;
import io.litealert.common.audit.AuditLogger;
import io.litealert.common.config.LiteAlertProperties;
import io.litealert.namespace.NamespaceService;
import io.litealert.namespace.domain.Namespace;
import io.litealert.topic.TopicService;
import io.litealert.topic.domain.Topic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiKeyServiceTest {

    private ApiKeyStore store;
    private ApiKeyService service;

    @BeforeEach
    void setUp() {
        store = mock(ApiKeyStore.class);
        CurrentUser currentUser = mock(CurrentUser.class);
        NamespaceService namespaceService = mock(NamespaceService.class);
        TopicService topicService = mock(TopicService.class);
        AuditLogger audit = mock(AuditLogger.class);

        LiteAlertProperties props = new LiteAlertProperties();
        props.getApikey().setPepper("test-pepper-1234567890");
        ApiKeyHasher hasher = new ApiKeyHasher(props);
        hasher.init();

        PermissionService permissionService = mock(PermissionService.class);
        when(currentUser.idOrThrow()).thenReturn("u_1");
        when(namespaceService.getOrThrow("ns_1")).thenReturn(Namespace.builder()
                .id("ns_1")
                .ownerId("u_1")
                .name("ops")
                .build());
        when(topicService.getOrThrow("t_1")).thenReturn(Topic.builder()
                .id("t_1")
                .ownerId("u_1")
                .namespaceId("ns_1")
                .name("alerts")
                .build());

        when(store.save(any(ApiKey.class))).thenAnswer(invocation -> invocation.getArgument(0));
        service = new ApiKeyService(store, hasher, currentUser, namespaceService, topicService, audit, permissionService);
    }

    @Test
    void rotateGeneratesNewSecretAndKeepsEditableMetadata() {
        ApiKey.Scope scope = new ApiKey.Scope(ApiKey.ScopeType.NAMESPACE, "ns_1");
        ApiKey existing = ApiKey.builder()
                .id("ak_1")
                .ownerId("u_1")
                .name("prod")
                .prefix("la_old1")
                .keyHash("old-hash")
                .validFrom(Instant.parse("2026-01-01T00:00:00Z"))
                .validUntil(Instant.parse("2027-01-01T00:00:00Z"))
                .scopes(new ArrayList<>(List.of(scope)))
                .status(ApiKey.Status.ACTIVE)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .usageCount(42)
                .lastUsedAt(Instant.parse("2026-06-01T00:00:00Z"))
                .build();
        when(store.findById("ak_1")).thenReturn(Optional.of(existing));

        ApiKeyService.CreateResult result = service.rotate("ak_1");

        assertThat(result.fullKey()).startsWith("la_");
        assertThat(result.apiKey().getId()).isEqualTo("ak_1");
        assertThat(result.apiKey().getName()).isEqualTo("prod");
        assertThat(result.apiKey().getScopes()).containsExactly(scope);
        assertThat(result.apiKey().getValidUntil()).isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
        assertThat(result.apiKey().getPrefix()).isNotEqualTo("la_old1");
        assertThat(result.apiKey().getKeyHash()).isNotEqualTo("old-hash");
        assertThat(result.apiKey().getUsageCount()).isZero();
        assertThat(result.apiKey().getLastUsedAt()).isNull();
        assertThat(result.apiKey().getRotateCount()).isEqualTo(1);
    }
}
