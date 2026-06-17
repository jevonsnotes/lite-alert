package io.litealert.common.error;

import lombok.Getter;

import java.util.List;

/**
 * Domain-level exception. Carries an {@link ErrorCode} (which decides the HTTP
 * status) plus optional structured details (e.g. JSON Schema violation list).
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode code;
    private final List<FieldError> errors;

    public BusinessException(ErrorCode code) {
        this(code, code.getDefaultMessage(), List.of());
    }

    public BusinessException(ErrorCode code, String message) {
        this(code, message, List.of());
    }

    public BusinessException(ErrorCode code, String message, List<FieldError> errors) {
        super(message);
        this.code = code;
        this.errors = errors == null ? List.of() : errors;
    }

    public record FieldError(String path, String message) {}
}
