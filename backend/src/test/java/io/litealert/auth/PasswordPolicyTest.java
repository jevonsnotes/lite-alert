package io.litealert.auth;

import io.litealert.common.error.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy();

    @Test
    void rejectsPasswordMissingUppercaseLowercaseOrDigit() {
        assertThatThrownBy(() -> policy.check("alice", "lowercase1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("uppercase");
        assertThatThrownBy(() -> policy.check("alice", "UPPERCASE1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("lowercase");
        assertThatThrownBy(() -> policy.check("alice", "NoDigitsHere"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("digit");
    }

    @Test
    void acceptsPasswordWithUppercaseLowercaseAndDigit() {
        assertThatCode(() -> policy.check("alice", "SafePass1"))
                .doesNotThrowAnyException();
    }
}
