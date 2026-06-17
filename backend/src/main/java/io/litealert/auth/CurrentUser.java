package io.litealert.auth;

import io.litealert.auth.domain.User;
import io.litealert.auth.domain.UserStore;
import io.litealert.common.error.BusinessException;
import io.litealert.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * Helpers around Spring Security's context for our domain code:
 * pulling the current user, asserting role, etc.
 */
@Component
@RequiredArgsConstructor
public class CurrentUser {

    private final UserStore userStore;

    public String idOrThrow() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return auth.getName();
    }

    public User getOrThrow() {
        String id = idOrThrow();
        return userStore.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
    }

    public boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        Collection<? extends GrantedAuthority> auths = auth.getAuthorities();
        return auths.stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    public void requireAdmin() {
        if (!isAdmin()) throw new BusinessException(ErrorCode.FORBIDDEN);
    }

    public static List<SimpleGrantedAuthority> authoritiesFor(User.Role role) {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }
}
