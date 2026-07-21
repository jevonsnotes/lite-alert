package io.litealert.scheduler;

import io.litealert.admin.settings.SystemSettings;
import io.litealert.admin.settings.SystemSettingsService;
import io.litealert.common.error.BusinessException;
import io.litealert.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Real {@link TaskTargetGuard} backed by {@link SystemSettingsService} (design D2). When enabled,
 * resolves the host to all its IPs and rejects any IP that matches a blocked CIDR (unless it also
 * matches an allowed CIDR); also rejects hostnames matching a blocked domain suffix. Disabled by
 * default (permits all).
 *
 * <p>{@link Primary @Primary} wins the autowire candidate over {@link AllowAllTaskTargetGuard}, so
 * this guard takes over with zero wiring change once present. The {@code Cidr} list is cached by a
 * fingerprint of the configured CIDR strings, so a steady-state probe does not re-parse on every
 * check.
 */
@Slf4j
@Component
@Primary
@RequiredArgsConstructor
public class CidrTaskTargetGuard implements TaskTargetGuard {

    private final SystemSettingsService settings;

    /** Cached parsed CIDRs + the fingerprint of the source strings they were built from. */
    private volatile CachedCidrs blocked = CachedCidrs.empty();
    private volatile CachedCidrs allowed = CachedCidrs.empty();

    @Override
    public void check(String host, int port) {
        SystemSettings.TaskTargetGuardConfig cfg = settings.current().getTaskTargetGuard();
        if (cfg == null || !cfg.isEnabled()) return;

        // domain-suffix rule: checked against the unresolved hostname
        if (DomainSuffix.matchesAny(host, cfg.getBlockedDomains())) {
            throw blocked(host, port, "domain:" + first(cfg.getBlockedDomains()));
        }

        List<Cidr> blockedCidrs = resolveCidrs(cfg.getBlockedCidrs(), true);
        List<Cidr> allowedCidrs = resolveCidrs(cfg.getAllowedCidrs(), false);

        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch (java.net.UnknownHostException e) {
            // unresolved host is not a guard concern -> let the executor report connect failure
            return;
        }
        for (InetAddress addr : addrs) {
            // check the address as-is, plus its IPv4-mapped IPv6 form if applicable, so that
            // ::ffff:169.254.169.254 cannot bypass the IPv4 blocked rules (design risk mitigation).
            if (matchesAny(addr, allowedCidrs)) continue;
            InetAddress mapped = ipv4Mapped(addr);
            if (mapped != null && matchesAny(mapped, allowedCidrs)) continue;
            for (Cidr c : blockedCidrs) {
                if (c.contains(addr) || (mapped != null && c.contains(mapped))) {
                    throw blocked(host, port, cidrLabel(c, cfg.getBlockedCidrs(), addr));
                }
            }
        }
    }

    private BusinessException blocked(String host, int port, String reason) {
        return new BusinessException(ErrorCode.TARGET_BLOCKED,
                "出站目标被拦截: " + host + ":" + port + " (命中 " + reason + ")");
    }

    private boolean matchesAny(InetAddress addr, List<Cidr> cidrs) {
        for (Cidr c : cidrs) if (c.contains(addr)) return true;
        return false;
    }

    /** Resolve the cache, rebuilding only when the source CIDR strings change. */
    private List<Cidr> resolveCidrs(List<String> raw, boolean isBlocked) {
        String fingerprint = raw == null ? "" : String.join("|", raw);
        if (isBlocked) {
            if (!Objects.equals(blocked.fingerprint, fingerprint)) {
                blocked = CachedCidrs.of(fingerprint, parseAll(raw));
            }
            return blocked.cidrs;
        } else {
            if (!Objects.equals(allowed.fingerprint, fingerprint)) {
                allowed = CachedCidrs.of(fingerprint, parseAll(raw));
            }
            return allowed.cidrs;
        }
    }

    private List<Cidr> parseAll(List<String> raw) {
        List<Cidr> out = new ArrayList<>();
        if (raw == null) return out;
        for (String c : raw) {
            Cidr.parse(c).ifPresent(out::add);
        }
        return out;
    }

    /** Best-effort label for the matched CIDR (uses the original string form). */
    private String cidrLabel(Cidr matched, List<String> raw, InetAddress addr) {
        if (raw != null) {
            for (String s : raw) {
                Cidr parsed = Cidr.parse(s).orElse(null);
                if (parsed != null && parsed.contains(addr)) return s;
            }
        }
        return "cidr";
    }

    /**
     * If {@code addr} is an IPv4-mapped IPv6 address ({@code ::ffff:a.b.c.d}), return the equivalent
     * IPv4 {@link InetAddress}; otherwise {@code null}. This lets an IPv4 CIDR rule catch a target
     * that disguises itself in IPv6 clothing.
     */
    private InetAddress ipv4Mapped(InetAddress addr) {
        if (!(addr instanceof java.net.Inet6Address)) return null;
        byte[] b = addr.getAddress();
        // IPv4-mapped: first 10 bytes 0, next 2 bytes 0xff, last 4 are the IPv4 addr
        for (int i = 0; i < 10; i++) if (b[i] != 0) return null;
        if (b[10] != 0 || b[11] != -1) return null;  // 0xff == -1 as signed byte
        try {
            return InetAddress.getByAddress(new byte[]{b[12], b[13], b[14], b[15]});
        } catch (java.net.UnknownHostException e) {
            return null;
        }
    }

    private String first(List<String> list) {
        return (list == null || list.isEmpty()) ? "?" : list.get(0);
    }

    private record CachedCidrs(String fingerprint, List<Cidr> cidrs) {
        static CachedCidrs empty() { return new CachedCidrs("", List.of()); }
        static CachedCidrs of(String fingerprint, List<Cidr> cidrs) { return new CachedCidrs(fingerprint, cidrs); }
    }
}
