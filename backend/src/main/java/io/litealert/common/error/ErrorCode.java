package io.litealert.common.error;

import lombok.Getter;

/**
 * Stable error codes returned to clients.
 *
 * <p>Each enum maps to an HTTP status; the controller advice translates
 * domain exceptions to the matching code + status.
 */
@Getter
public enum ErrorCode {

    // ---- generic ----
    INTERNAL_ERROR(500, "internal server error"),
    INVALID_PAYLOAD(400, "request payload is invalid"),
    INVALID_ARGUMENT(400, "argument validation failed"),

    // ---- auth ----
    UNAUTHORIZED(401, "authentication required"),
    INVALID_CREDENTIAL(401, "invalid username or password"),
    ACCOUNT_LOCKED(401, "account temporarily locked"),
    ACCOUNT_DISABLED(401, "account disabled"),
    FORBIDDEN(403, "permission denied"),
    TOKEN_EXPIRED(401, "token expired"),
    TOKEN_INVALID(401, "token invalid"),

    // ---- domain ----
    NOT_FOUND(404, "resource not found"),
    CONFLICT(409, "resource conflict"),
    NAMESPACE_NAME_TAKEN(409, "namespace name already taken"),
    TOPIC_NAME_TAKEN(409, "topic name already taken in this namespace"),
    NAMESPACE_NOT_EMPTY(409, "namespace still has published topics"),
    TOPIC_NOT_PUBLISHED(404, "topic is not published"),
    TOPIC_DISABLED(423, "topic is disabled"),
    NAMESPACE_DISABLED(423, "namespace is disabled"),
    SCHEMA_LOCKED(409, "inboundFormat is locked while topic is published"),

    // ---- apikey ----
    API_KEY_INVALID(401, "api key invalid"),
    API_KEY_EXPIRED(401, "api key expired"),
    API_KEY_NOT_YET_ACTIVE(401, "api key not yet active"),
    API_KEY_REVOKED(401, "api key revoked"),
    API_KEY_DISABLED_OWNER(401, "api key owner disabled"),
    SCOPE_NOT_ALLOWED(403, "api key scope does not cover this topic"),
    APIKEY_OWNED_BY_OTHER(403, "scope target is not owned by you"),
    APIKEY_REVOKED_FINAL(409, "revoked api key cannot be restored"),

    // ---- webhook ----
    PAYLOAD_TOO_LARGE(413, "payload too large"),
    RATE_LIMITED(429, "rate limit exceeded"),
    IP_NOT_ALLOWED(401, "remote ip not in whitelist"),
    SCHEMA_VALIDATION_FAILED(400, "payload does not match topic schema"),
    TRANSFORM_FAILED(400, "payload transform failed");

    private final int status;
    private final String defaultMessage;

    ErrorCode(int status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }
}
