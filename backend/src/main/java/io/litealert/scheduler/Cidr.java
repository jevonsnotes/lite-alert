package io.litealert.scheduler;

import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;

/**
 * Immutable parsed CIDR with an {@code contains(InetAddress)} membership test.
 *
 * <p>IPv4 uses {@link BigInteger} arithmetic on a 32-bit space; IPv6 on a 128-bit space. IPv6 is
 * supported for the loopback ({@code ::1/128}) and ULA ({@code fc00::/7}) ranges used by the
 * default config; arbitrary IPv6 CIDRs parse correctly too.
 *
 * <p>Parse failures yield {@link Optional#empty()} rather than throwing, so the caller (settings
 * normalization / guard cache) can drop invalid entries without aborting a save.
 */
public final class Cidr {

    private final boolean ipv6;
    private final BigInteger network;
    private final BigInteger mask;

    private Cidr(boolean ipv6, BigInteger network, BigInteger mask) {
        this.ipv6 = ipv6;
        this.network = network;
        this.mask = mask;
    }

    /** Parse {@code "10.0.0.0/8"} or {@code "2001:db8::/32"}. Empty if the value is invalid. */
    public static Optional<Cidr> parse(String cidr) {
        if (cidr == null) return Optional.empty();
        String s = cidr.trim();
        if (s.isEmpty()) return Optional.empty();
        int slash = s.indexOf('/');
        String addrPart = slash < 0 ? s : s.substring(0, slash);
        String prefixPart = slash < 0 ? (s.contains(":") ? "128" : "32") : s.substring(slash + 1);
        try {
            int prefix = Integer.parseInt(prefixPart.trim());
            InetAddress addr = InetAddress.getByName(addrPart);
            boolean v6 = addr instanceof Inet6Address;
            int bits = v6 ? 128 : 32;
            if (prefix < 0 || prefix > bits) return Optional.empty();
            BigInteger value = new BigInteger(1, addr.getAddress());
            BigInteger mask = prefix == 0 ? BigInteger.ZERO
                    : BigInteger.valueOf(-1).shiftLeft(bits - prefix).and(BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE));
            return Optional.of(new Cidr(v6, value.and(mask), mask));
        } catch (NumberFormatException | UnknownHostException e) {
            return Optional.empty();
        }
    }

    /** True if {@code addr} is within this network. IPv4 CIDRs never contain IPv6 addresses (and vice versa). */
    public boolean contains(InetAddress addr) {
        boolean v6 = addr instanceof Inet6Address;
        if (v6 != ipv6) return false;
        BigInteger value = new BigInteger(1, addr.getAddress());
        return value.and(mask).equals(network);
    }

    /** True if {@code cidr} looks like a valid CIDR (host part also acceptable). */
    public static boolean isValid(String cidr) {
        return parse(cidr).isPresent();
    }
}
