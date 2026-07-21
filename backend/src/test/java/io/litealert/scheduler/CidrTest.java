package io.litealert.scheduler;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;

class CidrTest {

    @Test
    void parsesAndMatchesPrivateRange() throws Exception {
        Cidr cidr = Cidr.parse("10.0.0.0/8").orElseThrow();
        assertThat(cidr.contains(InetAddress.getByName("10.1.2.3"))).isTrue();
        assertThat(cidr.contains(InetAddress.getByName("10.255.255.255"))).isTrue();
        assertThat(cidr.contains(InetAddress.getByName("11.0.0.1"))).isFalse();
        assertThat(cidr.contains(InetAddress.getByName("9.255.255.255"))).isFalse();
    }

    @Test
    void matchesLoopbackAndLinkLocal() throws Exception {
        assertThat(Cidr.parse("127.0.0.0/8").orElseThrow().contains(InetAddress.getByName("127.0.0.1"))).isTrue();
        assertThat(Cidr.parse("169.254.0.0/16").orElseThrow().contains(InetAddress.getByName("169.254.169.254"))).isTrue();
        assertThat(Cidr.parse("169.254.0.0/16").orElseThrow().contains(InetAddress.getByName("169.255.0.1"))).isFalse();
    }

    @Test
    void matchesNetworkAndBroadcastBoundary() throws Exception {
        Cidr cidr = Cidr.parse("192.168.1.0/24").orElseThrow();
        assertThat(cidr.contains(InetAddress.getByName("192.168.1.0"))).isTrue();
        assertThat(cidr.contains(InetAddress.getByName("192.168.1.255"))).isTrue();
        assertThat(cidr.contains(InetAddress.getByName("192.168.2.0"))).isFalse();
    }

    @Test
    void matchesIpv6LoopbackAndUla() throws Exception {
        assertThat(Cidr.parse("::1/128").orElseThrow().contains(InetAddress.getByName("::1"))).isTrue();
        assertThat(Cidr.parse("::1/128").orElseThrow().contains(InetAddress.getByName("::2"))).isFalse();
        assertThat(Cidr.parse("fc00::/7").orElseThrow().contains(InetAddress.getByName("fd00::1"))).isTrue();
        assertThat(Cidr.parse("fc00::/7").orElseThrow().contains(InetAddress.getByName("fe00::1"))).isFalse();
    }

    @Test
    void ipv4CidrDoesNotContainIpv6() throws Exception {
        Cidr cidr = Cidr.parse("127.0.0.0/8").orElseThrow();
        assertThat(cidr.contains(InetAddress.getByName("::1"))).isFalse();
    }

    @Test
    void invalidCidrParsesToEmpty() {
        assertThat(Cidr.parse("not-a-cidr")).isEmpty();
        assertThat(Cidr.parse("10.0.0.0/33")).isEmpty();
        assertThat(Cidr.parse("")).isEmpty();
        assertThat(Cidr.parse(null)).isEmpty();
    }

    @Test
    void singleHostCidrMatchesOnlyThatHost() throws Exception {
        Cidr cidr = Cidr.parse("10.0.0.5/32").orElseThrow();
        assertThat(cidr.contains(InetAddress.getByName("10.0.0.5"))).isTrue();
        assertThat(cidr.contains(InetAddress.getByName("10.0.0.6"))).isFalse();
    }
}
