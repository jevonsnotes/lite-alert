package io.litealert.auth;

import io.litealert.auth.domain.User;
import io.litealert.auth.domain.UserStore;
import io.litealert.common.error.BusinessException;
import io.litealert.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Helpers around Spring Security's context for our domain code.
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
}
