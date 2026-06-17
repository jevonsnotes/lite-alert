package io.litealert.auth;

import io.litealert.common.error.BusinessException;
import io.litealert.common.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Password policy enforced on user creation and password reset.
 *
 * <p>Rules:
 * <ul>
 *   <li>≥ 8 characters</li>
 *   <li>at least three of: lowercase / uppercase / digit / symbol</li>
 *   <li>not in a tiny common-password blocklist</li>
 * </ul>
 *
 * <p>Kept tunable but not externalized — these are defaults reasonable for
 * a self-hosted internal service. If you want strictness levels later,
 * promote the thresholds to {@code lite-alert.auth.password.*}.
 */
@Component
public class PasswordPolicy {

    private static final int MIN_LEN = 8;
    private static final int MAX_LEN = 64;
    private static final int MIN_CLASSES = 3;

    /** Tiny blocklist — covers the obvious "every leak top-50" entries. */
    private static final List<String> BLOCKLIST = List.of(
            "password", "12345678", "qwertyui", "iloveyou", "admin123",
            "letmein123", "welcome1", "abc12345", "P@ssw0rd", "Passw0rd!");

    public void check(String username, String raw) {
        if (raw == null) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "password is required");
        }
        int len = raw.length();
        if (len < MIN_LEN) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "password must be at least " + MIN_LEN + " characters");
        }
        if (len > MAX_LEN) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "password must be at most " + MAX_LEN + " characters");
        }
        int classes = 0;
        if (raw.chars().anyMatch(Character::isLowerCase)) classes++;
        if (raw.chars().anyMatch(Character::isUpperCase)) classes++;
        if (raw.chars().anyMatch(Character::isDigit)) classes++;
        if (raw.chars().anyMatch(c -> !Character.isLetterOrDigit(c))) classes++;
        if (classes < MIN_CLASSES) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "password must include at least " + MIN_CLASSES
                            + " of lowercase/uppercase/digit/symbol");
        }
        String lower = raw.toLowerCase();
        for (String bad : BLOCKLIST) {
            if (lower.equals(bad.toLowerCase())) {
                throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                        "password is too common, please choose another");
            }
        }
        if (username != null && !username.isBlank()
                && lower.contains(username.toLowerCase())) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "password must not contain the username");
        }
    }

    /** Variant that bypasses checks — used by AdminBootstrap on a first-launch dev seed. */
    public void allow(String raw) {
        // explicit no-op: documents why we bypass on bootstrap
    }
}
