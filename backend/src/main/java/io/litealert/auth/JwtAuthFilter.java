package io.litealert.auth;

import io.jsonwebtoken.Claims;
import io.litealert.auth.domain.User;
import io.litealert.auth.domain.UserStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Parses {@code Authorization: Bearer <jwt>} for management API requests
 * and populates the SecurityContext.
 *
 * <p>Webhook endpoints (paths under {@code /api/webhook/**}) are skipped
 * here — they have their own ApiKey-based auth flow.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserStore userStore;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String path = req.getRequestURI();
        return path.startsWith("/api/webhook/")
                || path.equals("/api/health")
                || path.equals("/api/auth/login")
                || path.startsWith("/api/actuator/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                Claims claims = jwtService.parse(token);
                String userId = claims.getSubject();
                User u = userStore.findById(userId).orElse(null);
                if (u != null && u.isEnabled()) {
                    var auth = new UsernamePasswordAuthenticationToken(u.getId(), null, List.of());
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            } catch (Exception e) {
                // leave context empty; downstream will return 401 via authz rules
                log.debug("jwt parse failed: {}", e.getMessage());
            }
        }
        chain.doFilter(req, res);
    }
}
