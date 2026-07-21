package io.litealert.scheduler;

import io.litealert.admin.settings.SystemSettings;
import io.litealert.admin.settings.SystemSettingsService;
import io.litealert.common.error.BusinessException;
import io.litealert.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CidrTaskTargetGuardTest {

    private SystemSettingsService settings;
    private CidrTaskTargetGuard guard;
    private SystemSettings.TaskTargetGuardConfig cfg;

    @BeforeEach
    void setUp() {
        settings = mock(SystemSettingsService.class);
        guard = new CidrTaskTargetGuard(settings);
        SystemSettings s = new SystemSettings();
        cfg = s.getTaskTargetGuard();
        when(settings.current()).thenReturn(s);
    }

    @Test
    void disabledPermitsAll() {
        cfg.setEnabled(false);
        assertThatCode(() -> guard.check("10.0.0.5", 3306)).doesNotThrowAnyException();
    }

    @Test
    void blocksPrivateIp() {
        cfg.setEnabled(true);
        assertThatThrownBy(() -> guard.check("10.0.0.5", 3306))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(ErrorCode.TARGET_BLOCKED));
    }

    @Test
    void blocksCloudMetadata() {
        cfg.setEnabled(true);
        assertThatThrownBy(() -> guard.check("169.254.169.254", 80))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void allowsPublicIp() {
        cfg.setEnabled(true);
        // 8.8.8.8 is not in any default blocked range
        assertThatCode(() -> guard.check("8.8.8.8", 53)).doesNotThrowAnyException();
    }

    @Test
    void allowedCidrOverridesBlocked() {
        cfg.setEnabled(true);
        cfg.setAllowedCidrs(List.of("10.0.0.5/32"));
        assertThatCode(() -> guard.check("10.0.0.5", 3306)).doesNotThrowAnyException();
        // a different private IP is still blocked
        assertThatThrownBy(() -> guard.check("10.0.0.6", 3306)).isInstanceOf(BusinessException.class);
    }

    @Test
    void blocksBlockedDomainSuffix() {
        cfg.setEnabled(true);
        cfg.setBlockedDomains(List.of("internal.corp"));
        assertThatThrownBy(() -> guard.check("host.internal.corp", 443))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void doesNotBlockPartialLabelDomain() {
        cfg.setEnabled(true);
        cfg.setBlockedDomains(List.of("internal.corp"));
        assertThatCode(() -> guard.check("notinternal.corp", 443)).doesNotThrowAnyException();
    }

    @Test
    void unresolvedHostNotBlocked() {
        cfg.setEnabled(true);
        // an unresolvable hostname should not be a guard concern - executor handles connect failure
        assertThatCode(() -> guard.check("nonexistent.invalid.domain.example", 80)).doesNotThrowAnyException();
    }

    @Test
    void blocksIpv4MappedCloudMetadata() {
        // ::ffff:169.254.169.254 is the cloud-metadata IP wrapped in IPv6; must not bypass the
        // IPv4 169.254.0.0/16 default rule via address-family mismatch.
        cfg.setEnabled(true);
        assertThatThrownBy(() -> guard.check("::ffff:169.254.169.254", 80))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void blocksIpv4MappedPrivateRange() {
        cfg.setEnabled(true);
        assertThatThrownBy(() -> guard.check("::ffff:10.0.0.5", 3306))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void blocksNativeIpv6LinkLocal() {
        cfg.setEnabled(true);
        assertThatThrownBy(() -> guard.check("fe80::1", 80))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void blocksNativeIpv6Loopback() {
        cfg.setEnabled(true);
        assertThatThrownBy(() -> guard.check("::1", 80))
                .isInstanceOf(BusinessException.class);
    }
}
