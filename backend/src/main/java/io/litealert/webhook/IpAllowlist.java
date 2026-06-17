package io.litealert.webhook;

import org.springframework.stereotype.Component;

/**
 * Match a remote IP against a list of CIDRs. Empty list = allow all.
 * Supports IPv4 only (the only practical case for self-hosted deployments).
 */
@Component
public class IpAllowlist {

    public boolean isAllowed(java.util.List<String> cidrs, String ip) {
        if (cidrs == null || cidrs.isEmpty()) return true;
        if (ip == null) return false;
        long target = parseIpv4(ip);
        if (target < 0) return false;
        for (String cidr : cidrs) {
            if (matches(cidr, target)) return true;
        }
        return false;
    }

    private boolean matches(String cidr, long ip) {
        int slash = cidr.indexOf('/');
        if (slash < 0) {
            long parsed = parseIpv4(cidr);
            return parsed == ip;
        }
        long base = parseIpv4(cidr.substring(0, slash));
        if (base < 0) return false;
        int prefix = Integer.parseInt(cidr.substring(slash + 1));
        long mask = prefix == 0 ? 0L : (~0L << (32 - prefix)) & 0xFFFFFFFFL;
        return (base & mask) == (ip & mask);
    }

    private long parseIpv4(String s) {
        try {
            String[] parts = s.split("\\.");
            if (parts.length != 4) return -1;
            long v = 0;
            for (String part : parts) {
                int n = Integer.parseInt(part);
                if (n < 0 || n > 255) return -1;
                v = (v << 8) | n;
            }
            return v & 0xFFFFFFFFL;
        } catch (Exception e) {
            return -1;
        }
    }
}
