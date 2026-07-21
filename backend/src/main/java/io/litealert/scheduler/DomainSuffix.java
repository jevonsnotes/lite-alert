package io.litealert.scheduler;

import java.util.List;

/**
 * Hostname-suffix matching for the outbound target guard (design D2/Q2). A suffix rule matches a
 * host when the host equals it, or ends with it preceded by a dot, e.g. rule {@code internal.corp}
 * matches {@code host.internal.corp} and {@code internal.corp} but not {@code notinternal.corp}.
 *
 * <p>Rules and hosts are compared case-insensitively. Leading dots on a rule are stripped.
 */
public final class DomainSuffix {

    private DomainSuffix() {}

    /** True if {@code host} matches any suffix rule in {@code suffixes}. */
    public static boolean matchesAny(String host, List<String> suffixes) {
        if (host == null || suffixes == null || suffixes.isEmpty()) return false;
        String h = host.toLowerCase();
        for (String raw : suffixes) {
            if (raw == null) continue;
            String s = raw.trim().toLowerCase();
            if (s.isEmpty()) continue;
            while (s.startsWith(".")) s = s.substring(1);
            if (s.isEmpty()) continue;
            if (h.equals(s) || h.endsWith("." + s)) return true;
        }
        return false;
    }

    /** True if {@code suffix} is a plausible hostname suffix (non-blank, no spaces). */
    public static boolean isValid(String suffix) {
        if (suffix == null) return false;
        String s = suffix.trim();
        if (s.isEmpty()) return false;
        return !s.contains(" ");
    }
}
