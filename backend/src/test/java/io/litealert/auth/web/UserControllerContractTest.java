package io.litealert.auth.web;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class UserControllerContractTest {

    @Test
    void createUserRequestOnlyAcceptsRoleBindings() {
        Set<String> fields = Arrays.stream(UserController.CreateUserRequest.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());

        assertThat(fields)
                .contains("username", "password", "roleIds")
                .doesNotContain("role", "permissions");
    }

    @Test
    void updateUserRequestOnlyAcceptsRoleBindingsAndAccountState() {
        Set<String> fields = Arrays.stream(UserController.UpdateUserRequest.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());

        assertThat(fields)
                .contains("enabled", "password", "roleIds")
                .doesNotContain("role", "permissions");
    }
}
