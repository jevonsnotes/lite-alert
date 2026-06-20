package io.litealert.auth;

import io.litealert.auth.domain.User;
import io.litealert.auth.permission.Permissions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionModelContractTest {

    @Test
    void userModelDoesNotExposeBaseRoleOrDirectUserPermissions() {
        Set<String> fieldNames = Arrays.stream(User.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .map(field -> field.getName())
                .collect(Collectors.toSet());
        Set<String> nestedTypes = Arrays.stream(User.class.getDeclaredClasses())
                .filter(type -> !type.isSynthetic())
                .map(Class::getSimpleName)
                .collect(Collectors.toSet());

        assertThat(fieldNames).doesNotContain("role", "permissions");
        assertThat(nestedTypes).doesNotContain("Role", "Permission");
    }

    @Test
    void currentUserOnlyResolvesAuthenticatedIdentity() {
        Set<String> publicMethods = Arrays.stream(CurrentUser.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(method -> method.getName())
                .collect(Collectors.toSet());

        assertThat(publicMethods)
                .contains("idOrThrow", "getOrThrow")
                .doesNotContain("isAdmin", "requireAdmin", "authoritiesFor");
    }

    @Test
    void allFormerAdminCapabilitiesAreRepresentedAsPermissions() {
        assertThat(Permissions.ALL).contains(
                "STATS_VIEW",
                "SYSTEM_HEALTH_VIEW",
                "MAIL_CONFIG_VIEW",
                "MAIL_CONFIG_UPDATE",
                "SMTP_TEST",
                "NAMESPACE_VIEW_ALL",
                "TOPIC_VIEW_ALL",
                "APIKEY_VIEW_ALL",
                "CONTACT_VIEW_ALL",
                "AUDIT_VIEW_ALL"
        );
    }
}
