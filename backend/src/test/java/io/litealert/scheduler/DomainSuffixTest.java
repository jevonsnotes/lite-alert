package io.litealert.scheduler;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DomainSuffixTest {

    @Test
    void matchesExactAndSubdomain() {
        assertThat(DomainSuffix.matchesAny("host.internal.corp", List.of("internal.corp"))).isTrue();
        assertThat(DomainSuffix.matchesAny("internal.corp", List.of("internal.corp"))).isTrue();
    }

    @Test
    void doesNotMatchPartialLabel() {
        // notinternal.corp must NOT match internal.corp (no dot boundary)
        assertThat(DomainSuffix.matchesAny("notinternal.corp", List.of("internal.corp"))).isFalse();
    }

    @Test
    void caseInsensitiveAndLeadingDotStripped() {
        assertThat(DomainSuffix.matchesAny("Host.Internal.CORP", List.of(".internal.corp"))).isTrue();
    }

    @Test
    void nullOrEmptyInputsDoNotMatch() {
        assertThat(DomainSuffix.matchesAny(null, List.of("x"))).isFalse();
        assertThat(DomainSuffix.matchesAny("host", List.of())).isFalse();
        assertThat(DomainSuffix.matchesAny("host", List.of("", "  "))).isFalse();
    }

    @Test
    void isValidChecksPlausibility() {
        assertThat(DomainSuffix.isValid("internal.corp")).isTrue();
        assertThat(DomainSuffix.isValid(".internal.corp")).isTrue();
        assertThat(DomainSuffix.isValid("")).isFalse();
        assertThat(DomainSuffix.isValid("has space.corp")).isFalse();
        assertThat(DomainSuffix.isValid(null)).isFalse();
    }
}
