package io.litealert.common.error;

import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import io.litealert.common.web.TraceIdHolder;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.Map;

/**
 * Translates exceptions thrown anywhere in the request handling chain
 * to the canonical {@link ErrorResponse} envelope.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException ex) {
        ErrorCode code = ex.getCode();
        ErrorResponse body = new ErrorResponse(
                code.name(),
                ex.getMessage() != null ? ex.getMessage() : code.getDefaultMessage(),
                TraceIdHolder.current(),
                ex.getErrors()
        );
        return ResponseEntity.status(code.getStatus()).body(body);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class,
            HttpMessageNotReadableException.class, MismatchedInputException.class})
    public ResponseEntity<ErrorResponse> handleValidation(Exception ex) {
        List<BusinessException.FieldError> details = List.of();
        if (ex instanceof MethodArgumentNotValidException manve) {
            details = manve.getBindingResult().getFieldErrors().stream()
                    .map(fe -> new BusinessException.FieldError(fe.getField(), fe.getDefaultMessage()))
                    .toList();
        }
        ErrorCode code = ErrorCode.INVALID_ARGUMENT;
        ErrorResponse body = new ErrorResponse(code.name(), ex.getMessage(),
                TraceIdHolder.current(), details);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        ErrorCode code = ErrorCode.FORBIDDEN;
        return ResponseEntity.status(code.getStatus()).body(
                new ErrorResponse(code.name(), ex.getMessage(), TraceIdHolder.current(), List.of()));
    }

    /**
     * Static resource not found (e.g. browser asks for {@code /favicon.svg}
     * before the SPA bundle is built). Treat as a plain 404 — definitely not
     * an unhandled-exception ERROR-level event.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex) {
        ErrorCode code = ErrorCode.NOT_FOUND;
        return ResponseEntity.status(code.getStatus()).body(
                new ErrorResponse(code.name(),
                        "static resource not found: " + ex.getResourcePath(),
                        TraceIdHolder.current(), List.of()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleAny(Exception ex) {
        log.error("unhandled exception", ex);
        ErrorCode code = ErrorCode.INTERNAL_ERROR;
        return ResponseEntity.status(code.getStatus()).body(
                new ErrorResponse(code.name(), code.getDefaultMessage(),
                        TraceIdHolder.current(), List.of()));
    }

    public record ErrorResponse(
            String code,
            String message,
            String traceId,
            List<BusinessException.FieldError> errors
    ) {
        public Map<String, Object> asMap() {
            return Map.of("code", code, "message", message, "traceId", traceId, "errors", errors);
        }
    }
}
